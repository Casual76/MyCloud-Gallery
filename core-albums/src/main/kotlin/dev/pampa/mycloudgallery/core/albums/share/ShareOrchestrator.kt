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
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
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
  /** Revisione B, SH-1: il tentativo di ogni piano chiesto (esadecimale): due `plan` con gli stessi media hanno impronte diverse. */
  private val newAttempt: () -> String = { SharePlanner.hexOf(ByteArray(16).also { SecureRandom().nextBytes(it) }) },
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

  override suspend fun plan(request: ShareRequest): SharePlanOutcome = planner.plan(request, null, ports.clocks.monotonicMs(), newNonce, newAttempt())

  override suspend fun start(plan: SharePlan, confirmation: ShareConfirmation): ShareStartOutcome = lock.withLock {
    val now = ports.clocks.monotonicMs()
    val check = ShareConfirmationRules.check(plan, confirmation, now)
    if (check != ShareConfirmationCheck.Valid) return@withLock ShareStartOutcome.ConfirmationRejected(check)
    // Lo stesso piano (lo stesso tentativo) non parte due volte; un piano nuovo con gli stessi media si', anche dopo un'operazione finita (SH-1).
    ports.diary.opByPlan(plan.hash)?.let { return@withLock ShareStartOutcome.AlreadyStarted(it.id) }
    when (val again = planner.plan(plan.request, plan.nonceHex, now, newNonce, plan.attempt)) {
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
    val keys = transferKeys(id, plan, destinations)
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
        transferKey = keys[index],
      )
    }
  }

  /**
   * Le chiavi dei trasferimenti, per elemento del piano. Revisione B, I2: un trasferimento porta al piu' [ShareRules.MAX_ITEMS_PER_TRANSFER] file
   * (un'operazione di core-nasops ne accetta 10.000, un album 20.000): oltre, l'azione si divide in lotti, ognuno con la sua chiave e il suo
   * trasferimento. "Riporta" ha un trasferimento per cartella d'origine e, dentro, per lotto. Con un solo lotto le chiavi sono quelle di prima.
   */
  private fun transferKeys(id: ShareOpId, plan: SharePlan, destinations: Map<dev.pampa.mycloudgallery.core.model.MediaId, ShareFolder>): List<TransferKey?> {
    val keys = arrayOfNulls<TransferKey>(plan.items.size)
    for (action in listOf(ShareItemAction.MOVE_IN, ShareItemAction.UPLOAD)) {
      plan.items.withIndex().filter { it.value.action == action }.forEachIndexed { n, (index, _) ->
        keys[index] = ShareTransferKeys.keyFor(id, action, n / ShareRules.MAX_ITEMS_PER_TRANSFER)
      }
    }
    var group = 0
    for (dest in destinations.values.distinct()) {
      val sameDest = plan.items.withIndex().filter { it.value.action == ShareItemAction.MOVE_OUT && destinations[it.value.media] == dest }
      for (chunk in sameDest.chunked(ShareRules.MAX_ITEMS_PER_TRANSFER)) {
        chunk.forEach { keys[it.index] = ShareTransferKeys.keyFor(id, ShareItemAction.MOVE_OUT, group) }
        group++
      }
    }
    return keys.toList()
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
      // Correzioni F (L231): se l'album e' stato chiuso (da me o da un altro dispositivo) cio' che non e' ancora partito non parte piu'.
      stopIfAlbumClosed(op)
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
    cancelItems(op)
    refresh()
  }

  private suspend fun cancelItems(op: ShareOpId) {
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
  }

  /**
   * "Smetti di condividere" (o "Elimina album") con degli elementi ancora in viaggio (correzioni F, risposta dell'utente del 27/10 su L231): quando l'album
   * di un'operazione che aggiunge elementi non e' piu' attivo, cio' che **non e' ancora partito si annulla** (resta dov'e', mai toccato) e cio' che e' **gia' in
   * viaggio finisce il suo passo atomico** (copia verificata, poi cestino: lo decide la porta, come per «Annulla»), poi l'operazione si chiude. E' la strada
   * di [cancel], quindi niente file a meta', niente doppioni, niente perdite; vale anche se a chiudere l'album e' stato un altro dispositivo e dopo la morte
   * del processo fra la chiusura e questo passo (si rifa' a ogni ripresa, idempotente). "Riporta" non si ferma mai: sono file dell'utente che tornano a casa.
   * Un album che la replica non conosce (ancora) non e' un album chiuso: non si decide.
   */
  private suspend fun stopIfAlbumClosed(op: ShareOpRecord) {
    if (op.kind == SharePlanKind.BRING_BACK) return
    val state = guardedOrNull { ports.shared.state(op.album) } ?: return
    if (state == SharedState.ACTIVE) return
    cancelItems(op.id)
    GalleryLog.i(TAG) { "album chiuso: ${op.kind.code}, cio' che non era partito non parte" }
  }

  override suspend fun retryRequest(op: ShareOpId): ShareRequest? = lock.withLock {
    val record = seen[op] ?: guardedOrNull { ports.diary.closedOps(MAX_PROBLEM_OPS) }?.firstOrNull { it.id == op } ?: return@withLock null
    val items = ports.diary.items(op)
    if (items.isEmpty() || !items.all { it.state.terminal }) return@withLock null
    val media = toRetry(record, items, inFlightMedia())
    if (media.isEmpty()) return@withLock null
    when (record.kind) {
      SharePlanKind.SHARE -> ShareRequest.AddToShared(record.album, media, record.hidden)
      SharePlanKind.ADOPT -> ShareRequest.FolderAdoption(record.album, media)
      SharePlanKind.BRING_BACK -> ShareRequest.BringBack(record.album, media)
    }
  }

  override suspend fun dismiss(op: ShareOpId) = lock.withLock {
    ports.problemMarks.dismiss(op)
    refresh()
  }

  /**
   * Le operazioni per la UI (SH-2): quelle viste da questo processo e, dal diario, le ultime chiuse; una chiusa con problemi ("fatta in parte") si
   * mostra solo se ha ancora elementi da riprovare e l'utente non ha chiuso la sua striscia. Cosi' "N da riprovare" con il motivo resta anche dopo
   * la morte del processo, e sparisce da sola quando "Riprova" li ha rimessi in viaggio o sono arrivati per altra via.
   */
  private suspend fun refresh() {
    val closed = guardedOrNull { ports.diary.closedOps(MAX_PROBLEM_OPS) }.orEmpty()
    val dismissed = guardedOrNull { ports.problemMarks.dismissed() }.orEmpty()
    val busy = inFlightMedia()
    val out = ArrayList<ShareOpState>()
    for (op in seen.values + closed.filter { it.id !in seen }) {
      val items = ports.diary.items(op.id)
      val state = stateOf(op, items)
      // Un'operazione chiusa che questo processo non ha avviato (letta dal diario) vale solo come "N da riprovare": mai come lavoro in corso. Con un
      // elemento fermo dal diario (I5, pacchetto A: una riga che non si legge, in viaggio senza biglietto) `stateOf` direbbe "in corso" per sempre.
      if (op.id !in seen && state !is ShareOpState.PartlyDone) continue
      if (state is ShareOpState.PartlyDone) {
        // Una lettura che non riesce non nasconde niente: la striscia resta (si ricalcola al giro dopo).
        val retry = guardedOrNull { toRetry(op, items, busy) }
        if (op.id in dismissed || (retry != null && retry.isEmpty())) continue
      }
      out += state
    }
    states.value = out
  }

  /** I media che una mia operazione aperta sta ancora portando (un elemento non finito). */
  private suspend fun inFlightMedia(): Set<dev.pampa.mycloudgallery.core.model.MediaId> {
    val out = HashSet<dev.pampa.mycloudgallery.core.model.MediaId>()
    for (open in ports.diary.openOps()) ports.diary.items(open.id).filter { !it.state.terminal }.mapTo(out) { it.media }
    return out
  }

  /**
   * SH-2: gli elementi di [op] (chiusa) da riprovare: falliti per un motivo che un piano nuovo puo' superare (non partiti, trasferimento non
   * riuscito, file cambiato: E-SH18), non in viaggio in un'altra mia operazione e non gia' dall'altra parte (elementi dell'album; per "Riporta"
   * ancora nell'area comune). Un file sparito, un livello che non lo permette piu' (E-SH13, che ha la sua striscia) o un annullamento no.
   */
  private suspend fun toRetry(
    op: ShareOpRecord,
    items: List<ShareItemRecord>,
    busy: Set<dev.pampa.mycloudgallery.core.model.MediaId>,
  ): List<dev.pampa.mycloudgallery.core.model.MediaId> {
    val failed = items.filter { it.state == ShareItemState.FAILED && it.failure in RETRYABLE }.map { it.media }.distinct().filter { it !in busy }
    if (failed.isEmpty()) return failed
    val facts = ports.facts.facts(failed)
    return if (op.kind == SharePlanKind.BRING_BACK) {
      failed.filter { facts[it]?.place == dev.pampa.mycloudgallery.core.albums.contract.MediaPlace.COMMON }
    } else {
      val members = ports.shared.members(op.album).keys
      failed.filter { it in facts && it !in members }
    }
  }

  private suspend fun <T> guardedOrNull(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.d(TAG) { "lettura per le strisce non riuscita (${e.javaClass.simpleName})" }
    null
  }

  companion object {
    private const val TAG = "AlbumCondivisione"

    /** SH-2: le ultime operazioni chiuse guardate per i problemi da riprovare. */
    const val MAX_PROBLEM_OPS: Int = 20

    /** SH-2: i motivi per cui un piano nuovo puo' riuscire. */
    val RETRYABLE: Set<ShareFailure> = setOf(ShareFailure.NOT_STARTED, ShareFailure.TRANSFER_FAILED, ShareFailure.SOURCE_CHANGED)

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
        // SH-2: chi aspetta prima di partire riparte da solo solo finche' vale la conferma (10 minuti); poi diventa "da riprovare".
        val notStarted = items.count { it.state == ShareItemState.PLANNED && it.transferKey != null && it.wait != null }
        ShareOpState.Paused(op.id, op.album, op.kind, wait, done, items.size, notStarted)
      } else {
        ShareOpState.Running(op.id, op.album, op.kind, done, items.size)
      }
    }
  }
}
