package dev.pampa.mycloudgallery.core.nasops.testing

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationGate
import dev.pampa.mycloudgallery.core.nasops.contract.DoneSummary
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.LockedBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.NameRules
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
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRules
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.PlannedItem
import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.SkippedItem
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * L'esecutore di **riferimento** delle operazioni, sopra le primitive di scrittura ([NasFileOps]), la lettura ([NasMediaReader]) e un
 * diario ([OpsStore]): le macchine a stati del §2.7 per tutte le operazioni, scritte in modo semplice. E' il fake di `NasOperations`
 * con cui P14 (UI), P15 (banco delle 500 interruzioni) e P16 (integrazione) lavorano finche' P12 non consegna quello vero; P12 e'
 * libero di riscriverlo (ha le stesse regole, non lo stesso codice).
 *
 * Cosa fa **uguale** al vero: ogni passo si scrive nel diario prima di farlo e le transizioni passano da [OpStateMachine]
 * (il diario le controlla); la ripresa riparte dal diario con i controlli del §2.7 (risposta persa: sorgente assente e destinazione
 * giusta = fatto); nomi alternativi scritti prima di riprovare; spostamento tra share con copia lato server o ripiego dal telefono,
 * verifica con l'impronta, pubblicazione senza sostituire, sorgente nel cestino solo se il timbro e' ancora quello verificato; file
 * bloccati con attese crescenti; niente parte senza una conferma valida; nessun dato del NAS crea voci del diario.
 *
 * Cosa fa **piu' semplice**: `start` esegue l'operazione fino in fondo (o fino alla prima pausa) prima di rispondere, nessun
 * parallelismo, nessun tetto di rete; una cartella spostata tra share si espande in file (le cartelle vuote non si ricreano e le
 * cartelle sorgente restano, vuote: P12 deve decidere come tracciarle nel diario); `fileCount` delle cartelle cestinate non e'
 * compilato; per una cartella cestinata l'identita' della sorgente non e' confrontata (le cartelle non hanno timbro).
 *
 * I difetti D1-D4 trovati dal banco delle interruzioni (P15, docs/prova-500.md §6) sono corretti come nell'esecutore vero di P12: la
 * lettura a flusso del ripiego che cade mette in pausa (D1); "Ferma" annulla un elemento solo dopo aver tolto il suo temporaneo (D2) e
 * non annulla un elemento VERIFIED il cui temporaneo e' gia' sparito (forse pubblicato, si porta a termine: D3); un errore passeggero
 * mentre si guarda se la pubblicazione e' avvenuta mette in pausa, non fa fallire (D4). Un temporaneo sparito prima della pubblicazione
 * (un "Ferma" perso con la morte del processo) annulla l'elemento invece di farlo fallire.
 */
