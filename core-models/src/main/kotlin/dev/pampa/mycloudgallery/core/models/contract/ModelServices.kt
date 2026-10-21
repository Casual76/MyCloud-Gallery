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

  /** Il cancello di rete non lo permette adesso (roaming, dati mobili senza conferma, nessuna rete validata). */
  NETWORK_REFUSED,
}

/** Lo stato di un modello su questo dispositivo (§1.5, `ModelsState`). */
sealed interface ModelState {
  data object Absent : ModelState

  data class Queued(val reason: ModelTrigger) : ModelState

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
) : Closeable {
  private val closed = AtomicBoolean(false)

  override fun close() {
    if (closed.compareAndSet(false, true)) onClose()
  }

  override fun toString(): String = "VerifiedModel(${spec.id})"
}

/** L'unico modo di avere un modello da caricare (D16, D33). Null = assente, in verifica o rifiutato (in quel caso tolto e rimesso in coda). */
interface ModelFiles {
  suspend fun open(id: ModelId): VerifiedModel?
}

/** L'esito di un tentativo di [ModelFetcher]. */
sealed interface FetchResult {
  /** Il file parziale e' completo (dimensione esatta): resta da verificare l'impronta. */
  data object Done : FetchResult

  /** Interrotto dopo [bytes] byte: si riprende da li' (E-A1). */
  data class Partial(val bytes: Long) : FetchResult

  /** Byte diversi da quelli fissati (lunghezza o impronta): parziale tolto, sorgente dopo (E-A2, E-S16). */
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

/** Il cancello di rete dello scaricamento HTTPS (D17): lo implementa :app (P11) sopra lo stato della rete. Non e' il cancello del NAS. */
fun interface ModelNetworkGate {
  suspend fun verdict(trigger: ModelTrigger, bytes: Long): ModelNetworkVerdict
}

/** I codici della Diagnostica dei modelli (§2.6): il catalogo li raccoglie in feature-diagnostics (P11). */
object ModelDiag {
  val AI_MODEL_MISSING = DiagCode("AI_MODEL_MISSING", setOf("E-Q11"))
  val AI_MODEL_FETCH_FAILED = DiagCode("AI_MODEL_FETCH_FAILED", setOf("E-A1"))
  val AI_MODEL_MISMATCH = DiagCode("AI_MODEL_MISMATCH", setOf("E-A2", "E-S16"))
  val AI_MODEL_NO_SPACE = DiagCode("AI_MODEL_NO_SPACE", setOf("E-A3"))

  val ALL: List<DiagCode> = listOf(AI_MODEL_MISSING, AI_MODEL_FETCH_FAILED, AI_MODEL_MISMATCH, AI_MODEL_NO_SPACE)
}
