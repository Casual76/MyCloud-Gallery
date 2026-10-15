package dev.pampa.mycloudgallery.core.nasops.testing

import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.CleanReport
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationGate
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationIssuer
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.LargestFile
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceDetails
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.PlannedItem
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeReport
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFilesCleaning
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.KeepReason
import dev.pampa.mycloudgallery.core.trust.data.LocalTrashEntry
import dev.pampa.mycloudgallery.core.trust.data.PurgeDecision
import dev.pampa.mycloudgallery.core.trust.data.PurgeRound
import dev.pampa.mycloudgallery.core.trust.data.TrashItemInfo
import dev.pampa.mycloudgallery.core.trust.data.TrashPurgePolicy
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

// I fake di core-nasops (P0-B), oltre al diario ([InMemoryOpsStore]) e all'esecutore di riferimento ([InMemoryNasOperations]): orologi,
// cancello, conferme di prova, cestino, spazio, cartelle e un `NasOperations` a copione per la UI. In src/main per i test di P12-P16 e
// :app. Mai nella release (tools/check.ps1).

/** Gli orologi controllati dal test: tutti e due avanzano insieme con [advance]; [nasNow] a null = ora del NAS non ancorata. */
class FakeOpClocks(var monotonic: Long = 1_000L, var nasNow: Long? = DEFAULT_NAS_NOW) : OpClocks {
  override fun monotonicMs(): Long = monotonic

  override fun nasNowMs(): Long? = nasNow

  fun advance(ms: Long) {
    monotonic += ms
    nasNow = nasNow?.plus(ms)
  }

  companion object {
    /** 2026-10-15T00:00:00Z: un'ora del NAS plausibile. */
    const val DEFAULT_NAS_NOW: Long = 1_792_022_400_000L
  }
}

/** Il cancello delle scritture, per share. Di default tutto aperto. */
class FakeOpsGate(var default: GateStatus = GateStatus.OPEN) : OpsGate {
  val byShare: MutableMap<String, GateStatus> = HashMap()

  override fun statusOf(share: String): GateStatus = byShare[share] ?: default
}

/**
 * Le conferme come le costruisce la UI, per i test: chiama [ConfirmationIssuer] con i tocchi richiesti dal piano. Sta in `testing`
 * (non nella release): il test di architettura lo ammette solo qui, come i test di core-nasops.
 */
object TestConfirmations {
  fun forPlan(plan: OpPlan, nowMonotonicMs: Long): UserConfirmation =
    ConfirmationIssuer.issue(plan, plan.requiredConfirmations, nowMonotonicMs)
}

// =============================================================================================================================
// Cestino
// =============================================================================================================================

/**
 * Un record del cestino come arriva dal NAS: **solo da mostrare** (in produzione e' un `TrashRecord` firmato letto da core-trust). Non
 * entra mai in una pulizia: lo prova [InMemoryNasTrash] con i test "dato remoto ostile".
 */
data class RemoteTrashRecord(
  val share: String,
  val ownerDeviceId: String,
  val trashId: String,
  val originalPath: SharePath,
  val size: Long,
  val trashedAtNas: Long,
  val author: String?,
  val reason: TrashReason = TrashReason.DELETE,
  val isFolder: Boolean = false,
  val fileCount: Int? = null,
  /** Falso se il record non ha passato la verifica (firma, posto): compare come "senza record valido". */
  val valid: Boolean = true,
) {
  override fun toString(): String = "RemoteTrashRecord(valid=$valid, size=$size)"
}

/**
 * Il cestino di riferimento. "Eliminati di recente" mostra le voci del **diario locale** (proprie) e i record remoti passati da
 * [remoteRecords], solo se il file e' davvero nel cestino. La pulizia ([purgeExpired]) legge **solo** il diario locale: un record
 * remoto, vero o finto, non e' fra i suoi ingressi (E-S11, F20).
 */
