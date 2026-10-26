package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationCheck
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
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
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * L'ORCHESTRATORE DELLA CONDIVISIONE E DEL "RIPORTA" (docs/fase7-progetto.md §2.5, §2.6; D12, D28, D29, D31, D38, D39; P4). Implementa
 * [ShareRunner] sopra le porte del contratto ([ShareOrchestratorPorts]) e il diario (`album_share_op`/`album_share_item`, D34: l'adattatore
 * Room e' di P1/P13).
 *
 * Le regole, tutte provate (fake fedeli, NAS finto e simulatore SMB con le vere `SmbNasFileOps`, guasto a ogni chiamata, morte a ogni salvataggio):
 * 1. **Il piano non scrive niente** e non chiama nessuna porta che sposta ([SharePlanner]).
 * 2. **Si parte solo con una conferma valida per questo piano** ([ShareConfirmationRules.check]) e con il piano ricontrollato sul catalogo: se e'
 *    cambiato, `PlanChanged` (serve un foglio nuovo). Lo stesso piano due volte non parte due volte (`AlreadyStarted`).
 * 3. **Il diario prima di ogni azione**: l'operazione e i suoi elementi (`PLANNED`, con la chiave di ogni trasferimento) in una scrittura; poi la
 *    definizione dell'album nuovo e i trasferimenti, con la conferma. La conferma vive solo in memoria: dopo la morte del processo un
 *    trasferimento mai partito diventa `FAILED(NOT_STARTED)`; uno partito si ritrova per chiave ("mai partito" lo dice solo una risposta di
 *    `find`: un suo errore vale "non so", SD-2), e i suoi esiti vanno a ogni file per la sua posizione nella richiesta (SD-1).
 * 4. **La sorgente va nel cestino solo dopo la verifica e dopo che il catalogo conosce la destinazione**: lo fa core-nasops (porta di
 *    completamento, D29); qui un file e' "arrivato" solo quando il catalogo locale lo vede nell'area comune con lo stesso `MediaId`.
 * 5. **Nessuna voce per un file che non e' nell'area comune**: la voce `item:` si accoda solo per un file che il catalogo vede li' adesso.
 * 6. **Il guardiano** solo per i miei media spostati dall'area privata che il telefono non ha piu' (D28, Q4 (b)).
 * 7. **Un guasto passeggero non diventa mai un errore permanente**: spazio, congelamento, file aperti, NAS irraggiungibile sono pause con il motivo.
 * 8. **Nessun dato letto dal NAS fa partire niente**: l'orchestratore lavora solo sulle operazioni del suo diario, nate da una conferma.
 *
 * Un processo alla volta per [start], [resumeAll] e [cancel] (un lucchetto): le riprese da un lavoro e un avvio dalla UI non si intrecciano.
 */
class ShareOrchestrator(
  private val ports: ShareOrchestratorPorts,
  private val newNonce: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
  private val newOpId: () -> String = { UUID.randomUUID().toString() },
) : ShareRunner {

  private val planner = SharePlanner(ports)
  private val lock = Mutex()

  /** Le conferme di questo processo, per operazione: mai sul disco (come la `UserConfirmation` della 4B). */
  private val live = HashMap<ShareOpId, ShareConfirmation>()

  /** Le operazioni viste da questo processo (anche finite), per [operations]. */
  private val seen = LinkedHashMap<ShareOpId, ShareOpRecord>()

  private val steps = ShareSteps(ports, live)

  private val states = MutableStateFlow<List<ShareOpState>>(emptyList())
  override val operations: StateFlow<List<ShareOpState>> = states

  override suspend fun plan(request: ShareRequest): SharePlanOutcome = planner.plan(request, null, ports.clocks.monotonicMs(), newNonce)

  override suspend fun start(plan: SharePlan, confirmation: ShareConfirmation): ShareStartOutcome = lock.withLock {
    val now = ports.clocks.monotonicMs()
    val check = ShareConfirmationRules.check(plan, confirmation, now)
    if (check != ShareConfirmationCheck.Valid) return@withLock ShareStartOutcome.ConfirmationRejected(check)
    ports.diary.opByPlan(plan.hash)?.let { return@withLock ShareStartOutcome.AlreadyStarted(it.id) }
    when (val again = planner.plan(plan.request, plan.nonceHex, now, newNonce)) {
      is SharePlanOutcome.Ready -> if (again.plan.hash != plan.hash) return@withLock ShareStartOutcome.PlanChanged
      is SharePlanOutcome.Blocked -> return@withLock ShareStartOutcome.Blocked(again.reason)
      else -> return@withLock ShareStartOutcome.PlanChanged
    }
    val id = ShareOpId(newOpId())
    val folder = when {
      plan.kind == SharePlanKind.BRING_BACK -> null
      plan.nonceHex != null -> ShareFolder(ports.family.commonShare() ?: return@withLock ShareStartOutcome.PlanChanged, dev.pampa.mycloudgallery.core.trust.nas.SharePath.parse(plan.folderLabel))
      else -> ports.shared.folder(plan.album) ?: return@withLock ShareStartOutcome.PlanChanged
    }
    val records = records(id, plan) ?: return@withLock ShareStartOutcome.PlanChanged
    val op = ShareOpRecord(id, plan.album, plan.kind, plan.hash, plan.title, plan.level, plan.nonceHex, folder, plan.hidden)
    // §2.5 passo 3: il diario prima di ogni azione (operazione ed elementi in una scrittura).
    ports.diary.create(op, records)
    seen[id] = op
    live[id] = confirmation
    GalleryLog.i(TAG) { "condivisione avviata: ${plan.kind.code}, ${plan.items.size} elementi" }
    (plan.request as? ShareRequest.FromPersonal)?.let { request ->
      try {
        ports.linkPersonal(request.album, plan.album)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "collegamento dell'album personale non scritto (${e.javaClass.simpleName})" }
      }
    }
    steps.ensureDefinition(op)
    steps.startTransfers(op)
    refresh()
    ShareStartOutcome.Started(id)
  }

  /**
   * Gli elementi del diario per [plan]: i fatti del catalogo di adesso (lo stesso piano appena ricontrollato), la chiave di ogni trasferimento
   * e, per "Riporta", la cartella di destinazione (la cartella d'origine, scritta qui: una ripresa non ne sceglie un'altra). Null se un media
   * non c'e' piu' (il piano e' cambiato).
   */
  private suspend fun records(id: ShareOpId, plan: SharePlan): List<ShareItemRecord>? {
    val known = ports.facts.facts(plan.items.map { it.media })
    val private = ports.family.privateShare()
    val nasNow = ports.clocks.nasNowMs()
    val destinations = HashMap<dev.pampa.mycloudgallery.core.model.MediaId, ShareFolder>()
    for (item in plan.items.filter { it.action == ShareItemAction.MOVE_OUT }) {
      val origin = ports.origins.originOf(item.media) ?: return null
      destinations[item.media] = SharePlanner.bringBackFolder(origin, private ?: return null, ports.family.backupFolderName(), nasNow)
    }
    val groups = destinations.values.distinct()
    return plan.items.mapIndexed { index, item ->
      val f = known[item.media] ?: return null
      val dest = destinations[item.media]
      ShareItemRecord(
        op = id,
        index = index,
        media = item.media,
        action = item.action,
        state = ShareItemState.PLANNED,
        mine = f.mine,
        size = item.size,
        contentKey = f.contentKey,
        source = f.nas,
        dest = dest?.let { NasItemRef(it.share, it.path) },
        transferKey = ShareTransferKeys.keyFor(id, item.action, if (dest == null) 0 else groups.indexOf(dest)),
      )
    }
  }

  override suspend fun resumeAll(budget: Duration): ShareRunResult = lock.withLock {
    val deadline = ports.clocks.monotonicMs() + budget.inWholeMilliseconds
    var done = 0
    var failed = 0
    var waiting = 0
    var ops = 0
    var stop = ShareRunStop.FINISHED
    for (op in ports.diary.openOps()) {
      if (ports.clocks.monotonicMs() >= deadline) {
        stop = ShareRunStop.BUDGET
        break
      }
      ops++
      seen[op.id] = op
      steps.startTransfers(op)
      steps.advance(op)
      val items = ports.diary.items(op.id)
      done += items.count { it.state == ShareItemState.DONE }
      failed += items.count { it.state == ShareItemState.FAILED }
      waiting += items.count { !it.state.terminal }
      if (items.all { it.state.terminal }) {
        ports.diary.finish(op.id)
        live.remove(op.id)
        val failures = items.filter { it.state == ShareItemState.FAILED }.groupingBy { it.failure!!.code }.eachCount()
        GalleryLog.i(TAG) { "condivisione chiusa: ${op.kind.code}, fatti ${items.count { it.state == ShareItemState.DONE }}, fermi $failures" }
      }
    }
    if (stop != ShareRunStop.BUDGET && waiting > 0) stop = ShareRunStop.WAITING
    refresh()
    ShareRunResult(ops, done, failed, waiting, stop)
  }

  /**
   * Ferma cio' che non e' partito; un trasferimento partito lo ferma la sua porta (nessun file a meta': l'esito per file arriva alla ripresa).
   * Come in SD-2, un elemento `PLANNED` con una chiave non e' per forza "mai partito" (la morte fra l'avvio e il salvataggio): lo dice solo
   * `find`. Ritrovato, diventa `TRANSFERRING` con il suo biglietto e si ferma con gli altri; senza risposta resta com'e', e la conferma
   * dell'operazione si toglie: niente di nuovo parte, al giro dopo un trasferimento partito si ritrova e uno mai partito chiude "non partito".
   */
  override suspend fun cancel(op: ShareOpId) = lock.withLock {
    live.remove(op)
    val lookups = HashMap<TransferKey, ShareSteps.TicketLookup>()
    val tickets = LinkedHashSet<TransferTicket>()
    for (item in ports.diary.items(op)) {
      when (item.state) {
        ShareItemState.PLANNED -> {
          val key = item.transferKey
          when (val lookup = if (key == null) ShareSteps.TicketLookup.Known(null) else lookups.getOrPut(key) { steps.lookUp(key) }) {
            ShareSteps.TicketLookup.Unknown -> Unit
            is ShareSteps.TicketLookup.Known -> {
              val ticket = lookup.ticket
              if (ticket == null) {
                ports.diary.update(item.copy(state = ShareItemState.CANCELLED, wait = null))
              } else {
                ports.diary.update(item.copy(state = ShareItemState.TRANSFERRING, ticket = ticket, wait = null))
                tickets += ticket
              }
            }
          }
        }
        ShareItemState.TRANSFERRING -> item.ticket?.let { tickets += it }
        else -> Unit
      }
    }
    for (ticket in tickets) {
      try {
        ports.tickets.cancel(ticket)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "annullamento non arrivato alla porta (${e.javaClass.simpleName})" }
      }
    }
    refresh()
  }

  private suspend fun refresh() {
    states.value = seen.values.map { op -> stateOf(op, ports.diary.items(op.id)) }
  }

  companion object {
    private const val TAG = "AlbumCondivisione"

    /** Lo stato per la UI di [op] (§1.5 `ShareProgress`): in corso, in pausa con il motivo, fatto, fatto in parte con i motivi. */
    fun stateOf(op: ShareOpRecord, items: List<ShareItemRecord>): ShareOpState {
      val done = items.count { it.state == ShareItemState.DONE }
      if (items.all { it.state.terminal }) {
        val failures: Map<ShareFailure, Int> = items.filter { it.state == ShareItemState.FAILED }.groupingBy { it.failure!! }.eachCount()
        val cancelled = items.count { it.state == ShareItemState.CANCELLED }
        return if (done == items.size) {
          ShareOpState.Done(op.id, op.album, op.kind, done)
        } else {
          ShareOpState.PartlyDone(op.id, op.album, op.kind, done, failures, cancelled)
        }
      }
      val wait = items.firstOrNull { !it.state.terminal && it.wait != null && it.wait != ShareWait.RULES }?.wait
      return if (wait != null) {
        ShareOpState.Paused(op.id, op.album, op.kind, wait, done, items.size)
      } else {
        ShareOpState.Running(op.id, op.album, op.kind, done, items.size)
      }
    }
  }
}
