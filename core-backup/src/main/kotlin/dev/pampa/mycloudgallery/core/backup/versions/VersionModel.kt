package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadFlow
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File

// Fase 5B, P8 (docs/fase5-progetto.md §2.4, D17, D18, D19, D20, D32, D35; §5.4 invarianti 12-13): il modello delle VERSIONI sul NAS. I
// lavori `VERSION` (una versione nuova al solito percorso, l'attuale in `Versioni/`) e `SWAP` ("Ripristina originale": due rinomine,
// nessun byte caricato) sono elementi di un **diario** ([VersionJournal]) con la stessa macchina a stati del backup (`UploadStateMachine`,
// cammini `VERSION`, `VERSION_DIRECT` e `SWAP` di P0) e le stesse regole: ogni valore di un passo si scrive **prima** della chiamata al
// NAS (il temporaneo, il nome in `Versioni/`, il nome finale), ogni passo e' idempotente, la risposta persa si riconosce dal NAS (sorgente
// assente e destinazione con lo stesso `fileId`). Il diario nasce solo da dati locali (catalogo, conferme della UI): niente di quanto si
// legge dal NAS crea un lavoro (E-S11).
//
// **Cosa il lavoro non puo' fare** (lo garantiscono le primitive, `NasBackupOps`, e `VersionPaths`): sostituire un file (le rinomine e la
// pubblicazione non sostituiscono mai), toccare un file fuori dalla share del media, spostare qualcosa che non sia la versione del media
// stesso (sempre con il suo timbro, controllato sullo stesso handle), togliere un file (nessuna primitiva lo fa).

/** L'id di un lavoro del diario delle versioni (positivo): entra nel nome del temporaneo `<io>-bk-<id>`. */
@JvmInline
value class VersionJobId(val value: Long) {
  init {
    require(value > 0) { "Id di lavoro non valido." }
  }

  override fun toString(): String = value.toString()
}

/** Da dove arrivano i byte di un lavoro. Dati dell'utente (righe, percorsi, impronte): mai nei log. */
sealed interface VersionSource {
  /** Una riga del telefono con il timbro atteso (Samsung "Salva", E-E1, E-E5; l'originale ancora sul telefono, E-E6). */
  data class Phone(val ref: PhoneRef, val expected: FileStamp) : VersionSource {
    override fun toString(): String = "VersionSource.Phone"
  }

  /** Un file locale preparato e riconosciuto dal suo SHA-256: una correzione (D19), il risultato di un editor esterno (E-E3, P11). */
  data class Staged(val sha256: Sha256) : VersionSource {
    override fun toString(): String = "VersionSource.Staged"
  }

  /** `SWAP`: la versione da riportare al solito percorso, gia' sul NAS in `Versioni/` (con la sua posizione nel catalogo, se nota). */
  data class InVersions(val path: SharePath, val stamp: NasStamp, val locationId: LocationId?) : VersionSource {
    init {
      require(VersionPaths.isInVersions(path)) { "La versione da ripristinare sta in Versioni/." }
    }

    override fun toString(): String = "VersionSource.InVersions"
  }
}

/**
 * Il file che sta al solito percorso e che il lavoro sposta in `Versioni/` (D17): la versione che contiene, la sua posizione nel catalogo
 * (per spostarla insieme al file), il timbro con cui lo si sposta (obbligatorio: un file diverso non si sposta) e se e' l'originale (per il
 * nome "(originale)" e per l'attivita' in sospeso dei video, D20).
 */
data class CurrentFile(val versionId: VersionId, val locationId: LocationId?, val stamp: NasStamp, val original: Boolean) {
  override fun toString(): String = "CurrentFile(${stamp.size} B${if (original) ", originale" else ""})"
}

/** Dove va la versione nuova di un lavoro `VERSION`. */
enum class VersionTarget(override val code: String) : StableCode {
  /** Al solito percorso: e' la versione attuale (D17). */
  USUAL_PATH("usual_path"),

  /** In `Versioni/`: una versione non attuale da conservare (E-E6), o il solito percorso occupato da un file di altri (mai sostituirlo). */
  VERSIONS_FOLDER("versions_folder"),
}

