package dev.pampa.mycloudgallery.feature.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPaneRules
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPanes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.ProvideGalleryWindow
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridController
import dev.pampa.mycloudgallery.feature.map.grid.ZoneTarget
import dev.pampa.mycloudgallery.feature.map.map.MapPanesLayout
import dev.pampa.mycloudgallery.feature.map.map.MapPanesTags
import dev.pampa.mycloudgallery.feature.map.map.MapZoneState
import dev.pampa.mycloudgallery.feature.map.support.FakeMapPoints
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.support.TestPlaceNames
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 10 (P5, D13 (2), D14, E-T2): la zona aperta accanto alla mappa. La zona sta nel `SavedStateHandle` (sopravvive a una ricreazione e passa fra pannello e pagina), la griglia e' la stessa vista viva della
 * pagina singola, e i due pannelli non si sovrappongono alle larghezze del riquadro (360, 600, 840 e 1280 dp).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapZoneStateTest {

  private val rig = MapRig(count = 90)

  private fun TestScope.state(handle: SavedStateHandle, created: MutableList<CellKey> = mutableListOf()) = MapZoneState(
    handle = handle,
    newController = { key, scope ->
      created += key
      ZoneGridController(ZoneTarget.Cell(key), FakeMapPoints(rig.ready), rig.visibility, TestPlaceNames(), FinalFilter(rig.visibility), scope, UnconfinedTestDispatcher(testScheduler))
    },
    parent = backgroundScope,
  )

  @Test
  fun laZonaApertaSiSalvaESopravvive_aUnaStatoNuovoSulloStessoHandle() = runTest {
    val handle = SavedStateHandle()
    val key = rig.cellOf(0, 24)
    val first = state(handle)
    assertThat(first.zone.value).isNull()
    first.open(key)
    runCurrent()
    assertThat(first.zone.value).isEqualTo(key)
    // Una ricreazione vera o la morte del processo: lo stato si rifa dallo stesso handle e la zona e' ancora aperta.
    val second = state(SavedStateHandle(mapOf(MapZoneState.KEY to handle.get<String>(MapZoneState.KEY))))
    assertThat(second.zone.value).isEqualTo(key)
    second.close()
    runCurrent()
    assertThat(second.zone.value).isNull()
  }

  @Test
  fun unDatoVecchioOOstileNonApreNiente() {
    for (bad in listOf("", "x", "1", "1:2:3", "99:0", "-1:0", "10:-5", "a:b", "24:" + Long.MAX_VALUE, null)) {
      assertThat(MapZoneState.decode(bad)).isNull()
    }
    val key = CellKey(24, 12345)
    assertThat(MapZoneState.decode(MapZoneState.encode(key))).isEqualTo(key)
    // Mai una coordinata chiara: livello e prefisso della chiave di Morton.
    assertThat(MapZoneState.encode(key)).isEqualTo("24:12345")
  }

  @Test
  fun laGrigliaDellaZonaEQuellaVivaDellaPagina_unControllorePerZona() = runTest {
    val created = mutableListOf<CellKey>()
    val state = state(SavedStateHandle(), created)
    val a = rig.cellOf(0, 24)
    val controller = state.controllerFor(a)
    runCurrent()
    // Lo stesso controllore finche' la zona e' quella: nessuna copia, nessun lavoro in piu' a ogni disegno.
    assertThat(state.controllerFor(a)).isSameInstanceAs(controller)
    assertThat(created).containsExactly(a)
    assertThat(controller.state.value).isInstanceOf(ZoneGridState.Ready::class.java)
    // Un'altra zona: un controllore nuovo, e il lavoro di quello di prima si ferma.
    val b = rig.cellOf(45, 24)
    if (b != a) {
      val other = state.controllerFor(b)
      assertThat(other).isNotSameInstanceAs(controller)
      assertThat(created).containsExactly(a, b).inOrder()
    }
    state.release()
  }
}

/** I due pannelli della mappa alle quattro larghezze del riquadro. */
class MapPanesLayoutTest : MapComposeTest() {

