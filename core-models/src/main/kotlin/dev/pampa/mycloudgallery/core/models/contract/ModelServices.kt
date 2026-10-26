package dev.pampa.mycloudgallery.core.models.contract

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// I SERVIZI dei modelli (docs/fase6-progetto.md §1.2 "core-models", §2.1, D16-D20, E-A1-E-A3, E-A11, E-S16). Contratti di P0; la
// logica (gestore, sorgenti, scaricamento con ripresa, verifica, scaffale sul NAS) e' di P3. Il resto dell'app riceve SOLO
// [ModelManager] e [ModelFiles]: le classi di `download/https/` sono `internal` (Phase6ArchitectureTest).

/** Da dove arriva (o e' arrivato) un modello, in ordine di preferenza (D17). */
sealed interface ModelSource {
  /** La copia pubblicata sul NAS da un dispositivo della famiglia (D18): `<area comune>/.mycloudgallery/devices/<id>/models/<sha256>`. */
  data class NasCopy(val share: String, val device: String) : ModelSource

  /** Un indirizzo HTTPS fissato nell'app (Hugging Face, D17). */
  data class Https(val url: PinnedUrl) : ModelSource

  /** La cartella visibile `MyCloud Gallery/Modelli/` dell'area comune, dove l'utente copia i file dal PC (solo nome e dimensione del catalogo). */
  data class PcFolder(val share: String) : ModelSource

  /** Dentro l'APK (il ritaglio, Q11 (a)). */
  data class Bundled(val asset: String) : ModelSource

  /** Il tipo di sorgente come codice, per la pagina Ricerca e modelli e la Diagnostica (mai la share ne' l'indirizzo). */
  val kind: ModelSourceKind
    get() = when (this) {
      is NasCopy -> ModelSourceKind.NAS
      is Https -> ModelSourceKind.HTTPS
      is PcFolder -> ModelSourceKind.PC_FOLDER
      is Bundled -> ModelSourceKind.BUNDLED
    }
}

enum class ModelSourceKind { NAS, HTTPS, PC_FOLDER, BUNDLED }

/** Chi chiede un modello (§2.7): decide il cancello di rete (dati mobili solo con "Scarica ora" e la conferma, mai in roaming). */
enum class ModelTrigger {
  /** "Scarica ora" con l'app aperta. */
  USER_NOW,

  /** In background, secondo le regole (Wi-Fi, mai i dati mobili). */
  RULES,

  /** Il ruolo Indicizzatore e' appena stato acceso (D15). */
  ROLE_CHANGED,

  /**
   * "Scarica ora" dopo la **conferma** dell'utente sui dati mobili (con la dimensione): vale come [USER_NOW] e in piu' lascia passare i dati
   * mobili (mai il roaming). La conferma e' parte della richiesta, non uno stato condiviso: nessuna pagina deve ricordarsi di spegnerla (MO-2).
   */
  USER_CONFIRMED,
  ;

  /** Una richiesta dell'utente: scavalca le regole del lavoro in background e la quarantena delle sorgenti (E-A2), e toglie "Rimuovi" (MO-3). */
  val isUserRequest: Boolean get() = this == USER_NOW || this == USER_CONFIRMED
}

/** I motivi stabili di un problema (codici, mai messaggi; §2.6). */
enum class ModelFailCode {
  /** Nessuna sorgente ha dato il file (rete, NAS, cartella dal PC): `AI_MODEL_FETCH_FAILED`. */
  FETCH_FAILED,

  /** Un file con dimensione o SHA-256 diversi: scartato (E-A2, E-S16): `AI_MODEL_MISMATCH`. */
  MISMATCH,

  /** Spazio insufficiente (dimensione mancante + 300 MB, E-A3): `AI_MODEL_NO_SPACE`. */
  NO_SPACE,

  /** La verifica all'apertura e' fallita: file tolto e rimesso in coda (E-A11). */
  VERIFY_FAILED,

  /** Il cancello di rete non lo permette (restano solo i dati mobili senza conferma: il resto e' "in coda", [ModelWait]). */
  NETWORK_REFUSED,
}

