package dev.pampa.mycloudgallery.core.model.domain

import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Un membro di un gruppo come lo vede la scelta della copertina: quando e' stato scattato e se si vede. */
data class GroupMember(
  val id: MediaId,
  val sortTimeMillis: Long,
  /** In almeno un posto raggiungibile e non nascosto. */
  val visible: Boolean,
)

/**
 * I campi **derivati** di un media per l'indice della timeline (D2 di `docs/fase3-progetto.md`): giorno
 * locale, proporzione, flag della cella, copertina dei gruppi. Stanno in `media` cosi' l'indice di 100
 * mila elementi si legge da **una** tabella; il catalogo li riscrive nella stessa transazione che cambia
 * cio' da cui dipendono, come `sort_time`, `presence` e `safety`.
 *
 * Pure come le altre regole: si provano sulla JVM con dati scritti a mano.
 */
object TimelineRules {

  /** Proporzione di chi non ha ancora le dimensioni (prima di META): un quadrato. */
  const val DEFAULT_ASPECT_MILLI: Int = 1000

  /** Limiti della proporzione salvata (1:1000 e 100:1): solo contro valori assurdi, la griglia ha i suoi. */
  const val MIN_ASPECT_MILLI: Int = 1
  const val MAX_ASPECT_MILLI: Int = 100_000

  private val FIRST_DAY: LocalDate = LocalDate.of(1, 1, 1)
  private val LAST_DAY: LocalDate = LocalDate.of(9999, 12, 31)

  /**
   * Il giorno dello scatto **nell'ora del posto** (E-P5, E-M14) come `aaaammgg`: una foto delle 23:30 a
   * New York appartiene a quel giorno anche se a Roma era gia' il successivo, e cambiare il fuso del
   * telefono dopo non la sposta.
   */
  fun localDay(utcMillis: Long, offsetMinutes: Int): Int {
    val shifted = utcMillis + offsetMinutes * 60_000L
    return yyyymmdd(Math.floorDiv(shifted, MILLIS_PER_DAY))
  }

  fun localDay(capture: CaptureTime): Int = localDay(capture.utcMillis, capture.offsetMinutes)

  /**
   * Il giorno provvisorio di un media appena visto, prima che META legga la data vera: nel fuso del
   * telefono **al momento della lettura** ([zone]). Il catalogo lo salva una volta e non lo ricalcola.
   */
  fun localDay(utcMillis: Long, zone: ZoneId): Int {
    val offsetSeconds = zone.rules.getOffset(Instant.ofEpochMilli(utcMillis.coerceIn(MIN_INSTANT, MAX_INSTANT))).totalSeconds
    return localDay(utcMillis, offsetSeconds / 60)
  }

  /**
   * La proporzione **mostrata** (larghezza/altezza x 1000, gia' orientata): con 90 e 270 gradi, e con gli
   * orientamenti EXIF specchiati 5 e 7, i lati si scambiano (E-V3, E-M15). Senza dimensioni, un quadrato.
   */
  fun aspectMilli(metadata: MediaMetadata?): Int {
    val width = metadata?.width ?: return DEFAULT_ASPECT_MILLI
    val height = metadata.height ?: return DEFAULT_ASPECT_MILLI
    val swap = metadata.orientation.swapsSides
    val shownWidth = (if (swap) height else width).toLong()
    val shownHeight = (if (swap) width else height).toLong()
    val milli = (shownWidth * 1000 + shownHeight / 2) / shownHeight
    return milli.coerceIn(MIN_ASPECT_MILLI.toLong(), MAX_ASPECT_MILLI.toLong()).toInt()
  }

  /**
   * I flag della cella ([CellFlags]) tranne quelli dei gruppi, che dipendono dagli altri membri
   * ([withGroupRole]).
   *
   * - `EDITED`: la versione corrente non e' l'originale (modifica o correzione);
   * - `DATE_ESTIMATED`: la data e' una stima;
   * - `UNREADABLE`: META ha rinunciato (E-M10);
   * - `HAS_PLACE`, `PANORAMA`, `ULTRA_HDR`: dai metadati della versione corrente;
   * - `MOTION`: il tipo e' motion photo;
   * - `FAVORITE`: il dato personale.
   */
  fun cellFlags(
    kind: MediaKind,
    currentVersionKind: VersionKind,
    metadata: MediaMetadata?,
    favorite: Boolean,
    unreadable: Boolean,
  ): Int {
    var flags = 0
    if (currentVersionKind != VersionKind.ORIGINAL) flags = flags or CellFlags.EDITED
    if (metadata?.captureTime?.isEstimated == true) flags = flags or CellFlags.DATE_ESTIMATED
    if (unreadable) flags = flags or CellFlags.UNREADABLE
    if (metadata?.place != null) flags = flags or CellFlags.HAS_PLACE
    if (kind == MediaKind.MOTION_PHOTO) flags = flags or CellFlags.MOTION
    val traits = metadata?.traits.orEmpty()
    if (MediaTrait.PANORAMA in traits) flags = flags or CellFlags.PANORAMA
    if (MediaTrait.ULTRA_HDR in traits) flags = flags or CellFlags.ULTRA_HDR
    if (favorite) flags = flags or CellFlags.FAVORITE
    return flags
  }