class InMemoryNasTrash(
  private val deviceId: String,
  private val store: OpsStore,
  private val fileOps: NasFileOps,
  private val reader: NasMediaReader,
  private val clocks: OpClocks,
  private val gate: OpsGate = OpsGate { GateStatus.OPEN },
  private val remoteRecords: () -> List<RemoteTrashRecord> = { emptyList() },
  private val readOnly: (String) -> Boolean = { false },
) : NasTrash {

  private val state = MutableStateFlow<NasTrashState>(NasTrashState.Loading)
  override val items: StateFlow<NasTrashState> = state

  override suspend fun refresh() {
    val nasNow = clocks.nasNowMs()
    val own = store.expiredTrash(Long.MAX_VALUE, Int.MAX_VALUE).filter { it.state == TrashEntryState.TRASHED }
    val shown = ArrayList<NasTrashItem>()
    val ownIds = own.mapTo(HashSet()) { it.trashId }
    for (entry in own) {
      val found = when (val r = reader.stat(entry.shareName, entry.trashedPath, Purpose.INTERACTIVE)) {
        is NasResult.Err -> {
          state.value = NasTrashState.Offline(cachedOwn = own.map { ownItem(it, nasNow) })
          return
        }
        is NasResult.Ok -> r.value
      }
      if (found != null) shown += ownItem(entry, nasNow)
    }
    for (record in remoteRecords()) {
      if (record.ownerDeviceId == deviceId && record.trashId in ownIds) continue
      val trashed = NasLayout.trashItemFile(record.ownerDeviceId, record.trashId)
      val exists = (reader.stat(record.share, trashed, Purpose.INTERACTIVE) as? NasResult.Ok)?.value != null
      if (!exists) continue
      shown += NasTrashItem(
        share = record.share,
        ownerDeviceId = record.ownerDeviceId,
        trashId = record.trashId,
        name = if (record.valid) record.originalPath.name else null,
        originalFolder = if (record.valid) record.originalPath.parent() else null,
        originalPath = if (record.valid) record.originalPath else null,
        size = record.size,
        daysLeft = daysLeft(record.trashedAtNas, nasNow),
        author = record.author,
        reason = if (record.valid) record.reason else null,
        isFolder = record.isFolder,
        fileCount = record.fileCount,
        withoutValidRecord = !record.valid,
        restorable = record.valid && !readOnly(record.share),
        // Le voci degli altri dispositivi si ripristinano soltanto: "elimina subito" e la scadenza sono di chi ha cestinato (D25).
        deletableNow = false,
      )
    }
    state.value = NasTrashState.Ready(shown, nasNow, writable = shown.none { readOnly(it.share) } || shown.any { !readOnly(it.share) })
  }

  private fun ownItem(entry: LocalTrashRecord, nasNow: Long?): NasTrashItem = NasTrashItem(
    share = entry.shareName,
    ownerDeviceId = deviceId,
    trashId = entry.trashId,
    name = entry.originalPath.name,
    originalFolder = entry.originalPath.parent(),
    originalPath = entry.originalPath,
    size = entry.size,
    daysLeft = daysLeft(entry.trashedAtNas, nasNow),
    author = null,
    reason = entry.reason,
    isFolder = entry.isFolder,
    fileCount = entry.fileCount,
    withoutValidRecord = false,
    restorable = !readOnly(entry.shareName),
    deletableNow = true,
  )

  private fun daysLeft(trashedAtNas: Long, nasNow: Long?): Int? {
    nasNow ?: return null
    val leftMs = trashedAtNas + TrustLimits.TRASH_RETENTION_MILLIS - nasNow
    return if (leftMs <= 0) 0 else ((leftMs + DAY_MS - 1) / DAY_MS).toInt()
  }

  override suspend fun purgeExpired(budget: Duration): PurgeReport {
    val nasNow = clocks.nasNowMs() ?: return report(0, 0, emptyMap(), 0, 0, PurgeStop.NO_NAS_TIME)
    val deadline = clocks.monotonicMs() + budget.inWholeMilliseconds
    val policy = TrashPurgePolicy(deviceId)
    val round = PurgeRound()
    var purged = 0
    var bytes = 0L
    var alreadyGone = 0
    var deferred = 0
    val kept = HashMap<KeepReason, Int>()
    var stop = PurgeStop.COMPLETE
    // Solo il diario locale: le voci proprie, trashed_at < ora del NAS - 30 giorni. Nessun record remoto fra gli ingressi.
    val candidates = store.expiredTrash(nasNow - TrustLimits.TRASH_RETENTION_MILLIS + 1, TrustLimits.MAX_PURGE_ITEMS_PER_ROUND + 1)
    for (entry in candidates) {
      if (clocks.monotonicMs() >= deadline) {
        stop = PurgeStop.BUDGET
        break
      }
      if (gate.statusOf(entry.shareName).blocksScheduled) {
        stop = PurgeStop.BLOCKED
        break
      }
      val found = when (val r = reader.stat(entry.shareName, entry.trashedPath, Purpose.BACKGROUND)) {
        is NasResult.Err -> {
          stop = PurgeStop.NAS_UNREACHABLE
          break
        }
        is NasResult.Ok -> r.value
      }
      // Una cartella cestinata non ha una dimensione propria da confrontare: conta che ci sia.
      val info = found?.let { TrashItemInfo(if (entry.isFolder) entry.size else it.size) }
      when (val decision = policy.decide(LocalTrashEntry(entry.shareName, entry.trashId, entry.size, entry.trashedAtNas), info, nasNow, round)) {
        is PurgeDecision.Purge -> when (val r = TrashTreePurger(fileOps, reader).purge(entry.shareName, decision.path, entry.isFolder, entry.size, Purpose.BACKGROUND)) {
          TrashTreePurger.Result.Done -> {
            store.markTrash(entry.trashId, TrashEntryState.PURGED, nasNow)
            purged++
            bytes += entry.size
          }
          TrashTreePurger.Result.Locked -> deferred++
          TrashTreePurger.Result.Changed -> kept.merge(KeepReason.CHANGED, 1, Int::plus)
          is TrashTreePurger.Result.Error -> {
            stop = PurgeStop.NAS_UNREACHABLE
            break
          }
        }
        is PurgeDecision.Keep -> kept.merge(decision.why, 1, Int::plus)
        PurgeDecision.AlreadyGone -> {
          store.markTrash(entry.trashId, TrashEntryState.PURGED, nasNow)
          alreadyGone++
        }
        PurgeDecision.Defer -> {
          deferred++
          stop = PurgeStop.BUDGET
          break
        }
      }
    }
    return report(purged, bytes, kept, alreadyGone, deferred, stop)
  }

  private fun report(purged: Int, bytes: Long, kept: Map<KeepReason, Int>, gone: Int, deferred: Int, stop: PurgeStop) =
    PurgeReport(purged, bytes, kept, gone, deferred, stop)

  private companion object {
    const val DAY_MS = 24L * 60 * 60 * 1000
  }
}

