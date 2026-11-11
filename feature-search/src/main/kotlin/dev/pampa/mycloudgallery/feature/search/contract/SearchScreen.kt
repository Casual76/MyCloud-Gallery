package dev.pampa.mycloudgallery.feature.search.contract

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice

// Gli STATI della ricerca (Fase 6, docs/fase6-progetto.md §1.5, §2.6; H4 della fase H, 11/11: la ricerca dentro la Libreria) e della pagina Ricerca e
// modelli. Dalla fase H la ricerca non ha piu' una pagina: il campo e' la barra in basso, i tre suggerimenti stanno in alto e i risultati prendono il posto della
// griglia della Libreria. Qui c'e' COSA si mostra; COME lo decidono la barra (core-designsystem) e la Libreria. Solo dati gia' pronti: niente rete, niente NAS (D28).

/** Perche' la ricerca non puo' mostrare niente (§2.6, "Stati vuoti e d'errore"). Ogni caso ha un'azione (la riga sopra la barra). */
enum class SearchUnavailableReason {
  /** Libreria vuota o permessi negati: "Non ci sono ancora foto da cercare." -> Consenti accesso. */
  EMPTY_LIBRARY,

  /** Errore interno: "La ricerca non ha funzionato." -> Riprova (codice in Diagnostica). */
  INTERNAL_ERROR,
}

/**
 * Che cosa c'e' dietro la barra di ricerca (H4). I suggerimenti in alto sono a parte (`SearchController.suggestions`): cambiano a ogni pausa mentre si
 * scrive, i risultati solo quando il motore risponde.
 */
sealed interface SearchScreenState {
  /** Niente da cercare (il campo e' vuoto, o una lettera sola: E-Q1): dietro la barra resta la Libreria. */
  data object Idle : SearchScreenState

  /**
   * I risultati, **solo per pertinenza** (scelta dell'utente dell'11/11): i sicuri e, in coda dopo un separatore leggero, i meno probabili (D27). [similar]:
   * quanti media fanno da esempio per "Trova simili" (null = una ricerca per testo). [chips] sono i filtri riconosciuti nel testo («agosto 2024», «Roma»,
   * «video»): valgono senza un'interfaccia a parte e finiscono nella cronologia.
   */
  data class Results(
    val chips: List<SearchChip>,
    val confident: OrderedTimelineView,
    val likely: OrderedTimelineView,
    val coverage: SearchCoverage,
    val notices: List<SearchNotice>,
    val similar: Int? = null,
    /** I2: i media da cui viene "Trova simili" (la riga sopra la barra mostra la foto del primo, se e' ancora nella Libreria visibile). */
    val similarTo: List<MediaId> = emptyList(),
  ) : SearchScreenState

  /** Nessun risultato (E-Q3) per [text] (null per "Trova simili"): una riga sola sopra la barra, mai una pagina. */
  data class NoResults(
    val text: String?,
    val chips: List<SearchChip>,
    val notices: List<SearchNotice> = emptyList(),
    val similar: Int? = null,
    val similarTo: List<MediaId> = emptyList(),
  ) : SearchScreenState

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

/**
 * Le azioni che la ricerca chiede fuori da se' (le esegue :app, come per il backup): aprire il viewer sui risultati, la pagina di
 * attivazione, Impostazioni, la Diagnostica, chiedere l'accesso ai media. I `feature-*` non si conoscono fra loro.
 */
interface SearchActions {
  /**
   * Il viewer sui risultati [view], nell'ordine mostrato, da [start]. [anchor] e' la griglia dei risultati (H4): il viewer si apre dalla cella toccata e,
   * chiudendosi, torna sulla cella del media che si stava guardando, come dalla Libreria.
   */
  fun openResults(view: OrderedTimelineView, start: MediaId, anchor: ViewerAnchor)

  fun openSearchSettings()

  fun openTextReadingActivation()

  fun openDiagnostics()

  fun requestMediaAccess()

  /** "Libera spazio" (P8, §2.6: `AI_MODEL_NO_SPACE`, `AI_STORAGE_LOW`, `CUTOUT_SAVE_FAILED`): la pagina di :app che libera spazio. Di serie non fa niente. */
  fun openFreeSpace() = Unit
}
