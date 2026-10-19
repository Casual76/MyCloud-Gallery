package dev.pampa.mycloudgallery.feature.backup.versions

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionPlan
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `CorrectionState`, §2.4 "Correzioni dall'app", D19, E-L8, E-E8, E-E9, L40, Q6): i tipi di "Correggi
// data e luogo" e "Ruota". Qui c'e' la parte che si prova senza comporre niente: la bozza che l'utente scrive (testi e scelte), la sua
// traduzione in una `CorrectionRequest` del contratto e i suoi errori. Data, luogo e fuso sono dell'utente: `toString` muti, mai nei log.

/** Cosa si apre: "Correggi data e luogo" (tutto) o "Ruota" (solo la rotazione). */
enum class CorrectionMode { ALL, ROTATE }

/**
 * Il media da correggere, con i valori di adesso per precompilare il foglio. Li legge `:app` dal catalogo (la versione attuale) quando apre il
 * foglio: i feature non si conoscono e questo modulo non legge il catalogo.
 *
 * @property takenAt data e ora **locali** di scatto di adesso (come le mostra la Libreria), null se il media non ha una data.
 * @property offsetMinutes il fuso del luogo di scatto in minuti dall'UTC, se noto; senza, vale il fuso del telefono.
 * @property placeLabel il nome del luogo di adesso ("Oslo"), null se non ne ha.
 */
@Immutable
class CorrectionSubject(
  val mediaId: MediaId,
  val takenAt: LocalDateTime? = null,
  val offsetMinutes: Int? = null,
  val placeLabel: String? = null,
  val hasLocation: Boolean = false,
  val orientation: ExifOrientation = ExifOrientation.NORMAL,
  val isVideo: Boolean = false,
) {
  override fun toString(): String = "CorrectionSubject(${if (isVideo) "video" else "foto"})"
}

/** Un luogo che si puo' scegliere: il nome e il punto. Il nome e' un dato dell'utente. */
@Immutable
class PlaceChoice(val label: String, val point: GeoPoint) {
  override fun toString(): String = "PlaceChoice"

  override fun equals(other: Any?): Boolean = other is PlaceChoice && other.label == label &&
    other.point.latitude == point.latitude && other.point.longitude == point.longitude

  override fun hashCode(): Int = label.hashCode() * 31 + point.latitude.hashCode() * 17 + point.longitude.hashCode()
}

/**
 * La ricerca di un luogo per nome (D19: "luogo"). `:app` la lega ai nomi di luogo dell'app se ne ha una (core-places); senza, il foglio chiede
 * le coordinate. Solo lettura: non scrive niente e non va in rete da qui.
 */
fun interface CorrectionPlaces {
  suspend fun search(text: String): List<PlaceChoice>
}

/** Cosa fare del luogo di un media. */
enum class PlaceEdit { KEEP, CHANGE, CLEAR }

/** Perche' la bozza non si puo' ancora confermare. */
enum class DraftError {
  /** La data non e' una data (gg/mm/aaaa). */
  DATE_INVALID,

  /** L'ora non e' un'ora (hh:mm). */
  TIME_INVALID,

  /** Si vuole cambiare il luogo e non ne e' stato scelto uno. */
  PLACE_MISSING,

  /** Le coordinate non sono due numeri nell'intervallo giusto. */
  COORDINATES_INVALID,

  /** Non c'e' niente da correggere. */
  NOTHING_TO_DO,
}

/**
 * Cio' che l'utente ha scritto e scelto nel foglio. [changeDate], [place] e [quarterTurns] dicono **cosa** si corregge: un campo non toccato
 * non entra nella correzione. Il fuso resta quello del media (o del telefono): la data si scrive "come la vedi".
 *
 * @property quarterTurns di quanti quarti di giro in senso orario si ruota (0..3).
 */
@Immutable
data class CorrectionDraft(
  val changeDate: Boolean = false,
  val dateText: String = "",
  val timeText: String = "",
  val place: PlaceEdit = PlaceEdit.KEEP,
  val placeQuery: String = "",
  val chosenPlace: PlaceChoice? = null,
  val coordinatesText: String = "",
  val quarterTurns: Int = 0,
) {
  init {
    require(quarterTurns in 0..3) { "I quarti di giro sono da 0 a 3." }
  }

  /** I campi che la bozza tocca, nei termini del modello. */
  val fields: Set<CorrectionField>
    get() = buildSet {
      if (changeDate) add(CorrectionField.DATE)
      if (place != PlaceEdit.KEEP) add(CorrectionField.LOCATION)
      if (quarterTurns != 0) add(CorrectionField.ROTATION)
    }

  override fun toString(): String = "CorrectionDraft(${fields.joinToString { it.code }})"

  companion object {
    /** La bozza di partenza per [subject]: i campi gia' scritti con i valori di adesso, nessuno acceso. */
    fun startingFrom(subject: CorrectionSubject): CorrectionDraft = CorrectionDraft(
      dateText = subject.takenAt?.let { CorrectionParsing.dateText(it.toLocalDate()) }.orEmpty(),
      timeText = subject.takenAt?.let { CorrectionParsing.timeText(it.toLocalTime()) }.orEmpty(),
    )
  }
}

