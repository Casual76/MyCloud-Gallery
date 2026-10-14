package dev.pampa.mycloudgallery.core.index.p3

import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.OutboxIndexPublisher
import dev.pampa.mycloudgallery.core.index.testing.FakeUnitPriority
import dev.pampa.mycloudgallery.core.index.work.LeasedWorkUnits
import dev.pampa.mycloudgallery.core.index.work.RemotePublishedClasses
import dev.pampa.mycloudgallery.core.index.work.TrustWorkLeases
import dev.pampa.mycloudgallery.core.index.work.WorkSource
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers

/** Un media del catalogo simulato, con il suo mese di scatto. */
class SimMedia(val file: TestFile, val month: String)

/**
 * Il catalogo locale di un dispositivo per le unita' di lavoro: tutti i media della share, e quelli che **questo**
 * dispositivo ha gia' preparato ([done]). Le classi che mancano agli altri media sono [needed].
 */
class SimSource(
  private val media: List<SimMedia>,
  private val done: Set<ContentKey>,
  private val needed: Set<SegmentClass> = setOf(SegmentClass.THUMB_S),
) : WorkSource {
  override suspend fun months(share: String, pass: WorkPass): List<String> =
    media.filter { it.file.contentKey !in done }.map { it.month }.distinct().sortedDescending()

  override suspend fun monthsOf(share: String, pass: WorkPass, visible: Set<Long>): List<String> =
    media.filter { it.file.contentKey.short in visible }.map { it.month }.distinct().sortedDescending()

  override suspend fun monthItems(share: String, pass: WorkPass, month: String): List<WorkItem> =
    media.filter { it.month == month }.map { m ->
      WorkItem(
        contentKey = m.file.contentKey,
        path = m.file.path,
        stamp = m.file.stamp,
        kind = MediaKind.PHOTO,
        mime = "image/jpeg",
        mediaId = m.file.mediaId,
        versionId = m.file.versionId,
        missing = if (m.file.contentKey in done) emptySet() else needed,
      )
    }
}

/** I media di una share: [perMonth] per ciascuno dei [months] mesi, gia' nel catalogo. */
fun simMedia(world: TestWorld, share: String, months: List<String>, perMonth: Int, random: Random): List<SimMedia> =
  months.flatMap { month ->
    (1..perMonth).map { i ->
      val file = TestFile.random(share, "Foto/$month/IMG_$i.jpg", random, size = 48)
      world.catalog.put(share, file.path, file.stamp, file.meta().quickHash)
      SimMedia(file, month)
    }
  }

/**
 * Un dispositivo che prepara: unita' di lavoro vere (prenotazioni firmate sul NAS finto, teste rilette), pubblicatore
 * vero, catalogo locale proprio. [restart] simula un processo nuovo (memoria persa, outbox e catalogo no).
 */
class SimWorker(val world: TestWorld, val device: TestDevice, private val media: List<SimMedia>, val share: String = "test-comune") {
  val done = HashSet<ContentKey>()
  private val byKey = media.associateBy { it.file.contentKey }
  var publisher: OutboxIndexPublisher = device.publisher()
  var units: LeasedWorkUnits = newUnits()
  var claim: UnitClaim? = null
  var lastRenew = 0L
  var offlineUntil = Long.MIN_VALUE
  val priority = FakeUnitPriority()

  fun newUnits(): LeasedWorkUnits {
    val remote = device.remote()
    return LeasedWorkUnits(
      identity = device.identity,
      source = SimSource(media, done),
      published = RemotePublishedClasses(remote, startRound = { remote.startRound() }),
      leases = TrustWorkLeases({ world.nas.share(it) }, device.signer, world.clock, { world.trust.state }, Dispatchers.Unconfined),
      rules = IndexWriteRules(device.flags, device.gate),
      unpublished = { s, keys -> publisher.hasPending(s, keys) },
    )
  }

  /** Un processo nuovo: niente in memoria, l'outbox e il catalogo restano. */
  suspend fun restart() {
    publisher = device.publisher()
    publisher.recover()
    units = newUnits()
    claim = null
    publisher.flush(share, FlushReason.PERIODIC)
  }

  /** Prepara [count] media dell'unita' in corso (o ne prende una). Restituisce le chiavi preparate. */
  suspend fun step(count: Int, prepMillis: Long = 20_000): List<ContentKey> {
    val current = claim ?: units.next(share, WorkPass.PREP, priority)?.also {
      claim = it
      lastRenew = world.clock.nowMillis()
    } ?: return emptyList()
    var active = current
    if (world.clock.nowMillis() - lastRenew >= TrustLimits.LEASE_RENEW_MILLIS) {
      when (val renewed = units.keepAlive(active)) {
        is KeepAliveResult.Renewed -> {
          active = renewed.claim
          claim = active
          lastRenew = world.clock.nowMillis()
        }
        KeepAliveResult.Lost -> {
          publisher.flush(share, FlushReason.PERIODIC)
          claim = null
          return emptyList()
        }
      }
    }
    val todo = active.items.filter { it.contentKey !in done }.take(count)
    val prepared = ArrayList<ContentKey>()
    for (item in todo) {
      val file = byKey.getValue(item.contentKey).file
      publisher.stage(share, listOf(file.put(setOf(SegmentClass.THUMB_S))), listOf(file.thumb()))
      done += item.contentKey
      prepared += item.contentKey
      world.clock.advance(prepMillis)
    }
    if (active.items.all { it.contentKey in done }) {
      publisher.flush(share, FlushReason.UNIT_FINISHED)
      units.finish(active, UnitOutcome.Completed(prepared = active.items.size, failed = 0))
      claim = null
    } else {
      publisher.flush(share, FlushReason.PERIODIC)
    }
    return prepared
  }

  /** Fine di una tappa (il sistema ferma il lavoro): si prova a pubblicare, si lascia l'unita', si sparisce per un po'. */
  suspend fun stop(forMillis: Long) {
    publisher.flush(share, FlushReason.PERIODIC)
    claim?.let { units.finish(it, UnitOutcome.Interrupted("STAGE_END")) }
    claim = null
    offlineUntil = world.clock.nowMillis() + forMillis
  }
}
