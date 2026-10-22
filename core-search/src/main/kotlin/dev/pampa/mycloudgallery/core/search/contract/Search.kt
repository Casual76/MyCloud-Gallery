package dev.pampa.mycloudgallery.core.search.contract

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

// IL MOTORE DI RICERCA (docs/fase6-progetto.md §1.2 "core-search", §2.4, D23-D32, D35, D48). Contratti di P0; il motore vero (indice
// a due passate, FTS4 e vocabolario, fusione, simili, copertura, cronologia) e' di P6. L'esecutore di riferimento
// `InMemorySearchEngine` (forza bruta float32, testing/) gira sugli stessi contratti: la suite di parita' del §5.2 li confronta.
//
// Nessuna rete e nessun NAS, per costruzione (D33). Nascosti MAI (D29): i candidati sono l'istantanea della Libreria (che non li
// contiene), un filtro finale li toglie ancora, e ogni superficie ha il suo test.

/** La richiesta di una ricerca: il testo, i chip modificati dall'utente (al posto di quelli dell'analisi) e "Mostra anche…" (E-Q12). */
data class SearchRequest(
  val text: String,
  val editedChips: List<SearchChip>? = null,
  val showLikely: Boolean = false,
  val order: ResultOrder = ResultOrder.RECENT,
)

/** L'ordine dei risultati (D48): "Recenti" (predefinito) o "Pertinenti". Ricordato come impostazione personale (`search_order`). */
enum class ResultOrder(val code: String) {
  RECENT("recent"),
  RELEVANT("relevant"),
  ;

  companion object {
    fun fromCode(code: String?): ResultOrder = entries.firstOrNull { it.code == code } ?: RECENT
  }
}

/** Perche' una cella e' ancora senza vettore o senza testo (D31). */
enum class CoverageReason {
  PREPARATION,
  INDEXER_RULES,
  NO_INDEXER_FOR_AREA,
  MODEL_MISSING,
  PHONE_ONLY_NOT_INDEXER,
  UNSUPPORTED,
  /** Il testo: nessun dispositivo dell'area ha la lettura del testo attiva (D42). Detto una volta nella pagina, mai come avviso. */
  TEXT_READING_OFF,
}

/** Quanto copre la ricerca per contenuto (D31). Conteggi **senza nascosti**. */
sealed interface SearchCoverage {
  data object Complete : SearchCoverage

  data class Partial(val covered: Int, val total: Int, val reasons: Map<CoverageReason, Int>) : SearchCoverage {
    val fraction: Float get() = if (total == 0) 1f else covered.toFloat() / total
  }

  data class ModelMissing(val sizeBytes: Long) : SearchCoverage

  /** Ricetta o spazio nuovi in corso (D21, E-A10): "Aggiornamento della ricerca: 40%". */
  data class Updating(val percent: Int) : SearchCoverage

  /** Vero se in Cerca va l'avviso (solo sotto il 98%, D31). */
  val warns: Boolean get() = this is Partial && fraction < SearchThresholds.COVERAGE_WARN_BELOW
}

/** Se e come si puo' cercare adesso (D35, E-Q11). Filtri e testo funzionano sempre, anche offline. */
sealed interface SearchReadiness {
  data object Ready : SearchReadiness

  /** Il motore delle query si sta caricando: "Sto preparando la ricerca per contenuto…". */
  data object PreparingContent : SearchReadiness

  /** Senza ricerca per contenuto (modello assente o motore guasto): solo filtri e testo. */
  data class FiltersAndTextOnly(val modelMissing: Boolean) : SearchReadiness
}

/** Gli avvisi sopra i risultati (§1.5). */
sealed interface SearchNotice {
  data object PreparingContentSearch : SearchNotice

  data object OnlyFiltersAndText : SearchNotice

  /** "Nessun dispositivo analizza l'area comune" (E-I12): l'area come codice, mai il nome della share. */
  data class NoIndexerForArea(val commonArea: Boolean) : SearchNotice

  data class TypoCorrected(val from: String, val to: String) : SearchNotice

  /** Parole cercate con la lettura del testo spenta ovunque (una volta, poi solo nella pagina, D31). */
  data object TextReadingOff : SearchNotice
}

/**
 * I risultati (D28): una cella per gruppo, **sicuri** e **meno probabili** in due viste ordinate (stesso ordine scelto, D48). Le viste
 * sono vive rispetto all'istantanea da cui nascono: un media nascosto o eliminato esce (E-Q13) alla ricerca successiva.
 */
data class SearchResults(
  val chips: List<SearchChip>,
  val confident: OrderedTimelineView,
  val likely: OrderedTimelineView,
  val coverage: SearchCoverage,
  val notices: List<SearchNotice>,
  val order: ResultOrder,
  /**
   * Della query, oltre ai [chips], resta qualcosa da cercare (contenuto, parole libere): togliere l'ultimo chip non deve buttare via la query
   * (CE-2). Falso per una query di soli filtri ("video di ieri"): senza chip non resta niente, e Cerca torna a riposo.
   */
  val freeText: Boolean = false,
)