/** Perche' un lavoro aspetta, o perche' e' finito senza fare cio' che doveva. Codici stabili. */
enum class VersionHold(override val code: String) : StableCode {
  /**
   * D17, §2.4: il file al solito percorso non e' quello che il diario conosce (modificato o tolto dal PC). **Niente si sposta**: la versione
   * nuova resta nel temporaneo finche' la scansione non registra il file del PC e il piano si ricalcola. Per uno `SWAP` il lavoro finisce
   * (`FAILED`): il piano confermato dall'utente non e' piu' quello.
   */
  SOURCE_CHANGED("source_changed"),

  /** Il solito percorso e' occupato da un file di altri: la versione nuova va in `Versioni/`, quel file non si tocca. */
  USUAL_PATH_TAKEN("usual_path_taken"),

  /** `SWAP`: l'originale non si poteva riportare (cambiato o sparito): l'attuale e' tornata al suo posto e il ripristino non e' fatto. */
  ROLLED_BACK("rolled_back"),
}

/**
 * Un lavoro del diario delle versioni. **Ogni valore di un passo si scrive qui prima della chiamata al NAS**. Il cammino ([flow]) deriva dal
 * tipo e da [current]: `VERSION` se c'e' un attuale da spostare, `VERSION_DIRECT` se no, `SWAP`. [current] non torna mai a null.
 *
 * @property versionId `VERSION`: la versione da salvare; `SWAP`: la versione da riportare al solito percorso (l'originale).
 * @property usualPath il solito percorso del media nella share (dove sta l'attuale e dove andra' la versione nuova).
 * @property archivePath il nome in `Versioni/` dell'attuale, scritto prima di `ARCHIVING`; [archiveAttempt] i nomi occupati provati.
 * @property archivedStamp il timbro dell'attuale dopo l'archiviazione (stesso `fileId`); null = niente spostato.
 * @property finalPath dove si pubblica (o si ripristina), scritto prima di `PUBLISHING`/`RESTORING`; [nameAttempt] i nomi occupati provati.
 * @property temp, resumeAt, resumeId, sha256, quickHash, tempStamp, verifyAttempts come per il caricamento della 5A (D4, D5, R3).
 */
data class VersionJob(
  val id: VersionJobId,
  val kind: UploadKind,
  val state: UploadState,
  val mediaId: MediaId,
  val versionId: VersionId,
  val size: Long,
  val share: String,
  val usualPath: SharePath,
  val source: VersionSource,
  val target: VersionTarget = VersionTarget.USUAL_PATH,
  val current: CurrentFile? = null,
  val archivePath: SharePath? = null,
  val archiveAttempt: Int = 0,
  val archivedStamp: NasStamp? = null,
  val temp: SharePath? = null,
  val resumeAt: Long = 0,
  val resumeId: Long? = null,
  val sha256: Sha256? = null,
  val quickHash: QuickHash? = null,
  val tempStamp: NasStamp? = null,
  val finalPath: SharePath? = null,
  val nameAttempt: Int = 0,
  val finalStamp: NasStamp? = null,
  val verifyAttempts: Int = 0,
  val hold: VersionHold? = null,
  val problem: BackupProblemCode? = null,
) {
  init {
    require(kind == UploadKind.VERSION || kind == UploadKind.SWAP) { "Il diario delle versioni tiene solo VERSION e SWAP." }
    require(size >= 0 && resumeAt in 0..size && archiveAttempt >= 0 && nameAttempt >= 0 && verifyAttempts >= 0) { "Valori fuori misura." }
    require(kind != UploadKind.SWAP || (source is VersionSource.InVersions && current != null)) { "Uno scambio sposta l'attuale e riporta una versione." }
    require(kind != UploadKind.VERSION || source !is VersionSource.InVersions) { "Una versione nuova non viene da Versioni/." }
    require(archivePath == null || VersionPaths.isInVersions(archivePath)) { "L'attuale si archivia solo in Versioni/." }
  }

  val flow: UploadFlow get() = UploadStateMachine.flowOf(kind, archivesCurrent = current != null)

  /** I percorsi, le righe e le impronte sono dati dell'utente: nel log solo id, tipo e stato. */
  override fun toString(): String = "VersionJob(#$id, ${kind.code}, ${state.code})"
}

/** Un lavoro da creare (`PLANNED`), da dati locali soltanto. */
class NewVersionJob(
  val kind: UploadKind,
  val mediaId: MediaId,
  val versionId: VersionId,
  val size: Long,
  val share: String,
  val usualPath: SharePath,
  val source: VersionSource,
  val target: VersionTarget,
  val current: CurrentFile?,
  /** Lo SHA-256 gia' noto dei byte (un file preparato): la lettura lo deve ritrovare. */
  val sha256: Sha256? = null,
) {
  override fun toString(): String = "NewVersionJob(${kind.code}, $size B)"
}

