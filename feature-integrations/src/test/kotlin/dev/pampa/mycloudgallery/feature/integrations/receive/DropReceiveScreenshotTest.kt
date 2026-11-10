package dev.pampa.mycloudgallery.feature.integrations.receive

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10 (P6, D21): il foglio della ricezione come lo apre un rilascio dentro l'app: sempre con il conteggio (due foto e un video), e con la destinazione del rilascio gia' scelta: una
 * cartella dell'albero di "Salva sul NAS" (qui «Ricevute > Mare») o un album («Aggiungi ad album» con l'album scelto). Niente parte senza il tocco su "Salva". Telefono, chiaro e scuro.
 * `gradlew :feature-integrations:recordRoborazziDebug --tests "*DropReceiveScreenshot*"` scrive in `docs/screenshots/10-P6/`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = RECEIVE_SHOT_PHONE)
class DropReceiveScreenshotTest(private val dark: Boolean) : ReceiveComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, expect: String, ui: ReceiveUi) {
    show(dark = dark) { ReceiveScreen(ui = ui, actions = ReceiveActions()) }
    assertWithMessage("manca «$expect» nella scena $name").that(composeRule.onAllNodesWithText(expect, substring = true).fetchSemanticsNodes()).isNotEmpty()
    composeRule.onRoot().captureRoboImage("../docs/screenshots/10-P6/rilascio-$name-$look-telefono.png")
  }

  @Test
  fun rilascioSuUnaCartella() = shoot("cartella", "Salva sul NAS", ReceiveUiSamples.ready(deep = true))

  @Test
  fun rilascioSuUnAlbum() = shoot("album", "In quale album", ReceiveAlbumSamples.picking(ReceiveAlbumSamples.family))

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
