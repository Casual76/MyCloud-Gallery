package dev.pampa.mycloudgallery.feature.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.testing.ReferenceClusterIndex
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapBubble
import dev.pampa.mycloudgallery.feature.map.map.BubbleEngine
import dev.pampa.mycloudgallery.feature.map.map.BubbleTap
import dev.pampa.mycloudgallery.feature.map.map.MapLabels
import dev.pampa.mycloudgallery.feature.map.map.MapViewport
import dev.pampa.mycloudgallery.feature.map.map.PlacedBubble
import dev.pampa.mycloudgallery.feature.map.support.Cities
import dev.pampa.mycloudgallery.feature.map.support.TestPlaceNames
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le bolle (Fase 9, P4; docs/fase9-progetto.md §2.10 "Bolle" ed "Etichette", D28, D29, D31, E-MAP9, E-MAP15, E-MAP18, E-H9): **mai un'etichetta "Casa"**, la copertina passa dal filtro finale dei Nascosti
 * (una copertina nascosta un attimo fa cede il posto, e senza altri media visibili la bolla non c'e'), e il tocco: avvicinarsi o aprire la griglia della zona.
 */
class BubbleEngineTest {

  private val view = GeoBox(-180_000_000, -85_000_000, 180_000_000, 85_000_000)
  private fun viewport(zoom: Double) = MapViewport(GeoE6(40_000_000, 10_000_000), zoom, view, 412, 892)

  private fun around(city: GeoE6, count: Int, place: Int, ids: MutableList<MediaId>): List<MapPoint> = List(count) { i ->
    val id = MediaId.random().also(ids::add)
    MapPoint(id, city.latE6 + i * 20, city.lonE6 + i * 20, 20_260_100 + i % 28, (i % 5).toShort(), place)
  }

  // --- Mai "Casa" (E-MAP18, D29) ----------------------------------------------------------------------------------------------------

  @Test
  fun unNomeCasaOHomeNonDiventaMaiUnEtichetta() {
    for (name in listOf("Casa", "casa", "CASA", "Home", "home", "La mia casa", "Casa mia", "My Home", "càsa", "Home, sweet", "  Casa  ", "Casa!")) {
      assertThat(MapLabels.clean(name)).isNull()
    }
    // Le parole, non le lettere: i luoghi che cominciano per "casa" restano.
    for (name in listOf("Casalecchio di Reno", "Casablanca", "Casale Monferrato", "Homs", "Firenze")) assertThat(MapLabels.clean(name)).isEqualTo(name)
    assertThat(MapLabels.clean("  Firenze ")).isEqualTo("Firenze")
    assertThat(MapLabels.clean(null)).isNull()
    assertThat(MapLabels.clean("   ")).isNull()
  }

  @Test
  fun nessunaBollaPortaMaiUnaEtichettaCasa_EsoloLeBolleGrandiHannoIlNome() = runTest {
    val ids = ArrayList<MediaId>()
    val points = around(Cities.FIRENZE, 40, place = 1, ids) + around(Cities.ROMA, 40, place = 2, ids) + around(Cities.OSLO, 5, place = 3, ids)
    val names = TestPlaceNames(special = mapOf(1 to "Casa", 2 to "Roma", 3 to "Oslo"))
    val engine = BubbleEngine(names, FinalFilter(NothingHidden))
    val bubbles = engine.compute(ReferenceClusterIndex(points), viewport(zoom = 8.0))
    val labels = bubbles.mapNotNull { it.label }
    assertThat(labels).containsExactly("Roma")
    // Firenze e' "Casa" per il dizionario: la bolla c'e' (le sue foto sono dell'utente), senza nome. Oslo ha poche foto: nessun nome.
    assertThat(bubbles.map { it.count }).containsExactly(40, 40, 5)
    assertThat(bubbles.all { it.label?.lowercase() != "casa" }).isTrue()
  }

  // --- Nascosti (D31, E-MAP15, E-H9) ----------------------------------------------------------------------------------------------

