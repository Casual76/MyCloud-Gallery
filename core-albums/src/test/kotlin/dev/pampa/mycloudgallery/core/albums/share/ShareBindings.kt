package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferItemOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanHash
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome

// I LEGAMI DEL BANCO (P4): come :app (P13) legera' le porte della condivisione al motore delle operazioni e al catalogo. Scritti qui per provare
// l'orchestratore con il motore vero; P13 li riscrive in :app (`AlbumMover` → `NasOperations.plan/start` con la conferma di `ConfirmationIssuer`
// sullo stesso piano, `TicketStatus` dal diario di core-nasops, `MoveCompletionSink` → `MediaCatalog.attachMoved`).

/**
 * La porta di completamento sul catalogo finto: lo **stesso** media della sorgente prende la posizione della destinazione (D29). Idempotente: una
 * sorgente che il catalogo non ha piu' (gia' collegata) non cambia niente. Mai un'azione sul NAS.
 */
class LibraryCompletions(private val library: FakeAlbumLibrary, private val commonShare: String = ShareNasTarget.COMMON) : MoveCompletionSink {
  var calls: Int = 0
    private set

  override suspend fun published(op: OpId, index: Int, source: NasFileRef, dest: NasFileRef, stamp: NasStamp, sha256: Sha256) {
    calls++
    val moved = library.all().firstOrNull { library.nasRefOf(it) == NasItemRef(source.share, source.path) } ?: return
    val place = if (dest.share.equals(commonShare, ignoreCase = true)) MediaPlace.COMMON else MediaPlace.MY_PRIVATE
    library.move(moved.id, place, dest.path.value)
  }
}

/**
 * `AlbumMover` e i suoi biglietti sopra `NasOperations`: la chiave del trasferimento si lega all'impronta del piano di core-nasops **prima** di
 * avviarlo ([keys], il "database" del legame: sopravvive alla morte del processo), cosi' `find` ritrova l'operazione avviata da un processo morto.
 * La conferma di core-nasops la emette il bersaglio (`TestConfirmations`) solo se quella della condivisione vale (come fara' :app).
 */
class NasAlbumMover(private val target: ShareNasTarget, private val monotonicMs: () -> Long) : AlbumMover {
  val keys: MutableMap<TransferKey, PlanHash> = LinkedHashMap()

  /** I piani avviati, per l'invariante 7 del §5.5 (ogni operazione di core-nasops nasce da una conferma della condivisione). */
  val started: MutableList<PlanHash> = mutableListOf()

  /** Prima di avviare un piano gia' fatto (i test fanno succedere qualcosa fra il piano di core-nasops e l'esecuzione: E-SH18). */
  var beforeStart: () -> Unit = {}

  override suspend fun move(key: TransferKey, items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): TransferStart {
    keys[key]?.let { hash -> target.store.opByPlanHash(hash)?.let { return TransferStart.Started(TransferTicket("op:${it.id.value}")) } }
    if (monotonicMs() - confirmation.issuedAtMonotonicMs !in 0..ShareConfirmationRules.MAX_AGE_MS) return TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED)
    val nasItems = items.map { ref -> target.item(ref.share, ref.path) ?: return TransferStart.Refused(TransferRefusal.PLAN_INVALID) }
    val request = OpRequest.Move(nasItems, dest.share, dest.path)
    val plan = when (val outcome = target.operations.plan(request)) {
      is PlanOutcome.Ready -> outcome.plan
      is PlanOutcome.Rejected -> return TransferStart.Refused(refusalOf(outcome.reason))
    }
    keys[key] = plan.hash
    beforeStart()
    return when (val started = target.start(plan)) {
      is StartOutcome.Started -> TransferStart.Started(TransferTicket("op:${started.id.value}")).also { this.started += plan.hash }
      is StartOutcome.AlreadyStarted -> TransferStart.Started(TransferTicket("op:${started.id.value}"))
      is StartOutcome.ConfirmationRejected -> TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED)
      is StartOutcome.PlanInvalid -> TransferStart.Refused(TransferRefusal.PLAN_INVALID)
      StartOutcome.WritesBlocked -> TransferStart.Refused(TransferRefusal.WRITES_BLOCKED)
    }.also { if (it is TransferStart.Refused) keys.remove(key) }
  }

  private fun refusalOf(reason: PlanRejection): TransferRefusal = when (reason) {
    PlanRejection.NOT_ENOUGH_SPACE -> TransferRefusal.NO_SPACE
    PlanRejection.WRITES_BLOCKED -> TransferRefusal.WRITES_BLOCKED
    PlanRejection.READ_ONLY -> TransferRefusal.READ_ONLY
    PlanRejection.NAS_UNREACHABLE -> TransferRefusal.NAS_UNREACHABLE
    else -> TransferRefusal.PLAN_INVALID
  }

  suspend fun find(key: TransferKey): TransferTicket? =
    keys[key]?.let { target.store.opByPlanHash(it) }?.let { TransferTicket("op:${it.id.value}") }

  suspend fun status(ticket: TransferTicket): TicketState {
    val id = OpId(ticket.value.removePrefix("op:"))
    val op = target.store.op(id) ?: return TicketState.Unknown
    val items = target.store.items(id).filter { !it.isFolder }.sortedBy { it.index }
    val done = items.count { it.state.terminal }
    if (items.all { it.state.terminal }) {
      return TicketState.Ended(
        items.map { item ->
          when (item.state) {
            OpItemState.DONE -> TransferItemOutcome.ARRIVED
            OpItemState.CANCELLED -> TransferItemOutcome.CANCELLED
            else -> when (item.errorCode) {
              OpErrorCode.SOURCE_CHANGED -> TransferItemOutcome.SOURCE_CHANGED
              OpErrorCode.SOURCE_MISSING -> TransferItemOutcome.SOURCE_GONE
              else -> TransferItemOutcome.FAILED
            }
          }
        },
      )
    }
    if (op.state == OpState.PAUSED) {
      val wait = when {
        op.errorCode == OpErrorCode.LOCKED -> ShareWait.LOCKED
        op.pauseReason == PauseReason.FROZEN || op.pauseReason == PauseReason.WRITES_STOPPED || op.pauseReason == PauseReason.CLOCK_JUMP -> ShareWait.WRITES_BLOCKED
        op.pauseReason == PauseReason.NAS_UNREACHABLE -> ShareWait.NAS_UNREACHABLE
        else -> ShareWait.RULES
      }
      return TicketState.Waiting(wait, done, items.size)
    }
    return TicketState.InProgress(done, items.size)
  }

  suspend fun cancel(ticket: TransferTicket) = target.operations.cancel(OpId(ticket.value.removePrefix("op:")))
}

