package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * IL BANCO DEGLI ALBUM CONDIVISI (P2, "Fatto quando": 200 giri del banco con `FaultPlan`). In ogni giro tre account e quattro dispositivi
 * lavorano su due album (aggiunte, lapidi, rinomine, copertine, ordini, livelli, a volte una chiusura, pubblicazioni e giri della replica)
 * con un guasto del NAS **prima o dopo** il 20% delle chiamate e morti del processo a caso; poi il NAS torna buono e tutti pubblicano e
 * leggono. Invarianti, a ogni giro:
 * 1. niente resta in coda e ogni modifica accodata e' dentro una testa pubblicata;
 * 2. tutti i dispositivi vedono lo stesso stato fuso di ogni album;
 * 3. un dispositivo nuovo che legge da zero vede lo stesso stato (la replica incrementale = quella da zero);
 * 4. ogni dispositivo ha scritto solo sotto `albums/<album>/<se stesso>/` (e solo con `publishExclusive`);
 * 5. nessun contributo onesto e' mai stato rifiutato da nessuno, nessun errore fuori dai guasti di rete;
 * 6. ogni elemento vivo rimanda a un file dell'area comune che il catalogo attesta (nessuna voce per un file assente, D9).
 */
class SharedAlbumBenchTest {

  /** Una share che ricorda le scritture di un dispositivo. */
  private class Recording(private val delegate: NasFileSystem) : NasFileSystem {
    val writes = mutableListOf<String>()
    override val shareName: String get() = delegate.shareName

    override fun list(dir: SharePath, limit: Int): NasListing = delegate.list(dir, limit)

    override fun read(path: SharePath, maxBytes: Int): NasRead = delegate.read(path, maxBytes)

    override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
      writes += path.value
      return delegate.publishExclusive(path, bytes)
    }
  }

  @Test
  fun duecentoGiriDelBancoConGuastiPrimaEDopoOgniChiamata() = runBlocking<Unit> {
    var fired = 0
    var published = 0
    repeat(200) { seed ->
      val (f, p) = round(seed)
      fired += f
      published += p
    }
    // Il banco ha davvero guastato e pubblicato.
    assertThat(fired).isGreaterThan(1_000)
    assertThat(published).isGreaterThan(1_000)
  }

  private suspend fun round(seed: Int): Pair<Int, Int> {
    val random = Random(seed)
    val world = AlbumWorld()
    val plans = ArrayList<AlbumFaultPlan>()
    val recordings = ArrayList<Recording>()
    val files = (1..10).map { FakeMedia(FakeAlbumLibrary.id(it), 1_789_000_000_000L + it * 60_000L, place = MediaPlace.COMMON, path = "Album condivisi/Banco/f$it.jpg") }
    val devices = listOf("test", "test", "test2", "carla").map { account ->
      val plan = AlbumFaultPlan(seed * 31L + plans.size, 0.2).also { plans += it }
      val recording = Recording(FaultyFileSystem(world.nas, plan)).also { recordings += it }
      world.device(account, fs = { recording }).also { d -> files.forEach { d.library.put(it) } }
    }
    plans.forEach { it.enabled = false }
    val albums = listOf(devices[0].createAlbum("Uno", ShareLevel.ADD), devices[2].createAlbum("Due", ShareLevel.ALL))
    world.settle()
    plans.forEach { it.enabled = true }
    val tickets = HashMap<Pair<AlbumDevice, AlbumId>, Long>()
    repeat(60) { step ->
      // A meta' un giro di sincronizzazione intero sotto i guasti.
      if (step == 30) world.settle(1)
      val d = devices.random(random)
      val album = albums.random(random)
      val file = files.random(random)
      when (random.nextInt(17)) {
        0, 1, 2 -> if (d.shared.allowed(album, AlbumOp.ADD_MEDIA)) {
          val change = SharedAlbumChange.Item(file.id, null, file.contentKey, SharePath.parse(file.path!!), random.nextLong(0, 100_000), d.account)
          tickets.merge(d to album, d.publisher.stage(album, listOf(change)).seq, ::maxOf)
        }
        3 -> d.shared.remove(album, listOf(file.id))
        4 -> d.shared.rename(album, listOf("Uno", "Due", "Banco", "Gita al mare").random(random))
        5 -> d.shared.setCover(album, if (random.nextBoolean()) file.id else null)
        6 -> d.shared.setSort(album, AlbumSort.entries.random(random))
        7 -> d.shared.setLevel(album, ShareLevel.entries.random(random))
        8, 9, 10 -> d.publisher.flush(album, FlushReason.entries.random(random))
        11, 12, 13 -> d.syncNow()
        14 -> world.clocks.advance(random.nextLong(1_000, PublishRules.MIN_HEAD_INTERVAL_MS + 60_000))
        15 -> d.restart()
        else -> if (random.nextInt(8) == 0) d.shared.close(album, CloseMode.entries.random(random), OthersAfterClose.entries.random(random))
      }
    }
    val fired = plans.sumOf { it.fired }
    plans.forEach { it.enabled = false }
    world.settle(3)

    // 1. Niente in coda; ogni modifica accodata e' pubblicata.
    for (d in devices) assertWithMessage("giro $seed: in coda").that(d.publisher.pending.value).isEqualTo(0)
    for ((key, ticket) in tickets) assertWithMessage("giro $seed").that(key.first.publisher.publishedUpTo(key.second).seq).isAtLeast(ticket)
    // 2. Tutti vedono lo stesso stato.
    for (album in albums) {
      val states = devices.map { it.view(album)?.merged }
      assertWithMessage("giro $seed: stato dell'album").that(states.toSet()).hasSize(1)
    }
    // 3. Da zero, lo stesso stato.
    val fresh = world.device("test2")
    files.forEach { fresh.library.put(it) }
    fresh.syncNow()
    for (album in albums) assertWithMessage("giro $seed: replica da zero").that(fresh.view(album)?.merged).isEqualTo(devices[0].view(album)?.merged)
    // 4. Solo sotto la propria cartella.
    for ((i, d) in devices.withIndex()) {
      for (path in recordings[i].writes) {
        val ok = albums.any { album -> path.startsWith(".mycloudgallery/albums/${AlbumIds.text(album)}/${d.deviceId}/") }
        assertWithMessage("giro $seed: $path").that(ok).isTrue()
      }
    }
    // 5. Nessun contributo onesto rifiutato, nessun errore isolato (i guasti sono errori del NAS, non dei dati).
    for (d in devices + fresh) {
      assertWithMessage("giro $seed: rifiutati").that(d.reports.sumOf { it.contributionsRejected }).isEqualTo(0)
      assertWithMessage("giro $seed: errori").that(d.sync.syncErrors).isEqualTo(0)
    }
    // 6. Ogni elemento vivo rimanda a un file dell'area comune attestato dal catalogo.
    for (album in albums) {
      val view = devices[0].view(album) ?: continue
      assertWithMessage("giro $seed: elementi attestati").that(view.unattested).isEqualTo(0)
    }
    return fired to world.nas.published.count { it.value.contains("/head/") }
  }
}
