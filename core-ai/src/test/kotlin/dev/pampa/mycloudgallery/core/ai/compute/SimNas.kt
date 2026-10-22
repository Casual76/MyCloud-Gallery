package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.index.ai.AiBufferSink
import dev.pampa.mycloudgallery.core.index.ai.AiDoneClasses
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiIndexPublisher
import dev.pampa.mycloudgallery.core.index.ai.AiPendingCounts
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.index.ai.FetchReport
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.trust.data.AiBody
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

// IL NAS FINTO del calcolo AI (P5, §5.1 "NAS finto"): le share con i loro media (catalogo uguale per tutti i dispositivi), le
// anteprime attestate (`analysis`, `frames`, `hq` pubblicate da chi ha preparato), le teste `ai` di ogni dispositivo, le
// prenotazioni con l'ora del NAS e i guasti. Fedele ai contratti di P2 (attestazione, M3, blocchi permanenti, corpi uguali non
// ripetuti, "fatto" con ricetta uguale o piu' nuova) senza firme ne' file: quelli li prova P2.

/** I guasti: un `IOException` alla chiamata numero [at] (contando tutte le chiamate al NAS finto), prima o dopo l'effetto. */
class Faults {
  var calls: Int = 0
    private set
  var at: Int = -1
  var after: Boolean = false
  var tripped: Boolean = false
    private set

  /** Esegue [effect] contando la chiamata; se tocca a questa, il guasto prima o dopo. */
  fun <T> call(effect: () -> T): T {
    val n = ++calls
    if (n == at && !after) {
      tripped = true
      throw IOException("guasto simulato prima")
    }
    val value = effect()
    if (n == at && after) {
      tripped = true
      throw IOException("guasto simulato dopo")
    }
    return value
  }
}

class SimMedia(val key: ContentKey, val kind: MediaKind, val month: String, val source: MediaSource, val hidden: Boolean)

class SimShare(val name: String, val owner: String?) {
  val media = LinkedHashMap<ContentKey, SimMedia>()

  /** Anteprime attestate (record `meta` presente di chi le ha pubblicate). */
  val previews = HashMap<Pair<ContentKey, SegmentClass>, ByteArray>()

  /** Revisione A (R5): chi ha preparato un'anteprima attestata (di serie "preparatore"). */
  val previewBy = HashMap<Pair<ContentKey, SegmentClass>, String>()

  /** Le anteprime NON attestate (un dispositivo che non ha il record): non si consegnano mai. */
  val stray = HashMap<Pair<ContentKey, SegmentClass>, ByteArray>()

  /** Le teste `ai`: dispositivo → (chiave, classe) → corpo. */
  val heads = HashMap<String, HashMap<Pair<ContentKey, AiClass>, AiBody>>()

  /** Le prenotazioni: risorsa → (dispositivo, scadenza nell'ora del NAS). */
  val leases = HashMap<String, Pair<String, Long>>()
}

class SimNas(val unitSize: Int = 200) {
  val shares = LinkedHashMap<String, SimShare>()
  var now: Long = 1_800_000_000_000L
  var offline: Boolean = false
  val faults = Faults()

  /** Ogni share toccata da ogni dispositivo, porta per porta (per "mai un'altra area privata"). */
  val touched = HashMap<String, MutableSet<String>>()

  /** Byte di anteprime letti dal NAS (traffico). */
  var bytesRead: Long = 0L

  private var counter = 0

  fun share(name: String, owner: String? = null): SimShare = shares.getOrPut(key(name)) { SimShare(name, owner) }

  fun of(name: String): SimShare = shares.getValue(key(name))

  /** Una chiave di contenuto nuova e deterministica. */
  fun newKey(): ContentKey = ContentKey((++counter).toString(16).padStart(64, '0'))

  /** Un media nuovo nella share, con (o senza) l'anteprima attestata. */
  fun add(
    share: String,
    month: String = "2024-03",
    kind: MediaKind = MediaKind.PHOTO,
    source: MediaSource = MediaSource.CAMERA,
    concepts: String = "cane",
    preview: Boolean = true,
    hidden: Boolean = false,
    key: ContentKey = newKey(),
  ): ContentKey {
    val s = of(share)
    s.media[key] = SimMedia(key, kind, month, source, hidden)
    if (preview) {
      val cls = if (kind == MediaKind.VIDEO) SegmentClass.FRAMES else SegmentClass.ANALYSIS
      s.previews[key to cls] = SimBytes.preview(kind, "$concepts ${key.hex.takeLast(4)}")
    }
    return key
  }

  fun touch(device: String, share: String) {
    touched.getOrPut(device) { HashSet() } += key(share)
  }

  /** I corpi attestati di una classe per (share, chiave): quelli di una testa con la chiave presente nella share. */
  fun doneIn(share: String, key: ContentKey, recipe: Int, cls: AiClass): Boolean {
    val s = shares[key(share)] ?: return false
    if (key !in s.media) return false
    return s.heads.values.any { (it[key to cls]?.recipe ?: 0) >= recipe }
  }

  fun <T> nas(effect: () -> T): T {
    if (offline) throw IOException("NAS spento")
    return faults.call(effect)
  }

  fun key(share: String) = share.lowercase(Locale.ROOT)
}

