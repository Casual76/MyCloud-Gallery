package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanHash
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.StageTicket
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferItemOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Il pubblicatore finto del mio contributo (§5.2, P0): coda **durevole** per album (sopravvive a un `InMemoryShareRunner` nuovo: e' il
 * mondo esterno), idempotente per (album, chiave, valore), una testa al piu' ogni [PublishRules.MIN_HEAD_INTERVAL_MS] e accorpamento di
 * [PublishRules.DEBOUNCE_MS] (orologio monotono), dietro il cancello. Una testa pubblicata finisce nel mondo comune [world] (se c'e') con
 * l'account di [family]: li' chi legge applica le sue regole. Il pubblicatore vero (teste e segmenti col codec di P2) e' di P2.
 */
class FakeAlbumPublisher(
  private val clocks: FakeAlbumClocks,
  private val family: FakeAlbumFamily,
  private val gate: AlbumWriteGate,
  private val world: FakeSharedWorld? = null,
) : AlbumPublisher {

  private class Queue {
    var seq = 0L
    var upTo = 0L
    var version = 0L
    var lastHeadAt: Long? = null
    var lastStageAt: Long = Long.MIN_VALUE
    val staged = LinkedHashMap<String, Pair<Long, SharedAlbumChange>>()
    val published = HashMap<String, Pair<Long, SharedAlbumChange>>()
  }

  private val queues = HashMap<AlbumId, Queue>()

  override val pending: MutableStateFlow<Int> = MutableStateFlow(0)

  /** Il prossimo `flush` fallisce come un errore del NAS. */
  var failNext: Boolean = false

  /** Quante teste ha pubblicato, per album. */
  fun heads(album: AlbumId): Long = queues[album]?.version ?: 0

  /** Le voci pubblicate del mio contributo, per chiave. */
  fun published(album: AlbumId): Map<String, SharedAlbumChange> = queues[album]?.published?.mapValues { it.value.second }.orEmpty()

  private fun keyOf(change: SharedAlbumChange): String = when (change) {
    is SharedAlbumChange.Definition -> "definition"
    is SharedAlbumChange.Level -> "level"
    is SharedAlbumChange.Cover -> "cover"
    is SharedAlbumChange.Sort -> "sort"
    is SharedAlbumChange.Item -> "item:${change.media.value}"
    is SharedAlbumChange.ItemRemoved -> "item:${change.media.value}"
    is SharedAlbumChange.Heir -> "heir"
  }

  override suspend fun stage(album: AlbumId, changes: List<SharedAlbumChange>): StageTicket {
    val q = queues.getOrPut(album) { Queue() }
    var last = 0L
    for (change in changes) {
      val key = keyOf(change)
      val staged = q.staged[key]
      val published = q.published[key]
      last = maxOf(last, when {
        staged != null && staged.second == change -> staged.first
        staged == null && published != null && published.second == change -> published.first
        else -> (++q.seq).also { q.staged[key] = it to change }
      })
    }
    q.lastStageAt = clocks.monotonicMs()
    pending.value = queues.values.sumOf { it.staged.size }
    return StageTicket(last)
  }

  override suspend fun flush(album: AlbumId, reason: FlushReason): PublishOutcome {
    if (failNext) {
      failNext = false
      return PublishOutcome.Failed
    }
    val share = family.commonShare() ?: return PublishOutcome.Blocked(AlbumWriteBlock.NO_NAS)
    gate.blockFor(share)?.let { return PublishOutcome.Blocked(it) }
    val q = queues[album]
    if (q == null || q.staged.isEmpty()) return PublishOutcome.NothingToPublish
    val now = clocks.monotonicMs()
    if (reason == FlushReason.DEBOUNCE && now - q.lastStageAt < PublishRules.DEBOUNCE_MS) {
      return PublishOutcome.TooSoon(PublishRules.DEBOUNCE_MS - (now - q.lastStageAt))
    }
    q.lastHeadAt?.let { last -> if (now - last < PublishRules.MIN_HEAD_INTERVAL_MS) return PublishOutcome.TooSoon(PublishRules.MIN_HEAD_INTERVAL_MS - (now - last)) }
    val changes = q.staged.values.sortedBy { it.first }
    world?.apply(album, family.me() ?: return PublishOutcome.Blocked(AlbumWriteBlock.NO_NAS), changes.map { it.second })
    q.staged.forEach { (key, value) -> q.published[key] = value }
    q.upTo = maxOf(q.upTo, changes.maxOf { it.first })
    q.staged.clear()
    q.version++
    q.lastHeadAt = now
    pending.value = queues.values.sumOf { it.staged.size }
    return PublishOutcome.Published(q.version, StageTicket(q.upTo), changes.size)
  }

  override suspend fun publishedUpTo(album: AlbumId): StageTicket = StageTicket(queues[album]?.upTo ?: 0)
}

