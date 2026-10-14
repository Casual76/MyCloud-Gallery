package dev.pampa.mycloudgallery.core.index.compact

import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteIndex
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.Nas
import dev.pampa.mycloudgallery.core.index.publish.OwnHeadWriter
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.index.publish.nasCall
import dev.pampa.mycloudgallery.core.index.store.IndexPublishStore
import dev.pampa.mycloudgallery.core.index.store.LiveBlob
import dev.pampa.mycloudgallery.core.index.store.LiveRecord
import dev.pampa.mycloudgallery.core.index.store.OwnHead
import dev.pampa.mycloudgallery.core.index.store.OwnSegment
import dev.pampa.mycloudgallery.core.index.store.PublishCommit
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.MetaRecords
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.data.SegmentEntry
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Com'e' andata una compattazione. */
sealed interface CompactOutcome {
  /** Spenta: nella 4A la compattazione e' scritta ma non accesa (si accende con la pulizia dei propri file, 4B). */
  data object Disabled : CompactOutcome

  data object NotNeeded : CompactOutcome

  data class Blocked(val block: WriteBlock) : CompactOutcome

  /** M3: una testa ogni 5 minuti per share. */
  data class TooSoon(val retryAtNasMillis: Long) : CompactOutcome

  /** Testa nuova [headVersion]: [removed] segmenti tolti dalla testa, [added] scritti. */
  data class Compacted(val headVersion: Long, val removed: Int, val added: Int) : CompactOutcome

  data class Failed(val failure: RemoteFailure) : CompactOutcome

  /**
   * Un segmento proprio da rileggere non c'e' piu' o e' rifiutato ([reason]; null = lo stato locale non coincide con
   * il NAS): non si pubblica niente, perche' la testa nuova perderebbe dati (E-I17).
   */
  data class Aborted(val reason: RejectReason?) : CompactOutcome
}

/**
 * Quando e cosa compattare.
 *
 * @property minSegments sotto questo numero di segmenti nella testa si compatta solo se c'e' un segmento quasi vuoto.
 * @property maxLiveRatio un segmento con meno di questa frazione di voci vive e' da riscrivere.
 * @property smallSegmentBytes i segmenti piu' piccoli si uniscono (almeno due della stessa classe).
 * @property maxInputBytes byte di segmenti riletti al massimo per giro.
 */
data class CompactionPolicy(
  val minSegments: Int = 64,
  val maxLiveRatio: Double = 0.5,
  val smallSegmentBytes: Long = TrustLimits.SEGMENT_TARGET_BYTES / 4L,
  val maxInputBytes: Long = 256L * 1024 * 1024,
)

/**
 * La compattazione dell'indice proprio in una share (D5; docs/fase4-progetto.md §1.2, scritta in P3 e accesa nella 4B).
 *
 * Lo stato di un dispositivo e' funzione pura della sua testa: per ogni percorso vince il `seq` piu' alto, le lapidi
 * tolgono, i blob valgono per (classe, chiave di contenuto). La compattazione riscrive le voci **vive** dei segmenti
 * scelti in segmenti nuovi (stessi record con gli stessi `seq`, stessi byte dei blob) e pubblica una testa che elenca i
 * nuovi al posto dei vecchi: **lo stato non cambia** (test `CompactorTest`). Le lapidi si tolgono solo se si riscrivono
 * tutti i segmenti `meta` (altrimenti un record piu' vecchio in un segmento non riscritto tornerebbe vivo).
 *
 * I segmenti si rileggono dal NAS (verificati con il codec, come quelli degli altri), uno alla volta: in memoria al
 * piu' un segmento letto e uno in scrittura. I vecchi restano sul NAS: li toglie la pulizia dei propri file (4B).
 * Stesse regole della pubblicazione: [IndexWriteRules] (D31, congelamento, salto dell'ora), registro, una testa ogni
 * 5 minuti, e lo stesso lucchetto per share ([ShareLocks]).
 */