/**
 * `AlbumUploader` del banco: il caricamento diretto nella cartella dell'album dei media solo sul telefono (E-SH1). Qui il "motore" del caricamento
 * e' semplice (il cammino `INCOMING` vero del backup si prova in core-backup): idempotente per chiave, scrive file **interi** (mai a meta'), non
 * sostituisce mai ("nome (2)"; un file con gli stessi byte = gia' li', `ALREADY_THERE`), poi il catalogo collega la posizione allo **stesso**
 * media. Finisce alla prima lettura dello stato.
 */
class PhoneUploader(
  private val target: ShareNasTarget,
  private val library: FakeAlbumLibrary,
  private val phone: Map<MediaId, ByteArray>,
  private val monotonicMs: () -> Long,
) : AlbumUploader {
  private class Upload(val media: List<MediaId>, val dest: ShareFolder) {
    var outcomes: List<TransferItemOutcome>? = null
  }

  private val byKey = LinkedHashMap<TransferKey, Upload>()
  val started: MutableList<TransferKey> = mutableListOf()

  override suspend fun upload(key: TransferKey, media: List<MediaId>, dest: ShareFolder, confirmation: ShareConfirmation): TransferStart {
    if (key in byKey) return TransferStart.Started(TransferTicket("up:${key.value}"))
    if (monotonicMs() - confirmation.issuedAtMonotonicMs !in 0..ShareConfirmationRules.MAX_AGE_MS) return TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED)
    byKey[key] = Upload(media, dest)
    started += key
    return TransferStart.Started(TransferTicket("up:${key.value}"))
  }

  fun find(key: TransferKey): TransferTicket? = if (key in byKey) TransferTicket("up:${key.value}") else null

  fun status(ticket: TransferTicket): TicketState {
    val upload = byKey[TransferKey(ticket.value.removePrefix("up:"))] ?: return TicketState.Unknown
    upload.outcomes?.let { return TicketState.Ended(it) }
    val outcomes = upload.media.map { id -> place(id, upload.dest) }
    upload.outcomes = outcomes
    return TicketState.Ended(outcomes)
  }

  private fun place(id: MediaId, dest: ShareFolder): TransferItemOutcome {
    val bytes = phone[id] ?: return TransferItemOutcome.SOURCE_GONE
    val m = library[id] ?: return TransferItemOutcome.SOURCE_GONE
    if (m.place == MediaPlace.COMMON) return TransferItemOutcome.ALREADY_THERE
    val name = "${id.value.takeLast(12)}.jpg"
    var n = 1
    while (true) {
      val candidate = if (n == 1) name else name.replace(".jpg", " ($n).jpg")
      val path = dest.path.child(candidate)
      val there = target.bytes(dest.share, path.value)
      if (there == null) {
        target.put(dest.share, path.value, bytes)
        library.move(id, MediaPlace.COMMON, path.value)
        return TransferItemOutcome.ARRIVED
      }
      if (there.contentEquals(bytes)) {
        library.move(id, MediaPlace.COMMON, path.value)
        return TransferItemOutcome.ALREADY_THERE
      }
      n++
    }
  }

  fun cancel(ticket: TransferTicket) {
    val upload = byKey[TransferKey(ticket.value.removePrefix("up:"))] ?: return
    if (upload.outcomes == null) upload.outcomes = upload.media.map { TransferItemOutcome.CANCELLED }
  }
}

/** I biglietti dei due trasferimenti insieme (spostamenti di core-nasops e caricamenti). */
class BenchTickets(private val mover: NasAlbumMover, private val uploader: PhoneUploader) : TicketStatus {
  override suspend fun status(ticket: TransferTicket): TicketState =
    if (ticket.value.startsWith("up:")) uploader.status(ticket) else mover.status(ticket)

  override suspend fun find(key: TransferKey): TransferTicket? = mover.find(key) ?: uploader.find(key)

  override suspend fun cancel(ticket: TransferTicket) {
    if (ticket.value.startsWith("up:")) uploader.cancel(ticket) else mover.cancel(ticket)
  }
}
