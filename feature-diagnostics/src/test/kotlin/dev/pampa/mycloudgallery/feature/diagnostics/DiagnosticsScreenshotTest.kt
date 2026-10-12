package dev.pampa.mycloudgallery.feature.diagnostics

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagActionPolicy
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsExportScreen
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsSectionScreen
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
 * Gli screenshot della Diagnostica (P8): la panoramica in tre stati, ogni sezione con avvisi veri, il test di
 * connessione prima e dopo, l'anteprima dell'esportazione. Chiaro e scuro, telefono (412 dp) e tablet (1280 dp), piu'
 * il testo grande (1,3x) per le pagine piu' dense.
 *
 * `gradlew :feature-diagnostics:recordRoborazziDebug` scrive i PNG in `docs/screenshots/2-P8/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano senza errori.
 */
internal abstract class DiagnosticsScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        // Come un'activity dichiarata nel manifest: finestra accelerata, cosi' Robolectric cattura con il renderer
        // hardware (il vetro della barra usa RuntimeShader, che un Canvas software rifiuta).
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
        if (fontScale == null) {
          content()
        } else {
          val density = LocalDensity.current
          CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
        }
      }
    }
    composeRule.waitForIdle()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  private fun section(
    section: DiagSection,
    state: DiagnosticsUiState = ScreenFixtures.attentionState(),
    actions: DiagActionPolicy = DiagActionPolicy(appActions = ScreenFixtures.appActions, wireGuardAvailable = true),
  ): @Composable () -> Unit = {
    DiagnosticsSectionScreen(section, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {}, actions = actions)
  }

  // --- Panoramica -------------------------------------------------------------------------------------------------

  @Test
  fun panoramica() = shoot("panoramica") {
    DiagnosticsScreen(ScreenFixtures.attentionState(), onBack = {}, onRefresh = {})
  }

  @Test
  fun panoramicaTestoGrande() = shoot("panoramica", fontScale = LARGE_TEXT) {
    DiagnosticsScreen(ScreenFixtures.attentionState(), onBack = {}, onRefresh = {})
  }

  @Test
  fun panoramicaTuttoAPosto() = shoot("panoramica-tutto-a-posto") {
    DiagnosticsScreen(ScreenFixtures.healthyState(), onBack = {}, onRefresh = {})
  }

  @Test
  fun panoramicaInCaricamento() = shoot("panoramica-in-caricamento") {
    DiagnosticsScreen(ScreenFixtures.loadingState(), onBack = {}, onRefresh = {})
  }

  // --- Sezioni ----------------------------------------------------------------------------------------------------

  @Test
  fun reteENas() = shoot("sezione-rete-e-nas", content = section(DiagSection.NETWORK_AND_NAS))

  @Test
  fun testDiConnessioneIniziale() = shoot(
    "sezione-test-di-connessione-iniziale",
    content = section(DiagSection.CONNECTION_TEST, ScreenFixtures.healthyState()),
  )

  @Test
  fun testDiConnessioneEsito() = shoot(
    "sezione-test-di-connessione-esito",
    content = section(DiagSection.CONNECTION_TEST, ScreenFixtures.attentionState(testOutcome = ScreenFixtures.testOutcomeFailed())),
  )

  @Test
  fun testDiConnessioneEsitoTestoGrande() = shoot(
    "sezione-test-di-connessione-esito",
    fontScale = LARGE_TEXT,
    content = section(DiagSection.CONNECTION_TEST, ScreenFixtures.attentionState(testOutcome = ScreenFixtures.testOutcomeFailed())),
  )

  @Test
  fun attivita() = shoot("sezione-attivita", content = section(DiagSection.ACTIVITIES))

  @Test
  fun ultimiEsiti() = shoot("sezione-ultimi-esiti", content = section(DiagSection.RECENT_EVENTS))

  @Test
  fun spazio() = shoot("sezione-spazio", content = section(DiagSection.STORAGE))

  @Test
  fun consumi() = shoot("sezione-consumi-del-mese", content = section(DiagSection.MONTHLY_TRAFFIC))

  @Test
  fun avvisiDiAndroid() = shoot("sezione-avvisi-di-android", content = section(DiagSection.ANDROID_WARNINGS))

  @Test
  fun avvisiDiAndroidTestoGrande() = shoot(
    "sezione-avvisi-di-android",
    fontScale = LARGE_TEXT,
    content = section(DiagSection.ANDROID_WARNINGS),
  )

  @Test
  fun fiducia() = shoot("sezione-fiducia", content = section(DiagSection.TRUST))

  @Test
  fun profili() = shoot("sezione-profili", content = section(DiagSection.PROFILES))

  @Test
  fun sezioneVuota() = shoot("sezione-vuota", content = section(DiagSection.STORAGE, ScreenFixtures.loadingState()))

  // --- Esportazione -------------------------------------------------------------------------------------------------

  @Test
  fun anteprimaDellEsportazione() = shoot("esporta-anteprima") {
    DiagnosticsExportScreen(ScreenFixtures.attentionState().export, refused = false, saveResult = null, onBack = {}, onSave = {})
  }

  @Test
  fun anteprimaDellEsportazioneTestoGrande() = shoot("esporta-anteprima", fontScale = LARGE_TEXT) {
    DiagnosticsExportScreen(ScreenFixtures.attentionState().export, refused = false, saveResult = null, onBack = {}, onSave = {})
  }

  @Test
  fun esportazioneSalvata() = shoot("esporta-salvata") {
    DiagnosticsExportScreen(ScreenFixtures.attentionState().export, refused = false, saveResult = SaveResult.SAVED, onBack = {}, onSave = {})
  }

  @Test
  fun esportazioneNonSalvata() = shoot("esporta-non-salvata") {
    DiagnosticsExportScreen(ScreenFixtures.attentionState().export, refused = false, saveResult = SaveResult.FAILED, onBack = {}, onSave = {})
  }

  @Test
  fun esportazioneBloccata() = shoot("esporta-bloccata") {
    DiagnosticsExportScreen(document = null, refused = true, saveResult = null, onBack = {}, onSave = {})
  }

  companion object {
    const val DIR = "../docs/screenshots/2-P8"
    const val LARGE_TEXT = 1.3f

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
internal class DiagnosticsPhoneScreenshotTest(dark: Boolean) : DiagnosticsScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
internal class DiagnosticsTabletScreenshotTest(dark: Boolean) : DiagnosticsScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
