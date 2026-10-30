package dev.pampa.mycloudgallery.core.map.points

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.MapCoverScore
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.testing.SyntheticMapLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 9 (P3; D27, E-MAP5, E-MAP7): `PagedMapPointSource` legge la query a pagine per chiave, converte in microgradi, scarta e conta le
 * coordinate che non entrano, calcola il punteggio della copertina; il modello della query (`SyntheticMapLibrary.pager`) ha gli stessi cursori.
 */
class PagedMapPointSourceTest {

  private fun id(n: Long) = MediaId(UUID(1L, n).toString())

  private fun raw(n: Long, lat: Double = 43.5, lon: Double = 11.25, flags: Int = 0, source: MediaSource = MediaSource.CAMERA, kind: MediaKind = MediaKind.PHOTO, duration: Long? = null, place: Int? = null) =
    MapPointRaw(id(n), lat, lon, 20250101, flags, source, kind, duration, place)

  /** Un paginatore con righe fisse, che ricorda i cursori ricevuti. */
  private class ListPager(private val rows: List<MapPointRaw>, private val without: Int = 0) : MapPointPager {
    val cursors = ArrayList<MediaId?>()

    override suspend fun page(after: MediaId?, limit: Int): List<MapPointRaw> {
      cursors += after
      return rows.sortedBy { it.id.value }.filter { after == null || it.id.value > after.value }.take(limit)
    }

    override suspend fun countWithoutPosition(): Int = without
  }

  @Test
  fun pagineDa2000ConIlCursorePerChiaveEUnaPaginaCortaInFondo() = runTest {
    val pager = ListPager(List(4_321) { raw(it.toLong() + 1) }, without = 77)
    val source = PagedMapPointSource(pager)
    val pages = source.pages().toList()
    assertThat(pages.map { it.size }).containsExactly(MapPoints.PAGE_SIZE, MapPoints.PAGE_SIZE, 321).inOrder()
    assertThat(pages.flatten().map { it.id.value }).isInOrder()
    assertThat(pager.cursors).containsExactly(null, id(2_000), id(4_000)).inOrder()
    assertThat(source.counts()).isEqualTo(dev.pampa.mycloudgallery.core.map.contract.MapPointCounts(77, 0))
  }

  /** Con righe multiple della pagina l'ultima chiamata torna vuota e basta: niente pagina vuota emessa, niente ciclo. */
  @Test
  fun conUnMultiploDellaPaginaSiFermaAlLaPaginaVuota() = runTest {
    val pager = ListPager(List(4_000) { raw(it.toLong() + 1) })
    val pages = PagedMapPointSource(pager).pages().toList()
    assertThat(pages.map { it.size }).containsExactly(2_000, 2_000).inOrder()
    assertThat(pager.cursors).hasSize(3)
    assertThat(PagedMapPointSource(ListPager(emptyList())).pages().toList()).isEmpty()
  }

  /** E-MAP7: fuori dai limiti, (0, 0) esatto, oltre 85 gradi, non finite: scartate e contate, anche quando riempiono una pagina intera. */
  @Test
  fun leCoordinateAssurdeSiScartanoESiContano() = runTest {
    val rows = ArrayList<MapPointRaw>()
    rows += raw(1, lat = 0.0, lon = 0.0)
    rows += raw(2, lat = 85.000001, lon = 10.0)
    rows += raw(3, lat = -85.5, lon = 10.0)
    rows += raw(4, lat = 10.0, lon = 180.000001)
    rows += raw(5, lat = Double.NaN, lon = 10.0)
    rows += raw(6, lat = 10.0, lon = Double.POSITIVE_INFINITY)
    rows += raw(7, lat = 1e300, lon = 1e300)
    rows += raw(8, lat = 84.999999, lon = 180.0)
    rows += raw(9, lat = 0.0, lon = 0.000001)
    val source = PagedMapPointSource(ListPager(rows), pageSize = 3)
    val points = source.pages().toList().flatten()
    assertThat(points.map { it.id }).containsExactly(id(8), id(9)).inOrder()
    assertThat(source.counts().invalid).isEqualTo(7)
    assertThat(points[0].latE6).isEqualTo(84_999_999)
    assertThat(points[0].lonE6).isEqualTo(180_000_000)
  }