/** Perche' un modello in coda aspetta la rete (MO-8): il motivo lo da' il cancello di rete, non e' un errore da riprovare. */
enum class ModelWait {
  /** Nessuna rete utilizzabile (nessuna connessione, non validata, portale). */
  NETWORK,

  /** In background servono i dati non a consumo (Wi-Fi): quelli mobili non si usano. */
  WIFI,

  /** Roaming: mai, nemmeno con "Scarica ora". */
  ROAMING,
}

/** Lo stato di un modello su questo dispositivo (§1.5, `ModelsState`). */
sealed interface ModelState {
  data object Absent : ModelState

  /** In coda; [waiting] dice, quando c'e', che aspetta la rete (MO-8) e non l'esito di un tentativo. */
  data class Queued(val reason: ModelTrigger, val waiting: ModelWait? = null) : ModelState

  /**
   * "Scarica ora" sui dati mobili: serve la conferma dell'utente con la dimensione ([bytes], quanto manca) (MO-2). La pagina chiede
   * "Scaricare N MB con i dati mobili?"; il "Si'" ripete la richiesta con [ModelTrigger.USER_CONFIRMED].
   */
  data class NeedsConfirmation(val bytes: Long) : ModelState

  data class Fetching(val source: ModelSourceKind, val done: Long, val total: Long) : ModelState

  data object Verifying : ModelState

  /** Verificato: [verifiedAtMonotonicNanos] e' l'orologio monotono dell'ultima verifica (D39: mai l'ora del telefono). */
  data class Ready(val sizeBytes: Long, val source: ModelSourceKind, val verifiedAtMonotonicNanos: Long) : ModelState

  data class Failed(val code: ModelFailCode) : ModelState
}

/** L'esito di [ModelManager.ensure]. */
sealed interface EnsureResult {
  data object Ready : EnsureResult

  /** Non adesso (rete, regole, tempo finito): resta in coda e riparte dal file parziale (E-A1). */
  data class Deferred(val state: ModelState) : EnsureResult

  data class Failed(val code: ModelFailCode, val neededBytes: Long? = null) : EnsureResult
}

/** Lo spazio occupato dai modelli su questo dispositivo (D20). */
data class ModelUsage(val bytesById: Map<ModelId, Long>, val partialBytes: Long) {
  val totalBytes: Long get() = bytesById.values.sum() + partialBytes
}

/**
 * Il gestore dei modelli (D16-D20): stato, scaricamento con ripresa, verifica, rimozione. [ensure] e' idempotente: un modello gia'
 * pronto risponde [EnsureResult.Ready] senza rete. [remove] toglie il file e dice se c'era; la pagina dice la conseguenza.
 */
interface ModelManager {
  val states: StateFlow<Map<ModelId, ModelState>>

  suspend fun ensure(id: ModelId, trigger: ModelTrigger, budget: Duration): EnsureResult

  suspend fun remove(id: ModelId): Boolean

  suspend fun usage(): ModelUsage
}

/**
 * Un modello verificato, pronto per un motore. **Si ottiene solo da [ModelFiles.open]**, che controlla dimensione e SHA-256 a OGNI
 * apertura (D16): il costruttore e' `internal` a core-models e lo chiama solo il verificatore (Phase6ArchitectureTest). [file] e' un
 * file dell'app in sola lettura (`noBackupFilesDir/models/<sha256>`); la finestra fra verifica e caricamento e' chiusa dal motore che
 * lo apre subito (P4 verifica se l'API di LiteRT-LM accetta un descrittore).
 */
