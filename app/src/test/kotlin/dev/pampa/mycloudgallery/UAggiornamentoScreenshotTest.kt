package dev.pampa.mycloudgallery

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.appicon.AppIconSwitcher
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconChoice
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconFigure
import dev.pampa.mycloudgallery.feature.settings.update.UpdateEntrySummary
import dev.pampa.mycloudgallery.feature.update.CheckState
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import dev.pampa.mycloudgallery.feature.update.install.InstallStep
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import dev.pampa.mycloudgallery.feature.update.ui.LocalUpdateAppIcon
import dev.pampa.mycloudgallery.feature.update.ui.UpdateActions
import dev.pampa.mycloudgallery.feature.update.ui.UpdatePanel
import dev.pampa.mycloudgallery.feature.update.ui.UpdatePromptCard
import dev.pampa.mycloudgallery.main.LocalTabBarNotice
import dev.pampa.mycloudgallery.release.ReleaseRoot
import dev.pampa.mycloudgallery.release.ReleaseStoppedScreen
import dev.pampa.mycloudgallery.release.ReleaseUi
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
 * Pacchetto U, giro 1 delle critiche (UU-14): l'aggiornamento dove lo vede davvero un genitore. L'avviso appoggiato sopra la barra delle schede della
 * Libreria vera (non sopra la pillola: UU-5), con l'icona vera dell'app (UU-7); «Aggiornata» alla riapertura; la pagina «versione fermata» con una
 * figura sola e «Aggiorna» (UU-8); la voce «Aggiornamenti» in cima alle Impostazioni quando c'e' una versione nuova (UU-4). Giro 2: la Libreria sale
 * sopra l'avviso (UU2-4), la pagina fermata senza una versione nuova («arriva presto», UU2-7), l'avviso in sola lettura con «Aggiorna» (UU2-8).
 *
 * `gradlew :app:recordRoborazziDebug --tests "*UAggiornamento*"` scrive i PNG in `docs/screenshots/beta-grafica/U/`.
 */
internal abstract class UAggiornamentoShotsBase(private val device: String, private val fontScale: Float = 1f) {

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

  private val offer = AvailableAppUpdate(
    version = "1.1.0",
    changelog = "### Novità\n- Album più veloci da aprire.",
    releaseTag = "stable-mycloud-gallery-v1.1.0",
    apkAsset = "mycloud-gallery-1.1.0.apk",
    downloadUrl = UpdateSourceConfig.downloadUrl("stable-mycloud-gallery-v1.1.0", "mycloud-gallery-1.1.0.apk"),
    sizeBytes = 115_343_360L,
    sha256 = "a".repeat(64),
  )
  protected val found = UpdatesState(installedVersion = "1.0.0", check = CheckState.Found(offer))
  protected val downloading = found.copy(install = InstallStep.Downloading(0.42f, 48_444_211L, 115_343_360L))
  protected val updated = UpdatesState(installedVersion = "1.1.0", check = CheckState.UpToDate, justUpdated = "1.1.0")
  protected val noNewVersion = UpdatesState(installedVersion = "1.0.0", check = CheckState.UpToDate)

  private val appIcon: @Composable (Dp) -> Unit = { side -> AppIconFigure(choice = AppIconChoice.AUTO, art = AppIconSwitcher.ART, side = side) }

