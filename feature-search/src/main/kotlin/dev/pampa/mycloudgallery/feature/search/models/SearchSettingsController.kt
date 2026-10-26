package dev.pampa.mycloudgallery.feature.search.models

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.DeviceProbe
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelRole
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.feature.search.contract.IndexSpace
import dev.pampa.mycloudgallery.feature.search.contract.IndexerChange
import dev.pampa.mycloudgallery.feature.search.contract.IndexerBacklog
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import dev.pampa.mycloudgallery.feature.search.contract.IndexerState
import dev.pampa.mycloudgallery.feature.search.contract.ModelRow
import dev.pampa.mycloudgallery.feature.search.contract.ModelsState
import dev.pampa.mycloudgallery.feature.search.contract.SearchSettingsState
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingAccepted
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingPageState
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Il cervello della pagina Impostazioni → Ricerca e modelli (Fase 6, P8, docs/fase6-progetto.md §2.6; D15, D20, D41; Q3, Q13): compone
 * [SearchSettingsState] dalle porte e fa le richieste dell'utente. Ogni porta e' opzionale (null = quella parte e' un riposo sicuro): senza
 * [IndexerControl] il ruolo e' spento e nessun lavoro si propone; senza [TextReadingSwitch] la lettura del testo resta spenta e non si puo' accendere.
 * Nessun log con testi, luoghi o chiavi (D40).
 *
 * Il consenso alla lettura del testo **non** si costruisce qui: lo passa la pagina di attivazione ([enableTextReading]), l'unica che puo' farlo
 * (Phase6ArchitectureTest).
 */
class SearchSettingsController(
  private val indexer: IndexerControl?,
  private val models: ModelManager?,
  engine: SearchEngine,
  private val history: SearchHistory,
  private val textReading: TextReadingSwitch?,
  accepted: TextReadingAccepted?,
  private val probe: DeviceProbe?,
  private val scope: CoroutineScope,
) {
  private val probeResult = MutableStateFlow<ProbeResult?>(null)

  /** La misura breve e' in corso: la pagina dice "Sto misurando il dispositivo…". */
  val measuring: MutableStateFlow<Boolean> = MutableStateFlow(false)

  /** IX-8: spento il ruolo, la pagina propone di togliere il modello di analisi. */
  private val removeOffer = MutableStateFlow(false)

  private val offIndexer: StateFlow<Boolean> = MutableStateFlow(false)
  private val offStatus: StateFlow<AiStatus> = MutableStateFlow(AiStatus.Off)
  private val noBacklog: StateFlow<IndexerBacklog> = MutableStateFlow(IndexerBacklog.NONE)
  private val noSpace: StateFlow<IndexSpace> = MutableStateFlow(IndexSpace.NONE)
  private val noModels: StateFlow<Map<ModelId, ModelState>> = MutableStateFlow(emptyMap())
  private val offReading: StateFlow<TextReadingState> = MutableStateFlow(TextReadingState.Off)
  private val notExcluded: StateFlow<Boolean> = MutableStateFlow(false)
  // Senza la porta della versione accettata non si puo' dire che il testo e' cambiato: vale quello di adesso.
  private val noVersion: StateFlow<Int> = MutableStateFlow(TextReadingSwitch.CURRENT_TEXT_VERSION)

  val state: StateFlow<SearchSettingsState> = combine(
    listOf(
      indexer?.indexer ?: offIndexer,
      indexer?.status ?: offStatus,
      indexer?.backlog ?: noBacklog,
      indexer?.space ?: noSpace,
      probeResult,
      models?.states ?: noModels,
      engine.coverage,
      history.entries,
      textReading?.state ?: offReading,
      accepted?.acceptedVersion ?: noVersion,
      textReading?.excludedHere ?: notExcluded,
      textReading?.syncPending ?: notExcluded,
      removeOffer,
    ),
  ) { values ->
    @Suppress("UNCHECKED_CAST")
    compose(
      isIndexer = values[0] as Boolean,
      status = values[1] as AiStatus,
      backlog = values[2] as IndexerBacklog,
      space = values[3] as IndexSpace,
      probeResult = values[4] as ProbeResult?,
      states = values[5] as Map<ModelId, ModelState>,
      coverage = values[6] as SearchCoverage,
      historyCount = (values[7] as List<*>).size,
      reading = values[8] as TextReadingState,
      acceptedVersion = values[9] as Int,
      excludedHere = values[10] as Boolean,
      syncPending = values[11] as Boolean,
      removeOffer = values[12] as Boolean,
    )
  }.stateIn(scope, SharingStarted.Eagerly, compose(false, AiStatus.Off, IndexerBacklog.NONE, IndexSpace.NONE, null, emptyMap(), SearchCoverage.Complete, 0, TextReadingState.Off, TextReadingSwitch.CURRENT_TEXT_VERSION))

  /** Il ruolo Indicizzatore e la lettura del testo sono collegati? Senza, la pagina nasconde la sezione (nessuna riga che non fa niente). */
  val hasIndexer: Boolean get() = indexer != null
  val hasTextReading: Boolean get() = textReading != null
  val hasModels: Boolean get() = models != null

  private fun compose(
    isIndexer: Boolean,
    status: AiStatus,
    backlog: IndexerBacklog,
    space: IndexSpace,
    probeResult: ProbeResult?,
    states: Map<ModelId, ModelState>,
    coverage: SearchCoverage,
    historyCount: Int,
    reading: TextReadingState,
    acceptedVersion: Int,
    excludedHere: Boolean = false,
    syncPending: Boolean = false,
    removeOffer: Boolean = false,
  ): SearchSettingsState {
    val nights = probeResult?.nightsFor(backlog.toAnalyze)?.takeIf { backlog.toAnalyze > 0 }
    val current = TextReadingSwitch.CURRENT_TEXT_VERSION
    return SearchSettingsState(
      indexer = IndexerState(isIndexer, status, probeResult, backlog.toAnalyze, nights, backlog.areasWithoutIndexer),
      textReading = TextReadingPageState(
        reading,
        current,
        needsConsent = reading == TextReadingState.Off || reading is TextReadingState.AwaitingNotice || acceptedVersion < current,
        excludedHere = excludedHere,
        syncPending = syncPending,
      ),
      models = ModelsState(
        models = ModelCatalog.ALL.map { spec ->
          // MO-6: il modello del ritaglio sta nell'APK: finche' non e' in errore e' "pronto" (il gestore lo dice `Absent` fino alla prima apertura).
          val reported = states[spec.id]
          val bundled = spec.role == ModelRole.CUTOUT && (reported == null || reported == ModelState.Absent || reported is ModelState.Queued || reported == ModelState.Verifying)
          val state = if (bundled) ModelState.Ready(spec.sizeBytes, ModelSourceKind.BUNDLED, 0) else reported ?: ModelState.Absent
          ModelRow(spec.id, state, spec.sizeBytes, (state as? ModelState.Ready)?.source, removable = spec.role != ModelRole.CUTOUT)
        },
        vectorBytes = space.vectorBytes,
        textBytes = space.textBytes,
      ),
      coverage = coverage,
      historyCount = historyCount,
      offerRemoveIndexModel = removeOffer,
    )
  }

  init {
    // IX-3: l'ultima misura si ritrova, e si rifa' quando il modello del calcolo diventa pronto (la prima falliva se mancava).
    indexer?.savedProbe()?.let { probeResult.value = it }
    val control = indexer
    val manager = models
    if (control != null && manager != null) {
      scope.launch {
        manager.states.map { it[ModelCatalog.INDEX_440M.id] is ModelState.Ready }.distinctUntilChanged().collect { ready ->
          if (ready && control.indexer.value && probeResult.value == null) measure()
        }
      }
    }
  }

  /** La pagina si apre: il lavoro che resta, lo spazio e lo stato si rileggono (IX-3). */
  fun refresh() {
    indexer?.refresh()
  }

  // --- Ruolo Indicizzatore (D15, Q3) ----------------------------------------------------------------------------------------------------

  /**
   * Accende o spegne il ruolo di questo dispositivo. Acceso: propone il 440M (scaricamento con le regole, mai i dati mobili) e lancia la misura
   * breve per dire "circa 2 notti" (D15). Spento: niente da proporre; chi osserva il ruolo rilascia le prenotazioni.
   */
  fun setIndexer(on: Boolean) {
    val control = indexer ?: return
    scope.launch {
      val change = try {
        control.setIndexer(on)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      // Il 440M lo chiede l'app all'accensione del ruolo (lavoro accelerato, D15); la misura parte quando il modello e' pronto (vedi `init`).
      if (on) {
        measure()
      } else if (change == IndexerChange.SAVED && models?.states?.value?.get(ModelCatalog.INDEX_440M.id) is ModelState.Ready) {
        // IX-8: spegnere il ruolo chiede se togliere anche il modello (370 MiB).
        removeOffer.value = true
      }
    }
  }

  /** La misura breve del dispositivo (20 immagini sintetiche dell'APK): una sola alla volta. */
  fun measure() {
    val device = probe ?: return
    if (measuring.value) return
    measuring.value = true
    scope.launch {
      try {
        guarded {
          val result = device.measure()
          probeResult.value = result
          indexer?.saveProbe(result)
        }
      } finally {
        measuring.value = false
      }
    }
  }

  /** "Indicizza ora": una richiesta dell'utente (D22). */
  fun indexNow() {
    indexer?.indexNow()
  }

  // --- Modelli (D20) ---------------------------------------------------------------------------------------------------------------

  /** "Scarica ora": una richiesta di lavoro (non scarica nel proprio scope: la pagina puo' chiudersi e lo scaricamento continua, MO-1). */
  fun downloadModel(id: ModelId) {
    val manager = models ?: return
    scope.launch { guarded { manager.ensure(id, ModelTrigger.USER_NOW, DOWNLOAD_BUDGET) } }
  }

  /** Il "Si'" alla conferma sui dati mobili (MO-2): la richiesta ripetuta porta la conferma con se'. */
  fun confirmMobileDownload(id: ModelId) {
    val manager = models ?: return
    scope.launch { guarded { manager.ensure(id, ModelTrigger.USER_CONFIRMED, DOWNLOAD_BUDGET) } }
  }

  /** "Tienilo": il modello di analisi resta (IX-8). */
  fun dismissRemoveOffer() {
    removeOffer.value = false
  }

  fun removeModel(id: ModelId) {
    removeOffer.value = false
    val manager = models ?: return
    val spec = ModelCatalog.byId(id) ?: return
    // Il ritaglio sta nell'APK e non si toglie (D44).
    if (spec.role == ModelRole.CUTOUT) return
    scope.launch { guarded { manager.remove(id) } }
  }

  // --- Cronologia (D32, Q5) ----------------------------------------------------------------------------------------------------------

  fun clearHistory() {
    scope.launch { guarded { history.clear() } }
  }

  // --- Lettura del testo (D41, Q4, Q13) -----------------------------------------------------------------------------------------------

  /** Accende la lettura per l'account. Il [consent] lo costruisce solo la pagina di attivazione, con la versione del testo che l'utente ha letto. */
  fun enableTextReading(consent: TextReadingConsent) {
    val switch = textReading ?: return
    scope.launch { guarded { switch.enableForAccount(consent) } }
  }

  fun excludeThisDevice() {
    val switch = textReading ?: return
    scope.launch { guarded { switch.excludeThisDevice() } }
  }

  fun includeThisDevice() {
    val switch = textReading ?: return
    scope.launch { guarded { switch.includeThisDevice() } }
  }

  fun disableTextReading() {
    val switch = textReading ?: return
    scope.launch { guarded { switch.disable() } }
  }

  private suspend fun guarded(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Una richiesta che non riesce lascia lo stato com'era: il motivo e' nel codice della Diagnostica, mai nel testo di un'eccezione.
    }
  }

  private companion object {
    val DOWNLOAD_BUDGET = 30.minutes
  }
}