/** La pulizia dei propri file a copione: ricorda le richieste e risponde con [report]. */
class FakeOwnFilesCleaning(var report: CleanReport = CleanReport(0, emptyMap(), 0, PurgeStop.COMPLETE)) : OwnFilesCleaning {
  val requests: MutableList<List<String>> = mutableListOf()

  override suspend fun clean(shares: List<String>, budget: Duration): CleanReport {
    requests += shares
    return report
  }
}

// =============================================================================================================================
// Spazio
// =============================================================================================================================

/** Lo Spazio NAS di riferimento: `space()` sulla share, il resto dai dati forniti dal test. */
class InMemoryNasSpace(
  private val fileOps: NasFileOps,
  private val clocks: OpClocks,
  private val largest: (String) -> List<LargestFile> = { emptyList() },
  private val trashBytes: (String) -> Long = { 0L },
  private val indexBytes: (String) -> Long = { 0L },
  /** Le share con il cestino del WD acceso (su `NAS`): lo spazio dei file eliminati per sempre si libera quando lo svuota il WD. */
  private val wdTrashShares: Set<String> = setOf("NAS"),
) : NasSpace {

  override suspend fun load(share: String): NasSpaceState {
    val biggest = largest(share).sortedByDescending { it.size }.take(NasSpaceDetails.MAX_LARGEST)
    return when (val r = fileOps.space(share, Purpose.INTERACTIVE)) {
      is NasResult.Err -> NasSpaceState.Unavailable(OpErrorCode.UNREACHABLE, biggest)
      is NasResult.Ok -> NasSpaceState.Ready(
        NasSpaceDetails(
          share = share,
          freeBytes = r.value.freeBytes,
          totalBytes = r.value.totalBytes,
          quotaBytes = if (r.value.quotaLimited) r.value.callerFreeBytes else null,
          largest = biggest,
          trashBytes = trashBytes(share),
          indexBytes = indexBytes(share),
          versionsBytes = 0,
          wdTrashMayHoldSpace = wdTrashShares.any { it.equals(share, ignoreCase = true) },
          measuredAtNas = clocks.nasNowMs(),
        ),
      )
    }
  }
}

