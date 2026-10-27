package dev.pampa.mycloudgallery.faces

import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.faces.compute.FacesBacklog
import dev.pampa.mycloudgallery.core.faces.compute.FacesComputeCounters
import dev.pampa.mycloudgallery.core.faces.compute.FacesComputeEngine
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesSync
import dev.pampa.mycloudgallery.core.faces.erase.DefaultFacesEraser
import dev.pampa.mycloudgallery.core.faces.grouping.CustodianGrouping
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseFence
import dev.pampa.mycloudgallery.core.faces.erase.FacesNasJanitor
import dev.pampa.mycloudgallery.core.faces.people.ArchivePeople
import dev.pampa.mycloudgallery.core.faces.store.FacesStores
import dev.pampa.mycloudgallery.core.faces.switch.DefaultFacesSwitch
import dev.pampa.mycloudgallery.core.faces.switch.FacesPrePublishHook
import dev.pampa.mycloudgallery.core.faces.sync.FacesArchives
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFilesCleaning
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.PrePublishHook
import dev.pampa.mycloudgallery.feature.people.crops.GuardedPeopleCrops
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * I motori dei volti (rilevatore e embedder), costruiti **solo** quando il calcolo ha qualcosa da fare e rilasciati a fine tappa, a memoria bassa e
 * allo stop (§2.2 "Rilascio"). Prima dell'attivazione nessuna classe dei motori si carica: [create] non e' mai chiamata finche' lo stato non e' attivo qui.
 */
class FacesEngineHolder(private val create: suspend () -> FaceEngines?) {
  private val mutex = Mutex()
  private var engines: FaceEngines? = null

  @Volatile private var stopped = false

  /** I motori di adesso (costruiti al primo uso); null se non si possono avere (modelli assenti) o dopo lo stop. */
  suspend fun get(): FaceEngines? = mutex.withLock {
    if (stopped) return@withLock null
    engines ?: create()?.also { engines = it }
  }

  /** Li rilascia (si riaprono da soli al prossimo [get]). */
  suspend fun release() = withContext(NonCancellable) {
    val held = mutex.withLock { engines.also { engines = null } }
    runCatching { held?.release() }
  }

  /** Per sempre in questo processo (azzeramento): rilascia e rifiuta altri usi. */
  suspend fun stop() {
    stopped = true
    release()
  }

  /** Vero se i motori sono costruiti (per i test: prima dell'attivazione devono essere falso). */
  val isCreated: Boolean get() = engines != null
}

/**
 * Tutto cio' che le persone hanno montato in questo processo (Fase 8, P13; docs/fase8-progetto.md §1.4): l'interruttore con il suo file locale, il
 * recinto delle aperture con la cancellazione, il calcolo, il raggruppamento con il custode, la sincronizzazione con il NAS, le persone con le
 * copertine in memoria. Lo costruisce [FacesWiring] una volta per processo, quando la famiglia e' pronta. Ogni apertura dei dati dei volti passa da
 * [archives], cioe' dal recinto ([FacesEraseFence]): niente si apre per una generazione che si sta cancellando.
 */
class FacesLive internal constructor(
  val scope: CoroutineScope,
  val stores: FacesStores,
  val switch: DefaultFacesSwitch,
  val settings: FacesPersonalSettings,
  val fence: FacesEraseFence,
  val eraser: DefaultFacesEraser,
  val janitor: FacesNasJanitor,
  val sync: FacesSync,
  val publisher: FacesPublisher,
  val compute: FacesComputeEngine,
  val counters: FacesComputeCounters,
  val grouping: CustodianGrouping,
  val runControl: StageFacesControl,
  val people: ArchivePeople,
  val crops: GuardedPeopleCrops,
  val archives: FencedArchives,
  val engines: FacesEngineHolder,
  val requirements: FacesRequirements,
  val backlog: FacesBacklog,
  private val arrivals: MutableSharedFlow<Unit>,
  /** La pulizia dei propri file dei volti sul NAS e l'area privata (null senza area privata: niente si pulisce). */
  private val cleaning: OwnFilesCleaning?,
  private val privateShare: () -> String?,
  /** Le persone o il loro stato sono cambiati: ricerca e album smart rivalutano (E-F37). */
  private val onPeopleChanged: () -> Unit,
  /** Dopo il montaggio: i lavori che servono (per esempio il nome degli album smart che dipendono dalle persone). */
  private val mounted: suspend () -> Unit = {},
  /** Cambia il profilo del dispositivo (l'account, l'area privata dichiarata): l'interruttore rilegge i requisiti e la recinzione (P7). */
  private val requirementsChanged: Flow<Unit> = emptyFlow(),
) {
  /** Il gancio prima di ogni pubblicazione dei dati personali (D30): lo mette `FacesWiring` nel motore dei dati personali. */
  val hook: PrePublishHook = FacesPrePublishHook(switch)

  /** Un dato nuovo da fuori (replica, custode, calcolo): le persone si rileggono. */
  val arrived: SharedFlow<Unit> get() = arrivals

  @Volatile private var stoppingNow = false

  /** Dopo il montaggio: i collegamenti che vivono quanto le persone. */
  @OptIn(FlowPreview::class)
  suspend fun afterMount() {
    mounted()
    scope.launch { requirementsChanged.collect { if (!stoppingNow) switch.refreshRequirements() } }
    // Le persone cambiano (un nome, un'unione, un gruppo nuovo) o cambia lo stato: ricerca e album si rivalutano, accorpati.
    scope.launch {
      merge(people.list.map { it.named.size to it.hiddenCount }.drop(1).map { }, switch.state.drop(1).map { })
        .debounce(PEOPLE_QUIET_MS)
        .collect { if (!stoppingNow) onPeopleChanged() }
    }
  }

  /** Lo stato di Persone e' cambiato (si accende, si spegne, si esclude): le viste dipendenti si rifanno. */
  fun statusChanged() {
    if (!stoppingNow) arrivals.tryEmit(Unit)
  }

  /** Dati nuovi arrivati (replica, calcolo, custode): le viste delle persone si rileggono. */
  fun dataArrived() {
    if (!stoppingNow) arrivals.tryEmit(Unit)
  }

  /** Lo stato del calcolo per la pagina e la Diagnostica. */
  val status: kotlinx.coroutines.flow.StateFlow<FacesStatus> get() = compute.status

  suspend fun releaseEngines() = engines.release()

  suspend fun releaseEnginesQuietly() {
    runCatching { engines.stop() }
  }

  /** Da qui niente di nuovo parte (primo passo dell'azzeramento). */
  fun stopping() {
    stoppingNow = true
  }

  /** La pulizia dei propri file dei volti sul NAS (con la manutenzione dell'indice): solo l'area privata, mai l'area comune. */
  suspend fun cleanOwnFiles(budget: Duration) {
    val share = privateShare() ?: return
    cleaning?.clean(listOf(share), budget)
  }

  override fun toString(): String = "FacesLive"

  private companion object {
    /** Le persone cambiano a raffica durante il primo calcolo: ricerca e album si rivalutano al piu' ogni 20 secondi. */
    const val PEOPLE_QUIET_MS: Long = 20_000L
  }
}
