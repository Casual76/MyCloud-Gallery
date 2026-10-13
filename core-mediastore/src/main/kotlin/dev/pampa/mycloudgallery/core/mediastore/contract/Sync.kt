package dev.pampa.mycloudgallery.core.mediastore.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// L'accesso ai media e la sincronizzazione con il catalogo (docs/fase3-progetto.md §2.1). Contratti di P0; la logica
// e' di P2. La sincronizzazione del telefono NON passa dal motore delle regole (D10): in primo piano gira sempre, in
// background solo con un lavoro WorkManager a trigger di contenuto e una manutenzione quotidiana in carica; nessuna
// rete.

/** Quanto l'app puo' vedere dei media del telefono. */
enum class MediaAccess {
  /** `READ_MEDIA_IMAGES` e `READ_MEDIA_VIDEO` (o `READ_EXTERNAL_STORAGE` fino ad Android 12): tutta la libreria. */
  FULL,

  /** "Foto selezionate" (`READ_MEDIA_VISUAL_USER_SELECTED`): solo cio' che l'utente ha scelto adesso (E-P1). */
  PARTIAL,

  /** Nessun permesso. */
  NONE,
}

/**
 * Lo stato dell'accesso, **mai memorizzato**: si rilegge a ogni `onResume` e a ogni giro, perche' l'utente puo'
 * cambiarlo in qualunque momento dalle impostazioni di Android (E-P1, E-P15).
 *
 * @property mediaLocation `ACCESS_MEDIA_LOCATION`: senza, le foto nuove restano "in attesa" e non nasce mai
 *   un'impronta su byte oscurati (D9, E-P21).
 * @property volumes i volumi visibili adesso.
 */
data class PhoneAccess(
  val media: MediaAccess,
  val mediaLocation: Boolean,
  val volumes: Set<String>,
) {
  companion object {
    /** Nessun accesso e nessun volume: lo stato prima della prima lettura e dei null-object. */
    val NONE: PhoneAccess = PhoneAccess(MediaAccess.NONE, mediaLocation = false, volumes = emptySet())
  }
}

/** Legge l'accesso ai media. [refresh] si chiama a ogni `onResume`, dopo ogni richiesta di permesso e a ogni giro. */
interface PhoneAccessReader {
  val state: StateFlow<PhoneAccess>

  fun refresh()
}

/** Perche' la sincronizzazione e' partita (per la Diagnostica e per la priorita'). */
enum class SyncReason {
  /** Avvio del processo. */
  APP_START,

  /** Ritorno in primo piano. */
  FOREGROUND,

  /** Il MediaStore e' cambiato (osservatore o trigger di contenuto). */
  CONTENT_CHANGE,

  /** I permessi sono cambiati. */
  PERMISSION_CHANGED,

  /** Un lavoro WorkManager. */
  WORKER,

  /** La manutenzione quotidiana in carica. */
  MAINTENANCE,

  /** Un tocco dell'utente ("Aggiorna"). */
  MANUAL,
}

/** Perche' la sincronizzazione e' ferma. Mai interpretato come una cancellazione (E-P15). */
enum class PauseReason {
  /** Permessi ritirati o mai dati (E-P15). */
  NO_PERMISSION,

  /** `ACCESS_MEDIA_LOCATION` negato: le foto nuove restano in attesa, i video e le foto note vanno avanti (E-P21). */
  MEDIA_LOCATION_DENIED,

  /** Telefono non ancora sbloccato dopo il riavvio (E-P2, E-P10, E-P13): `Blocked(STORAGE_LOCKED)` della Fase 2. */
  STORAGE_LOCKED,

  /** Disco pieno (E-P3): nessun crash, l'istantanea non si riscrive, la cache si riduce. */
  DISK_FULL,
}

/** Cosa sta facendo la sincronizzazione. */
sealed interface SyncStatus {

  /** Ferma e aggiornata all'istante [atMs] (orologio di riferimento). */
  data class Idle(val atMs: Long) : SyncStatus

  /**
   * In lettura. [total] e' null finche' non si sa; [full] dice se e' una scansione completa (prima lettura o
   * versione del MediaStore cambiata): la Libreria mostra l'avanzamento in fondo solo in quel caso.
   */
  data class Scanning(val done: Int, val total: Int?, val full: Boolean) : SyncStatus

  /** Ferma per un motivo che l'utente puo' risolvere, o che passa da solo. */
  data class Paused(val reason: PauseReason) : SyncStatus
}

/**
 * Il bilancio di un giro, solo numeri (finisce nella Diagnostica).
 *
 * @property pendingMediaLocation le foto nuove in attesa del permesso della posizione (E-P21): la Libreria le
 *   conta e le spiega in un banner.
 * @property ignored zero byte, falsi media e byte iniziali non da media (E-M12): contati, mai in Libreria.
 * @property completed falso se il giro e' stato interrotto (budget, permessi, disco): ripartira' da dove era.
 */
data class SyncReport(
  val seen: Int = 0,
  val added: Int = 0,
  val updated: Int = 0,
  val vanished: Int = 0,
  val ignored: Int = 0,
  val pendingMediaLocation: Int = 0,
  val fullScan: Boolean = false,
  val completed: Boolean = true,
)

/**
 * La sincronizzazione del telefono con il catalogo. Un solo oggetto per processo.
 *
 * [attach] la lega al primo piano (`ProcessWatcher` di :app): osserva `changes()` e `PhoneAccessReader` finche'
 * il processo e' in primo piano; [detach] la ferma. [syncNow] fa un giro e restituisce il bilancio: e' idempotente
 * (la generazione si legge prima della query e si salva dopo il commit) e riprendibile (E-P4, E-P8).
 */
interface PhoneLibrarySync {
  val status: StateFlow<SyncStatus>

  suspend fun syncNow(reason: SyncReason): SyncReport

  fun attach(scope: CoroutineScope)

  fun detach()
}

/** L'esito di un lavoro in background con un budget di tempo. */
sealed interface RunResult {
  /** Tutto fatto. */
  data object Done : RunResult

  /** Il budget e' finito prima: il lavoro si riaccoda e riprende da dove era. [remaining] e' una stima, se nota. */
  data class BudgetExhausted(val remaining: Int?) : RunResult

  /** Fermo per un motivo noto ([PauseReason]). */
  data class Paused(val reason: PauseReason) : RunResult

  /** Fallito con un codice stabile (mai un messaggio con dati). */
  data class Failed(val code: String) : RunResult
}

/**
 * Il corpo del lavoro `PhoneSyncWorker` di :app: un giro di sincronizzazione entro [budget] (tappe di 8 minuti al
 * massimo, batteria non scarica). Si riaccoda da solo dopo ogni giro (trigger di contenuto, ritardo 5 s, massimo 60 s).
 */
interface PhoneSyncRunner {
  suspend fun run(budget: Duration): RunResult
}

/**
 * La fase META (§2.2) per i media gia' nel catalogo: metadati, luogo, tipo definitivo, ThumbHash e gruppi. Prima i
 * visibili ([MetaPriority]), poi i piu' recenti. Il corpo di `LibraryMaintenanceWorker` (in carica) e della
 * priorita' in primo piano.
 */
interface MetadataPass {
  suspend fun run(budget: Duration, priority: MetaPriority): RunResult
}

/**
 * Cio' che e' sullo schermo adesso: `:app` lo lega alla finestra visibile della griglia, cosi' i media che l'utente
 * sta guardando hanno i metadati per primi (D11).
 */
interface MetaPriority {
  val visible: StateFlow<List<MediaId>>
}
