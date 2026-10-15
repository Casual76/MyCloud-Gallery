package dev.pampa.mycloudgallery.core.nasops.contract

import dev.pampa.mycloudgallery.core.model.StableCode
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// Fase 4B (docs/fase4-progetto.md §1.2 "Operazioni", §1.5 "NasOperationState", §2.7): il contratto delle operazioni sui file del NAS e
// gli stati che la UI mostra ("Operazioni in corso", la notifica UIDT). Contratto, null-object e fake di P0-B; la logica e' di P12.

/**
 * Le operazioni sui file del NAS: crea cartella, rinomina, sposta, elimina (cestino del NAS), ripristina, "elimina subito".
 *
 *  1. [plan] calcola cosa si farebbe, senza modificare niente (puo' interrogare il NAS: spazio, esistenza).
 *  2. L'utente vede il riepilogo e lo conferma: la UI costruisce la [UserConfirmation] dopo il suo tocco.
 *  3. [start] verifica la conferma contro il piano, scrive l'operazione nel diario e la esegue a passi idempotenti.
 *  4. Dopo un'interruzione (processo ucciso, rete persa) [resumeAll] riprende **dal diario locale**, senza nuova conferma: era
 *     gia' stata data. Niente altro avvia o riprende un'operazione.
 *
 * Nessun errore come eccezione: gli esiti sono tipi sigillati. Mai nei log: percorsi, nomi, share, account (§4.3).
 */
interface NasOperations {

  suspend fun plan(request: OpRequest): PlanOutcome

  /**
   * Avvia [plan] con la conferma dell'utente. Con una conferma che non vale per questo piano ([ConfirmationCheck]) non fa niente.
   * Idempotente sull'impronta del piano: ripetere `start` con lo stesso piano restituisce l'operazione gia' avviata, non una seconda.
   */
  suspend fun start(plan: OpPlan, confirmation: UserConfirmation): StartOutcome

  /** Tutte le operazioni del diario non ancora chiuse e le ultime chiuse, nell'ordine di creazione. */
  val operations: StateFlow<List<NasOperationState>>

  /** Ferma l'operazione prima dell'elemento successivo: toglie i propri temporanei e lascia fatti quelli fatti ([OpStateMachine.cancellable]). */
  suspend fun cancel(id: OpId)

  /**
   * Riprende dal diario locale tutto cio' che e' rimasto a meta', per al massimo [budget]. Chiamata all'avvio dell'app, dal
   * JobService delle operazioni lunghe (UIDT) e dopo un'interruzione. Rispetta i blocchi ([OpsGate]).
   */
  suspend fun resumeAll(budget: Duration): RunResult
}

/** L'esito di [NasOperations.plan]. */
sealed interface PlanOutcome {
  data class Ready(val plan: OpPlan) : PlanOutcome

  data class Rejected(val reason: PlanRejection) : PlanOutcome
}

/** L'esito di [NasOperations.start]. */
sealed interface StartOutcome {
  data class Started(val id: OpId) : StartOutcome

  /** Lo stesso piano era gia' stato avviato: eccola. */
  data class AlreadyStarted(val id: OpId) : StartOutcome

  /** La conferma non vale (altro piano, scaduta, tocchi mancanti): nessuna operazione nata. */
  data class ConfirmationRejected(val check: ConfirmationCheck) : StartOutcome

  /** Il piano non passa i controlli ([PlanRules]) anche con una conferma valida: nessuna operazione nata. */
  data class PlanInvalid(val violation: PlanViolation) : StartOutcome

  /**
   * Le scritture sono bloccate adesso (congelamento D10, emergenza) o l'ora del NAS non e' ancora ancorata: il piano si puo'
   * riprovare piu' tardi.
   */
  data object WritesBlocked : StartOutcome
}

/** Perche' un'operazione e' in pausa. */
enum class PauseReason(override val code: String) : StableCode {
  NAS_UNREACHABLE("nas_unreachable"),

  /** Le regole delle attivita' non lasciano lavorare adesso (dati mobili, batteria): riprende da sola. */
  CONDITIONS("conditions"),

  /** Share congelata (D10). */
  FROZEN("frozen"),

  /** L'ora del NAS e' saltata oltre 5 minuti (D32). */
  CLOCK_JUMP("clock_jump"),

  /** "Ferma le scritture sul NAS" (emergenza). */
  WRITES_STOPPED("writes_stopped"),

  /** L'utente ha detto "Ferma" dalla notifica o dalla schermata. */
  USER("user"),
}

/**
 * Cio' che "Operazioni in corso" e la notifica di avanzamento mostrano per ogni operazione (§1.5). Solo numeri e codici: l'aspetto e'
 * del filone grafico. [done] e [total] contano gli elementi.
 */