/** Il motore (D23-D31). Mai un'eccezione per un indice incompleto: risponde con cio' che ha e lo dice ([SearchResults.coverage]). */
interface SearchEngine {
  suspend fun search(request: SearchRequest): SearchResults

  /** "Trova simili" (D30): il vettore del media come query; esclusi la cella e il gruppo del media; mai dai Nascosti. */
  suspend fun similar(media: MediaId, order: ResultOrder = ResultOrder.RECENT): SearchResults

  val coverage: StateFlow<SearchCoverage>

  val readiness: StateFlow<SearchReadiness>

  /** Cerca si apre (D35, E-Q11, CE-9): il motore delle query comincia a caricarsi, cosi' la prima ricerca non lo aspetta. Di serie niente. */
  fun warmUp() {}
}

/**
 * Le soglie di convinzione (D27) e i parametri dell'ordine (D48), **valori di partenza prudenti**: P9 li calibra sul set privato
 * (§5.3) e li scrive qui con i numeri in docs/decisioni.md; un test li rilegge.
 */
object SearchThresholds {
  /** Sicuri: z >= ZC e s >= S_MIN. */
  const val ZC: Float = 3.5f
  /** Meno probabili: ZL <= z < ZC. */
  const val ZL: Float = 2.0f
  const val S_MIN: Float = 0f
  /** "Trova simili" ha soglie sue (distribuzioni immagine-immagine diverse). */
  const val ZS: Float = 3.5f
  const val ZLS: Float = 2.0f
  /** Al massimo tante celle fra i meno probabili. */
  const val MAX_LIKELY: Int = 300
  /** L'avviso di copertura solo sotto questa frazione. */
  const val COVERAGE_WARN_BELOW: Float = 0.98f
  /** Il fattore della deviazione robusta (sigma = 1,4826 x MAD). */
  const val MAD_SCALE: Float = 1.4826f
  /** RRF (fusione per ranghi). */
  const val RRF_K: Int = 60
}

/** I parametri dell'ordine "Pertinenti" (D48, §2.4): mezza vita della recenza e peso della recenza. Tarati in P9. */
data class RankParams(val halfLifeDays: Int = 90, val recencyWeight: Float = 0.10f)

/**
 * L'ORDINE DEI RISULTATI (D48, §2.4): la formula, pura e condivisa dal motore di P6 e dall'esecutore di riferimento.
 * - **Pertinenti**: chiave `p + beta * 2^(-a/H)` decrescente, a parita' la data (piu' recente prima).
 * - **Recenti**: per periodo dal piu' recente (stesso giorno per gli ultimi 31 giorni, stesso mese fino a 2 anni, stesso anno oltre),
 *   dentro il periodo per pertinenza `p`, poi per data.
 */
object ResultRanking {

  /** La recenza di uno scatto di [ageDays] giorni fa (0 = oggi): 1, 0,5 dopo una mezza vita. */
  fun recency(ageDays: Int, params: RankParams = RankParams()): Float =
    Math.pow(2.0, -ageDays.coerceAtLeast(0).toDouble() / params.halfLifeDays).toFloat()

  /** La recenza di uno scatto del giorno [day] visto [today]; uno scatto **senza data** non e' recente per niente: 0 (CE-12). */
  fun recency(day: LocalDate?, today: LocalDate, params: RankParams = RankParams()): Float =
    if (day == null) 0f else recency(java.time.temporal.ChronoUnit.DAYS.between(day, today).toInt(), params)

  /** Il periodo di uno scatto senza data valida: piu' vecchio di ogni altro, quindi in fondo a "Recenti" (CE-12). */
  const val UNDATED_PERIOD: Long = Long.MIN_VALUE

  /**
   * Il periodo di "Recenti" di uno scatto del giorno [day] visto [today], come il giorno in cui il periodo comincia (crescente col
   * tempo anche fra granularita' diverse: un periodo di un mese o di un anno comincia sempre prima dei giorni degli ultimi 31).
   * Senza data ([day] null): [UNDATED_PERIOD], il piu' vecchio di tutti (prima valeva "oggi" e le foto senza data stavano in cima).
   */
  fun period(day: LocalDate?, today: LocalDate): Long {
    if (day == null) return UNDATED_PERIOD
    val age = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    return when {
      age <= 31 -> day.toEpochDay()
      age <= 730 -> day.withDayOfMonth(1).toEpochDay()
      else -> day.withDayOfYear(1).toEpochDay()
    }
  }

  /**
   * Un comparatore delle celle (pertinenza in 0..1, giorno dello scatto, istante per la parita'), dal primo all'ultimo da mostrare.
   */
  fun comparator(order: ResultOrder, today: LocalDate, params: RankParams = RankParams()): Comparator<Ranked> = when (order) {
    ResultOrder.RELEVANT -> compareByDescending<Ranked> {
      it.relevance + params.recencyWeight * recency(it.day, today, params)
    }.thenByDescending { it.sortTime }
    ResultOrder.RECENT -> compareByDescending<Ranked> { period(it.day, today) }
      .thenByDescending { it.relevance }
      .thenByDescending { it.sortTime }
  }

