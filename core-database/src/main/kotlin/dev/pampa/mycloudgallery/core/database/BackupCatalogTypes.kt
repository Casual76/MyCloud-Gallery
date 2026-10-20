package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind

// Fase 5A, P1 (docs/fase5-progetto.md §1.2, §1.3, §2.12; docs/modello-dati.md §12): i tipi delle operazioni del catalogo per il
// backup e per le copie simili. **core-database non dipende da core-backup**: questi tipi rispecchiano quelli della porta
// `BackupCatalog` (core-backup/.../contract/BackupPorts.kt, BackupTypes.kt) e `:app` (P7) le lega 1:1 con una traduzione campo
// per campo, senza decisioni. Percorsi, nomi, share e id sono dati dell'utente: i `toString` sono muti (mai nei log, §4.3).

// --- Sorgenti, candidati -----------------------------------------------------------------------------------------------------

/**
 * Una sorgente del backup (`BackupSource` di core-backup): la fotocamera e gli screenshot sono i media con quella `MediaSource`
 * (`CAMERA`, `SCREENSHOT`); una cartella aggiuntiva e' una cartella del telefono, per volume e percorso relativo **come il
 * MediaStore lo scrive** (`Pictures/Telegram/`, confronto esatto).
 */
sealed interface CatalogSource {
  data object Camera : CatalogSource

  data object Screenshots : CatalogSource

  class Folder(val volume: String, val relativePath: String) : CatalogSource {
    init {
      require(volume.isNotBlank() && relativePath.isNotBlank()) { "Cartella del telefono vuota." }
    }

    override fun equals(other: Any?): Boolean = other is Folder && other.volume == volume && other.relativePath == relativePath

    override fun hashCode(): Int = volume.hashCode() * 31 + relativePath.hashCode()

    override fun toString(): String = "CatalogSource.Folder"
  }
}

/** Che cosa salva un giro (`BackupKind`): i video sono solo [MediaKind.VIDEO]; motion photo, RAW e animate vanno con le foto. */
enum class CatalogBackupKind(override val code: String) : StableCode {
  PHOTOS("photos"),
  VIDEOS("videos"),
}

/** Il cursore delle pagine dei candidati (`CandidateCursor`): `sort_time` decrescente, poi identita' decrescente. */
data class CatalogCandidateCursor(val sortTime: Long, val mediaId: MediaId) {
  override fun toString(): String = "CatalogCandidateCursor"
}

/** La riga del MediaStore di una versione, com'e' in cache nel catalogo (`PhoneRef`). L'`_ID` non e' un'identita'. */
data class CatalogPhonePlace(val volume: String, val mediaStoreId: Long, val mediaStoreVersion: String) {
  init {
    require(mediaStoreId > 0) { "Un _ID MediaStore e' positivo." }
  }

  override fun toString(): String = "CatalogPhonePlace"
}

/**
 * Un candidato del backup (`BackupCandidate`): un media **proprio**, con una copia presente sul telefono in una sorgente scelta
 * (con un `_ID` valido per la versione attuale del MediaStore), per una versione da proteggere senza copia privata verificata.
 * Uno per media.
 *
 * @property localDay il giorno dello scatto nell'ora del luogo (`aaaammgg`), null = senza data (E-B17).
 * @property dateEstimated la data non e' una lettura dei metadati (nome o data del file, o META non ancora passata).
 * @property original la versione da proteggere e' l'originale del media.
 */
class CatalogBackupCandidate(
  val mediaId: MediaId,
  val versionId: VersionId,
  val phone: CatalogPhonePlace,
  val stamp: FileStamp,
  val size: Long,
  val kind: MediaKind,
  val source: CatalogSource,
  val localDay: Int?,
  val dateEstimated: Boolean,
  val fileName: String,
  val sortTime: Long,
  val quickHash: QuickHash,
  val original: Boolean,
) {
  override fun toString(): String = "CatalogBackupCandidate(${kind.code}, $size B)"
}

/** I conteggi della sicurezza dei media propri delle sorgenti (`SafetyCounts`); [problems] per codice di `BackupProblemCode`. */
data class CatalogSafetyCounts(
  val safe: Int,
  val unverified: Int,
  val onlyCommonCopy: Int,
  val noNasCopy: Int,
  val lost: Int,
  val problems: Map<String, Int>,
) {
  companion object {
    val ZERO: CatalogSafetyCounts = CatalogSafetyCounts(0, 0, 0, 0, 0, emptyMap())
  }
}