class VerifiedModel internal constructor(
  val spec: ModelSpec,
  val file: File,
  /** L'orologio monotono della verifica appena fatta. */
  val verifiedAtMonotonicNanos: Long,
  private val onClose: () -> Unit = {},
  /**
   * P3 (D16): il descrittore del file aperto in sola lettura **sul quale** sono stati calcolati dimensione e SHA-256, riportato
   * all'inizio; vale finche' il modello non si chiude. Chi carica il motore lo duplica (`ParcelFileDescriptor.dup`) invece di riaprire
   * [file]: la finestra fra verifica e uso e' chiusa (un file sostituito dopo la verifica non e' piu' quello che il motore legge).
   * Null nei fake. Non lo si chiude: lo chiude [close].
   */
  val descriptor: FileDescriptor? = null,
  /** Lo stesso file come canale, per le letture per posizione dello scaffale (copia sul NAS dei byte verificati). */
  internal val channel: FileChannel? = null,
  /**
   * I1 (revisione di sicurezza della Fase 6): `stat` del file **preso durante la verifica** (dispositivo, inode, dimensione e `ctime`, che
   * cambia a ogni scrittura anche sullo stesso inode). `PATH_CHECKED` lo confronta con quello del percorso prima e dopo l'inizializzazione
   * del motore. Null dove non c'e' un lettore di `stat` (i test sulla JVM): allora il motore ripiega sul solo confronto del descrittore.
   */
  val stat: FileStat? = null,
) : Closeable {
  private val closed = AtomicBoolean(false)

  override fun close() {
    if (closed.compareAndSet(false, true)) onClose()
  }

  override fun toString(): String = "VerifiedModel(${spec.id})"
}

/**
 * Lo `stat` di un file (I1): dispositivo, inode, dimensione e `ctime` in secondi. Il `ctime` cambia a ogni scrittura, anche sullo stesso
 * inode, e a ogni cambio di permessi: confrontato prima e dopo l'apertura, scopre anche una modifica che non cambia ne' inode ne' dimensione.
 */
data class FileStat(val device: Long, val inode: Long, val size: Long, val ctimeSeconds: Long) {
  /** Lo stesso file di prima: stesso dispositivo, stesso inode, stessa dimensione e stessa `ctime` (una scrittura sullo stesso inode cambia la `ctime`). */
  fun isSameFileAs(other: FileStat): Boolean =
    device == other.device && inode == other.inode && size == other.size && ctimeSeconds == other.ctimeSeconds
}

/** Chi legge lo `stat` del descrittore su cui si verifica (la versione Android e' [dev.pampa.mycloudgallery.core.models.verify.AndroidFileStatReader]). */
fun interface FileStatReader {
  fun stat(descriptor: java.io.FileDescriptor): FileStat?

  companion object {
    /** Nessuno `stat` (JVM): `VerifiedModel.stat` resta null. */
    val None: FileStatReader = FileStatReader { null }
  }
}

/** L'unico modo di avere un modello da caricare (D16, D33). Null = assente, in verifica o rifiutato (in quel caso tolto e rimesso in coda). */
interface ModelFiles {
  suspend fun open(id: ModelId): VerifiedModel?
}

/** L'esito di un tentativo di [ModelFetcher]. */
sealed interface FetchResult {
  /** Il file parziale e' completo (dimensione esatta): resta da verificare l'impronta. */
  data object Done : FetchResult

  /**
   * La sorgente dice di avere un file di **un'altra dimensione** (la lunghezza dichiarata o la voce sul NAS) e non e' stato letto un solo
   * byte: la sorgente e' sbagliata (E-A2, E-S16), ma il parziale, scritto da un'altra sorgente, non c'entra e **resta** (R13).
   */
  data object SizeMismatch : FetchResult

  /** Interrotto dopo [bytes] byte: si riprende da li' (E-A1). */
  data class Partial(val bytes: Long) : FetchResult

  /** Byte **letti** diversi da quelli fissati (un flusso piu' lungo, un file piu' corto): parziale tolto, sorgente dopo (E-A2, E-S16). */
  data object Mismatch : FetchResult

  data object NotFound : FetchResult

  /** Rifiutato prima di scaricare: redirezione fuori dagli host ammessi, `http`, oltre la dimensione fissata (E-S16). */
  data class Refused(val reason: String) : FetchResult

  data object NoSpace : FetchResult

  data object NetworkError : FetchResult
}

