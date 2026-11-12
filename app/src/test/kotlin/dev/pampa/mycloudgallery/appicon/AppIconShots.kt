package dev.pampa.mycloudgallery.appicon

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.SHOT_PHONE
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.AppIconEntry
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconChoice
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconScreen
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I5: gli screenshot di Impostazioni -> Icona dell'app con le **risorse vere** dell'icona (il primo piano `ic_launcher_foreground` e i due sfondi fissi,
 * `AppIconSwitcher.ART`): chiaro e scuro sul telefono (360 dp), il testo a 1,3x, e la voce nella pagina principale. `gradlew :app:recordRoborazziDebug
 * --tests dev.pampa.mycloudgallery.appicon.AppIconPhoneShots --tests dev.pampa.mycloudgallery.appicon.AppIconTallShots` li scrive in
 * `docs/screenshots/beta-grafica/I5/`; nei normali `testDebugUnitTest` si verifica solo che le pagine compongano.
 */
internal abstract class AppIconShotsBase {

  val composeRule = createComposeRule()

  /** `ComponentActivity` non e' nel manifest dell'app: la si registra nel PackageManager di Robolectric (come `ScaffoldScreenshotTest`). */
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

  protected fun shoot(name: String, dark: Boolean, fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        if (fontScale == null) {
          content()
        } else {
          val density = LocalDensity.current
          CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage("$DIR/$name.png")
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/I5"
  }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-$SHOT_PHONE")
internal class AppIconPhoneShots : AppIconShotsBase() {

  @Test
  fun chiaro() = shoot("icona-app-chiaro-telefono", dark = false) {
    AppIconScreen(choice = AppIconChoice.AUTO, art = AppIconSwitcher.ART, onBack = {}, onChoose = {})
  }

  @Test
  fun scuro() = shoot("icona-app-scuro-telefono", dark = true) {
    AppIconScreen(choice = AppIconChoice.AUTO, art = AppIconSwitcher.ART, onBack = {}, onChoose = {})
  }

  @Test
  fun scuraSceltaTestoGrande() = shoot("icona-app-scura-scelta-chiaro-telefono-testo-grande", dark = false, fontScale = 1.3f) {
    AppIconScreen(choice = AppIconChoice.DARK, art = AppIconSwitcher.ART, onBack = {}, onChoose = {})
  }

  @Test
  fun chiaraSceltaScuro() = shoot("icona-app-chiara-scelta-scuro-telefono", dark = true) {
    AppIconScreen(choice = AppIconChoice.LIGHT, art = AppIconSwitcher.ART, onBack = {}, onChoose = {})
  }
}

/** La pagina principale delle Impostazioni intera (360 x 1800 dp): la voce «Icona dell'app» nel gruppo «Foto», accanto al widget. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h1800dp-xhdpi")
internal class AppIconTallShots : AppIconShotsBase() {

  private val state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList())

  @Test
  fun impostazioniChiaro() = shoot("impostazioni-con-icona-app-chiaro-telefono-intera", dark = false) {
    SettingsScreen(
      state = state,
      onBack = {},
      openDiagnostics = {},
      resumeStep = {},
      links = SettingsLinks(openWidget = {}, openAppIcon = {}),
      appIcon = AppIconEntry(AppIconChoice.AUTO, AppIconSwitcher.ART),
    )
  }

  @Test
  fun impostazioniScuro() = shoot("impostazioni-con-icona-app-scuro-telefono-intera", dark = true) {
    SettingsScreen(
      state = state,
      onBack = {},
      openDiagnostics = {},
      resumeStep = {},
      links = SettingsLinks(openWidget = {}, openAppIcon = {}),
      appIcon = AppIconEntry(AppIconChoice.DARK, AppIconSwitcher.ART),
    )
  }
}
