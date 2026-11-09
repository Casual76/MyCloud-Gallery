package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumsTabKeys
import dev.pampa.mycloudgallery.feature.albums.contract.InMemoryAlbumOpenings
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbum
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabEntry
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabOrder
import dev.pampa.mycloudgallery.feature.albums.tab.SpecialSummary
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * G2b: l'ordine della scheda Album come l'ha chiesto l'utente. In testa Preferiti, Fotocamera e Video; poi tutti gli altri insieme (i miei album, i
 * condivisi, gli smart, Screenshot e Messaggistica) per ultima apertura, il piu' recente prima; i mai aperti dopo, dal piu' recente per contenuto.
 * Le voci speciali vuote si nascondono, tranne i Preferiti.
 */
class AlbumsTabOrderTest {

  private val cover = MediaId("00000000-0000-4000-8000-000000000001")

  /** Un id d'album canonico dal nome breve [name] (cifre esadecimali). */
  private fun albumId(name: String) = AlbumId("00000000-0000-4000-8000-" + name.padStart(12, '0'))

  private fun manual(id: String, day: LocalDate?, count: Int = 5, updated: Long = 0L) =
    Album.Manual(albumId(id), "Album $id", if (count > 0) CoverRef.Auto(cover) else CoverRef.None, count, AlbumSort.NEWEST_FIRST, updated, day)

  private fun smart(id: String, day: LocalDate?) = Album.Smart(
    albumId(id), "Smart $id", CoverRef.Auto(cover), 9, AlbumSort.NEWEST_FIRST, 0L, day,
    SmartRules(SmartPeriod.Ranges(listOf(LocalDayRange(LocalDate.of(2026, 1, 8), LocalDate.of(2026, 1, 14)))), null, null),
    frozen = false, suggested = 0, newSinceSeen = 0,
  )

  private fun shared(id: String, day: LocalDate?) = Album.Shared(
    albumId(id), "Condiviso $id", AlbumKind.MANUAL, CoverRef.Auto(cover), 7, AlbumSort.NEWEST_FIRST, 0L, day,
    ShareLevel.ADD, AccountId("test2"), null, mine = false, state = SharedState.ACTIVE,
  )

  private fun special(count: Int, day: Int = 20260101) = SpecialSummary(count, if (count > 0) cover else null, if (count > 0) day else 0)

  private fun keys(entries: List<AlbumsTabEntry>): List<String> = entries.map { it.key }

  private fun specials(
    favorites: Int = 3, camera: Int = 100, videos: Int = 10, screenshots: Int = 40, messaging: Int = 60,
    screenshotsDay: Int = 20260301, messagingDay: Int = 20260201,
  ): Map<SpecialAlbum, SpecialSummary> = mapOf(
    SpecialAlbum.FAVORITES to special(favorites),
    SpecialAlbum.CAMERA to special(camera),
    SpecialAlbum.VIDEOS to special(videos),
    SpecialAlbum.SCREENSHOTS to special(screenshots, screenshotsDay),
    SpecialAlbum.MESSAGING to special(messaging, messagingDay),
  )

  private val fav = AlbumsTabKeys.special(SpecialAlbum.FAVORITES)
  private val cam = AlbumsTabKeys.special(SpecialAlbum.CAMERA)
  private val vid = AlbumsTabKeys.special(SpecialAlbum.VIDEOS)
  private val shots = AlbumsTabKeys.special(SpecialAlbum.SCREENSHOTS)
  private val msg = AlbumsTabKeys.special(SpecialAlbum.MESSAGING)

  @Test
  fun fissiInTestaPoiIMaiApertiDalPiuRecentePerContenuto() {
    val a = manual("a", LocalDate.of(2026, 4, 1))
    val b = smart("b", LocalDate.of(2026, 2, 15))
    val c = shared("c", LocalDate.of(2025, 12, 31))
    val (entries, pinned) = AlbumsTabOrder.arrange(listOf(c, b, a), specials(), emptyMap())

    assertThat(pinned).isEqualTo(3)
    assertThat(keys(entries)).containsExactly(
      fav, cam, vid,
      AlbumsTabKeys.album(a.id), // 2026-04-01
      shots, // 2026-03-01
      AlbumsTabKeys.album(b.id), // 2026-02-15
      msg, // 2026-02-01
      AlbumsTabKeys.album(c.id), // 2025-12-31
    ).inOrder()
  }