/** Una sorgente di byte per un modello: scrive in [partial] (`models/partial/<sha256>.part`) al piu' `spec.sizeBytes` byte. */
fun interface ModelFetcher {
  suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult
}

/** Una copia di un modello sul NAS. */
data class ShelfCopy(val share: String, val device: String, val sha256: Sha256, val sizeBytes: Long)

/** L'esito della pubblicazione di una copia sul NAS (D18). */
sealed interface ShelfResult {
  data object Published : ShelfResult

  /** C'era gia' (scrittura esclusiva: mai una sostituzione). */
  data object AlreadyThere : ShelfResult

  /** La share non e' pubblicabile (F4-D31, sola lettura, emergenza): niente si scrive. */
  data class Blocked(val reason: String) : ShelfResult

  data object Failed : ShelfResult
}

/**
 * Lo scaffale dei modelli sul NAS (D18): **solo** nella cartella del proprio dispositivo (`devices/<id>/models/<sha256>`), scrittura
 * esclusiva, nessuna sostituzione, solo se la share e' pubblicabile. [pruneOwn] toglie solo le PROPRIE copie che l'app non elenca
 * piu' (mai quelle degli altri).
 */
interface ModelShelf {
  suspend fun publish(spec: ModelSpec, share: String): ShelfResult

  suspend fun copies(share: String, sha256: Sha256): List<ShelfCopy>

  suspend fun pruneOwn(share: String, keep: Set<Sha256>): Int
}

/** Il verdetto del cancello di rete per HTTPS (§2.7). */
sealed interface ModelNetworkVerdict {
  data object Allowed : ModelNetworkVerdict

  /** Sui dati mobili con "Scarica ora": serve la conferma dell'utente con la dimensione. */
  data class NeedsConfirmation(val bytes: Long) : ModelNetworkVerdict

  /** Non adesso: roaming (mai), dati mobili in background (mai), nessuna rete validata. */
  data class Refused(val reason: String) : ModelNetworkVerdict
}

/** I motivi stabili di un [ModelNetworkVerdict.Refused] (codici, mai messaggi): la pagina ne ricava "aspetto il Wi-Fi" o "in roaming non scarico". */
object ModelGateReason {
  const val OFFLINE: String = "offline"
  const val NOT_VALIDATED: String = "not_validated"
  const val ROAMING: String = "roaming"
  const val METERED: String = "metered"
}

/**
 * Il cancello di rete dello scaricamento (D17, R11): lo implementa :app (P11) sopra lo stato della rete e vale per **ogni** sorgente che usa
 * la rete, HTTPS, copie sul NAS e cartella dal PC ([source]): mai in roaming, in background mai i dati mobili, con "Scarica ora" i dati mobili
 * solo dopo la conferma. Per le sorgenti del NAS non conta se Android ha validato l'accesso a Internet (il NAS e' in LAN: il tablet senza
 * Internet scarica dal NAS, F48). Non e' il cancello del NAS (sessione, recinzione): quello sta dentro core-nas.
 */
fun interface ModelNetworkGate {
  suspend fun verdict(trigger: ModelTrigger, bytes: Long, source: ModelSourceKind): ModelNetworkVerdict
}

/** I codici della Diagnostica dei modelli (§2.6): il catalogo li raccoglie in feature-diagnostics (P11). */
object ModelDiag {
  val AI_MODEL_MISSING = DiagCode("AI_MODEL_MISSING", setOf("E-Q11"))
  val AI_MODEL_FETCH_FAILED = DiagCode("AI_MODEL_FETCH_FAILED", setOf("E-A1"))
  val AI_MODEL_MISMATCH = DiagCode("AI_MODEL_MISMATCH", setOf("E-A2", "E-S16"))
  val AI_MODEL_NO_SPACE = DiagCode("AI_MODEL_NO_SPACE", setOf("E-A3"))

  val ALL: List<DiagCode> = listOf(AI_MODEL_MISSING, AI_MODEL_FETCH_FAILED, AI_MODEL_MISMATCH, AI_MODEL_NO_SPACE)
}
