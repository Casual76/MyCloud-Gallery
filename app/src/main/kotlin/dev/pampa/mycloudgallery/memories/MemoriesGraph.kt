package dev.pampa.mycloudgallery.memories

import android.content.ComponentCallbacks2
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCutouts
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.plan.MemoryChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicSettings
import dev.pampa.mycloudgallery.core.memories.contract.NotifySettings
import dev.pampa.mycloudgallery.core.memories.contract.RefreshBudgets
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyRun
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.settings.less.PeopleLessJanitor
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.notify.MemoryNotifier
import dev.pampa.mycloudgallery.work.MemoryNotifySlot
import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cio' che i ricordi montano quando la famiglia e' pronta (Fase 9, P13): il motore con lo scaffale su file e le porte vere, le scelte (dati personali, con gli
 * alias delle persone), le persone dietro lo stato di Persone, il janitor di "Mostra meno" e il giorno di una notifica. Vive in [scope], figlio del grafo.
 */
class MemoriesLive(
  val scope: CoroutineScope,
  val engine: MemoryEngine,
  val personal: MemoriesPersonalSettings,
  val people: MemoryPeople,
  val janitor: PeopleLessJanitor,
  val run: MemoryNotifyRun,
  /** Le porte del motore, per lo strumento di valutazione (P11, solo debug: un motore suo con le stesse porte). */
  val trips: MemoryTrips,
  val cutouts: MemoryCutouts,
  val choice: MemoryChoice,
)

/**
 * I RICORDI NELL'APP (Fase 9, P13; docs/fase9-progetto.md §1.4 `MemoriesGraph`, §2.1, §2.3, §2.6, D3-D20, D31, D37). Nasce con il processo (Hilt) **senza
 * I/O** e monta [MemoriesLive] quando la famiglia e' pronta (galleria partita e dati personali agganciati: le scelte "visto", "non mostrare", "Mostra meno" e
 * "Mai" stanno li') e **dopo il primo disegno utile** ([afterFirstDraw]): niente sul percorso dell'avvio. Tutto e' locale (D4): vive anche con il NAS
 * irraggiungibile.
 *
 * Al montaggio, **prima di tutto**, il motore legge l'ultimo scaffale da file (`restore()`, fuori dal thread principale): la sezione Ricordi delle Raccolte
 * si disegna da li' senza aspettare la Libreria. Poi: il lavoro delle notifiche riceve il suo giorno ([MemoryNotifySlot]) e si ripianifica; la notifica in
 * tendina si rivaluta a ogni cambio di scaffale; il janitor di "Mostra meno" segue lo stato di Persone e le persone; a ogni cambio dei nascosti (un
 * "Nascondi" fatto qui, subito; uno arrivato da un altro dispositivo, dopo la calma dell'istantanea) lo scaffale si ripulisce e la notifica si rivaluta.
 *
 * I giri: [nightly] dalla manutenzione notturna (dopo i viaggi, 60 s), [onCollectionsOpened] all'apertura di Raccolte (`OPEN`: non rifa' niente se oggi il
 * giro c'e' gia' stato). [stop] e' il primo passo dell'azzeramento (D37). Nei log solo numeri e nomi di classi.
 */