  /** Una cella da ordinare; [day] null = scatto senza data valida (CE-12). */
  data class Ranked(val index: Int, val relevance: Float, val day: LocalDate?, val sortTime: Long)

  /** La pertinenza (§2.4) dal punteggio standardizzato del contenuto [z] (o null) e dal testo [textScore] (1, 0,6, 0). */
  fun relevance(z: Float?, textScore: Float): Float {
    val pc = if (z == null) 0f else ((z - SearchThresholds.ZL) / (SearchThresholds.ZC + 2 - SearchThresholds.ZL)).coerceIn(0f, 1f)
    val bonus = if (pc > 0f && textScore > 0f) 0.1f else 0f
    return (maxOf(pc, textScore) + bonus).coerceAtMost(1f)
  }
}

/** La data di oggi: la data del telefono, solo per interpretare le query (D39). La implementa :app. */
fun interface Today {
  fun date(): LocalDate
}

/** Il nome di un luogo per id (sopra `ReverseGeocoder.place(id)` di core-places, legato da :app). */
data class PlaceName(val name: String, val admin1: String?, val country: String)

fun interface PlaceLookup {
  fun place(id: Int): PlaceName?
}

/**
 * Cio' che il motore legge del catalogo (P1), legato da :app. Firma di partenza: P6 la puo' rifinire per l'indice a due passate (file
 * dei vettori mappato, D24), ma resta l'unica strada dei dati verso la ricerca.
 */
interface SearchData {
  /** L'istantanea della Libreria (senza nascosti ne' membri di gruppo): i candidati. */
  val snapshot: StateFlow<TimelineSnapshot?>

  /** I vettori di ogni cella (foto: uno; video: uno per fotogramma; gruppo: anche quelli dei membri) nello [space]. */
  suspend fun vectors(ids: Collection<MediaId>, space: EmbeddingSpace): Map<MediaId, List<FloatArray>>

  /** Il testo letto di ogni cella, gia' normalizzato (D13: minuscolo, senza accenti, apostrofi separati). */
  suspend fun texts(ids: Collection<MediaId>): Map<MediaId, String>

  /** Quali di [ids] sono nascosti adesso (il filtro finale, D29). */
  suspend fun hidden(ids: Collection<MediaId>): Set<MediaId>
}

/** Un suggerimento (§2.6): mai parole del testo letto (il vocabolario contiene anche i nascosti, D29). */
sealed interface Suggestion {
  val label: String

  data class Recent(val entry: HistoryEntry) : Suggestion {
    override val label: String get() = entry.text
  }

  data class PlaceSuggestion(val option: PlaceOption) : Suggestion {
    override val label: String get() = option.label
  }

  data class Type(val type: SearchMediaType, override val label: String) : Suggestion

  data class Term(override val label: String) : Suggestion
}

/** Cerca a riposo (§2.6): ricerche recenti (fino a 10), luoghi piu' frequenti (fino a 8, mai da nascosti), tipi. */
data class IdleSuggestions(val recents: List<HistoryEntry>, val places: List<PlaceOption>, val types: List<SearchMediaType>)

interface Suggestions {
  suspend fun idle(): IdleSuggestions

  suspend fun complete(prefix: String): List<Suggestion>
}

/** Una ricerca recente (D32): testo e chip come etichette, **senza miniature**. */
data class HistoryEntry(val id: Long, val text: String, val chipLabels: List<String>)

/** La cronologia locale (D32): al massimo 50 voci, mai sincronizzata, tolta dall'azzeramento. */
interface SearchHistory {
  val entries: StateFlow<List<HistoryEntry>>

  suspend fun record(text: String, chips: List<SearchChip>)

  suspend fun delete(id: Long)

  suspend fun clear()

  companion object {
    const val MAX_ENTRIES: Int = 50
  }
}

/** Un rettangolo normalizzato (0..1) sull'immagine raddrizzata. */
data class NormBox(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** Gli estremi di una parola lungo la riga (0..1 della lunghezza della riga). */
data class TextWord(val start: Float, val end: Float)

/** Una riga di testo letto per il testo dal vivo (D43). */
data class TextLine(val box: NormBox, val angleDegrees: Int, val words: List<TextWord>, val text: String) {
  /** Mai il testo (D40). */
  override fun toString(): String = "TextLine(${words.size} parole)"
}

class TextLayout(val lines: List<TextLine>)

/** Il testo letto di un media dall'indice locale (`ai_text`), per il testo dal vivo: :app lo lega a `ViewerTextSource` (D43). */
fun interface MediaText {
  suspend fun layout(media: MediaId): TextLayout?
}

/** I codici della Diagnostica della ricerca (§2.6). */
object SearchDiag {
  val SEARCH_INDEX_REBUILT = DiagCode("SEARCH_INDEX_REBUILT")

  val ALL: List<DiagCode> = listOf(SEARCH_INDEX_REBUILT)
}