// --- Copie sul NAS, verifiche, guardiano -------------------------------------------------------------------------------------

/** Una copia sul NAS che il catalogo conosce (`NasCopyRef`). [share] e [path] sono come li scrive il NAS: mai nei log. */
class CatalogNasCopy(
  val locationId: LocationId,
  val mediaId: MediaId,
  val versionId: VersionId,
  val share: String,
  val path: NasPath,
  val stamp: NasStamp,
) {
  override fun toString(): String = "CatalogNasCopy(${stamp.size} B)"
}

/** Cosa si rilegge (`VerifyScope`). */
sealed interface CatalogVerifyScope {
  /** Le copie private presenti mai riletta, con l'hash della versione noto (`PRIVATE_COPY_UNVERIFIED`). */
  data object Unverified : CatalogVerifyScope

  /** La fase `FULL_HASH` in coda (F4-D21): copie del NAS di versioni senza hash completo; [CatalogCopyToVerify.expected] e' null. */
  data object FullHash : CatalogVerifyScope

  /** La riverifica a rotazione (Q10): le copie private gia' verificate, dalla verifica piu' vecchia. */
  data object Rotation : CatalogVerifyScope

  /** 5B (D21): la verifica sul momento delle copie private dei media di "Libera spazio". */
  class OnTheSpot(val media: Set<MediaId>) : CatalogVerifyScope {
    override fun toString(): String = "OnTheSpot(${media.size})"
  }
}

/** Una copia privata da rileggere (`CopyToVerify`): [expected] e' lo SHA-256 della versione (null = la lettura lo calcola). */
class CatalogCopyToVerify(
  val copy: CatalogNasCopy,
  val size: Long,
  val expected: Sha256?,
  val verifiedAtMillis: Long?,
) {
  override fun toString(): String = "CatalogCopyToVerify($size B)"
}

/** Un media proprio con la sola copia nell'area comune (`GuardCandidate`, D9). [fileName] e' il nome della copia comune. */
class CatalogGuardCandidate(
  val common: CatalogNasCopy,
  val size: Long,
  val fullHash: Sha256?,
  val quickHash: QuickHash?,
  val localDay: Int?,
  val fileName: String,
) {
  override fun toString(): String = "CatalogGuardCandidate($size B)"
}

/** Lo stato di sicurezza di un media (`MediaSafety`); [problem] e' un codice di `BackupProblemCode`. */
data class CatalogMediaSafety(val status: SafetyStatus, val verifiedAtMillis: Long?, val problem: String?)

/** Cosa ha fatto il catalogo con un hash completo calcolato qui (`HashRecord`). */
enum class FullHashRecord(override val code: String) : StableCode {
  /** La versione ora ha l'hash completo. */
  RECORDED("recorded"),

  /** Il catalogo aveva gia' lo stesso hash. */
  SAME("same"),

  /** Il catalogo ha un **altro** hash per la versione: i byte letti non sono quelli della versione (E-B15). Niente cambia. */
  CONFLICT("conflict"),

  /** La versione non esiste (piu'). */
  UNKNOWN_VERSION("unknown_version"),
}

/**
 * Una copia scritta dall'app da attaccare al catalogo (`VerifiedCopy`, [MediaCatalog.attachCopies]): le stesse regole di
 * [MediaCatalog.attachCopy]. [verifiedAtMillis] e' l'ora della rilettura con hash uguale (ora del NAS), null = non verificata.
 */
class AttachedCopy(
  val mediaId: MediaId,
  val versionId: VersionId,
  val location: Location,
  val stamp: FileStamp,
  val verifiedAtMillis: Long?,
) {
  override fun toString(): String = "AttachedCopy(${stamp.size} B, verified=${verifiedAtMillis != null})"
}

/** Le posizioni di una versione (`versionLocations`): dove sta ognuna, con l'area se e' sul NAS. */
class VersionLocations(
  val versionId: VersionId,
  val kind: VersionKind,
  val isCurrent: Boolean,
  val size: Long,
  val fullHash: Sha256?,
  val locations: List<VersionLocation>,
) {
  override fun toString(): String = "VersionLocations(${kind.code}, ${locations.size})"
}

/** Una posizione di una versione e l'area della share se e' sul NAS (null = telefono o share di area sconosciuta). */
class VersionLocation(val location: MediaLocation, val area: NasArea?) {
  override fun toString(): String = "VersionLocation(${location.state.code})"
}

