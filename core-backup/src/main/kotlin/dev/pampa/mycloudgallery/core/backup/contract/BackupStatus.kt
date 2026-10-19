package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.StableCode

// Fase 5A (docs/fase5-progetto.md §1.5, §2.6, §2.11, D15, D16): lo STATO del backup come contratto. **Cosa** dire e **quando**; il
// **come** e' del filone grafico (D30, componenti G2). Solo numeri e codici: nessun nome di file, percorso, share, account o id.

/** Che cosa propone un problema (la colonna "Azione" del §2.6). Le esegue `:app` (navigazione, permessi, Diagnostica). */
enum class BackupAction(override val code: String) : StableCode {
  RETRY("retry"),
  DIAGNOSTICS("diagnostics"),
  NAS_SPACE("nas_space"),

  /** Solo un testo ("Chiedi all'admin del NAS"): nessun tasto. */
  ASK_ADMIN("ask_admin"),
  ENTER_PASSWORD("enter_password"),
  AREAS_SETTINGS("areas_settings"),
  GRANT_MEDIA("grant_media"),
  GRANT_MEDIA_LOCATION("grant_media_location"),
  GRANT_ALL_MEDIA("grant_all_media"),

  /** "Fai ora" con la conferma e la stima (tetto dati raggiunto, E-B10). */
  DO_NOW_CONFIRM("do_now_confirm"),
  OPEN_GALLERY_TRASH("open_gallery_trash"),

  /** "Recuperalo dal cestino del NAS" (30 giorni, Eliminati di recente del NAS della 4B): un media perso dall'area comune (L44). */
  OPEN_NAS_TRASH("open_nas_trash"),
  FREE_SPACE("free_space"),

  /** La guida: batteria "Illimitata", "Mai in sospensione" (E-P15, E-P2). */
  BATTERY_GUIDE("battery_guide"),
}

/**
 * I problemi del backup (§2.6, "Ogni problema ha un messaggio e un'azione"): **codici stabili**, nello stesso ordine della tabella del
 * progetto (`BackupProblemCatalogTest` la confronta con il documento). Il messaggio e' della UI (P5) e della Diagnostica (P7).
 *
 * @property actions le azioni proposte, nell'ordine ("Riprova · Diagnostica").
 * @property edgeCases gli edge case del piano coperti (per il catalogo della Diagnostica).
 * @property marksMedia il problema e' di un media e accende [dev.pampa.mycloudgallery.core.model.CellFlags.BACKUP_PROBLEM] (D15);
 *   gli altri fermano o rallentano il giro intero.
 */
