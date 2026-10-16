package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 5A (docs/fase5-progetto.md §1.2 "Porte", §1.4): le PORTE del backup verso il resto dell'app. Le lega `:app` (P7): il telefono
// a `MediaStoreSource` (core-backup non vede core-mediastore), il catalogo a `MediaCatalog` (P1), il cancello a D12 + congelamento +
// emergenza + area in sola lettura, l'orologio al monotono e all'ora del NAS. Fake in `testing`, null-object in `noop`.

// --- Il telefono ------------------------------------------------------------------------------------------------------------

/** Perche' il telefono nega i byte (il giro si ferma con il codice corrispondente). */
enum class PhoneDenied(override val code: String, val problem: BackupProblemCode) : StableCode {
  /** Permessi dei media ritirati (E-P15, E-O4). */
  MEDIA_PERMISSION("media_permission", BackupProblemCode.MEDIA_PERMISSION),

  /** Senza `ACCESS_MEDIA_LOCATION` le foto restano in attesa: mai un'impronta di byte oscurati (E-P21). */
  MEDIA_LOCATION("media_location", BackupProblemCode.MEDIA_LOCATION),

  /** Accesso solo alle foto selezionate e questa non lo e' (E-P1). */
  NOT_SELECTED("not_selected", BackupProblemCode.PARTIAL_ACCESS),
}

/** Com'e' adesso una riga del telefono. */
sealed interface PhoneLookup {
  /** La riga c'e' con lo stesso `_ID` e la stessa versione del MediaStore; [stamp] e' il suo timbro adesso. */
  data class Present(val stamp: FileStamp) : PhoneLookup

  /** La riga non c'e' piu' (cancellata, nel cestino, E-B4). */
  data object Gone : PhoneLookup

  /**
   * La versione del MediaStore e' cambiata (E-P12, E-P22): l'`_ID` non vale piu'. Il motore chiede la riga nuova al catalogo
   * (`BackupCatalog.phoneRowOf`), che la ritrova per impronta dopo la lettura del telefono; finche' la lettura non ha finito, l'elemento
   * aspetta; se ha finito e la riga non c'e', l'elemento e' `GONE`.
   */
  data object Stale : PhoneLookup

  data class Denied(val reason: PhoneDenied) : PhoneLookup
}

/**
 * La riga del telefono di una versione secondo il catalogo, per un elemento che ha visto la sua riga diventare [PhoneLookup.Stale]
 * ([BackupCatalog.phoneRowOf], E-P12, F1 di docs/prova-500.md §B6).
 */
sealed interface PhoneRowNow {
  /** La riga attuale. Se e' quella che l'elemento ha gia', il catalogo non ha ancora visto il cambio di versione (in ritardo): si aspetta. */
  data class At(val ref: PhoneRef) : PhoneRowNow

  /** La lettura del telefono non ha ancora riletto tutto il volume dopo il cambio di versione (o non puo', accesso parziale): si aspetta. */
  data object Rereading : PhoneRowNow

  /** Il catalogo ha riletto tutto il volume dopo il cambio di versione e la versione non ha una riga presente: l'elemento e' `GONE`. */
  data object Absent : PhoneRowNow
}

/**
 * I byte di una riga aperta: [bytes] (per le foto letti con `setRequireOriginal`, D9 della Fase 3) e il timbro letto all'apertura.
 * Si chiude dopo l'uso. Una lettura che fallisce a meta' (riga sparita, file riscritto) lancia l'eccezione di I/O di [MediaBytes]: il
 * motore rilegge il timbro e decide (E-B4, E-B15).
 */
class PhoneFile(val bytes: MediaBytes, val stamp: FileStamp, private val onClose: () -> Unit = {}) : AutoCloseable {
  override fun close() = onClose()

  override fun toString(): String = "PhoneFile(${stamp.size} B)"
}

/** L'esito dell'apertura di una riga. */
sealed interface PhoneOpen {
  class Opened(val file: PhoneFile) : PhoneOpen {
    override fun toString(): String = "PhoneOpen.Opened"
  }

  data object Gone : PhoneOpen

  data object Stale : PhoneOpen

  data class Denied(val reason: PhoneDenied) : PhoneOpen
}

