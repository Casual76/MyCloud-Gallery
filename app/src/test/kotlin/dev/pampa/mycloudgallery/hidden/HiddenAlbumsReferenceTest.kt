package dev.pampa.mycloudgallery.hidden

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedAlbums
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedWorld
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryAlbums
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryTrips
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 7 (P9, D22, D23): **E-H5, E-H6 ed E-H7** sugli esecutori di riferimento di album e viaggi (il codice vero arriva con P2/P3/P4/P7: la
 * stessa prova gira su di lui, lo chiede il registro). Il nascosto e' un dato personale: vale su tutti i dispositivi del mio account (E-H4) e
 * mai su quelli di un altro account.
 */
class HiddenAlbumsReferenceTest {

  private val t0 = 1_760_000_000_000L

  private fun commonMedia(count: Int): List<FakeMedia> =
    (1..count).map { FakeMedia(FakeAlbumLibrary.id(it), t0 + it * 60_000L, place = MediaPlace.COMMON, pixels = 1_000L * it) }

  private class Viewer(val account: AccountId, val library: FakeAlbumLibrary, world: FakeSharedWorld) {
    val family = FakeAlbumFamily(account)
    val shared = FakeSharedAlbums(world, family, library)
    val albums = InMemoryAlbums(library, family, FakeAlbumClocks(), shared)

    fun sharedAlbum(id: AlbumId): Album.Shared = albums.all.value.byId(id) as Album.Shared