/**
 * Il diario delle versioni. Le stesse regole del diario del backup (`BackupStore`): **le scritture sono transazioni**, ogni transizione deve
 * essere ammessa da `UploadStateMachine` per il cammino del lavoro (altrimenti `IllegalStateException`), l'identita' (tipo, media, versione,
 * share, solito percorso, dimensione) e un temporaneo gia' scritto non cambiano (`IllegalArgumentException`), [create] e' idempotente per
 * versione e tipo. Nella stessa tabella del diario del backup (`backup_item`) i lavori aperti sono citati dalla pulizia dei propri file
 * (D33) e il caricamento della 5A non crea una copia nuova di una versione che ha un lavoro aperto.
 */
interface VersionJournal {

  /** Crea il lavoro, o restituisce quello aperto per la stessa versione e lo stesso tipo (mai due). */
  suspend fun create(job: NewVersionJob): VersionJob

  suspend fun job(id: VersionJobId): VersionJob?

  /** I lavori aperti (non terminali), dal piu' vecchio, al massimo [limit]. */
  suspend fun open(limit: Int): List<VersionJob>

  suspend fun update(job: VersionJob)

  /** Vero se la versione ha gia' un elemento aperto (un lavoro delle versioni, o un caricamento della 5A nella stessa tabella). */
  suspend fun busy(versionId: VersionId): Boolean

  /** I lavori di un media, terminali compresi, dal piu' vecchio. */
  suspend fun forMedia(mediaId: MediaId): List<VersionJob>

  /** Tutti i lavori di una share, terminali compresi (la franchigia del congelamento, D32). */
  suspend fun inShare(share: String): List<VersionJob>

  /**
   * R17 della revisione 5B: i lavori di una share, terminali compresi, il cui ultimo passo e' stato scritto (ora del NAS del diario) da
   * [sinceNasMs] in poi: la franchigia vale solo per i lavori recenti. Di default tutti (un diario che non tiene l'ora).
   */
  suspend fun inShareSince(share: String, sinceNasMs: Long): List<VersionJob> = inShare(share)

  /** D33: i temporanei citati dai lavori aperti (la pulizia dei propri file non li toglie). */
  suspend fun citedTemps(): Set<SharePath>
}

// --- Il catalogo visto dalle versioni ----------------------------------------------------------------------------------------------

/** Una copia sul NAS di una versione, come la sa il catalogo locale. Percorso e share: mai nei log. */
class NasVersionCopy(
  val locationId: LocationId,
  val share: String,
  val path: SharePath,
  val stamp: NasStamp,
  /** L'area della share per l'account (null = sconosciuta: non vale come copia privata). */
  val area: NasArea?,
  val verifiedAtMillis: Long?,
  val present: Boolean,
) {
  override fun toString(): String = "NasVersionCopy(${area?.code}, ${stamp.size} B)"
}

/** Una copia sul telefono di una versione. */
class PhoneVersionCopy(val locationId: LocationId, val ref: PhoneRef, val stamp: FileStamp, val present: Boolean = true) {
  override fun toString(): String = "PhoneVersionCopy"
}

/** Una versione di un media con le sue copie. */
class VersionFacts(
  val versionId: VersionId,
  val kind: VersionKind,
  val parentId: VersionId?,
  val size: Long,
  val fullHash: Sha256?,
  val quickHash: QuickHash?,
  val nas: List<NasVersionCopy>,
  val phone: List<PhoneVersionCopy>,
) {
  override fun toString(): String = "VersionFacts(${kind.code}, $size B)"
}

/** Un media con tutte le sue versioni (§1.3, `MediaCatalog.versionLocations`). */
class VersionedMedia(
  val mediaId: MediaId,
  /** Il media e' di questo account (le versioni di un media altrui non si toccano). */
  val mine: Boolean,
  val kind: MediaKind,
  /** Il tipo della versione attuale (`MediaSniffer` o MediaStore), per le correzioni (Q6). */
  val mime: String?,
  val currentVersionId: VersionId,
  /** Dalla piu' vecchia. */
  val versions: List<VersionFacts>,
) {
  val current: VersionFacts get() = versions.first { it.versionId == currentVersionId }

  val original: VersionFacts? get() = versions.firstOrNull { it.kind == VersionKind.ORIGINAL }

  fun version(id: VersionId): VersionFacts? = versions.firstOrNull { it.versionId == id }

  override fun toString(): String = "VersionedMedia(${kind.code}, ${versions.size} versioni)"
}

