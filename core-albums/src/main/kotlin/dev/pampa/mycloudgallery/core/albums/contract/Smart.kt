package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import kotlin.time.Duration

// GLI ALBUM SMART (docs/fase7-progetto.md §1.2 "Regole", "Interpretazione", "Motore", "Scelte"; §2.2; D13-D17, D36). Contratti di P0; il
// motore (interpretazione del nome con `ItalianQueryParser`, raffinamento sulle foto, classi sicuri/dubbi, ricalcolo, congela) e' di P6.
// Giorni sempre **locali dello scatto** (`local_day`, D36): un viaggio in un altro fuso resta nei suoi giorni (E-AL10). Mai un nascosto
// fra candidati, sicuri, suggeriti, anteprime (D14, D22).

/** Un intervallo di giorni locali dello scatto, estremi compresi (D36). Una serie a cavallo del 31/12 resta una (E-AL1). */
data class LocalDayRange(val from: LocalDate, val to: LocalDate) {
  init {
    require(!to.isBefore(from)) { "Intervallo di giorni rovesciato." }
  }

  operator fun contains(day: LocalDate): Boolean = !day.isBefore(from) && !day.isAfter(to)

  /** Quanti giorni (almeno 1). */
  val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

  /** Lo stesso intervallo allargato di [n] giorni per parte (il ± 1 giorno del raffinamento, D13). */
  fun widen(n: Long): LocalDayRange = LocalDayRange(from.minusDays(n), to.plusDays(n))

  /** Mai le date nei log (dicono dove si era e quando). */
  override fun toString(): String = "LocalDayRange($days giorni)"
}

/** Il periodo di un album smart (D13): una o piu' serie di giorni, o un periodo che torna ogni anno, o i giorni di una festa mobile. */
sealed interface SmartPeriod {
  operator fun contains(day: LocalDate): Boolean

  /** Una o piu' serie di giorni (E-AL12: due viaggi nello stesso luogo nello stesso anno). Mai vuota, ordinate, senza sovrapposizioni. */
  data class Ranges(val ranges: List<LocalDayRange>) : SmartPeriod {
    init {
      require(ranges.isNotEmpty()) { "Periodo senza giorni." }
      require(ranges.zipWithNext().all { (a, b) -> a.to.isBefore(b.from) }) { "Serie non ordinate o sovrapposte." }
    }

    override fun contains(day: LocalDate): Boolean = ranges.any { day in it }

    override fun toString(): String = "SmartPeriod.Ranges(${ranges.size})"
  }

  /** Ogni anno fra [from] e [to] ("Estate in Puglia"): se [to] viene prima di [from] il periodo attraversa il capodanno. */
  data class EveryYear(val from: MonthDay, val to: MonthDay) : SmartPeriod {
    override fun contains(day: LocalDate): Boolean {
      val md = MonthDay.from(day)
      return if (!to.isBefore(from)) !md.isBefore(from) && !md.isAfter(to) else !md.isBefore(from) || !md.isAfter(to)
    }

    override fun toString(): String = "SmartPeriod.EveryYear"
  }

  /** Un insieme di giorni (una festa mobile "tutti gli anni", come `SearchChip.DateSet`). Mai vuoto. */
  data class Days(val days: Set<LocalDate>) : SmartPeriod {
    init {
      require(days.isNotEmpty()) { "Periodo senza giorni." }
    }

    override fun contains(day: LocalDate): Boolean = day in days

    override fun toString(): String = "SmartPeriod.Days(${days.size})"
  }
}

/** Che luogo copre una regola (§2.2 "Raggio"): attorno a un punto (citta' 25 km, vicino 15 km), una regione (`admin1`), un paese. */
enum class PlaceScope(override val code: String) : StableCode {
  AROUND("around"),
  REGION("region"),
  COUNTRY("country"),
}

/**
 * Il luogo di un album smart (D13): gli id GeoNames ([placeIds], piu' di uno per un viaggio in piu' luoghi), il [center] e il raggio per
 * [PlaceScope.AROUND] (da [SmartLimits.MIN_RADIUS_KM] a [SmartLimits.MAX_RADIUS_KM]). [label] e' gia' in italiano ("Oslo").
 */
