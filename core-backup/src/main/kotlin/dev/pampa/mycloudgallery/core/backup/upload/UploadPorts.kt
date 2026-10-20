package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration

// Fase 5A, P2 (docs/fase5-progetto.md §2.2, D3-D5, D10, D11): le porte e le misure del MOTORE VERO del caricamento, oltre ai contratti
// di P0 (`contract/`). Tutte con un valore neutro, cosi' chi le lega (P7) puo' cominciare da quelle che ha: senza [UploadFacts] il giro
// delle foto riprende anche gli elementi dei video (come l'esecutore di riferimento), senza [UploadIndexing] niente va all'indice (la
// preparazione dal NAS lo rifara', D10).

/** Cosa il catalogo sa di una versione oltre a `BackupCatalog`: il tipo (il giro delle foto non riprende un video, D11) e il MIME. */
class UploadDescription(val kind: MediaKind, val mime: String?) {
  override fun toString(): String = "UploadDescription(${kind.code})"
}

/** Il tipo e il MIME di una versione dal catalogo locale (:app la lega a `MediaCatalog`, P7). Null = non noto. */
fun interface UploadFacts {
  suspend fun describe(mediaId: MediaId, versionId: VersionId): UploadDescription?

  companion object {
    /** Nessuna descrizione: ogni giro riprende tutti gli elementi aperti, e niente si prepara. */
    val UNKNOWN: UploadFacts = UploadFacts { _, _ -> null }
  }
}

/**
 * La tappa del lavoro vista dal motore (D11, F4-D22, `StagedRun`): se le regole lasciano continuare e il **registro del traffico**,
 * scritto subito con la rete usata, **prima** del punto di ripresa (§2.2 "Traffico"). [UploadStages] la lega a `StageScope`.
 */
interface UploadControl {
  /** Le regole lasciano continuare? Falso = il giro si ferma al prossimo passo (o al prossimo blocco da 1 MiB) e salva. */
  suspend fun proceed(): Boolean = true

  /** [bytes] passati sulla rete adesso (caricamento e rilettura di verifica): si contano prima del punto di ripresa. */
  suspend fun traffic(bytes: Long) = Unit

  companion object {
    /** Senza regole ne' registro: solo il tempo del giro (i test e `BackupEngine.run`). */
    val FREE: UploadControl = object : UploadControl {}
  }
}

/** Il motore vero: il contratto di P0 piu' i giri dentro una tappa ([UploadControl]). Lo consegna [BackupEngineFactory]. */
interface StagedBackupEngine : BackupEngine {
  suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger, control: UploadControl): BackupRunResult

  suspend fun resumeAll(budget: Duration, control: UploadControl): BackupRunResult
}

/**
 * Cio' che serve per preparare e indicizzare un file caricato (D10): la versione, i byte letti (SHA-256 e `qh1` degli stessi byte) e
 * la descrizione del catalogo. Nessun dato dell'utente nel `toString`.
 */
class UploadSubject(
  val mediaId: MediaId,
  val versionId: VersionId,
  val size: Long,
  val sha256: Sha256,
  val quickHash: QuickHash,
  val description: UploadDescription?,
) {
  override fun toString(): String = "UploadSubject($size B)"
}

/** Le uscite della preparazione di un file, tenute in memoria fra la lettura del telefono e il collegamento. */
open class PreparedUpload(
  /** I byte delle immagini preparate (il tetto della memoria del motore). */
  val bytesInMemory: Long,
) {
  override fun toString(): String = "PreparedUpload($bytesInMemory B)"
}

/**
 * D10, "chi carica prepara": miniature, anteprime e fotogrammi dai **byte del telefono**, nella stessa lettura dell'hash; il record va
 * all'indice con il timbro letto **dopo** la pubblicazione, dopo che il media e' "al sicuro". Con F4-D31 acceso o con una share non
 * pubblicabile resta nella replica locale (lo decide `IndexPublisher.stage`, R2 della 4A). L'implementazione vera e'
 * [PreparingUploadIndexing]; il motore non dipende da lei per la sicurezza: un errore qui non ferma mai un caricamento.
 */
interface UploadIndexing {
  /** Vero se prepara e pubblica (il motore tiene allora le uscite fino al collegamento). */
  val enabled: Boolean