/**
 * I trasferimenti finti (`AlbumMover`, `AlbumUploader`, `TicketStatus`) sopra la Libreria finta: alla prima lettura dello stato (con
 * [autoComplete]) ogni file arriva a destinazione e la Libreria lo sa **con lo stesso `MediaId`** (porta di completamento, D29).
 * Idempotenti per chiave; [refuseNext], [waiting], [outcomes] e [crashAfterStart] provano rifiuti, attese, esiti per file (E-SH18) e la morte
 * del processo subito dopo l'avvio. [started] registra ogni avvio con l'impronta del piano confermato (invariante 7 del §5.5).
 */
class FakeShareTransfers(private val library: FakeAlbumLibrary) : AlbumMover, AlbumUploader, TicketStatus {

  class Transfer(val key: TransferKey, val ticket: TransferTicket, val media: List<MediaId?>, val dest: ShareFolder, val planHash: SharePlanHash) {
    var ended: List<TransferItemOutcome>? = null
  }

  val started: MutableList<Transfer> = mutableListOf()
  private val byKey = HashMap<TransferKey, Transfer>()
  private val byTicket = HashMap<TransferTicket, Transfer>()

  var autoComplete: Boolean = true
  var refuseNext: TransferRefusal? = null
  var waiting: ShareWait? = null
  val outcomes: MutableMap<MediaId, TransferItemOutcome> = mutableMapOf()

  /** Il prossimo avvio riesce (il trasferimento esiste) ma la chiamata lancia: il processo "muore" prima di sapere il biglietto. */
  var crashAfterStart: Boolean = false

  private fun start(key: TransferKey, media: List<MediaId?>, dest: ShareFolder, confirmation: ShareConfirmation): TransferStart {
    byKey[key]?.let { return TransferStart.Started(it.ticket) }
    refuseNext?.let {
      refuseNext = null
      return TransferStart.Refused(it)
    }
    val t = Transfer(key, TransferTicket("t-${started.size + 1}"), media, dest, confirmation.planHash)
    started += t
    byKey[key] = t
    byTicket[t.ticket] = t
    if (crashAfterStart) {
      crashAfterStart = false
      throw IllegalStateException("morte del processo dopo l'avvio")
    }
    return TransferStart.Started(t.ticket)
  }

  override suspend fun move(key: TransferKey, items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): TransferStart =
    start(key, items.map { ref -> library.all().firstOrNull { library.nasRefOf(it) == ref }?.id }, dest, confirmation)

  override suspend fun upload(key: TransferKey, media: List<MediaId>, dest: ShareFolder, confirmation: ShareConfirmation): TransferStart =
    start(key, media, dest, confirmation)

  /** Con [autoComplete] spento: finisce adesso ogni trasferimento aperto. */
  fun completeAll() = byTicket.values.filter { it.ended == null }.forEach { complete(it) }

  private fun complete(t: Transfer) {
    t.ended = t.media.map { id ->
      val m = id?.let { library[it] } ?: return@map TransferItemOutcome.SOURCE_GONE
      outcomes[m.id]?.let { return@map it }
      val toCommon = t.dest.share == library.shareOf(MediaPlace.COMMON)
      val place = if (toCommon) MediaPlace.COMMON else MediaPlace.MY_PRIVATE
      if (m.place == place) return@map TransferItemOutcome.ALREADY_THERE
      val name = m.path?.substringAfterLast('/') ?: "${m.id.value}.jpg"
      library.move(m.id, place, "${t.dest.path.value}/$name", mine = true)
      TransferItemOutcome.ARRIVED
    }
  }

  override suspend fun status(ticket: TransferTicket): TicketState {
    val t = byTicket[ticket] ?: return TicketState.Unknown
    t.ended?.let { return TicketState.Ended(it) }
    waiting?.let { return TicketState.Waiting(it, 0, t.media.size) }
    if (!autoComplete) return TicketState.InProgress(0, t.media.size)
    complete(t)
    return TicketState.Ended(t.ended!!)
  }

  override suspend fun find(key: TransferKey): TransferTicket? = byKey[key]?.ticket

  override suspend fun cancel(ticket: TransferTicket) {
    val t = byTicket[ticket] ?: return
    if (t.ended == null) t.ended = t.media.map { TransferItemOutcome.CANCELLED }
  }
}
