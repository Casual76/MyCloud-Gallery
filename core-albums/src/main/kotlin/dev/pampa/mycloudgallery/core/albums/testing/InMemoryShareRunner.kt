package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.SafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationCheck
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareDiary
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunResult
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunStop
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumFacts
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedOrigins
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

/** Le porte e il diario dell'orchestratore, tutte insieme (le stesse che :app legherà all'orchestratore vero di P4). */
class ShareRunnerPorts(
  val diary: ShareDiary,
  val facts: CatalogFacts,
  val shared: SharedAlbumFacts,
  /** La tabella del §2.4 applicata a me (`SharedAlbums.allowed`). */
  val allowed: (AlbumId, AlbumOp, MediaId?) -> Boolean,
  val mover: AlbumMover,
  val uploader: AlbumUploader,
  val tickets: TicketStatus,
  val publisher: AlbumPublisher,
  val safeCopies: SafeCopyRequester,
  val origins: SharedOrigins,
  val family: AlbumFamily,
  val gate: AlbumWriteGate,
  val clocks: AlbumClocks,
)

/**
 * L'ESECUTORE DI RIFERIMENTO della condivisione (docs/fase7-progetto.md §2.5, §2.6, §5.2, §5.5): piccolo e leggibile, sopra le porte e
 * il diario del contratto. P4 scrive l'orchestratore vero (share/) e lo fa passare dalla stessa suite (`ShareRunnerContract`); P5 scrive
 * da subito gli invarianti e il banco delle interruzioni su questo.
 *
 * Regole che segue alla lettera: piano senza scritture; `start` solo con una conferma valida per **questo** piano e con il piano
 * ricontrollato sul catalogo; diario `PLANNED` (con la chiave di ogni trasferimento) **prima** di chiamare una porta; cartella d'origine
 * registrata prima di uno spostamento verso l'area comune; voce `item:` accodata solo per un file che il catalogo vede nell'area comune;
 * guardiano subito per i miei media senza copia sul telefono (D28); ogni passo idempotente e ripreso dal diario. La conferma vive solo in
 * memoria: dopo la morte del processo un trasferimento mai partito diventa `FAILED(NOT_STARTED)` ("Riprova" fa un piano nuovo).
 */
class InMemoryShareRunner(
  private val ports: ShareRunnerPorts,
  private val planner: ReferenceSharePlanner,
  private val newNonce: () -> ByteArray = { ByteArray(16).also { java.security.SecureRandom().nextBytes(it) } },
  private val newOpId: () -> String = { UUID.randomUUID().toString() },
  /** Revisione B, SH-1: il tentativo di ogni piano chiesto, come l'orchestratore vero. */
  private val newAttempt: () -> String = { ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) } },
) : ShareRunner {

  override val operations: MutableStateFlow<List<ShareOpState>> = MutableStateFlow(emptyList())

  /** Le conferme di questo "processo", per operazione: mai sul disco. */
  private val live = HashMap<ShareOpId, ShareConfirmation>()

  /** Le operazioni viste da questo processo (anche finite), per [operations]. */
  private val seen = LinkedHashMap<ShareOpId, ShareOpRecord>()

  private val steps = ReferenceShareSteps(ports, live)

  override suspend fun plan(request: ShareRequest): SharePlanOutcome = planner.plan(request, null, ports.clocks.monotonicMs(), newNonce, newAttempt())

  override suspend fun start(plan: SharePlan, confirmation: ShareConfirmation): ShareStartOutcome {
    val now = ports.clocks.monotonicMs()
    val check = ShareConfirmationRules.check(plan, confirmation, now)
    if (check != ShareConfirmationCheck.Valid) return ShareStartOutcome.ConfirmationRejected(check)
    ports.diary.opByPlan(plan.hash)?.let { return ShareStartOutcome.AlreadyStarted(it.id) }
    when (val again = planner.plan(plan.request, plan.nonceHex, now, newNonce, plan.attempt)) {
      is SharePlanOutcome.Ready -> if (again.plan.hash != plan.hash) return ShareStartOutcome.PlanChanged
      is SharePlanOutcome.Blocked -> return ShareStartOutcome.Blocked(again.reason)
      else -> return ShareStartOutcome.PlanChanged
    }
    val id = ShareOpId(newOpId())
    val folder = when {
      plan.kind == SharePlanKind.BRING_BACK -> null
      plan.nonceHex != null -> ShareFolder(ports.family.commonShare()!!, SharePath.of(SharedAlbumLimits.FOLDER_ROOT, ReferenceSharePlanner.folderName(plan.title)))
      else -> ports.shared.folder(plan.album)
    }
    val known = ports.facts.facts(plan.items.map { it.media })
    // "Riporta": un trasferimento per cartella d'origine (D31), ognuno con la sua chiave.
    val origins = plan.items.filter { it.action == ShareItemAction.MOVE_OUT }.associate { it.media to ports.origins.originOf(it.media) }
    val groups = origins.values.distinct()
    val records = plan.items.mapIndexed { index, item ->
      val f = known.getValue(item.media)
      val group = if (item.action == ShareItemAction.MOVE_OUT) groups.indexOf(origins[item.media]) else 0
      ShareItemRecord(
        op = id, index = index, media = item.media, action = item.action, state = ShareItemState.PLANNED, mine = f.mine, size = item.size,
        contentKey = f.contentKey, source = f.nas, transferKey = ReferenceShareSteps.keyFor(id, item.action, group),
      )
    }
    val op = ShareOpRecord(id, plan.album, plan.kind, plan.hash, plan.title, plan.level, plan.nonceHex, folder, plan.hidden)
    // Il diario prima di ogni azione (§2.5 passo 3); poi la definizione dell'album nuovo e i trasferimenti, con la conferma.
    ports.diary.create(op, records)
    seen[id] = op
    live[id] = confirmation
    steps.ensureDefinition(op)
    steps.startTransfers(op)
    refresh()
    return ShareStartOutcome.Started(id)
  }

  override suspend fun resumeAll(budget: Duration): ShareRunResult {
    var done = 0
    var failed = 0
    var waiting = 0
    val ops = ports.diary.openOps()
    for (op in ops) {
      seen[op.id] = op
      steps.ensureDefinition(op)
      steps.startTransfers(op)
      steps.advance(op)
      val items = ports.diary.items(op.id)
      done += items.count { it.state == ShareItemState.DONE }
      failed += items.count { it.state == ShareItemState.FAILED }
      waiting += items.count { !it.state.terminal }
      if (items.all { it.state.terminal }) {
        ports.diary.finish(op.id)
        live.remove(op.id)
      }
    }
    refresh()
    return ShareRunResult(ops.size, done, failed, waiting, if (waiting == 0) ShareRunStop.FINISHED else ShareRunStop.WAITING)
  }

  override suspend fun cancel(op: ShareOpId) {
    for (item in ports.diary.items(op)) {
      when (item.state) {
        ShareItemState.PLANNED -> ports.diary.update(item.copy(state = ShareItemState.CANCELLED, wait = null))
        ShareItemState.TRANSFERRING -> item.ticket?.let { ports.tickets.cancel(it) }
        else -> Unit
      }
    }
    refresh()
  }

  private suspend fun refresh() {
    operations.value = seen.values.map { op -> ReferenceShareSteps.stateOf(op, ports.diary.items(op.id)) }
  }
}
