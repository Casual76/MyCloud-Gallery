package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaKind
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Gli screenshot di H1 nel viewer (D8): **un video ingrandito con i suoi comandi**. Il doppio tocco ha ingrandito il video (qui la sua miniatura:
 * Robolectric non disegna la superficie del lettore), la barra del video e le azioni restano dove sono, della loro misura, sopra il contenuto
 * ingrandito. Chiaro e scuro, telefono dell'utente (360 x 780 dp) e tablet.
 *
 * La chiusura sopra la Libreria vera sta in `:app` (`H1ViewerCloseScreenshotTest`). `gradlew :feature-viewer:recordRoborazziDebug --tests
 * dev.pampa.mycloudgallery.feature.viewer.ViewerH1ShotsPhone` (e `...Tablet`) scrive i PNG in `docs/screenshots/beta-grafica/H1/`.
 */
abstract class ViewerH1Shots(private val device: String) : ViewerTestBase() {

  /** Una Libreria con dei video. */
  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private fun path(name: String, dark: Boolean) = "../docs/screenshots/beta-grafica/H1/viewer-$name-${if (dark) "scuro" else "chiaro"}-$device.png"

  private fun videoZoomed(dark: Boolean) {
    val index = fixture.indicesOf(MediaKind.VIDEO).first { it > 2 }
    // Un fotogramma riconoscibile (cielo, prato, nuvole) al posto del gradiente: si vede che e' ingrandito.
    show(request = fixture.request(startIndex = index), services = services(imageSource = photoSource(lightPhoto(portrait = false))), dark = dark)
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Pausa").assertExists()
    assertThat(composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Ingrandita")).fetchSemanticsNodes()).isNotEmpty()
    composeRule.onRoot().captureRoboImage(path("video-ingrandito", dark))
  }

  @Test fun videoIngrandito_chiaro() = videoZoomed(dark = false)

  @Test fun videoIngrandito_scuro() = videoZoomed(dark = true)

  private companion object {
    const val SETTLE_MS = 800L
  }
}

/** Il telefono dell'utente (Galaxy S25, SM-S931B): 360 x 780 dp. */
@Config(qualifiers = "it-w360dp-h780dp-xhdpi")
class ViewerH1ShotsPhone : ViewerH1Shots("telefono")

@Config(qualifiers = "it-w1280dp-h800dp-xhdpi")
class ViewerH1ShotsTablet : ViewerH1Shots("tablet")
