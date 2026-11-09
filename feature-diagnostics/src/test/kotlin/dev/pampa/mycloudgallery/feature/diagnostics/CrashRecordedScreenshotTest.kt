package dev.pampa.mycloudgallery.feature.diagnostics

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
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
 * Gli screenshot della voce nuova della Diagnostica (RG-B, C472, OA-2 / OB-7): gli errori registrati con il **dove** (quando, e il primo punto del codice nostro) nella sezione
 * «Avvisi di Android» e come finiscono nell'anteprima dell'esportazione, con la catena delle classi e le cornici e senza messaggi. Chiaro e scuro, telefono (412 dp) e tablet
 * orizzontale (1280 dp).
 *
 * `gradlew :feature-diagnostics:recordRoborazziDebug --tests "*CrashRecordedScreenshot*"` scrive i PNG in `docs/screenshots/beta-grafica/rg-b/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano senza errori.
 */
internal abstract class CrashRecordedScreenshotBase(private val dark: Boolean, private val device: String) {

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

  private fun shoot(name: String, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) { content() }
    }
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device.png")
  }

  @Test
  fun avvisiDiAndroidConGliErroriRegistrati() = shoot("avvisi-di-android-con-errori-registrati") {
    DiagnosticsSectionScreen(
      DiagSection.ANDROID_WARNINGS,
      ScreenFixtures.crashRecordedState(),
      onBack = {},
      onRefresh = {},
      onRunTest = {},
      onAction = {},
      actions = DiagActionPolicy(appActions = ScreenFixtures.appActions, wireGuardAvailable = true),
    )
  }

  @Test
  fun anteprimaDellEsportazioneConGliErroriRegistrati() = shoot("esporta-anteprima-con-errori-registrati") {
    DiagnosticsExportScreen(ScreenFixtures.crashRecordedExport(), refused = false, saveResult = null, onBack = {}, onSave = {})
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/rg-b"

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
internal class CrashRecordedPhoneScreenshotTest(dark: Boolean) : CrashRecordedScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
internal class CrashRecordedTabletScreenshotTest(dark: Boolean) : CrashRecordedScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
