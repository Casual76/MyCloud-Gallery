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
   * [everyYear] = solo mese e giorno contano ("Agosto (tutti gli anni)", "Natale"). [person]: la stessa parola e' anche il nome di una persona
   * che ha meno media (E-F36): il foglio del chip propone "Cerca la persona".
   */
  data class DateRange(
    val from: LocalDate,
    val to: LocalDate,
    val everyYear: Boolean,
    override val label: String,
    val person: PersonOption? = null,
  ) : SearchChip {
    init {
      require(!to.isBefore(from) || everyYear) { "Periodo rovesciato." }
    }
  }

  /**
   * Un **insieme di giorni** (P6, richiesta di P7): una festa mobile "tutti gli anni" ("Pasqua" senza anno = ogni domenica di
   * Pasqua dal [FIRST_YEAR] a oggi). Come [DateRange], i giorni **nell'ora del posto dello scatto**. Mai vuoto, al piu' [MAX_DAYS].
   */
  data class DateSet(val days: Set<LocalDate>, override val label: String, val person: PersonOption? = null) : SearchChip {
    init {
      require(days.isNotEmpty()) { "Insieme di giorni vuoto." }
      require(days.size <= MAX_DAYS) { "Troppi giorni." }
    }

    /** Mai i giorni (sono tanti): solo quanti. */
    override fun toString(): String = "DateSet(${days.size} giorni, $label)"

    companion object {
      const val MAX_DAYS: Int = 1_000

      /** Il primo anno delle feste mobili "tutti gli anni" (le foto scansionate possono essere vecchie). */
      const val FIRST_YEAR: Int = 1900
    }
  }

  /**
   * Un luogo, una regione o un paese; [alternatives] sono gli omonimi e "Cerca come parola" (E-Q10) lo propone la UI. [person]: la stessa
   * parola e' anche il nome di una persona che ha meno media (E-F36): il foglio del chip propone "Cerca la persona".
   */
  data class Place(
    val option: PlaceOption,
    val alternatives: List<PlaceOption>,
    val word: String,
    val person: PersonOption? = null,
  ) : SearchChip {
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

  /**
   * Fase 8 (D26, §2.7): una o piu' **persone con un nome** dal dizionario ([PeopleDictionary]), "una qualunque" o "tutte"
   * ([PersonOption.mode]). E' un **filtro**: i candidati sono le celle con un volto assegnato alle persone, dalla porta [PeopleLookup]
   * (P10), mai dai nascosti. [alternatives] sono le altre letture della stessa parola (una festa, un luogo, "Cerca come parola", E-F36,
   * come E-Q10); [word] e' la parola della query. L'etichetta e' un **nome**: mai nei log; la cronologia la salva come testo e "Disattiva e
   * cancella" la toglie (`SearchHistoryPurge`, D29).
   */
  data class Person(val option: PersonOption, val alternatives: List<ChipAlternative>, val word: String) : SearchChip {
    override val label: String get() = option.label

    /** Mai il nome ne' gli id (D37). */
    override fun toString(): String = "SearchChip.Person(${option.ids.size}, ${option.mode.code})"
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

  /**
   * Fase 8 (D26, §2.7): la stessa analisi con il dizionario delle **persone** ("Marco al mare", "Marco e Anna 2024"). Di serie ignora
   * [people] (nessun chip "Persona": il nome resta testo per il contenuto, come quando Persone e' spenta); l'analizzatore italiano di P10
   * la riscrive (dopo le virgolette e prima dei luoghi, la corrispondenza piu' lunga di un nome).
   */
  fun parse(text: String, today: LocalDate, places: PlaceDictionary, people: PeopleDictionary): ParsedQuery = parse(text, today, places)

  companion object {
    const val MAX_QUERY_CHARS: Int = 200
  }
}
