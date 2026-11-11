package dev.pampa.mycloudgallery

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di H1 (la chiusura del viewer, D5 e D7 della prova dell'11/11) **sopra la Libreria vera**: lo scaffold delle prove con la
 * griglia sintetica, una cella toccata, il viewer vero che si apre e poi si chiude. Si vede quello che vedra' l'utente: solo la foto si muove e si
 * rimpicciolisce, lo sfondo del viewer sfuma e sotto si intravede la Libreria; al rilascio la foto vola alla sua cella.
 *
 *  - `trascinamento`: il dito a meta' strada verso il basso, ancora giu';
 *  - `volo`: un attimo dopo il rilascio, la foto in viaggio verso la cella (ritaglio che diventa quello della miniatura);
 *  - `pizzico`: due dita che stringono a zoom minimo, ancora giu' (la stessa chiusura).
 *
 * Chiaro e scuro, telefono dell'utente (360 x 780 dp) e tablet. `gradlew :app:recordRoborazziDebug --tests <classe>` scrive i PNG in
 * `docs/screenshots/beta-grafica/H1/`.
 */
internal abstract class H1ViewerCloseScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shot(name: String) {
    composeRule.onRoot().captureRoboImage("$DIR/viewer-chiusura-$name-$look-$device.png")
  }

  /** La Libreria vera, una cella toccata, il viewer aperto e a riposo (le barre comprese). */
  private fun openViewer() {
    val scenes = ScaffoldScenes()
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        GalleryScaffold(scenes, GalleryTab.Library)
      }
    }
    composeRule.waitForIdle()
    val cells = composeRule.onAllNodesWithContentDescription("Foto, ", substring = true)
    cells[CELL].performClick()
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithContentDescription("Chiudi").fetchSemanticsNodes()).isNotEmpty()
  }

  @Test
  fun trascinamentoEVolo() {
    openViewer()
    composeRule.onRoot().performTouchInput {
      down(center)
      moveBy(Offset(0f, height * 0.12f), delayMillis = 60)
      moveBy(Offset(width * 0.04f, height * 0.12f), delayMillis = 60)
    }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(FADE_MS)
    composeRule.waitForIdle()
    shot("trascinamento")

    composeRule.mainClock.autoAdvance = false
    composeRule.onRoot().performTouchInput { up() }
    composeRule.mainClock.advanceTimeBy(FLIGHT_FRAME_MS)
    shot("volo")
    composeRule.mainClock.autoAdvance = true
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
  }

  @Test
  fun pizzico() {
    openViewer()
    composeRule.onRoot().performTouchInput {
      val half = width * 0.36f
      down(0, center - Offset(half, 0f))
      down(1, center + Offset(half, 0f))
      repeat(PINCH_STEPS) {
        updatePointerBy(0, Offset(half * 0.05f, height * 0.004f))
        updatePointerBy(1, Offset(-half * 0.05f, height * 0.004f))
        move(delayMillis = 16)
      }
    }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(FADE_MS)
    composeRule.waitForIdle()
    shot("pizzico")
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/H1"

    /** Una cella nella parte alta della griglia visibile (la terza riga): il volo di ritorno ha strada da fare. */
    const val CELL = 9

    /** Piu' dell'apertura e dell'arrivo delle barre. */
    const val SETTLE_MS = 2_000L

    /** Le barre del viewer sfumano via quando parte il gesto. */
    const val FADE_MS = 400L

    /** Un fotogramma del volo verso la cella, a meta' circa. */
    const val FLIGHT_FRAME_MS = 96L

    /** Dieci passi da 5%: le dita passano dal 72% al 36% della larghezza (il contenuto a meta' misura). */
    const val PINCH_STEPS = 10

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
internal class H1ViewerClosePhoneScreenshotTest(dark: Boolean) : H1ViewerCloseScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_TABLET)
internal class H1ViewerCloseTabletScreenshotTest(dark: Boolean) : H1ViewerCloseScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
