package dev.pampa.mycloudgallery.feature.viewer.motion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.feature.viewer.ViewerFixture
import dev.pampa.mycloudgallery.feature.viewer.ViewerTestBase
import dev.pampa.mycloudgallery.feature.viewer.ViewerTestHost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Il badge "Movimento" nei suoi stati nuovi (Fase 6, VM-3, docs/screenshots/6-P14): mentre il video di un media solo sul NAS si legge (fino a 64 MB) il badge
 * mostra un indicatore e dice "In caricamento", e il tocco non ricomincia la lettura. Il badge e' composto da solo, su uno sfondo che somiglia a una foto: lo
 * stato "in caricamento" nel viewer intero si vede solo con un NAS vero (si guarda sul telefono, F71). Si scrive con `gradlew :feature-viewer:recordRoborazziDebug`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class MotionBadgeScreenshotTest(private val dark: Boolean) : ViewerTestBase() {

  override val fixture: ViewerFixture = ViewerFixture(count = 10)

  companion object {
    private const val SCENE = "scena-badge"

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  /** Si fotografa solo la scena del badge (non tutto lo schermo): un riquadro che somiglia a una foto con il badge in mezzo. */
  private fun shot(name: String) {
    composeRule.onNodeWithTag(SCENE).captureRoboImage("../docs/screenshots/6-P14/viewer-movimento-$name-${if (dark) "scuro" else "chiaro"}-telefono.png")
  }

  private fun badge(playing: Boolean = false, unavailable: Boolean = false, loading: Boolean = false) {
    // L'indicatore gira all'infinito: il tempo lo si fa avanzare a mano.
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      ViewerTestHost(dark = dark) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
          Box(
            modifier = Modifier.size(width = 240.dp, height = 120.dp).background(Color(0xFF6D7F8C)).testTag(SCENE),
            contentAlignment = Alignment.Center,
          ) {
            MotionBadge(playing = playing, unavailable = unavailable, onClick = {}, loading = loading)
          }
        }
      }
    }
    composeRule.mainClock.advanceTimeBy(600)
  }

  @Test
  fun badgeInCaricamento() {
    badge(loading = true)
    composeRule.onNodeWithContentDescription("Riproduci il movimento").assertExists()
    shot("in-caricamento")
  }
}
