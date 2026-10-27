package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
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
 * Revisione E, confine A/B (SC-1 e R1): il creatore che fa "Smetti di condividere" ha sempre l'album personale, anche se il segno locale "visto attivo"
 * (R1) manca, e la copia non sparisce con il telefono che ha chiuso. Il pacchetto A faceva nascere la copia solo per un album visto attivo da questo
 * dispositivo e la ritirava alla revoca di chi aveva chiuso; per l'album di un altro account e' la difesa di R1, per il mio no: l'id dell'album e'
 * legato all'account che l'ha creato, nessun dispositivo di un altro account puo' farne un "album mio".
 */
class SharedAlbumCreatorCopyTest {

  private val world = AlbumWorld()
  private val alice = world.device("test")
  private val bruno = world.device("test2")

  private fun file(n: Int): FakeMedia {
    val m = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n * 60_000L, place = MediaPlace.COMMON, path = "Album condivisi/Gita/f$n.jpg")
    world.devices.forEach { it.library.put(m) }
    return m
  }

  private fun item(m: FakeMedia, by: AlbumDevice) = SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(m.path!!), m.takenAtUtcMs % 100_000, by.account)

  private suspend fun albumOf(title: String, vararg files: Int): AlbumId {
    val album = alice.createAlbum(title, ShareLevel.ALL)
    alice.publisher.stage(album, files.map { item(file(it), alice) })
    world.settle()
    return album
  }

  /** Il segno "visto attivo" manca (non si e' potuto scrivere, o e' andato perso): dopo il mio "Smetti" l'album personale nasce lo stesso. */
  @Test
  fun ilCreatoreTieneLaCopiaDelSuoAlbumAncheSenzaIlSegnoVistoAttivo() = runBlocking<Unit> {
    val album = albumOf("Gita", 1, 2)
    alice.marks.update { it.clear() }
    assertThat(alice.shared.close(album, CloseMode.STOP, OthersAfterClose.REMOVE)).isEqualTo(CloseOutcome.Closed)
    val copy = alice.shared.keptCopies().single()
    assertThat(copy.mine).isTrue()
    assertThat(copy.items).containsExactly(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))
    // Scritta una volta, non si riscrive (A-3).
    alice.shared.copyKept(copy)
    assertThat(alice.shared.keptCopies()).isEmpty()
  }

  /** Per un album di un altro account senza il segno la difesa di R1 resta: nessuna copia. */
  @Test
  fun perLAlbumDiUnAltroAccountSenzaSegnoNonNascePiuNiente() = runBlocking<Unit> {
    val album = albumOf("Gita", 1)
    bruno.marks.update { it.clear() }
    alice.shared.close(album, CloseMode.STOP, OthersAfterClose.KEEP_COPY)
    world.settle()
    assertThat(bruno.view(album)!!.merged.state).isEqualTo(SharedState.CLOSED)
    assertThat(bruno.shared.keptCopies()).isEmpty()
  }

  /** Un album nato chiuso da un altro account non fa scrivere niente al creatore sbagliato: `creator` e' legato all'id. */
  @Test
  fun unAlbumNatoChiusoDiUnAltroAccountNonDiventaMio() = runBlocking<Unit> {
    val now = world.clocks.nas!!
    val nonce = world.nextNonce()
    val album = AlbumId.derived(bruno.account, nonce)
    val definition = AlbumValue.Definition(
      "Regalo", SharePath.of("Album condivisi", "Regalo"), AlbumDefinitionState.CLOSED, bruno.account, nonce.joinToString("") { "%02x".format(it) }, "keep_copy",
    )
    RawHeads.write(world, bruno.signer, album, listOf(AlbumEntry(album, AlbumField.Definition, now, bruno.deviceId, bruno.account, definition)), 1)
    world.settle()
    assertThat(alice.replica.views.value.values.count { it.merged.state == SharedState.CLOSED }).isEqualTo(1)
    assertThat(alice.shared.keptCopies()).isEmpty()
  }

  /** La revoca del telefono che ha chiuso non ritira la copia del creatore (e continua a ritirare quella degli altri). */
  @Test
  fun laRevocaDelTelefonoCheHaChiusoNonRitiraLaCopiaDelCreatore() = runBlocking<Unit> {
    val album = albumOf("Gita", 1)
    // Un secondo dispositivo del creatore: e' lui a leggere la copia, mentre alice (il telefono che chiude) e' quello che poi si da' per perso.
    val tablet = world.device("test")
    world.settle()
    alice.shared.close(album, CloseMode.STOP, OthersAfterClose.KEEP_COPY)
    world.settle()
    val mine = tablet.shared.keptCopies().single()
    tablet.shared.copyKept(mine)
    val others = bruno.shared.keptCopies().single()
    bruno.shared.copyKept(others)

    world.family.changeStatus(alice.signer, DeviceStatus.LOST)
    world.settle()
    assertThat(tablet.shared.copiesToWithdraw()).isEmpty()
    assertThat(bruno.shared.copiesToWithdraw().map { it.shared }).containsExactly(album)
  }
}