data class PlaceRule(val placeIds: List<Int>, val center: GeoPoint, val radiusKm: Int, val label: String, val scope: PlaceScope) {
  init {
    require(placeIds.isNotEmpty()) { "Un luogo senza id." }
    require(radiusKm in SmartLimits.MIN_RADIUS_KM..SmartLimits.MAX_RADIUS_KM) { "Raggio fuori dai limiti." }
  }

  /** Mai il luogo nei log (D37). */
  override fun toString(): String = "PlaceRule(${scope.code}, ${placeIds.size} luoghi)"
}

/** Da dove vengono i media che un album smart considera (§1.2): i miei del telefono, la mia area privata, l'area comune, i familiari. */
enum class SmartSource(override val code: String) : StableCode {
  MY_PHONE("my_phone"),
  MY_PRIVATE("my_private"),
  COMMON("common"),
  FAMILY("family"),
  ;

  companion object {
    val ALL: Set<SmartSource> = entries.toSet()
  }
}

/** I tipi di media di un album smart. Screenshot e registrazioni dello schermo sono sempre e solo suggeriti (E-AL4). */
enum class SmartMediaType(override val code: String) : StableCode {
  PHOTO("photo"),
  VIDEO("video"),
  ;

  companion object {
    val ALL: Set<SmartMediaType> = entries.toSet()
  }
}

/**
 * Le regole di un album smart (D13, §1.2), tutte modificabili prima di creare. [period] e [place] possono mancare (E-AL9: solo
 * descrizione). [text] e [examples] valgono solo con il motore di ricerca (D15); al piu' [SmartLimits.MAX_EXAMPLES] esempi.
 *
 * Fase 8 (D27, firma di P0): [people] = "con Marco", "con Marco e Anna" ([PeopleRule]); si combina in "e" con le altre regole. Il campo
 * personale `smart:people` e la regola nel motore smart sono di P10; dove i volti non ci sono la regola non inserisce niente (E-F37).
 */
data class SmartRules(
  val period: SmartPeriod?,
  val place: PlaceRule?,
  val text: String?,
  val examples: List<MediaId> = emptyList(),
  val sources: Set<SmartSource> = SmartSource.ALL,
  val types: Set<SmartMediaType> = SmartMediaType.ALL,
  val people: PeopleRule? = null,
) {
  init {
    require(examples.size <= SmartLimits.MAX_EXAMPLES) { "Troppe foto d'esempio." }
    require(sources.isNotEmpty() && types.isNotEmpty()) { "Nessuna sorgente o nessun tipo." }
  }

  /** Mai la descrizione, il luogo ne' le persone nei log. */
  override fun toString(): String =
    "SmartRules(period=${period != null}, place=${place != null}, text=${text != null}, examples=${examples.size}, people=${people != null})"
}

/** I numeri del §2.2 e di D14, D16. P6 li usa, P12 li misura; un cambio passa dal progetto. */
object SmartLimits {
  const val MAX_EXAMPLES: Int = 6
  const val MIN_RADIUS_KM: Int = 5
  const val MAX_RADIUS_KM: Int = 300
  const val CITY_RADIUS_KM: Int = 25
  const val NEAR_RADIUS_KM: Int = 15

  /** Regola B: foto senza GPS dello stesso dispositivo fra due sicuri a meno di 6 ore da ciascuno. */
  const val RULE_B_WINDOW_HOURS: Int = 6

  /** S3: GPS fino a 2 volte il raggio = suggerito. */
  const val NEAR_MISS_FACTOR: Int = 2

  /** Il ± 1 giorno del raffinamento del periodo (D13). */
  const val REFINE_MARGIN_DAYS: Long = 1

  /** Regole troppo larghe (E-AL6): oltre 3.000 elementi o oltre il 20% della Libreria. */
  const val TOO_BROAD_ITEMS: Int = 3_000
  const val TOO_BROAD_FRACTION: Double = 0.20

  /** Ricalcolo a lotti di candidati (D16). */
  const val RECOMPUTE_BATCH: Int = 2_000

  /** Miniature dell'anteprima (§2.2). */
  const val PREVIEW_SAMPLE: Int = 12
}