class InMemoryNasOperations(
  private val deviceId: String,
  private val accountId: String,
  private val fileOps: NasFileOps,
  private val reader: NasMediaReader,
  private val store: OpsStore,
  private val clocks: OpClocks,
  private val gate: OpsGate = OpsGate { GateStatus.OPEN },
  /** Le share in sola lettura per questo account (E-N9). */
  private val readOnly: (String) -> Boolean = { false },
  private val newId: () -> String = { TrustIds.random() },
) : NasOperations {

  private val states = MutableStateFlow<List<NasOperationState>>(emptyList())
  override val operations: StateFlow<List<NasOperationState>> = states

  private val pauseReasons = HashMap<OpId, PauseReason>()
  private val cancelRequests = HashSet<OpId>()
  private var purpose: Purpose = Purpose.INTERACTIVE

  private class PauseSignal(val reason: PauseReason) : RuntimeException(null, null, false, false)

  // =========================================================================================================================
  // Pianificazione: nessuna scrittura, nessun diario
  // =========================================================================================================================

  override suspend fun plan(request: OpRequest): PlanOutcome {
    purpose = Purpose.INTERACTIVE
    return when (request) {
      is OpRequest.CreateFolder -> planCreateFolder(request)
      is OpRequest.Rename -> planRename(request)
      is OpRequest.Move -> planMove(request)
      is OpRequest.Trash -> planTrash(request)
      is OpRequest.Restore -> planRestore(request)
      is OpRequest.PurgeNow -> planPurge(request)
    }
  }

  private fun blocked(share: String): Boolean = gate.statusOf(share).blocksUserOps

  private sealed interface Probe {
    data class Found(val entry: NasEntry) : Probe

    data object Absent : Probe

    data object Unreachable : Probe
  }

  private suspend fun probe(share: String, path: SharePath): Probe = when (val r = reader.stat(share, path, purpose)) {
    is NasResult.Ok -> r.value?.let { Probe.Found(it) } ?: Probe.Absent
    is NasResult.Err -> Probe.Unreachable
  }

  private fun buildPlan(kind: OpKind, items: List<PlannedItem>, skipped: List<SkippedItem>, destShare: String?, destFolder: SharePath?): OpPlan {
    val warnings = buildSet {
      if (items.any { it.flow == ItemFlow.MOVE_ACROSS_SHARES }) add(PlanWarning.CROSS_SHARE_COPY)
      if (kind.permanent) add(PlanWarning.PERMANENT_DELETE)
      if (items.any { it.isFolder }) add(PlanWarning.FOLDER_INCLUDED)
      if (skipped.isNotEmpty()) add(PlanWarning.SOME_SKIPPED)
    }
    val summary = PlanSummary(kind, items.size, items.count { it.isFolder }, items.sumOf { it.size }, destShare, destFolder)
    return OpPlan(kind, items, skipped, summary, warnings)
  }

  private suspend fun planCreateFolder(request: OpRequest.CreateFolder): PlanOutcome {
    val share = request.share
    if (blocked(share)) return PlanOutcome.Rejected(PlanRejection.WRITES_BLOCKED)
    if (readOnly(share)) return PlanOutcome.Rejected(PlanRejection.READ_ONLY)
    if (NameRules.check(request.name) !is NameRules.Check.Valid) return PlanOutcome.Rejected(PlanRejection.INVALID_NAME)
    val path = request.parent?.child(request.name) ?: SharePath.of(request.name)
    if (!NasOpsPaths.isMediaPath(path)) return PlanOutcome.Rejected(PlanRejection.PATH_NOT_ALLOWED)
    when (probe(share, path)) {
      is Probe.Found -> return PlanOutcome.Rejected(PlanRejection.NAME_EXISTS)
      Probe.Unreachable -> return PlanOutcome.Rejected(PlanRejection.NAS_UNREACHABLE)
      Probe.Absent -> Unit
    }
    val item = PlannedItem(0, ItemFlow.CREATE_FOLDER, share, path, null, null, isFolder = true, size = 0, stamp = null)
    return PlanOutcome.Ready(buildPlan(OpKind.CREATE_FOLDER, listOf(item), emptyList(), null, null))
  }

  private suspend fun planRename(request: OpRequest.Rename): PlanOutcome {
    val item = request.item
    if (blocked(item.share)) return PlanOutcome.Rejected(PlanRejection.WRITES_BLOCKED)
    if (readOnly(item.share)) return PlanOutcome.Rejected(PlanRejection.READ_ONLY)
    if (!NasOpsPaths.isMediaPath(item.path)) return PlanOutcome.Rejected(PlanRejection.PATH_NOT_ALLOWED)
    if (NameRules.check(request.newName) !is NameRules.Check.Valid) return PlanOutcome.Rejected(PlanRejection.INVALID_NAME)
    val dst = item.path.parent()?.child(request.newName) ?: SharePath.of(request.newName)
    if (dst == item.path) return PlanOutcome.Rejected(PlanRejection.SAME_LOCATION)
    val caseOnly = item.path.name.equals(request.newName, ignoreCase = true)
    if (!caseOnly) {
      when (probe(item.share, dst)) {
        is Probe.Found -> return PlanOutcome.Rejected(PlanRejection.NAME_EXISTS)
        Probe.Unreachable -> return PlanOutcome.Rejected(PlanRejection.NAS_UNREACHABLE)
        Probe.Absent -> Unit
      }
    }
    when (probe(item.share, item.path)) {
      Probe.Absent -> return PlanOutcome.Rejected(PlanRejection.SOURCE_MISSING)
      Probe.Unreachable -> return PlanOutcome.Rejected(PlanRejection.NAS_UNREACHABLE)
      is Probe.Found -> Unit
    }
    val flow = OpStateMachine.flowOf(OpKind.RENAME, item.share, item.share, item.path, dst)
    val planned = PlannedItem(0, flow, item.share, item.path, item.share, dst, item.isFolder, item.size, item.stamp)
    return PlanOutcome.Ready(buildPlan(OpKind.RENAME, listOf(planned), emptyList(), null, null))
  }

  private suspend fun planMove(request: OpRequest.Move): PlanOutcome {
    if (request.items.isEmpty()) return PlanOutcome.Rejected(PlanRejection.EMPTY)
    if (blocked(request.destShare) || request.items.any { blocked(it.share) }) return PlanOutcome.Rejected(PlanRejection.WRITES_BLOCKED)
    if (readOnly(request.destShare)) return PlanOutcome.Rejected(PlanRejection.READ_ONLY)
    request.destFolder?.let { if (!NasOpsPaths.isMediaPath(it)) return PlanOutcome.Rejected(PlanRejection.PATH_NOT_ALLOWED) }
    val planned = ArrayList<PlannedItem>()
    val skipped = ArrayList<SkippedItem>()
    // R11: i file da lasciare dov'erano (i nascosti, spostando nell'area comune) non diventano elementi: vanno fra gli esclusi.
    val leave = request.leaveBehind.mapTo(HashSet()) { leaveKey(it.share, it.path) }
    for (item in request.items) {
      if (!NasOpsPaths.isMediaPath(item.path)) return PlanOutcome.Rejected(PlanRejection.PATH_NOT_ALLOWED)
      if (readOnly(item.share)) {
        skipped += SkippedItem(item.share, item.path, SkipReason.READ_ONLY)
        continue
      }
      if (!item.isFolder && leaveKey(item.share, item.path) in leave) {
        skipped += SkippedItem(item.share, item.path, SkipReason.LEFT_BEHIND)
        continue
      }
      val dst = request.destFolder?.child(item.path.name) ?: SharePath.of(item.path.name)
      val sameShare = item.share.equals(request.destShare, ignoreCase = true)
      if (sameShare && dst == item.path) {
        skipped += SkippedItem(item.share, item.path, SkipReason.SAME_LOCATION)
        continue
      }
      if (sameShare && item.isFolder && dst.isWithin(item.path)) {
        skipped += SkippedItem(item.share, item.path, SkipReason.INTO_ITSELF)
        continue
      }
      when {
        sameShare -> planned += PlannedItem(planned.size, ItemFlow.RENAME, item.share, item.path, request.destShare, dst, item.isFolder, item.size, item.stamp)
        !item.isFolder -> planned += PlannedItem(
          planned.size, ItemFlow.MOVE_ACROSS_SHARES, item.share, item.path, request.destShare, dst, false, item.size, item.stamp, trashId = newId(),
        )
        else -> if (!expandFolder(item.share, item.path, request.destShare, dst, planned, skipped, leave)) return PlanOutcome.Rejected(PlanRejection.NAS_UNREACHABLE)
      }
      if (planned.size > OpRules.MAX_ITEMS_PER_OP) return PlanOutcome.Rejected(PlanRejection.TOO_MANY_ITEMS)
    }
    if (planned.isEmpty()) {
      return PlanOutcome.Rejected(
        when {
          skipped.any { it.reason == SkipReason.READ_ONLY } -> PlanRejection.READ_ONLY
          skipped.any { it.reason == SkipReason.INTO_ITSELF } -> PlanRejection.INTO_ITSELF
          skipped.any { it.reason == SkipReason.SAME_LOCATION } || skipped.isEmpty() -> PlanRejection.SAME_LOCATION
          else -> PlanRejection.EMPTY
        },
      )
    }
    val crossBytes = planned.filter { it.flow == ItemFlow.MOVE_ACROSS_SHARES }.sumOf { it.size }
    if (planned.any { it.flow == ItemFlow.MOVE_ACROSS_SHARES }) {
      when (val space = fileOps.space(request.destShare, purpose)) {
        is NasResult.Err -> return PlanOutcome.Rejected(PlanRejection.NAS_UNREACHABLE)
        is NasResult.Ok -> if (space.value.callerFreeBytes < OpRules.requiredFreeBytes(crossBytes)) {
          return PlanOutcome.Rejected(PlanRejection.NOT_ENOUGH_SPACE)
        }
      }
    }
    return PlanOutcome.Ready(buildPlan(OpKind.MOVE, planned, skipped, request.destShare, request.destFolder))
  }

  private fun leaveKey(share: String, path: SharePath): String = share.lowercase(Locale.ROOT) + "/" + path.value.lowercase(Locale.ROOT)

  /** Espande una cartella da spostare tra share in un elemento per file (ordine stabile). Falso se il NAS non risponde. */
  private suspend fun expandFolder(
    share: String,
    root: SharePath,
    destShare: String,
    destRoot: SharePath,
    out: MutableList<PlannedItem>,
    skipped: MutableList<SkippedItem>,
    leave: Set<String>,
  ): Boolean {
    val queue = ArrayDeque<Pair<SharePath, SharePath>>()
    queue.add(root to destRoot)
    while (queue.isNotEmpty()) {
      val (dir, dstDir) = queue.removeFirst()
      val entries = ArrayList<NasEntry>()
      if (reader.list(share, dir, purpose) { entries += it } is NasResult.Err) return false
      for (entry in entries.sortedBy { it.name }) {
        // Un nome non valido letto dal NAS si salta (E-N6): non diventa mai un'operazione.
        val src = runCatching { dir.child(entry.name) }.getOrNull() ?: continue
        val dst = runCatching { dstDir.child(entry.name) }.getOrNull() ?: continue
        if (entry.isDirectory) {
          queue.add(src to dst)
        } else if (leaveKey(share, src) in leave) {
          skipped += SkippedItem(share, src, SkipReason.LEFT_BEHIND)
        } else {
          out += PlannedItem(
            out.size, ItemFlow.MOVE_ACROSS_SHARES, share, src, destShare, dst, false, entry.size,
            NasStamp(entry.size, entry.modifiedAt, entry.fileId), trashId = newId(),
          )
        }
      }
    }
    return true
  }

  private suspend fun planTrash(request: OpRequest.Trash): PlanOutcome {
    if (request.items.isEmpty()) return PlanOutcome.Rejected(PlanRejection.EMPTY)
    if (request.items.any { blocked(it.share) }) return PlanOutcome.Rejected(PlanRejection.WRITES_BLOCKED)
    val planned = ArrayList<PlannedItem>()
    val skipped = ArrayList<SkippedItem>()
    for (item in request.items) {
      if (!NasOpsPaths.isMediaPath(item.path)) return PlanOutcome.Rejected(PlanRejection.PATH_NOT_ALLOWED)
      if (readOnly(item.share)) {
        skipped += SkippedItem(item.share, item.path, SkipReason.READ_ONLY)
        continue
      }
      when (probe(item.share, item.path)) {
        Probe.Unreachable -> return PlanOutcome.Rejected(PlanRejection.NAS_UNREACHABLE)
        Probe.Absent -> skipped += SkippedItem(item.share, item.path, SkipReason.SOURCE_MISSING)
        is Probe.Found ->
          planned += PlannedItem(planned.size, ItemFlow.TRASH, item.share, item.path, null, null, item.isFolder, item.size, item.stamp, trashId = newId())
      }
    }
    if (planned.size > OpRules.MAX_ITEMS_PER_OP) return PlanOutcome.Rejected(PlanRejection.TOO_MANY_ITEMS)
    if (planned.isEmpty()) {
      return PlanOutcome.Rejected(if (skipped.any { it.reason == SkipReason.READ_ONLY }) PlanRejection.READ_ONLY else PlanRejection.SOURCE_MISSING)
    }
    return PlanOutcome.Ready(buildPlan(OpKind.TRASH, planned, skipped, null, null))
  }

  private suspend fun planRestore(request: OpRequest.Restore): PlanOutcome {
    if (request.entries.isEmpty()) return PlanOutcome.Rejected(PlanRejection.EMPTY)
    if (request.entries.any { blocked(it.share) }) return PlanOutcome.Rejected(PlanRejection.WRITES_BLOCKED)
    val planned = ArrayList<PlannedItem>()
    val skipped = ArrayList<SkippedItem>()
    for (ref in request.entries) {
      if (!NasOpsPaths.isMediaPath(ref.originalPath)) return PlanOutcome.Rejected(PlanRejection.PATH_NOT_ALLOWED)
      val trashed = NasLayout.trashItemFile(ref.ownerDeviceId, ref.trashId)
      if (readOnly(ref.share)) {
        skipped += SkippedItem(ref.share, ref.originalPath, SkipReason.READ_ONLY)
        continue
      }
      // Un record finto o sparito: se il file non e' davvero nel cestino non c'e' niente da ripristinare.
      when (probe(ref.share, trashed)) {
        Probe.Unreachable -> return PlanOutcome.Rejected(PlanRejection.NAS_UNREACHABLE)
        Probe.Absent -> skipped += SkippedItem(ref.share, ref.originalPath, SkipReason.SOURCE_MISSING)
        is Probe.Found ->
          planned += PlannedItem(planned.size, ItemFlow.RESTORE, ref.share, trashed, ref.share, ref.originalPath, ref.isFolder, ref.size, null, trashId = ref.trashId)
      }
    }
    if (planned.isEmpty()) {
      return PlanOutcome.Rejected(if (skipped.any { it.reason == SkipReason.READ_ONLY }) PlanRejection.READ_ONLY else PlanRejection.SOURCE_MISSING)
    }
    return PlanOutcome.Ready(buildPlan(OpKind.RESTORE, planned, skipped, null, null))
  }

  private suspend fun planPurge(request: OpRequest.PurgeNow): PlanOutcome {
    if (request.entries.isEmpty()) return PlanOutcome.Rejected(PlanRejection.EMPTY)
    val planned = ArrayList<PlannedItem>()
    for (ref in request.entries) {
      // Solo il diario locale dice cosa e' mio. Un id che non c'e', o non e' piu' nel cestino, o non e' di questo account: niente.
      val entry = store.trashEntry(ref.trashId)
      if (entry == null || entry.state != TrashEntryState.TRASHED || entry.accountId != accountId) return PlanOutcome.Rejected(PlanRejection.NOT_MINE)
      if (entry.trashedPath != NasLayout.trashItemFile(deviceId, ref.trashId)) return PlanOutcome.Rejected(PlanRejection.NOT_MINE)
      if (blocked(entry.shareName)) return PlanOutcome.Rejected(PlanRejection.WRITES_BLOCKED)
      planned += PlannedItem(planned.size, ItemFlow.PURGE, entry.shareName, entry.trashedPath, null, null, entry.isFolder, entry.size, null, trashId = ref.trashId)
    }
    return PlanOutcome.Ready(buildPlan(OpKind.PURGE_NOW, planned, emptyList(), null, null))
  }

  // =========================================================================================================================
  // Avvio, annullamento, ripresa
  // =========================================================================================================================

  override suspend fun start(plan: OpPlan, confirmation: UserConfirmation): StartOutcome {
    val accepted = when (val outcome = ConfirmationGate.accept(plan, confirmation, clocks.monotonicMs())) {
      is AcceptOutcome.Rejected -> return StartOutcome.ConfirmationRejected(outcome.check)
      is AcceptOutcome.Accepted -> outcome.accepted
    }
    PlanRules.firstViolation(plan, deviceId)?.let { return StartOutcome.PlanInvalid(it) }
    store.opByPlanHash(plan.hash)?.let { return StartOutcome.AlreadyStarted(it.id) }
    if (plan.items.any { blocked(it.srcShare) || it.dstShare?.let(::blocked) == true }) return StartOutcome.WritesBlocked
    val nasNow = clocks.nasNowMs() ?: return StartOutcome.WritesBlocked
    val record = store.create(accepted, OpId(newId()), accountId, nasNow)
    refreshStates()
    purpose = Purpose.INTERACTIVE
    runOp(record.id, Long.MAX_VALUE)
    return StartOutcome.Started(record.id)
  }

  override suspend fun cancel(id: OpId) {
    val op = store.op(id) ?: return
    if (op.state.terminal) return
    cancelRequests += id
    try {
      for (item in store.items(id)) {
        if (!OpStateMachine.cancellable(item.flow, item.state)) continue
        when (tempGone(item)) {
          TempCheck.GONE -> persist(item.copy(state = OpItemState.CANCELLED, errorCode = null, nextAttemptAtNas = null))
          // D3 (P15): un elemento VERIFIED senza temporaneo puo' essere gia' pubblicato (risposta persa): si porta a termine.
          TempCheck.MAYBE_PUBLISHED -> Unit
          // D2 (P15): il temporaneo non si e' potuto togliere: niente si annulla (nessun temporaneo che il diario non cita), si riprova.
          TempCheck.UNREACHABLE -> {
            pause(id, PauseReason.NAS_UNREACHABLE)
            return
          }
        }
      }
      // Gli elementi oltre il punto di non ritorno (copia gia' pubblicata) si portano a termine: niente doppioni visibili.
      runOp(id, Long.MAX_VALUE)
    } finally {
      cancelRequests -= id
    }
  }

  private enum class TempCheck { GONE, MAYBE_PUBLISHED, UNREACHABLE }

  /** Il temporaneo proprio di un elemento da annullare non c'e' piu' (tolto adesso o mai creato)? Vedi D2 e D3 in docs/prova-500.md. */
  private suspend fun tempGone(item: OpItemRecord): TempCheck {
    if (item.state == OpItemState.PLANNED) return TempCheck.GONE
    val temp = item.tempName?.takeIf { NasOpsPaths.isTempOf(it, deviceId) } ?: return TempCheck.GONE
    return when (val r = fileOps.deleteOwnTemp(item.dstShare ?: item.srcShare, temp, purpose)) {
      is NasResult.Err -> TempCheck.UNREACHABLE
      is NasResult.Ok -> when (r.value) {
        DeleteOutcome.Deleted -> TempCheck.GONE
        DeleteOutcome.Missing -> if (item.state == OpItemState.VERIFIED) TempCheck.MAYBE_PUBLISHED else TempCheck.GONE
        else -> TempCheck.UNREACHABLE
      }
    }
  }

  override suspend fun resumeAll(budget: Duration): RunResult {
    purpose = Purpose.BACKGROUND
    // Dopo la morte del processo lo stato mostrato si ricostruisce dal diario (quello vero lo fa da `observeOps`).
    refreshStates()
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
      val run = runOp(op.id, deadline)
      done += run.done
      failed += run.failed
      waiting += run.waiting
      if (run.stop == RunStop.NAS_UNREACHABLE || run.stop == RunStop.BLOCKED || run.stop == RunStop.BUDGET) {
        stop = run.stop
        break
      }
      if (run.stop == RunStop.WAITING_LOCKED) stop = RunStop.WAITING_LOCKED
    }
    return RunResult(resumed, done, failed, waiting, stop)
  }

  private class OpRun(val done: Int, val failed: Int, val waiting: Int, val stop: RunStop)

  /** Esegue gli elementi pronti di [id] in ordine, fino alla fine, a una pausa o alla scadenza di [deadlineMonotonic]. */
  private suspend fun runOp(id: OpId, deadlineMonotonic: Long): OpRun {
    val op = store.op(id) ?: return OpRun(0, 0, 0, RunStop.COMPLETE)
    if (op.state.terminal) return OpRun(0, 0, 0, RunStop.COMPLETE)
    store.setOpState(id, OpState.RUNNING, null, clocks.nasNowMs() ?: op.updatedAtNas)
    pauseReasons.remove(id)
    refreshStates()
    var stop = RunStop.COMPLETE
    try {
      for (snapshot in store.items(id)) {
        if (snapshot.state.terminal) continue
        if (clocks.monotonicMs() >= deadlineMonotonic) {
          stop = RunStop.BUDGET
          pause(id, PauseReason.CONDITIONS)
          break
        }
        val nasNow = nasNow()
        val gateStatus = listOfNotNull(snapshot.srcShare, snapshot.dstShare).map { gate.statusOf(it) }.firstOrNull { it.blocksUserOps }
        if (gateStatus != null) {
          stop = RunStop.BLOCKED
          pause(id, if (gateStatus == GateStatus.FROZEN) PauseReason.FROZEN else PauseReason.WRITES_STOPPED)
          break
        }
        if (snapshot.nextAttemptAtNas != null && snapshot.nextAttemptAtNas > nasNow) continue
        runItem(snapshot)
      }
    } catch (p: PauseSignal) {
      stop = if (p.reason == PauseReason.NAS_UNREACHABLE) RunStop.NAS_UNREACHABLE else RunStop.BLOCKED
      pause(id, p.reason)
    }
    val items = store.items(id)
    val doneCount = items.count { it.state == OpItemState.DONE }
    val failedCount = items.count { it.state == OpItemState.FAILED }
    val waitingCount = items.count { it.waitingLocked }
    if (items.all { it.state.terminal }) {
      val finalState = when {
        items.any { it.state == OpItemState.CANCELLED } -> OpState.CANCELLED
        failedCount == items.size -> OpState.FAILED
        else -> OpState.DONE
      }
      val code = if (finalState == OpState.FAILED) items.firstOrNull { it.errorCode != null }?.errorCode else null
      store.setOpState(id, finalState, code, clocks.nasNowMs() ?: op.updatedAtNas)
    } else if (stop == RunStop.COMPLETE && waitingCount > 0) {
      stop = RunStop.WAITING_LOCKED
      store.setOpState(id, OpState.PAUSED, OpErrorCode.LOCKED, clocks.nasNowMs() ?: op.updatedAtNas)
    }
    refreshStates()
    return OpRun(doneCount, failedCount, waitingCount, stop)
  }

  private suspend fun pause(id: OpId, reason: PauseReason) {
    pauseReasons[id] = reason
    // Come l'esecutore vero (prova F16): il motivo della pausa nel diario.
    store.setOpState(id, OpState.PAUSED, null, clocks.nasNowMs() ?: 0L, reason)
    refreshStates()
  }

  // =========================================================================================================================
  // Gli elementi: una macchina a stati per cammino
  // =========================================================================================================================

  private suspend fun nasNow(): Long = clocks.nasNowMs() ?: throw PauseSignal(PauseReason.NAS_UNREACHABLE)

  private suspend fun persist(item: OpItemRecord): OpItemRecord {
    val saved = item.copy(updatedAtNas = nasNow())
    store.updateItem(saved)
    refreshStates()
    return saved
  }

  /** Passa al passo [state] (scrive il diario). Azzera l'attesa: un passo avanti significa che il blocco e' passato. */
  private suspend fun advanceTo(item: OpItemRecord, state: OpItemState, edit: (OpItemRecord) -> OpItemRecord = { it }): OpItemRecord =
    persist(edit(item).copy(state = state, errorCode = null, nextAttemptAtNas = null))

  private suspend fun fail(item: OpItemRecord, code: OpErrorCode): OpItemRecord =
    persist(item.copy(state = OpItemState.FAILED, errorCode = code, nextAttemptAtNas = null))

  /** Il file e' aperto dal PC (E-N13): attesa crescente fino a 24 ore, poi si ferma. */
  private suspend fun waitLocked(item: OpItemRecord): OpItemRecord = when (val decision = LockedBackoff.decide(item.attempts)) {
    is LockedBackoff.Decision.RetryAfter ->
      persist(item.copy(attempts = item.attempts + 1, nextAttemptAtNas = nasNow() + decision.delayMs, errorCode = OpErrorCode.LOCKED))
    LockedBackoff.Decision.GiveUp -> fail(item, OpErrorCode.LOCKED_TOO_LONG)
  }

  /** Un errore del NAS: fallimento definitivo dell'elemento, attesa di un blocco, o pausa dell'operazione (rete). */
  private suspend fun failOrPause(item: OpItemRecord, error: NasError): OpItemRecord = when (error) {
    NasError.PermissionDenied -> fail(item, OpErrorCode.PERMISSION_DENIED)
    NasError.NasFull -> fail(item, OpErrorCode.NO_SPACE)
    NasError.QuotaExceeded -> fail(item, OpErrorCode.QUOTA)
    NasError.FileBusy -> waitLocked(item)
    is NasError.ShareMissing -> fail(item, OpErrorCode.UNREACHABLE)
    NasError.Exists, NasError.NotFound, is NasError.Unexpected -> fail(item, OpErrorCode.UNEXPECTED)
    else -> throw PauseSignal(PauseReason.NAS_UNREACHABLE)
  }

  private suspend fun runItem(item: OpItemRecord): OpItemRecord = when (item.flow) {
    ItemFlow.CREATE_FOLDER -> runCreateFolder(item)
    ItemFlow.RENAME -> runRename(item)
    ItemFlow.RENAME_CASE_ONLY -> runCaseOnlyRename(item)
    ItemFlow.MOVE_ACROSS_SHARES -> runMoveAcrossShares(item)
    ItemFlow.TRASH -> runTrash(item)
    ItemFlow.RESTORE -> runRestore(item)
    ItemFlow.PURGE -> runPurge(item)
  }

  // --- Crea cartella: PLANNED -> DONE ----------------------------------------------------------------------------------------

  private suspend fun runCreateFolder(item: OpItemRecord): OpItemRecord =
    when (val r = fileOps.mkdirs(item.srcShare, item.srcPath, purpose)) {
      is NasResult.Err -> if (r.error == NasError.Exists) fail(item, OpErrorCode.NAME_TAKEN) else failOrPause(item, r.error)
      is NasResult.Ok -> advanceTo(item, OpItemState.DONE)
    }

  // --- Rinomina e sposta nella stessa share: PLANNED -> RENAMING -> DONE ------------------------------------------------------

  private suspend fun runRename(item: OpItemRecord): OpItemRecord {
    var cur = item
    if (cur.state == OpItemState.PLANNED) cur = advanceTo(cur, OpItemState.RENAMING)
    val baseName = cur.dstPath!!.name
    cur.dstPath!!.parent()?.let { parent ->
      (fileOps.mkdirs(cur.srcShare, parent, purpose) as? NasResult.Err)?.let { return failOrPause(cur, it.error) }
    }
    var attempt = 2
    while (true) {
      val dst = cur.dstPath!!
      when (val r = fileOps.rename(cur.srcShare, cur.srcPath, dst, cur.stamp, purpose)) {
        is NasResult.Err -> return failOrPause(cur, r.error)
        is NasResult.Ok -> when (r.value) {
          is RenameOutcome.Renamed -> return advanceTo(cur, OpItemState.DONE)
          RenameOutcome.Collision -> {
            if (attempt > MAX_ALTERNATIVES) return fail(cur, OpErrorCode.NAME_TAKEN)
            val alternative = NameRules.alternative(baseName, attempt++)
            // Il nome alternativo si scrive nel diario PRIMA di riprovare: una ripresa non ne sceglie un altro (§2.7).
            cur = persist(cur.copy(dstPath = dst.parent()?.child(alternative) ?: SharePath.of(alternative)))
          }
          RenameOutcome.SourceMissing ->
            return if (alreadyAt(cur, dst)) advanceTo(cur, OpItemState.DONE) else fail(cur, OpErrorCode.SOURCE_MISSING)
          is RenameOutcome.SourceChanged -> return fail(cur, OpErrorCode.SOURCE_CHANGED)
          RenameOutcome.Locked -> return waitLocked(cur)
        }
      }
    }
  }

  /** La destinazione [dst] c'e' ed e' l'elemento spostato (risposta persa, §2.7): stessa natura, stessa dimensione, stesso `fileId`. */
  private suspend fun alreadyAt(item: OpItemRecord, dst: SharePath): Boolean {
    val entry = when (val found = probe(item.dstShare ?: item.srcShare, dst)) {
      is Probe.Found -> found.entry
      Probe.Absent -> return false
      Probe.Unreachable -> throw PauseSignal(PauseReason.NAS_UNREACHABLE)
    }
    if (entry.isDirectory != item.isFolder) return false
    if (item.isFolder) return true
    return entry.size == (item.size ?: entry.size) && (item.stamp?.fileId == null || entry.fileId == item.stamp.fileId)
  }

  // --- Rinomina solo di maiuscole: PLANNED -> TEMP_NAME -> DONE ---------------------------------------------------------------

  private suspend fun runCaseOnlyRename(item: OpItemRecord): OpItemRecord {
    val share = item.srcShare
    val src = item.srcPath
    val dst = item.dstPath!!
    val tmp = item.tempName ?: OpTemps.caseSwapName(src, item.opId, item.index)
    val cur = if (item.state == OpItemState.PLANNED) advanceTo(item, OpItemState.TEMP_NAME) { it.copy(tempName = tmp) } else item
    val atTemp = probe(share, tmp)
    val atSource = probe(share, src)
    if (atTemp == Probe.Unreachable || atSource == Probe.Unreachable) throw PauseSignal(PauseReason.NAS_UNREACHABLE)
    if (atTemp !is Probe.Found) {
      val entry = (atSource as? Probe.Found)?.entry ?: return fail(cur, OpErrorCode.SOURCE_MISSING)
      // Il nome e' gia' quello finale: la seconda rinomina e' stata fatta (risposta persa).
      if (entry.name == dst.name) return advanceTo(cur, OpItemState.DONE)
      when (val r = fileOps.rename(share, src, tmp, cur.stamp, purpose)) {
        is NasResult.Err -> return failOrPause(cur, r.error)
        is NasResult.Ok -> when (r.value) {
          is RenameOutcome.Renamed -> Unit
          RenameOutcome.Locked -> return waitLocked(cur)
          is RenameOutcome.SourceChanged -> return fail(cur, OpErrorCode.SOURCE_CHANGED)
          RenameOutcome.SourceMissing -> return fail(cur, OpErrorCode.SOURCE_MISSING)
          RenameOutcome.Collision -> return fail(cur, OpErrorCode.UNEXPECTED)
        }
      }
    }
    when (val r = fileOps.rename(share, tmp, dst, null, purpose)) {
      is NasResult.Err -> return failOrPause(cur, r.error)
      is NasResult.Ok -> when (r.value) {
        is RenameOutcome.Renamed -> return advanceTo(cur, OpItemState.DONE)
        RenameOutcome.Locked -> return waitLocked(cur)
        RenameOutcome.Collision -> {
          // Qualcuno ha preso il nome finale: si rimette il nome di prima (se riesce) e ci si ferma.
          fileOps.rename(share, tmp, src, null, purpose)
          return fail(cur, OpErrorCode.NAME_TAKEN)
        }
        RenameOutcome.SourceMissing -> return if (alreadyAt(cur, dst)) advanceTo(cur, OpItemState.DONE) else fail(cur, OpErrorCode.SOURCE_MISSING)
        is RenameOutcome.SourceChanged -> return fail(cur, OpErrorCode.SOURCE_CHANGED)
      }
    }
  }

  // --- Sposta tra share: PLANNED -> COPYING -> COPIED -> VERIFIED -> PUBLISHED -> SOURCE_RECORDED -> SOURCE_TRASHED -> DONE -----

  private sealed interface Step {
    data class Next(val item: OpItemRecord) : Step

    data class Stop(val item: OpItemRecord) : Step
  }

  private suspend fun runMoveAcrossShares(item: OpItemRecord): OpItemRecord {
    var cur = item
    val steps = ItemFlow.MOVE_ACROSS_SHARES.steps
    val temp = cur.tempName ?: OpTemps.tempFor(deviceId, cur.opId, cur.index)
    fun reached(state: OpItemState): Boolean = steps.indexOf(cur.state) >= steps.indexOf(state)
    if (!reached(OpItemState.COPIED)) {
      if (cur.state == OpItemState.PLANNED) cur = advanceTo(cur, OpItemState.COPYING) { it.copy(tempName = temp) }
      when (val s = copyPhase(cur, temp)) {
        is Step.Next -> cur = s.item
        is Step.Stop -> return s.item
      }
    }
    if (!reached(OpItemState.VERIFIED)) {
      when (val s = verifyPhase(cur, temp)) {
        is Step.Next -> cur = s.item
        is Step.Stop -> return s.item
      }
    }
    if (!reached(OpItemState.PUBLISHED)) {
      when (val s = publishPhase(cur, temp)) {
        is Step.Next -> cur = s.item
        is Step.Stop -> return s.item
      }
    }
    if (!reached(OpItemState.SOURCE_RECORDED)) {
      when (val s = recordSourcePhase(cur)) {
        is Step.Next -> cur = s.item
        is Step.Stop -> return s.item
      }
    }
    if (!reached(OpItemState.SOURCE_TRASHED)) {
      when (val s = trashSourcePhase(cur)) {
        is Step.Next -> cur = s.item
        is Step.Stop -> return s.item
      }
    }
    return advanceTo(cur, OpItemState.DONE)
  }

  private suspend fun copyPhase(cur: OpItemRecord, temp: SharePath): Step {
    val dstShare = cur.dstShare!!
    // Ripresa: un temporaneo proprio con la dimensione giusta e' una copia gia' fatta; altrimenti si toglie e si rifa' (§2.7).
    when (val existing = probe(dstShare, temp)) {
      Probe.Unreachable -> throw PauseSignal(PauseReason.NAS_UNREACHABLE)
      is Probe.Found -> {
        if (!existing.entry.isDirectory && existing.entry.size == cur.size) return Step.Next(advanceTo(cur, OpItemState.COPIED))
        (fileOps.deleteOwnTemp(dstShare, temp, purpose) as? NasResult.Err)?.let { return Step.Stop(failOrPause(cur, it.error)) }
      }
      Probe.Absent -> Unit
    }
    return when (val r = fileOps.copyToTemp(cur.srcShare, cur.srcPath, dstShare, temp, cur.stamp, purpose)) {
      is NasResult.Err -> Step.Stop(failOrPause(cur, r.error))
      is NasResult.Ok -> when (r.value) {
        is CopyOutcome.Copied -> Step.Next(advanceTo(cur, OpItemState.COPIED))
        CopyOutcome.SourceMissing -> Step.Stop(fail(cur, OpErrorCode.SOURCE_MISSING))
        is CopyOutcome.SourceChanged -> Step.Stop(fail(cur, OpErrorCode.SOURCE_CHANGED))
        CopyOutcome.TempExists -> Step.Stop(fail(cur, OpErrorCode.UNEXPECTED))
        CopyOutcome.Locked -> Step.Stop(waitLocked(cur))
        // Copia lato server rifiutata: ripiego dal telefono (E-D12).
        CopyOutcome.NotSupported -> fallbackCopy(cur, temp)
      }
    }
  }

  /** Il ripiego dal telefono: legge la sorgente a blocchi e la scrive nel temporaneo (E-D12). */
  private suspend fun fallbackCopy(cur: OpItemRecord, temp: SharePath): Step {
    val dstShare = cur.dstShare!!
    val file = when (val opened = reader.open(cur.srcShare, cur.srcPath, purpose)) {
      is NasResult.Err -> return Step.Stop(if (opened.error == NasError.NotFound) fail(cur, OpErrorCode.SOURCE_MISSING) else failOrPause(cur, opened.error))
      is NasResult.Ok -> opened.value
    }
    try {
      if (cur.stamp != null && !file.stamp.sameAs(cur.stamp)) return Step.Stop(fail(cur, OpErrorCode.SOURCE_CHANGED))
      val writer = when (val opened = fileOps.openTemp(dstShare, temp, purpose)) {
        is NasResult.Err -> return Step.Stop(failOrPause(cur, opened.error))
        is NasResult.Ok -> opened.value
      }
      try {
        val buffer = ByteArray(FALLBACK_CHUNK)
        var offset = 0L
        while (offset < file.size) {
          // D1 (P15): una lettura a flusso che cade si traduce nel suo errore (pausa), non esce come eccezione.
          val n = try {
            file.read(offset, buffer, 0, buffer.size)
          } catch (e: NasIoException) {
            return Step.Stop(failOrPause(cur, e.error))
          }
          if (n <= 0) break
          (writer.write(offset, buffer, 0, n) as? NasResult.Err)?.let { return Step.Stop(failOrPause(cur, it.error)) }
          offset += n
        }
        return when (val finished = writer.finish(file.size)) {
          is NasResult.Err -> Step.Stop(failOrPause(cur, finished.error))
          is NasResult.Ok -> when (finished.value) {
            is TempFinish.Complete -> Step.Next(advanceTo(cur, OpItemState.COPIED))
            is TempFinish.SizeMismatch -> {
              fileOps.deleteOwnTemp(dstShare, temp, purpose)
              Step.Stop(fail(cur, OpErrorCode.VERIFY_FAILED))
            }
          }
        }
      } finally {
        writer.close()
      }
    } finally {
      file.close()
    }
  }

  private sealed interface Hashed {
    data class Ok(val sha: Sha256, val stamp: NasStamp) : Hashed

    data object Missing : Hashed

    data class Error(val error: NasError) : Hashed
  }

  /** L'impronta SHA-256 di un file del NAS e il suo timbro all'apertura. */
  private suspend fun hashOf(share: String, path: SharePath): Hashed {
    val file = when (val opened = reader.open(share, path, purpose)) {
      is NasResult.Err -> return if (opened.error == NasError.NotFound) Hashed.Missing else Hashed.Error(opened.error)
      is NasResult.Ok -> opened.value
    }
    try {
      return when (val sha = reader.readAll(file, { _, _ -> }, null)) {
        is NasResult.Err -> Hashed.Error(sha.error)
        is NasResult.Ok -> Hashed.Ok(sha.value, file.stamp)
      }
    } finally {
      file.close()
    }
  }

  private suspend fun verifyPhase(cur: OpItemRecord, temp: SharePath): Step {
    val dstShare = cur.dstShare!!
    val source = when (val h = hashOf(cur.srcShare, cur.srcPath)) {
      Hashed.Missing -> {
        fileOps.deleteOwnTemp(dstShare, temp, purpose)
        return Step.Stop(fail(cur, OpErrorCode.SOURCE_MISSING))
      }
      is Hashed.Error -> return Step.Stop(failOrPause(cur, h.error))
      is Hashed.Ok -> h
    }
    // E-D13: la sorgente non deve essere cambiata mentre la si copiava. Si ferma l'elemento, si toglie la copia (propria), nessun file
    // va nel cestino: la sorgente resta dov'e'.
    if (cur.stamp != null && !source.stamp.sameAs(cur.stamp)) {
      fileOps.deleteOwnTemp(dstShare, temp, purpose)
      return Step.Stop(fail(cur, OpErrorCode.SOURCE_CHANGED))
    }
    val copy = when (val h = hashOf(dstShare, temp)) {
      // Prima della pubblicazione il temporaneo proprio lo toglie solo un "Ferma" perso con la morte del processo: annullato.
      Hashed.Missing -> return Step.Stop(advanceTo(cur, OpItemState.CANCELLED))
      is Hashed.Error -> return Step.Stop(failOrPause(cur, h.error))
      is Hashed.Ok -> h
    }
    if (copy.sha != source.sha) {
      fileOps.deleteOwnTemp(dstShare, temp, purpose)
      return Step.Stop(fail(cur, OpErrorCode.VERIFY_FAILED))
    }
    return Step.Next(advanceTo(cur, OpItemState.VERIFIED) { it.copy(sha256 = source.sha, stamp = source.stamp) })
  }

  private suspend fun publishPhase(item: OpItemRecord, temp: SharePath): Step {
    var cur = item
    val dstShare = cur.dstShare!!
    val baseName = cur.dstPath!!.name
    var attempt = 2
    while (true) {
      val dst = cur.dstPath!!
      when (val r = fileOps.publishTemp(dstShare, temp, dst, purpose)) {
        is NasResult.Err -> return Step.Stop(failOrPause(cur, r.error))
        is NasResult.Ok -> when (r.value) {
          is PublishOutcome.Published -> return Step.Next(advanceTo(cur, OpItemState.PUBLISHED))
          PublishOutcome.Collision -> {
            if (attempt > MAX_ALTERNATIVES) return Step.Stop(fail(cur, OpErrorCode.NAME_TAKEN))
            val alternative = NameRules.alternative(baseName, attempt++)
            cur = persist(cur.copy(dstPath = dst.parent()?.child(alternative) ?: SharePath.of(alternative)))
          }
          PublishOutcome.Locked -> return Step.Stop(waitLocked(cur))
          // Il temporaneo non c'e' piu': o e' stato pubblicato e la risposta si e' persa, o e' sparito. Si guarda la destinazione.
          PublishOutcome.TempMissing -> return Step.Stop(
            when {
              publishedByThisOp(cur, dst) -> advanceTo(cur, OpItemState.PUBLISHED)
              // Nessun file a quel nome: il temporaneo l'ha tolto un "Ferma" perso con la morte del processo.
              probeOrPause(dstShare, dst) == Probe.Absent -> advanceTo(cur, OpItemState.CANCELLED)
              else -> fail(cur, OpErrorCode.UNEXPECTED)
            },
          )
        }
      }
    }
  }

  /** Destinazione con la dimensione e l'impronta del file verificato: la pubblicazione e' avvenuta (§2.7). */
  private suspend fun publishedByThisOp(cur: OpItemRecord, dst: SharePath): Boolean {
    val entry = (probeOrPause(cur.dstShare!!, dst) as? Probe.Found)?.entry ?: return false
    if (entry.isDirectory || entry.size != cur.size) return false
    return when (val hashed = hashOf(cur.dstShare, dst)) {
      is Hashed.Ok -> hashed.sha == cur.sha256
      Hashed.Missing -> false
      // D4 (P15): un errore passeggero mentre si guarda la destinazione non dice "non pubblicato": si riprova.
      is Hashed.Error -> if (transient(hashed.error)) throw PauseSignal(PauseReason.NAS_UNREACHABLE) else false
    }
  }

  /** Come [probe], ma un errore passeggero mette in pausa invece di valere "non si sa" (D4). */
  private suspend fun probeOrPause(share: String, path: SharePath): Probe = when (val r = reader.stat(share, path, purpose)) {
    is NasResult.Ok -> r.value?.let { Probe.Found(it) } ?: Probe.Absent
    is NasResult.Err -> if (r.error == NasError.NotFound) Probe.Absent else if (transient(r.error)) throw PauseSignal(PauseReason.NAS_UNREACHABLE) else Probe.Unreachable
  }

  /** Gli errori che [failOrPause] tratta come rete (pausa), non come esito. */
  private fun transient(error: NasError): Boolean = when (error) {
    NasError.PermissionDenied, NasError.NasFull, NasError.QuotaExceeded, NasError.FileBusy, NasError.Exists, NasError.NotFound -> false
    is NasError.ShareMissing, is NasError.Unexpected -> false
    else -> true
  }

  private suspend fun recordSourcePhase(cur: OpItemRecord): Step {
    val trashId = cur.trashId ?: return Step.Stop(fail(cur, OpErrorCode.UNEXPECTED))
    // La voce del diario locale del cestino si scrive PRIMA della rinomina (§2.7); un record senza file e' innocuo.
    store.recordTrash(
      LocalTrashRecord(
        trashId = trashId,
        accountId = accountId,
        shareKey = cur.srcShare.lowercase(),
        shareName = cur.srcShare,
        originalPath = cur.srcPath,
        trashedPath = NasLayout.trashItemFile(deviceId, trashId),
        reason = TrashReason.MOVE,
        isFolder = false,
        size = cur.size ?: 0,
        fileCount = null,
        trashedAtNas = nasNow(),
        state = TrashEntryState.TRASHED,
        opId = cur.opId,
        updatedAtNas = nasNow(),
      ),
    )
    return Step.Next(advanceTo(cur, OpItemState.SOURCE_RECORDED))
  }

  private suspend fun trashSourcePhase(cur: OpItemRecord): Step {
    val trashId = cur.trashId!!
    val trashed = NasLayout.trashItemFile(deviceId, trashId)
    return when (val r = fileOps.trashMove(cur.srcShare, cur.srcPath, trashId, cur.stamp, purpose)) {
      is NasResult.Err -> Step.Stop(failOrPause(cur, r.error))
      is NasResult.Ok -> when (r.value) {
        is RenameOutcome.Renamed -> Step.Next(advanceTo(cur, OpItemState.SOURCE_TRASHED))
        RenameOutcome.SourceMissing -> when (probe(cur.srcShare, trashed)) {
          // La rinomina c'e' stata e la risposta si e' persa.
          is Probe.Found -> Step.Next(advanceTo(cur, OpItemState.SOURCE_TRASHED))
          Probe.Unreachable -> throw PauseSignal(PauseReason.NAS_UNREACHABLE)
          // La sorgente e' sparita per altra via dopo la pubblicazione: la copia e' a posto, nel cestino non c'e' niente.
          Probe.Absent -> {
            store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
            Step.Next(advanceTo(cur, OpItemState.SOURCE_TRASHED))
          }
        }
        // E-D13: la sorgente e' cambiata dopo la verifica. Nessun file nel cestino, entrambe le copie restano, l'utente lo vede.
        is RenameOutcome.SourceChanged -> {
          store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
          Step.Stop(fail(cur, OpErrorCode.SOURCE_CHANGED))
        }
        RenameOutcome.Locked -> Step.Stop(waitLocked(cur))
        RenameOutcome.Collision -> Step.Stop(fail(cur, OpErrorCode.UNEXPECTED))
      }
    }
  }

  // --- Elimina: PLANNED -> RECORDED -> TRASHED -> DONE --------------------------------------------------------------------------

  private suspend fun runTrash(item: OpItemRecord): OpItemRecord {
    var cur = item
    val trashId = cur.trashId ?: return fail(cur, OpErrorCode.UNEXPECTED)
    val trashed = NasLayout.trashItemFile(deviceId, trashId)
    if (cur.state == OpItemState.PLANNED) {
      store.recordTrash(
        LocalTrashRecord(
          trashId = trashId,
          accountId = accountId,
          shareKey = cur.srcShare.lowercase(),
          shareName = cur.srcShare,
          originalPath = cur.srcPath,
          trashedPath = trashed,
          reason = TrashReason.DELETE,
          isFolder = cur.isFolder,
          size = cur.size ?: 0,
          fileCount = null,
          trashedAtNas = nasNow(),
          state = TrashEntryState.TRASHED,
          opId = cur.opId,
          updatedAtNas = nasNow(),
        ),
      )
      cur = advanceTo(cur, OpItemState.RECORDED)
    }
    if (cur.state == OpItemState.RECORDED) {
      when (val r = fileOps.trashMove(cur.srcShare, cur.srcPath, trashId, cur.stamp, purpose)) {
        is NasResult.Err -> return failOrPause(cur, r.error)
        is NasResult.Ok -> when (r.value) {
          is RenameOutcome.Renamed -> cur = advanceTo(cur, OpItemState.TRASHED)
          RenameOutcome.SourceMissing -> when (probe(cur.srcShare, trashed)) {
            is Probe.Found -> cur = advanceTo(cur, OpItemState.TRASHED)
            Probe.Unreachable -> throw PauseSignal(PauseReason.NAS_UNREACHABLE)
            Probe.Absent -> {
              store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
              return fail(cur, OpErrorCode.SOURCE_MISSING)
            }
          }
          is RenameOutcome.SourceChanged -> {
            store.markTrash(trashId, TrashEntryState.PURGED, nasNow())
            return fail(cur, OpErrorCode.SOURCE_CHANGED)
          }
          RenameOutcome.Locked -> return waitLocked(cur)
          RenameOutcome.Collision -> return fail(cur, OpErrorCode.UNEXPECTED)
        }
      }
    }
    return if (cur.state == OpItemState.TRASHED) advanceTo(cur, OpItemState.DONE) else cur
  }

  // --- Ripristina: PLANNED -> RESTORING -> DONE ------------------------------------------------------------------------------------

  private suspend fun runRestore(item: OpItemRecord): OpItemRecord {
    var cur = item
    if (cur.state == OpItemState.PLANNED) cur = advanceTo(cur, OpItemState.RESTORING)
    val share = cur.srcShare
    val baseName = cur.dstPath!!.name
    // Cartella d'origine sparita: si ricrea (E-D3).
    cur.dstPath!!.parent()?.let { parent ->
      (fileOps.mkdirs(share, parent, purpose) as? NasResult.Err)?.let { return failOrPause(cur, it.error) }
    }
    var attempt = 2
    while (true) {
      val dst = cur.dstPath!!
      when (val r = fileOps.rename(share, cur.srcPath, dst, null, purpose)) {
        is NasResult.Err -> return failOrPause(cur, r.error)
        is NasResult.Ok -> when (r.value) {
          is RenameOutcome.Renamed -> return restored(cur)
          RenameOutcome.Collision -> {
            if (attempt > MAX_ALTERNATIVES) return fail(cur, OpErrorCode.NAME_TAKEN)
            val alternative = NameRules.alternative(baseName, attempt++)
            cur = persist(cur.copy(dstPath = dst.parent()?.child(alternative) ?: SharePath.of(alternative)))
          }
          RenameOutcome.SourceMissing -> return if (alreadyAt(cur, dst)) restored(cur) else fail(cur, OpErrorCode.SOURCE_MISSING)
          is RenameOutcome.SourceChanged -> return fail(cur, OpErrorCode.SOURCE_CHANGED)
          RenameOutcome.Locked -> return waitLocked(cur)
        }
      }
    }
  }

  private suspend fun restored(cur: OpItemRecord): OpItemRecord {
    // Se la voce e' nel mio diario la segno; le voci degli altri dispositivi non sono mie, non le tocco.
    cur.trashId?.let { id -> if (store.trashEntry(id) != null) store.markTrash(id, TrashEntryState.RESTORED, nasNow()) }
    return advanceTo(cur, OpItemState.DONE)
  }

  // --- Elimina subito: PLANNED -> PURGING -> DONE ------------------------------------------------------------------------------

  private suspend fun runPurge(item: OpItemRecord): OpItemRecord {
    var cur = item
    if (cur.state == OpItemState.PLANNED) cur = advanceTo(cur, OpItemState.PURGING)
    return when (val r = TrashTreePurger(fileOps, reader).purge(cur.srcShare, cur.srcPath, cur.isFolder, cur.size ?: 0, purpose)) {
      TrashTreePurger.Result.Done -> {
        cur.trashId?.let { store.markTrash(it, TrashEntryState.PURGED, nasNow()) }
        advanceTo(cur, OpItemState.DONE)
      }
      TrashTreePurger.Result.Locked -> waitLocked(cur)
      TrashTreePurger.Result.Changed -> fail(cur, OpErrorCode.SOURCE_CHANGED)
      is TrashTreePurger.Result.Error -> failOrPause(cur, r.error)
    }
  }

  // =========================================================================================================================
  // Gli stati per la UI
  // =========================================================================================================================

  private suspend fun refreshStates() {
    val all = store.ops(OpState.entries.toSet())
    val (open, closed) = all.partition { !it.state.terminal }
    val shown = open + closed.takeLast(OpRules.CLOSED_OPS_SHOWN)
    states.value = shown.map { stateOf(it, store.items(it.id)) }
  }

  private fun stateOf(op: OpRecord, items: List<OpItemRecord>): NasOperationState {
    val done = items.count { it.state == OpItemState.DONE }
    val failed = items.count { it.state == OpItemState.FAILED }
    val total = items.size
    val bytesDone = items.filter { it.state == OpItemState.DONE }.sumOf { it.size ?: 0 }
    return when (op.state) {
      OpState.PLANNED -> NasOperationState.Planned(
        op.id, op.kind, PlanSummary(op.kind, total, items.count { it.isFolder }, op.totalBytes, null, null),
      )
      OpState.RUNNING -> NasOperationState.Running(op.id, op.kind, done, total, bytesDone, op.totalBytes)
      OpState.PAUSED -> {
        val locked = items.count { it.waitingLocked }
        val reason = pauseReasons[op.id] ?: op.pauseReason
        if (locked > 0 && reason == null) {
          NasOperationState.WaitingLocked(op.id, op.kind, locked, done, total)
        } else {
          NasOperationState.Paused(op.id, op.kind, reason ?: PauseReason.CONDITIONS, done, total)
        }
      }
      OpState.DONE -> NasOperationState.Done(op.id, op.kind, DoneSummary(done, failed, skipped = 0, bytes = bytesDone))
      OpState.FAILED -> NasOperationState.Failed(op.id, op.kind, op.errorCode ?: OpErrorCode.UNEXPECTED, done, total)
      OpState.CANCELLED -> NasOperationState.Cancelled(op.id, op.kind, done, total)
    }
  }

  private companion object {
    /** Nomi alternativi provati prima di fermarsi: `nome (2)` ... `nome (99)`. */
    const val MAX_ALTERNATIVES = 99

    /** Il blocco del ripiego dal telefono. */
    const val FALLBACK_CHUNK = 256 * 1024
  }
}