  private var widthDp by mutableStateOf(1280)
  private var zoneOpen by mutableStateOf(true)
  private var created = 0

  /** Compone i pannelli una volta sola (`setContent` si chiama una volta per test); larghezza e zona le cambia il test. */
  private fun start(initialWidthDp: Int, zone: Boolean) {
    widthDp = initialWidthDp
    zoneOpen = zone
    show {
      val window = GalleryWindow.of(widthDp, 800)
      ProvideGalleryWindow(window) {
        val panes = GalleryPaneRules.mapZone(window, zoneOpen)
        val split = (panes as? GalleryPanes.Docked)?.let { GalleryPaneRules.split(window, it) }
        Box(Modifier.requiredSize(widthDp.dp, 800.dp)) {
          MapPanesLayout(
            panes = panes,
            split = split,
            map = {
              // Lo stato della mappa (la sua camera, la superficie): non deve ripartire quando i pannelli cambiano.
              var taps by rememberSaveable { mutableIntStateOf(0) }
              DisposableEffect(Unit) {
                created++
                onDispose { }
              }
              Text("camera $taps", modifier = Modifier.clickable { taps++ })
            },
            zone = { Text("zona") },
          )
        }
      }
    }
  }

  private fun resizeTo(dp: Int) {
    widthDp = dp
    composeRule.waitForIdle()
  }

  @Test
  fun sottoLaSoglia_laMappaEIntera_ancheConUnaZonaAperta() {
    start(360, zone = true)
    for (dp in listOf(360, 600, 839)) {
      resizeTo(dp)
      composeRule.onAllNodesWithTag(MapPanesTags.Zone).assertCountEquals(0)
      assertThat(composeRule.onNodeWithTag(MapPanesTags.Map).getUnclippedBoundsInRoot().width).isEqualTo(dp.dp)
    }
  }

  @Test
  fun daEstesa_laMappaEIl60eLaGrigliaDellaZonaIl40_senzaSovrapporsi() {
    start(840, zone = true)
    for (dp in listOf(840, 1280)) {
      resizeTo(dp)
      val map = composeRule.onNodeWithTag(MapPanesTags.Map).getUnclippedBoundsInRoot()
      val zone = composeRule.onNodeWithTag(MapPanesTags.Zone).getUnclippedBoundsInRoot()
      val expectedZone = GalleryPaneRules.gridWidthDp(GalleryWindow.of(dp, 800), GalleryPanes.Docked(GalleryPaneRules.ZONE_FRACTION))
      assertThat(zone.width).isEqualTo(expectedZone.dp)
      assertThat(map.width + zone.width).isEqualTo(dp.dp)
      assertThat(map.right).isAtMost(zone.left)
      composeRule.onNodeWithText("zona").assertExists()
    }
  }

  @Test
  fun chiusa_laMappaEIntera_ancheSuUnaFinestraAmpia() {
    start(1280, zone = false)
    composeRule.onAllNodesWithTag(MapPanesTags.Zone).assertCountEquals(0)
    assertThat(composeRule.onNodeWithTag(MapPanesTags.Map).getUnclippedBoundsInRoot().width).isEqualTo(1280.dp)
  }

  @Test
  fun attraversareLaSogliaNonRicreaLaMappa_eNonPerdeLoStato() {
    start(1280, zone = true)
    composeRule.onNodeWithText("camera 0").performClick()
    composeRule.onNodeWithText("camera 1").assertExists()
    // Da 1280 a 600 a 1280 a 360 a 840, poi la zona chiusa e riaperta: la camera resta, la mappa e' stata composta una volta sola.
    for (dp in listOf(600, 1280, 360, 840)) {
      resizeTo(dp)
      composeRule.onNodeWithTag(MapPanesTags.Map).assertExists()
      composeRule.onNodeWithText("camera 1").assertExists()
    }
    zoneOpen = false
    composeRule.waitForIdle()
    zoneOpen = true
    composeRule.waitForIdle()
    composeRule.onNodeWithText("camera 1").assertExists()
    assertThat(created).isEqualTo(1)
  }
}
