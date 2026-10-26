package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisherContract
import dev.pampa.mycloudgallery.core.albums.contract.CommonArea
import dev.pampa.mycloudgallery.core.albums.contract.PublisherWorld
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumsContract
import dev.pampa.mycloudgallery.core.albums.contract.SharedSide
import dev.pampa.mycloudgallery.core.albums.contract.SharedWorld
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.KeptCopy
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Le suite di contratto di P0 sulle classi vere di P2 ("Fatto quando" di P0 per P2: far passare AlbumPublisherContract e
// SharedAlbumsContract alle classi vere). Il NAS e' quello finto in memoria, la famiglia e le firme sono vere.

/** Il pubblicatore vero ([NasAlbumPublisher]) sopra il NAS finto, con gli orologi, il cancello e la famiglia della suite. */
class RealPublisherContractTest : AlbumPublisherContract() {
  override fun world(): PublisherWorld {
    val w = AlbumWorld()
    val device = w.device("test")
    return PublisherWorld(device.publisher, w.clocks, device.gate, device.albumFamily)
  }
}

/** Gli album condivisi veri ([ReplicaSharedAlbums]) di due account, con la replica e il pubblicatore veri. */
class RealSharedAlbumsContractTest : SharedAlbumsContract() {
  override fun world(): SharedWorld {
    val w = AlbumWorld()
    val alice = w.device("test")
    val bruno = w.device("test2")
    return SharedWorld(RealCommonArea(w, alice, bruno), RealSide(w, alice), RealSide(w, bruno))
  }
}

/** Un dispositivo vero visto dalla suite. */
class RealSide(private val world: AlbumWorld, private val device: AlbumDevice) : SharedSide {
  override val me: AccountId get() = device.account
  override val library: FakeAlbumLibrary get() = device.library
  override val sharedAlbums: SharedAlbums get() = device.shared

  override suspend fun keptCopies(): List<KeptCopy> = device.shared.keptCopies().map { KeptCopy(it.copy, it.shared, it.title, it.creator, it.mine, it.items) }

  override suspend fun createAlbum(title: String, level: ShareLevel): AlbumId = device.createAlbum(title, level).also { world.settle() }
}

/**
 * L'area comune vera: le voci "a mano" le scrive un dispositivo in piu' dell'account (un'app difettosa: [RawWriter]); quante valgono lo dice
 * la fusione di chi legge (la voce scritta e' la vincitrice della sua chiave).
 */
class RealCommonArea(private val world: AlbumWorld, private val alice: AlbumDevice, private val bruno: AlbumDevice) : CommonArea {
  private val writers = HashMap<AccountId, RawWriter>()

  private fun writer(account: AccountId): RawWriter = writers.getOrPut(account) { RawWriter(world, account) { alice } }

  override suspend fun apply(album: AlbumId, author: AccountId, changes: List<SharedAlbumChange>): Int {
    val written = writer(author).write(album, changes)
    val merged = alice.view(album)?.merged ?: return 0
    return written.count { merged.winners[it.key] == it }
  }

  override fun folder(album: AlbumId): SharePath = alice.view(album)!!.merged.folder

  override fun nonceHex(album: AlbumId): String = alice.view(album)!!.merged.nonceHex

  override val forgeriesCounted: Int
    get() = world.devices.sumOf { d -> d.reports.sumOf { it.contributionsRejected } } + alice.replica.unresolved.value.size

  override suspend fun makeAdmin(account: AccountId) {
    val device = listOf(alice, bruno).first { it.account == account }
    device.replaceWith(world.family.approve("Telefono admin di ${account.value}", account.value, admin = true))
    world.settle()
  }

  override suspend fun removeFromFamily(account: AccountId) {
    world.family.removeAccount(account.value)
    world.settle()
  }

  override suspend fun settle() = world.settle()
}
