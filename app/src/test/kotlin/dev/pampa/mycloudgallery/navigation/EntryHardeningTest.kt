package dev.pampa.mycloudgallery.navigation

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.MainActivity
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockGate
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockState
import dev.pampa.mycloudgallery.core.security.lock.AppLockTimeout
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingCurrentStepRoute
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingGraphRoute
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingResumeRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsDevicesRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsGraphRoute
import dev.pampa.mycloudgallery.notify.OpenMemoriesRequests
import dev.pampa.mycloudgallery.notify.ServeOpenMemoriesRequests
import dev.pampa.mycloudgallery.root.RootDestination
import dev.pampa.mycloudgallery.status.GlobalStatus
import kotlinx.coroutines.CompletableDeferred
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

  // --- Fase 9, P10 (D17, C151, E-NT8, E-NT9): un'azione nota, nessun dato, apertura dopo il blocco app ----------------------------------------------

  private val app: Application get() = ApplicationProvider.getApplicationContext()

  /** Un intent ostile: l'azione scelta, con deep link interni di Navigation, dati, categorie, flag e un componente di un'altra app. */
  private fun hostile(action: String?): Intent = Intent().apply {
    this.action = action
    putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(1, 2, 3))
    putExtra("android-support-nav:controller:deepLinkArgs", Bundle().apply { putString("code", "ABCD-EFGH-JKMN-PQRS") })
    putExtra("memoryId", "0123456789abcdefghjkmnpqrs")
    setData(Uri.parse("mycloudgallery://memory/0123456789abcdefghjkmnpqrs"))
    addCategory(Intent.CATEGORY_BROWSABLE)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    setClassName("com.altra.app", "com.altra.app.Qualcosa")
  }

  /** L'azione dei Ricordi e' l'unica azione nota: la `MainActivity` la riconosce e l'intent resta pulito come sempre (niente extra, dati, categorie, flag, componenti altrui). */
  @Test
  fun lAzioneDeiRicordiEUnicaAzioneNotaEdIntentRestaPulito() {
    assertThat(NotifyRules.OPEN_ACTION).isEqualTo("dev.pampa.mycloudgallery.action.OPEN_MEMORIES")
    for (incoming in listOf(Intent(NotifyRules.OPEN_ACTION), hostile(NotifyRules.OPEN_ACTION))) {
      val entry = MainActivity.entryOf(app, incoming)
      assertThat(entry.opensMemories).isTrue()
      // Fase 10 (P4, D16): la richiesta la serve la finestra principale; se l'intent e' arrivato alla seconda, si porta davanti la principale.
      assertThat(entry.needsPrimaryWindow).isTrue()
      val clean = entry.clean
      assertThat(clean.action).isEqualTo(Intent.ACTION_MAIN)
      assertThat(clean.categories).containsExactly(Intent.CATEGORY_LAUNCHER)
      assertThat(clean.component?.className).isEqualTo(MainActivity::class.java.name)
      assertThat(clean.extras).isNull()
      assertThat(clean.data).isNull()
      assertThat(clean.type).isNull()
      assertThat(clean.flags).isEqualTo(0)
    }
  }

  /** Ogni altra azione (o nessuna) non apre niente: ne' un'azione parente (stesso inizio, un'altra coda), ne' le maiuscole, ne' uno spazio, ne' le azioni di sistema. */
  @Test
  fun ogniAltraAzioneNonApreIRicordi() {
    val others = listOf(
      null, Intent.ACTION_MAIN, Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_VIEW + "x", "dev.pampa.mycloudgallery.action.OPEN_MEMORY",
      NotifyRules.OPEN_ACTION + " ", NotifyRules.OPEN_ACTION.lowercase(), NotifyRules.OPEN_ACTION + ".x", "com.altra.app.action.OPEN_MEMORIES", "", NotifyRules.OPEN_ACTION.uppercase(),
    )
    for (action in others) {
      val entry = MainActivity.entryOf(app, hostile(action))
      assertThat(entry.opensMemories).isFalse()
      assertThat(entry.needsPrimaryWindow).isFalse()
      assertThat(entry.clean.extras).isNull()
      assertThat(entry.clean.data).isNull()
    }
    assertThat(MainActivity.entryOf(app, null).opensMemories).isFalse()
    assertThat(MainActivity.entryOf(app, MainActivity.launcherIntent(app)).opensMemories).isFalse()
  }

  /**
   * E-NT9: un'altra app che manda l'azione dei Ricordi (con extra e dati ostili) puo' al piu' chiedere la pagina Ricordi: la richiesta e' un solo evento interno senza contenuto, e
   * la navigazione non apre nient'altro (come per ogni intent d'ingresso: F5).
   */
  @Test
  fun unAltraAppPuoSoloChiedereLaPaginaRicordi() {
    val requests = OpenMemoriesRequests()
    val entry = MainActivity.entryOf(app, hostile(NotifyRules.OPEN_ACTION))
    if (entry.opensMemories) requests.request()
    assertThat(requests.pending.value).isTrue()
    // L'evento non porta niente: un booleano. Due richieste prima di essere servite sono una.
    requests.request()
    assertThat(requests.take()).isTrue()
    assertThat(requests.take()).isFalse()
    val nav = show(RootDestination.AwaitingApproval("ABCD-EFGH-JKMN-PQRS"))
    assertUnreachable(nav, MainRoute, SettingsGraphRoute, DiagnosticsGraphRoute, OnboardingGraphRoute)
  }

  /** Un'autenticazione che finisce quando il test lo decide: lo sblocco vero (BiometricPrompt) e' sostituito da questo. */
  private class GatedAuthenticator(val result: CompletableDeferred<AuthResult> = CompletableDeferred()) : SystemCredentialAuthenticator {
    override fun availability(): AuthAvailability = AuthAvailability.AVAILABLE

    override suspend fun authenticate(text: AuthPromptText): AuthResult = result.await()
  }

  /**
   * D17, E-NT8: con il blocco app attivo il tocco sulla notifica **non apre niente** finche' l'app e' bloccata (il contenuto non e' nemmeno in composizione); allo sblocco la richiesta
   * si serve, **una volta sola**. Il blocco app e' quello vero (`AppLockGate`).
   */
  @Test
  fun laRichiestaSiServeSoloDopoIlBloccoApp() {
    val controller = AppLockController()
    controller.updateSettings(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_1_MINUTE))
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    val requests = OpenMemoriesRequests()
    val authenticator = GatedAuthenticator()
    var opened = 0
    compose.setContent {
      MyCloudGalleryTheme {
        AppLockGate(controller = controller, authenticator = authenticator, lockedContent = { Text("bloccata") }) {
          Text("sbloccata")
          ServeOpenMemoriesRequests(requests) { opened++ }
        }
      }
    }
    // Il tocco arriva con l'app bloccata: la richiesta resta in attesa, non si apre niente, e il contenuto non c'e'.
    requests.request()
    compose.waitForIdle()
    assertThat(opened).isEqualTo(0)
    assertThat(requests.pending.value).isTrue()
    assertThat(compose.onAllNodesWithText("sbloccata").fetchSemanticsNodes()).isEmpty()
    // Lo sblocco riesce: ora la richiesta si serve, una volta.
    authenticator.result.complete(AuthResult.Success)
    compose.waitForIdle()
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    assertThat(opened).isEqualTo(1)
    assertThat(requests.pending.value).isFalse()
    // Una richiesta nuova a app sbloccata si serve subito, una volta per richiesta.
    requests.request()
    compose.waitForIdle()
    assertThat(opened).isEqualTo(2)
    compose.waitForIdle()
    assertThat(opened).isEqualTo(2)
  }

  /** Senza il blocco app la richiesta si serve subito, una volta sola. */
  @Test
  fun senzaBloccoLaRichiestaSiServeSubito() {
    val controller = AppLockController()
    controller.updateSettings(AppLockSettings())
    val requests = OpenMemoriesRequests().also { it.request() }
    var opened = 0
    compose.setContent {
      MyCloudGalleryTheme {
        AppLockGate(controller = controller, authenticator = GatedAuthenticator(), lockedContent = { Text("bloccata") }) {
          ServeOpenMemoriesRequests(requests) { opened++ }
        }
      }
    }
    compose.waitForIdle()
    assertThat(opened).isEqualTo(1)
    compose.waitForIdle()
    assertThat(opened).isEqualTo(1)
  }
}
