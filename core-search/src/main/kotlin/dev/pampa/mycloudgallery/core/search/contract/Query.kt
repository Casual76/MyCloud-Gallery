package dev.pampa.mycloudgallery.core.search.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.LocalDate

// LE QUERY (docs/fase6-progetto.md §1.2 "core-search", §2.5, D26, E-Q1-E-Q10). Contratti di P0; l'analizzatore italiano vero (date,
// feste, stagioni, luoghi, tipi, refusi) e' di P7 (`parse/`). Il fake semplice per i test sta in `testing/`.

/** Il tipo di media chiesto da una query o da un chip (§2.5). */
enum class SearchMediaType { PHOTO, VIDEO, SCREENSHOT, PANORAMA, MOTION_PHOTO, DOCUMENT }

/** Gli stati personali di una cella chiedibili con una parola ("preferiti", "modificate"). */
enum class SearchFlag { FAVORITE, EDITED }

/** Dove sta il media (§2.5: "sul telefono", "sul NAS", "solo sul NAS"). */
enum class SearchPresence { ON_PHONE, ON_NAS, NAS_ONLY }

/** Che tipo di luogo e' un chip di luogo: il luogo, la sua regione (`admin1`), il paese. */
enum class PlaceLevel { PLACE, REGION, COUNTRY }

/** Un'interpretazione di un luogo: gli id GeoNames che copre e come dirlo. */
data class PlaceOption(val ids: Set<Int>, val label: String, val level: PlaceLevel)

/**
 * Un chip della barra (§1.5, D26): l'interpretazione scelta, sempre visibile e modificabile (E-Q2). Le etichette sono gia' in
 * italiano (le scrive l'analizzatore, una volta). Mai mostrati per i nascosti (D29).
 */
sealed interface SearchChip {
  val label: String

  /**
   * Un periodo di giorni **nell'ora del posto dello scatto** (`local_day`, come le intestazioni della Libreria), estremi inclusi.
   * [everyYear] = solo mese e giorno contano ("Agosto (tutti gli anni)", "Natale").
   */
  data class DateRange(val from: LocalDate, val to: LocalDate, val everyYear: Boolean, override val label: String) : SearchChip {
    init {
      require(!to.isBefore(from) || everyYear) { "Periodo rovesciato." }
    }
  }

  /** Un luogo, una regione o un paese; [alternatives] sono gli omonimi e "Cerca come parola" (E-Q10) lo propone la UI. */
  data class Place(val option: PlaceOption, val alternatives: List<PlaceOption>, val word: String) : SearchChip {
    override val label: String get() = option.label
  }

  data class MediaType(val type: SearchMediaType, override val label: String) : SearchChip

  data class Source(val source: MediaSource, override val label: String) : SearchChip

  data class Flag(val flag: SearchFlag, override val label: String) : SearchChip

  data class Presence(val where: SearchPresence, override val label: String) : SearchChip

  /** "Simili a" (D30): la miniatura la mostra la UI dal [media]. */
  data class Similar(val media: MediaId, override val label: String) : SearchChip

  /** Una parola o una frase da cercare nel testo letto; [exact] = fra virgolette (testo esatto). */
  data class Word(val text: String, val exact: Boolean) : SearchChip {
    override val label: String get() = if (exact) "“$text”" else text
  }

  /** I chip che restringono i candidati (filtri). Le parole e "simili a" no: decidono il punteggio. */
  val isFilter: Boolean get() = this !is Word && this !is Similar
}

/**
 * Il risultato dell'analisi di una query (§2.5): i chip, il testo per la ricerca per contenuto (il resto, con le parole vuote: il
 * modello le usa; null se non resta niente), le parole da cercare nel testo letto (senza parole vuote) e le frasi esatte.
 */
data class ParsedQuery(
  val chips: List<SearchChip>,
  val semantic: String?,
  val words: List<String>,
  val exact: List<String>,
  /** I refusi corretti sul dizionario (E-Q5): "Ho cercato «screenshot» invece di «screeshot»". */
  val corrections: List<Pair<String, String>> = emptyList(),
)

/** Un nome di luogo presente nella Libreria (§2.5): solo da celle **visibili**, mai dai nascosti (D29). */
data class PlaceEntry(
  /** Il nome normalizzato (minuscolo, senza accenti, apostrofi uniformi), anche di piu' parole ("monaco di baviera"). */
  val name: String,
  val option: PlaceOption,
  /** Quanti media visibili ci sono: fra omonimi vince il piu' frequente. */
  val mediaCount: Int,
)

/** Il dizionario dei luoghi della Libreria (§2.5). */
class PlaceDictionary(val entries: List<PlaceEntry>) {
  /** Gli omonimi di [name] (normalizzato), dal piu' frequente. */
  fun lookup(name: String): List<PlaceEntry> = entries.filter { it.name == name }.sortedByDescending { it.mediaCount }

  companion object {
    val EMPTY = PlaceDictionary(emptyList())
  }
}

/**
 * L'analizzatore italiano delle query (D26): a regole, deterministico, senza rete ne' modelli, puro (JVM). "Oggi" e' [today], la
 * data del telefono (D39), solo per interpretare. Query vuota o di una lettera: nessun chip, nessuna ricerca (E-Q1). Oltre 200
 * caratteri: troncata (E-Q9). Nessuna sintassi interpretata (E-Q9).
 */
fun interface QueryParser {
  fun parse(text: String, today: LocalDate, places: PlaceDictionary): ParsedQuery

  companion object {
    const val MAX_QUERY_CHARS: Int = 200
  }
}
