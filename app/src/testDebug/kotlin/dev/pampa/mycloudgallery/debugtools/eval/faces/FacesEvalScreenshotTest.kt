package dev.pampa.mycloudgallery.debugtools.eval.faces

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot dello strumento "Valutazione delle persone" (Fase 8, P11): la pagina di partenza a meta' lavoro, l'elenco dei gruppi di una sessione, un gruppo con i
 * volti (e i dieci meno simili), "e' la stessa persona di ..." e il resoconto dei numeri. Telefono (412 dp), chiaro e scuro, piu' il testo a 1,3x per il gruppo.
 * **Solo dati sintetici**: una famiglia di vettori inventati e ritagli che sono sagome astratte colorate, mai un volto.
 *
 * `gradlew :app:recordRoborazziDebug --tests "*FacesEvalScreenshot*"` scrive i PNG in `docs/screenshots/8-P11/`. Nei normali `testDebugUnitTest` `captureRoboImage` non
 * fa niente e qui si verifica solo che le schermate compongano.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class FacesEvalScreenshotTest {

  private val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterActivity()).around(composeRule)

  private class RegisterActivity : TestWatcher() {
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

  @After
  fun tearDown() {
    FacesEvalEnvironmentHolder.current = null
  }

  private val app: Application = ApplicationProvider.getApplicationContext()

  /** Una famiglia sintetica abbastanza grande perche' il gruppo piu' grande abbia piu' di 70 volti (e quindi i dieci meno simili). */
  private val rig: FacesEvalTestRig by lazy { FacesEvalTestRig.family(seed = 7L, named = 3, photos = 420) }

  private val calibration = EvalSessionKind.CALIBRATION

  /** La sessione 1 a meta' (i primi gruppi guardati, uno misto, uno diviso in due) e la sessione 2 appena cominciata. */
  private fun prepared(): FacesEvalSession {
    val session = FacesEvalSession(rig, FacesEvalStore.of(app), newSeed = { 4L }).also { it.load() }
    runBlocking {
      session.start(calibration)
      idealUser(rig, session, calibration, mixedShare = 0.6, limit = 5)
      val groups = session.session(calibration)!!.groups.map { it.person }
      session.toggleSame(calibration, groups[1], groups[2])
      // Un gruppo con due volti segnati (uno fra i meno simili) e uno dato per misto: cosi' la griglia e l'elenco mostrano tutti gli stati.
      val choice = session.choice(calibration, groups[3])
      session.toggleWrong(calibration, groups[3], choice.main[2].locator, choice.all.size)
      choice.leastSimilar.firstOrNull()?.let { session.toggleWrong(calibration, groups[3], it.locator, choice.all.size) }
      session.setMixed(calibration, groups[4], true, 40)
      session.start(EvalSessionKind.MEASURE)
    }
    return session
  }

  private fun shoot(name: String, dark: Boolean, fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        if (fontScale == null) content() else CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
      }
    }
    // Le pagine che leggono dalla porta mostrano "Carico i volti..." un attimo: si aspetta che sia finito prima di fotografare.
    composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(FacesEvalStrings.LOADING).fetchSemanticsNodes().isEmpty() }
    composeRule.waitForIdle()
    val look = if (dark) "scuro" else "chiaro"
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("../docs/screenshots/8-P11/$name-$look-telefono$suffix.png")
  }

  @Test fun partenzaChiaro() = runPage { shoot("partenza", dark = false) { FacesEvalScreen(onBack = {}) } }

  @Test fun partenzaScuro() = runPage { shoot("partenza", dark = true) { FacesEvalScreen(onBack = {}) } }

  @Test fun partenzaTestoGrande() = runPage { shoot("partenza", dark = false, fontScale = LARGE_TEXT) { FacesEvalScreen(onBack = {}) } }

  @Test fun gruppiChiaro() = runPage { s -> shoot("gruppi", dark = false) { FacesGroupsPage(s, calibration) {} } }

  @Test fun gruppiScuro() = runPage { s -> shoot("gruppi", dark = true) { FacesGroupsPage(s, calibration) {} } }

  @Test fun gruppoChiaro() = runPage { s -> shoot("gruppo", dark = false) { FacesGroupPage(s, rig, calibration, s.session(calibration)!!.groups[3].person) {} } }

  @Test fun gruppoScuro() = runPage { s -> shoot("gruppo", dark = true) { FacesGroupPage(s, rig, calibration, s.session(calibration)!!.groups[3].person) {} } }

  @Test fun gruppoTestoGrande() = runPage { s ->
    shoot("gruppo", dark = false, fontScale = LARGE_TEXT) { FacesGroupPage(s, rig, calibration, s.session(calibration)!!.groups[3].person) {} }
  }

  @Test fun stessaPersonaChiaro() = runPage { s -> shoot("stessa-persona", dark = false) { FacesSamePage(s, rig, calibration, s.session(calibration)!!.groups[1].person) {} } }

  @Test fun stessaPersonaScuro() = runPage { s -> shoot("stessa-persona", dark = true) { FacesSamePage(s, rig, calibration, s.session(calibration)!!.groups[1].person) {} } }

  @Test fun numeriChiaro() = runPage { s ->
    shoot("numeri", dark = false) { FacesTextPage(FacesPage.Text("report-01.txt", s.reportText(calibration)!!)) {} }
  }

  @Test fun numeriScuro() = runPage { s ->
    shoot("numeri", dark = true) { FacesTextPage(FacesPage.Text("report-01.txt", s.reportText(calibration)!!)) {} }
  }

  /** Prepara le sessioni, mette la porta e lancia la scena. */
  private fun runPage(scene: (FacesEvalSession) -> Unit) {
    val session = prepared()
    FacesEvalEnvironmentHolder.current = rig
    scene(session)
  }

  private companion object {
    const val LARGE_TEXT = 1.3f
  }
}
