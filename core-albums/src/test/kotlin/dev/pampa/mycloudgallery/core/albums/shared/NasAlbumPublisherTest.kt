package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
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
import org.junit.Assert.assertThrows
import org.junit.Test

/** Il pubblicatore vero (P2, D6, §2.5 passo 6). */
class NasAlbumPublisherTest {

  /** Una share che ricorda ogni chiamata di chi la usa. */
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

  private fun media(n: Int) = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n, place = MediaPlace.COMMON, path = "Album condivisi/Gita/f$n.jpg")

  private fun item(n: Int, device: AlbumDevice) = media(n).let { SharedAlbumChange.Item(it.id, null, it.contentKey, SharePath.parse(it.path!!), n * 1_000L, device.account) }

  /**
   * Proprieta' (P2, "Fatto quando"): con modifiche, pubblicazioni, guasti prima e dopo ogni chiamata e morti del processo a caso, il
   * pubblicatore scrive **solo** sotto `albums/<album>/<se stesso>/` (`head/<20 cifre>` o `seg/<impronta>`) e solo con `publishExclusive`
   * (l'interfaccia del NAS non ha altro), e alla fine tutto cio' che ha accodato e' pubblicato e si rilegge.
   */
  @Test
  fun scriveSoloSottoLaPropriaCartellaESoloConPublishExclusive() = runBlocking<Unit> {
    repeat(60) { seed ->
      val random = Random(seed)
      val world = AlbumWorld()
      val faults = AlbumFaultPlan(seed.toLong(), 0.2)
      val recording = Recording(FaultyFileSystem(world.nas, faults))
      val me = world.device("test", fs = { recording })
      val other = world.device("test2")
      val albums = (1..3).map { me.createAlbum("Album $it", ShareLevel.ALL) } + other.createAlbum("Altrui", ShareLevel.ALL)
      faults.enabled = false
      world.settle()
      faults.enabled = true
      val tickets = HashMap<AlbumId, Long>()
      repeat(40) {
        val album = albums.random(random)
        when (random.nextInt(6)) {
          0, 1 -> tickets.merge(album, me.publisher.stage(album, listOf(item(random.nextInt(30), me))).seq, ::maxOf)
          2 -> tickets.merge(album, me.publisher.stage(album, listOf(SharedAlbumChange.Sort(AlbumSort.entries.random(random)))).seq, ::maxOf)
          3 -> runCatching { me.publisher.flush(album, FlushReason.entries.random(random)) }
          4 -> world.clocks.advance(random.nextLong(1_000, PublishRules.MIN_HEAD_INTERVAL_MS * 2))
          else -> me.restart()
        }
      }
      faults.enabled = false
      world.settle(3)
      val prefix = { album: AlbumId -> ".mycloudgallery/albums/${AlbumIds.text(album)}/${me.deviceId}/" }
      for (path in recording.writes) {
        val album = albums.firstOrNull { path.startsWith(prefix(it)) }
        assertWithMessage("seme $seed: $path fuori dalla mia cartella").that(album).isNotNull()
        val rest = path.removePrefix(prefix(album!!))
        assertWithMessage("seme $seed: $path").that(rest.matches(Regex("head/[0-9]{20}|seg/[0-9a-z]{52}"))).isTrue()
      }
      // L'album di un altro: mai una scrittura sotto la sua cartella, anche se vi ho accodato modifiche.
      assertThat(recording.writes.none { it.contains("/${other.deviceId}/") }).isTrue()
      assertThat(me.publisher.pending.value).isEqualTo(0)
      for ((album, ticket) in tickets) assertWithMessage("seme $seed").that(me.publisher.publishedUpTo(album).seq).isAtLeast(ticket)
      // Chi legge rilegge esattamente il mio contributo pubblicato.
      for (album in albums.filter { it != albums.last() }) {
        val mine = other.replicaStore.contribution(album, me.deviceId)!!.entries.map { it.entry }.toSet()
        assertWithMessage("seme $seed").that(mine).isEqualTo(me.publishStore.load(album)!!.published.values.map { it.entry }.toSet())
      }
    }
  }

  /** Lo store locale perso (reinstallazione con la stessa chiave): la testa nuova ripubblica le voci di prima, nessuna sparisce. */
  @Test
  fun lostorePersoSiRiprendeDalNas() = runBlocking<Unit> {
    val world = AlbumWorld()
    val me = world.device("test")
    val other = world.device("test2")
    val album = me.createAlbum("Gita", ShareLevel.ALL)
    me.publisher.stage(album, (1..5).map { item(it, me) })
    world.settle()
    me.publishStore = MemoryAlbumPublishStore()
    me.restart()
    me.publisher.stage(album, listOf(item(6, me)))
    world.settle()
    assertThat(other.view(album)!!.merged.items).hasSize(6)
    assertThat(other.view(album)!!.merged.title).isEqualTo("Gita")
  }

  /** Segmenti stabili: una modifica in un album grande riscrive un segmento solo (chi legge riscarica solo quello). */
  @Test
  fun unaModificaRiscriveUnSegmentoSolo() = runBlocking<Unit> {
    val world = AlbumWorld()
    val me = world.device("test")
    val album = me.createAlbum("Grande", ShareLevel.ALL)
    me.publisher.stage(album, (1..3_000).map { item(it, me) })
    world.settle()
    val before = world.nas.published.size
    me.publisher.stage(album, listOf(item(5_000, me)))
    world.settle()
    val written = world.nas.published.drop(before).filter { it.value.contains("/${me.deviceId}/") }
    assertThat(written.count { it.value.contains("/seg/") }).isEqualTo(1)
    assertThat(written.count { it.value.contains("/head/") }).isEqualTo(1)
    assertThat(AlbumSegmentPlan.buckets(3_002)).isEqualTo(8)
  }

  /** Una modifica che chi legge rifiuterebbe (un percorso troppo lungo, un titolo con un carattere invisibile) non si accoda nemmeno. */
  @Test
  fun unaModificaNonAmmessaSiRifiutaPrimaDelNas() = runBlocking<Unit> {
    val world = AlbumWorld()
    val me = world.device("test")
    val album = me.createAlbum("Gita", ShareLevel.ALL)
    world.settle()
    val long = SharePath.parse(List(5) { "a".repeat(250) }.joinToString("/"))
    val m = media(1)
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { me.publisher.stage(album, listOf(SharedAlbumChange.Item(m.id, null, m.contentKey, long, 1, me.account))) }
    }
    val view = me.view(album)!!.merged
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { me.publisher.stage(album, listOf(SharedAlbumChange.Definition("Gita​", view.folder, SharedState.ACTIVE, view.creator, view.nonceHex, null))) }
    }
    assertThat(me.publishStore.load(album)!!.staged).isEmpty()
    assertThat(me.publisher.pending.value).isEqualTo(0)
  }

  /** Con l'ora del NAS tornata indietro, una voce nuova non e' mai datata prima della mia ultima testa: chi legge non la scarta. */
  @Test
  fun maiUnaVoceDatataPrimaDellUltimaTesta() = runBlocking<Unit> {
    val world = AlbumWorld()
    val me = world.device("test")
    val other = world.device("test2")
    val album = me.createAlbum("Gita", ShareLevel.ALL)
    world.settle()
    world.clocks.nas = world.clocks.nas!! - 30 * 60_000L
    me.publisher.stage(album, listOf(item(1, me)))
    world.clocks.nas = world.clocks.nas!! + 40 * 60_000L
    world.settle()
    assertThat(other.view(album)!!.merged.items).hasSize(1)
    assertThat(other.replicaStore.contribution(album, me.deviceId)!!.entries.none { it.retrodated }).isTrue()
  }

  /** Il cancello: un blocco (congelamento, sola lettura, recinzione) non scrive niente e non perde niente (D30, D38). */
  @Test
  fun dietroIlCancelloNienteSiScrive() = runBlocking<Unit> {
    val world = AlbumWorld()
    val me = world.device("test")
    val album = me.createAlbum("Gita", ShareLevel.ALL)
    me.gate.blocks["test-comune"] = dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock.FROZEN
    val before = world.nas.published.size
    world.clocks.advance(PublishRules.MIN_HEAD_INTERVAL_MS)
    assertThat(me.publisher.flush(album, FlushReason.SYNC_ROUND)).isEqualTo(PublishOutcome.Blocked(dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock.FROZEN))
    assertThat(world.nas.published.size).isEqualTo(before)
    assertThat(me.publisher.pending.value).isEqualTo(2)
    me.gate.blocks.clear()
    assertThat(me.publisher.flush(album, FlushReason.SYNC_ROUND)).isInstanceOf(PublishOutcome.Published::class.java)
  }
}