  /** Prepara dai byte del telefono appena letti ([bytes] sono gli stessi dell'hash). Null = niente da tenere. Mai un'eccezione. */
  suspend fun prepare(subject: UploadSubject, bytes: MediaBytes): PreparedUpload?

  /** Dopo il collegamento: record (timbro [stamp] del file pubblicato in [path]) e immagini all'indice. Mai un'eccezione. */
  suspend fun publish(subject: UploadSubject, share: String, path: SharePath, stamp: NasStamp, prepared: PreparedUpload?)

  companion object {
    /** Niente preparazione ne' indice. */
    val NONE: UploadIndexing = object : UploadIndexing {
      override val enabled: Boolean = false

      override suspend fun prepare(subject: UploadSubject, bytes: MediaBytes): PreparedUpload? = null

      override suspend fun publish(subject: UploadSubject, share: String, path: SharePath, stamp: NasStamp, prepared: PreparedUpload?) = Unit
    }
  }
}

/**
 * Le misure del motore (§2.2, D5, D11). I valori di default sono quelli del progetto; i test le stringono per provare punti di ripresa
 * e guasti con file piccoli.
 *
 * @property chunkBytes scritture e letture del telefono da 1 MiB (§2.2).
 * @property checkpointBytes un punto di ripresa ogni 64 MiB (D5): dopo un'interruzione si ricaricano al massimo questi byte.
 * @property parallel file in volo (D11): uno in caricamento, l'altro in lettura, preparazione o verifica.
 * @property spaceCheckBytes oltre questa dimensione lo spazio si controlla prima di scrivere (§2.2 "Spazio"), con [spaceMarginBytes].
 * @property inMemoryMaxBytes i file fino a qui si leggono dal telefono in memoria (hash e preparazione dagli stessi byte, D10).
 * @property lockedRetries quante attese crescenti, nello stesso giro, per un file aperto dal PC (E-N13), dalla prima di [lockedFirstWaitMs].
 * @property phoneReadRetries letture del telefono fallite con la riga invariata prima di `UNREADABLE` (D15).
 * @property maxNameAttempts i nomi "nome (n)" provati dopo collisioni con file diversi (E-B3); oltre, l'elemento aspetta il prossimo giro.
 * @property maxStepsPerItem i passi di un elemento in un giro: oltre, aspetta il prossimo (difesa contro un ciclo che non avanza).
 * @property allowedRoots le radici in cui il backup puo' pubblicare (§2.2 "Regole"): `Backup/` della share, o quella recintata (D13).
 * @property driveGuardCopies se il motore guida anche gli elementi del guardiano (`GUARD_COPY`, D9). Di default no: sono di P3.
 * @property busyRestartAfterMs D-13: un elemento in caricamento fermo da tanto (ora del NAS dall'ultimo passo del diario) su un **proprio**
 *   temporaneo che il NAS tiene occupato (una connessione morta che il NAS non ha ancora chiuso) riparte su un temporaneo nuovo (il tentativo
 *   dopo); il vecchio resta, citato dal diario finche' l'elemento e' aperto, e il motore lo toglie prima che l'elemento finisca, quando il NAS lo
 *   lascia (fino ad allora l'elemento aspetta il giro dopo, senza dire "occupato"). Dieci minuti: piu' o meno quanto costa ricaricare un video da
 *   3 GB sul Wi-Fi di casa, contro un'attesa che sul WD puo' durare ore (misure in `docs/decisioni.md`, D-13).
 * @property maxTempAttempts D-13: i temporanei nuovi al massimo per un elemento (oltre, si aspetta che il NAS lasci l'ultimo).
 */