// --- Copie simili (D36, §2.12) -----------------------------------------------------------------------------------------------

/**
 * Un media che puo' essere una copia simile di un altro (§2.12 condizioni 1, 2 e 5): stesso tipo (foto con foto, video con video),
 * stessa ora di scatto **letta dai metadati** al secondo (EXIF o contenitore: mai il nome o la data del file, mai una stima),
 * visibile da qualche parte e non in una raffica, in una coppia RAW+JPEG o in un `PROBABLE_DUPLICATE`. Le condizioni 3 (proporzione,
 * durata) e 4 (firma) sono del rilevatore: qui ci sono i dati, [aspectMilli] e [durationMs].
 *
 * @property takenAtUtcMillis l'istante di scatto (`sort_time` della versione corrente).
 * @property group il gruppo di copie simili in cui e' gia', se c'e'.
 * @property signatureAlgorithm l'algoritmo della firma visiva salvata per la versione corrente, null = nessuna firma.
 */
class SimilarCandidate(
  val mediaId: MediaId,
  val versionId: VersionId,
  val kind: MediaKind,
  val takenAtUtcMillis: Long,
  val aspectMilli: Int,
  val durationMs: Long?,
  val group: MediaGroupId?,
  val signatureAlgorithm: Int?,
) {
  /** L'istante di scatto al secondo (epoca, secondi). */
  val second: Long get() = Math.floorDiv(takenAtUtcMillis, 1000L)

  val isVideo: Boolean get() = kind.isVideo

  override fun toString(): String = "SimilarCandidate(${kind.code})"
}

/** Almeno due candidati dello stesso tipo nello stesso secondo di scatto. */
class SimilarRun(val second: Long, val candidates: List<SimilarCandidate>) {
  init {
    require(candidates.size >= 2) { "Un gruppo di candidati ha almeno due media." }
  }

  override fun toString(): String = "SimilarRun(${candidates.size})"
}

/** Una pagina di [SimilarRun]: [nextSecond] e' da dove riprendere (null = finite). Le pagine non spezzano mai un secondo. */
class SimilarRunsPage(val runs: List<SimilarRun>, val nextSecond: Long?)

/** Perche' [MediaCatalog.groupSimilar] rifiuta. */
enum class SimilarRefusal(override val code: String) : StableCode {
  /** Il membro e' in una raffica (E-L12): non si sposta. */
  IN_BURST("in_burst"),

  /** Il membro e' in una coppia RAW+JPEG: non si sposta. */
  IN_RAW_JPEG_PAIR("in_raw_jpeg_pair"),

  /** Il membro e' in un `PROBABLE_DUPLICATE` (stesso video su telefono e NAS in attesa dell'hash completo): non si sposta. */
  IN_PROBABLE_DUPLICATE("in_probable_duplicate"),

  /** Un'identita' sconosciuta (e non un alias di un'unione). */
  UNKNOWN_MEDIA("unknown_media"),

  /** Meno di due media diversi (dopo gli alias). */
  TOO_FEW_MEMBERS("too_few_members"),

  /** La copertina non e' uno dei membri. */
  COVER_NOT_A_MEMBER("cover_not_a_member"),
}

/** L'esito di [MediaCatalog.groupSimilar]. */
sealed interface SimilarGroupOutcome {
  /**
   * Il gruppo e' nato con [members] (alias risolti). [hidden]: almeno un membro era nascosto e ora **lo sono tutti** (E-H1).
   * [dissolved]: i gruppi di copie simili che i membri hanno lasciato e che sono rimasti con meno di due media.
   */
  class Grouped(val group: MediaGroupId, val members: List<MediaId>, val hidden: Boolean, val dissolved: Int) : SimilarGroupOutcome {
    override fun toString(): String = "Grouped(${members.size}, hidden=$hidden)"
  }

  /** Rifiutato **senza scrivere nulla**: [offenders] sono i membri che non si possono unire, ognuno con il suo motivo (vuoto per i motivi generali). */
  class Refused(val reason: SimilarRefusal, val offenders: Map<MediaId, SimilarRefusal>) : SimilarGroupOutcome {
    override fun toString(): String = "Refused(${reason.code}, ${offenders.size})"
  }
}

/** L'esito di [MediaCatalog.separate]. */
sealed interface SeparateOutcome {
  /** Il media non e' in un gruppo di copie simili. */
  data object NotInGroup : SeparateOutcome