/** L'esito della traduzione di una bozza: la richiesta del contratto, oppure gli errori che la bloccano. */
@Immutable
class DraftResult(val request: CorrectionRequest?, val errors: Set<DraftError>) {
  val isValid: Boolean get() = request != null
}

/** Le regole di lettura di cio' che l'utente scrive: pure, si provano su JVM. */
object CorrectionParsing {

  private const val MIN_YEAR = 1900
  private const val MAX_YEAR = 2100
  private val SEPARATORS = Regex("""[/.\-]""")

  fun dateText(date: LocalDate): String = "%02d/%02d/%04d".format(date.dayOfMonth, date.monthValue, date.year)

  fun timeText(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

  /** "gg/mm/aaaa" (anche con il punto o il trattino); null se non e' una data vera (31/02 non lo e'). */
  fun parseDate(text: String): LocalDate? {
    val parts = text.trim().split(SEPARATORS)
    if (parts.size != 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
    val (day, month, year) = parts.map { it.toIntOrNull() ?: return null }
    if (year !in MIN_YEAR..MAX_YEAR) return null
    return try {
      LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
      null
    }
  }

  /** "hh:mm" (anche "hh.mm"); null se non e' un'ora. */
  fun parseTime(text: String): LocalTime? {
    val parts = text.trim().split(':', '.')
    if (parts.size != 2 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
    val (hour, minute) = parts.map { it.toIntOrNull() ?: return null }
    return try {
      LocalTime.of(hour, minute)
    } catch (_: DateTimeException) {
      null
    }
  }

  /**
   * Due numeri: "45,4642 9,1900", "45.4642, 9.1900" o "45,4642; 9,1900". Latitudine e poi longitudine, con la virgola o il punto come
   * decimale. Null se non sono due numeri, o se escono dall'intervallo (la latitudine va da -90 a 90, la longitudine da -180 a 180).
   */
  fun parseCoordinates(text: String): GeoPoint? {
    val trimmed = text.trim()
    val parts = when {
      ';' in trimmed -> trimmed.split(';')
      trimmed.any(Char::isWhitespace) -> trimmed.split(Regex("""\s+""")).map { it.trimEnd(',') }
      trimmed.count { it == ',' } == 1 && '.' in trimmed -> trimmed.split(',')
      else -> return null
    }.map { it.trim() }
    if (parts.size != 2) return null
    val (lat, lon) = parts.map { it.replace(',', '.').toDoubleOrNull() ?: return null }
    if (!lat.isFinite() || !lon.isFinite()) return null
    return try {
      GeoPoint(lat, lon)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  /** L'orientamento dopo [turns] quarti di giro in senso orario. L'orientamento EXIF e' "prima lo specchio, poi la rotazione": i gradi si sommano. */
  fun rotated(current: ExifOrientation, turns: Int): ExifOrientation =
    if (turns % 4 == 0) current else ExifOrientation.of((current.degrees + 90 * turns) % 360, current.mirrored)
}

/**
 * Traduce la bozza nella richiesta del contratto per [subject]. In [CorrectionMode.ROTATE] conta solo la rotazione.
 * Gli errori sono tutti quelli che la bozza ha, non solo il primo: il foglio li mostra accanto ai campi giusti.
 */
fun CorrectionDraft.build(subject: CorrectionSubject, mode: CorrectionMode = CorrectionMode.ALL): DraftResult {
  val errors = linkedSetOf<DraftError>()
  var takenAt: Long? = null
  var offset: Int? = null
  var geo: GeoChange = GeoChange.Keep
  val withOthers = mode == CorrectionMode.ALL

  if (withOthers && changeDate) {
    val date = CorrectionParsing.parseDate(dateText)
    val time = CorrectionParsing.parseTime(timeText)
    if (date == null) errors += DraftError.DATE_INVALID
    if (time == null) errors += DraftError.TIME_INVALID
    if (date != null && time != null) {
      val local = LocalDateTime.of(date, time)
      val minutes = subject.offsetMinutes ?: (ZoneId.systemDefault().rules.getOffset(local).totalSeconds / SECONDS_PER_MINUTE)
      takenAt = local.toInstant(ZoneOffset.ofTotalSeconds(minutes * SECONDS_PER_MINUTE)).toEpochMilli()
      offset = minutes
    }
  }
  if (withOthers) {
    when (place) {
      PlaceEdit.KEEP -> Unit
      PlaceEdit.CLEAR -> geo = GeoChange.Clear
      PlaceEdit.CHANGE -> {
        val point = chosenPlace?.point
        when {
          point != null -> geo = GeoChange.Set(point)
          coordinatesText.isBlank() -> errors += DraftError.PLACE_MISSING
          else -> {
            val parsed = CorrectionParsing.parseCoordinates(coordinatesText)
            if (parsed == null) errors += DraftError.COORDINATES_INVALID else geo = GeoChange.Set(parsed)
          }
        }
      }
    }
  }
  val orientation = if (quarterTurns != 0) CorrectionParsing.rotated(subject.orientation, quarterTurns) else null

  if (errors.isNotEmpty()) return DraftResult(null, errors)
  val change = MetadataChange(takenAtUtcMillis = takenAt, offsetMinutes = offset, geo = geo, orientation = orientation)
  if (change.isEmpty) return DraftResult(null, setOf(DraftError.NOTHING_TO_DO))
  return DraftResult(CorrectionRequest(change), emptySet())
}

private const val SECONDS_PER_MINUTE = 60

/** Una frase che il foglio dice dopo che qualcosa e' cambiato o non e' andato: un codice, il testo e' nelle stringhe. */
enum class CorrectionMessage {
  /** Il piano e' cambiato dopo il foglio (il file e' cambiato): ricalcolato, si riconferma. */
  PLAN_CHANGED,

  /** E' passato troppo tempo fra il riepilogo e il tocco. */
  CONFIRMATION_EXPIRED,

  /** L'utente non ha dato il permesso del sistema: il file del telefono non e' stato toccato. */
  PERMISSION_REFUSED,

  /** Il dialogo del sistema non c'e' su questo telefono. */
  NO_SYSTEM_DIALOG,
}

/** Cosa mostra il foglio delle correzioni (§1.5 `CorrectionState`). */
@Immutable
internal sealed interface CorrectionUi {

  /** Il foglio si sta aprendo: il media non e' ancora stato letto. */
  data object Loading : CorrectionUi

  /** La funzione non e' ancora legata (nessun servizio delle correzioni): il foglio lo dice. */
  data object Unavailable : CorrectionUi

  /**
   * `Editing(fields)`: l'utente scrive. [plan] e' il piano per la richiesta di adesso (null finche' la bozza non e' valida o il piano non e' tornato);
   * [fields] sono i campi toccati.
   */
  data class Editing(
    val subject: CorrectionSubject,
    val mode: CorrectionMode,
    val draft: CorrectionDraft,
    val errors: Set<DraftError>,
    val plan: CorrectionPlan?,
    val places: List<PlaceChoice> = emptyList(),
    val canSearchPlaces: Boolean = false,
    val message: CorrectionMessage? = null,
    /** Dal piano di prova all'apertura: `FORMAT_NOT_WRITABLE` = la correzione resta nell'app e il file non cambia (Q6). */
    val readiness: CorrectionReadiness = CorrectionReadiness.READY,
  ) : CorrectionUi {
    val fields: Set<CorrectionField> get() = draft.fields
  }

  /** Un media del telefono non e' ancora al sicuro: prima il backup e la verifica (D19). */
  data class NeedsSafeFirst(val subject: CorrectionSubject) : CorrectionUi

  /** Il sistema chiede il permesso di riscrivere la riga del telefono. */
  data class AwaitingSystemDialog(val rows: Int) : CorrectionUi

  /** Si scrive la copia corretta, si rilegge e si decodifica. */
  data object Writing : CorrectionUi

  /** La correzione e' sul telefono (e nell'app); il backup la porta sul NAS come versione. [onlyInApp]: il file non e' stato toccato (HEIC, RAW, video). */
  data class Uploading(val onlyInApp: Boolean) : CorrectionUi

  /** Fatto: la versione corretta e' al sicuro sul NAS. [onlyInApp]: corretta solo nell'app, il file non cambia (Q6). */
  data class Done(val onlyInApp: Boolean) : CorrectionUi

  data class Failed(val code: CorrectionFailure) : CorrectionUi

  /** Il piano non permette la correzione (area comune, sola lettura, NAS, congelamento...). */
  data class Blocked(val reason: CorrectionReadiness) : CorrectionUi
}