enum class BackupProblemCode(
  override val code: String,
  val actions: List<BackupAction>,
  val edgeCases: Set<String>,
  val marksMedia: Boolean = false,
) : StableCode {
  NAS_UNREACHABLE("nas_unreachable", listOf(BackupAction.RETRY, BackupAction.DIAGNOSTICS), setOf("E-R5")),
  NAS_FULL("nas_full", listOf(BackupAction.NAS_SPACE), setOf("E-R9")),
  QUOTA("quota", listOf(BackupAction.ASK_ADMIN), setOf("E-N13")),
  AUTH("auth", listOf(BackupAction.ENTER_PASSWORD), setOf("E-R8")),
  READ_ONLY_AREA("read_only_area", listOf(BackupAction.ASK_ADMIN), setOf("E-N9", "E-I19")),
  NO_PRIVATE_AREA("no_private_area", listOf(BackupAction.AREAS_SETTINGS), setOf("E-O2")),
  MEDIA_PERMISSION("media_permission", listOf(BackupAction.GRANT_MEDIA), setOf("E-P15", "E-O4")),
  MEDIA_LOCATION("media_location", listOf(BackupAction.GRANT_MEDIA_LOCATION), setOf("E-P21")),
  PARTIAL_ACCESS("partial_access", listOf(BackupAction.GRANT_ALL_MEDIA), setOf("E-P1")),
  CAP_REACHED("cap_reached", listOf(BackupAction.DO_NOW_CONFIRM), setOf("E-B10")),
  FROZEN("frozen", listOf(BackupAction.DIAGNOSTICS), setOf("E-B19", "C11")),
  WRITES_STOPPED("writes_stopped", listOf(BackupAction.DIAGNOSTICS), setOf("E-B19")),
  TEST_ONLY("test_only", emptyList(), setOf("D12")),
  LOST_BEFORE_BACKUP("lost_before_backup", listOf(BackupAction.OPEN_GALLERY_TRASH), setOf("E-B4"), marksMedia = true),
  VERIFY_FAILED("verify_failed", listOf(BackupAction.RETRY, BackupAction.DIAGNOSTICS), setOf("E-B18", "E-B8"), marksMedia = true),

  /**
   * D15 (P2): un file del telefono che non si legge (errori di lettura ripetuti con la riga e il timbro invariati) o che non e' un media
   * salvabile (zero byte, E-M12): mai caricato, mai "al sicuro"; "Riprova" toglie il segno e il backup lo rilegge. Un media **rovinato**
   * ma leggibile invece si salva comunque (E-M10: i byte sono dell'utente, la preparazione mette il segnaposto).
   */
  UNREADABLE("unreadable", listOf(BackupAction.RETRY, BackupAction.DIAGNOSTICS), setOf("D15", "E-M12"), marksMedia = true),

  /**
   * L44 (proposto da P3): un media **proprio** la cui unica copia era nell'area comune, sparita prima che il guardiano ne facesse la copia
   * privata, senza piu' la copia del telefono (E-B13 con Q4 b). Resta solo il cestino del NAS, per 30 giorni.
   */
  COMMON_COPY_LOST("common_copy_lost", listOf(BackupAction.OPEN_NAS_TRASH), setOf("E-B13", "L44"), marksMedia = true),

  /**
   * R1 della revisione 5A (difesa in profondita'): un errore che nessuna regola prevedeva (un nome rifiutato, un diario che dice di no,
   * un'eccezione del NAS non tradotta). L'elemento che lo ha provocato si **parcheggia** (`FAILED`, mai "al sicuro") e il giro continua con
   * gli altri: un solo file non ferma piu' il backup di tutti. Il segno toglie il media dai candidati; "Riprova" lo rimette in gioco. Con il
   * numero 0 e' l'errore di un intero lavoro (`BackupWork`, `BackupJobService`): la pagina non resta "in corso" e non lo ripete all'infinito.
   */
  UNEXPECTED("unexpected", listOf(BackupAction.RETRY, BackupAction.DIAGNOSTICS), setOf("R1"), marksMedia = true),
  PHONE_STORAGE_LOW("phone_storage_low", listOf(BackupAction.FREE_SPACE), setOf("E-P3")),
  STALE("stale", listOf(BackupAction.BATTERY_GUIDE), setOf("E-P15", "E-P2")),
  WAITING_UNLOCK("waiting_unlock", emptyList(), setOf("E-P13")),
  PROFILE_INACTIVE("profile_inactive", emptyList(), setOf("E-P19")),
}

/** Perche' il backup aspetta (`BackupStatus.Waiting`, `MediaBackupState.Queued`). */
enum class WaitReason(override val code: String) : StableCode {
  CHARGING("charging"),
  WIFI("wifi"),
  HOME_WIFI("home_wifi"),
  NIGHT_WINDOW("night_window"),
  BATTERY("battery"),
  THERMAL("thermal"),
  DATA_CAP("data_cap"),
  NAS("nas"),

  /** §2.11: prima del primo backup si aspetta la fine della scansione dell'area privata e del confronto delle copie simili. */
  COMPARING("comparing"),
  NEXT_RUN("next_run"),
}

/** Che cosa manca per poter fare il backup (`BackupStatus.NotConfigured`). */
enum class BackupSetupStep(override val code: String) : StableCode {
  NAS("nas"),
  PRIVATE_AREA("private_area"),
  SOURCES("sources"),
  DEVICE_FOLDER("device_folder"),
  MEDIA_PERMISSION("media_permission"),
}

/**
 * Lo stato del backup (§1.5): pagina Backup e Impostazioni. Lo riduce a `LibraryBackupStatus` (core-library) `:app` per il piede.
 * Istanti in ora del NAS, durate in ms.
 */
sealed interface BackupStatus {
  data class NotConfigured(val missing: Set<BackupSetupStep>) : BackupStatus

  /** D12: sulle share vere niente si scrive ("Backup in prova"). */
  data object TestOnly : BackupStatus

  /** Il riepilogo del primo backup e' pronto (§2.11): "Stanotte" o "Inizia ora". */
  data class FirstBackupReady(val summary: BackupSummary) : BackupStatus