    suspend fun itemsOf(id: AlbumId): List<MediaId> =
      albums.content(id).first()!!.items.let { view -> (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) } }
  }

  /** E-H5 ed E-H6: Bruno ha messo sei foto in un album condiviso e ne ha scelto la copertina; io ne ho nascosta una, e proprio quella. */
  @Test
  fun unNascostoInUnAlbumCondivisoDaUnAltroPerMeNonCeNeNegliElementiNeNeiConteggiNeComeCopertina() = runBlocking<Unit> {
    val world = FakeSharedWorld()
    val common = commonMedia(6)
    val alice = Viewer(AccountId("test"), FakeAlbumLibrary().also { it.put(*common.toTypedArray()) }, world)
    val carla = Viewer(AccountId("test3"), FakeAlbumLibrary().also { it.put(*common.toTypedArray()) }, world)
    val bruno = AccountId("test2")
    val nonce = ByteArray(16) { (it * 3 + 1).toByte() }
    val id = AlbumId.derived(bruno, nonce)
    val coverByBruno = common[2]
    world.apply(
      id, bruno,
      listOf(
        SharedAlbumChange.Definition("Di famiglia", SharePath.of("Album condivisi", "Di famiglia"), SharedState.ACTIVE, bruno, nonce.joinToString("") { "%02x".format(it.toInt() and 0xff) }, null),
        SharedAlbumChange.Level(ShareLevel.ADD),
      ) + common.mapIndexed { i, m -> SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(checkNotNull(m.path)), (i + 1) * 1_000L, bruno) } +
        SharedAlbumChange.Cover(coverByBruno.id, coverByBruno.contentKey),
    )
    assertThat(alice.sharedAlbum(id).cover).isEqualTo(CoverRef.Chosen(coverByBruno.id))

    alice.library.hide(coverByBruno.id)

    // Per me: cinque foto, la copertina non e' quella nascosta (E-H6), il nascosto non c'e' (E-H5).
    val mine = alice.sharedAlbum(id)
    assertThat(mine.count).isEqualTo(5)
    assertThat(alice.itemsOf(id)).containsNoneOf(coverByBruno.id, FakeAlbumLibrary.id(99))
    assertThat(alice.itemsOf(id)).hasSize(5)
    assertThat(mine.cover).isNotEqualTo(CoverRef.Chosen(coverByBruno.id))
    assertThat(mine.cover.media).isNotEqualTo(coverByBruno.id)
    assertThat(mine.cover).isInstanceOf(CoverRef.Auto::class.java)
    // Per gli altri sì, e con la copertina scelta: nascondere vale solo per me (E-H4).
    val theirs = carla.sharedAlbum(id)
    assertThat(theirs.count).isEqualTo(6)
    assertThat(carla.itemsOf(id)).contains(coverByBruno.id)
    assertThat(theirs.cover).isEqualTo(CoverRef.Chosen(coverByBruno.id))

    // Tolto il nascosto torna com'era (il dato personale cambia, il resto no).
    alice.library.unhide(coverByBruno.id)
    assertThat(alice.sharedAlbum(id).count).isEqualTo(6)
    assertThat(alice.sharedAlbum(id).cover).isEqualTo(CoverRef.Chosen(coverByBruno.id))
  }

  /** E-H6 nel mio account: la copertina di un album personale e' nascosta da un altro dei miei dispositivi (stessa libreria sincronizzata). */
  @Test
  fun laCopertinaScelteENascostaDaUnAltroMioDispositivoDiventaAutomatica() = runBlocking<Unit> {
    val library = FakeAlbumLibrary().also { it.put(*commonMedia(5).toTypedArray()) }
    val viewer = Viewer(AccountId("test"), library, FakeSharedWorld())
    val id = (viewer.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val all = (1..5).map { FakeAlbumLibrary.id(it) }
    viewer.albums.add(id, all)
    viewer.albums.setCover(id, all[1])
    assertThat(viewer.albums.all.value.byId(id)!!.cover).isEqualTo(CoverRef.Chosen(all[1]))

    library.hide(all[1]) // il nascosto arriva dal giro dei dati personali dell'altro dispositivo

    val album = viewer.albums.all.value.byId(id)!!
    assertThat(album.cover.media).isNotNull()
    assertThat(album.cover.media).isNotEqualTo(all[1])
    assertThat(album.cover).isInstanceOf(CoverRef.Auto::class.java)
    assertThat(album.count).isEqualTo(4)
  }

  private val today = LocalDate.of(2026, 10, 22)
  private val firenze = GeoPoint(43.7696, 11.2558)
  private val oslo = GeoPoint(59.9139, 10.7522)
  private var n = 0

  private fun shot(day: LocalDate, hour: Int, gps: GeoPoint, hidden: Boolean = false): FakeMedia {
    val utc = day.atTime(hour, 0).toInstant(ZoneOffset.ofHours(1)).toEpochMilli()
    return FakeMedia(FakeAlbumLibrary.id(1_000 + ++n), utc, zoneOffsetMinutes = 60, gps = gps, hidden = hidden, onPhone = true)
  }

  private fun libraryWithHome(vararg away: LocalDayRange): FakeAlbumLibrary {
    val library = FakeAlbumLibrary()
    for (d in 0 until 60) {
      val day = today.minusDays(10L + d * 4L)
      if (away.any { day in it }) continue
      library.put(shot(day, 21, firenze), shot(day, 13, firenze))
    }
    return library
  }

  /** E-H7: un viaggio formato solo da media nascosti non si propone; uno con qualche nascosto si propone senza di loro. */
  @Test
  fun unViaggioDiSoliNascostiNonSiProponeEUnoConQualcunoSiProponeSenzaDiLoro() = runBlocking<Unit> {
    val onlyHidden = LocalDayRange(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 16))
    val someHidden = LocalDayRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
    val library = libraryWithHome(onlyHidden, someHidden)
    val secret = (0..2).flatMap { d -> (0..4).map { h -> shot(onlyHidden.from.plusDays(d.toLong()), 10 + h, oslo, hidden = true) } }
    val mixed = (0..2).flatMap { d -> (0..4).map { h -> shot(someHidden.from.plusDays(d.toLong()), 10 + h, oslo) } }
    library.put(*secret.toTypedArray(), *mixed.toTypedArray())
    val hiddenInMixed = mixed.take(3).map { it.id }.toSet()
    hiddenInMixed.forEach { library.hide(it) }
    val albums = InMemoryAlbums(library, FakeAlbumFamily(AccountId("test")), FakeAlbumClocks())

    val found = InMemoryTrips(library, Today { today }, albums).also { it.detect(1.minutes) }.proposals.value

    val trips = found.proposals.filter { it.kind == TripKind.TRIP }
    assertThat(trips.map { it.days }).containsExactly(someHidden)
    val trip = trips.single()
    assertThat(trip.count).isEqualTo(mixed.size - hiddenInMixed.size)
    assertThat(trip.cover).isNotIn(hiddenInMixed)
    assertThat(trip.cover).isNotIn(secret.map { it.id })
  }
}
