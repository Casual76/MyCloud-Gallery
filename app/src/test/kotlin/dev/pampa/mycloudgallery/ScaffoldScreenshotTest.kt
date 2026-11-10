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
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.LockedScreenState
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsScreen
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsUiState
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingScreen
import dev.pampa.mycloudgallery.feature.onboarding.join.AwaitingApprovalScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.lock.LockedScreen
import dev.pampa.mycloudgallery.feature.library.library.emptyLibraryTabState
import dev.pampa.mycloudgallery.root.BlockedScreen
import dev.pampa.mycloudgallery.root.KeystoreUi
import dev.pampa.mycloudgallery.root.KeystoreUnavailableScreen
import dev.pampa.mycloudgallery.root.WipedScreen
import dev.pampa.mycloudgallery.status.GlobalStatus
import androidx.test.core.app.ApplicationProvider
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
 * Gli screenshot dello scaffold della Fase 2 (P0): galleria con le tre schede e l'indicatore globale,
 * onboarding, attesa dell'approvazione, azzerato, fermo, app bloccata, Impostazioni e Diagnostica. Chiaro e
 * scuro, telefono dell'utente (360 dp, K3a) e tablet (1280 dp), piu' il testo grande sul telefono.
 *
 * `gradlew :app:recordRoborazziDebug` scrive i PNG in `docs/screenshots/2-P0/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano.
 *
 * L'applicazione e' quella di Android, non `MyCloudGalleryApp`: qui si disegnano le schermate con stati
 * espliciti, senza Hilt.
 */
internal abstract class ScaffoldScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  /**
   * L'activity vuota su cui la regola di Compose disegna (`ComponentActivity`) non e' nel manifest
   * dell'app: `ui-test-manifest` la aggiunge solo con `debugImplementation`, cioe' all'APK di debug, e non la
   * vogliamo li'. La si registra nel PackageManager di Robolectric prima che la regola la lanci.
   */
  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        // Come un'activity dichiarata nel manifest: finestra accelerata, cosi' Robolectric cattura con il
        // renderer hardware (il vetro della barra usa RuntimeShader, che un Canvas software rifiuta).
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

  /** La Libreria vuota (com'era la release prima del grafo vero), nello scaffold delle prove. */
  @Test
  fun libreria() {
    val scenes = ScaffoldScenes(library = emptyLibraryTabState())
    shoot("main-libreria") { GalleryScaffold(scenes, GalleryTab.Library) }
  }

  @Test
  fun libreriaTestoGrande() {
    val scenes = ScaffoldScenes(library = emptyLibraryTabState())
    shoot("main-libreria", fontScale = LARGE_TEXT) { GalleryScaffold(scenes, GalleryTab.Library, status = GlobalStatus.AWAY_VPN_MOBILE) }
  }

  /** K3a: le Raccolte vere (lo scaffold delle prove, [GalleryScaffold]), con la capsula della rete in testa nello stato "VPN spenta". */
  @Test
  fun raccolte() {
    val scenes = ScaffoldScenes()
    shoot("main-raccolte-vpn-spenta") { GalleryScaffold(scenes, GalleryTab.Collections, status = GlobalStatus.VPN_OFF) }
  }

  /** G2: Cerca non e' piu' una scheda ma il cerchio accanto alla pillola; aperta, sta sopra la scheda di prima (K3a: la schermata vera, a riposo). */
  @Test
  fun cerca() {
    val scenes = ScaffoldScenes()
    shoot("main-cerca-password") { GalleryScaffold(scenes, GalleryTab.Library, status = GlobalStatus.NEEDS_PASSWORD, searchOpen = true) }
  }

  @Test
  fun onboarding() = shoot("onboarding") {
    OnboardingScreen(progress = OnboardingProgress.START, onContinue = {})
  }

  @Test
  fun attesa() = shoot("attesa-approvazione") {
    AwaitingApprovalScreen(code = "K7QD-2M9X-FA4T", deviceKeyQr = null)
  }

  @Test
  fun azzerato() = shoot("azzerato") {
    WipedScreen(onReconfigure = {})
  }

  @Test
  fun fermo() = shoot("fermo-aggiornare") {
    BlockedScreen(reason = BlockReason.NEEDS_APP_UPDATE)
  }

  /** R7: il Keystore non risponde. "Riprova" in primo piano, "Configura di nuovo" solo in fondo. */
  @Test
  fun fermoChiavi() = shoot("fermo-chiavi") {
    KeystoreUnavailableScreen(KeystoreUi(stillUnavailable = true), onRetry = {}, onAskStartOver = {}, onCancelStartOver = {}, onConfirmStartOver = {})
  }

  @Test
  fun bloccata() = shoot("bloccata") {
    LockedScreen(
      LockedScreenState(
        availability = AuthAvailability.AVAILABLE,
        lastResult = null,
        isAuthenticating = false,
        onUnlock = {},
        onOpenSecuritySettings = {},
      ),
    )
  }

  @Test
  fun impostazioni() = shoot("impostazioni") {
    SettingsScreen(
      state = SettingsUiState(
        trust = TrustStatus.Approved(admin = true),
        deviceCount = 3,
        pendingRequests = 0,
        pendingSteps = listOf(OnboardingStep.BATTERY, OnboardingStep.VPN),
      ),
      onBack = {},
      openDiagnostics = {},
      resumeStep = {},
    )
  }

  @Test
  fun diagnostica() = shoot("diagnostica") {
    DiagnosticsScreen(
      state = DiagnosticsUiState(loading = false, sections = emptyMap(), recentEvents = 0),
      onBack = {},
      onRefresh = {},
    )
  }

  companion object {
    const val DIR = "../docs/screenshots/2-P0"
    const val LARGE_TEXT = 1.3f

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
internal class ScaffoldPhoneScreenshotTest(dark: Boolean) : ScaffoldScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_TABLET)
internal class ScaffoldTabletScreenshotTest(dark: Boolean) : ScaffoldScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
