package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * DG-3 (verifica funzionale della Fase 7, revisione E): il rifiuto di un contributo ha il suo **motivo**, in nomi fissi (`AlbumRejections`, o il nome di
 * `RejectReason`), e non sparisce con il giro che lo ha visto: il resoconto del giro lo conta per motivo e il segno durevole nella replica lo conserva
 * (`rejectedByReason`). Prima si contava solo "quanti", e solo nel giro in cui nasceva.
 */
class SharedAlbumRejectionReasonsTest {

  private val world = AlbumWorld()
  private val alice = world.device("test")

  private fun file(n: Int): FakeMedia {
    val m = FakeMedia(FakeAlbumLibrary.id(n), 1_789_000_000_000L + n * 60_000L, place = MediaPlace.COMMON, path = "Album condivisi/Gita/f$n.jpg")
    world.devices.forEach { it.library.put(m) }
    return m
  }

  private fun entry(album: dev.pampa.mycloudgallery.core.model.AlbumId, m: FakeMedia, by: AlbumDevice, at: Long) =
    AlbumEntry(album, AlbumField.Item(m.id), at, by.deviceId, by.account, AlbumValue.Item(m.contentKey, SharePath.parse(m.path!!), 1, by.account))

  private suspend fun hours(n: Int) = repeat(n * 2) {
    world.clocks.advance(30 * 60_000L)
    world.devices.forEach { it.flushAll() }
    world.devices.forEach { it.syncNow() }
  }

  /** Una testa che dichiara un'ora prima di cio' che chi legge ha gia' visto: il motivo e' `RETRODATED`, nel giro e nel segno durevole. */
  @Test
  fun unaTestaRetrodataDiceIlSuoMotivo() = runBlocking<Unit> {
    val album = alice.createAlbum("Gita", ShareLevel.ALL)
    val t0 = world.clocks.nas!!
    world.settle()
    // Un dispositivo del registro che alice guarda da ore e che non ha ancora pubblicato niente (R7: alice ricorda l'ora in cui ha visto la sua serie vuota).
    val dora = world.device("test4")
    hours(3)
    RawHeads.write(world, dora.signer, album, listOf(entry(album, file(5), dora, t0 + 60_000)), 1, createdAt = t0 + 60_000)
    world.clocks.advance(30 * 60_000L)
    val report = alice.syncNow()
    assertThat(report.contributionsRejected).isEqualTo(1)
    assertThat(report.rejectedByReason).containsExactly(AlbumRejections.RETRODATED, 1)
    // Il segno dura oltre il giro: al giro dopo la testa non si riscarica (e non si riconta), ma il motivo resta nella replica.
    assertThat(alice.syncNow().rejectedByReason).isEmpty()
    assertThat(alice.replicaStore.rejectedByReason()).containsExactly(AlbumRejections.RETRODATED, 1)
  }

  /** I tetti per dispositivo prima di scaricare: il motivo e' `OVER_LIMITS`. */
  @Test
  fun unTettoSuperatoDiceIlSuoMotivo() = runBlocking<Unit> {
    val reader = world.device("test2", limits = AlbumSyncLimits(maxAlbumsPerDevice = 0))
    alice.createAlbum("Gita", ShareLevel.ALL)
    world.clocks.advance(5 * 60_000L)
    alice.flushAll()
    val report = reader.syncNow()
    assertThat(report.rejectedByReason).containsExactly(AlbumRejections.OVER_LIMITS, 1)
    assertThat(report.rejectedByReason.values.sum()).isEqualTo(report.contributionsRejected)
    assertThat(reader.replicaStore.rejectedByReason()).containsExactly(AlbumRejections.OVER_LIMITS, 1)
  }

  /** Il conto per motivo segue i segni: si toglie quando il segno si toglie (il dispositivo pubblica una testa buona); un motivo non ammesso vale `UNKNOWN`. */
  @Test
  fun ilConteggioPerMotivoSegueISegni() = runBlocking<Unit> {
    val store = MemoryAlbumReplicaStore()
    val album = alice.createAlbum("Gita", ShareLevel.ALL)
    store.setRejectedHead(album, "dev-a", HeadMark(3, "aa"), AlbumRejections.RETRODATED)
    store.setRejectedHead(album, "dev-b", HeadMark(1, "bb"), "BAD_SIGNATURE")
    // Un segno di prima, senza motivo, e un motivo che non e' una costante ammessa: valgono `UNKNOWN`, mai un testo qualunque.
    store.setRejectedHead(album, "dev-c", HeadMark(2, "cc"))
    store.setRejectedHead(album, "dev-d", HeadMark(2, "dd"), "ho un nome strano")
    assertThat(store.rejectedByReason()).containsExactly(AlbumRejections.RETRODATED, 1, "BAD_SIGNATURE", 1, AlbumRejections.UNKNOWN, 2)
    store.setRejectedHead(album, "dev-a", null)
    store.setRejectedHead(album, "dev-b", null)
    store.setRejectedHead(album, "dev-c", null)
    store.setRejectedHead(album, "dev-d", null)
    assertThat(store.rejectedByReason()).isEmpty()
  }
}