class Compactor(
  private val identity: IndexIdentity,
  private val clock: NasClock,
  private val fileSystem: (String) -> NasFileSystem,
  private val store: IndexPublishStore,
  private val remote: RemoteIndex,
  private val rules: IndexWriteRules,
  private val locks: ShareLocks,
  private val enabled: Boolean,
  private val policy: CompactionPolicy = CompactionPolicy(),
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {

  private val heads = OwnHeadWriter(identity, io, AtomicInteger())

  suspend fun compactIfNeeded(share: String): CompactOutcome = locks.withLock(share) { compactLocked(share) }

  private suspend fun compactLocked(share: String): CompactOutcome {
    if (!enabled) return CompactOutcome.Disabled
    rules.blockFor(share)?.let { return CompactOutcome.Blocked(it) }
    val registry = identity.writableRegistry() ?: return CompactOutcome.Blocked(WriteBlock.NOT_TRUSTED)
    val own = store.ownHead(share) ?: return CompactOutcome.NotNeeded
    val chosen = choose(own, store.liveCounts(share))
    if (chosen.isEmpty()) return CompactOutcome.NotNeeded
    val now = clock.nowMillis()
    val next = own.publishedAtNas + TrustLimits.HEAD_MIN_INTERVAL_MILLIS
    if (now < next) return CompactOutcome.TooSoon(next)

    val fs = fileSystem(share)
    val view = IndexHeadView(fs.shareName, identity.deviceId, own.version, registry.registry.version, own.publishedAtNas, own.mark, own.classed, false)
    val chosenHashes = chosen.mapTo(HashSet()) { it.hash }
    val metaChosen = chosen.filter { it.segment.cls == SegmentClass.META }
    val fullMeta = metaChosen.size == own.segments.count { it.segment.cls == SegmentClass.META }
    val liveRecords = store.liveRecordsIn(share, chosenHashes).associateBy { it.pathKey }
    val liveBlobs = store.liveBlobsIn(share, chosenHashes).associateBy { it.cls to it.contentKey }

    val writer = Writer(fs)
    val moved = ArrayList<Pair<LiveRecord, Int>>()
    val movedBlobs = ArrayList<Pair<LiveBlob, Int>>()
    val dropped = HashSet<String>()
    val seenRecords = HashSet<String>()
    val seenBlobs = HashSet<Pair<SegmentClass, ContentKey>>()

    for (segment in chosen) {
      val content = when (val r = remote.segment(view, segment.segment)) {
        is RemoteResult.Failed -> return CompactOutcome.Failed(r.failure)
        is RemoteResult.Ok -> when (val fetch = r.value) {
          is SegmentFetch.Ok -> fetch.content
          SegmentFetch.Missing -> return CompactOutcome.Aborted(null)
          is SegmentFetch.Rejected -> return CompactOutcome.Aborted(fetch.reason)
        }
      }
      when (content) {
        is SegmentContent.Meta -> for (record in content.records) {
          val key = IndexKeys.metaHex(record.path)
          val live = liveRecords[key] ?: continue
          if (live.segment != segment.hash || live.seq != record.seq) continue
          seenRecords += key
          if (record is MetaRecord.Gone && fullMeta) {
            dropped += key
            continue
          }
          val index = writer.add(SegmentClass.META, SegmentEntry(IndexKeys.meta(record.path), MetaRecords.encode(record))) ?: return writer.failure()
          moved += live to index
        }
        is SegmentContent.Blobs -> for (entry in content.entries) {
          val key = content.cls to entry.contentKey
          val live = liveBlobs[key] ?: continue
          if (live.segment != segment.hash) continue
          seenBlobs += key
          val body = content.bytes.copyOfRange(entry.offset, entry.offset + entry.length)
          val index = writer.add(content.cls, SegmentEntry(IndexKeys.content(entry.contentKey), body)) ?: return writer.failure()
          movedBlobs += live to index
        }
      }
    }
    // Ogni voce viva dei segmenti scelti deve essere stata ritrovata: altrimenti lo stato locale non e' quello del NAS.
    if (seenRecords.size != liveRecords.size || seenBlobs.size != liveBlobs.size) return CompactOutcome.Aborted(null)
    if (!writer.flushAll()) return writer.failure()

    val outputs = writer.published
    val fresh = outputs
    val listed = (own.segments.filter { it.hash !in chosenHashes } + fresh).distinctBy { it.hash }.sortedBy { it.hash }
    val head = when (val result = heads.publish(fs, registry, own.version, listed.map { it.segment }, now)) {
      is OwnHeadWriter.Result.Done -> result
      is OwnHeadWriter.Result.Failed -> return CompactOutcome.Failed(result.failure)
    }
    store.commit(
      share,
      PublishCommit(
        head = OwnHead(head.version, head.mark, now, listed),
        consumedRecords = emptySet(),
        consumedBlobs = emptySet(),
        newSegments = fresh,
        records = moved.map { (live, index) -> live.copy(segment = outputs[index].hash) },
        blobs = movedBlobs.map { (live, index) -> live.copy(segment = outputs[index].hash) },
        droppedPaths = dropped,
      ),
    )
    return CompactOutcome.Compacted(head.version, removed = chosen.size, added = outputs.size)
  }

  /** I segmenti da riscrivere: quasi vuoti, oppure piccoli (almeno due della stessa classe), entro il tetto del giro. */
  private fun choose(own: OwnHead, live: Map<String, Int>): List<OwnSegment> {
    fun ratio(s: OwnSegment): Double = (live[s.hash] ?: 0).toDouble() / s.entries.coerceAtLeast(1)
    val sparse = own.segments.any { ratio(it) < policy.maxLiveRatio }
    if (!sparse && own.segments.size < policy.minSegments) return emptyList()
    val chosen = ArrayList<OwnSegment>()
    var bytes = 0L
    for ((_, segments) in own.segments.groupBy { it.segment.cls }) {
      val candidates = segments
        .filter { ratio(it) < policy.maxLiveRatio || it.segment.ref.size < policy.smallSegmentBytes }
        .sortedBy { ratio(it) }
      if (candidates.size < 2 && candidates.none { ratio(it) < policy.maxLiveRatio }) continue
      for (segment in candidates) {
        if (chosen.isNotEmpty() && bytes + segment.segment.ref.size > policy.maxInputBytes) break
        chosen += segment
        bytes += segment.segment.ref.size
      }
    }
    return chosen
  }

  /** Accumula le voci per classe e pubblica un segmento quando e' pieno (stesse soglie della pubblicazione). */
  private inner class Writer(private val fs: NasFileSystem) {
    /** I segmenti scritti (solo riferimenti: i byte si lasciano andare appena scritti). */
    val published = ArrayList<OwnSegment>()
    private val pending = LinkedHashMap<SegmentClass, ArrayList<SegmentEntry>>()
    private val pendingBytes = HashMap<SegmentClass, Long>()
    private var lastFailure: RemoteFailure? = null

    /** Aggiunge una voce; restituisce l'indice (in [published]) del segmento che la conterra', o null se il NAS ha fallito. */
    suspend fun add(cls: SegmentClass, entry: SegmentEntry): Int? {
      val entries = pending.getOrPut(cls) { ArrayList() }
      val bytes = pendingBytes[cls] ?: 0L
      if (entries.isNotEmpty() &&
        (entries.size >= TrustLimits.MAX_SEGMENT_ENTRIES || IndexSegments.sizeOf(entries.size + 1, bytes + entry.body.size) > TrustLimits.SEGMENT_TARGET_BYTES)
      ) {
        if (!flush(cls)) return null
      }
      pending.getOrPut(cls) { ArrayList() } += entry
      pendingBytes[cls] = (pendingBytes[cls] ?: 0L) + entry.body.size
      // L'indice che il segmento avra' quando sara' scritto: quelli gia' scritti + quelli in attesa prima di lui.
      return indexOf(cls)
    }

    private val reserved = HashMap<SegmentClass, Int>()

    private fun indexOf(cls: SegmentClass): Int = reserved.getOrPut(cls) { nextIndex++ }

    private var nextIndex = 0
    private val slots = HashMap<Int, OwnSegment>()

    private suspend fun flush(cls: SegmentClass): Boolean {
      val entries = pending.remove(cls) ?: return true
      pendingBytes.remove(cls)
      val index = reserved.remove(cls) ?: return true
      val encoded = IndexSegments.encode(cls, entries)
      val path = NasLayout.segmentFile(identity.deviceId, HeadKind.INDEX.wire, encoded.ref.hash)
      return when (val r = nasCall(io) { fs.publishExclusive(path, encoded.bytes) }) {
        is Nas.Ok -> {
          slots[index] = OwnSegment(encoded.classed, encoded.entries)
          true
        }
        is Nas.Failed -> {
          lastFailure = r.failure
          false
        }
      }
    }

    suspend fun flushAll(): Boolean {
      for (cls in pending.keys.toList()) if (!flush(cls)) return false
      published.clear()
      published += (0 until nextIndex).map { slots.getValue(it) }
      return true
    }

    fun failure(): CompactOutcome = CompactOutcome.Failed(lastFailure ?: RemoteFailure.OTHER)
  }
}