/**
 * Il catalogo locale visto dalle versioni e dalle correzioni. Lo lega `:app` (P14) a `MediaCatalog` (`versionLocations`, `recordFullHash`,
 * `attachCopies`, `relocate`, `restoreOriginal`, `correct`). **Ogni decisione parte da qui**, mai da un dato letto dal NAS (E-S11); "al
 * sicuro" nasce solo da [attachVerifiedCopy], cioe' da una rilettura fatta qui.
 */
interface VersionCatalog {

  /** Il media con le sue versioni e le loro copie, com'e' adesso; null se non c'e'. */
  suspend fun media(mediaId: MediaId): VersionedMedia?

  /** L'hash completo dei byte letti qui (come `BackupCatalog.recordFullHash`). */
  suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord

  /**
   * Una copia riletta qui con lo SHA-256 della versione: il media e' "al sicuro" (come `BackupCatalog.attachVerifiedCopies`). Falso se il
   * catalogo la rifiuta (dimensione o hash che non tornano): il lavoro aspetta, nessun "al sicuro".
   */
  suspend fun attachVerifiedCopy(copy: VerifiedCopy): Boolean

  /**
   * Un file proprio spostato dalle versioni (archiviato in `Versioni/` o riportato al solito percorso): **la stessa posizione** in un altro
   * percorso della stessa share, con il timbro dopo la rinomina; la verifica della posizione resta (i byte sono gli stessi). `MediaCatalog.relocate`.
   */
  suspend fun relocate(locationId: LocationId, share: String, path: SharePath, stamp: NasStamp)

  /** "Ripristina originale" nel catalogo (`MediaCatalog.restoreOriginal`, `VersionRules.restoreOriginal`): l'originale torna la versione attuale. */
  suspend fun restoreOriginal(mediaId: MediaId)

  /**
   * Una correzione scritta in un file come **versione nuova** (`MediaCatalog.correct`, `VersionRules.corrected`, `VersionKind.CORRECTED`)
   * con l'impronta dei byte corretti, **prima** che la lettura del telefono veda il cambio (F4-D20): nessun posto la contiene ancora (la
   * lettura del telefono la riconoscera' come ritorno a una versione nota, il NAS dal lavoro `VERSION`). Null se il media non c'e' piu'.
   */
  suspend fun correct(mediaId: MediaId, fields: Set<CorrectionField>, size: Long, quickHash: QuickHash, sha256: Sha256): VersionId?

  /**
   * Campo, gruppo A (D3, F36): i metadati della versione corretta [versionId] letti dai byte corretti [file] (di tipo [mime]) e scritti nel catalogo,
   * subito: la Libreria e il viewer mostrano la data e il luogo nuovi senza aspettare che la lettura del telefono o del NAS rilegga il file (prima la
   * versione nasceva senza metadati e la Libreria restava sulla data di prima). Non lancia; di norma niente.
   */
  suspend fun readCorrected(mediaId: MediaId, versionId: VersionId, file: File, mime: String) = Unit

  /**
   * Q6 (a), L40: la correzione di un formato che non si scrive (HEIC, RAW, video) resta **nell'app e nell'indice**: il file non si tocca.
   * Falso se non si e' potuta registrare.
   */
  suspend fun correctInApp(mediaId: MediaId, versionId: VersionId, change: MetadataChange): Boolean

  /** Il problema del media (D15), o null per toglierlo. */
  suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?)
}

// --- File locali, attivita' in sospeso, misure ---------------------------------------------------------------------------------------

/** I byte di un file preparato, letti a intervalli; si chiude dopo l'uso. */
interface StagedBytes : MediaBytes, AutoCloseable

/** Un file preparato e il media a cui appartiene (scritto prima del file: la ripresa dopo una morte sa a chi darlo). */
class StagedEntry(val sha256: Sha256, val mediaId: MediaId, val size: Long) {
  override fun toString(): String = "StagedEntry($size B)"
}