  protected fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalUpdateAppIcon provides appIcon) { content() }
      }
    }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    val look = if (dark) "scuro" else "chiaro"
    composeRule.onRoot().captureRoboImage("$DIR/aggiornamenti-$name-$look-$device.png")
  }

  /** La Libreria vera, con l'avviso dove lo mette l'app (`LocalTabBarNotice`, posato da `MainScaffold` sopra la pillola). */
  protected fun overLibrary(name: String, state: UpdatesState, dark: Boolean) = shoot(name, dark) {
    val scenes = ScaffoldScenes()
    val notice: @Composable (androidx.compose.ui.Modifier) -> Unit = { modifier -> UpdatePromptCard(state = state, actions = UpdateActions.None, modifier = modifier) }
    CompositionLocalProvider(LocalTabBarNotice provides notice) { GalleryScaffold(scenes, GalleryTab.Library) }
  }

  protected fun stopped(dark: Boolean) = shoot("versione-fermata", dark) {
    ReleaseStoppedScreen(ReleaseMessage.SECURITY, onDiagnostics = {}, update = { UpdatePanel(state = found, actions = UpdateActions.None, stopped = true) })
  }

  /** La versione fermata prima che la correzione sia pubblicata: la clessidra, «La versione nuova arriva presto», «Controlla adesso» pieno (UU2-7). */
  protected fun stoppedWithoutFix(dark: Boolean) = shoot("versione-fermata-senza-nuova", dark) {
    ReleaseStoppedScreen(ReleaseMessage.DATA_RISK, onDiagnostics = {}, update = { UpdatePanel(state = noNewVersion, actions = UpdateActions.None, stopped = true) })
  }

  /** In sola lettura: l'avviso in alto con «Aggiorna» sopra la Libreria vera (UU-8, UU2-8). */
  protected fun readOnlyBanner(dark: Boolean) = shoot("sola-lettura-libreria", dark) {
    val scenes = ScaffoldScenes()
    ReleaseRoot(ReleaseUi.UpdateBanner(ReleaseMessage.UPDATE), onUpdate = {}) { GalleryScaffold(scenes, GalleryTab.Library) }
  }

  protected fun settingsTop(dark: Boolean) = shoot("impostazioni-voce-in-cima", dark) {
    SettingsScreen(
      state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList()),
      onBack = {},
      openDiagnostics = {},
      resumeStep = {},
      links = SettingsLinks(openUpdates = {}),
      updateSummary = UpdateEntrySummary(available = "1.1.0"),
    )
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/U"

    /** Piu' di ogni animazione della chrome e dell'entrata dell'avviso. */
    const val SETTLE_MS = 2_000L
  }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-$SHOT_PHONE")
internal class UAggiornamentoPhoneScreenshotTest : UAggiornamentoShotsBase("telefono") {
  @Test fun avvisoSullaLibreriaChiaro() = overLibrary("avviso-libreria", found, dark = false)

  @Test fun avvisoSullaLibreriaScuro() = overLibrary("avviso-libreria", found, dark = true)

  @Test fun avvisoScaricaSullaLibreriaChiaro() = overLibrary("avviso-scarica-libreria", downloading, dark = false)

  @Test fun aggiornataSullaLibreriaChiaro() = overLibrary("aggiornata-libreria", updated, dark = false)

  @Test fun versioneFermataChiaro() = stopped(dark = false)

  @Test fun versioneFermataScuro() = stopped(dark = true)

  @Test fun versioneFermataSenzaNuovaChiaro() = stoppedWithoutFix(dark = false)

  @Test fun solaLetturaChiaro() = readOnlyBanner(dark = false)

  @Test fun solaLetturaScuro() = readOnlyBanner(dark = true)

  @Test fun impostazioniChiaro() = settingsTop(dark = false)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-$SHOT_PHONE")
internal class UAggiornamentoLargeTextScreenshotTest : UAggiornamentoShotsBase("telefono-testo-grande", fontScale = 1.3f) {
  @Test fun avvisoSullaLibreriaChiaro() = overLibrary("avviso-libreria", found, dark = false)

  @Test fun versioneFermataChiaro() = stopped(dark = false)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-$SHOT_TABLET")
internal class UAggiornamentoTabletScreenshotTest : UAggiornamentoShotsBase("tablet-orizzontale") {
  @Test fun avvisoSullaLibreriaChiaro() = overLibrary("avviso-libreria", found, dark = false)

  @Test fun versioneFermataScuro() = stopped(dark = true)
}