/** Lo scaricamento delle anteprime dal NAS finto: solo blob attestati, nel buffer; traffico contato. */
class SimFetcher(private val nas: SimNas, private val device: String) : AnalysisFetcher {
  var calls = 0
    private set

  override suspend fun fetch(share: String, keys: List<ContentKey>, classes: Set<SegmentClass>, into: AiBufferSink, budget: Duration): FetchReport {
    calls++
    nas.touch(device, share)
    require(classes.all { it in AnalysisFetcher.FETCHABLE })
    val found = nas.nas { keys.flatMap { k -> classes.mapNotNull { c -> nas.of(share).previews[k to c]?.let { Triple(k, c, it) } } } }
    var fetched = 0
    var bytes = 0L
    for ((k, c, b) in found) {
      nas.bytesRead += b.size
      bytes += b.size
      val by = nas.of(share).previewBy[k to c] ?: "preparatore"
      if (!into.putFrom(k, c, b.copyOf(), share, by)) return FetchReport(fetched, 0, bytes, stoppedEarly = true)
      fetched++
    }
    return FetchReport(fetched, keys.size * classes.size - found.size, bytes, stoppedEarly = false)
  }
}

/** Il lavoro gia' fatto nella share (D9): dalle teste attestate di qualunque dispositivo, ricetta uguale o piu' nuova. */
class SimDone(private val nas: SimNas, private val device: String) : AiDoneClasses {
  override suspend fun done(share: String, keys: Collection<ContentKey>, recipe: Int): Map<ContentKey, Set<AiClass>> {
    nas.touch(device, share)
    return keys.associateWith { k -> AiClass.entries.filterTo(LinkedHashSet()) { nas.doneIn(share, k, recipe, it) } }
      .filterValues { it.isNotEmpty() }
  }
}

/**
 * Il pubblicatore della testa `ai` di un dispositivo sul NAS finto (D8): outbox e voci in attesa per share (come le righe di
 * `AiPublishStore`), M3 (solo chiavi della share), blocco permanente → in attesa, corpo uguale a uno gia' pubblicato o in fila =
 * niente. L'outbox e le voci in attesa sono "database": sopravvivono alla morte del processo.
 */
class SimPublisher(private val nas: SimNas, private val device: String, val gate: MapWriteGate) : AiIndexPublisher {
  private val outbox = LinkedHashMap<String, LinkedHashMap<Pair<ContentKey, AiClass>, AiBody>>()
  private val held = LinkedHashMap<String, LinkedHashMap<Pair<ContentKey, AiClass>, AiBody>>()
  var foreign = 0
    private set
  var heads = 0
    private set

  /** Revisione A (IX-6): quante volte il calcolo ha chiamato [stage] (M3 una volta per lotto). */
  var stageCalls = 0
    private set

  override val pending = MutableStateFlow(AiPendingCounts.EMPTY)

  override suspend fun stage(share: String, entries: List<AiEntry>) {
    stageCalls++
    nas.touch(device, share)
    val s = nas.of(share)
    for (e in entries) {
      if (e.contentKey !in s.media) {
        foreign++
        continue
      }
      val body: AiBody = when (e) {
        is AiEntry.Vector -> e.body
        is AiEntry.Text -> e.body
      }
      check(AiSegments.admits(body)) { "corpo che gli altri rifiuterebbero" }
      val k = e.contentKey to e.cls
      if (s.heads[device]?.get(k) == body) continue
      outbox.getOrPut(nas.key(share)) { LinkedHashMap() }[k] = body
    }
    refresh()
  }

  override suspend fun flush(share: String, reason: FlushReason): PublishOutcome {
    nas.touch(device, share)
    val box = outbox[nas.key(share)]
    if (box.isNullOrEmpty()) return PublishOutcome.NothingPending
    gate.blockFor(share)?.let { block ->
      if (block == WriteBlock.LOCAL_ONLY || block == WriteBlock.READ_ONLY_AREA) {
        held.getOrPut(nas.key(share)) { LinkedHashMap() }.putAll(box)
        box.clear()
        refresh()
      }
      return PublishOutcome.Blocked(block)
    }
    val copy = LinkedHashMap(box)
    nas.nas { nas.of(share).heads.getOrPut(device) { HashMap() }.putAll(copy) }
    copy.keys.forEach { box.remove(it) }
    heads++
    refresh()
    return PublishOutcome.Published(heads.toLong(), copy.values.map { it.cls }.distinct().size, 0)
  }

  override suspend fun releasePending(share: String): Int {
    nas.touch(device, share)
    if (gate.blockFor(share) != null) return 0
    val list = held.remove(nas.key(share)) ?: return 0
    outbox.getOrPut(nas.key(share)) { LinkedHashMap() }.putAll(list)
    refresh()
    return list.size
  }

  fun waiting(share: String): Int = held[nas.key(share)]?.size ?: 0

  /** Revisione A (R5): le voci di [share] in fila o in attesa (non ancora pubblicate). */
  fun queued(share: String): Set<Pair<ContentKey, AiClass>> = outbox[nas.key(share)].orEmpty().keys + held[nas.key(share)].orEmpty().keys

  fun hasPending(share: String, keys: Set<ContentKey>): Boolean = outbox[nas.key(share)]?.keys?.any { it.first in keys } == true

  private fun refresh() {
    pending.value = AiPendingCounts(outbox.values.sumOf { it.size }, held.values.sumOf { it.size })
  }
}
