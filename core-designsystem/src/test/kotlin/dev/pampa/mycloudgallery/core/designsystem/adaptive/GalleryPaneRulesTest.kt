package dev.pampa.mycloudgallery.core.designsystem.adaptive

import android.view.InputDevice
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10 (P5, D12, D13): le regole dei pannelli alle larghezze del riquadro (360, 600, 840 e 1280 dp) e ai loro bordi, il riconoscimento del mouse e la finestra vista da un pannello.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryPaneRulesTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun window(widthDp: Int, heightDp: Int = 800) = GalleryWindow.of(widthDp, heightDp)

  @Test
  fun elencoEDettaglioSolo_daEstesa() {
    val panes = mapOf(360 to false, 599 to false, 600 to false, 839 to false, 840 to true, 1199 to true, 1280 to true)
    for ((dp, two) in panes) {
      val result = GalleryPaneRules.listDetail(window(dp))
      if (two) assertThat(result).isEqualTo(GalleryPanes.ListDetail(0.4f)) else assertThat(result).isEqualTo(GalleryPanes.Single)
    }
  }

  @Test
  fun laMappaHaLaGrigliaDellaZonaSoloDaEstesaESoloConUnaZonaAperta() {
    for (dp in listOf(360, 600, 839)) {
      assertThat(GalleryPaneRules.mapZone(window(dp), zoneOpen = true)).isEqualTo(GalleryPanes.Single)
    }
    for (dp in listOf(840, 1280)) {
      assertThat(GalleryPaneRules.mapZone(window(dp), zoneOpen = true)).isEqualTo(GalleryPanes.Docked(0.4f))
      // Chiusa = mappa intera.
      assertThat(GalleryPaneRules.mapZone(window(dp), zoneOpen = false)).isEqualTo(GalleryPanes.Single)
    }
  }

  @Test
  fun ilViewerSiAffiancaSoloDaAmpiaSoloAperto_eSoloAScelta() {
    for (dp in listOf(360, 600, 840, 1199)) {
      assertThat(GalleryPaneRules.libraryViewer(window(dp), viewerOpen = true, wantsDock = true)).isEqualTo(GalleryPanes.Single)
    }
    assertThat(GalleryPaneRules.libraryViewer(window(1200), viewerOpen = true, wantsDock = true)).isEqualTo(GalleryPanes.Docked(0.4f))
    assertThat(GalleryPaneRules.libraryViewer(window(1280), viewerOpen = true, wantsDock = true)).isEqualTo(GalleryPanes.Docked(0.4f))
    // Di base il viewer e' a finestra intera, e senza viewer non c'e' niente da affiancare.
    assertThat(GalleryPaneRules.libraryViewer(window(1280), viewerOpen = true, wantsDock = false)).isEqualTo(GalleryPanes.Single)
    assertThat(GalleryPaneRules.libraryViewer(window(1280), viewerOpen = false, wantsDock = true)).isEqualTo(GalleryPanes.Single)
  }

  @Test
  fun leDueFinestreDeiPannelliSiSommanoEHannoLaClasseGiusta() {
    val docked = GalleryPanes.Docked(GalleryPaneRules.DOCKED_GRID_FRACTION)
    for (dp in listOf(1200, 1280, 1537, 2560)) {
      val whole = window(dp)
      val split = GalleryPaneRules.split(whole, docked)
      assertThat(split.grid.widthDp + split.other.widthDp).isEqualTo(dp)
      assertThat(split.grid.heightDp).isEqualTo(whole.heightDp)
      assertThat(split.grid.width).isAtMost(GalleryWidth.EXPANDED)
    }
    val at1280 = GalleryPaneRules.split(window(1280), docked)
    assertThat(at1280.grid.widthDp).isEqualTo(512)
    assertThat(at1280.other.widthDp).isEqualTo(768)
    // A 1280 dp la Libreria e' una finestra compatta (512 dp: la sua griglia ha le colonne di un telefono largo) e il viewer una media (768 dp: informazioni in un foglio, non in colonna).
    assertThat(at1280.grid.width).isEqualTo(GalleryWidth.COMPACT)
    assertThat(at1280.other.width).isEqualTo(GalleryWidth.MEDIUM)
  }

  @Test
  fun unMouseBastaEUnDispositivoVirtualeNonLoE() {
    val mouse = InputDevice.SOURCE_MOUSE
    val touchscreen = InputDevice.SOURCE_TOUCHSCREEN
    val keyboard = InputDevice.SOURCE_KEYBOARD
    assertThat(PointerSources.of(emptyList())).isEqualTo(PointerKind.TOUCH)
    assertThat(PointerSources.of(listOf(touchscreen to false, keyboard to false))).isEqualTo(PointerKind.TOUCH)
    assertThat(PointerSources.of(listOf(touchscreen to false, mouse to false))).isEqualTo(PointerKind.MOUSE)
    assertThat(PointerSources.of(listOf(mouse or keyboard to false))).isEqualTo(PointerKind.MOUSE)
    // Il touchpad di DeX sul telefono e' virtuale: non e' un mouse.
    assertThat(PointerSources.of(listOf(mouse to true))).isEqualTo(PointerKind.TOUCH)
  }

  @Test
  fun senzaDispositiviSiLeggeIlTocco() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    assertThat(PointerSources.read(context)).isEqualTo(PointerKind.TOUCH)
  }

  @Composable
  private fun Report(tag: String) {
    val window = rememberGalleryWindow()
    Text("$tag ${window.width} ${window.widthDp} ${window.pointer}")
  }

  @Test
  fun ilPannelloDaALeSchermateLaSuaFinestra_eSenzaPannelloSiLeggeQuellaVera() {
    var pane by mutableStateOf<GalleryWindow?>(GalleryWindow.of(768, 800))
    composeRule.setContent { ProvidePaneWindow(pane) { Report("w") } }
    composeRule.onNodeWithText("w MEDIUM 768 TOUCH").assertExists()
    pane = null
    composeRule.waitForIdle()
    // La finestra vera del test: 412 dp, compatta.
    composeRule.onNodeWithText("w COMPACT 412 TOUCH").assertExists()
  }

  @Test
  fun attraversareUnaSogliaNonPerdeLoStatoDiChiStaDentro() {
    var pane by mutableStateOf<GalleryWindow?>(null)
    var composed by mutableIntStateOf(0)
    composeRule.setContent {
      ProvidePaneWindow(pane) {
        var taps by rememberSaveable { mutableIntStateOf(0) }
        androidx.compose.runtime.DisposableEffect(Unit) {
          composed++
          onDispose { }
        }
        Text("giri $taps", modifier = Modifier.clickable { taps++ })
        Report("w")
      }
    }
    composeRule.onNodeWithText("giri 0").performClick()
    composeRule.onNodeWithText("giri 1").performClick()
    // Da una finestra intera a un pannello e di nuovo a intera: la composizione non si ricrea e il conteggio resta.
    for (dp in listOf(768, 512, 1280, 360)) {
      pane = GalleryWindow.of(dp, 800)
      composeRule.waitForIdle()
      composeRule.onNodeWithText("giri 2").assertExists()
    }
    pane = null
    composeRule.waitForIdle()
    composeRule.onNodeWithText("giri 2").assertExists()
    assertThat(composed).isEqualTo(1)
  }
}