  @Test
  fun laLetturaSiRifaDaCapoELeCifreRestanoGiuste() = runTest {
    val source = PagedMapPointSource(ListPager(listOf(raw(1, lat = 0.0, lon = 0.0), raw(2))))
    repeat(2) {
      assertThat(source.pages().toList().flatten()).hasSize(1)
      assertThat(source.counts().invalid).isEqualTo(1)
    }
  }

  /** Il punteggio della copertina e' quello di `MapCoverScore` (una definizione sola), e il luogo manca = 0. */
  @Test
  fun ilPunteggioELaCopertinaSonoQuelliDelContratto() = runTest {
    val flags = CellFlags.FAVORITE or CellFlags.EDITED
    val row = raw(1, flags = flags, source = MediaSource.WHATSAPP, kind = MediaKind.VIDEO, duration = 10_000, place = 3_000_001)
    val shot = raw(2, source = MediaSource.SCREENSHOT)
    val points: List<MapPoint> = PagedMapPointSource(ListPager(listOf(row, shot))).pages().toList().flatten()
    assertThat(points[0].score).isEqualTo(MapCoverScore.of(flags, MediaSource.WHATSAPP, MediaKind.VIDEO, 10_000))
    assertThat(points[0].placeId).isEqualTo(3_000_001)
    assertThat(points[1].score).isEqualTo(MapCoverScore.of(0, MediaSource.SCREENSHOT, MediaKind.PHOTO, 0))
    assertThat(points[1].placeId).isEqualTo(0)
    assertThat(points[1].score).isLessThan(points[0].score)
  }

  @Test
  fun leRigheEIlPaginatoreNonMostranoCoordinateNeId() {
    val row = raw(7, lat = 43.123456, lon = 11.654321)
    for (text in listOf(row.toString(), PagedMapPointSource(ListPager(listOf(row))).toString())) {
      assertThat(text).doesNotContain("43.123456")
      assertThat(text).doesNotContain(id(7).value)
    }
  }

  /** Il modello della query (la Libreria sintetica): cursori per id, solo visibili con coordinate, nessun nascosto. */
  @Test
  fun ilModelloDellaQueryDaLeStessePagineEMaiUnNascosto() = runTest {
    val library = SyntheticMapLibrary.generate(6_000, seed = 12)
    val source = PagedMapPointSource(library.pager())
    val points = source.pages().toList().flatten()
    assertThat(points.map { it.id }).containsExactlyElementsIn(library.expectedPoints().map { it.id })
    assertThat(points).containsExactlyElementsIn(library.expectedPoints())
    assertThat(points.map { it.id.value }).isInOrder()
    assertThat(points.map { it.id }.toSet().intersect(library.neverShown)).isEmpty()
    val counts = source.counts()
    assertThat(counts.invalid).isEqualTo(library.expectedInvalid())
    assertThat(counts.withoutPosition).isEqualTo(library.withoutPosition)
    // La Libreria sintetica contiene davvero i casi: se no la prova sarebbe vuota.
    assertThat(library.media.count { it.hidden }).isGreaterThan(50)
    assertThat(library.media.count { it.groupMember }).isGreaterThan(50)
    assertThat(library.media.count { it.groupId != null && !it.hidden && it.id in library.neverShown && !it.groupMember }).isGreaterThan(0)
    assertThat(library.media.count { !it.present }).isGreaterThan(10)
    assertThat(counts.invalid).isGreaterThan(10)
    assertThat(counts.withoutPosition).isGreaterThan(100)
  }
}