/** Un'alternativa per un luogo omonimo (E-AL2): "Oslo (Norvegia)" o un'altra Oslo, con quanti media visibili ci sono. */
data class PlaceAlternative(val rule: PlaceRule, val mediaCount: Int) {
  override fun toString(): String = "PlaceAlternative($mediaCount)"
}

/** Cosa l'editor dice sotto la proposta (§1.5 `SmartEditorState`). */
enum class ProposalNote {
  /** Nessuna data nel titolo: nessun periodo. */
  NO_DATE,

  /** Nessun luogo nel titolo. */
  NO_PLACE,

  /** Un luogo senza foto proprie geolocalizzate nel periodo: periodo intero del titolo (D13). */
  NO_PHOTOS_IN_PLACE,

  /** Piu' serie di giorni (E-AL12): l'editor le mostra tutte e lascia sceglierne una. */
  SEVERAL_SERIES,

  /** "La descrizione si usera' quando la ricerca per contenuto sara' pronta" (D15, E-AL9). */
  NO_SEARCH,

  /** Le foto d'esempio non si usano finche' il motore non ha `similarToAll` (§2.9). */
  EXAMPLES_UNSUPPORTED,
}

/** La proposta dell'editor (D13): regole, omonimi, serie di giorni del raffinamento, note. */
data class RuleProposal(
  val rules: SmartRules,
  val alternatives: List<PlaceAlternative>,
  val periodSeries: List<LocalDayRange>,
  val notes: Set<ProposalNote>,
) {
  override fun toString(): String = "RuleProposal(${alternatives.size} alternative, ${periodSeries.size} serie, $notes)"
}

/** Il nome diventa regole (D13): `ItalianQueryParser` e il raffinamento sulle foto. Puro sulla JVM con le porte del catalogo. */
fun interface SmartNameInterpreter {
  suspend fun propose(title: String, today: LocalDate): RuleProposal
}

/** L'anteprima prima di creare o cambiare le regole (§2.2): conteggi senza nascosti, [sample] al piu' [SmartLimits.PREVIEW_SAMPLE]. */
data class SmartPreview(
  val confident: Int,
  val suggested: Int,
  val tooBroad: Boolean,
  val sample: List<MediaId>,
  /** Se descrizione ed esempi hanno contato (motore pronto, D15). */
  val searchUsed: Boolean,
  /**
   * Fase 8 (E-F37): falso se le regole hanno le persone e qui i volti non ci sono (Persone spenta o esclusa): la regola non inserisce niente e l'anteprima
   * e' vuota; l'editor dice "Le persone non sono attive su questo dispositivo". Vero (di serie) per ogni altra regola.
   */
  val peopleAvailable: Boolean = true,
  /** Quante persone della regola non sono piu' riconosciute (dimenticate, E-F27): l'editor dice "Una persona della regola non e' piu' riconosciuta". */
  val peopleLost: Int = 0,
)

/** L'esito di un ricalcolo (D16). */
sealed interface SmartOutcome {
  data class Done(val confident: Int, val suggested: Int, val newSinceSeen: Int) : SmartOutcome

  /** Il budget e' finito: riprende dal punto a cui era arrivato. */
  data class Partial(val done: Int, val total: Int) : SmartOutcome

  /** Congelato: le regole non agiscono (E-AL14). */
  data object Frozen : SmartOutcome

  data object NotFound : SmartOutcome
}

/** Il motore degli album smart (D14-D16). Mai un nascosto: candidati da `VisibleMedia`, filtro finale `HiddenMask`. */
interface SmartAlbumEngine {
  suspend fun evaluate(id: AlbumId, budget: Duration): SmartOutcome

  suspend fun preview(rules: SmartRules): SmartPreview
}

/**
 * Le scelte dell'utente su un album smart (voci D4, sincronizzate, per sempre: E-AL5, E-AL13). "Congela" fa degli elementi di quel
 * momento elementi manuali con impronta (D16); "Scongela" riattiva le regole tenendo esclusioni e accettazioni.
 */
interface SmartChoices {
  suspend fun exclude(id: AlbumId, media: List<MediaId>): EditOutcome

  suspend fun accept(id: AlbumId, media: List<MediaId>): EditOutcome

  suspend fun freeze(id: AlbumId): EditOutcome

  suspend fun unfreeze(id: AlbumId): EditOutcome
}
