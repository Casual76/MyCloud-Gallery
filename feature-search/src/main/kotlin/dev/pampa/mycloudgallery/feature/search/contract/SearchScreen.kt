package dev.pampa.mycloudgallery.feature.search.contract

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.Suggestion

// Gli STATI della schermata Cerca e della pagina Ricerca e modelli (docs/fase6-progetto.md §1.5, §2.6, D36). Contratto di P0: la
// Fase 6 decide COSA si mostra e QUANDO, il filone grafico decide COME. P8 scrive ViewModel e composable con i soli componenti G2
// esistenti; il filone grafico rifinisce senza toccare questi tipi. Solo dati gia' pronti da mostrare: niente rete, niente NAS (D28).

/** Perche' Cerca non puo' mostrare niente (§2.6, "Stati vuoti e d'errore"). Ogni caso ha un'azione. */
enum class SearchUnavailableReason {
  /** Libreria vuota o permessi negati: "Non ci sono ancora foto da cercare." -> Consenti accesso. */
  EMPTY_LIBRARY,

  /** Errore interno: "La ricerca non ha funzionato." -> Riprova (codice in Diagnostica). */
  INTERNAL_ERROR,
}

/** La schermata Cerca (§1.5). */
sealed interface SearchScreenState {
  /** A riposo: ricerche recenti (fino a 10), luoghi frequenti (fino a 8, mai da nascosti), tipi. */
  data class Idle(val suggestions: IdleSuggestions, val recents: List<HistoryEntry>) : SearchScreenState

  /** Mentre si scrive: completamenti di luoghi, mesi, feste, tipi e ricerche recenti, **mai** parole del testo letto (D29). */
  data class Typing(val text: String, val completions: List<Suggestion>) : SearchScreenState

  data class Searching(val chips: List<SearchChip>) : SearchScreenState

  /**
   * I risultati: barra dei chip, selettore "Recenti · Pertinenti" ([order], ricordato), sicuri, "Mostra anche risultati meno
   * probabili ([likelyCount])", avviso di copertura sotto il 98%.
   */
  data class Results(
    val chips: List<SearchChip>,
    val order: ResultOrder,
    val confident: OrderedTimelineView,
    val likely: OrderedTimelineView,
    val likelyCount: Int,
    val showLikely: Boolean,
    val coverage: SearchCoverage,
    val notices: List<SearchNotice>,
  ) : SearchScreenState

  /** Nessun risultato sicuro (E-Q3, E-Q12): [likelyCount] > 0 propone "Mostra anche…"; [alternatives] sono chip da togliere. */
  data class NoResults(val chips: List<SearchChip>, val likelyCount: Int, val alternatives: List<SearchChip>) : SearchScreenState

  data class Unavailable(val reason: SearchUnavailableReason) : SearchScreenState
}

/** Un modello nella pagina Ricerca e modelli (§1.5, D20). */
data class ModelRow(
  val id: ModelId,
  val state: ModelState,
  val sizeBytes: Long,
  /** Da dove e' arrivato, se pronto (mai la share ne' l'indirizzo). */
  val source: ModelSourceKind?,
  /** Si puo' togliere (il ritaglio nell'APK no). */
  val removable: Boolean,
)

/** I modelli e lo spazio della ricerca (D20). */
data class ModelsState(val models: List<ModelRow>, val vectorBytes: Long, val textBytes: Long)

/** Il ruolo Indicizzatore di questo dispositivo e il suo stato (D15, Q3). */
data class IndexerState(
  val indexer: Boolean,
  val status: AiStatus,
  /** La misura breve del dispositivo, se fatta (D15). */
  val probe: ProbeResult?,
  /** "Analizzare N media richiede circa K notti" (stima dal backlog vero). */
  val toAnalyze: Int,
  val nights: Int?,
  /** Le aree senza indicizzatori (E-I12): solo il tipo di area, mai il nome della share. */
  val areasWithoutIndexer: List<AreaKind>,
)

enum class AreaKind { MY_PRIVATE_AREA, COMMON_AREA }

/** La lettura del testo nelle foto (D41): stato, e se la pagina di attivazione va mostrata (testo nuovo da accettare). */
data class TextReadingPageState(
  val state: TextReadingState,
  val textVersion: Int,
  val needsConsent: Boolean,
  /** Questo dispositivo si e' escluso (locale, vale anche da spenta: R8). */
  val excludedHere: Boolean = false,
  /** La scelta fatta qui non e' ancora arrivata agli altri dispositivi (fuori casa, senza l'ora del NAS): vale qui subito (R6, I8). */
  val syncPending: Boolean = false,
)

/** La pagina Impostazioni → Ricerca e modelli (§2.6). */
data class SearchSettingsState(
  val indexer: IndexerState,
  val textReading: TextReadingPageState,
  val models: ModelsState,
  val coverage: SearchCoverage,
  val historyCount: Int,
  /** IX-8: il ruolo e' appena stato spento e il modello di analisi (370 MiB) c'e' ancora: la pagina chiede se toglierlo. */
  val offerRemoveIndexModel: Boolean = false,
)

/** La cronologia a riposo (§1.5, D32). */
data class HistoryState(val entries: List<HistoryEntry>)

/**
 * Le azioni che la schermata chiede fuori da se' (le esegue :app, come per il backup): aprire il viewer sui risultati, la pagina di
 * attivazione, Impostazioni, la Diagnostica, chiedere l'accesso ai media. I `feature-*` non si conoscono fra loro.
 */
interface SearchActions {
  fun openResults(view: OrderedTimelineView, start: dev.pampa.mycloudgallery.core.model.MediaId)

  fun openSearchSettings()

  fun openTextReadingActivation()

  fun openDiagnostics()

  fun requestMediaAccess()

  /** "Libera spazio" (P8, §2.6: `AI_MODEL_NO_SPACE`, `AI_STORAGE_LOW`, `CUTOUT_SAVE_FAILED`): la pagina di :app che libera spazio. Di serie non fa niente. */
  fun openFreeSpace() = Unit
}