/**
 * I byte originali del telefono (§1.2 "Porte", D10): `:app` la lega a `MediaStoreSource.open` (foto con `setRequireOriginal` e
 * `ACCESS_MEDIA_LOCATION`, video in streaming, mai interi in memoria) e al controllo della riga. Con D13 (debug con gli account di
 * prova) `:app` la recinta: solo le righe di `DCIM/mcg-prova/` e `Pictures/mcg-prova/` ([BackupFence]). Nella 5A **sola lettura**:
 * nessuna scrittura sul telefono.
 */
interface PhoneBytes {
  suspend fun lookup(ref: PhoneRef): PhoneLookup

  suspend fun open(ref: PhoneRef): PhoneOpen
}

// --- Il catalogo ------------------------------------------------------------------------------------------------------------

/** Cosa ha fatto il catalogo con un hash completo calcolato qui dai byte del telefono. */
enum class HashRecord(override val code: String) : StableCode {
  /** Registrato: la versione ora ha l'hash completo (serve a `attachCopy` verificata). */
  RECORDED("recorded"),

  /** Il catalogo aveva gia' lo stesso hash. */
  SAME("same"),

  /** Il catalogo ha un **altro** hash per la versione: i byte letti non sono quelli della versione (E-B15). Niente cambia. */
  CONFLICT("conflict"),

  /** La versione non esiste piu' (unione d'identita', versione sostituita). */
  UNKNOWN_VERSION("unknown_version"),
}

/**
 * Una copia privata scritta (o trovata) e **riletta qui** con lo SHA-256 della versione: `attachCopy(verifiedAt)` del catalogo (D4, D8,
 * L28). [verifiedAtMillis] e' l'ora del NAS della rilettura.
 */
class VerifiedCopy(
  val mediaId: MediaId,
  val versionId: VersionId,
  val share: String,
  val path: SharePath,
  val stamp: NasStamp,
  val verifiedAtMillis: Long,
) {
  override fun toString(): String = "VerifiedCopy(${stamp.size} B)"
}

/** Una copia sul NAS che il catalogo conosce (deduplica, verifiche, guardiano). Percorso e share: mai nei log. */
class NasCopyRef(
  val locationId: LocationId?,
  val mediaId: MediaId,
  val versionId: VersionId?,
  val share: String,
  val path: SharePath,
  val stamp: NasStamp?,
) {
  override fun toString(): String = "NasCopyRef"
}

/** Una copia privata da rileggere (D14): [expected] e' lo SHA-256 della versione (null = la verifica lo calcola, `FULL_HASH`). */
class CopyToVerify(
  val copy: NasCopyRef,
  val size: Long,
  val expected: Sha256?,
  val verifiedAtMillis: Long?,
) {
  override fun toString(): String = "CopyToVerify($size B)"
}

/**
 * Un media **proprio** con la sola copia nell'area comune (D9, `ONLY_COMMON_COPY`): il guardiano ne crea la copia privata. [fullHash]
 * e' quello della versione (se manca, la verifica usa quello letto qui dalla sorgente, solo se timbro e `qh1` tornano, §2.3).
 */
class GuardCandidate(
  val common: NasCopyRef,
  val size: Long,
  val fullHash: Sha256?,
  val quickHash: QuickHash?,
  val localDay: Int?,
  val fileName: String,
) {
  override fun toString(): String = "GuardCandidate($size B)"
}

/** I conteggi della sicurezza per la pagina del backup e il riepilogo (P1: `MediaCatalog.safetyCounts()`). */
data class SafetyCounts(
  val safe: Int,
  val unverified: Int,
  val onlyCommonCopy: Int,
  val noNasCopy: Int,
  val lost: Int,
  val problems: Map<BackupProblemCode, Int>,
) {
  companion object {
    val ZERO: SafetyCounts = SafetyCounts(0, 0, 0, 0, 0, emptyMap())
  }
}

/** Lo stato di sicurezza di un media (riga del viewer, insieme al diario). */
data class MediaSafety(val status: SafetyStatus, val verifiedAtMillis: Long?, val problem: BackupProblemCode?)

