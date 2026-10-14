package dev.pampa.mycloudgallery.core.index.testing

import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteIndex
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.data.BlobEntry
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * L'indice degli altri dispositivi **gia' verificato**, in memoria (P0): teste e segmenti come oggetti, senza codec ne'
 * firme. Serve a P3 (unita' di lavoro: "cio' che altri hanno gia' pubblicato") e a P4 (sincronizzazione, fusione D6,
 * adozione D7, E-I5, E-I7, E-I15, E-I17) per lavorare senza il codec di P3. I casi ostili veri (byte malformati,
 * firme) si provano sul codec di P3 e sul NAS finto; qui si programmano gli **esiti** ([rejectHead], [rejectSegment],
 * [dropSegment], [failWith]).
 *
 * Ogni [publish] crea una testa `version + 1` che elenca, come vuole D5, **tutti** i segmenti validi del dispositivo:
 * quelli della testa precedente (se [keepPrevious]) piu' quelli nuovi.
 */
class InMemoryRemoteIndex(
  /** L'ora del NAS con cui si datano le teste. */
  var nasNow: () -> Long = { 1_791_936_000_000L },
) : RemoteIndex {

  private val lock = Any()
  private val heads = HashMap<Pair<String, String>, MutableList<IndexHeadView>>()
  private val segments = HashMap<String, SegmentContent>()
  private val headRejections = HashMap<Pair<String, String>, RejectReason>()
  private val segmentRejections = HashMap<String, RejectReason>()
  private val dropped = HashSet<String>()

  /** Se non null, ogni lettura fallisce cosi' (NAS spento, share negata...). */
  @Volatile var failWith: RemoteFailure? = null

  val deviceListings = AtomicInteger(0)
  val headReads = AtomicInteger(0)
  val segmentReads = AtomicInteger(0)

  /** Pubblica per [deviceId] in [share] una testa nuova con [contents] (piu' i segmenti precedenti se [keepPrevious]). */
  fun publish(
    share: String,
    deviceId: String,
    contents: List<SegmentContent>,
    keepPrevious: Boolean = true,
    registryVersion: Long = 1,
  ): IndexHeadView = synchronized(lock) {
    val list = heads.getOrPut(key(share) to deviceId) { mutableListOf() }
    val version = (list.lastOrNull()?.version ?: 0) + 1
    val fresh = contents.mapIndexed { i, content ->
      val hash = Hashes.sha256Name("$share|$deviceId|$version|$i|${content.cls.wire}".toByteArray(Charsets.UTF_8))
      segments[hash] = content
      ClassedSegment(content.cls, SegmentRef(hash, sizeOf(content)))
    }
    val previous = if (keepPrevious) list.lastOrNull()?.segments.orEmpty() else emptyList()
    val head = IndexHeadView(
      share = share,
      deviceId = deviceId,
      version = version,
      registryVersion = registryVersion,
      createdAtNasMillis = nasNow(),
      mark = HeadMark(version, Hashes.sha256Name("head|$share|$deviceId|$version".toByteArray(Charsets.UTF_8))),
      segments = (previous + fresh).sortedBy { it.ref.hash },
      registryBehind = false,
    )
    list += head
    head
  }

  /**
   * Comodita' per il pubblicatore finto: record e blob di questo dispositivo diventano un segmento `meta` e un segmento
   * per ogni classe d'immagine (voci in ordine di chiave, come il formato). Nessun codec: solo i tipi.
   */
  fun publishOwn(share: String, deviceId: String, records: List<MetaRecord>, blobs: List<BlobPut>): IndexHeadView {
    val contents = ArrayList<SegmentContent>()
    if (records.isNotEmpty()) contents += SegmentContent.Meta(records)
    blobs.groupBy { it.cls }.toSortedMap(compareBy { it.code }).forEach { (cls, puts) ->
      val body = ByteArrayOutputStream()
      val entries = puts.sortedBy { it.contentKey.hex }.map { put ->
        val bytes = when (put) {
          is BlobPut.Image -> put.bytes
          is BlobPut.Frames -> framesBody(put)
        }
        val entry = BlobEntry(put.contentKey, body.size(), bytes.size)
        body.write(bytes)
        entry
      }
      contents += SegmentContent.Blobs(cls, entries, body.toByteArray())
    }
    return publish(share, deviceId, contents)
  }

  /** La prossima lettura della testa di [deviceId] in [share] sara' rifiutata per [reason] (revocato, firma...). */
  fun rejectHead(share: String, deviceId: String, reason: RejectReason?) {
    synchronized(lock) {
      if (reason == null) headRejections.remove(key(share) to deviceId) else headRejections[key(share) to deviceId] = reason
    }
  }

  /** Il segmento [hash] risultera' rifiutato (E-I14). */
  fun rejectSegment(hash: String, reason: RejectReason) {
    synchronized(lock) { segmentRejections[hash] = reason }
  }

  /** Il segmento [hash] sparisce dal NAS pur restando elencato (E-I17). */
  fun dropSegment(hash: String) {
    synchronized(lock) { dropped += hash }
  }

  /** Tutto l'indice di una share cancellato dal PC (E-I5): le teste non si trovano piu'. */
  fun wipeShare(share: String) {
    synchronized(lock) { heads.keys.removeAll { it.first == key(share) } }
  }

  override suspend fun devices(share: String): RemoteResult<List<String>> {
    deviceListings.incrementAndGet()
    failWith?.let { return RemoteResult.Failed(it) }
    return synchronized(lock) {
      RemoteResult.Ok(heads.keys.filter { it.first == key(share) }.map { it.second }.sorted())
    }
  }

  override suspend fun latestHead(share: String, deviceId: String, previous: HeadMark?): RemoteResult<HeadFetch> {
    headReads.incrementAndGet()
    failWith?.let { return RemoteResult.Failed(it) }
    return synchronized(lock) {
      headRejections[key(share) to deviceId]?.let { return@synchronized RemoteResult.Ok(HeadFetch.Rejected(it)) }
      val latest = heads[key(share) to deviceId]?.lastOrNull()
      val fetch = when {
        latest == null && previous == null -> HeadFetch.None
        latest == null -> HeadFetch.Rejected(RejectReason.ROLLBACK)
        previous == null -> HeadFetch.Accepted(latest)
        latest.version < previous.version -> HeadFetch.Rejected(RejectReason.ROLLBACK)
        latest.version == previous.version && latest.mark.contentHash != previous.contentHash -> HeadFetch.Rejected(RejectReason.EQUIVOCATION)
        latest.version == previous.version -> HeadFetch.NoChange
        else -> HeadFetch.Accepted(latest)
      }
      RemoteResult.Ok(fetch)
    }
  }

  override suspend fun segment(head: IndexHeadView, segment: ClassedSegment): RemoteResult<SegmentFetch> {
    segmentReads.incrementAndGet()
    failWith?.let { return RemoteResult.Failed(it) }
    return synchronized(lock) {
      val hash = segment.ref.hash
      val fetch = when {
        hash in dropped -> SegmentFetch.Missing
        hash in segmentRejections -> SegmentFetch.Rejected(segmentRejections.getValue(hash))
        else -> segments[hash]?.let { content ->
          if (content.cls != segment.cls) SegmentFetch.Rejected(RejectReason.WRONG_TYPE) else SegmentFetch.Ok(content)
        } ?: SegmentFetch.Missing
      }
      RemoteResult.Ok(fetch)
    }
  }

  private companion object {
    fun key(share: String): String = share.lowercase(Locale.ROOT)

    fun sizeOf(content: SegmentContent): Long = when (content) {
      is SegmentContent.Meta -> (content.records.size * 256L).coerceAtLeast(1)
      is SegmentContent.Blobs -> content.bytes.size.toLong().coerceAtLeast(1)
    }

    /** Il corpo `frames` del formato (§5.5-bis): conteggio, poi per ogni fotogramma istante (4), lunghezza (4) e JPEG. */
    fun framesBody(put: BlobPut.Frames): ByteArray {
      val out = ByteArrayOutputStream()
      out.write(put.frames.size)
      for (frame in put.frames) {
        out.write(int(frame.atMs))
        out.write(int(frame.jpeg.size))
        out.write(frame.jpeg)
      }
      return out.toByteArray()
    }

    fun int(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
  }
}
