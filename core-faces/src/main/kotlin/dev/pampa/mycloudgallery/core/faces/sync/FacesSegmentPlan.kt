package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.EncodedSegment
import dev.pampa.mycloudgallery.core.trust.data.FacesBody
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesLimits
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.PeopleDoc

/**
 * Le voci del proprio contributo divise in segmenti **stabili** (§2.4 "Pubblicazione", come i dati personali): le voci si raggruppano per i
 * primi bit della loro chiave, quindi un segmento dipende solo dalle voci che ci cadono. Una voce che cambia riscrive un segmento solo; uno
 * che non cambia ha lo stesso nome (la sua impronta) e non si ripubblica. Il numero di bit dipende da quante sono le voci, non da come
 * cadono; un gruppo troppo grande (chiavi scelte apposta) si spezza per numero e per byte, cosi' ogni segmento resta sotto i tetti.
 */
internal object FacesSegmentPlan {
  /** Voci per segmento, per classe: i corpi `faces` sono i piu' grandi (fino a 18.630 byte). */
  const val BODIES_TARGET: Int = 512
  const val PEOPLE_TARGET: Int = 1024
  const val CLUSTER_TARGET: Int = 4096

  /** Un segmento si chiude a questi byte di corpi (sotto i 12 MiB del formato). */
  const val TARGET_SEGMENT_BYTES: Long = 8L * 1024 * 1024

  private const val MAX_BUCKET_BITS = 12
  private const val TABLE_ROW_BYTES = 36

  fun bodies(items: List<Pair<ContentKey, FacesBody>>): List<EncodedSegment> =
    plan(items, { it.first.bytes() }, { bodySize(it.second) }, BODIES_TARGET) { FacesDocs.bodiesSegment(it) }

  fun people(docs: List<PeopleDoc>): List<EncodedSegment> = plan(docs, { it.key }, { PEOPLE_ENTRY_ESTIMATE }, PEOPLE_TARGET) { FacesDocs.peopleSegment(it) }

  fun cluster(entries: List<ClusterEntry>): List<EncodedSegment> = plan(entries, { it.key }, ::clusterSize, CLUSTER_TARGET) { FacesDocs.clusterSegment(it) }

  private fun <T> plan(items: List<T>, key: (T) -> ByteArray, size: (T) -> Int, target: Int, encode: (List<T>) -> EncodedSegment): List<EncodedSegment> {
    if (items.isEmpty()) return emptyList()
    val keyed = items.map { key(it) to it }.sortedWith { a, b -> IndexKeys.compare(a.first, b.first) }
    val bits = bucketBits(keyed.size, target)
    val out = ArrayList<EncodedSegment>()
    var start = 0
    while (start < keyed.size) {
      val bucket = bucketOf(keyed[start].first, bits)
      var end = start + 1
      while (end < keyed.size && bucketOf(keyed[end].first, bits) == bucket) end++
      var from = start
      var bytes = 0L
      for (i in start until end) {
        val rowBytes = size(keyed[i].second).toLong() + TABLE_ROW_BYTES
        if (i > from && (i - from >= target || bytes + rowBytes > TARGET_SEGMENT_BYTES)) {
          out += encode(keyed.subList(from, i).map { it.second })
          from = i
          bytes = 0
        }
        bytes += rowBytes
      }
      out += encode(keyed.subList(from, end).map { it.second })
      start = end
    }
    return out
  }

  private fun bucketBits(entries: Int, target: Int): Int {
    var bits = 0
    while (bits < MAX_BUCKET_BITS && entries.toLong() > (target.toLong() / 2) shl bits) bits++
    return bits
  }

  private fun bucketOf(key: ByteArray, bits: Int): Int {
    if (bits == 0) return 0
    val top = ((key[0].toInt() and 0xff) shl 8) or (key[1].toInt() and 0xff)
    return top ushr (16 - bits)
  }

  private fun bodySize(body: FacesBody): Int =
    FacesLimits.BODY_HEADER_BYTES + body.faces.sumOf { FacesLimits.FACE_BYTES + if (it.tier.hasVector) FacesLimits.VECTOR_BYTES else 0 }

  private fun clusterSize(entry: ClusterEntry): Int = when (entry) {
    is ClusterEntry.Person -> FacesLimits.PERSON_FIXED_BYTES + FacesLimits.REF_BYTES * entry.exemplars.size
    is ClusterEntry.Assignment -> FacesLimits.ASSIGNMENT_BYTES
  }

  /** Una voce `people` e' al piu' qualche centinaio di byte (localizzatori, nomi): il tetto per byte non scatta prima di quello per numero. */
  private const val PEOPLE_ENTRY_ESTIMATE = 400
}
