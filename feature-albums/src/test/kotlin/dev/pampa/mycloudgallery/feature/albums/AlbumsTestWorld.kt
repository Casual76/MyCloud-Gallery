package dev.pampa.mycloudgallery.feature.albums

import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumPublisher
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.FakeSafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.testing.FakeShareTransfers
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedAlbums
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedOrigins
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedWorld
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryAlbums
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareDiary
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareRunner
import dev.pampa.mycloudgallery.core.albums.testing.ReferenceSharePlanner
import dev.pampa.mycloudgallery.core.albums.testing.ShareRunnerPorts
import dev.pampa.mycloudgallery.core.albums.testing.SmartMembership
import dev.pampa.mycloudgallery.core.albums.testing.addItems
import dev.pampa.mycloudgallery.core.albums.testing.createAlbum
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import java.time.LocalDate

/**
 * Un mondo di prova per i controllori delle schermate (Fase 7, P10): un dispositivo dell'account [me] sopra l'area comune finta condivisa con gli altri,
 * fatto con gli esecutori di riferimento e i fake fedeli di core-albums (gli stessi che P0 provo' con le suite di contratto). Come `ReferenceWorld` di
 * core-albums, che sta nei test di quel modulo e qui non si vede.
 */
class AlbumsTestWorld(val me: AccountId = AccountId("test"), val common: FakeSharedWorld = FakeSharedWorld()) {
  val clocks = FakeAlbumClocks()
  val family = FakeAlbumFamily(account = me)
  val library = FakeAlbumLibrary()
  val gate = FakeAlbumWriteGate()
  val publisher = FakeAlbumPublisher(clocks, family, gate, common)
  val transfers = FakeShareTransfers(library)
  val safeCopies = FakeSafeCopyRequester()
  val origins = FakeSharedOrigins()
  val diary = InMemoryShareDiary()
  val sharedAlbums = FakeSharedAlbums(common, family, library)
  var smart = SmartMembership.EMPTY
  private var nonce = 0

  fun nextNonce(): ByteArray = ByteArray(16) { (it + 31 * ++nonce).toByte() }

  private fun ports() = ShareRunnerPorts(
    diary, library, sharedAlbums, { id, op, item -> sharedAlbums.allowed(id, op, item) }, transfers, transfers, transfers, publisher, safeCopies,
    origins, family, gate, clocks,
  )

  val runner: InMemoryShareRunner by lazy {
    InMemoryShareRunner(ports(), ReferenceSharePlanner(library, sharedAlbums, { id, op, item -> sharedAlbums.allowed(id, op, item) }, family, gate), { nextNonce() })
      .also { sharedAlbums.runner = it }
  }

  val albums: InMemoryAlbums by lazy { InMemoryAlbums(library, family, clocks, sharedAlbums, runner, { smart }, { nextNonce() }) }

  /** L'orologio delle schermate: "oggi" fermo e il monotono degli orologi del mondo (cosi' un test avanza il tempo in un posto solo). */
  val screens: AlbumScreensClock = object : AlbumScreensClock {
    override fun today(): LocalDate = TODAY

    override fun monotonicMs(): Long = clocks.monotonicMs()
  }

  /** Un album condiviso creato da [creator] (un altro account, o io) al livello [level], gia' nel mondo comune. */
  fun albumBy(creator: AccountId, title: String = "Di famiglia", level: ShareLevel = ShareLevel.ADD): AlbumId = common.createAlbum(creator, title, level)

  /** Mette nell'area comune [media] (gia' file dell'area comune, vedi [commonPhoto]) come aggiunti da [by] all'album [album]. */
  fun addToShared(album: AlbumId, by: AccountId, vararg media: FakeMedia) {
    library.put(*media)
    common.addItems(album, by, media.toList())
  }

  /** Un file dell'area comune (di [mine] o di un altro familiare), con un'ora a partire dall'inizio del 2026. */
  fun commonPhoto(n: Int, hoursFromBase: Int = n, mine: Boolean = false, hidden: Boolean = false): FakeMedia = FakeMedia(
    FakeAlbumLibrary.id(n), BASE + hoursFromBase * 3_600_000L, place = MediaPlace.COMMON, mine = mine, hidden = hidden, pixels = 1_000_000L + n,
    path = "Album condivisi/Prova/${FakeAlbumLibrary.id(n).value}.jpg",
  )

  /** Un media nell'area privata mia (se [place] e' quella di default) con un'ora a partire dall'inizio del 2026. */
  fun photo(n: Int, hoursFromBase: Int = n, hidden: Boolean = false): FakeMedia =
    FakeMedia(FakeAlbumLibrary.id(n), BASE + hoursFromBase * 3_600_000L, hidden = hidden, pixels = 1_000_000L + n)

  companion object {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 21)
    const val BASE: Long = 1_767_225_600_000L // 1/1/2026 00:00 UTC

    fun media(n: Int): MediaId = FakeAlbumLibrary.id(n)
  }
}
