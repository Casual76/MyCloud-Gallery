package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerTextSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gli screenshot del testo dal vivo (Fase 6, P12, docs/screenshots/6-P12): il velo del pulsante "Testo", la parola selezionata con le
 * maniglie e la barra, una selezione su piu' righe, una riga inclinata, il foglio "Testo nella foto" e la selezione a foto ingrandita; chiaro
 * e scuro, telefono, tablet e testo a 1,3x. Si scrivono con `gradlew :feature-viewer:recordRoborazziDebug`; nei normali `testDebugUnitTest`
 * verificano solo che gli stati compongano e non scrivono niente.
 *
 * La foto ha il testo disegnato davvero ([LiveTextTestImage]): si vede se l'evidenziazione e le maniglie cadono sulle parole.
 */
abstract class LiveTextScreenshotBase(protected val dark: Boolean) : LiveTextViewerBase() {

  protected val multiline = LiveTextTestLines.multiline

  internal fun picture(layout: ViewerTextLayout): ViewerServices =
    services(imageSource = LiveTextTestImage.source(layout), textSource = FakeViewerTextSource(mutableMapOf(shown to layout)))

  protected fun shot(name: String, device: String = "telefono") {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/6-P12/viewer-testo-$name-${if (dark) "scuro" else "chiaro"}-$device.png")
  }

  protected fun runTalkBackAction() {
    val matcher = SemanticsMatcher("azione Testo nella foto") { node ->
      node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Testo nella foto" } == true
    }
    val action = composeRule.onAllNodes(matcher).fetchSemanticsNodes().first().config[SemanticsActions.CustomActions].first { it.label == "Testo nella foto" }
    composeRule.runOnIdle { action.action() }
    composeRule.waitForIdle()
  }

  protected fun selectWord(layout: ViewerTextLayout, line: Int, word: Int, name: String = "parola", device: String = "telefono") {
    showText(picture(layout), dark = dark)
    longPress(at(layout, line, word))
    shot(name, device)
  }

  protected fun sheet(device: String = "telefono") {
    showText(picture(multiline), dark = dark)
    runTalkBackAction()
    shot("foglio", device)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = dev.pampa.mycloudgallery.feature.viewer.SHOT_PHONE)
class ViewerLiveTextScreenshotTest(dark: Boolean) : LiveTextScreenshotBase(dark) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  @Test
  fun veloSulleRighe() {
    showText(picture(multiline), dark = dark)
    composeRule.onNodeWithContentDescription("Testo nella foto").performClick()
    composeRule.waitForIdle()
    shot("velo")
  }

  @Test
  fun parolaSelezionata() = selectWord(multiline, 0, 1)

  @Test
  fun rigaInclinata() = selectWord(LiveTextTestLines.tilted, 0, 1, name = "riga-inclinata")

  @Test
  fun rigaIn_30Gradi() = selectWord(LiveTextTestLines.steep, 0, 1, name = "riga-meno-30-gradi")

  @Test
  fun selezioneSuPiuRighe() {
    showText(picture(multiline), dark = dark)
    longPress(at(multiline, 0, 1))
    composeRule.onNodeWithContentDescription("Seleziona tutto").performClick()
    composeRule.waitForIdle()
    shot("tutto")
  }

  @Test
  fun foglio() = sheet()

  @Test
  fun selezioneAFotoIngrandita() {
    val layout = LiveTextTestLines.centered
    showText(picture(layout), dark = dark)
    pager().performTouchInput { doubleClick(pageCenter()) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    longPress(pageCenter())
    shot("zoom")
  }
}

/** Sul tablet (1280 dp) la barra e il foglio hanno piu' spazio. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = dev.pampa.mycloudgallery.feature.viewer.TABLET)
class ViewerLiveTextTabletScreenshotTest(dark: Boolean) : LiveTextScreenshotBase(dark) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  @Test
  fun parolaSelezionataSulTablet() = selectWord(multiline, 1, 2, device = "tablet")

  @Test
  fun foglioSulTablet() = sheet(device = "tablet")
}

/** Testo a 1,3x (la regola dei brief: ogni UI e' provata anche con il testo grande). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = dev.pampa.mycloudgallery.feature.viewer.SHOT_PHONE, fontScale = 1.3f)
class ViewerLiveTextLargeTextScreenshotTest(dark: Boolean) : LiveTextScreenshotBase(dark) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  @Test
  fun parolaSelezionataConTestoGrande() = selectWord(multiline, 0, 1, device = "telefono-testo-grande")

  @Test
  fun foglioConTestoGrande() = sheet(device = "telefono-testo-grande")
}
