package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * R1 della revisione di sicurezza della Fase 7 (alta): un album chiuso con "ognuno tiene una copia" (Q5 b) faceva scrivere copie personali a ogni
 * familiare, anche di album mai visti attivi (nati chiusi), senza tetti, crescendo a ogni giro "riapri, aggiungi, richiudi", e le copie
 * restavano dopo la revoca. Adesso: copie solo per album visti attivi, una volta sola, chiusura definitiva, tetti per creatore, e la revoca di
 * chi ha chiuso ritira le copie che ha fatto nascere.
 */
class SharedAlbumClosedCopiesTest {

  private val world = AlbumWorld()
  private val alice = world.device("test")
  private val bruno = world.device("test2")

  private fun file(n: Int): FakeMedia {
    val m = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n * 60_000L, place = MediaPlace.COMMON, path = "Album condivisi/Gita/f$n.jpg")
    world.devices.forEach { it.library.put(m) }
    return m
  }

  private fun item(m: FakeMedia, by: AlbumDevice) = SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(m.path!!), m.takenAtUtcMs % 100_000, by.account)

  private suspend fun albumSeenByAll(title: String, vararg files: Int): AlbumId {
    val album = alice.createAlbum(title, ShareLevel.ALL)
    alice.publisher.stage(album, files.map { item(file(it), alice) })
    world.settle()
    assertThat(bruno.view(album)!!.merged.state).isEqualTo(SharedState.ACTIVE)
    return album
  }

  /** Lo scenario della revisione: un dispositivo compromesso pubblica album **nati chiusi** con `keep_copy`: niente copie, niente avvisi. */
  @Test
  fun albumNatiChiusiNonFannoNascereCopieNeAvvisi() = runBlocking<Unit> {
    val now = world.clocks.nas!!
    repeat(5) { n ->
      val nonce = world.nextNonce()
      val album = AlbumId.derived(alice.account, nonce)
      val definition = AlbumValue.Definition(
        "Regalo $n", SharePath.of("Album condivisi", "Regalo $n"), AlbumDefinitionState.CLOSED, alice.account, nonce.joinToString("") { "%02x".format(it) }, "keep_copy",
      )
      RawHeads.write(world, alice.signer, album, listOf(AlbumEntry(album, AlbumField.Definition, now, alice.deviceId, alice.account, definition)), 1)
    }
    world.settle()
    assertThat(bruno.replica.views.value.values.count { it.merged.state == SharedState.CLOSED }).isEqualTo(5)
    assertThat(bruno.shared.keptCopies()).isEmpty()
    assertThat(bruno.shared.notices.value.filterIsInstance<SharedAlbumNotice.Closed>()).isEmpty()
  }

  /** Visto attivo e poi chiuso: la copia nasce una volta; "riapri, aggiungi, richiudi" dallo stesso dispositivo del creatore non la allunga. */
  @Test
  fun laChiusuraVistaEDefinitivaELaCopiaSiScriveUnaVolta() = runBlocking<Unit> {
    val album = albumSeenByAll("Gita", 1, 2)
    alice.shared.close(album, CloseMode.STOP, OthersAfterClose.KEEP_COPY)
    world.settle()
    val copy = bruno.shared.keptCopies().single()
    assertThat(copy.items).containsExactly(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))
    assertThat(copy.cause).isEqualTo(alice.deviceId)
    assertThat((bruno.shared.notices.value.single() as SharedAlbumNotice.Closed).keptCopy).isEqualTo(copy.copy)
    bruno.shared.copyKept(copy)
    assertThat(bruno.shared.keptCopies()).isEmpty()

    // Il telefono di alice (compromesso) ripubblica la definizione `active`, aggiunge una foto e richiude.
    val m = bruno.view(album)!!.merged
    alice.publisher.stage(album, listOf(SharedAlbumChange.Definition(m.title, m.folder, SharedState.ACTIVE, m.creator, m.nonceHex, null)))
    alice.publisher.stage(album, listOf(item(file(3), alice)))
    world.settle()
    val reopened = bruno.view(album)!!.merged
    assertThat(reopened.state).isEqualTo(SharedState.CLOSED)
    assertThat(reopened.items.keys).doesNotContain(FakeAlbumLibrary.id(3))
    assertThat(bruno.shared.visible.value).isEmpty()
    alice.publisher.stage(album, listOf(SharedAlbumChange.Definition(m.title, m.folder, SharedState.CLOSED, m.creator, m.nonceHex, OthersAfterClose.KEEP_COPY)))
    world.settle()
    assertThat(bruno.shared.keptCopies()).isEmpty()
    assertThat(bruno.replica.reopeningsRefused).isAtLeast(1)
  }

  /** Tetti per creatore: oltre, la copia non nasce e l'avviso non la promette. */
  @Test
  fun leCopieHannoUnTettoPerCreatore() = runBlocking<Unit> {
    val albums = listOf(albumSeenByAll("Uno", 1), albumSeenByAll("Due", 2), albumSeenByAll("Tre", 3))
    val limited = ReplicaSharedAlbums(
      bruno.identity, bruno.albumFamily, bruno.replica, bruno.publisher, bruno.gate, world.clocks, bruno.library, bruno.choices,
      copyLimits = ClosedCopyLimits(maxCopiesPerCreator = 2, maxItemsPerCreator = 20_000),
    )
    for (album in albums) alice.shared.close(album, CloseMode.STOP, OthersAfterClose.KEEP_COPY)
    world.settle()
    limited.refresh()
    assertThat(limited.keptCopies()).hasSize(2)
    assertThat(limited.copiesCapped).isEqualTo(1)
    val notices = limited.notices.value.filterIsInstance<SharedAlbumNotice.Closed>()
    assertThat(notices).hasSize(3)
    assertThat(notices.count { it.keptCopy != null }).isEqualTo(2)
    // Le copie gia' scritte contano: scritte le due, la terza resta fuori.
    limited.keptCopies().forEach { limited.copyKept(it) }
    assertThat(limited.keptCopies()).isEmpty()
    val items = ReplicaSharedAlbums(
      bruno.identity, bruno.albumFamily, bruno.replica, bruno.publisher, bruno.gate, world.clocks, bruno.library, bruno.choices,
      copyLimits = ClosedCopyLimits(maxCopiesPerCreator = 32, maxItemsPerCreator = 2),
    )
    assertThat(items.keptCopies()).isEmpty()
  }

  /** La revoca del dispositivo che ha chiuso ritira le copie che ha fatto nascere; una dismissione no. */
  @Test
  fun laRevocaDiChiHaChiusoRitiraLeCopie() = runBlocking<Unit> {
    val album = albumSeenByAll("Gita", 1)
    alice.shared.close(album, CloseMode.DELETE, OthersAfterClose.KEEP_COPY)
    world.settle()
    val copy = bruno.shared.keptCopies().single()
    bruno.shared.copyKept(copy)
    assertThat(bruno.shared.copiesToWithdraw()).isEmpty()
    world.family.changeStatus(alice.signer, DeviceStatus.REVOKED)
    world.settle()
    val withdraw = bruno.shared.copiesToWithdraw().single()
    assertThat(withdraw.copy).isEqualTo(copy.copy)
    assertThat(withdraw.shared).isEqualTo(album)
    bruno.shared.copyWithdrawn(album)
    assertThat(bruno.shared.copiesToWithdraw()).isEmpty()
    // Ritirata non si ripropone.
    assertThat(bruno.shared.keptCopies()).isEmpty()
  }
}