  /** I bit di presenza della Fase 4 ([CellFlags.ON_PHONE], [CellFlags.ON_NAS], [CellFlags.SAFE], [CellFlags.TO_VERIFY]). */
  const val PRESENCE_MASK: Int = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.TO_VERIFY

  /**
   * I flag di presenza della cella (D18, §1.5 di `docs/fase4-progetto.md`), che diventano il badge
   * ([dev.pampa.mycloudgallery.core.model.PresenceBadge]):
   * - `ON_PHONE`, `ON_NAS`: almeno una posizione **presente** sul telefono, sul NAS (come [SafetyRules.presence]: un file
   *   assente o in un contenitore non raggiungibile non si puo' aprire);
   * - `SAFE`: lo stato "al sicuro" e' [SafetyStatus.SAFE] (copia privata riletta qui, D8: mai da dati remoti);
   * - `TO_VERIFY`: il media e' in un gruppo `PROBABLE_DUPLICATE` (D21, E-L9) in attesa dell'hash completo.
   */
  fun presenceFlags(media: Media, safety: SafetyStatus, probableDuplicate: Boolean): Int {
    val present = media.locations.filter { it.isPresent }
    var flags = 0
    if (present.any { it.isPhone }) flags = flags or CellFlags.ON_PHONE
    if (present.any { it.isNas }) flags = flags or CellFlags.ON_NAS
    if (safety == SafetyStatus.SAFE) flags = flags or CellFlags.SAFE
    if (probableDuplicate) flags = flags or CellFlags.TO_VERIFY
    return flags
  }

  /**
   * Gli stessi bit di [presenceFlags] dai campi derivati gia' salvati (`presence`, `safety`): li usa la migrazione 2 -> 3,
   * che non ricostruisce i grafi. Coincide con [presenceFlags] perche' quei campi sono calcolati dalle stesse regole.
   */
  fun presenceFlags(presence: Presence, safety: SafetyStatus, probableDuplicate: Boolean): Int {
    var flags = 0
    if (presence == Presence.PHONE_ONLY || presence == Presence.PHONE_AND_NAS) flags = flags or CellFlags.ON_PHONE
    if (presence == Presence.NAS_ONLY || presence == Presence.PHONE_AND_NAS) flags = flags or CellFlags.ON_NAS
    if (safety == SafetyStatus.SAFE) flags = flags or CellFlags.SAFE
    if (probableDuplicate) flags = flags or CellFlags.TO_VERIFY
    return flags
  }

  /**
   * Chi rappresenta un gruppo nell'indice (D20): la copertina scelta, se si vede; altrimenti il membro
   * visibile piu' recente (cosi' una coppia RAW+JPEG con il JPEG cancellato dal telefono resta nella
   * Libreria con il DNG). `null` se nessun membro si vede.
   */
  fun groupCover(members: List<GroupMember>, designatedCover: MediaId?): MediaId? {
    val visible = members.filter { it.visible }
    if (visible.isEmpty()) return null
    designatedCover?.let { cover -> visible.firstOrNull { it.id == cover }?.let { return it.id } }
    return visible.maxWith(compareBy<GroupMember> { it.sortTimeMillis }.thenBy { it.id }).id
  }

  /** I flag di [flags] con il ruolo nel gruppo: nessun gruppo, copertina o membro nascosto dietro la copertina. */
  fun withGroupRole(flags: Int, inGroup: Boolean, isCover: Boolean): Int {
    val base = flags and (CellFlags.GROUP_MEMBER or CellFlags.GROUP_COVER).inv()
    return when {
      !inGroup -> base
      isCover -> base or CellFlags.GROUP_COVER
      else -> base or CellFlags.GROUP_MEMBER
    }
  }

  // --- Fase 5A (P1): backup e copie simili (docs/fase5-progetto.md D15, D36; docs/modello-dati.md §12) -------------------

