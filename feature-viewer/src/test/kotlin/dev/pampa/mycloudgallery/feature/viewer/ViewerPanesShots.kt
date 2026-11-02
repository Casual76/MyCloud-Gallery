package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPaneRules
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPanes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.ProvidePaneWindow
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Gli screenshot del viewer ai quattro riquadri (Fase 10, P5, D13 (5), §2.3), chiaro e scuro: a finestra intera a 360, 600, 840 (informazioni in colonna) e 1280 dp, e **affiancato** alla Libreria a 1280 dp
 * (la Libreria al 40% a sinistra, il viewer al 60% a destra, che e' una finestra media: informazioni in un foglio). `gradlew :feature-viewer:recordRoborazziDebug --tests "*ViewerPanesShots*"` scrive i PNG in
 * `docs/screenshots/10-P5/`.
 */
abstract class ViewerPanesShots(private val width: Int) : ViewerTestBase() {

  private fun path(name: String, dark: Boolean) = "../docs/screenshots/10-P5/$name-${width}dp-${if (dark) "scuro" else "chiaro"}.png"

  private fun whole(dark: Boolean) {
    show(dark = dark)
    composeRule.onRoot().captureRoboImage(path("viewer", dark))
    // Con le informazioni aperte: da 840 dp in una colonna a destra, sotto in un foglio.
    composeRule.onNodeWithContentDescription("Informazioni").performClick()
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage(path("viewer-informazioni", dark))
  }

  @Test fun intero_chiaro() = whole(dark = false)

  @Test fun intero_scuro() = whole(dark = true)

  private fun docked(dark: Boolean) {
    val window = GalleryWindow.of(width, 800)
    val split = GalleryPaneRules.split(window, GalleryPanes.Docked(GalleryPaneRules.DOCKED_GRID_FRACTION))
    val dock = FakeViewerDock(docked = true)
    val request = fixture.request()
    val services = services()
    composeRule.setContent {
      ViewerTestHost(dark = dark) {
        CompositionLocalProvider(LocalViewerDock provides dock) {
          Row {
            // La Libreria affiancata e' una griglia viva: qui un segnaposto per l'ingombro, la griglia vera e' negli screenshot della Libreria.
            Box(Modifier.width(split.grid.widthDp.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer)) { Text("Libreria") }
            Box(Modifier.width(split.other.widthDp.dp).fillMaxHeight()) {
              ProvidePaneWindow(split.other) { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) }
            }
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage(path("viewer-affiancato", dark))
  }

  @Test fun affiancato_chiaro() = if (GalleryPaneRules.canDock(GalleryWindow.of(width, 800))) docked(dark = false) else Unit

  @Test fun affiancato_scuro() = if (GalleryPaneRules.canDock(GalleryWindow.of(width, 800))) docked(dark = true) else Unit
}

@Config(qualifiers = "it-w360dp-h800dp-xhdpi")
class ViewerPanesShots360 : ViewerPanesShots(360)

@Config(qualifiers = "it-w600dp-h900dp-xhdpi")
class ViewerPanesShots600 : ViewerPanesShots(600)

@Config(qualifiers = "it-w840dp-h900dp-xhdpi")
class ViewerPanesShots840 : ViewerPanesShots(840)

@Config(qualifiers = "it-w1280dp-h800dp-xhdpi")
class ViewerPanesShots1280 : ViewerPanesShots(1280)