data class UploadConfig(
  val chunkBytes: Int = 1 shl 20,
  val checkpointBytes: Long = 64L shl 20,
  val parallel: Int = 2,
  val spaceCheckBytes: Long = 1_000_000_000L,
  val spaceMarginBytes: Long = 1L shl 30,
  val planPage: Int = 500,
  val inMemoryMaxBytes: Long = 64L shl 20,
  val lockedRetries: Int = 5,
  val lockedFirstWaitMs: Long = 2_000L,
  val phoneReadRetries: Int = 3,
  val maxNameAttempts: Int = 999,
  val maxStepsPerItem: Int = 200,
  val allowedRoots: List<SharePath> = listOf(SharePath.of("Backup")),
  val driveGuardCopies: Boolean = false,
  val purpose: Purpose = Purpose.BACKGROUND,
  val busyRestartAfterMs: Long = 10L * 60_000L,
  val maxTempAttempts: Int = 3,
) {
  init {
    require(chunkBytes > 0 && checkpointBytes >= chunkBytes) { "Misure di scrittura non valide." }
    require(parallel in 1..2) { "Al piu' due file in volo (D11)." }
    require(planPage in 1..1_000 && inMemoryMaxBytes in 0..Int.MAX_VALUE.toLong()) { "Misure non valide." }
    require(lockedRetries >= 0 && lockedFirstWaitMs >= 0 && phoneReadRetries >= 1 && maxNameAttempts >= 1 && maxStepsPerItem >= 8) {
      "Tetti non validi."
    }
    require(allowedRoots.isNotEmpty()) { "Almeno una radice ammessa." }
    require(busyRestartAfterMs >= 0 && maxTempAttempts >= 0) { "Tetti dell'occupato non validi." }
  }

  /** [path] e' dentro una radice ammessa (e non e' la radice stessa). */
  fun allows(path: SharePath): Boolean = allowedRoots.any { root ->
    val c = path.components
    val r = root.components
    c.size > r.size && r.indices.all { c[it].equals(r[it], ignoreCase = true) }
  }
}

// --- Fase 5B (P14): i file in arrivo da altre app (D24, elementi `INCOMING` del diario) ------------------------------------------------

/** La copia locale di un file in arrivo, aperta in lettura (in `noBackupFilesDir/incoming/`). La chiude chi la usa. */
interface IncomingLocalFile : java.io.Closeable {
  val bytes: MediaBytes
}

/**
 * I file in arrivo che il motore carica (D24, E-X8): la copia locale di ogni elemento `INCOMING` e cosa farne a lavoro finito. `:app` (P14) la
 * lega alle righe di `incoming_item` (che sanno quale file locale carica quale elemento) e ai file di `IncomingStager`. Mai nomi nei log.
 */
interface IncomingLocalFiles {

  /** La copia locale dell'elemento [id], o null se non c'e' piu' (tolta, ripulita): l'elemento esce (`GONE`), niente si carica. */
  suspend fun open(id: BackupItemId): IncomingLocalFile?

  /**
   * L'elemento [id] e' finito in [state] (terminale). Con `DONE` o `LINKED_EXISTING` (la copia e' sul NAS, riletta per intero e collegata nel
   * catalogo) la copia locale **si toglie**; con `GONE` o `FAILED` resta (l'utente non perde il file). Idempotente: si puo' chiamare due volte.
   */
  suspend fun finished(id: BackupItemId, state: UploadState)
}

/**
 * Come il motore carica i file in arrivo (D24; null in [SafeUploadEngine] = gli elementi `INCOMING` aspettano). Stesso caricamento sicuro di
 * ogni elemento (temporaneo proprio, rilettura completa, pubblicazione senza sostituire, "nome (2)" se occupato), con tre differenze: i byte
 * vengono da [files] (non dal telefono), la destinazione e' la cartella scelta dall'utente nell'area privata **o comune** dell'account (non
 * `Backup/`), e il cancello e' [gate], che vale per la share di destinazione (D12, R2, congelamento, emergenza, sola lettura, pausa).
 *
 * @property root D13: con la recinzione di debug, la radice sotto cui si puo' pubblicare (`mcg-campo`); null = tutta la share, fuori
 *   dall'area dell'app e da `Versioni/`.
 */
class IncomingUploadPorts(val files: IncomingLocalFiles, val gate: BackupGate, val root: SharePath? = null) {

  /** [path] e' un file di media dove un arrivo puo' essere pubblicato: fuori dall'area dell'app e da `Versioni/`, dentro [root] se c'e'. */
  fun allows(path: SharePath): Boolean {
    if (!NasOpsPaths.isMediaPath(path) || VersionPaths.isInVersions(path) || VersionPaths.isVersionsRoot(path)) return false
    val r = root?.components ?: return true
    val c = path.components
    return c.size > r.size && r.indices.all { c[it].equals(r[it], ignoreCase = true) }
  }

  override fun toString(): String = "IncomingUploadPorts"
}
