package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * R2 e R13 della revisione di sicurezza della Fase 7: un contributo rotto (un segmento tolto dal PC, un file che il NAS nega) non blocca la
 * sincronizzazione degli album di tutti e non si riscarica in ciclo; i byte si contano; i tetti per dispositivo si controllano prima di
 * scaricare. Prima: il segmento assente rendeva il giro "incompleto" e lo si riscaricava subito, il cursore restava sull'album rotto, un
 * errore di I/O di un solo file fermava il giro, e i tetti di album e voci si guardavano dopo aver scaricato tutto.
 */
class SharedAlbumRobustnessTest {

  private val world = AlbumWorld()
  private val alice = world.device("test")
  private val bruno = world.device("test2")

  private fun file(n: Int): FakeMedia {
    val m = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n * 60_000L, place = MediaPlace.COMMON, path = "Album condivisi/Gita/f$n.jpg")
    world.devices.forEach { it.library.put(m) }
    return m
  }

  private fun item(m: FakeMedia, by: AlbumDevice, position: Long = m.takenAtUtcMs % 100_000) =
    SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(m.path!!), position, by.account)

  /** Due album di alice: [first] viene prima nella rotazione (id minore), [second] dopo. */
  private suspend fun twoAlbums(): Pair<AlbumId, AlbumId> {
    val one = alice.createAlbum("Uno", ShareLevel.ALL)
    val two = alice.createAlbum("Due", ShareLevel.ALL)
    world.settle()
    return if (AlbumIds.text(one) < AlbumIds.text(two)) one to two else two to one
  }

  /** Conta le letture dei segmenti di un dispositivo in un album (per provare che non si riscaricano). */
  private class Reads(private val delegate: NasFileSystem) : NasFileSystem {
    val paths = mutableListOf<String>()

    override val shareName: String get() = delegate.shareName

    override fun list(dir: SharePath, limit: Int): NasListing = delegate.list(dir, limit)

    override fun read(path: SharePath, maxBytes: Int): NasRead = delegate.read(path, maxBytes).also { if (maxBytes > 0) paths += path.value }

    override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = delegate.publishExclusive(path, bytes)
  }

  @Test
  fun unSegmentoTolto_ilContributoAspettaEGliAltriAlbumSiAggiornano() = runBlocking<Unit> {
    val (first, second) = twoAlbums()
    bruno.publisher.stage(first, (1..3).map { item(file(it), bruno) })
    world.settle()
    // Dal PC, senza chiavi: via un segmento della testa di bruno nel primo album.
    val brunoSegments = world.nas.paths().filter { it.contains("/albums/${AlbumIds.text(first)}/${bruno.deviceId}/seg/") }
    assertThat(brunoSegments).isNotEmpty()
    world.nas.delete(brunoSegments.first())

    var reads: Reads? = null
    val carla = world.device("carla", fs = { Reads(it).also { r -> reads = r } })
    file(9) // anche carla vede i file nell'area comune
    val round1 = carla.syncNow()
    // Il contributo rotto aspetta; il giro non e' "incompleto" per lui; gli altri contributi e il secondo album ci sono.
    assertThat(round1.incomplete).isFalse()
    assertThat(round1.contributionsHeld).isEqualTo(1)
    assertThat(round1.bytesRead).isGreaterThan(0)
    assertThat(carla.replicaStore.hold(first, bruno.deviceId)).isNotNull()
    assertThat(carla.view(first)).isNotNull()
    assertThat(carla.view(second)).isNotNull()

    // Una modifica di alice al secondo album dopo: arriva, e il contributo rotto non si riscarica (nessuna lettura dei suoi segmenti).
    alice.shared.rename(second, "Due bis")
    world.clocks.advance(6 * 60_000L)
    alice.flushAll()
    reads!!.paths.clear()
    val round2 = carla.syncNow()
    assertThat(carla.view(second)!!.merged.title).isEqualTo("Due bis")
    assertThat(reads!!.paths.filter { it.contains("/${bruno.deviceId}/seg/") }).isEmpty()
    assertThat(round2.incomplete).isFalse()

    // Dopo l'attesa si riprova una volta (ancora assente: l'attesa raddoppia).
    world.clocks.advance(AlbumSyncLimits().holdFirstMs + 1)
    carla.syncNow()
    assertThat(carla.replicaStore.hold(first, bruno.deviceId)!!.attempts).isEqualTo(2)

    // Bruno, che rilegge il suo contributo (per esempio con la replica persa), trova il buco e rimette il segmento uguale.
    bruno.replicaStore = MemoryAlbumReplicaStore()
    bruno.restart()
    bruno.syncNow()
    assertThat(bruno.publisher.segmentsRestored).isEqualTo(1)
    assertThat(world.nas.bytes(brunoSegments.first())).isNotNull()
    world.clocks.advance(AlbumSyncLimits().holdMaxMs)
    carla.syncNow()
    assertThat(carla.replicaStore.hold(first, bruno.deviceId)).isNull()
    assertThat(carla.view(first)!!.merged.items.values.count { it.addedBy == bruno.account }).isEqualTo(3)
  }

  /** Un errore di I/O di un solo file (un permesso tolto dal PC su una testa): aspetta solo quel contributo, il giro va avanti. */
  @Test
  fun unFileNegatoFermaSoloIlSuoContributo() = runBlocking<Unit> {
    val (first, second) = twoAlbums()
    bruno.publisher.stage(first, listOf(item(file(1), bruno)))
    world.settle()
    val poison = "/albums/${AlbumIds.text(first)}/${bruno.deviceId}/head/"
    class Denied : IOException("accesso negato")
    val denying: (NasFileSystem) -> NasFileSystem = { delegate ->
      object : NasFileSystem by delegate {
        override fun read(path: SharePath, maxBytes: Int): NasRead {
          if (path.value.contains(poison)) throw Denied()
          return delegate.read(path, maxBytes)
        }
      }
    }
    // Senza la classificazione (come prima): il giro si ferma al primo album.
    val stopped = world.device("dora", fs = denying)
    world.devices.remove(stopped)
    assertThat(stopped.sync.sync(10.minutes).incomplete).isTrue()
    // Con la classificazione di :app (un file negato e' un errore del file): il giro finisce, il secondo album c'e', bruno aspetta.
    val carla = world.device("carla", fs = denying, ioErrors = AlbumIoErrors { it is Denied })
    world.devices.remove(carla)
    val report = carla.sync.sync(10.minutes)
    assertThat(report.incomplete).isFalse()
    assertThat(carla.view(second)).isNotNull()
    assertThat(carla.view(first)!!.merged.winners.values.none { it.author == bruno.deviceId }).isTrue()
    assertThat(carla.replicaStore.hold(first, bruno.deviceId)!!.mark).isNull()
  }

  /** R13: i tetti per dispositivo (album e byte) si controllano prima di scaricare: un contributo oltre il tetto non si scarica nemmeno. */
  @Test
  fun iTettiPerDispositivoPrimaDiScaricare() = runBlocking<Unit> {
    val (first, second) = twoAlbums()
    bruno.publisher.stage(first, listOf(item(file(1), bruno)))
    bruno.publisher.stage(second, listOf(item(file(2), bruno)))
    val third = bruno.createAlbum("Tre", ShareLevel.ALL)
    world.settle()
    var reads: Reads? = null
    // Due album per dispositivo: alice ne ha due (passa), bruno tre (il terzo si rifiuta prima di scaricarlo).
    val capped = world.device("carla", fs = { Reads(it).also { r -> reads = r } }, limits = AlbumSyncLimits(maxAlbumsPerDevice = 2))
    world.devices.remove(capped)
    capped.sync.sync(10.minutes)
    val albums = listOf(first, second, third)
    assertThat(albums.count { capped.replicaStore.contribution(it, bruno.deviceId) != null }).isEqualTo(2)
    val refused = albums.single { capped.replicaStore.rejectedHead(it, bruno.deviceId) != null }
    assertThat(capped.replicaStore.contribution(refused, bruno.deviceId)).isNull()
    assertThat(albums.count { capped.replicaStore.contribution(it, alice.deviceId) != null }).isEqualTo(2)
    // Il segmento del contributo rifiutato non e' mai stato letto.
    assertThat(reads!!.paths.filter { it.contains("/${AlbumIds.text(refused)}/${bruno.deviceId}/seg/") }).isEmpty()
    // Lo stesso con il tetto di byte: niente letto di bruno.
    var bytesReads: Reads? = null
    val tiny = world.device("dora", fs = { Reads(it).also { r -> bytesReads = r } }, limits = AlbumSyncLimits(maxBytesPerDevice = 1))
    world.devices.remove(tiny)
    tiny.sync.sync(10.minutes)
    assertThat(bytesReads!!.paths.filter { it.contains("/seg/") }).isEmpty()
    assertThat(tiny.replicaStore.rejectedHead(first, bruno.deviceId)).isNotNull()
    assertThat(tiny.replicaStore.albums()).isEmpty()
  }
}