/**
 * Il catalogo locale visto dal backup (P1 aggiunge a `MediaCatalog` le query e le scritture; `:app` lega questa porta). **Ogni
 * decisione parte da qui**, mai da dati letti dal NAS (E-S11); "al sicuro" nasce solo da [attachVerifiedCopies] e [markVerified], cioe'
 * da una rilettura fatta qui (F4-D8, L28).
 */
interface BackupCatalog {

  // --- Piano (P4) -------------------------------------------------------------------------------------------------------------

  /** I candidati (§2.1) di una pagina: media propri, nelle sorgenti, non al sicuro, senza `IS_PENDING`, per `sort_time` decrescente. */
  suspend fun candidates(query: CandidateQuery): List<BackupCandidate>

  suspend fun safetyCounts(sources: BackupSources): SafetyCounts

  // --- Caricamento (P2) -------------------------------------------------------------------------------------------------------

  /** La riga del telefono attuale della versione (dopo una rinumerazione degli `_ID`, E-P12), o null se il catalogo non la sa. */
  suspend fun phoneRefOf(mediaId: MediaId, versionId: VersionId): PhoneRef?

  /**
   * Dove sta adesso la riga di una versione il cui `_ID` [stale] non vale piu' (E-P12; F1 di docs/prova-500.md §B6). [PhoneRowNow.Absent]
   * **solo se il catalogo dimostra di aver riletto il telefono dopo il cambio di versione**: la lettura completa del volume di [stale] e'
   * finita (nessuna scansione completa in corso ne' da fare, come dopo un accesso parziale) a una versione del MediaStore diversa da quella
   * di [stale], e la versione non ha una riga presente. Mentre la lettura rilegge, [phoneRefOf] risponde null anche per le righe che ci
   * sono ancora (la scansione completa salva subito la versione nuova del cursore e rilegge le righe a lotti, anche in piu' giri): qui e'
   * [PhoneRowNow.Rereading], mai un `GONE` con "persa prima del backup" per una lettura non ancora fatta.
   *
   * Con `MediaCatalog` (`:app`): prima il cursore (`mediaStoreCursor(stale.volume)`: `msVersion` diversa da quella di [stale],
   * `scanEpoch == null`, `generation != null`), poi [phoneRefOf]; o le due letture in una transazione. Il default, per chi lega solo
   * [phoneRefOf], non risponde mai `Absent`: l'elemento aspetta (F1 resta, ma nessuna perdita finta).
   */
  suspend fun phoneRowOf(mediaId: MediaId, versionId: VersionId, stale: PhoneRef): PhoneRowNow =
    phoneRefOf(mediaId, versionId)?.let { PhoneRowNow.At(it) } ?: PhoneRowNow.Rereading

  /** L'hash completo calcolato qui dai byte originali del telefono (D10): serve prima di attaccare una copia verificata. */
  suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord

  /**
   * Le copie verificate, **a lotti in una transazione** (un solo cambiamento dell'indice della timeline per lotto, §5.5): il media
   * diventa "al sicuro". Idempotente sulla stessa posizione. Restituisce quante sono state attaccate.
   */
  suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int

  /** Il problema del media (D15: [BackupProblemCode.marksMedia] accende `BACKUP_PROBLEM`), o null per toglierlo. */
  suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?)

  // --- Deduplica, verifiche, guardiano (P3) -------------------------------------------------------------------------------------

  /**
   * D8: le copie dell'area privata [share] **del proprietario** con la stessa dimensione (e lo stesso `qh1`, se noto) in **qualunque
   * cartella** (anche i vecchi backup fatti con altri strumenti, §2.11 punto 2). Mai un'altra area privata, mai l'area comune. Sono solo
   * candidati: decide la rilettura completa.
   */
  suspend fun privateCopies(share: String, size: Long, quickHash: QuickHash?, limit: Int): List<NasCopyRef>

  /** Le copie private da rileggere per [scope] (non verificate, `FULL_HASH`, rotazione per `verified_at` crescente). */
  suspend fun verifyCandidates(scope: VerifyScope, limit: Int): List<CopyToVerify>

  /** L'esito di una rilettura: vero se torna (la copia e' "verificata" adesso); falso toglie "al sicuro" (E-B8). */
  suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean

  /** I media propri con la sola copia nell'area comune (D9). */
  suspend fun onlyCommonCopies(scope: GuardScope, limit: Int): List<GuardCandidate>

  // --- Stato (P5, P7) ---------------------------------------------------------------------------------------------------------

  suspend fun mediaSafety(mediaId: MediaId): MediaSafety?
}

