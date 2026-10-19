package dev.pampa.mycloudgallery.navigation

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.MainActivity
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingCurrentStepRoute
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingGraphRoute
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingResumeRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsDevicesRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsGraphRoute
import dev.pampa.mycloudgallery.root.RootDestination
import dev.pampa.mycloudgallery.status.GlobalStatus
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Revisione di sicurezza della Fase 2, F5: un'altra app sul telefono che entra non deve poter mostrare un codice
 * d'approvazione falso dentro l'app (deep link interni di Navigation via extra dell'intent, o una rotta con il codice
 * come argomento), ne' coprirlo con una sovrapposizione o catturarlo.
 */
@RunWith(RobolectricTestRunner::class)
class EntryHardeningTest {

  private val compose = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
      // HIDE_OVERLAY_WINDOWS e' un permesso normale: su un telefono vero e' concesso all'installazione.
      shadowOf(app).grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS")
    }
  }

  private val actions = RootNavActions(
    openWireGuard = { false },
    deviceKeyQr = { null },
    watchApproval = { emptyFlow() },
    reconfigure = {},
    pause = {},
  )

  private fun show(destination: RootDestination): NavHostController {
    var controller: NavHostController? = null
    compose.setContent {
      MyCloudGalleryTheme {
        AppNavHost(destination, GlobalStatus.OFFLINE, actions, onNavController = { controller = it })
      }
    }
    compose.waitForIdle()
    return checkNotNull(controller)
  }

  private fun assertUnreachable(nav: NavHostController, vararg routes: Any) {
    for (route in routes) {
      compose.runOnUiThread {
        assertThrows(IllegalArgumentException::class.java) { nav.navigate(route) }
      }
    }
  }

  /** Fase 4B (P16): le pagine del NAS (rotte stringa di `NasScreen`) esistono solo nella galleria. */
  private fun assertNasUnreachable(nav: NavHostController) {
    for (screen in NasScreen.entries) {
      compose.runOnUiThread {
        assertThrows(IllegalArgumentException::class.java) { nav.navigate(screen.route) }
      }
    }
  }

  @Test
  fun lIntentDiIngressoNonPortaExtraNeDeepLink() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val clean = MainActivity.launcherIntent(app)
    assertThat(clean.action).isEqualTo(Intent.ACTION_MAIN)
    assertThat(clean.categories).containsExactly(Intent.CATEGORY_LAUNCHER)
    assertThat(clean.component?.className).isEqualTo(MainActivity::class.java.name)
    assertThat(clean.extras).isNull()
    assertThat(clean.data).isNull()
    assertThat(clean.flags).isEqualTo(0)
  }

  /** Revisione 2, F13: `singleTask` esportata con `taskAffinity=""` (StrandHogg): l'affinita' non e' quella del pacchetto. */
  @Test
  fun laMainActivityNonHaLAffinitaDelPacchetto() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val info = app.packageManager.getActivityInfo(android.content.ComponentName(app, MainActivity::class.java), 0)
    assertThat(info.taskAffinity).isNotEqualTo(app.packageName)
    assertThat(info.taskAffinity.orEmpty()).isEmpty()
  }

  @Test
  fun inAttesaIlCodiceVieneDalloStatoESiVedeSoloLAttesa() {
    val nav = show(RootDestination.AwaitingApproval("ABCD-EFGH-JKMN-PQRS"))
    assertThat(compose.onAllNodesWithText("ABCD", substring = true, useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
    // Nessuna sovrapposizione e nessuna cattura dello schermo (anche senza QR, che aveva gia' la sua finestra sicura).
    val flags = compose.activity.window.attributes.flags
    assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)
    // Da qui non si arriva altrove: niente galleria, Impostazioni, Diagnostica o onboarding con un intent costruito.
    assertUnreachable(nav, MainRoute, SettingsGraphRoute, SettingsDevicesRoute, DiagnosticsGraphRoute, OnboardingGraphRoute)
    // "Rifai la richiesta" (ON-2) aggiunge la sola sua pagina: il passo corrente e quelli da riprendere restano fuori.
    assertUnreachable(nav, OnboardingCurrentStepRoute, OnboardingResumeRoute(OnboardingStep.VPN))
    assertNasUnreachable(nav)
  }

  @Test
  fun ogniStatoRegistraSoloLeSueDestinazioni() {
    val wiped = show(RootDestination.Wiped)
    assertUnreachable(wiped, MainRoute, AwaitingApprovalRoute, SettingsGraphRoute, OnboardingGraphRoute)
    assertNasUnreachable(wiped)
  }

  @Test
  fun bloccatoNonApreAltro() {
    val blocked = show(RootDestination.Blocked(BlockReason.STORAGE_LOCKED))
    assertUnreachable(blocked, MainRoute, AwaitingApprovalRoute, SettingsGraphRoute, DiagnosticsGraphRoute)
    assertNasUnreachable(blocked)
  }
}