  @Test
  fun gliApertiPrimaDeiMaiApertiDalPiuRecenteEIFissiNonSiMuovono() {
    val a = manual("a", LocalDate.of(2026, 4, 1))
    val b = smart("b", LocalDate.of(2026, 2, 15))
    val c = shared("c", LocalDate.of(2025, 12, 31))
    // Aprire i fissi non li sposta: restano in testa nel loro ordine.
    val openings = mapOf(AlbumsTabKeys.album(c.id) to 9L, msg to 7L, vid to 8L, AlbumsTabKeys.album(b.id) to 2L)
    val (entries, _) = AlbumsTabOrder.arrange(listOf(a, b, c), specials(), openings)

    assertThat(keys(entries)).containsExactly(
      fav, cam, vid,
      AlbumsTabKeys.album(c.id), msg, AlbumsTabKeys.album(b.id), // aperti, dal piu' recente
      AlbumsTabKeys.album(a.id), shots, // mai aperti, per contenuto
    ).inOrder()
  }

  @Test
  fun leVociSpecialiVuoteSiNascondonoTranneIPreferiti() {
    val (entries, pinned) = AlbumsTabOrder.arrange(emptyList(), specials(favorites = 0, camera = 0, videos = 0, screenshots = 0, messaging = 0), emptyMap())
    assertThat(keys(entries)).containsExactly(fav)
    assertThat(pinned).isEqualTo(1)
    val favorites = entries.single() as AlbumsTabEntry.Special
    assertThat(favorites.count).isEqualTo(0)
    assertThat(favorites.cover).isNull()

    // Un profilo del tablet: niente Fotocamera, ma i Video si'.
    val (tablet, tabletPinned) = AlbumsTabOrder.arrange(emptyList(), specials(camera = 0, messaging = 0), emptyMap())
    assertThat(keys(tablet)).containsExactly(fav, vid, shots).inOrder()
    assertThat(tabletPinned).isEqualTo(2)
  }

  @Test
  fun unAlbumVuotoRestaInFondoAiMaiAperti() {
    val empty = manual("e", day = null, count = 0)
    val full = manual("f", LocalDate.of(2020, 1, 1))
    val (entries, _) = AlbumsTabOrder.arrange(listOf(empty, full), specials(screenshots = 0, messaging = 0), emptyMap())
    assertThat(keys(entries).takeLast(2)).containsExactly(AlbumsTabKeys.album(full.id), AlbumsTabKeys.album(empty.id)).inOrder()
    assertThat(entries.last().cover).isNull()
  }

  @Test
  fun aParitaDiGiornoVaPrimaLAlbumAggiornatoPiuDiRecenteEPoiLaChiave() {
    val day = LocalDate.of(2026, 3, 3)
    val older = manual("b", day, updated = 100L)
    val newer = manual("c", day, updated = 200L)
    val same = manual("a", day, updated = 100L)
    val (entries, _) = AlbumsTabOrder.arrange(listOf(older, same, newer), specials(screenshots = 0, messaging = 0), emptyMap())
    assertThat(keys(entries).drop(3)).containsExactly(AlbumsTabKeys.album(newer.id), AlbumsTabKeys.album(same.id), AlbumsTabKeys.album(older.id)).inOrder()
  }

  @Test
  fun ilGiornoDiUnAlbumELoStessoNumeroDiQuelloDiUnaVoceSpeciale() {
    val album = AlbumsTabEntry.Item(manual("a", LocalDate.of(2026, 3, 9)))
    assertThat(AlbumsTabOrder.newestDayOf(album)).isEqualTo(20260309)
    assertThat(AlbumsTabOrder.newestDayOf(AlbumsTabEntry.Special(SpecialAlbum.SCREENSHOTS, special(1, 20260309)))).isEqualTo(20260309)
  }

  @Test
  fun leAperturePrendonoIlNumeroDopoIlPiuAltoEDimenticanoLePiuVecchie() = runBlocking<Unit> {
    val openings = InMemoryAlbumOpenings(limit = 3)
    openings.opened("a")
    openings.opened("b")
    openings.opened("a")
    assertThat(openings.order.value).containsExactly("a", 3L, "b", 2L)
    openings.opened("c")
    openings.opened("d")
    // Oltre tre voci si dimentica la piu' vecchia ("b").
    assertThat(openings.order.value).containsExactly("a", 3L, "c", 4L, "d", 5L)
  }
}