// --- Cancello, orologio, confronto, recinzione ----------------------------------------------------------------------------------

/** Se il backup puo' scrivere nella share adesso (§1.4 "Cancello"). */
enum class BackupGateState(override val code: String, val problem: BackupProblemCode?) : StableCode {
  OPEN("open", null),

  /** D12: share non di prova finche' l'utente non chiude la 5A (Q3): niente si scrive, lo stato dice "Backup in prova". */
  TEST_ONLY("test_only", BackupProblemCode.TEST_ONLY),

  /** F4-D10, D32: share congelata per cambiamenti di massa (E-B19). */
  FROZEN("frozen", BackupProblemCode.FROZEN),

  /** "Ferma le scritture sul NAS" (emergenza, E-B19). */
  WRITES_STOPPED("writes_stopped", BackupProblemCode.WRITES_STOPPED),

  /** L'area privata e' in sola lettura per l'account (E-N9, E-I19). */
  READ_ONLY_AREA("read_only_area", BackupProblemCode.READ_ONLY_AREA),

  /** Nessuna area privata dichiarata (`LocalDeviceProfile.privateShare`, E-O2). */
  NO_PRIVATE_AREA("no_private_area", BackupProblemCode.NO_PRIVATE_AREA),

  /** La famiglia non e' pronta (onboarding, revoca): niente si scrive. */
  NOT_READY("not_ready", null),
  ;

  val open: Boolean get() = this == OPEN
}

/**
 * Il cancello del backup: lo compone `:app` (P7) da D12 (`FeatureFlags.backupMayWriteTo`), congelamento, emergenza, area in sola
 * lettura e famiglia pronta. Il motore lo controlla prima di ogni elemento (D32: "si ferma al passo successivo").
 */
fun interface BackupGate {
  fun stateOf(share: String): BackupGateState
}

/**
 * Gli orologi del backup (D31, §4.3): il **monotono** per le durate (tappe, budget, attese); l'**ora del NAS** solo per cio' che si
 * scrive (l'istante di una verifica). Mai l'orologio del telefono; un salto dell'ora non ferma il backup.
 */
interface BackupClock {
  fun monotonicMs(): Long

  /** L'ora del NAS adesso, o null se nessuna sessione l'ha ancorata: senza, una copia non si segna "verificata adesso". */
  fun nasNowMs(): Long?
}

/**
 * Cosa c'e' gia' sul NAS prima del primo backup (§2.11): la scansione dell'area privata e' finita? quanti candidati aspettano il
 * confronto delle copie simili (D36)? `:app` la lega alla scansione (core-nasmedia) e a `SimilarCopies.progress` (core-library, P15):
 * core-backup non vede ne' l'uno ne' l'altro.
 */
fun interface ExistingCopiesProgress {
  suspend fun current(): ExistingCopiesState
}

/**
 * D13: la recinzione del telefono nella build **debug** con gli account di prova (come F4-D33). Il backup legge solo le righe delle
 * cartelle ammesse (`DCIM/mcg-prova/`, `Pictures/mcg-prova/`) e scrive sotto [destinationRoot] delle share di prova; nella 5B "Libera
 * spazio", correzioni, "Ripristina originale" e "Modifica con…" toccano solo quei media. La lega `:app` da `DeveloperTools` di debug
 * (P7); **nella release non esiste** (`DeveloperTools` di release risponde null e `tools/check.ps1` la cerca nel dex).
 */
interface BackupFence {
  /** Il backup puo' leggere una riga del telefono nella cartella [relativePath] (come la scrive il MediaStore) con l'account [account]? */
  fun allowsPhoneFolder(account: String, relativePath: String): Boolean

  /** La cartella della share sotto cui il backup scrive con [account] (`mcg-campo`), o null = nessuna recinzione per quell'account. */
  fun destinationRoot(account: String): SharePath?
}
