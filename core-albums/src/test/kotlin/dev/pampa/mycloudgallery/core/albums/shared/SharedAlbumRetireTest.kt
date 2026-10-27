package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.InheritOutcome
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * R6 della revisione di sicurezza della Fase 7: dismettere un dispositivo (il gesto normale quando si cambia telefono) non fa sparire i suoi
 * contributi agli album: le sue teste d'album accettate si congelano nel ritiro ([RetireAlbumHeads], legato da :app al registro), come promette
 * A7. E l'eredita' resta quando si dismette il telefono admin dell'erede. Prima nessuna testa si congelava (un dismesso valeva come un
 * revocato: senza la definizione del creatore l'album "non e' creato") e la voce `heir` valeva solo se il suo dispositivo era approvato adesso.
 */
class SharedAlbumRetireTest {

  private val world = AlbumWorld()
  private val alice = world.device("test")
  private val bruno = world.device("test2")

  private fun file(n: Int): FakeMedia {
    val m = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n * 60_000L, place = MediaPlace.COMMON, path = "Album condivisi/Gita/f$n.jpg")
    world.devices.forEach { it.library.put(m) }
    return m
  }

  private fun item(m: FakeMedia, by: AlbumDevice) = SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(m.path!!), m.takenAtUtcMs % 100_000, by.account)

  private suspend fun albumWithItems(): AlbumId {
    val album = alice.createAlbum("Gita", ShareLevel.ADD)
    alice.publisher.stage(album, listOf(item(file(1), alice), item(file(2), alice)))
    world.settle()
    bruno.publisher.stage(album, listOf(item(file(3), bruno)))
    world.settle()
    assertThat(bruno.view(album)!!.merged.items).hasSize(3)
    return album
  }

  @Test
  fun dismettereIlTelefonoDelCreatoreNonFaSparireLAlbum() = runBlocking<Unit> {
    val album = albumWithItems()
    // L'admin dismette il vecchio telefono di alice: le teste che la sua replica ha accettato (qui quella di bruno) si congelano.
    val frozen = RetireAlbumHeads.of(bruno.replicaStore, alice.deviceId, TrustLimits.MAX_FROZEN_HEADS)
    assertThat(frozen).containsExactly(bruno.replicaStore.contribution(album, alice.deviceId)!!.mark.contentHash)
    world.family.changeStatus(alice.signer, DeviceStatus.RETIRED, frozen)
    world.settle()
    val view = bruno.view(album)!!
    assertThat(view.merged.title).isEqualTo("Gita")
    assertThat(view.merged.items).hasSize(3)
    // Il telefono nuovo di alice (stesso account) e' ancora il creatore: puo' cambiare il livello.
    val newPhone = world.device("test")
    world.settle()
    assertThat(newPhone.shared.allowed(album, dev.pampa.mycloudgallery.core.albums.contract.AlbumOp.CHANGE_LEVEL)).isTrue()
  }

  @Test
  fun senzaTesteCongelateIlDismessoValeComeUnRevocato() = runBlocking<Unit> {
    // Il comportamento di prima della correzione (nessuna testa congelata): l'album sparisce. Resta vero per un dismesso senza teste.
    val album = albumWithItems()
    world.family.changeStatus(alice.signer, DeviceStatus.RETIRED)
    world.settle()
    assertThat(bruno.view(album)).isNull()
  }

  @Test
  fun lEreditaRestaQuandoSiDismetteIlTelefonoAdminDellErede() = runBlocking<Unit> {
    val dora = world.device("test4", admin = true)
    val album = albumWithItems()
    // Alice esce dalla famiglia (tutti i suoi dispositivi revocati); dora, admin, eredita e ricostituisce l'album.
    world.family.removeAccount("test")
    world.settle()
    assertThat(dora.shared.inherit(album)).isEqualTo(InheritOutcome.Inherited)
    world.settle()
    assertThat(bruno.view(album)!!.merged.heir).isEqualTo(dora.account)
    // Dora cambia telefono: il vecchio, admin, si dismette con le sue teste d'album congelate.
    val frozen = RetireAlbumHeads.of(bruno.replicaStore, dora.deviceId, TrustLimits.MAX_FROZEN_HEADS)
    world.family.changeStatus(dora.signer, DeviceStatus.RETIRED, frozen)
    world.settle()
    val view = bruno.view(album)!!
    assertThat(view.merged.heir).isEqualTo(dora.account)
    assertThat(view.merged.items.keys).containsAtLeast(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2), FakeAlbumLibrary.id(3))
  }

  /** Le teste piu' importanti prima (definizione e livello), oltre il tetto le altre restano fuori; mai un contributo escluso. */
  @Test
  fun leTesteDaCongelareHannoUnOrdineEUnTetto() = runBlocking<Unit> {
    val own = alice.createAlbum("Mio", ShareLevel.ALL)
    val other = albumWithItems()
    world.settle()
    val all = RetireAlbumHeads.of(bruno.replicaStore, alice.deviceId, 10)
    assertThat(all).hasSize(2)
    assertThat(RetireAlbumHeads.of(bruno.replicaStore, alice.deviceId, 1)).hasSize(1)
    assertThat(RetireAlbumHeads.of(bruno.replicaStore, bruno.deviceId, 10))
      .containsExactly(bruno.replicaStore.contribution(other, bruno.deviceId)!!.mark.contentHash)
    assertThat(own).isNotNull()
  }
}