/**
 * I file locali delle versioni (in `noBackupFilesDir`, P14): le copie di lavoro delle correzioni e i file **preparati** di una versione
 * (riconosciuti dal loro SHA-256, con il media a cui appartengono), che il lavoro `VERSION` carica e poi toglie. Sopravvivono alla morte del
 * processo: un file preparato senza lavoro aperto si ritrova ([pending]) e si affida al suo lavoro, o si toglie se la correzione non e' mai
 * arrivata al catalogo.
 */
interface StagedVersionFiles {
  /** Un percorso nuovo nella cartella di lavoro, che **non esiste** ancora (lo crea chi ci scrive). */
  fun newWorkFile(): File

  /**
   * Rende [file] (completo, con SHA-256 [sha256]) il file preparato di quella impronta per il media [mediaId]: prima si scrive a chi
   * appartiene, poi il file prende il suo nome (una rinomina). Idempotente. Restituisce il file preparato.
   */
  fun keep(file: File, sha256: Sha256, mediaId: MediaId): File

  /** Il file preparato di [sha256], o null se non c'e'. */
  fun file(sha256: Sha256): File?

  /** I byte del file preparato, o null se non c'e'. */
  fun open(sha256: Sha256): StagedBytes?

  /** I file preparati che ci sono (con il loro media), per la ripresa dopo una morte. */
  fun pending(): List<StagedEntry>

  /** Toglie il file preparato e la sua nota (il lavoro e' finito, o la correzione non c'e'). Assente = niente. */
  fun discard(sha256: Sha256)
}

/**
 * D20: un video modificato e salvato sopra l'originale (l'originale e' finito in `Versioni/`): nasce l'attivita' in sospeso "Hai modificato
 * questo video. L'originale occupa X sul NAS: vuoi eliminarlo?". Idempotente per versione. La lega `:app` a `RoomPendingTasks` (tasks/).
 */
fun interface VideoOriginalTasks {
  suspend fun originalKept(mediaId: MediaId, versionId: VersionId, bytes: Long)

  companion object {
    val NONE: VideoOriginalTasks = VideoOriginalTasks { _, _, _ -> }
  }
}

/**
 * Le misure del motore delle versioni (le stesse del caricamento della 5A, D5, D11; i test le stringono).
 *
 * @property allowedRoots dove una versione nuova puo' essere pubblicata (il solito percorso di un media del backup, e `Versioni/`).
 */
data class VersionConfig(
  val chunkBytes: Int = 1 shl 20,
  val checkpointBytes: Long = 64L shl 20,
  val spaceCheckBytes: Long = 1_000_000_000L,
  val spaceMarginBytes: Long = 1L shl 30,
  val inMemoryMaxBytes: Long = 64L shl 20,
  val lockedRetries: Int = 5,
  val lockedFirstWaitMs: Long = 2_000L,
  val phoneReadRetries: Int = 3,
  val maxNameAttempts: Int = 999,
  val maxStepsPerJob: Int = 200,
  val openLimit: Int = 1_000,
  /**
   * D13 (P14): con la recinzione di debug e un account di prova, la radice di prova (`mcg-campo`). Solo i media il cui solito percorso sta
   * sotto di lei hanno lavori delle versioni (le loro `Versioni/` stanno in `<radice>/Versioni/`, `VersionPaths`); gli altri non si toccano
   * mai. Null = nessuna recinzione.
   */
  val fenceRoot: SharePath? = null,
) {
  init {
    require(chunkBytes > 0 && checkpointBytes >= chunkBytes) { "Misure di scrittura non valide." }
    require(inMemoryMaxBytes in 0..Int.MAX_VALUE.toLong()) { "Misure non valide." }
    require(lockedRetries >= 0 && phoneReadRetries >= 1 && maxNameAttempts >= 1 && maxStepsPerJob >= 8 && openLimit > 0) { "Tetti non validi." }
  }
}

/** L'esito di un giro del motore delle versioni. Solo numeri e codici. */
data class VersionRunResult(
  val done: Int,
  val gone: Int,
  val failed: Int,
  val waiting: Int,
  val held: Int,
  val bytesUploaded: Long,
  val stoppedBy: RunStop,
  val problem: BackupProblemCode?,
) {
  companion object {
    fun nothing(stop: RunStop, problem: BackupProblemCode? = null): VersionRunResult = VersionRunResult(0, 0, 0, 0, 0, 0, stop, problem)
  }
}
