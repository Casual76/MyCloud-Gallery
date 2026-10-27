package dev.pampa.mycloudgallery.core.nasops.engine

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptedPlan
import dev.pampa.mycloudgallery.core.nasops.contract.CatalogHoldBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationGate
import dev.pampa.mycloudgallery.core.nasops.contract.DoneSummary
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.LockedBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpRules
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpStateMachine
import dev.pampa.mycloudgallery.core.nasops.contract.OpTemps
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFolderBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRules
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.ProtectedFiles
import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.nasops.noop.NoMoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.trash.OwnTrashPurger
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * L'esecutore delle operazioni sui file del NAS (P12; docs/fase4-progetto.md §2.7 per intero, D24, D25, Q3, Q8): **l'unico codice che
 * sposta o cancella file dell'archivio**, che non ha un backup esterno. Le regole, tutte provate dai test (guasto a ogni chiamata
 * prima e dopo, morte del processo a ogni passo):
 *
 *  - **si parte solo da un [UserConfirmation]** valido per **quel** piano ([ConfirmationGate]); [PlanRules] lo ricontrolla anche con
 *    una conferma valida; il diario nasce solo da [OpsStore.create] con l'[dev.pampa.mycloudgallery.core.nasops.contract.AcceptedPlan];
 *  - **ogni passo si scrive nel diario prima di farlo** (gli stati in "-ING" e ogni valore del passo: temporaneo, nome alternativo,
 *    impronta verificata, voce del cestino), poi si chiama il NAS, poi si scrive l'esito;
 *  - **la ripresa** ([resumeAll]) riparte **solo dal diario** e, per ogni passo che puo' essere stato applicato con la risposta persa,
 *    guarda il NAS prima di rifarlo (sorgente assente + destinazione con la stessa identita' = fatto);
 *  - **niente sovrascrive**: nomi occupati = nome alternativo scritto nel diario prima di riprovare;
 *  - **niente si perde**: un media esce dal suo posto solo per una rinomina atomica (stessa share) o per il cestino **dopo** che la
 *    copia e' verificata byte per byte e pubblicata (tra share, Q3); un file cambiato (E-D13) o di qualcun altro non va mai nel
 *    cestino; l'eliminazione definitiva tocca solo il proprio cestino, dal diario locale, con un handle;
 *  - **la sorgente va nel cestino solo dopo che il catalogo conosce la destinazione** (Fase 7, D29): la porta di completamento
 *    ([MoveCompletionSink]) si chiama dopo `PUBLISHED` e prima di `SOURCE_RECORDED`; finche' non risponde l'elemento resta li';
 *  - **nessun percorso letto dal NAS diventa un'azione** senza la conferma dell'utente: i soli percorsi usati sono quelli del piano
 *    confermato e quelli derivati dall'app (temporanei, cestino proprio, nomi alternativi);
 *  - mai nei log: percorsi, nomi, share, account (solo codici).
 *
 * Esecuzione: [start] e [resumeAll] eseguono gli elementi in ordine, uno alla volta, sotto il lucchetto [NasOpsLock] condiviso con le
 * pulizie. **Un'operazione nuova entra nel diario subito** (NA-1): [start] la registra senza aspettare il lucchetto, cosi' mentre ne gira
 * una lunga quella nuova si vede "in coda" (`NasOperationState.Planned`) e parte appena il lucchetto e' libero, in ordine di arrivo;
 * se muore il processo in coda, la riprende `resumeAll` dal diario. Un'operazione si ferma (in pausa) se il NAS non risponde, se le scritture sono bloccate ([OpsGate]) o alla fine del budget,
 * e aspetta i file aperti dal PC con attese crescenti fino a 24 ore (E-N13).
 *
 * Una cartella che il telefono stesso tiene aperta (la Libreria che legge una miniatura sotto di lei) non e' un permesso negato e non e' il PC:
 * Samba non la rinomina finche' un nostro handle e' aperto sotto. Le primitive rilasciano i nostri handle e, se serve, rispondono
 * [RenameOutcome.FolderInUse]: qui si riprova con attese brevi ([OwnFolderBackoff], circa un minuto), poi diventa un'attesa normale come un
 * file aperto. Un `ACCESS_DENIED` senza un nostro handle sotto resta l'errore di sempre.
 */
class NasOpsEngine internal constructor(
  private val deviceId: String,
  private val accountId: String,
  private val fileOps: NasFileOps,
  private val reader: NasMediaReader,
  private val store: OpsStore,
  private val clocks: OpClocks,
  private val gate: OpsGate,
  private val access: ShareAccessView = ShareAccessView.ALL_WRITABLE,
  private val records: TrashRecordWriter = TrashRecordWriter.NONE,
  private val hashes: KnownHashes = KnownHashes.NONE,
  private val heavy: HeavyWorkPolicy = HeavyWorkPolicy.ALWAYS,
  private val lock: NasOpsLock = NasOpsLock(),
  private val newId: () -> String = { TrustIds.random() },
  /** R3: cio' che il catalogo vede ancora presente (inventario delle cartelle cestinate, "Elimina subito"). Serve solo a non eliminare. */
  private val presence: CatalogPresence = CatalogPresence.NONE,
  /** L'attesa breve di [OwnFolderBackoff] (millisecondi): di norma un `delay`; i test la fanno passare sull'orologio finto. */
  private val waitShortly: suspend (Long) -> Unit = { delay(it) },
  /**
   * Fase 7 (D29, P4): la porta di completamento, l'unica (anche `MoveCompletions` del campo A passa da qui, [completionPortOf]): il catalogo
   * collega la posizione nuova di uno spostamento tra share **prima** che la sorgente vada nel cestino. Vincolante: vedi [completePublished].
   */
  private val completions: MoveCompletionSink = NoMoveCompletionSink,
  /**
   * Fase 7, revisione B (R3): i file degli album condivisi che la tabella dei permessi non lascia togliere dal loro posto. Il piano li lascia fuori
   * con il motivo: e' il punto comune a viewer, Cartelle NAS, "Ovunque", versioni e Doppioni. Di serie nessuno.
   */
  private val protectedFiles: ProtectedFiles = ProtectedFiles.NONE,
) : NasOperations {

  init {
    require(TrustIds.isValid(deviceId)) { "Id di dispositivo non valido." }
    require(fileOps.ownerDeviceId == deviceId) { "Le primitive devono essere di questo dispositivo." }
  }

  private val planner = OpPlanner(deviceId, accountId, fileOps, reader, store, gate, access, newId, protectedFiles)
  private val purger = OwnTrashPurger(deviceId, fileOps, reader, store, presence)
  private val inventory = FolderInventory(reader, presence)

  // =========================================================================================================================
  // Stato mostrato ("Operazioni in corso", notifica UIDT): contatori per operazione, aggiornati a ogni scrittura del diario
  // =========================================================================================================================

  private class Tally(var total: Int = 0, var folders: Int = 0, var done: Int = 0, var failed: Int = 0, var bytesDone: Long = 0, var locked: Int = 0)

  private class View(var record: OpRecord, val tally: Tally, var pause: PauseReason? = null)

  private val viewsLock = Any()
  private val views = LinkedHashMap<OpId, View>()
  private val states = MutableStateFlow<List<NasOperationState>>(emptyList())
  override val operations: StateFlow<List<NasOperationState>> = states

  @Volatile private var loaded = false
  private val cancelRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<OpId>()

  /** Per ogni piano avviato in questo processo, l'istante (monotono) della conferma che l'ha avviato: un doppio tocco non riparte. */
  private val confirmed = HashMap<dev.pampa.mycloudgallery.core.nasops.contract.PlanHash, Long>()

  /**
   * La registrazione di un'operazione nuova nel diario (NA-1). Breve, **mai tenuta durante l'esecuzione**: cosi' un'operazione che
   * arriva mentre ne gira una lunga entra subito nel diario e si vede "in coda" (`Planned`) invece di aspettare, invisibile, per ore.
   * Serializza anche [loadViews], che rilegge il diario e sostituisce le viste: senza, una ripresa potrebbe cancellare la vista di
   * un'operazione appena registrata. Ordine dei lucchetti: prima `lock.mutex` (esecuzione), poi questo; la registrazione non prende
   * mai `lock.mutex` mentre lo tiene, quindi non c'e' attesa circolare.
   */
  private val registration = Mutex()

  /** Ricostruisce lo stato mostrato dal diario (all'avvio, dopo la morte del processo). */
  private suspend fun loadViews() = registration.withLock { reloadViews() }

  private suspend fun reloadViews() {
    val all = store.ops(OpState.entries.toSet())
    val (open, closed) = all.partition { !it.state.terminal }
    val shown = open + closed.takeLast(OpRules.CLOSED_OPS_SHOWN)
    val fresh = LinkedHashMap<OpId, View>()
    for (op in shown) {
      val tally = Tally()
      store.items(op.id).forEach { add(tally, it, +1) }
      // Il motivo della pausa: quello che sa questo processo, altrimenti quello scritto nel diario (dopo la morte del processo).
      fresh[op.id] = View(op, tally, synchronized(viewsLock) { views[op.id]?.pause } ?: op.pauseReason)
    }
    synchronized(viewsLock) {
      views.clear()
      views.putAll(fresh)
    }
    loaded = true
    publish()
  }

  private fun add(tally: Tally, item: OpItemRecord, sign: Int) {
    tally.total += sign
    if (item.isFolder) tally.folders += sign
    if (item.state == OpItemState.DONE) {
      tally.done += sign
      tally.bytesDone += sign * (item.size ?: 0)
    }
    if (item.state == OpItemState.FAILED) tally.failed += sign
    if (item.waitingLocked) tally.locked += sign
  }

  private fun track(old: OpItemRecord, new: OpItemRecord) {
    synchronized(viewsLock) {
      val view = views[new.opId] ?: return
      add(view.tally, old, -1)
      add(view.tally, new, +1)
    }
    publish()
  }

  private suspend fun setOpState(id: OpId, state: OpState, code: OpErrorCode?, pause: PauseReason? = null) {
    val now = clocks.nasNowMs()
    val record = store.op(id) ?: return
    // R9: un'operazione che l'utente ha fermato resta fermata (in pausa con il suo segno) finche' non si chiude: nessuna scrittura
    // dell'esecuzione lo toglie, cosi' una ripresa dopo la morte del processo annulla invece di continuare.
    val stopped = !state.terminal && (id in cancelRequests || record.errorCode == OpErrorCode.STOPPED_BY_USER)
    if (stopped) {
      store.setOpState(id, OpState.PAUSED, OpErrorCode.STOPPED_BY_USER, now ?: record.updatedAtNas)
    } else {
      // Prova F16: il motivo della pausa va nel diario, non solo nella memoria di questo processo.
      store.setOpState(id, state, code, now ?: record.updatedAtNas, pause)
    }
    val updated = store.op(id) ?: return
    synchronized(viewsLock) {
      val view = views[id]
      if (view == null) {
        views[id] = View(updated, Tally())
      } else {
        view.record = updated
      }
      // Tiene le ultime chiuse, come il contratto (OpRules.CLOSED_OPS_SHOWN).
      val closed = views.values.filter { it.record.state.terminal }
      if (closed.size > OpRules.CLOSED_OPS_SHOWN) closed.take(closed.size - OpRules.CLOSED_OPS_SHOWN).forEach { views.remove(it.record.id) }
    }
    publish()
  }

  private fun publish() {
    states.value = synchronized(viewsLock) { views.values.map(::stateOf) }
  }

  private fun stateOf(view: View): NasOperationState {
    val op = view.record
    val t = view.tally
    return when (op.state) {
      OpState.PLANNED -> NasOperationState.Planned(op.id, op.kind, PlanSummary(op.kind, t.total, t.folders, op.totalBytes, null, null))
      OpState.RUNNING -> NasOperationState.Running(op.id, op.kind, t.done, t.total, t.bytesDone, op.totalBytes)
      // R9: un'operazione fermata dall'utente lo resta anche dopo un riavvio (il segno e' nel diario); se questo processo sa perche'
      // l'annullamento aspetta (il NAS non risponde), lo dice.
      OpState.PAUSED -> if (op.errorCode == OpErrorCode.STOPPED_BY_USER && view.pause == null) {
        NasOperationState.Paused(op.id, op.kind, PauseReason.USER, t.done, t.total)
      } else if (t.locked > 0 && view.pause == null) {
        NasOperationState.WaitingLocked(op.id, op.kind, t.locked, t.done, t.total)
      } else {
        NasOperationState.Paused(op.id, op.kind, view.pause ?: PauseReason.CONDITIONS, t.done, t.total)
      }
      OpState.DONE -> NasOperationState.Done(op.id, op.kind, DoneSummary(t.done, t.failed, skipped = 0, bytes = t.bytesDone))
      OpState.FAILED -> NasOperationState.Failed(op.id, op.kind, op.errorCode ?: OpErrorCode.UNEXPECTED, t.done, t.total)
      OpState.CANCELLED -> NasOperationState.Cancelled(op.id, op.kind, t.done, t.total)
    }
  }

  // =========================================================================================================================
  // Contratto
  // =========================================================================================================================

  /** Pianifica: nessuna scrittura, ne' sul NAS ne' nel diario. */
  override suspend fun plan(request: OpRequest): PlanOutcome = planner.plan(request)

  override suspend fun start(plan: OpPlan, confirmation: UserConfirmation): StartOutcome {
    // 1. La conferma dell'utente vale per QUESTO piano, ed e' fresca.
    val accepted = when (val outcome = ConfirmationGate.accept(plan, confirmation, clocks.monotonicMs())) {
      is AcceptOutcome.Rejected -> return StartOutcome.ConfirmationRejected(outcome.check)
      is AcceptOutcome.Accepted -> outcome.accepted
    }
    // 2. Il piano e' in ordine anche se la UI avesse sbagliato (area dell'app, cestino altrui, cammini).
    PlanRules.firstViolation(plan, deviceId)?.let { return StartOutcome.PlanInvalid(it) }
    // 3. Il diario (NA-1): l'operazione e tutti i suoi elementi in una transazione, solo dal piano accettato, **subito**, anche se ne
    // gira un'altra: da qui l'utente la vede "in coda". Il lucchetto dell'esecuzione si prende dopo, solo per eseguire.
    val record = when (val registered = register(plan, confirmation, accepted)) {
      is Registered.Early -> return registered.outcome
      is Registered.Ok -> registered.record
    }
    lock.mutex.withLock {
      // Dall'attesa puo' essere cambiato tutto (R9): l'utente puo' averla fermata, la ripresa puo' averla chiusa. Cio' che e' chiuso o
      // fermato dall'utente non si esegue: lo chiude chi l'ha fermato (`cancel`) o la ripresa (`resumeAll`), come sempre.
      val current = store.op(record.id)
      if (current == null || current.state.terminal || current.errorCode == OpErrorCode.STOPPED_BY_USER || record.id in cancelRequests) {
        return@withLock
      }
      runOp(record.id, Long.MAX_VALUE, Purpose.INTERACTIVE)
    }
    return StartOutcome.Started(record.id)
  }

  private sealed interface Registered {
    class Ok(val record: OpRecord) : Registered

    class Early(val outcome: StartOutcome) : Registered
  }

  private suspend fun register(plan: OpPlan, confirmation: UserConfirmation, accepted: AcceptedPlan): Registered =
    registration.withLock {
      if (!loaded) reloadViews()
      // Idempotente sull'impronta del piano: la stessa operazione non chiusa, o la stessa conferma toccata due volte, non ne fa partire
      // una seconda. Un piano uguale a uno gia' CHIUSO con una conferma nuova (rinomina a→b, b→a, di nuovo a→b) e' una richiesta nuova
      // (P15, O1).
      store.opByPlanHash(plan.hash)?.let { existing ->
        val sameConfirmation = synchronized(confirmed) { confirmed[plan.hash] } == confirmation.issuedAtMonotonicMs
        if (!existing.state.terminal || sameConfirmation) return@withLock Registered.Early(StartOutcome.AlreadyStarted(existing.id))
      }
      if (plan.items.any { blockedForUser(it.srcShare) || it.dstShare?.let(::blockedForUser) == true }) {
        return@withLock Registered.Early(StartOutcome.WritesBlocked)
      }
      val nasNow = clocks.nasNowMs() ?: return@withLock Registered.Early(StartOutcome.WritesBlocked)
      val record = store.create(accepted, OpId(newId()), accountId, nasNow)
      synchronized(confirmed) { confirmed[plan.hash] = confirmation.issuedAtMonotonicMs }
      val tally = Tally()
      store.items(record.id).forEach { add(tally, it, +1) }
      synchronized(viewsLock) { views[record.id] = View(record, tally) }
      publish()
      GalleryLog.i(TAG) { "operazione avviata: ${plan.kind.code}, ${plan.items.size} elementi" }
      Registered.Ok(record)
    }

  override suspend fun cancel(id: OpId) {
    // Ferma l'esecuzione prima dell'elemento successivo (il ciclo lo guarda), poi annulla cio' che si puo' annullare.
    cancelRequests += id
    try {
      // R9: la richiesta dell'utente si scrive SUBITO nel diario, senza aspettare il lucchetto (l'operazione in corso lo tiene fino al
      // prossimo elemento): se il processo muore adesso, la ripresa annulla invece di continuare.
      store.op(id)?.takeIf { !it.state.terminal }?.let { op ->
        store.setOpState(id, OpState.PAUSED, OpErrorCode.STOPPED_BY_USER, clocks.nasNowMs() ?: op.updatedAtNas)
      }
      lock.mutex.withLock { cancelLocked(id, Purpose.INTERACTIVE) }
    } finally {
      cancelRequests -= id
    }
  }

  /** L'annullamento sotto il lucchetto: dal "Ferma" dell'utente, o dalla ripresa di un'operazione che l'utente aveva fermato (R9). */
  private suspend fun cancelLocked(id: OpId, purpose: Purpose) {
    if (!loaded) loadViews()
    val op = store.op(id) ?: return
    if (op.state.terminal) return
    for (item in store.items(id)) {
      if (!OpStateMachine.cancellable(item.flow, item.state)) continue
      when (tempGone(item, purpose)) {
        TempCheck.GONE -> persist(item, item.copy(state = OpItemState.CANCELLED, errorCode = null, nextAttemptAtNas = null), allowNoClock = true)
        // Forse gia' pubblicata (risposta persa di publishTemp): si porta a termine, mai due copie visibili.
        TempCheck.MAYBE_PUBLISHED -> Unit
        // Il temporaneo non si e' potuto togliere: "Annulla" non lascia temporanei che il diario non cita. Si riprova piu' tardi.
        TempCheck.UNREACHABLE -> {
          pause(id, PauseReason.NAS_UNREACHABLE)
          return
        }
      }
    }
    // Gli elementi oltre il punto di non ritorno (copia gia' pubblicata) si portano a termine: niente doppioni visibili.
    runOp(id, Long.MAX_VALUE, purpose, honourCancel = false)
  }

  private enum class TempCheck { GONE, MAYBE_PUBLISHED, UNREACHABLE }

  /**
   * Il temporaneo proprio di un elemento da annullare non c'e' piu' (tolto adesso o mai creato)? Per un elemento VERIFIED un temporaneo
   * gia' assente puo' voler dire che la pubblicazione e' avvenuta con la risposta persa: allora l'elemento non si annulla (P15, D3).
   */
  private suspend fun tempGone(item: OpItemRecord, purpose: Purpose): TempCheck {
    val temp = item.tempName?.takeIf { NasOpsPaths.isTempOf(it, deviceId) } ?: return TempCheck.GONE
    if (item.state == OpItemState.PLANNED) return TempCheck.GONE
    return when (val r = fileOps.deleteOwnTemp(item.dstShare ?: item.srcShare, temp, purpose)) {
      is NasResult.Err -> TempCheck.UNREACHABLE
      is NasResult.Ok -> when (r.value) {
        DeleteOutcome.Deleted -> TempCheck.GONE
        DeleteOutcome.Missing -> if (item.state == OpItemState.VERIFIED) TempCheck.MAYBE_PUBLISHED else TempCheck.GONE
        DeleteOutcome.Locked, is DeleteOutcome.SizeChanged, DeleteOutcome.NotAFile -> TempCheck.UNREACHABLE
      }
    }
  }

  override suspend fun resumeAll(budget: Duration): RunResult = lock.mutex.withLock {
    loadViews()
    val deadline = clocks.monotonicMs() + budget.inWholeMilliseconds
    var resumed = 0
    var done = 0
    var failed = 0
    var waiting = 0
    var stop = RunStop.COMPLETE
    for (op in store.ops(setOf(OpState.PLANNED, OpState.RUNNING, OpState.PAUSED))) {
      if (clocks.monotonicMs() >= deadline) {
        stop = RunStop.BUDGET
        break
      }
      resumed++
      // R9: l'utente l'aveva fermata (anche in un processo di prima): si annulla cio' che si puo' annullare, non si continua.
      if (op.errorCode == OpErrorCode.STOPPED_BY_USER) {
        cancelRequests += op.id
        try {
          cancelLocked(op.id, Purpose.BACKGROUND)
        } finally {
          cancelRequests -= op.id
        }
        continue
      }
      val run = runOp(op.id, deadline, Purpose.BACKGROUND)
      done += run.done
      failed += run.failed
      waiting += run.waiting
      // R1: un'operazione che aspetta l'ora del NAS fidata non ferma le altre.
      if (run.held) {
        stop = RunStop.BLOCKED
        continue
      }
      if (run.stop == RunStop.NAS_UNREACHABLE || run.stop == RunStop.BLOCKED || run.stop == RunStop.BUDGET) {
        stop = run.stop
        break
      }
      if (run.stop == RunStop.WAITING_LOCKED) stop = RunStop.WAITING_LOCKED
    }
    RunResult(resumed, done, failed, waiting, stop)
  }

  // =========================================================================================================================
  // Esecuzione di un'operazione
  // =========================================================================================================================

  private fun blockedForUser(share: String): Boolean = gate.statusOf(share).blocksUserOps

  /** Fermata dell'operazione intera (rete, condizioni): l'elemento resta al passo scritto, la ripresa riparte da li'. */
  private class PauseSignal(val reason: PauseReason) : RuntimeException(null, null, false, false)

  private class OpRun(val done: Int, val failed: Int, val waiting: Int, val stop: RunStop, val held: Boolean = false)

  /**
   * Il contesto di un giro: lo scopo (tempi del NAS) e lo stato corrente degli elementi (le cartelle aspettano il loro contenuto).
   * [heldForClock]: un elemento e' entrato nel cestino mentre l'ora del NAS era sospetta (R1) e aspetta di essere datato.
   */
  private class RunCtx(val purpose: Purpose, val current: MutableMap<Int, OpItemRecord>, val deadline: Long, var heldForClock: Boolean = false) {
    /** Quanto, in questo giro, si e' gia' aspettato per cartelle in uso dall'app ([OwnFolderBackoff]): il tetto vale per tutto il giro, non per elemento. */
    var shortWaitedMs: Long = 0

    /**
     * Fase 7 (D29): la porta di completamento non ha risposto per un elemento `PUBLISHED`: l'elemento resta li' (sorgente al suo posto), l'operazione
     * si ferma (nessun altro file si copia per niente) e aspetta, senza fermare le altre (come l'ora del NAS sospetta, R1).
     */
    var heldForCatalog: Boolean = false
  }

  private suspend fun runOp(id: OpId, deadlineMonotonic: Long, purpose: Purpose, honourCancel: Boolean = true): OpRun {
    val op = store.op(id) ?: return OpRun(0, 0, 0, RunStop.COMPLETE)
    if (op.state.terminal) return OpRun(0, 0, 0, RunStop.COMPLETE)
    setOpState(id, OpState.RUNNING, null)
    synchronized(viewsLock) { views[id]?.pause = null }
    publish()
    val items = store.items(id)
    val ctx = RunCtx(purpose, items.associateByTo(LinkedHashMap()) { it.index }, deadlineMonotonic)
    var stop = RunStop.COMPLETE
    try {
      for (snapshot in items) {
        if (snapshot.state.terminal) continue
        if (honourCancel && id in cancelRequests) {
          stop = RunStop.BLOCKED
          break
        }
        if (clocks.monotonicMs() >= deadlineMonotonic) {
          stop = RunStop.BUDGET
          pause(id, PauseReason.CONDITIONS)
          break
        }
        val status = listOfNotNull(snapshot.srcShare, snapshot.dstShare).map { gate.statusOf(it) }.firstOrNull { it.blocksUserOps }
        if (status != null) {
          stop = RunStop.BLOCKED
          pause(id, if (status == GateStatus.FROZEN) PauseReason.FROZEN else PauseReason.WRITES_STOPPED)
          break
        }
        val nasNow = nasNow()
        if (snapshot.nextAttemptAtNas != null && snapshot.nextAttemptAtNas > nasNow) continue
        val after = runItem(snapshot, ctx)
        ctx.current[after.index] = after
        if (ctx.heldForCatalog) break
      }
    } catch (p: PauseSignal) {
      stop = if (p.reason == PauseReason.NAS_UNREACHABLE) RunStop.NAS_UNREACHABLE else RunStop.BLOCKED
      pause(id, p.reason)
    }
    val finalItems = store.items(id)
    val doneCount = finalItems.count { it.state == OpItemState.DONE }
    val failedCount = finalItems.count { it.state == OpItemState.FAILED }
    val waitingCount = finalItems.count { it.waitingLocked }
    if (finalItems.all { it.state.terminal }) {
      val finalState = when {
        finalItems.any { it.state == OpItemState.CANCELLED } -> OpState.CANCELLED
        failedCount == finalItems.size -> OpState.FAILED
        else -> OpState.DONE
      }
      val code = if (finalState == OpState.FAILED) finalItems.firstOrNull { it.errorCode != null }?.errorCode else null
      setOpState(id, finalState, code)
      GalleryLog.i(TAG) { "operazione chiusa: ${finalState.code}, fatti $doneCount, fermi $failedCount" }
    } else if (stop == RunStop.COMPLETE && (ctx.heldForCatalog || finalItems.any { it.waitingCatalog })) {
      // Fase 7 (D29): il catalogo non conosce ancora la destinazione di un file pubblicato. La sorgente resta al suo posto, l'operazione aspetta
      // e non ferma le altre operazioni. Revisione B (I2): con il suo motivo ([PauseReason.CATALOG], prima "condizioni", che non diceva niente) e
      // con il prossimo tentativo nel diario ([CatalogHoldBackoff]); anche quando questo giro ha solo saltato un elemento non ancora da riprovare.
      pause(id, PauseReason.CATALOG)
      return OpRun(doneCount, failedCount, waitingCount, RunStop.BLOCKED, held = true)
    } else if (stop == RunStop.COMPLETE && ctx.heldForClock) {
      // R1: file gia' nel cestino, voce non ancora datata: l'operazione resta aperta (la voce e' sua, la pulizia la salta) finche' l'ora
      // del NAS torna fidata; la ripresa la data e la chiude.
      stop = RunStop.BLOCKED
      pause(id, PauseReason.CLOCK_JUMP)
      return OpRun(doneCount, failedCount, waitingCount, stop, held = true)
    } else if (stop == RunStop.COMPLETE && waitingCount > 0) {
      stop = RunStop.WAITING_LOCKED
      // Il motivo nel diario: file aperti (E-N13); gli elementi dicono quali e quando si riprova.
      setOpState(id, OpState.PAUSED, OpErrorCode.LOCKED)
    }
    return OpRun(doneCount, failedCount, waitingCount, stop)
  }

  private suspend fun pause(id: OpId, reason: PauseReason) {
    synchronized(viewsLock) { views[id]?.pause = reason }
    setOpState(id, OpState.PAUSED, null, reason)
    GalleryLog.d(TAG) { "operazione in pausa: ${reason.code}" }
  }

  // =========================================================================================================================
  // Il diario a ogni passo
  // =========================================================================================================================

  private fun nasNow(): Long = clocks.nasNowMs() ?: throw PauseSignal(PauseReason.NAS_UNREACHABLE)

  private suspend fun persist(old: OpItemRecord, next: OpItemRecord, allowNoClock: Boolean = false): OpItemRecord {
    val now = clocks.nasNowMs() ?: if (allowNoClock) old.updatedAtNas else throw PauseSignal(PauseReason.NAS_UNREACHABLE)
    val saved = next.copy(updatedAtNas = now)
    store.updateItem(saved)
    track(old, saved)
    return saved
  }

  /** Il passo [state], scritto PRIMA di farlo (o dopo, per gli stati d'arrivo). Un passo avanti azzera l'attesa di un blocco. */
  private suspend fun advance(item: OpItemRecord, state: OpItemState, edit: (OpItemRecord) -> OpItemRecord = { it }): OpItemRecord =
    persist(item, edit(item).copy(state = state, errorCode = null, nextAttemptAtNas = null))

  private suspend fun fail(item: OpItemRecord, code: OpErrorCode): OpItemRecord {
    GalleryLog.d(TAG) { "elemento fermo: ${code.code}" }
    return persist(item, item.copy(state = OpItemState.FAILED, errorCode = code, nextAttemptAtNas = null))
  }

  /** Il file e' aperto dal PC (E-N13): attesa crescente fino a 24 ore, poi l'elemento si ferma e lo dice. */
  private suspend fun waitLocked(item: OpItemRecord): OpItemRecord = when (val decision = LockedBackoff.decide(item.attempts)) {
    is LockedBackoff.Decision.RetryAfter ->
      persist(item, item.copy(attempts = item.attempts + 1, nextAttemptAtNas = nasNow() + decision.delayMs, errorCode = OpErrorCode.LOCKED))
    LockedBackoff.Decision.GiveUp -> fail(item, OpErrorCode.LOCKED_TOO_LONG)
  }

  /** Un errore del NAS: fallimento dell'elemento (definitivo), attesa di un file aperto, o pausa dell'operazione (rete). */
  private suspend fun failOrPause(item: OpItemRecord, error: NasError): OpItemRecord = when (error) {
    NasError.PermissionDenied -> fail(item, OpErrorCode.PERMISSION_DENIED)
    NasError.NasFull -> fail(item, OpErrorCode.NO_SPACE)
    NasError.QuotaExceeded -> fail(item, OpErrorCode.QUOTA)
    NasError.FileBusy -> waitLocked(item)
    is NasError.ShareMissing -> fail(item, OpErrorCode.UNREACHABLE)
    NasError.Exists -> fail(item, OpErrorCode.NAME_TAKEN)
    NasError.NotFound, is NasError.Unexpected -> fail(item, OpErrorCode.UNEXPECTED)
    // Rete, sessione, interruttore, percorso negato: niente e' deciso, si riprende piu' tardi dallo stesso passo.
    else -> throw PauseSignal(PauseReason.NAS_UNREACHABLE)
  }

  /**
   * La rinomina (o il cestino) di una cartella risponde `FolderInUse`: un handle nostro e' aperto sotto di lei (le primitive hanno gia'
   * rilasciato quelli di lettura). Non e' un file aperto dal PC e non e' un permesso negato: si riprova con attese brevi ([OwnFolderBackoff],
   * circa un minuto **in tutto per giro**: con molte cartelle bloccate non si aspetta un minuto per ognuna) senza uscire dal giro, finche'
   * l'utente non tocca "Ferma", le scritture non si fermano o il tempo della ripresa non finisce. Se alla fine e' ancora `FolderInUse`, chi
   * chiama la tratta come un file aperto ([waitLocked]): attesa normale, mai un errore.
   * Un processo che muore nell'attesa non lascia niente da disfare: il passo e' quello gia' scritto, la ripresa riparte da li' su una
   * connessione nuova (e senza handle).
   */
  private suspend fun retryWhileFolderInUse(item: OpItemRecord, ctx: RunCtx, call: suspend () -> NasResult<RenameOutcome>): NasResult<RenameOutcome> {
    var result = call()
    var waits = 0
    for (delayMs in OwnFolderBackoff.DELAYS_MS) {
      if (!result.isFolderInUse()) break
      if (item.opId in cancelRequests || clocks.monotonicMs() + delayMs > ctx.deadline) break
      if (ctx.shortWaitedMs + delayMs > OwnFolderBackoff.MAX_TOTAL_WAIT_MS) break
      if (listOfNotNull(item.srcShare, item.dstShare).any { gate.statusOf(it).blocksUserOps }) break
      GalleryLog.d(TAG) { "cartella in uso dall'app: riprovo fra $delayMs ms" }
      waits++
      ctx.shortWaitedMs += delayMs
      waitShortly(delayMs)
      result = call()
    }
    if (result.isFolderInUse()) GalleryLog.d(TAG) { "cartella ancora in uso dall'app dopo $waits attese brevi: attesa normale" }
    return result
  }

  private fun NasResult<RenameOutcome>.isFolderInUse(): Boolean = this is NasResult.Ok && value == RenameOutcome.FolderInUse

  private sealed interface Probe {
    data class Found(val entry: NasEntry) : Probe

    data object Absent : Probe
  }

  /** Lo stato di [path]: presente, assente; un errore di rete mette in pausa, un altro errore e' restituito. */
  private suspend fun probe(share: String, path: SharePath, purpose: Purpose): NasResult<Probe> =
    when (val r = reader.stat(share, path, purpose)) {
      is NasResult.Ok -> NasResult.Ok(r.value?.let { Probe.Found(it) } ?: Probe.Absent)
      is NasResult.Err -> if (r.error == NasError.NotFound) NasResult.Ok(Probe.Absent) else NasResult.Err(r.error)
    }

  /**
   * L'elemento [item] e' gia' in [dst] (risposta persa, §2.7): stessa natura e, per un file, stessa dimensione; se le identita' (`fileId`)
   * sono note, uguali. Un errore di rete mette in pausa.
   */
  private suspend fun alreadyAt(item: OpItemRecord, share: String, dst: SharePath, purpose: Purpose): Boolean {
    val entry = when (val r = probe(share, dst, purpose)) {
      is NasResult.Err -> if (isNetwork(r.error)) throw PauseSignal(PauseReason.NAS_UNREACHABLE) else return false
      is NasResult.Ok -> (r.value as? Probe.Found)?.entry ?: return false
    }
    return isSame(item, entry)
  }

  private fun isSame(item: OpItemRecord, entry: NasEntry): Boolean {
    if (entry.isDirectory != item.isFolder) return false
    val knownId = item.stamp?.fileId
    if (knownId != null && entry.fileId != null && knownId != entry.fileId) return false
    return item.isFolder || entry.size == (item.size ?: entry.size)
  }

  private fun isNetwork(error: NasError): Boolean = when (error) {
    NasError.PermissionDenied, NasError.NasFull, NasError.QuotaExceeded, NasError.FileBusy, is NasError.ShareMissing,
    NasError.Exists, NasError.NotFound, is NasError.Unexpected -> false
    else -> true
  }

  /** Il nome alternativo dopo [name] (`foto.jpg` → `foto (2).jpg` → `foto (3).jpg`): calcolato dal nome nel diario, uguale a ogni ripresa. */
  private fun nextAlternative(name: String): String? {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val stem = name.substring(0, dot)
    val ext = name.substring(dot)
    val match = ALTERNATIVE.matchEntire(stem)
    val (base, next) = if (match != null) match.groupValues[1] to (match.groupValues[2].toInt() + 1) else stem to 2
    if (next > MAX_ALTERNATIVE) return null
    val candidate = "$base ($next)$ext"
    return candidate.takeIf { SharePath.isValidComponent(it) }
  }

  /** Scrive nel diario il nome alternativo PRIMA di riprovare (§2.7): una ripresa non ne sceglie un altro. */
  private suspend fun withAlternative(item: OpItemRecord): OpItemRecord? {
    val dst = item.dstPath ?: return null
    val alternative = nextAlternative(dst.name) ?: return null
    val path = dst.parent()?.child(alternative) ?: SharePath.of(alternative)
    return persist(item, item.copy(dstPath = path))
  }

  // =========================================================================================================================
  // Gli elementi: una macchina a stati per cammino
  // =========================================================================================================================

  private suspend fun runItem(item: OpItemRecord, ctx: RunCtx): OpItemRecord = when (item.flow) {
    ItemFlow.CREATE_FOLDER -> createFolder(item, ctx)
    ItemFlow.RENAME -> rename(item, ctx)
    ItemFlow.RENAME_CASE_ONLY -> caseOnlyRename(item, ctx)
    ItemFlow.MOVE_ACROSS_SHARES -> if (item.isFolder) moveFolderAcross(item, ctx) else moveFileAcross(item, ctx)
    ItemFlow.TRASH -> trash(item, ctx)
    ItemFlow.RESTORE -> restore(item, ctx)
    ItemFlow.PURGE -> purgeNow(item, ctx)
  }

  // --- Crea cartella: PLANNED -> DONE ("esiste gia'" = fatto) -----------------------------------------------------------------

  private suspend fun createFolder(item: OpItemRecord, ctx: RunCtx): OpItemRecord =
    when (val r = fileOps.mkdirs(item.srcShare, item.srcPath, ctx.purpose)) {
      is NasResult.Err -> if (r.error == NasError.Exists) fail(item, OpErrorCode.NAME_TAKEN) else failOrPause(item, r.error)
      is NasResult.Ok -> advance(item, OpItemState.DONE)
    }

  // --- Rinomina e sposta nella stessa share: PLANNED -> RENAMING -> DONE ---------------------------------------------------------

  private suspend fun rename(item: OpItemRecord, ctx: RunCtx): OpItemRecord {
    var cur = item
    if (cur.state == OpItemState.PLANNED) cur = advance(cur, OpItemState.RENAMING)
    val share = cur.srcShare
    var parentReady = cur.dstPath!!.parent() == cur.srcPath.parent()
    var parentTries = 0
    while (true) {
      val dst = cur.dstPath!!
      if (!parentReady) {
        if (parentTries++ >= MAX_PARENT_TRIES) return fail(cur, OpErrorCode.UNEXPECTED)
        dst.parent()?.let { parent ->
          when (val r = fileOps.mkdirs(share, parent, ctx.purpose)) {
            is NasResult.Err -> return failOrPause(cur, r.error)
            is NasResult.Ok -> Unit
          }
        }
        parentReady = true
      }
      val expect = cur.stamp.takeIf { !cur.isFolder }
      when (val r = retryWhileFolderInUse(cur, ctx) { fileOps.rename(share, cur.srcPath, dst, expect, ctx.purpose) }) {
        // La cartella di destinazione e' sparita fra la creazione e la rinomina: si ricrea (poche volte).
        is NasResult.Err -> if (r.error == NasError.NotFound) parentReady = false else return failOrPause(cur, r.error)
        is NasResult.Ok -> when (r.value) {
          is RenameOutcome.Renamed -> return advance(cur, OpItemState.DONE)
          RenameOutcome.Collision -> cur = withAlternative(cur) ?: return fail(cur, OpErrorCode.NAME_TAKEN)
          // Risposta persa (la rinomina c'e' stata) o spostato da un altro dispositivo (E-N11): si guarda la destinazione.
          RenameOutcome.SourceMissing ->
            return if (alreadyAt(cur, share, dst, ctx.purpose)) advance(cur, OpItemState.DONE) else fail(cur, OpErrorCode.SOURCE_MISSING)
          is RenameOutcome.SourceChanged -> return fail(cur, OpErrorCode.SOURCE_CHANGED)
          RenameOutcome.Locked, RenameOutcome.FolderInUse -> return waitLocked(cur)
        }
      }
    }
  }

  // --- Rinomina solo di maiuscole (E-N7): PLANNED -> TEMP_NAME -> DONE ---------------------------------------------------------

  private suspend fun caseOnlyRename(item: OpItemRecord, ctx: RunCtx): OpItemRecord {
    val share = item.srcShare
    val src = item.srcPath
    val tmp = item.tempName ?: OpTemps.caseSwapName(src, item.opId, item.index)
    var cur = if (item.state == OpItemState.PLANNED) advance(item, OpItemState.TEMP_NAME) { it.copy(tempName = tmp) } else item
    val expect = cur.stamp.takeIf { !cur.isFolder }
    val atTmp = when (val r = probe(share, tmp, ctx.purpose)) {
      is NasResult.Err -> return failOrPause(cur, r.error)
      is NasResult.Ok -> r.value
    }
    if (atTmp is Probe.Found) {
      // Il nome temporaneo e' occupato: deve essere il NOSTRO file (identita' nota), altrimenti non si tocca niente.
      if (!isSame(cur, atTmp.entry)) return fail(cur, OpErrorCode.UNEXPECTED)
    } else {
      val atSrc = when (val r = probe(share, src, ctx.purpose)) {
        is NasResult.Err -> return failOrPause(cur, r.error)
        is NasResult.Ok -> r.value
      }
      if (atSrc !is Probe.Found) {
        // Niente al nome di partenza ne' al temporaneo: la seconda rinomina c'e' stata (anche con un nome alternativo) o un altro
        // dispositivo l'ha spostato.
        return if (alreadyAt(cur, share, cur.dstPath!!, ctx.purpose)) advance(cur, OpItemState.DONE) else fail(cur, OpErrorCode.SOURCE_MISSING)
      }
      // Il nome sul server e' gia' quello finale (risposta persa della seconda rinomina).
      if (atSrc.entry.name == cur.dstPath!!.name && isSame(cur, atSrc.entry)) return advance(cur, OpItemState.DONE)
      when (val r = retryWhileFolderInUse(cur, ctx) { fileOps.rename(share, src, tmp, expect, ctx.purpose) }) {
        is NasResult.Err -> return failOrPause(cur, r.error)
        is NasResult.Ok -> when (r.value) {
          is RenameOutcome.Renamed -> Unit
          RenameOutcome.Locked, RenameOutcome.FolderInUse -> return waitLocked(cur)
          is RenameOutcome.SourceChanged -> return fail(cur, OpErrorCode.SOURCE_CHANGED)
          RenameOutcome.SourceMissing -> return fail(cur, OpErrorCode.SOURCE_MISSING)
          RenameOutcome.Collision -> return fail(cur, OpErrorCode.UNEXPECTED)
        }
      }
    }
    // Seconda rinomina: dal temporaneo (nostro) al nome finale; se qualcuno l'ha preso nel frattempo, nome alternativo.
    while (true) {
      val dst = cur.dstPath!!
      when (val r = retryWhileFolderInUse(cur, ctx) { fileOps.rename(share, tmp, dst, null, ctx.purpose) }) {
        is NasResult.Err -> return if (isNetwork(r.error)) failOrPause(cur, r.error) else stuckAtTemp(cur, share, tmp, src, ctx, failOrPause(cur, r.error))
        is NasResult.Ok -> when (r.value) {
          is RenameOutcome.Renamed -> return advance(cur, OpItemState.DONE)
          RenameOutcome.Collision -> cur = withAlternative(cur) ?: return stuckAtTemp(cur, share, tmp, src, ctx, fail(cur, OpErrorCode.NAME_TAKEN))
          RenameOutcome.Locked, RenameOutcome.FolderInUse -> return waitLocked(cur)
          RenameOutcome.SourceMissing ->
            return if (alreadyAt(cur, share, dst, ctx.purpose)) advance(cur, OpItemState.DONE) else fail(cur, OpErrorCode.SOURCE_MISSING)
          is RenameOutcome.SourceChanged -> return stuckAtTemp(cur, share, tmp, src, ctx, fail(cur, OpErrorCode.SOURCE_CHANGED))
        }
      }
    }
  }

  /** Un elemento fermo con il file al nome temporaneo (nascosto): si prova a rimetterlo al nome di prima (best effort). */
  private suspend fun stuckAtTemp(cur: OpItemRecord, share: String, tmp: SharePath, src: SharePath, ctx: RunCtx, result: OpItemRecord): OpItemRecord {
    if (result.state.terminal) fileOps.rename(share, tmp, src, null, ctx.purpose)
    return result
  }

  // --- Sposta tra share (file): PLANNED -> COPYING -> COPIED -> VERIFIED -> PUBLISHED -> SOURCE_RECORDED -> SOURCE_TRASHED -> DONE --

  private suspend fun moveFileAcross(item: OpItemRecord, ctx: RunCtx): OpItemRecord {
    val temp = item.tempName ?: OpTemps.tempFor(deviceId, item.opId, item.index)
    var cur = item
    if (cur.state == OpItemState.PLANNED) cur = advance(cur, OpItemState.COPYING) { it.copy(tempName = temp) }
    if (cur.state == OpItemState.COPYING) cur = copyPhase(cur, temp, ctx)
    if (cur.state == OpItemState.COPIED) cur = verifyPhase(cur, temp, ctx)
    if (cur.state == OpItemState.VERIFIED) cur = publishPhase(cur, temp, ctx)
    if (cur.state == OpItemState.PUBLISHED) {
      // Fase 7 (D29): la copia verificata e' al suo posto; il catalogo la collega allo stesso media **prima** che la sorgente esca. Revisione B
      // (R8, I2): la porta non si salta mai; senza una sua risposta l'elemento si ferma qui, con il motivo e un tetto all'attesa.
      when (val linked = completePublished(cur, ctx)) {
        is Completion.Linked -> cur = linked.item
        is Completion.Stopped -> return linked.item
      }
      cur = recordSourcePhase(cur)
    }
    if (cur.state == OpItemState.SOURCE_RECORDED) cur = trashSourcePhase(cur, ctx)
    if (cur.state == OpItemState.SOURCE_TRASHED) cur = dateAndFinish(cur, ctx)
    return cur
  }

  /**
   * Fase 7 (D29, E-SH2, E-SH3; unifica `MoveCompletions` del campo A, D7): la **porta di completamento**, chiamata dopo `PUBLISHED` e **prima** di
   * `SOURCE_RECORDED`, a ogni ripresa del passo (chi la lega e' idempotente), con il timbro della destinazione letto adesso e l'impronta verificata.
   *
   * - Destinazione presente: si chiama la porta. Se risponde, si va avanti ([Completion.Linked]). Se lancia, il catalogo non conosce ancora la
   *   destinazione: l'elemento **resta** in `PUBLISHED` con la sorgente al suo posto e aspetta ([holdForCatalog]).
   * - Revisione B, R8: la destinazione **non si lascia guardare** per un motivo che non e' la rete (un file aperto in esclusiva da un altro client,
   *   un errore del NAS): prima si andava avanti **senza** la porta e, se l'occhiata dopo ([copyStillThere]) riusciva, la sorgente finiva nel
   *   cestino con il catalogo che non sapeva la destinazione (E-SH2). Adesso e' un'attesa come quella del catalogo: la porta non si salta mai.
   * - Destinazione sparita (tolta dal PC fra la pubblicazione e adesso) o diventata una cartella: niente da collegare; l'elemento si ferma qui
   *   ([OpErrorCode.UNEXPECTED]) con la sorgente al suo posto, senza voce del cestino (come l'esecutore di riferimento). Un errore di rete mette in
   *   pausa, come ogni passo.
   * - Revisione B, I2: l'attesa ha un motivo leggibile ([PauseReason.CATALOG]) e un tetto ([CatalogHoldBackoff]: qualche ripresa subito, poi
   *   intervalli crescenti fino a 24 ore); poi l'elemento si ferma con [OpErrorCode.CATALOG_MISMATCH] e le due copie restano.
   * Mai un'azione sul NAS nella porta: solo il catalogo locale. Nei log solo il tipo dell'errore.
   */
  private suspend fun completePublished(cur: OpItemRecord, ctx: RunCtx): Completion {
    val dstShare = cur.dstShare
    val dst = cur.dstPath
    val sha = cur.sha256
    // Un elemento pubblicato senza destinazione o senza impronta non puo' essere collegato: si ferma, la sorgente resta (mai senza la porta).
    if (dstShare == null || dst == null || sha == null) return Completion.Stopped(fail(cur, OpErrorCode.UNEXPECTED))
    val entry = when (val r = probe(dstShare, dst, ctx.purpose)) {
      is NasResult.Err -> if (isNetwork(r.error)) throw PauseSignal(PauseReason.NAS_UNREACHABLE) else return holdForCatalog(cur, ctx)
      is NasResult.Ok -> (r.value as? Probe.Found)?.entry ?: return Completion.Stopped(fail(cur, OpErrorCode.UNEXPECTED))
    }
    if (entry.isDirectory) return Completion.Stopped(fail(cur, OpErrorCode.UNEXPECTED))
    try {
      completions.published(cur.opId, cur.index, NasFileRef(cur.srcShare, cur.srcPath), NasFileRef(dstShare, dst), entry.stamp, sha)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "catalogo non aggiornato dopo la pubblicazione (${e.javaClass.simpleName}): l'elemento aspetta, la sorgente resta" }
      return holdForCatalog(cur, ctx)
    }
    // Un'attesa del catalogo e' finita: i suoi tentativi si azzerano (le attese dei passi dopo, file aperti, contano da capo).
    val linked = if (cur.errorCode == OpErrorCode.CATALOG_PENDING) persist(cur, cur.copy(attempts = 0, errorCode = null, nextAttemptAtNas = null)) else cur
    return Completion.Linked(linked)
  }

  /** L'esito della porta di completamento per un elemento `PUBLISHED`: si va avanti con [item], oppure ci si ferma qui. */
  private sealed interface Completion {
    val item: OpItemRecord

    class Linked(override val item: OpItemRecord) : Completion

    class Stopped(override val item: OpItemRecord) : Completion
  }

  /**
   * Revisione B (I2, R8): l'elemento aspetta il catalogo in `PUBLISHED`, con la sorgente al suo posto: il prossimo tentativo e' scritto nel diario
   * ([CatalogHoldBackoff]), l'operazione si ferma con [PauseReason.CATALOG] e non ferma le altre. Oltre il tetto l'elemento si ferma per sempre.
   */
  private suspend fun holdForCatalog(cur: OpItemRecord, ctx: RunCtx): Completion {
    // I tentativi contano solo dentro questa attesa (quelli dei passi prima, file aperti, non c'entrano).
    val attempts = if (cur.errorCode == OpErrorCode.CATALOG_PENDING) cur.attempts else 0
    return when (val decision = CatalogHoldBackoff.decide(attempts)) {
      is LockedBackoff.Decision.RetryAfter -> {
        ctx.heldForCatalog = true
        Completion.Stopped(
          persist(cur, cur.copy(attempts = attempts + 1, nextAttemptAtNas = nasNow() + decision.delayMs, errorCode = OpErrorCode.CATALOG_PENDING)),
        )
      }
      LockedBackoff.Decision.GiveUp -> {
        GalleryLog.w(TAG) { "catalogo mai allineato dopo la pubblicazione: l'elemento si ferma, le due copie restano" }
        Completion.Stopped(fail(cur, OpErrorCode.CATALOG_MISMATCH))
      }
    }
  }

  /**
   * R5, R1 (revisione 4B): il file e' nel cestino adesso (passo `TRASHED` o `SOURCE_TRASHED`). I 30 giorni della sua voce contano da qui
   * (`trashEntered`, solo in avanti), non dalla voce scritta prima della rinomina (che puo' essere di un mese fa, dopo una pausa). Con l'ora
   * del NAS sospetta (salto, D32) non si data: l'elemento resta a questo passo e l'operazione aperta (la pulizia salta le voci citate da
   * un'operazione aperta) finche' l'ora torna fidata.
   */
  private suspend fun dateAndFinish(cur: OpItemRecord, ctx: RunCtx): OpItemRecord {
    val trashId = cur.trashId
    val entry = trashId?.let { store.trashEntry(it) }
    if (entry != null && entry.state == TrashEntryState.TRASHED) {
      if (gate.statusOf(cur.srcShare) == GateStatus.CLOCK_JUMP) {
        ctx.heldForClock = true
        return cur
      }
      store.trashEntered(entry.trashId, nasNow())
    }
    return advance(cur, OpItemState.DONE)
  }

  private suspend fun deleteTemp(share: String, temp: SharePath, purpose: Purpose) {
    // Best effort: un temporaneo rimasto e' proprio e citato dal diario, lo toglie la ripresa o la pulizia dei propri file.
    fileOps.deleteOwnTemp(share, temp, purpose)
  }

  private suspend fun copyPhase(item: OpItemRecord, temp: SharePath, ctx: RunCtx): OpItemRecord {
    val cur = item
    val dstShare = cur.dstShare!!
    // Ripresa: un temporaneo proprio con la dimensione giusta e' una copia gia' fatta (la verifica completa viene dopo);
    // altrimenti si toglie e si rifa' (§2.7).
    when (val r = probe(dstShare, temp, ctx.purpose)) {
      is NasResult.Err -> return failOrPause(cur, r.error)
      is NasResult.Ok -> when (val found = r.value) {
        is Probe.Found -> {
          if (!found.entry.isDirectory && found.entry.size == cur.size) return advance(cur, OpItemState.COPIED)
          when (val d = fileOps.deleteOwnTemp(dstShare, temp, ctx.purpose)) {
            is NasResult.Err -> return failOrPause(cur, d.error)
            is NasResult.Ok -> if (d.value == DeleteOutcome.Locked) return waitLocked(cur)
          }
        }
        Probe.Absent -> Unit
      }
    }
    return when (val r = fileOps.copyToTemp(cur.srcShare, cur.srcPath, dstShare, temp, cur.stamp, ctx.purpose)) {
      is NasResult.Err -> {
        if (r.error == NasError.NasFull || r.error == NasError.QuotaExceeded) deleteTemp(dstShare, temp, ctx.purpose)
        failOrPause(cur, r.error)
      }
      is NasResult.Ok -> when (r.value) {
        is CopyOutcome.Copied -> advance(cur, OpItemState.COPIED)
        CopyOutcome.SourceMissing -> fail(cur, OpErrorCode.SOURCE_MISSING)
        // E-D13: la sorgente non e' piu' quella confermata. Nessun cestino, la sorgente resta dov'e'.
        is CopyOutcome.SourceChanged -> fail(cur, OpErrorCode.SOURCE_CHANGED)
        // Il temporaneo e' comparso fra l'occhiata e la copia: lo si toglie e la ripresa rifa' la copia.
        CopyOutcome.TempExists -> {
          deleteTemp(dstShare, temp, ctx.purpose)
          throw PauseSignal(PauseReason.NAS_UNREACHABLE)
        }
        CopyOutcome.Locked -> waitLocked(cur)
        // E-D12: copia lato server rifiutata, ripiego dal telefono (attivita' pesante).
        CopyOutcome.NotSupported -> fallbackCopy(cur, temp, ctx)
      }
    }
  }

  /** Il ripiego dal telefono (E-D12): legge la sorgente e la scrive nel temporaneo, controllando il timbro sullo stesso handle. */
  private suspend fun fallbackCopy(cur: OpItemRecord, temp: SharePath, ctx: RunCtx): OpItemRecord {
    if (!heavy.allowedNow()) throw PauseSignal(PauseReason.CONDITIONS)
    val dstShare = cur.dstShare!!
    val file = when (val opened = reader.open(cur.srcShare, cur.srcPath, ctx.purpose)) {
      is NasResult.Err -> return if (opened.error == NasError.NotFound) fail(cur, OpErrorCode.SOURCE_MISSING) else failOrPause(cur, opened.error)
      is NasResult.Ok -> opened.value
    }
    file.use { source ->
      if (cur.stamp != null && !source.stamp.sameAs(cur.stamp)) return fail(cur, OpErrorCode.SOURCE_CHANGED)
      val writer = when (val opened = fileOps.openTemp(dstShare, temp, ctx.purpose)) {
        is NasResult.Err -> {
          // Un temporaneo rimasto (di un giro precedente): si toglie e la ripresa riprova.
          if (opened.error == NasError.Exists) deleteTemp(dstShare, temp, ctx.purpose)
          return failOrPause(cur, if (opened.error == NasError.Exists) NasError.Timeout else opened.error)
        }
        is NasResult.Ok -> opened.value
      }
      writer.use { out ->
        val buffer = ByteArray(FALLBACK_CHUNK)
        var offset = 0L
        while (offset < source.size) {
          val n = try {
            source.read(offset, buffer, 0, buffer.size)
          } catch (e: NasIoException) {
            return failOrPause(cur, e.error)
          }
          if (n <= 0) break
          when (val w = out.write(offset, buffer, 0, n)) {
            is NasResult.Err -> {
              if (w.error == NasError.NasFull || w.error == NasError.QuotaExceeded) deleteTemp(dstShare, temp, ctx.purpose)
              return failOrPause(cur, w.error)
            }
            is NasResult.Ok -> Unit
          }
          offset += n
        }
        return when (val finished = out.finish(source.size)) {
          is NasResult.Err -> failOrPause(cur, finished.error)
          is NasResult.Ok -> when (finished.value) {
            is TempFinish.Complete -> advance(cur, OpItemState.COPIED)
            is TempFinish.SizeMismatch -> {
              deleteTemp(dstShare, temp, ctx.purpose)
              fail(cur, OpErrorCode.VERIFY_FAILED)
            }
          }
        }
      }
    }
  }

  private sealed interface Hashed {
    data class Ok(val sha: Sha256, val stamp: NasStamp) : Hashed

    data object Missing : Hashed

    data class Error(val error: NasError) : Hashed
  }

  /** L'impronta SHA-256 di un file del NAS, letto per intero, e il timbro visto sullo stesso handle. */
  private suspend fun hashOf(share: String, path: SharePath, purpose: Purpose): Hashed {
    val file = when (val opened = reader.open(share, path, purpose)) {
      is NasResult.Err -> return if (opened.error == NasError.NotFound) Hashed.Missing else Hashed.Error(opened.error)
      is NasResult.Ok -> opened.value
    }
    file.use {
      return when (val sha = reader.readAll(file, { _, _ -> }, null)) {
        is NasResult.Err -> Hashed.Error(sha.error)
        is NasResult.Ok -> Hashed.Ok(sha.value, file.stamp)
      }
    }
  }

  private suspend fun verifyPhase(cur: OpItemRecord, temp: SharePath, ctx: RunCtx): OpItemRecord {
    val dstShare = cur.dstShare!!
    // 1. La sorgente: ancora quella confermata (E-D13), e la sua impronta (nota dal catalogo per quel timbro, o calcolata).
    val known = cur.stamp?.let { hashes.sha256(cur.srcShare, cur.srcPath, it) }
    val source: Hashed.Ok = if (known != null && cur.stamp != null) {
      when (val r = probe(cur.srcShare, cur.srcPath, ctx.purpose)) {
        is NasResult.Err -> return failOrPause(cur, r.error)
        is NasResult.Ok -> when (val found = r.value) {
          Probe.Absent -> {
            deleteTemp(dstShare, temp, ctx.purpose)
            return fail(cur, OpErrorCode.SOURCE_MISSING)
          }
          is Probe.Found -> if (found.entry.isDirectory || !found.entry.stamp.sameAs(cur.stamp)) {
            deleteTemp(dstShare, temp, ctx.purpose)
            return fail(cur, OpErrorCode.SOURCE_CHANGED)
          } else {
            Hashed.Ok(known, found.entry.stamp)
          }
        }
      }
    } else {
      when (val h = hashOf(cur.srcShare, cur.srcPath, ctx.purpose)) {
        Hashed.Missing -> {
          deleteTemp(dstShare, temp, ctx.purpose)
          return fail(cur, OpErrorCode.SOURCE_MISSING)
        }
        is Hashed.Error -> return failOrPause(cur, h.error)
        is Hashed.Ok -> h
      }
    }
    if (cur.stamp != null && !source.stamp.sameAs(cur.stamp)) {
      // E-D13: cambiata mentre la si copiava. Si ferma l'elemento, la copia (propria) si toglie, niente va nel cestino.
      deleteTemp(dstShare, temp, ctx.purpose)
      return fail(cur, OpErrorCode.SOURCE_CHANGED)
    }
    // 2. La copia, per intero.
    val copy = when (val h = hashOf(dstShare, temp, ctx.purpose)) {
      // Prima della pubblicazione il temporaneo (proprio) lo toglie solo un "Ferma" la cui scrittura nel diario e' andata persa
      // con il processo: per l'utente non e' cambiato niente (sorgente al suo posto, nessun doppione), l'elemento e' annullato.
      Hashed.Missing -> return advance(cur, OpItemState.CANCELLED)
      is Hashed.Error -> return failOrPause(cur, h.error)
      is Hashed.Ok -> h
    }
    if (copy.sha != source.sha || copy.stamp.size != source.stamp.size) {
      deleteTemp(dstShare, temp, ctx.purpose)
      return fail(cur, OpErrorCode.VERIFY_FAILED)
    }
    // L'impronta e il timbro verificati si scrivono: il cestino della sorgente avverra' solo se il timbro sara' ancora questo.
    return advance(cur, OpItemState.VERIFIED) { it.copy(sha256 = source.sha, stamp = source.stamp, size = source.stamp.size) }
  }

  private suspend fun publishPhase(item: OpItemRecord, temp: SharePath, ctx: RunCtx): OpItemRecord {
    var cur = item
    val dstShare = cur.dstShare!!
    while (true) {
      val dst = cur.dstPath!!
      when (val r = fileOps.publishTemp(dstShare, temp, dst, ctx.purpose)) {
        is NasResult.Err -> return failOrPause(cur, r.error)
        is NasResult.Ok -> when (r.value) {
          is PublishOutcome.Published -> return advance(cur, OpItemState.PUBLISHED)
          PublishOutcome.Collision -> cur = withAlternative(cur) ?: return fail(cur, OpErrorCode.NAME_TAKEN)
          PublishOutcome.Locked -> return waitLocked(cur)
          // Il temporaneo non c'e' piu': pubblicato con la risposta persa (destinazione con dimensione e impronta verificate), o sparito.
          PublishOutcome.TempMissing -> return when {
            publishedHere(cur, dst, ctx) -> advance(cur, OpItemState.PUBLISHED)
            // Nessuna pubblicazione e nessun file a quel nome: il temporaneo l'ha tolto un "Ferma" perso con il processo (vedi
            // verifyPhase), l'elemento e' annullato. Un file diverso a quel nome invece non si spiega: l'elemento si ferma.
            destinationAbsent(dstShare, dst, ctx) -> advance(cur, OpItemState.CANCELLED)
            else -> fail(cur, OpErrorCode.UNEXPECTED)
          }
        }
      }
    }
  }

  /** Nessuna voce a [dst] (una lettura non riuscita per la rete mette in pausa, ogni altro errore vale "non si sa": false). */
  private suspend fun destinationAbsent(share: String, dst: SharePath, ctx: RunCtx): Boolean = when (val r = probe(share, dst, ctx.purpose)) {
    is NasResult.Err -> if (isNetwork(r.error)) throw PauseSignal(PauseReason.NAS_UNREACHABLE) else false
    is NasResult.Ok -> r.value == Probe.Absent
  }

  /** La destinazione [dst] ha la dimensione e l'impronta verificate: la pubblicazione e' avvenuta (§2.7). */
  private suspend fun publishedHere(cur: OpItemRecord, dst: SharePath, ctx: RunCtx): Boolean {
    val share = cur.dstShare!!
    val entry = when (val r = probe(share, dst, ctx.purpose)) {
      is NasResult.Err -> if (isNetwork(r.error)) throw PauseSignal(PauseReason.NAS_UNREACHABLE) else return false
      is NasResult.Ok -> (r.value as? Probe.Found)?.entry ?: return false
    }
    if (entry.isDirectory || entry.size != cur.size) return false
    return when (val h = hashOf(share, dst, ctx.purpose)) {
      is Hashed.Ok -> h.sha == cur.sha256
      Hashed.Missing -> false
      is Hashed.Error -> if (isNetwork(h.error)) throw PauseSignal(PauseReason.NAS_UNREACHABLE) else false
    }
  }

  private suspend fun recordSourcePhase(cur: OpItemRecord): OpItemRecord {
    val trashId = cur.trashId ?: return fail(cur, OpErrorCode.UNEXPECTED)
    val now = nasNow()
    // Prima la voce del diario locale e il record (reason = move): un record senza file e' innocuo e non si mostra (§2.7).
    store.recordTrash(
      LocalTrashRecord(
        trashId = trashId,
        accountId = accountId,
        shareKey = cur.srcShare.lowercase(Locale.ROOT),
        shareName = cur.srcShare,
        originalPath = cur.srcPath,
        trashedPath = NasLayout.trashItemFile(deviceId, trashId),
        reason = TrashReason.MOVE,
        isFolder = false,
        size = cur.size ?: 0,
        fileCount = null,
        trashedAtNas = now,
        state = TrashEntryState.TRASHED,
        opId = cur.opId,
        updatedAtNas = now,
      ),
    )
    publishRecord(trashId)
    return advance(cur, OpItemState.SOURCE_RECORDED)
  }

  /** Il record firmato della voce [trashId], con i valori della voce del diario locale (scritta per prima: vince la prima scrittura). */
  private suspend fun publishRecord(trashId: String) {
    val entry = store.trashEntry(trashId) ?: return
    val draft = TrashRecordDraft(
      entry.shareName, entry.trashId, entry.originalPath, entry.size, entry.trashedAtNas, entry.reason, entry.isFolder, entry.fileCount,
    )
    when (records.publish(draft)) {
      RecordWrite.Written, RecordWrite.Unavailable -> Unit
      RecordWrite.Retry -> throw PauseSignal(PauseReason.NAS_UNREACHABLE)
    }
  }

  private suspend fun trashSourcePhase(cur: OpItemRecord, ctx: RunCtx): OpItemRecord {
    val trashId = cur.trashId ?: return fail(cur, OpErrorCode.UNEXPECTED)
    // I10: fra la pubblicazione e il cestino della sorgente la copia puo' essere stata tolta dal PC (sembrava un doppione): la sorgente
    // va nel cestino solo se la copia c'e' ancora, con la dimensione verificata. Altrimenti restano dove sono (nessuna perdita).
    if (!copyStillThere(cur, ctx)) {
      store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
      return fail(cur, OpErrorCode.UNEXPECTED)
    }
    // Solo se il file e' ancora quello verificato (timbro): altrimenti nessun cestino, restano entrambe le copie (E-D13).
    return when (val r = fileOps.trashMove(cur.srcShare, cur.srcPath, trashId, cur.stamp, ctx.purpose)) {
      is NasResult.Err -> closeEntryIfFailed(failOrPause(cur, r.error), trashId, ctx)
      is NasResult.Ok -> when (r.value) {
        is RenameOutcome.Renamed -> advance(cur, OpItemState.SOURCE_TRASHED)
        RenameOutcome.SourceMissing -> if (inOwnTrash(cur, trashId, ctx)) {
          advance(cur, OpItemState.SOURCE_TRASHED)
        } else {
          // La sorgente e' sparita per altra via dopo la pubblicazione: la copia e' a posto, nel cestino non c'e' niente.
          store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
          advance(cur, OpItemState.SOURCE_TRASHED)
        }
        is RenameOutcome.SourceChanged -> {
          store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
          fail(cur, OpErrorCode.SOURCE_CHANGED)
        }
        // Un file non e' una cartella: `FolderInUse` non puo' arrivare di qui, ma se arrivasse e' un'attesa come le altre.
        RenameOutcome.Locked, RenameOutcome.FolderInUse -> closeEntryIfFailed(waitLocked(cur), trashId, ctx)
        RenameOutcome.Collision -> {
          // Il posto nel cestino e' occupato da altro: la voce non deve mai far eliminare quel file.
          store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
          fail(cur, OpErrorCode.UNEXPECTED)
        }
      }
    }
  }

  /** La copia pubblicata c'e' ancora con la dimensione verificata; se la sorgente e' gia' nel cestino (ripresa), non serve guardare. */
  private suspend fun copyStillThere(cur: OpItemRecord, ctx: RunCtx): Boolean {
    val dst = cur.dstPath ?: return false
    val entry = when (val r = probe(cur.dstShare!!, dst, ctx.purpose)) {
      is NasResult.Err -> if (isNetwork(r.error)) throw PauseSignal(PauseReason.NAS_UNREACHABLE) else return false
      is NasResult.Ok -> (r.value as? Probe.Found)?.entry
    }
    if (entry != null) return !entry.isDirectory && entry.size == cur.size
    // La copia non c'e': forse la sorgente e' gia' nel cestino (risposta persa) e la copia l'ha tolta qualcuno dopo. Il cestino e' coerente.
    return inOwnTrash(cur, cur.trashId ?: return false, ctx)
  }

  /** L'elemento e' nel proprio cestino con la sua identita' (la rinomina c'e' stata, la risposta no). */
  private suspend fun inOwnTrash(cur: OpItemRecord, trashId: String, ctx: RunCtx): Boolean {
    val trashed = NasLayout.trashItemFile(deviceId, trashId)
    return when (val r = probe(cur.srcShare, trashed, ctx.purpose)) {
      is NasResult.Err -> if (isNetwork(r.error)) throw PauseSignal(PauseReason.NAS_UNREACHABLE) else false
      is NasResult.Ok -> (r.value as? Probe.Found)?.let { isSame(cur, it.entry) } ?: false
    }
  }

  // --- Sposta tra share (cartella): ricrea la cartella e, dopo il suo contenuto, toglie quella d'origine se e' vuota --------------

  private suspend fun moveFolderAcross(item: OpItemRecord, ctx: RunCtx): OpItemRecord {
    var cur = item
    if (cur.state == OpItemState.PLANNED) cur = advance(cur, OpItemState.COPYING)
    if (cur.state == OpItemState.COPYING) {
      // La cartella nella destinazione (anche vuota): "esiste gia'" va bene; un file al suo posto ferma l'elemento.
      when (val r = fileOps.mkdirs(cur.dstShare!!, cur.dstPath!!, ctx.purpose)) {
        is NasResult.Err -> return failOrPause(cur, r.error)
        is NasResult.Ok -> cur = advance(cur, OpItemState.COPIED)
      }
    }
    // La cartella d'origine si tocca solo quando il suo contenuto ha finito: un file in attesa (aperto dal PC) la tiene, e la
    // cartella aspetta con lui (stessa condizione d'attesa, senza consumare tentativi).
    val pending = ctx.current.values.filter { other ->
      other.index != cur.index && !other.state.terminal && other.srcShare.equals(cur.srcShare, ignoreCase = true) &&
        other.srcPath != cur.srcPath && other.srcPath.isWithin(cur.srcPath)
    }
    if (pending.isNotEmpty()) {
      val next = pending.mapNotNull { it.nextAttemptAtNas }.minOrNull() ?: (nasNow() + LockedBackoff.DELAYS_MS.first())
      return persist(cur, cur.copy(nextAttemptAtNas = next, errorCode = OpErrorCode.LOCKED))
    }
    if (cur.state == OpItemState.COPIED) cur = advance(cur, OpItemState.VERIFIED)
    if (cur.state == OpItemState.VERIFIED) cur = advance(cur, OpItemState.PUBLISHED)
    if (cur.state == OpItemState.PUBLISHED) cur = advance(cur, OpItemState.SOURCE_RECORDED)
    if (cur.state == OpItemState.SOURCE_RECORDED) {
      removeEmptySource(cur, ctx)?.let { return it }
      cur = advance(cur, OpItemState.SOURCE_TRASHED)
    }
    if (cur.state == OpItemState.SOURCE_TRASHED) cur = advance(cur, OpItemState.DONE)
    return cur
  }

  /**
   * O-B18 (prove sul campo, gruppo B): a fine spostamento la cartella d'origine segue i suoi file e se ne va, ma **solo se e' davvero vuota in
   * questo momento**: `rmdirIfEmpty` e' una sola richiesta al NAS che toglie la cartella se non ha niente dentro e rifiuta altrimenti (mai una
   * cancellazione ricorsiva, mai un file arrivato dal PC nel frattempo). Una cartella vuota non ha niente da perdere e il ripristino dei suoi
   * file la ricrea (E-D3), quindi non passa dal cestino come una voce a se' (sarebbe un elemento da 0 byte in "Eliminati di recente").
   *
   * Prima la richiesta era una sola e un [RmdirOutcome.Locked] (un handle aperto sotto o sopra la cartella, nostro o di un altro client) la lasciava
   * li' per sempre, vuota, senza dirlo. Ora un blocco si riprova con le attese brevi di [OwnFolderBackoff] (stesso tetto per giro delle cartelle in
   * uso dall'app); se alla fine e' ancora bloccata o non e' vuota, **resta com'e'** e l'operazione si chiude lo stesso: una cartella lasciata
   * non e' un errore, e lo si dice nel log (solo numeri) per capire il perche' alla prossima prova.
   *
   * Vale anche per la ripresa dopo una morte del processo: l'elemento e' al passo [OpItemState.SOURCE_RECORDED] e la ripresa rifa' questa richiesta
   * (una cartella gia' tolta risponde [RmdirOutcome.Missing]).
   *
   * @return l'elemento da restituire subito (pausa o fallimento del NAS), o null se si puo' passare al passo dopo.
   */
  private suspend fun removeEmptySource(cur: OpItemRecord, ctx: RunCtx): OpItemRecord? {
    var waits = 0
    while (true) {
      when (val r = fileOps.rmdirIfEmpty(cur.srcShare, cur.srcPath, ctx.purpose)) {
        // `NotFound` qui vuol dire "non e' una cartella": non c'e' niente da togliere.
        is NasResult.Err -> return if (r.error == NasError.NotFound) null else failOrPause(cur, r.error)
        is NasResult.Ok -> when (r.value) {
          RmdirOutcome.Removed, RmdirOutcome.Missing -> return null
          RmdirOutcome.NotEmpty -> {
            logSourceLeft(cur, ctx, "non vuota")
            return null
          }
          RmdirOutcome.Locked -> {
            val delayMs = OwnFolderBackoff.DELAYS_MS.getOrNull(waits)
            if (delayMs == null || !mayWaitShortly(cur, ctx, delayMs)) {
              logSourceLeft(cur, ctx, "ancora in uso")
              return null
            }
            GalleryLog.d(TAG) { "cartella d'origine in uso: riprovo a toglierla fra $delayMs ms" }
            waits++
            ctx.shortWaitedMs += delayMs
            waitShortly(delayMs)
          }
        }
      }
    }
  }

  /** Si puo' fare una attesa breve di [delayMs]: non e' stato chiesto "Ferma", c'e' ancora tempo nel giro, resta budget per giro e le scritture sono aperte. */
  private fun mayWaitShortly(item: OpItemRecord, ctx: RunCtx, delayMs: Long): Boolean =
    item.opId !in cancelRequests &&
      clocks.monotonicMs() + delayMs <= ctx.deadline &&
      ctx.shortWaitedMs + delayMs <= OwnFolderBackoff.MAX_TOTAL_WAIT_MS &&
      listOfNotNull(item.srcShare, item.dstShare).none { gate.statusOf(it).blocksUserOps }

  /** Perche' una cartella d'origine e' rimasta (solo numeri: quante voci l'elenco le vede dentro; "0" con "non vuota" = file che il server nasconde). */
  private suspend fun logSourceLeft(cur: OpItemRecord, ctx: RunCtx, why: String) {
    // In release il log non scrive: nemmeno la richiesta al NAS che serve solo a lui.
    if (!GalleryLog.isEnabled) return
    var entries = 0
    val listed = try {
      reader.list(cur.srcShare, cur.srcPath, ctx.purpose) { page -> entries += page.size }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    val seen = if (listed is NasResult.Ok) "$entries voci viste" else "elenco non letto"
    GalleryLog.i(TAG) { "cartella d'origine $why: resta dov'e' ($seen)" }
  }

  // --- Elimina: PLANNED -> RECORDED -> TRASHED -> DONE ---------------------------------------------------------------------------

  private suspend fun trash(item: OpItemRecord, ctx: RunCtx): OpItemRecord {
    var cur = item
    val trashId = cur.trashId ?: return fail(cur, OpErrorCode.UNEXPECTED)
    if (cur.state == OpItemState.PLANNED) {
      // Prima la voce del diario locale e il record firmato, poi la rinomina nel cestino (un record senza file e' innocuo).
      val counted = if (cur.isFolder) {
        when (val r = folderBeforeTrash(cur, ctx)) {
          is FolderCheck.Ready -> r.counted
          is FolderCheck.Stop -> return r.item
        }
      } else {
        null
      }
      // R3: l'inventario della cartella, nel diario, prima della rinomina: la pulizia cancellera' solo cio' che c'e' qui.
      if (counted != null) store.saveTrashManifest(trashId, counted.manifest)
      val now = nasNow()
      store.recordTrash(
        LocalTrashRecord(
          trashId = trashId,
          accountId = accountId,
          shareKey = cur.srcShare.lowercase(Locale.ROOT),
          shareName = cur.srcShare,
          originalPath = cur.srcPath,
          trashedPath = NasLayout.trashItemFile(deviceId, trashId),
          reason = TrashReason.DELETE,
          isFolder = cur.isFolder,
          size = counted?.bytes ?: (cur.size ?: 0),
          fileCount = counted?.files,
          trashedAtNas = now,
          state = TrashEntryState.TRASHED,
          opId = cur.opId,
          updatedAtNas = now,
        ),
      )
      publishRecord(trashId)
      cur = advance(cur, OpItemState.RECORDED)
    }
    if (cur.state == OpItemState.RECORDED) {
      val expect = cur.stamp.takeIf { !cur.isFolder }
      when (val r = retryWhileFolderInUse(cur, ctx) { fileOps.trashMove(cur.srcShare, cur.srcPath, trashId, expect, ctx.purpose) }) {
        is NasResult.Err -> return closeEntryIfFailed(failOrPause(cur, r.error), trashId, ctx)
        is NasResult.Ok -> when (r.value) {
          is RenameOutcome.Renamed -> cur = advance(cur, OpItemState.TRASHED)
          RenameOutcome.SourceMissing -> if (inOwnTrash(cur, trashId, ctx)) {
            cur = advance(cur, OpItemState.TRASHED)
          } else {
            // Gia' tolto da un altro dispositivo o dal PC (E-N11): niente nel cestino, la voce non fara' mai eliminare nulla.
            store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
            return fail(cur, OpErrorCode.SOURCE_MISSING)
          }
          is RenameOutcome.SourceChanged -> {
            store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
            return fail(cur, OpErrorCode.SOURCE_CHANGED)
          }
          RenameOutcome.Locked, RenameOutcome.FolderInUse -> return closeEntryIfFailed(waitLocked(cur), trashId, ctx)
          RenameOutcome.Collision -> {
            store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
            return fail(cur, OpErrorCode.UNEXPECTED)
          }
        }
      }
    }
    return if (cur.state == OpItemState.TRASHED) dateAndFinish(cur, ctx) else cur
  }

  /**
   * Un elemento di "elimina" fermo per sempre prima che il file sia entrato nel cestino (permesso negato, file aperto oltre 24 ore):
   * la sua voce del diario locale si chiude, cosi' non resta "nel cestino" un elemento che non c'e'. Se invece il file e' nel cestino
   * (la rinomina c'e' stata), la voce resta: il cestino e' coerente e la scadenza fara' il suo corso.
   */
  private suspend fun closeEntryIfFailed(result: OpItemRecord, trashId: String, ctx: RunCtx): OpItemRecord {
    if (result.state == OpItemState.FAILED && !inOwnTrash(result, trashId, ctx)) store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
    return result
  }

  private sealed interface FolderCheck {
    class Ready(val counted: FolderInventory.Result.Counted) : FolderCheck

    class Stop(val item: OpItemRecord) : FolderCheck
  }

  /**
   * Una cartella da cestinare, un attimo prima della rinomina: e' ancora quella confermata (identita', I4) e non contiene **piu'** byte di
   * quelli che il foglio ha detto (R2: la conferma vale per quei numeri); il suo inventario (R3) e il conteggio per il record.
   */
  private suspend fun folderBeforeTrash(cur: OpItemRecord, ctx: RunCtx): FolderCheck {
    val self = when (val r = probe(cur.srcShare, cur.srcPath, ctx.purpose)) {
      is NasResult.Err -> return FolderCheck.Stop(failOrPause(cur, r.error))
      is NasResult.Ok -> (r.value as? Probe.Found)?.entry ?: return FolderCheck.Stop(fail(cur, OpErrorCode.SOURCE_MISSING))
    }
    val knownId = cur.stamp?.fileId
    if (!self.isDirectory || self.isReparsePoint || (knownId != null && self.fileId != null && knownId != self.fileId)) {
      return FolderCheck.Stop(fail(cur, OpErrorCode.SOURCE_CHANGED))
    }
    return when (val r = inventory.take(cur.srcShare, cur.srcPath, self.fileId, ctx.purpose, checkCatalog = true)) {
      is FolderInventory.Result.Counted ->
        if (r.bytes > (cur.size ?: 0)) FolderCheck.Stop(fail(cur, OpErrorCode.SOURCE_CHANGED)) else FolderCheck.Ready(r)
      FolderInventory.Result.TooBig -> FolderCheck.Stop(fail(cur, OpErrorCode.SOURCE_CHANGED))
      is FolderInventory.Result.Failed -> FolderCheck.Stop(failOrPause(cur, r.error))
    }
  }

  // --- Ripristina: PLANNED -> RESTORING -> DONE ---------------------------------------------------------------------------------

  private suspend fun restore(item: OpItemRecord, ctx: RunCtx): OpItemRecord {
    var cur = item
    if (cur.state == OpItemState.PLANNED) cur = advance(cur, OpItemState.RESTORING)
    val share = cur.srcShare
    var parentReady = false
    var parentTries = 0
    while (true) {
      val dst = cur.dstPath!!
      if (!parentReady) {
        if (parentTries++ >= MAX_PARENT_TRIES) return fail(cur, OpErrorCode.UNEXPECTED)
        // E-D3: la cartella d'origine sparita si ricrea.
        dst.parent()?.let { parent ->
          when (val r = fileOps.mkdirs(share, parent, ctx.purpose)) {
            is NasResult.Err -> return if (r.error == NasError.Exists) fail(cur, OpErrorCode.NAME_TAKEN) else failOrPause(cur, r.error)
            is NasResult.Ok -> Unit
          }
        }
        parentReady = true
      }
      when (val r = retryWhileFolderInUse(cur, ctx) { fileOps.rename(share, cur.srcPath, dst, null, ctx.purpose) }) {
        // La cartella d'origine e' sparita di nuovo fra la creazione e la rinomina: si ricrea (poche volte).
        is NasResult.Err -> if (r.error == NasError.NotFound) parentReady = false else return failOrPause(cur, r.error)
        is NasResult.Ok -> when (r.value) {
          is RenameOutcome.Renamed -> return restored(cur)
          // Mai sopra un file esistente: nome alternativo, scritto prima di riprovare.
          RenameOutcome.Collision -> cur = withAlternative(cur) ?: return fail(cur, OpErrorCode.NAME_TAKEN)
          RenameOutcome.SourceMissing ->
            return if (alreadyAt(cur, share, dst, ctx.purpose)) restored(cur) else fail(cur, OpErrorCode.SOURCE_MISSING)
          is RenameOutcome.SourceChanged -> return fail(cur, OpErrorCode.SOURCE_CHANGED)
          RenameOutcome.Locked, RenameOutcome.FolderInUse -> return waitLocked(cur)
        }
      }
    }
  }

  private suspend fun restored(cur: OpItemRecord): OpItemRecord {
    // Se la voce e' nel mio diario la segno; le voci degli altri dispositivi non sono mie e non le tocco.
    cur.trashId?.let { id ->
      val entry = store.trashEntry(id)
      if (entry != null && entry.state == TrashEntryState.TRASHED && cur.srcPath == entry.trashedPath) {
        store.markTrash(id, TrashEntryState.RESTORED, nasNow())
      }
    }
    return advance(cur, OpItemState.DONE)
  }

  // --- Elimina subito (Q8): PLANNED -> PURGING -> DONE, solo dal proprio cestino e dal diario locale ---------------------------

  private suspend fun purgeNow(item: OpItemRecord, ctx: RunCtx): OpItemRecord {
    var cur = item
    val trashId = cur.trashId ?: return fail(cur, OpErrorCode.UNEXPECTED)
    // Si rilegge il diario locale: percorso e dimensione vengono da li', mai dal piano o da un record remoto.
    val entry = store.trashEntry(trashId)
    val path = NasLayout.trashItemFile(deviceId, trashId)
    if (entry == null || entry.accountId != accountId || entry.trashedPath != path || cur.srcPath != path) {
      return fail(cur, OpErrorCode.PERMISSION_DENIED)
    }
    when (entry.state) {
      // Gia' eliminata (da questo passo prima della morte del processo, o dalla scadenza): e' cio' che l'utente voleva.
      TrashEntryState.PURGED -> {
        if (cur.state == OpItemState.PLANNED) cur = advance(cur, OpItemState.PURGING)
        return advance(cur, OpItemState.DONE)
      }
      // Ripristinata nel frattempo: non e' piu' nel cestino, non si elimina niente.
      TrashEntryState.RESTORED -> return fail(cur, OpErrorCode.SOURCE_MISSING)
      TrashEntryState.TRASHED -> Unit
    }
    // I6: una voce citata da un'altra operazione aperta (il file non e' ancora nel cestino, o si sta ripristinando) non si elimina.
    if (cur.state == OpItemState.PLANNED && citedElsewhere(trashId, cur.opId)) return fail(cur, OpErrorCode.SOURCE_CHANGED)
    if (cur.state == OpItemState.PLANNED) cur = advance(cur, OpItemState.PURGING)
    return when (val r = purger.purge(entry, ctx.purpose)) {
      OwnTrashPurger.Result.Done -> {
        store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
        advance(cur, OpItemState.DONE)
      }
      OwnTrashPurger.Result.Locked -> waitLocked(cur)
      OwnTrashPurger.Result.Changed -> fail(cur, OpErrorCode.SOURCE_CHANGED)
      is OwnTrashPurger.Result.Error -> failOrPause(cur, r.error)
    }
  }

  /** La voce [trashId] e' citata da un elemento non chiuso di un'altra operazione aperta. */
  private suspend fun citedElsewhere(trashId: String, self: OpId): Boolean =
    store.ops(setOf(OpState.PLANNED, OpState.RUNNING, OpState.PAUSED)).filter { it.id != self }.any { op ->
      store.items(op.id).any { !it.state.terminal && it.trashId == trashId }
    }

  private companion object {
    const val TAG = "NasOps"

    /** Il blocco del ripiego dal telefono (E-D12). */
    const val FALLBACK_CHUNK = 256 * 1024

    /** Nomi alternativi provati prima di fermarsi: `nome (2)` ... `nome (99)`. */
    const val MAX_ALTERNATIVE = 99

    /** Quante volte si ricrea la cartella di destinazione sparita fra la creazione e la rinomina. */
    const val MAX_PARENT_TRIES = 3

    val ALTERNATIVE = Regex("""^(.*) \((\d{1,2})\)$""")
  }
}