  @Test
  fun unaCopertinaNascostaUnAttimoFaCedeIlPostoAUnAltroMedia_OLaBollaNonCe() = runTest {
    val ids = ArrayList<MediaId>()
    val points = around(Cities.FIRENZE, 12, place = 0, ids)
    val index = ReferenceClusterIndex(points)
    val cover = index.clusters(view, 8.0, BubbleEngine.CELL_DP).single().cover
    val visibility = FakeVisibility(present = ids.toSet(), hidden = setOf(cover))
    val engine = BubbleEngine(TestPlaceNames(), FinalFilter(visibility))
    val bubbles = engine.compute(index, viewport(8.0))
    assertThat(bubbles).hasSize(1)
    assertThat(bubbles.single().bubble.cover).isNotEqualTo(cover)
    assertThat(visibility.maskCalls).isAtLeast(1)
    // Tutti i media della cella nascosti (l'indice non lo sa ancora): la bolla non c'e'.
    visibility.hidden += ids
    assertThat(engine.compute(index, viewport(8.0))).isEmpty()
  }

  @Test
  fun unaCopertinaCheLaLibreriaNonSaDisegnareCedeIlPostoSeSiPuo() = runTest {
    val ids = ArrayList<MediaId>()
    val points = around(Cities.ROMA, 10, place = 0, ids)
    val index = ReferenceClusterIndex(points)
    val cover = index.clusters(view, 8.0, BubbleEngine.CELL_DP).single().cover
    val engine = BubbleEngine(TestPlaceNames(), FinalFilter(NothingHidden))
    val drawn = engine.compute(index, viewport(8.0)) { it != cover }.single()
    assertThat(drawn.bubble.cover).isNotEqualTo(cover)
    // Se nessun altro si puo' disegnare resta quella (segnaposto): la bolla non sparisce per questo.
    assertThat(engine.compute(index, viewport(8.0)) { false }.single().bubble.cover).isEqualTo(cover)
  }

  @Test
  fun leBolleSonoDeterministicheEOrdinateDallaCella() = runTest {
    val ids = ArrayList<MediaId>()
    val points = around(Cities.OSLO, 20, 0, ids) + around(Cities.LISBONA, 20, 0, ids) + around(Cities.AUCKLAND, 20, 0, ids)
    val engine = BubbleEngine(TestPlaceNames(), FinalFilter(NothingHidden))
    val first = engine.compute(ReferenceClusterIndex(points), viewport(6.0))
    val second = engine.compute(ReferenceClusterIndex(points.reversed()), viewport(6.0))
    assertThat(first.map { it.key }).isEqualTo(second.map { it.key })
    assertThat(first.map { it.key }).isInOrder(compareBy<dev.pampa.mycloudgallery.core.map.contract.CellKey>({ it.level }, { it.prefix }))
    assertThat(first.filter { it.count == 1 }.all { it.bubble is MapBubble.Single }).isTrue()
  }

  // --- Il tocco (D28) -----------------------------------------------------------------------------------------------------------------

  private fun bubble(count: Int) = PlacedBubble(
    if (count == 1) MapBubble.Single(GeoGrid.cellOf(43_000_000, 11_000_000, 20), MediaId.random()) else MapBubble.Cluster(GeoGrid.cellOf(43_000_000, 11_000_000, 20), count, MediaId.random(), null),
    GeoE6(43_000_000, 11_000_000),
  )

  @Test
  fun sottoTrentaElementiOAlLivelloMassimoSiApreLaGriglia_AltrimentiCiSiAvvicina() {
    assertThat(BubbleTap.of(bubble(1), zoom = 4.0, maxZoom = 17.0)).isInstanceOf(BubbleTap.OpenZone::class.java)
    assertThat(BubbleTap.of(bubble(30), zoom = 4.0, maxZoom = 17.0)).isInstanceOf(BubbleTap.OpenZone::class.java)
    val far = BubbleTap.of(bubble(31), zoom = 4.0, maxZoom = 17.0)
    assertThat((far as BubbleTap.ZoomTo).spot).isEqualTo(CameraSpot(GeoE6(43_000_000, 11_000_000), 6.0))
    // Vicino al massimo non si zooma piu': griglia anche per mille foto (con la sola carta di base il massimo e' 9).
    assertThat(BubbleTap.of(bubble(1_000), zoom = 9.0, maxZoom = 9.0)).isInstanceOf(BubbleTap.OpenZone::class.java)
    // Lo zoom non supera mai il massimo.
    assertThat((BubbleTap.of(bubble(500), zoom = 8.0, maxZoom = 9.0) as BubbleTap.ZoomTo).spot.zoom).isEqualTo(9.0)
  }

  @Test
  fun leBollePrivateDiCoordinateNelToString() {
    assertThat(bubble(12).toString()).doesNotContain("43")
    assertThat(bubble(12).toString()).doesNotContain("11")
    assertThat(BubbleTap.of(bubble(500), 4.0, 17.0).toString()).isEqualTo("BubbleTap.ZoomTo")
  }
}