class MemoriesGraph internal constructor(
  parent: CoroutineScope,
  private val ready: StateFlow<Boolean>,
  /** Falso senza famiglia (nessun NAS): i ricordi non si monteranno mai qui, e la sezione lo dice invece di "sto preparando". */
  val canMount: Boolean,
  private val build: suspend (CoroutineScope) -> MemoriesLive?,
  /** La visibilita' dei media (i due contratti dei Nascosti), una per processo. */
  val visibility: MemoryVisibility,
  /** La notifica: nasce con il processo (il permesso e il canale si leggono anche prima del montaggio), pigra. */
  notifier: Lazy<MemoryNotifier>,
  /** Le preferenze delle notifiche (`memories/notify-prefs`): la pagina di P9 e il giorno di una notifica leggono la stessa istanza. Pigre. */
  notifySettings: Lazy<NotifySettings>,
  /** La scelta della musica della presentazione (`memories/music`), solo su questo dispositivo. Pigra. */
  musicSettings: Lazy<MusicSettings>,
  private val slot: MemoryNotifySlot,
  private val afterFirstDraw: suspend () -> Unit,
  /** Cio' che la Diagnostica conta (giri, scaffali rigenerati, esclusi, notifiche saltate, in ritardo, tolte). */
  val counters: MemoriesCounters,
  /** Le persone sono cambiate (un giro dei volti, un nascondi, un "dimentica", un'unione): il janitor rifa' il suo giro. */
  private val peopleChanged: Flow<Unit>,
  /** La miniatura **locale** di un media (mai il NAS): per lo strumento di valutazione (solo debug). */
  val localThumbnail: suspend (MediaId, Int) -> Bitmap? = { _, _ -> null },
  /** Il nome di un luogo (id GeoNames), gia' scritto. */
  val placeLabel: (Int) -> String? = { null },
  /** "Oggi" dei ricordi (la porta `Today`). */
  val today: () -> LocalDate = { LocalDate.now() },
) {
  private val job = SupervisorJob(parent.coroutineContext[Job])
  private val scope = CoroutineScope(parent.coroutineContext + job)

  /** Dove vivono i flussi delle facciate (si ferma con [stop]). */
  internal val facadeScope: CoroutineScope get() = scope
  private val building = Mutex()
  private val stopped = AtomicBoolean(false)
  private val liveFlow = MutableStateFlow<MemoriesLive?>(null)
  private val notifierHolder = notifier

  /** Il montaggio di adesso (null prima e dopo [stop]). */
  val live: StateFlow<MemoriesLive?> = liveFlow.asStateFlow()

  val notifier: MemoryNotifier get() = notifierHolder.value

  /** Vero se la notifica e' gia' nata in questo processo (l'azzeramento la toglie solo se c'e'; altrimenti basta la cartella). */
  val notifierBuilt: Boolean get() = notifierHolder.isInitialized()

  private val notifyHolder = notifySettings
  private val musicHolder = musicSettings

  val notifySettings: NotifySettings get() = notifyHolder.value

  val musicSettings: MusicSettings get() = musicHolder.value

  /** I ricordi per le schermate: quelli montati, e prima i null-object ("sto preparando"). */
  val memories: Memories = MemoriesFacade(this, scope)

  /** Parte con il processo: aspetta la famiglia pronta e il primo disegno utile, poi monta. */
  fun start() {
    scope.launch {
      afterFirstDraw()
      ready.first { it }
      ensureLive()
    }
  }

  /** Il montaggio, fatto adesso se si puo' (null se la famiglia non e' pronta, se la costruzione fallisce o dopo lo stop). */
  suspend fun ensureLive(): MemoriesLive? {
    liveFlow.value?.let { return it }
    if (stopped.get()) return null
    return building.withLock {
      liveFlow.value ?: mountLocked()
    }
  }

  private suspend fun mountLocked(): MemoriesLive? {
    if (stopped.get() || !ready.value) return null
    val mountScope = CoroutineScope(scope.coroutineContext + SupervisorJob(job))
    val built = try {
      build(mountScope)?.also { live -> live.engine.restore() }
    } catch (e: CancellationException) {
      mountScope.cancel()
      throw e
    } catch (e: Exception) {
      counters.mountFailed = true
      GalleryLog.w(TAG) { "ricordi non montati: ${e.javaClass.simpleName}" }
      null
    }
    if (built == null || stopped.get()) {
      mountScope.cancel()
      return null
    }
    liveFlow.value = built
    watch(built)
    return built
  }

  @OptIn(FlowPreview::class)
  private fun watch(live: MemoriesLive) {
    // P10: senza il giorno di una notifica il lavoro `memories-notify` non fa niente; a ogni avvio si ripianifica (e a ogni cambio di impostazioni).
    slot.runner = live.run
    live.run.watch(live.scope)
    notifier.watch(live.scope)
    // P8: il janitor segue stato di Persone, disponibilita' e scelte; in piu' a ogni cambio delle persone (giro dei volti, nascondi, dimentica, unisci).
    live.janitor.watch(live.scope)
    live.scope.launch { peopleChanged.debounce(PEOPLE_QUIET).collect { quietly { live.janitor.sweep() } } }
    // Un nascosto arrivato da un altro dispositivo cambia l'istantanea visibile: dopo la calma, scaffale ripulito e notifica rivalutata.
    live.scope.launch {
      visibility.snapshot.map { it?.seq }.distinctUntilChanged().drop(1).debounce(SNAPSHOT_QUIET).collect { quietly { pruneAndRevalidate(live) } }
    }
  }

  /** Un "Nascondi" o "Mostra" fatto qui (P6: `prune()`; P10: `revalidate()`): subito, fuori dal thread principale. */
  fun onHiddenChanged() {
    val live = liveFlow.value ?: return
    live.scope.launch { quietly { pruneAndRevalidate(live) } }
  }

  private suspend fun pruneAndRevalidate(live: MemoriesLive) {
    live.engine.prune()
    notifier.revalidate()
  }

  /** L'apertura di Raccolte (P9: `Memories.refresh(OPEN)`): il giro di oggi se non c'e' ancora stato, con il budget del passo leggero. */
  fun onCollectionsOpened() {
    if (stopped.get()) return
    scope.launch {
      val live = ensureLive() ?: return@launch
      quietly { counters.round(live.engine.refresh(RefreshReason.OPEN, RefreshBudgets.LIGHT_SECONDS.seconds)) }
    }
  }

  /** La generazione notturna (dentro `LibraryMaintenanceWorker`, dopo i viaggi, §1.4): vero se il giro e' finito. */
  suspend fun nightly(budget: Duration = RefreshBudgets.NIGHTLY_SECONDS.seconds): Boolean {
    val live = ensureLive() ?: return false
    val started = android.os.SystemClock.elapsedRealtime()
    val report = live.engine.refresh(RefreshReason.NIGHTLY, budget)
    counters.round(report, android.os.SystemClock.elapsedRealtime() - started)
    // Lo scaffale nuovo: la notifica in tendina si rivaluta (un ricordo uscito non resta in tendina).
    quietly { notifier.revalidate() }
    return !report.incomplete
  }

  /** `onTrimMemory`: la tabella a colonne dell'istantanea si lascia (si rifa' al giro dopo, ~20 ms ogni 100 mila celle). */
  fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) liveFlow.value?.engine?.releaseCaches()
  }

  /**
   * L'azzeramento (D37), **per primo**: niente si monta piu', il lavoro delle notifiche perde il suo giorno, i collettori si fermano e si aspettano. Lavoro e
   * notifica li toglie [LocalData][dev.pampa.mycloudgallery.graph.AppLocalDataEraser] subito dopo, poi la cartella `memories/`.
   */
  suspend fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    building.withLock { liveFlow.value = null }
    slot.runner = null
    job.cancelAndJoin()
  }

  private suspend fun quietly(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      counters.roundErrors++
      GalleryLog.w(TAG) { "ricordi: ${e.javaClass.simpleName}" }
    }
  }

  companion object {
    private const val TAG = "Memories"

    /** I cambi delle persone arrivano a raffiche (un giro dei volti): il janitor aspetta che si calmino. */
    val PEOPLE_QUIET: Duration = 2.seconds

    /** Le istantanee arrivano a raffiche durante la lettura del telefono e la sincronizzazione: lo scaffale si ripulisce dopo la calma. */
    val SNAPSHOT_QUIET: Duration = 3.seconds

    /** Lo scaffale prima del montaggio, se i ricordi possono montarsi. */
    internal fun beforeMount(canMount: Boolean) = if (canMount) MemoriesFacade.PREPARING else MemoriesFacade.NOT_READY
  }
}
