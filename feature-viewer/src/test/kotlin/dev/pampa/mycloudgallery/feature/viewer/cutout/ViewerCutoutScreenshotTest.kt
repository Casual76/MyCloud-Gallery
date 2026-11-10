package dev.pampa.mycloudgallery.feature.viewer.cutout

import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeCutoutSaver
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gli screenshot del ritaglio del soggetto (Fase 6, P13, docs/screenshots/6-P13): il velo che pulsa mentre il modello lavora, il soggetto
 * staccato con la barra, l'interruttore "Contorno" acceso (l'adesivo con il bordo bianco), il salvataggio in corso, il soggetto a foto
 * ingrandita, la voce del menu ⋮ e l'avviso "Nessun soggetto chiaro"; chiaro e scuro, telefono, tablet e testo a 1,3x. Si scrivono con
 * `gradlew :feature-viewer:recordRoborazziDebug`; nei normali `testDebugUnitTest` verificano solo che gli stati compongano e non scrivono
 * niente.
 *
 * La foto ha il soggetto disegnato davvero ([CutoutTestImage]) e la maschera e' fatta con gli stessi tratti: si vede se il soggetto staccato e'
 * quello della foto e se il contorno gli sta intorno.
 */
abstract class CutoutScreenshotBase(protected val dark: Boolean) : CutoutViewerBase() {

  protected fun shot(name: String, device: String = "telefono") {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/6-P13/viewer-ritaglio-$name-${if (dark) "scuro" else "chiaro"}-$device.png")
  }

  protected fun lifted(device: String, name: String = "soggetto", outline: Boolean = false) {
    showCutout(dark = dark)
    pressRobot()
    if (outline) tap(OUTLINE)
    shot(name, device)
  }

  protected fun veil(device: String) {
    val gate = CompletableDeferred<Unit>()
    showCutout(cutoutServices(cutter = CutoutTestImage.cutter().apply { this.gate = gate }), dark = dark)
    pressRobot()
    shot("velo", device)
    gate.complete(Unit)
  }

  protected fun saving(device: String) {
    val gate = CompletableDeferred<Unit>()
    showCutout(cutoutServices(saver = FakeCutoutSaver().apply { this.gate = gate }), dark = dark)
    pressRobot()
    tap(OUTLINE)
    tap(SAVE)
    shot("salvataggio", device)
    gate.complete(Unit)
  }

  protected fun menu(device: String) {
    showCutout(dark = dark)
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(CUTOUT_ACTION).fetchSemanticsNodes().isNotEmpty() }
    shot("menu", device)
  }

  protected fun none(device: String) {
    showCutout(cutoutServices(cutter = CutoutTestImage.cutter().apply { result = { _, _ -> ViewerCutout.NoSubject } }), dark = dark)
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(CUTOUT_ACTION).fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText(CUTOUT_ACTION).performClick()
    composeRule.waitForIdle()
    shot("nessun-soggetto", device)
  }

  /** Un guasto con il suo motivo (R20, VR-2): il messaggio dice di chiudere qualche app o di liberare spazio, non il generico "riprova". */
  protected fun failed(name: String, reason: CutoutFailure, device: String = "telefono") {
    showCutout(cutoutServices(cutter = CutoutTestImage.cutter().apply { result = { _, _ -> ViewerCutout.Failed("CUTOUT_FAILED", reason) } }), dark = dark)
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(CUTOUT_ACTION).fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText(CUTOUT_ACTION).performClick()
    composeRule.waitForIdle()
    shot(name, device)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = dev.pampa.mycloudgallery.feature.viewer.SHOT_PHONE)
class ViewerCutoutScreenshotTest(dark: Boolean) : CutoutScreenshotBase(dark) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  @Test
  fun veloMentreIlModelloLavora() = veil("telefono")

  @Test
  fun soggettoStaccatoConLaBarra() = lifted("telefono")

  @Test
  fun conIlContorno() = lifted("telefono", name = "contorno", outline = true)

  @Test
  fun salvataggioInCorso() = saving("telefono")

  @Test
  fun voceDelMenu() = menu("telefono")

  @Test
  fun nessunSoggettoChiaro() = none("telefono")

  @Test
  fun confermaDaiNascosti() {
    showCutout(dark = dark, secure = true)
    pressRobot()
    tap(SAVE)
    shot("conferma-nascosti")
  }

  @Test
  fun memoriaInsufficiente() = failed("memoria", CutoutFailure.LOW_MEMORY)

  @Test
  fun spazioInsufficiente() = failed("spazio", CutoutFailure.NO_SPACE)

  @Test
  fun soggettoAFotoIngrandita() {
    showCutout(dark = dark)
    pager().performTouchInput { doubleClick(center) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    pressRobot()
    shot("zoom")
  }
}

/** Sul tablet (1280 dp) la barra e il soggetto hanno piu' spazio. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = dev.pampa.mycloudgallery.feature.viewer.TABLET)
class ViewerCutoutTabletScreenshotTest(dark: Boolean) : CutoutScreenshotBase(dark) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  @Test
  fun soggettoStaccatoSulTablet() = lifted("tablet")

  @Test
  fun conIlContornoSulTablet() = lifted("tablet", name = "contorno", outline = true)

  @Test
  fun veloSulTablet() = veil("tablet")
}

/** Testo a 1,3x (la regola dei brief: ogni UI e' provata anche con il testo grande): le etichette della barra vanno a capo, non si tagliano. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = dev.pampa.mycloudgallery.feature.viewer.SHOT_PHONE, fontScale = 1.3f)
class ViewerCutoutLargeTextScreenshotTest(dark: Boolean) : CutoutScreenshotBase(dark) {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  @Test
  fun soggettoStaccatoConTestoGrande() = lifted("telefono-testo-grande")

  @Test
  fun conIlContornoConTestoGrande() = lifted("telefono-testo-grande", name = "contorno", outline = true)
}
