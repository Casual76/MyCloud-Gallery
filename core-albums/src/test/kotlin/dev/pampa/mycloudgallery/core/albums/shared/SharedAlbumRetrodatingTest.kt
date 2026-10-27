package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * R7 della revisione di sicurezza della Fase 7 (L59): la difesa contro le voci retrodatate non si aggira dichiarando un'ora vecchia. Prima una
 * voce nuova si confrontava solo con l'ora **dichiarata** dalla testa precedente dello stesso dispositivo: chi pubblicava ogni testa con
 * l'ora della creazione dell'album non aveva mai voci retrodatate, e la prima testa di un dispositivo non si controllava affatto. Adesso
 * contano anche le ore di **chi legge**: l'ultima volta che ha visto la serie di quel dispositivo senza niente di nuovo.
 */
class SharedAlbumRetrodatingTest {

  private val world = AlbumWorld()
  private val alice = world.device("test")
  private val bruno = world.device("test2")
  private val carla = world.device("test3")

  private fun file(n: Int): FakeMedia {
    val m = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n * 60_000L, place = MediaPlace.COMMON, path = "Album condivisi/Gita/f$n.jpg")
    world.devices.forEach { it.library.put(m) }
    return m
  }

  private fun entry(album: dev.pampa.mycloudgallery.core.model.AlbumId, m: FakeMedia, by: AlbumDevice, at: Long) =
    AlbumEntry(album, AlbumField.Item(m.id), at, by.deviceId, by.account, AlbumValue.Item(m.contentKey, SharePath.parse(m.path!!), 1, by.account))

  /** Un'ora di giri (uno ogni 30 minuti, come il lavoro dell'indice): pubblicano e leggono tutti. */
  private suspend fun hours(n: Int) = repeat(n * 2) {
    world.clocks.advance(30 * 60_000L)
    world.devices.forEach { it.flushAll() }
    world.devices.forEach { it.syncNow() }
  }

  /** Bruno contribuisce quando l'album e' a "Tutti possono tutto"; poi il livello si stringe. Bruno, compromesso, prova a datare voci nel passato. */
  @Test
  fun unaTestaNuovaConUnOraVecchiaORetrodatataNonPassa() = runBlocking<Unit> {
    val album = alice.createAlbum("Gita", ShareLevel.ALL)
    val t0 = world.clocks.nas!!
    world.settle()
    bruno.publisher.stage(album, listOf(item(file(1), bruno)))
    world.settle()
    val v1 = alice.replicaStore.contribution(album, bruno.deviceId)!!
    hours(2)
    alice.shared.setLevel(album, ShareLevel.VIEW)
    hours(2)
    val old = v1.entries.map { it.entry }

    // (a) Ogni testa con l'ora della creazione dell'album: la testa e' piu' vecchia dell'ultima volta che alice ha visto la serie. Rifiutata.
    RawHeads.write(world, bruno.signer, album, old + entry(album, file(2), bruno, t0 + 60_000), v1.mark.version + 1, createdAt = v1.createdAt)
    hours(1)
    assertThat(alice.view(album)!!.merged.items.keys).doesNotContain(FakeAlbumLibrary.id(2))
    assertThat(alice.sync.retrodatedHeads).isAtLeast(1)

    // (b) Una testa con l'ora di adesso e una voce datata quando l'album era aperto a tutti: la voce e' retrodatata, si scarta.
    RawHeads.write(world, bruno.signer, album, old + entry(album, file(3), bruno, t0 + 60_000), v1.mark.version + 2)
    hours(1)
    assertThat(alice.view(album)!!.merged.items.keys).doesNotContain(FakeAlbumLibrary.id(3))
    assertThat(alice.replicaStore.contribution(album, bruno.deviceId)!!.entries.single { it.entry.field == AlbumField.Item(FakeAlbumLibrary.id(3)) }.retrodated).isTrue()
    // Le voci di bruno di prima restano (non sono nuove).
    assertThat(alice.view(album)!!.merged.items.keys).contains(FakeAlbumLibrary.id(1))
  }

  /** La prima testa di un dispositivo che chi legge ha visto a lungo senza contributo: non puo' datare voci a quando l'album era aperto. */
  @Test
  fun laPrimaTestaDiUnDispositivoVistoSenzaContributoNonRetrodata() = runBlocking<Unit> {
    val album = alice.createAlbum("Gita", ShareLevel.ALL)
    val t0 = world.clocks.nas!!
    world.settle()
    hours(3)
    alice.shared.setLevel(album, ShareLevel.VIEW)
    hours(2)
    RawHeads.write(world, carla.signer, album, listOf(entry(album, file(4), carla, t0 + 60_000)), 1)
    hours(1)
    assertThat(alice.view(album)!!.merged.items.keys).doesNotContain(FakeAlbumLibrary.id(4))
    assertThat(alice.replicaStore.contribution(album, carla.deviceId)!!.entries.single().retrodated).isTrue()
    // Con l'ora della testa nel passato, la testa stessa e' rifiutata.
    val dora = world.device("test4")
    hours(3)
    RawHeads.write(world, dora.signer, album, listOf(entry(album, file(5), dora, t0 + 60_000)), 1, createdAt = t0 + 60_000)
    hours(1)
    assertThat(alice.replicaStore.contribution(album, dora.deviceId)).isNull()
    assertThat(alice.replicaStore.rejectedHead(album, dora.deviceId)).isNotNull()
  }

  /** Chi pubblica onestamente non e' mai retrodatato: nemmeno una modifica rimasta in coda ore (il NAS irraggiungibile) prima di pubblicare. */
  @Test
  fun unaModificaRimastaInCodaOreNonERetrodatata() = runBlocking<Unit> {
    val album = alice.createAlbum("Gita", ShareLevel.ALL)
    world.settle()
    hours(1)
    bruno.publisher.stage(album, listOf(item(file(6), bruno)))
    // Il NAS non c'e' per tre ore (bruno non pubblica), alice intanto guarda la serie di bruno (vuota).
    repeat(6) {
      world.clocks.advance(30 * 60_000L)
      alice.syncNow()
    }
    world.clocks.advance(PublishRules.MIN_HEAD_INTERVAL_MS)
    bruno.flushAll()
    alice.syncNow()
    assertThat(alice.view(album)!!.merged.items.keys).contains(FakeAlbumLibrary.id(6))
    assertThat(alice.replicaStore.contribution(album, bruno.deviceId)!!.entries.none { it.retrodated }).isTrue()
  }

  private fun item(m: FakeMedia, by: AlbumDevice) = SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(m.path!!), 1, by.account)
}