// =============================================================================================================================
// Cartelle
// =============================================================================================================================

/** Le Cartelle NAS di riferimento: l'elenco letto con il [NasMediaReader], con l'ultima risposta buona in cache per l'offline. */
class InMemoryNasFolders(
  private val reader: NasMediaReader,
  private val clocks: OpClocks,
  private val readOnly: (String) -> Boolean = { false },
) : NasFolders {

  private class Cached(val entries: List<NasFolderEntry>, val at: Long?)

  private val cache = HashMap<String, Cached>()

  private fun keyOf(share: String, dir: SharePath?) = share.lowercase() + "/" + dir?.value.orEmpty().lowercase()

  override fun observe(share: String, dir: SharePath?): Flow<NasFolderState> = flow {
    emit(NasFolderState.Loading)
    emit(load(share, dir))
  }

  override suspend fun refresh(share: String, dir: SharePath?) {
    cache.remove(keyOf(share, dir))
  }

  private suspend fun load(share: String, dir: SharePath?): NasFolderState {
    val raw = ArrayList<NasEntry>()
    return when (val r = reader.list(share, dir, Purpose.INTERACTIVE) { raw += it }) {
      is NasResult.Ok -> {
        val parent = dir
        val entries = raw.filterNot { it.name.startsWith(".") || it.isHidden || it.isSystem }
          .mapNotNull { entry ->
            val path = runCatching { parent?.child(entry.name) ?: SharePath.of(entry.name) }.getOrNull() ?: return@mapNotNull null
            NasFolderEntry(path, entry.isDirectory, entry.size, entry.modifiedAt, entry.fileId)
          }
          .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
        cache[keyOf(share, dir)] = Cached(entries, clocks.nasNowMs())
        NasFolderState.Listing(entries, readOnly = readOnly(share), cachedAt = null)
      }
      is NasResult.Err -> when (r.error) {
        NasError.PermissionDenied -> NasFolderState.Denied
        NasError.Timeout, NasError.SessionExpired, NasError.ServiceDown, NasError.Stalled, is NasError.NotAllowedHere ->
          cache[keyOf(share, dir)].let { NasFolderState.Offline(it?.entries, it?.at) }
        else -> NasFolderState.Error(OpErrorCode.UNEXPECTED)
      }
    }
  }
}

// =============================================================================================================================
// Operazioni a copione (per la UI)
// =============================================================================================================================

/**
 * Un `NasOperations` a copione per i test della UI (P14): non tocca nessun NAS. [plan] costruisce un piano dalla sola richiesta
 * (o risponde con [planFor]), [start] verifica la conferma come il vero (stessa [ConfirmationGate]) e mette l'operazione in elenco
 * come `Planned`; poi il test racconta cio' che accade con [emit]. Registra richieste, piani avviati e annullamenti.
 */