sealed interface NasOperationState {
  val id: OpId
  val kind: OpKind

  /** Confermata e nel diario, non ancora partita. */
  data class Planned(override val id: OpId, override val kind: OpKind, val summary: PlanSummary) : NasOperationState

  data class Running(
    override val id: OpId,
    override val kind: OpKind,
    val done: Int,
    val total: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
  ) : NasOperationState

  data class Paused(override val id: OpId, override val kind: OpKind, val reason: PauseReason, val done: Int, val total: Int) : NasOperationState

  /** [lockedCount] elementi aperti dal PC: si riprova con attese crescenti fino a 24 ore (E-N13). */
  data class WaitingLocked(override val id: OpId, override val kind: OpKind, val lockedCount: Int, val done: Int, val total: Int) :
    NasOperationState

  data class Done(override val id: OpId, override val kind: OpKind, val summary: DoneSummary) : NasOperationState

  data class Failed(override val id: OpId, override val kind: OpKind, val code: OpErrorCode, val done: Int, val total: Int) : NasOperationState

  data class Cancelled(override val id: OpId, override val kind: OpKind, val done: Int, val total: Int) : NasOperationState

  /** Un'operazione in corso o in attesa, non ancora chiusa. */
  val active: Boolean
    get() = this is Planned || this is Running || this is Paused || this is WaitingLocked
}

/** Il riepilogo di un'operazione finita. [failed] sono gli elementi che si sono fermati (sorgente cambiata, nome non valido...). */
data class DoneSummary(val done: Int, val failed: Int, val skipped: Int, val bytes: Long)

/** Perche' un giro di [NasOperations.resumeAll] si e' fermato. */
enum class RunStop(override val code: String) : StableCode {
  /** Non c'era piu' niente da riprendere o aspettare. */
  COMPLETE("complete"),
  BUDGET("budget"),
  NAS_UNREACHABLE("nas_unreachable"),
  BLOCKED("blocked"),

  /** Restano solo elementi in attesa di un file bloccato (E-N13): si riprova al loro turno. */
  WAITING_LOCKED("waiting_locked"),
}

/** Il resoconto di un giro di ripresa. */
data class RunResult(val resumedOps: Int, val itemsDone: Int, val itemsFailed: Int, val itemsWaiting: Int, val stoppedBy: RunStop)

/**
 * Gli orologi delle operazioni (D32, §4.3): l'**ora del NAS** per cio' che si scrive o scade (voci del cestino, attese dei file
 * bloccati, 30 giorni), l'**orologio monotono** per le durate e per l'eta' della conferma. Mai l'orologio del telefono.
 */
interface OpClocks {
  fun monotonicMs(): Long

  /** L'ora del NAS adesso, o null se nessuna sessione l'ha ancora ancorata (le operazioni che ne hanno bisogno aspettano). */
  fun nasNowMs(): Long?

  /**
   * R1 (revisione 4B, D32): l'ora del NAS in cui l'ultimo salto dell'ora e' finito (24 ore di ora stabile o il gesto dell'utente), o null
   * se non se ne ricorda nessuno. Le scadenze che contano su date scritte prima (i 30 giorni del cestino) non partono mai da prima di
   * questo istante: dopo un salto accettato il cestino riparte con 30 giorni pieni (al peggio tiene di piu', mai di meno).
   */
  fun clockTrustedSinceNasMs(): Long? = null
}

/** Perche' adesso non si scrive sul NAS. */
enum class GateStatus(override val code: String) : StableCode {
  OPEN("open"),

  /** Share congelata per cambiamenti di massa (D10): ferma tutto, anche le operazioni dell'utente in coda. */
  FROZEN("frozen"),

  /** Salto dell'ora del NAS oltre 5 minuti (D32): ferma le funzioni a scadenza (pulizie), non le richieste dell'utente. */
  CLOCK_JUMP("clock_jump"),

  /** "Ferma le scritture sul NAS" in Diagnostica (D31): ferma tutto. */
  WRITES_STOPPED("writes_stopped"),
  ;

  /** Le operazioni avviate dall'utente non partono. */
  val blocksUserOps: Boolean get() = this == FROZEN || this == WRITES_STOPPED

  /** Le funzioni automatiche (pulizia del cestino, dei propri file, compattazione) non partono. */
  val blocksScheduled: Boolean get() = this != OPEN
}

/** Chi dice se si puo' scrivere sulla share: lo compone `:app` da congelamento, salto dell'ora ed emergenza. */
fun interface OpsGate {
  fun statusOf(share: String): GateStatus
}