  /**
   * Il media e' uscito dal gruppo [group]. Se il gruppo e' rimasto con meno di due media si e' sciolto ([dissolved], [group] null)
   * e [remaining] sono tornati celle a se'. [coverChanged]: la copertina era il media separato e il catalogo ne ha messa una
   * provvisoria (la piu' recente): il rilevatore rivaluta la sua regola. **Il nascosto non cambia mai.**
   */
  class Separated(val group: MediaGroupId?, val remaining: List<MediaId>, val dissolved: Boolean, val coverChanged: Boolean) : SeparateOutcome {
    override fun toString(): String = "Separated(${remaining.size}, dissolved=$dissolved)"
  }
}

/** Una copia di un gruppo di copie simili con cio' che serve alla copertina e alla striscia del viewer (`SimilarCopy`). */
class SimilarMember(
  val mediaId: MediaId,
  val versionId: VersionId,
  val kind: MediaKind,
  val bytes: Long,
  val width: Int?,
  val height: Int?,
  val orientationDegrees: Int,
  val durationMs: Long?,
  val mime: String?,
  val sortTime: Long,
  val onPhone: Boolean,
  val onNasPrivate: Boolean,
  val onNasCommon: Boolean,
  val hidden: Boolean,
  val safety: SafetyStatus,
  val backupProblem: Boolean,
) {
  override fun toString(): String = "SimilarMember(${kind.code}, $bytes B)"
}

/** Un gruppo di copie simili: [cover] e' la copertina salvata (puo' essere null se nessuna lo e'), [hidden] vale per tutto il gruppo. */
class SimilarGroupInfo(val group: MediaGroupId, val cover: MediaId?, val members: List<SimilarMember>, val hidden: Boolean) {
  override fun toString(): String = "SimilarGroupInfo(${members.size})"
}

// --- Fase 5B (P14): cio' che "Libera spazio", "Modifica con…", le correzioni e i file in arrivo chiedono al catalogo -------------------

/** Un media con una copia sul telefono, nell'ordine della Libreria ([MediaCatalog.phoneMedia]). [localDay] null = senza data. */
data class CatalogPhoneMedia(val mediaId: MediaId, val sortTime: Long, val localDay: Int?) {
  override fun toString(): String = "CatalogPhoneMedia"
}

/** Il cursore delle pagine di [MediaCatalog.phoneMedia]: `sort_time` decrescente, poi id decrescente. */
data class CatalogPhoneCursor(val sortTime: Long, val mediaId: MediaId) {
  override fun toString(): String = "CatalogPhoneCursor"
}

/** Il luogo di una correzione solo nell'app (Q6 (a)). */
sealed interface InAppGeo {
  /** Il luogo resta com'e'. */
  data object Keep : InAppGeo

  /** Il luogo corretto. */
  class Set(val latitude: Double, val longitude: Double) : InAppGeo {
    init {
      require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Coordinate fuori intervallo." }
    }

    override fun toString(): String = "InAppGeo.Set"
  }

  /** Il luogo tolto. */
  data object Clear : InAppGeo
}

/**
 * Una correzione **solo nell'app** (Q6 (a), L40; [MediaCatalog.correctInApp]): la data (istante e fuso), il luogo e la rotazione (gradi e
 * specchio) che valgono per la versione al posto di quelli letti dal file. I campi null non correggono niente.
 */
data class InAppCorrection(
  val takenAtUtcMillis: Long? = null,
  val offsetMinutes: Int? = null,
  val geo: InAppGeo = InAppGeo.Keep,
  val orientationDegrees: Int? = null,
  val mirrored: Boolean? = null,
) {
  init {
    require(offsetMinutes == null || (takenAtUtcMillis != null && offsetMinutes in -18 * 60..18 * 60)) { "Fuso non valido." }
    require(orientationDegrees == null || orientationDegrees in setOf(0, 90, 180, 270)) { "Rotazione non valida." }
    require((orientationDegrees == null) == (mirrored == null)) { "Rotazione e specchio vanno insieme." }
  }

  val isEmpty: Boolean get() = takenAtUtcMillis == null && geo == InAppGeo.Keep && orientationDegrees == null

  override fun toString(): String = "InAppCorrection"
}

/** Esito di [MediaCatalog.incomingMedia]: il media e la versione che un file in arrivo diventera' (nuovo, o gia' noto per gli stessi byte). */
data class CatalogIncomingMedia(val mediaId: MediaId, val versionId: VersionId, val created: Boolean) {
  override fun toString(): String = "CatalogIncomingMedia(nuovo=$created)"
}