class ScriptedNasOperations(
  private val clocks: OpClocks = FakeOpClocks(),
  /** Il dispositivo proprietario del cestino, per i percorsi di "ripristina" e "elimina subito" del piano predefinito. */
  private val deviceId: String = TrustIds.random(),
  var planFor: ((OpRequest) -> PlanOutcome)? = null,
) : NasOperations {

  private val list = MutableStateFlow<List<NasOperationState>>(emptyList())
  override val operations: StateFlow<List<NasOperationState>> = list

  val requests: MutableList<OpRequest> = mutableListOf()
  val started: MutableList<OpPlan> = mutableListOf()
  val cancelled: MutableList<OpId> = mutableListOf()
  var resumeCalls: Int = 0
    private set

  override suspend fun plan(request: OpRequest): PlanOutcome {
    requests += request
    return planFor?.invoke(request) ?: defaultPlan(request)
  }

  override suspend fun start(plan: OpPlan, confirmation: UserConfirmation): StartOutcome {
    val accepted = when (val outcome = ConfirmationGate.accept(plan, confirmation, clocks.monotonicMs())) {
      is AcceptOutcome.Rejected -> return StartOutcome.ConfirmationRejected(outcome.check)
      is AcceptOutcome.Accepted -> outcome.accepted
    }
    started += accepted.plan
    val id = OpId(TrustIds.random())
    list.value = list.value + NasOperationState.Planned(id, plan.kind, plan.summary)
    return StartOutcome.Started(id)
  }

  override suspend fun cancel(id: OpId) {
    cancelled += id
  }

  override suspend fun resumeAll(budget: Duration): RunResult {
    resumeCalls++
    return RunResult(0, 0, 0, 0, RunStop.COMPLETE)
  }

  /** Racconta un cambio di stato: sostituisce lo stato dell'operazione con lo stesso id (o lo aggiunge). */
  fun emit(state: NasOperationState) {
    val current = list.value
    list.value = if (current.any { it.id == state.id }) current.map { if (it.id == state.id) state else it } else current + state
  }

  private fun defaultPlan(request: OpRequest): PlanOutcome {
    val items = ArrayList<PlannedItem>()
    fun add(flow: ItemFlow, share: String, src: SharePath, dstShare: String?, dst: SharePath?, folder: Boolean, size: Long, trashId: String? = null) {
      items += PlannedItem(items.size, flow, share, src, dstShare, dst, folder, size, null, trashId)
    }
    when (request) {
      is OpRequest.CreateFolder -> add(ItemFlow.CREATE_FOLDER, request.share, request.parent?.child(request.name) ?: SharePath.of(request.name), null, null, true, 0)
      is OpRequest.Rename -> add(
        ItemFlow.RENAME, request.item.share, request.item.path, request.item.share,
        request.item.path.parent()?.child(request.newName) ?: SharePath.of(request.newName), request.item.isFolder, request.item.size,
      )
      is OpRequest.Move -> request.items.forEach { item ->
        val dst = request.destFolder?.child(item.path.name) ?: SharePath.of(item.path.name)
        val across = !item.share.equals(request.destShare, ignoreCase = true)
        add(if (across) ItemFlow.MOVE_ACROSS_SHARES else ItemFlow.RENAME, item.share, item.path, request.destShare, dst, item.isFolder, item.size, if (across) TrustIds.random() else null)
      }
      is OpRequest.Trash -> request.items.forEach { add(ItemFlow.TRASH, it.share, it.path, null, null, it.isFolder, it.size, TrustIds.random()) }
      is OpRequest.Restore -> request.entries.forEach {
        add(ItemFlow.RESTORE, it.share, NasLayout.trashItemFile(it.ownerDeviceId, it.trashId), it.share, it.originalPath, it.isFolder, it.size, it.trashId)
      }
      is OpRequest.PurgeNow -> request.entries.forEach {
        add(ItemFlow.PURGE, "scripted", NasLayout.trashItemFile(deviceId, it.trashId), null, null, false, 0, it.trashId)
      }
    }
    if (items.isEmpty()) return PlanOutcome.Rejected(PlanRejection.EMPTY)
    val kind = request.kind
    val warnings = buildSet {
      if (items.any { it.flow == ItemFlow.MOVE_ACROSS_SHARES }) add(PlanWarning.CROSS_SHARE_COPY)
      if (kind.permanent) add(PlanWarning.PERMANENT_DELETE)
      if (items.any { it.isFolder }) add(PlanWarning.FOLDER_INCLUDED)
    }
    val destShare = (request as? OpRequest.Move)?.destShare
    val destFolder = (request as? OpRequest.Move)?.destFolder
    val summary = PlanSummary(kind, items.size, items.count { it.isFolder }, items.sumOf { it.size }, destShare, destFolder)
    return PlanOutcome.Ready(OpPlan(kind, items, emptyList(), summary, warnings))
  }
}