  /** Tutto al sicuro; [atNasMillis] e' la fine dell'ultimo giro (null = non nota). */
  data class UpToDate(val safeCount: Int, val atNasMillis: Long?) : BackupStatus

  data class Waiting(val items: Int, val bytes: Long, val reason: WaitReason) : BackupStatus

  data class Running(
    val kind: BackupKind,
    val done: Int,
    val total: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val etaMillis: Long?,
    val trigger: BackupTrigger,
  ) : BackupStatus

  data class Stopped(val by: StopBy) : BackupStatus

  /** Il problema piu' grave, con quanti media riguarda (0 = tutto il backup) e le sue azioni. */
  data class Problem(val code: BackupProblemCode, val count: Int) : BackupStatus {
    val actions: List<BackupAction> get() = code.actions
  }
}

/** Lo stato di una sorgente nella pagina Backup (§1.5 `SourceStatus`). */
data class SourceStatus(
  val source: BackupSource,
  val active: Boolean,
  val media: Int,
  val safe: Int,
  val queued: Int,
  val problems: Map<BackupProblemCode, Int>,
) {
  /** Il problema piu' grave della sorgente (il primo nell'ordine del §2.6), o null. */
  val worst: BackupProblemCode? get() = problems.keys.minByOrNull { it.ordinal }
}

/** Perche' un media non e' sul NAS (`MediaBackupState.NotOnNas`). */
enum class NotOnNasReason(override val code: String) : StableCode {
  /** La sua cartella non e' fra le sorgenti scelte (E-B12). */
  SOURCE_OFF("source_off"),

  /** Il backup non e' configurato o e' "in prova" (D12). */
  BACKUP_OFF("backup_off"),

  /** Non ancora pianificato (lettura del telefono appena fatta). */
  NOT_YET("not_yet"),
}

/**
 * La riga "Backup" del pannello info del viewer (§1.5, D15): "in coda", "in caricamento" e "in verifica" stanno **solo** qui e nella
 * pagina del backup, mai nei bit della cella.
 */
sealed interface MediaBackupState {
  data object NotMine : MediaBackupState

  data class NotOnNas(val reason: NotOnNasReason) : MediaBackupState

  data class Queued(val reason: WaitReason?) : MediaBackupState

  data class Uploading(val bytesDone: Long, val bytesTotal: Long) : MediaBackupState

  data class Verifying(val bytesDone: Long, val bytesTotal: Long) : MediaBackupState

  /** Al sicuro: copia privata riletta qui all'istante [verifiedAtMillis] (ora del catalogo). */
  data class Safe(val verifiedAtMillis: Long) : MediaBackupState

  /** Solo la copia dell'area comune (D9): il guardiano crea quella privata quando serve (Q4 b). */
  data object OnlyCommonCopy : MediaBackupState

  /** Copia privata presente ma non riletta. */
  data object Unverified : MediaBackupState

  data class Problem(val code: BackupProblemCode) : MediaBackupState

  /** Cancellato dal telefono prima del backup (E-B4). */
  data object LostBeforeBackup : MediaBackupState
}

/** Numero e byte di foto e video (riepilogo, §2.11). */
data class SummaryCount(val photos: Int, val videos: Int, val photoBytes: Long, val videoBytes: Long) {
  init {
    require(photos >= 0 && videos >= 0 && photoBytes >= 0 && videoBytes >= 0) { "Conteggi negativi." }
  }

  val items: Int get() = photos + videos

  val bytes: Long get() = photoBytes + videoBytes

  operator fun plus(other: SummaryCount): SummaryCount =
    SummaryCount(photos + other.photos, videos + other.videos, photoBytes + other.photoBytes, videoBytes + other.videoBytes)

  companion object {
    val ZERO: SummaryCount = SummaryCount(0, 0, 0, 0)
  }
}

/**
 * Cosa c'e' gia' sul NAS prima del primo backup (§2.11 punti 1 e 6): il piano aspetta la fine della scansione dell'area privata
 * ("Sto cercando cosa c'e' gia' sul NAS…") e del confronto delle copie simili ("Sto confrontando 120 foto…"). Lo legge la porta
 * [ExistingCopiesProgress], che `:app` lega alla scansione (core-nasmedia) e al rilevatore delle copie simili (core-library, P15).
 */
