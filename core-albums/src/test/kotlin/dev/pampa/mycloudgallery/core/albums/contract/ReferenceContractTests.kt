package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumPublisher
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.testing.FakeSafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.testing.FakeShareTransfers
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedAlbums
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedOrigins
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedWorld
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryAlbums
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareDiary
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareRunner
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryTrips
import dev.pampa.mycloudgallery.core.albums.testing.ReferenceSharePlanner
import dev.pampa.mycloudgallery.core.albums.testing.ShareRunnerPorts
import dev.pampa.mycloudgallery.core.albums.testing.SmartMembership
import dev.pampa.mycloudgallery.core.albums.testing.TripDismissals
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.LocalDate

// Gli esecutori di riferimento e i fake fedeli passano le suite di contratto (P0, "Fatto quando"). I pacchetti dopo fanno lo stesso con le
// implementazioni vere: P3 (AlbumsContract), P4 (ShareRunnerContract), P7 (TripsContract), P2 (AlbumPublisherContract, SharedAlbumsContract).

/** Un mondo di prova completo: un dispositivo dell'account [me] sopra l'area comune finta condivisa con gli altri. */
class ReferenceWorld(val me: AccountId = AccountId("test"), val shared: FakeSharedWorld = FakeSharedWorld()) {
  val clocks = FakeAlbumClocks()
  val family = FakeAlbumFamily(account = me)
  val library = FakeAlbumLibrary()
  val gate = FakeAlbumWriteGate()
  val publisher = FakeAlbumPublisher(clocks, family, gate, shared)
  val transfers = FakeShareTransfers(library)
  val safeCopies = FakeSafeCopyRequester()
  val origins = FakeSharedOrigins()
  val diary = InMemoryShareDiary()
  val sharedAlbums = FakeSharedAlbums(shared, family, library)
  var smart = SmartMembership.EMPTY
  private var nonce = 0

  fun ports() = ShareRunnerPorts(
    diary, library, sharedAlbums, { id, op, item -> sharedAlbums.allowed(id, op, item) }, transfers, transfers, transfers, publisher, safeCopies,
    origins, family, gate, clocks,
  )

  fun planner() = ReferenceSharePlanner(library, sharedAlbums, { id, op, item -> sharedAlbums.allowed(id, op, item) }, family, gate)

  /** Un processo nuovo dell'orchestratore (stesso diario, stesso mondo): la ripresa dopo la morte del processo. */
  fun runner(): InMemoryShareRunner = InMemoryShareRunner(ports(), planner(), newNonce = { nextNonce() }).also { sharedAlbums.runner = it }

  fun nextNonce(): ByteArray = ByteArray(16) { (it + 31 * ++nonce).toByte() }

  val albums: InMemoryAlbums by lazy { InMemoryAlbums(library, family, clocks, sharedAlbums, runner(), { smart }, { nextNonce() }) }

  /** Un album condiviso creato da [creator] (un altro account) al livello [level], gia' nel mondo comune. */
  fun albumBy(creator: AccountId, title: String = "Di famiglia", level: ShareLevel = ShareLevel.ADD): AlbumId {
    val bytes = ByteArray(16) { (it * 7 + title.length).toByte() }
    val id = AlbumId.derived(creator, bytes)
    val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    shared.apply(
      id, creator,
      listOf(SharedAlbumChange.Definition(title, SharePath.of("Album condivisi", title), SharedState.ACTIVE, creator, hex, null), SharedAlbumChange.Level(level)),
    )
    return id
  }
}

class ReferenceAlbumsContractTest : AlbumsContract() {
  override fun world(): World {
    val ref = ReferenceWorld()
    return object : World {
      override val albums: Albums get() = ref.albums
      override val library: FakeAlbumLibrary get() = ref.library
      override val choices: SmartChoices get() = ref.albums

      override fun setSmart(confident: List<MediaId>, suggested: List<MediaId>) {
        ref.smart = SmartMembership(confident, suggested)
      }

      override fun sharedAlbum(): AlbumId = ref.albumBy(AccountId("test2"))
    }
  }
}

class ReferenceShareRunnerContractTest : ShareRunnerContract() {
  override fun world(): ReferenceWorld = ReferenceWorld()
}

class ReferenceTripsContractTest : TripsContract() {
  override fun trips(library: FakeAlbumLibrary, today: LocalDate, albums: Albums, dismissals: TripDismissals): Trips =
    InMemoryTrips(library, Today { today }, albums, dismissals)

  override fun albumsFor(library: FakeAlbumLibrary): Albums {
    val ref = ReferenceWorld()
    return InMemoryAlbums(library, ref.family, ref.clocks)
  }
}

class FakePublisherContractTest : AlbumPublisherContract() {
  override fun world(): PublisherWorld {
    val ref = ReferenceWorld()
    return PublisherWorld(ref.publisher, ref.clocks, ref.gate, ref.family)
  }
}

class FakeSharedAlbumsContractTest : SharedAlbumsContract() {
  override fun world(): SharedWorld {
    val common = FakeSharedWorld()
    val alice = ReferenceWorld(AccountId("test"), common)
    val bruno = ReferenceWorld(AccountId("test2"), common)
    return SharedWorld(common, alice, bruno)
  }
}
