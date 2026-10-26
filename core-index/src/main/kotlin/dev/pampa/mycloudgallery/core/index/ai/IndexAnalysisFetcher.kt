package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.index.contract.BlobScope
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.RemoteIndex
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.index.replica.PathKeys
import dev.pampa.mycloudgallery.core.index.replica.store.HeadRow
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentRow
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * Lo scaricamento delle anteprime dall'indice di una share nel buffer degli indicizzatori (D10; contratto [AnalysisFetcher]):
 * **solo** `analysis`, `frames` e `hq`, **solo** blob attestati, traffico registrato, mai i byte originali.
 *
 * - **Prima la replica locale** ([local]): l'HQ gia' scaricata al livello Alta non costa traffico.
 * - **Poi il NAS**: i segmenti di quelle classi elencati dalle teste `index` **accettate** della share (la replica le conosce anche
 *   per le classi che non scarica: stato "saltato"), per dispositivo (id minore prima) e impronta. Ogni segmento si scarica intero e
 *   si verifica ([RemoteIndex.segment]: dimensione e impronta della testa firmata, tabella, immagini con `ImageBounds`): un pezzo di
 *   un segmento non si puo' verificare, quindi niente letture parziali. Un segmento gia' visto in questo processo e senza chiavi
 *   utili non si riscarica (per impronta: non cambia mai); l'ultimo scaricato resta in memoria per il lotto successivo.
 * - **Attestazione** (R1 della 4A): un blob vale solo se **il suo** dispositivo ha, nella stessa share, un record `meta` presente con
 *   la stessa chiave di contenuto **intera** ([ReplicaStore.presentContentKeys]); un blob di un'altra share non si guarda nemmeno.
 * - **Traffico**: i byte letti dal NAS vanno a [traffic] (D10, "traffico delle anteprime registrato").
 *
 * - **Provenienza** (revisione A, R5): ogni blob arriva al buffer con la share e il dispositivo che l'ha preparato
 *   ([AiBufferSink.putFrom]; l'HQ della replica locale con il dispositivo non noto); un blob preparato da questo stesso dispositivo
 *   ([selfDevice]) e' proprio ([AiBufferSink.put]).
 *
 * Mai byte remoti ai decodificatori nativi: chi usa il buffer passa da `ModelInputs` di core-ai (D5).
 */
class IndexAnalysisFetcher(
  private val remote: RemoteIndex,
  private val replica: ReplicaStore,
  private val traffic: AiTraffic,
  private val local: ReplicaBlobs? = null,
  private val ticker: () -> Long = System::nanoTime,
  /** Byte di segmenti letti dal NAS al massimo per chiamata: un lotto non scarica gigabyte. */
  private val maxBytesPerFetch: Long = DEFAULT_MAX_BYTES_PER_FETCH,
  /** R5: l'id di questo dispositivo (le sue anteprime sono proprie); null = nessuna lo e'. */
  private val selfDevice: String? = null,
) : AnalysisFetcher {

  /** Segmenti scaricati dal NAS (per i test). */
  val segmentReads = AtomicInteger()

  /** Byte letti dal NAS (gli stessi registrati in [traffic]). */
  val bytesRead = AtomicLong()

  private val lock = Any()

  /** Le chiavi di ogni segmento gia' scaricato (per impronta, con un tetto): si salta senza riscaricarlo. */
  private val seen = object : LinkedHashMap<String, Set<ContentKey>>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<ContentKey>>?): Boolean = size > MAX_SEEN_SEGMENTS
  }

  /** L'ultimo segmento scaricato (i lotti di un'unita' cadono spesso nello stesso segmento). */
  private var last: Pair<String, SegmentContent.Blobs>? = null

  override suspend fun fetch(
    share: String,
    keys: List<ContentKey>,
    classes: Set<SegmentClass>,
    into: AiBufferSink,
    budget: Duration,
  ): FetchReport {
    require(classes.all { it in AnalysisFetcher.FETCHABLE }) { "Solo analysis, frames e hq." }
    val start = ticker()
    val budgetNanos = if (budget.isInfinite()) Long.MAX_VALUE else budget.inWholeNanoseconds
    fun timeUp() = ticker() - start >= budgetNanos

    val wanted = LinkedHashSet<Pair<ContentKey, SegmentClass>>()
    for (cls in ORDER) if (cls in classes) keys.distinct().forEach { wanted += it to cls }
    var fetched = 0
    var bytes = 0L

    // 1. La replica locale (HQ al livello Alta): nessun traffico.
    if (local != null) {
      for (pair in wanted.toList()) {
        val (key, cls) = pair
        val location = local.locate(key.short, cls, BlobScope(listOf(share), key)) ?: continue
        val buffer = try {
          local.read(location)
        } catch (e: IOException) {
          continue
        }
        val blob = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (!into.putFrom(key, cls, blob, share, null)) return FetchReport(fetched, 0, bytes, stoppedEarly = true)
        fetched++
        wanted -= pair
      }
    }

    // 2. Il NAS: i segmenti delle teste `index` accettate, per classe, dispositivo e impronta.
    val shareKey = PathKeys.share(share)
    val heads = replica.heads(shareKey).sortedBy { it.deviceId }
    val finished = HashSet<SegmentClass>()
    var stopped = false
    classes@ for (cls in ORDER) {
      if (cls !in classes) continue
      for (head in heads) {
        if (wanted.none { it.second == cls }) break
        val segments = replica.segments(shareKey, head.deviceId)
          .filter { it.listed && it.cls == cls && it.state != SegmentState.MISSING && it.state != SegmentState.REJECTED }
          .sortedBy { it.hash }
        if (segments.isEmpty()) continue
        // R1: solo le chiavi per cui questo dispositivo ha un record presente nella share.
        val present = replica.presentContentKeys(shareKey, head.deviceId)
        for (segment in segments) {
          val need = wanted.filter { it.second == cls && it.first in present }.mapTo(HashSet()) { it.first }
          if (need.isEmpty()) break
          val known = synchronized(lock) { seen[segment.hash] }
          if (known != null && known.none { it in need }) continue
          var content = synchronized(lock) { last?.takeIf { it.first == segment.hash }?.second }
          if (content == null) {
            if (timeUp() || bytes + segment.size > maxBytesPerFetch) {
              stopped = true
              break@classes
            }
            when (val read = remote.segment(view(share, head, segment), segment.ref)) {
              // Il NAS non risponde: il resto al lotto dopo (le chiavi non sono "mancanti").
              is RemoteResult.Failed -> {
                stopped = true
                break@classes
              }
              is RemoteResult.Ok -> {
                bytes += segment.size
                bytesRead.addAndGet(segment.size)
                segmentReads.incrementAndGet()
                traffic.record(segment.size)
                content = (read.value as? SegmentFetch.Ok)?.content as? SegmentContent.Blobs
              }
            }
          }
          // Assente, rifiutato o di un'altra classe: nessuna voce utile, non si riprova in questo processo.
          if (content == null || content.cls != cls) {
            synchronized(lock) { seen[segment.hash] = emptySet() }
            continue
          }
          synchronized(lock) {
            seen[segment.hash] = content.entries.mapTo(HashSet()) { it.contentKey }
            last = segment.hash to content
          }
          for (entry in content.entries) {
            if (entry.contentKey !in need) continue
            val pair = entry.contentKey to cls
            if (pair !in wanted) continue
            val blob = content.bytes.copyOfRange(entry.offset, entry.offset + entry.length)
            val stored = if (head.deviceId == selfDevice) into.put(entry.contentKey, cls, blob) else into.putFrom(entry.contentKey, cls, blob, share, head.deviceId)
            if (!stored) {
              stopped = true
              break@classes
            }
            fetched++
            wanted -= pair
          }
        }
      }
      finished += cls
    }
    // Mancanti: le chiavi delle classi guardate fino in fondo senza un blob attestato ("in attesa della preparazione").
    val missing = wanted.count { it.second in finished }
    return FetchReport(fetched, missing, bytes, stoppedEarly = stopped)
  }

  /** La testa accettata, dalla replica, per riscaricare un segmento senza rileggerla. */
  private fun view(share: String, head: HeadRow, segment: SegmentRow) = IndexHeadView(
    share = share,
    deviceId = head.deviceId,
    version = head.version,
    registryVersion = head.registryVersion,
    createdAtNasMillis = head.createdAtNasMillis,
    mark = head.mark,
    segments = listOf(segment.ref),
    registryBehind = false,
  )

  companion object {
    /** L'ordine delle classi: prima l'anteprima di analisi (il vettore), poi i fotogrammi, poi l'HQ (seconda passata dell'OCR). */
    val ORDER: List<SegmentClass> = listOf(SegmentClass.ANALYSIS, SegmentClass.FRAMES, SegmentClass.HQ)

    const val DEFAULT_MAX_BYTES_PER_FETCH: Long = 256L * 1024 * 1024

    private const val MAX_SEEN_SEGMENTS = 4096
  }
}
