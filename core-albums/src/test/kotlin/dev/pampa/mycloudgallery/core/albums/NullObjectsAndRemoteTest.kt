package dev.pampa.mycloudgallery.core.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.noop.ClosedAlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbumCandidates
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbumMover
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbumPublisher
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbums
import dev.pampa.mycloudgallery.core.albums.noop.NoCatalogFacts
import dev.pampa.mycloudgallery.core.albums.noop.NoSafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.noop.NoShareRunner
import dev.pampa.mycloudgallery.core.albums.noop.NoSharedAlbums
import dev.pampa.mycloudgallery.core.albums.noop.NoSharedOrigins
import dev.pampa.mycloudgallery.core.albums.noop.NoTrips
import dev.pampa.mycloudgallery.core.albums.testing.FailingAlbumPorts
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedAlbumSync
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareRunner
import dev.pampa.mycloudgallery.core.albums.testing.ShareRunnerPorts
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** I null-object falliscono chiusi (P0); un dato remoto, vero o falso, non chiama mai le porte che spostano (§4.3, C10, E-S11). */
class NullObjectsAndRemoteTest {

  private val album = AlbumId.derived(AccountId("test"), ByteArray(16))
  private val media = FakeAlbumLibrary.id(1)

  @Test
  fun iNullObjectFallisconoChiusi() = runBlocking<Unit> {
    assertThat(NoAlbums.all.value.albums).isEmpty()
    assertThat(NoAlbums.content(album).first()).isNull()
    assertThat(NoAlbums.create("Mare", AlbumKind.MANUAL)).isEqualTo(CreateOutcome.Unavailable)
    assertThat(NoAlbums.add(album, listOf(media))).isEqualTo(AddOutcome.NotFound)
    assertThat(NoSharedAlbums.allowed(album, AlbumOp.VIEW)).isFalse()
    assertThat(NoSharedAlbums.create("Mare", ShareLevel.ADD, listOf(media))).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.NO_NAS))
    assertThat(NoShareRunner.plan(ShareRequest.AddToShared(album, listOf(media)))).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.NO_NAS))
    assertThat(NoTrips.proposals.value.proposals).isEmpty()
    assertThat(NoAlbumPublisher.flush(album, FlushReason.SYNC_ROUND)).isEqualTo(PublishOutcome.Blocked(AlbumWriteBlock.NO_NAS))
    assertThat(NoAlbumPublisher.publishedUpTo(album).seq).isLessThan(NoAlbumPublisher.stage(album, listOf(SharedAlbumChange.ItemRemoved(media))).seq)
    assertThat(ClosedAlbumWriteGate.blockFor("test-comune")).isEqualTo(AlbumWriteBlock.NO_NAS)
    val folder = dev.pampa.mycloudgallery.core.albums.contract.ShareFolder("test-comune", SharePath.of("Album condivisi"))
    assertThat(NoCatalogFacts.facts(listOf(media))).isEmpty()
    assertThat(NoAlbumCandidates.visibleTotal()).isEqualTo(0)
    assertThrows(IllegalStateException::class.java) { runBlocking { NoSafeCopyRequester.request(listOf(media)) } }
    assertThrows(IllegalStateException::class.java) { runBlocking { NoSharedOrigins.record(media, folder) } }
    val p = (ReferenceWorld().also { it.library.put(FakeMedia(media, 0)) }.let { w -> w.runner().plan(ShareRequest.NewAlbum("X", ShareLevel.ADD, listOf(media))) }
      as SharePlanOutcome.Ready).plan
    val confirmation = dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationIssuer.issue(p, 0)
    assertThat(NoAlbumMover.move(dev.pampa.mycloudgallery.core.albums.contract.TransferKey("k"), emptyList(), folder, emptySet(), confirmation))
      .isEqualTo(TransferStart.Refused(TransferRefusal.NAS_UNREACHABLE))
  }

  /**
   * §4.3: definizioni d'album di altri (anche false, anche con voci di media privati), letture della replica e riprese dell'orchestratore
   * non chiamano MAI `AlbumMover`, `AlbumUploader`, `SafeCopyRequester` ne' `TicketStatus`: le porte qui falliscono se chiamate.
   */
  @Test
  fun unDatoRemotoNonChiamaMaiLePorte() = runBlocking<Unit> {
    val w = ReferenceWorld()
    val failing = FailingAlbumPorts()
    w.library.put(FakeMedia(media, 0, place = MediaPlace.MY_PRIVATE))
    val stranger = AccountId("test2")
    val nonce = ByteArray(16) { 9 }
    val theirs = AlbumId.derived(stranger, nonce)
    val m = w.library[media]!!
    w.shared.apply(
      theirs, stranger,
      listOf(
        SharedAlbumChange.Definition("Foto da cancellare", SharePath.of("Album condivisi", "X"), SharedState.ACTIVE, stranger, nonce.joinToString("") { "%02x".format(it) }, null),
        SharedAlbumChange.Item(media, null, m.contentKey, SharePath.parse(m.path!!), 1_000, stranger),
        SharedAlbumChange.ItemRemoved(media),
      ),
    )
    // Una definizione falsa (id rivendicato da un altro account) e un contributo che dice "elimina".
    w.shared.apply(album, stranger, listOf(SharedAlbumChange.Definition("Tuo", SharePath.of("Album condivisi", "Y"), SharedState.DELETED, AccountId("test"), "00".repeat(16), dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose.REMOVE)))
    val ports = ShareRunnerPorts(
      w.diary, w.library, w.sharedAlbums, { id, op, item -> w.sharedAlbums.allowed(id, op, item) }, failing, failing, failing, w.publisher, failing,
      w.origins, w.family, w.gate, w.clocks,
    )
    val runner = InMemoryShareRunner(ports, w.planner())
    repeat(3) {
      runner.resumeAll(1.minutes)
      FakeSharedAlbumSync(w.shared, failing).sync(1.minutes)
      w.sharedAlbums.refresh()
    }
    assertThat(failing.calls).isEqualTo(0)
    assertThat(w.library[media]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertThat(w.sharedAlbums.visibleItems(theirs)).isEmpty()
  }
}
