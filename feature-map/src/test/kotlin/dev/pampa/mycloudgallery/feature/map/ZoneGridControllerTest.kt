package dev.pampa.mycloudgallery.feature.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.testing.ReferenceClusterIndex
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridController
import dev.pampa.mycloudgallery.feature.map.grid.ZoneTarget
import dev.pampa.mycloudgallery.feature.map.support.FakeMapPoints
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.support.PLACE_BASE
import dev.pampa.mycloudgallery.feature.map.support.TestPlaceNames
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La griglia di una zona e di un luogo (Fase 9, P4; docs/fase9-progetto.md §1.5 `ZoneGridState`, §2.10 "Griglia della zona", §2.5, D28, D30, D31, E-MAP9, E-MAP15, E-V6, E-H9): gli elementi **dal piu' recente**,
 * **viva** sull'istantanea visibile, con il filtro finale dei Nascosti subito prima di mostrare; "Questa zona" o il nome del luogo (mai "Casa").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZoneGridControllerTest {

  private val rig = MapRig(count = 90)

  private fun TestScope.controller(target: ZoneTarget, points: FakeMapPoints, names: TestPlaceNames = TestPlaceNames()) =
    ZoneGridController(target, points, rig.visibility, names, FinalFilter(rig.visibility), backgroundScope, UnconfinedTestDispatcher(testScheduler))

  private fun ids(view: OrderedTimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

  private fun ready(controller: ZoneGridController) = controller.state.value as ZoneGridState.Ready

  /** I media dell'indice nella cella che contiene il punto del media [i] al livello 24 (la cella di una bolla a zoom alto). */
  private fun cellMembers(i: Int): Pair<dev.pampa.mycloudgallery.core.map.contract.CellKey, List<MediaId>> {
    val key = rig.cellOf(i, 24)
    return key to rig.points.filter { dev.pampa.mycloudgallery.core.map.contract.GeoGrid.cellOf(it.latE6, it.lonE6, 24) == key }.map { it.id }
  }

  @Test
  fun laGrigliaDiUnaZonaHaGliElementiDellaCellaDalPiuRecente() = runTest {
    val (key, members) = cellMembers(0)
    assertThat(members.size).isAtLeast(2)
    val controller = controller(ZoneTarget.Cell(key), FakeMapPoints(rig.ready))
    runCurrent()
    val view = ready(controller).view
    // Gli stessi elementi della cella, nell'ordine dell'istantanea (0 e' il piu' recente).
    assertThat(ids(view)).containsExactlyElementsIn(members)
    val positions = (0 until view.size).map { view.snapshotIndex(it) }
    assertThat(positions).isInOrder()
    // Ogni elemento e' nella vista e il suo posto e' quello dell'istantanea: il viewer scorre questa vista.
    for (id in members) assertThat(view.viewIndexOf(id)).isAtLeast(0)
  }

  @Test
  fun primaDelFotogrammaPronto_NullaESubitoLoScheletro_PoiLaGriglia() = runTest {
    val (key, _) = cellMembers(0)
    val points = FakeMapPoints()
    val controller = controller(ZoneTarget.Cell(key), points)
    runCurrent()
    assertThat(controller.state.value).isNull()
    // Aprendo la griglia senza essere passati dalla mappa l'indice si costruisce qui.
    assertThat(points.rebuilds).containsExactly(RebuildReason.OPEN)
    points.set(rig.ready)
    runCurrent()
    assertThat(controller.state.value).isInstanceOf(ZoneGridState.Ready::class.java)
    points.set(GeoIndexState.Empty(3))
    runCurrent()
    assertThat(controller.state.value).isEqualTo(ZoneGridState.Empty)
  }

  @Test
  fun unNascostoEsceSubitoDallaGrigliaViva_AncheSeLIndiceNonLoSaAncora() = runTest {
    val (key, members) = cellMembers(0)
    val points = FakeMapPoints(rig.ready)
    val controller = controller(ZoneTarget.Cell(key), points)
    runCurrent()
    assertThat(ids(ready(controller).view)).containsExactlyElementsIn(members)
    // Nascosto un attimo fa: l'istantanea e l'indice sono quelli di prima, ma il filtro finale lo toglie.
    val gone = members.first()
    rig.visibility.hidden += gone
    points.set(GeoIndexState.Ready(ReferenceClusterIndex(rig.points), 5, 0))
    runCurrent()
    val after = ids(ready(controller).view)
    assertThat(after).doesNotContain(gone)
    assertThat(after).containsExactlyElementsIn(members - gone)
    assertThat(rig.visibility.maskCalls).isAtLeast(1)
    // Tutti nascosti: "Nessuna foto qui", mai una griglia con un nascosto (E-H9).
    rig.visibility.hidden += members
    points.set(GeoIndexState.Ready(ReferenceClusterIndex(rig.points), 5, 0))
    runCurrent()
    assertThat(controller.state.value).isEqualTo(ZoneGridState.Empty)
  }

  @Test
  fun unaZonaChePiuNonCeLaGrigliaDiceNessunaFotoQui() = runTest {
    val (key, _) = cellMembers(0)
    val elsewhere = ReferenceClusterIndex(rig.points.filter { dev.pampa.mycloudgallery.core.map.contract.GeoGrid.cellOf(it.latE6, it.lonE6, 24) != key })
    val controller = controller(ZoneTarget.Cell(key), FakeMapPoints(GeoIndexState.Ready(elsewhere, 0, 0)))
    runCurrent()
    assertThat(controller.state.value).isEqualTo(ZoneGridState.Empty)
  }

  @Test
  fun laGrigliaDiUnLuogoHaLeCelleDiQuelLuogoENomeNonCasa() = runTest {
    val place = PLACE_BASE + 1
    val expected = (0 until rig.snapshot.size).filter { rig.snapshot.placeAt(it) == place }.map { rig.snapshot.idAt(it) }
    assertThat(expected).isNotEmpty()
    val named = controller(ZoneTarget.Place(place), FakeMapPoints(rig.ready))
    runCurrent()
    val ready = ready(named)
    assertThat(ids(ready.view)).containsExactlyElementsIn(expected).inOrder()
    assertThat(ready.label).isEqualTo("Luogo 1")
    // Un luogo che il dizionario chiama "Casa" non ha nome: la griglia si apre lo stesso, come "Questa zona".
    val home = controller(ZoneTarget.Place(place), FakeMapPoints(rig.ready), TestPlaceNames(mapOf(place to "Casa")))
    runCurrent()
    assertThat(ready(home).label).isNull()
  }

  @Test
  fun unaZonaSenzaNomeUsaIlLuogoPiuFrequenteMaMaiCasa() = runTest {
    val (key, members) = cellMembers(1)
    val tally = members.map { rig.snapshot.placeAt(rig.snapshot.indexOf(it)) }.filter { it != 0 }.groupingBy { it }.eachCount()
    val top = tally.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
    val controller = controller(ZoneTarget.Cell(key), FakeMapPoints(rig.ready))
    runCurrent()
    assertThat(ready(controller).label).isEqualTo(top?.let { "Luogo ${it - PLACE_BASE}" })
    val home = controller(ZoneTarget.Cell(key), FakeMapPoints(rig.ready), TestPlaceNames(top?.let { mapOf(it to "Casa") }.orEmpty()))
    runCurrent()
    assertThat(ready(home).label).isNull()
  }

  @Test
  fun neiLogNienteDiCoordinateNeDiLuoghi() = runTest {
    val (key, _) = cellMembers(0)
    val controller = controller(ZoneTarget.Cell(key), FakeMapPoints(rig.ready))
    runCurrent()
    assertThat(ZoneTarget.Cell(key).toString()).isEqualTo("ZoneTarget.Cell")
    assertThat(ZoneTarget.Place(PLACE_BASE + 1).toString()).isEqualTo("ZoneTarget.Place")
    assertThat(controller.state.value.toString()).startsWith("ZoneGridState.Ready(")
    assertThat(MapPoint(rig.ids[0], 1, 2, 3, 0).toString()).isEqualTo("MapPoint")
  }
}