  /**
   * Il media e' un **candidato del backup** (`media.backup_todo`, §2.1): ha una copia presente sul telefono ed e' un media
   * **proprio** non ancora al sicuro ([SafetyStatus.NO_NAS_COPY] o [SafetyStatus.PRIVATE_COPY_UNVERIFIED]: quest'ultimo si
   * rilegge e si collega, D8) e nessun problema che chiede l'utente
   * ([markedProblem]: un media con la verifica fallita tre volte non si ricarica all'infinito, E-B18).
   *
   * **Mai [SafetyStatus.ONLY_COMMON_COPY]** (§2.11 punto 3, risposta Q4 (b)): un media la cui sola copia sul NAS e' nell'area comune
   * non si ricarica dal telefono; la copia privata la crea il guardiano (D9) quando serve ([SafetyStatus.NOT_MINE] e
   * [SafetyStatus.LOST] mai). Funzione dei soli derivati gia' salvati (`presence`, `safety`) e del problema: per questo la
   * migrazione 3 -> 4 la calcola in SQL senza conoscere l'account del dispositivo, e senza una seconda copia della regola.
   */
  fun backupTodo(presence: Presence, safety: SafetyStatus, markedProblem: Boolean): Boolean =
    !markedProblem &&
      (presence == Presence.PHONE_ONLY || presence == Presence.PHONE_AND_NAS) &&
      (safety == SafetyStatus.NO_NAS_COPY || safety == SafetyStatus.PRIVATE_COPY_UNVERIFIED)

  /**
   * [CellFlags.BACKUP_PROBLEM] (D15): un problema che chiede l'utente ([markedProblem]) vale finche' il media non e' di nuovo
   * al sicuro; un media al sicuro non porta mai il segno (non si nasconde "al sicuro" dietro un errore vecchio).
   */
  fun backupProblemFlags(markedProblem: Boolean, safety: SafetyStatus): Int =
    if (markedProblem && safety != SafetyStatus.SAFE) CellFlags.BACKUP_PROBLEM else 0

  /**
   * Chi rappresenta un gruppo `SIMILAR_COPIES` nell'indice (D36, E-H1). Come [groupCover], ma il **nascosto e' contagioso**: se
   * anche un solo membro e' nascosto ([anyHidden]) nessuno rappresenta il gruppo, mai "un'altra copia al suo posto": la stessa
   * foto non deve ricomparire dalla porta della sua copia. (Il catalogo nasconde tutte le copie insieme; questa e' la difesa
   * se un dato arriva comunque a meta'.)
   */
  fun similarCopiesCover(members: List<GroupMember>, designatedCover: MediaId?, anyHidden: Boolean): MediaId? =
    if (anyHidden) null else groupCover(members, designatedCover)

  /**
   * I flag della cella che rappresenta un gruppo di copie simili (§2.12 "Segni della cella"): [CellFlags.SIMILAR_COPIES], piu' i
   * segni di presenza **del gruppo**, non solo della copertina: sul telefono se almeno una copia lo e', sul NAS se almeno una
   * copia lo e'; **al sicuro solo se ogni copia che sta sul telefono e' al sicuro** (la copertina puo' essere la copia
   * convertita sul NAS mentre l'originale del telefono non e' ancora protetto); [CellFlags.BACKUP_PROBLEM] se una copia lo ha.
   * Senza copie sul telefono "al sicuro" e' quello della copertina. Gli altri bit restano quelli della copertina.
   *
   * @param coverFlags i flag della copertina; @param memberFlags quelli di tutti i membri (copertina compresa).
   */
  fun similarCopiesFlags(coverFlags: Int, memberFlags: List<Int>): Int {
    val onPhone = memberFlags.filter { CellFlags.has(it, CellFlags.ON_PHONE) }
    var flags = coverFlags and (CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.BACKUP_PROBLEM).inv()
    if (onPhone.isNotEmpty()) flags = flags or CellFlags.ON_PHONE
    if (memberFlags.any { CellFlags.has(it, CellFlags.ON_NAS) }) flags = flags or CellFlags.ON_NAS
    val safe = if (onPhone.isNotEmpty()) onPhone.all { CellFlags.has(it, CellFlags.SAFE) } else CellFlags.has(coverFlags, CellFlags.SAFE)
    if (safe) flags = flags or CellFlags.SAFE
    if (memberFlags.any { CellFlags.has(it, CellFlags.BACKUP_PROBLEM) }) flags = flags or CellFlags.BACKUP_PROBLEM
    return flags or CellFlags.SIMILAR_COPIES
  }

  private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

  private val MIN_INSTANT: Long = FIRST_DAY.toEpochDay() * MILLIS_PER_DAY
  private val MAX_INSTANT: Long = LAST_DAY.toEpochDay() * MILLIS_PER_DAY

  private fun yyyymmdd(epochDay: Long): Int {
    val day = LocalDate.ofEpochDay(epochDay.coerceIn(FIRST_DAY.toEpochDay(), LAST_DAY.toEpochDay()))
    return day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth
  }
}
