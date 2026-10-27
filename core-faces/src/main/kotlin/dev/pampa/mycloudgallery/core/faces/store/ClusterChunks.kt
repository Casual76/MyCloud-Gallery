package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesChunkEntity
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesSegmentContent
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.Verdict

// LE VOCI `cluster` NEL DATABASE, SIGILLATE (docs/fase8-progetto.md D14, C39). Lo stato della replica e del pubblicatore contiene persone
// dell'istantanea del custode **con il centroide** (un vettore) e le loro assegnazioni: non stanno mai in chiaro nel file. Si salvano come
// blocchi di `faces_chunk`: ogni blocco e' un segmento `cluster` del formato (`FacesDocs.clusterSegment`, lo stesso codec e le stesse regole di
// chi legge dal NAS: una lettura ostile o rovinata e' un rifiuto, mai un'eccezione), sigillato con la chiave dati e un `aad` che lo lega al suo
// posto (ambito, proprietario, nome, pezzo, riferimento). Un insieme grande si spezza in pezzi di poche centinaia di KB: Android non legge
// una riga oltre i 2 MB. Mai valori nei log (D37).

internal object ClusterChunks {

  const val REPLICA_CLUSTER = "replica.cluster"
  const val REPLICA_SNAPSHOT = "replica.snapshot"
  const val PUBLISH_SNAPSHOT = "publish.snapshot"

  /** Un insieme di voci, quello che sta sotto un unico (ambito, proprietario, nome). */
  class Key(val scope: String, val owner: String, val name: String) {
    override fun equals(other: Any?): Boolean = other is Key && other.scope == scope && other.owner == owner && other.name == name

    override fun hashCode(): Int = (scope.hashCode() * 31 + owner.hashCode()) * 31 + name.hashCode()

    override fun toString(): String = "ClusterChunks.Key($scope)"
  }

  /** Il peso approssimato di una voce (byte): una persona ha il centroide (256) e fino a 16 esemplari (42 l'uno), un'assegnazione e' piccola. */
  private fun weight(entry: ClusterEntry): Int = if (entry is ClusterEntry.Person) PERSON_WEIGHT else ASSIGNMENT_WEIGHT

  /** Spezza [entries] in pezzi di circa [PART_BYTES] byte e al piu' [PART_ENTRIES] voci. Mai un pezzo vuoto. */
  fun split(entries: List<ClusterEntry>): List<List<ClusterEntry>> {
    val parts = ArrayList<List<ClusterEntry>>()
    var current = ArrayList<ClusterEntry>()
    var bytes = 0
    for (entry in entries) {
      current += entry
      bytes += weight(entry)
      if (bytes >= PART_BYTES || current.size >= PART_ENTRIES) {
        parts += current
        current = ArrayList()
        bytes = 0
      }
    }
    if (current.isNotEmpty()) parts += current
    return parts
  }

  /** Le righe di [entries] sotto [key], sigillate con [dataKey]; nessuna riga per un elenco vuoto. */
  fun encode(key: Key, entries: List<ClusterEntry>, dataKey: FacesDataKey): List<FacesChunkEntity> =
    split(entries).mapIndexed { part, piece ->
      val segment = FacesDocs.clusterSegment(piece)
      val sealed = dataKey.seal(segment.bytes, aad(key, part, segment.ref.hash, segment.ref.size))
      FacesChunkEntity(key.scope, key.owner, key.name, part, segment.ref.hash, segment.ref.size, sealed)
    }

  /**
   * Le voci di un insieme, dalle sue righe (in ordine di pezzo), o null se anche un solo pezzo non si apre, non si decodifica o non e' la
   * classe `cluster`: un insieme a meta' non vale (chi chiama lo ripete o lo riscarica).
   */
  fun decode(key: Key, rows: List<FacesChunkEntity>, dataKey: FacesDataKey): List<ClusterEntry>? {
    val out = ArrayList<ClusterEntry>()
    for ((expected, row) in rows.sortedBy { it.part }.withIndex()) {
      if (row.part != expected) return null
      val plain = dataKey.open(row.data, aad(key, row.part, row.encHash, row.encSize)) ?: return null
      val verdict = try {
        FacesDocs.decodeSegment(plain, SegmentRef(row.encHash, row.encSize), SegmentClass.CLUSTER)
      } catch (e: RuntimeException) {
        GalleryLog.w(TAG) { "un blocco di voci cluster non si legge (${e.javaClass.simpleName})" }
        return null
      }
      val content = (verdict as? Verdict.Accepted)?.value as? FacesSegmentContent.Cluster ?: return null
      out += content.entries
    }
    return out
  }

  private fun aad(key: Key, part: Int, encHash: String, encSize: Long): ByteArray =
    "mcg-faces/v1/faces_chunk.data|${key.scope}|${key.owner}|${key.name}|$part|$encHash|$encSize".toByteArray(Charsets.UTF_8)

  private const val TAG = "FacesChunks"
  private const val PART_BYTES = 192 * 1024
  private const val PART_ENTRIES = 4_096
  private const val PERSON_WEIGHT = 1_100
  private const val ASSIGNMENT_WEIGHT = 64
}