data class ExistingCopiesState(val privateAreaScanned: Boolean, val similarPending: Int) {
  init {
    require(similarPending >= 0) { "Conteggio negativo." }
  }

  /** Niente da aspettare: i numeri del riepilogo sono definitivi. */
  val settled: Boolean get() = privateAreaScanned && similarPending == 0

  companion object {
    val SETTLED: ExistingCopiesState = ExistingCopiesState(privateAreaScanned = true, similarPending = 0)
  }
}

/** La stima del primo backup con le regole attuali (§2.9; entro ±30%, M2 e F32). */
data class BackupEstimate(
  val durationMillis: Long,
  val nights: Int,
  val wifiBytes: Long,
  val mobileBytes: Long,
  /** Batteria consumata se fosse tutta a batteria, in percento (null = dalla presa o non stimabile). */
  val batteryPercent: Int?,
  /**
   * Aggiunta compatibile di P4: le regole attuali non lasciano partire il backup (nessuna ora utile, o il backup spento nelle
   * impostazioni). [nights] vale 0 e il riepilogo lo dice invece di "0 notti".
   */
  val neverFinishes: Boolean = false,
)

/** Gli avvisi del riepilogo del primo backup. */
enum class SummaryWarning(override val code: String) : StableCode {
  /** "Inizia ora" sui dati mobili: conferma con la stima. */
  MOBILE_DATA("mobile_data"),

  /** "Meglio in carica e sul Wi-Fi di casa". */
  ON_BATTERY("on_battery"),
  PHONE_STORAGE_LOW("phone_storage_low"),

  /** E-P1: si salvano solo le foto selezionate. */
  PARTIAL_ACCESS("partial_access"),

  /** E-P21: foto in attesa del permesso della posizione. */
  MEDIA_LOCATION("media_location"),

  /** Piu' di una notte con il preset attuale. */
  LONG_FIRST_BACKUP("long_first_backup"),
}

/**
 * Il riepilogo del primo backup (§1.5, §2.11 punti 4 e 6): **tre numeri** e la stima. "N gia' sul NAS (collegati), M con una copia
 * simile sul NAS (si caricano e si vedranno insieme), K nuovi: da caricare M+K, X GB, circa Y ore". Finche' [existing] non e'
 * definitivo il riepilogo dice "sto ancora confrontando" e i numeri sono provvisori.
 *
 * @property linked identici byte per byte a una copia dell'area privata: si collegano dopo la rilettura, nessun caricamento (D8).
 * @property similar con una copia simile sul NAS (D36): si caricano sempre e si uniscono in una cella.
 * @property fresh nuovi: si caricano.
 * @property inCommonArea identici a una copia dell'area comune o pubblica: **non** si caricano e non sono "al sicuro" (§2.11 punto 3,
 *   Q4 b): fuori dai tre numeri, il riepilogo li puo' citare a parte.
 * @property bySource da caricare, per sorgente ([BackupSource.id]).
 */
data class BackupSummary(
  val existing: ExistingCopiesState,
  val linked: SummaryCount,
  val similar: SummaryCount,
  val fresh: SummaryCount,
  val inCommonArea: SummaryCount,
  val bySource: Map<String, SummaryCount>,
  val estimate: BackupEstimate?,
  val warnings: Set<SummaryWarning>,
  /**
   * Aggiunta compatibile di P4: la stima di "Inizia ora" (lavoro avviato dall'utente, parte subito e scavalca fascia e carica,
   * sui dati mobili con conferma). [estimate] e' la scelta "Stanotte" (predefinita): segue il preset.
   */
  val estimateNow: BackupEstimate? = null,
) {
  /** "Sto ancora confrontando": i tre numeri non sono ancora definitivi. */
  val comparing: Boolean get() = !existing.settled

  /** Da caricare: M + K. */
  val toUpload: SummaryCount get() = similar + fresh

  companion object {
    val EMPTY: BackupSummary = BackupSummary(
      existing = ExistingCopiesState.SETTLED,
      linked = SummaryCount.ZERO,
      similar = SummaryCount.ZERO,
      fresh = SummaryCount.ZERO,
      inCommonArea = SummaryCount.ZERO,
      bySource = emptyMap(),
      estimate = null,
      warnings = emptySet(),
    )
  }
}
