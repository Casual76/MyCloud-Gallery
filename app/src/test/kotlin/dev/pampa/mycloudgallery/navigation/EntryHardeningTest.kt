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
import com.google.common.truth.Truth.assertWithMessage
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
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapUri
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
import dev.pampa.mycloudgallery.widget.OpenWidgetRequests
import dev.pampa.mycloudgallery.widget.ServeOpenWidgetRequests
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

  // --- Fase 10, P3 (D9, C17, C433, E-W7): il tocco sul widget, un'azione fissa e due numeri, apertura dopo il blocco app -------------------------------------------------

  /** Un intent costruito da un'altra app: l'azione e l'URI scelti, con in piu' extra, categorie, flag e un componente altrui. */
  private fun widgetIntent(action: String?, uri: String?): Intent = hostile(action).apply {
    setData(if (uri == null) null else Uri.parse(uri))
    putExtra("appWidgetId", 7)
    putExtra("mediaId", "0123456789abcdefghjkmnpqrs")
  }

  private fun assertClean(clean: Intent) {
    assertThat(clean.action).isEqualTo(Intent.ACTION_MAIN)
    assertThat(clean.categories).containsExactly(Intent.CATEGORY_LAUNCHER)
    assertThat(clean.component?.className).isEqualTo(MainActivity::class.java.name)
    assertThat(clean.extras).isNull()
    assertThat(clean.data).isNull()
    assertThat(clean.type).isNull()
    assertThat(clean.flags).isEqualTo(0)
  }

  /** L'azione del widget e l'URI `mcg-widget:<id>/<cella>`: la `MainActivity` legge i soli due numeri e l'intent resta pulito, anche se l'altra app ci ha messo di tutto. */
  @Test
  fun ilToccoSulWidgetPortaSoloIDueNumeriEIntentRestaPulito() {
    assertThat(WidgetTapUri.ACTION).isEqualTo("dev.pampa.mycloudgallery.action.OPEN_WIDGET")
    val real = Intent(WidgetTapUri.ACTION).setData(Uri.parse(WidgetTapUri.format(WidgetTapRequest(12, 3))))
    for (incoming in listOf(real, widgetIntent(WidgetTapUri.ACTION, "mcg-widget:12/3"))) {
      val entry = MainActivity.entryOf(app, incoming)
      assertThat(entry.widgetTap).isEqualTo(WidgetTapRequest(12, 3))
      assertThat(entry.opensMemories).isFalse()
      // D16: lo serve la finestra principale; se l'intent e' arrivato alla seconda, si porta davanti la principale.
      assertThat(entry.needsPrimaryWindow).isTrue()
      assertClean(entry.clean)
    }
    // Gli estremi che il widget puo' mandare: la prima e l'ultima cella, il primo e l'ultimo id.
    for ((id, cell) in listOf(1 to 0, 1 to 6, Int.MAX_VALUE to 0, Int.MAX_VALUE to 6)) {
      val tap = MainActivity.entryOf(app, Intent(WidgetTapUri.ACTION).setData(Uri.parse("mcg-widget:$id/$cell"))).widgetTap
      assertThat(tap).isEqualTo(WidgetTapRequest(id, cell))
    }
  }

  /**
   * Un intent inventato: l'azione del widget con un URI che non e' **esattamente** `mcg-widget:<int>/<int>` nei limiti non porta nessuna richiesta (l'app si apre come dall'icona:
   * al piu' la Libreria). Numeri fuori dai limiti, zeri davanti, segni, parti in piu', parametri, frammenti, un altro schema, un URI gerarchico, spazi, maiuscole, niente URI.
   */
  @Test
  fun unIntentInventatoDelWidgetNonPortaUnTocco() {
    val forged = listOf(
      null, "", "mcg-widget:", "mcg-widget:1", "mcg-widget:1/", "mcg-widget:/1", "mcg-widget:0/1", "mcg-widget:1/7", "mcg-widget:1/-1", "mcg-widget:-1/2", "mcg-widget:+1/2",
      "mcg-widget:01/2", "mcg-widget:1/02", "mcg-widget:1/2/3", "mcg-widget:1/2?x=1", "mcg-widget:1/2#f", "mcg-widget:1/2 ", "mcg-widget: 1/2", "mcg-widget:2147483648/0",
      "mcg-widget:99999999999999999999/0", "mcg-widget://1/2", "mcg-widget:1/2,3", "mcg-widget:1.0/2", "mcg-widget:0x1/2", "MCG-WIDGET:1/2", "mcg-widget1/2",
      "mycloudgallery://widget/1/2", "https://evil.example/1/2", "content://dev.pampa.mycloudgallery.docs/document/12", "file:///data/data/x",
    )
    for (uri in forged) {
      val entry = MainActivity.entryOf(app, widgetIntent(WidgetTapUri.ACTION, uri))
      assertWithMessage("un URI inventato ($uri) non deve portare un tocco").that(entry.widgetTap).isNull()
      assertThat(entry.needsPrimaryWindow).isFalse()
      assertClean(entry.clean)
    }
  }

  /** Il tocco si riconosce solo con l'azione del widget, per uguaglianza esatta: nessun'altra azione (nemmeno parente o quella dei Ricordi) lo porta, qualunque URI abbia. */
  @Test
  fun soloLAzioneDelWidgetPortaUnTocco() {
    val others = listOf(
      null, Intent.ACTION_MAIN, Intent.ACTION_VIEW, Intent.ACTION_SEND, NotifyRules.OPEN_ACTION, "dev.pampa.mycloudgallery.action.OPEN_WIDGETS", "dev.pampa.mycloudgallery.action.OPEN_WIDGE",
      WidgetTapUri.ACTION + " ", WidgetTapUri.ACTION.lowercase(), WidgetTapUri.ACTION + ".x", "com.altra.app.action.OPEN_WIDGET", "", WidgetTapUri.ACTION.uppercase(),
    )
    for (action in others) {
      val entry = MainActivity.entryOf(app, widgetIntent(action, "mcg-widget:12/3"))
      assertWithMessage("l'azione $action non deve portare un tocco").that(entry.widgetTap).isNull()
      assertClean(entry.clean)
    }
    assertThat(MainActivity.entryOf(app, null).widgetTap).isNull()
    assertThat(MainActivity.entryOf(app, MainActivity.launcherIntent(app)).widgetTap).isNull()
    // L'azione dei Ricordi con un URI del widget apre i Ricordi e non porta il tocco: le due richieste non si mescolano.
    val memories = MainActivity.entryOf(app, widgetIntent(NotifyRules.OPEN_ACTION, "mcg-widget:12/3"))
    assertThat(memories.opensMemories).isTrue()
    assertThat(memories.widgetTap).isNull()
  }

  /** L'evento interno porta i due numeri e basta; un tocco nuovo sostituisce quello ancora in attesa; si prende una volta sola; da qui non si apre altro. */
  @Test
  fun ilToccoEUnEventoInternoConISoliDueNumeri() {
    val requests = OpenWidgetRequests()
    assertThat(requests.pending.value).isNull()
    assertThat(requests.take()).isNull()
    requests.request(WidgetTapRequest(12, 3))
    requests.request(WidgetTapRequest(12, 4))
    assertThat(requests.pending.value).isEqualTo(WidgetTapRequest(12, 4))
    assertThat(requests.take()).isEqualTo(WidgetTapRequest(12, 4))
    assertThat(requests.take()).isNull()
    // Un'altra app che manda i numeri di un widget altrui ottiene solo questo: un evento con due interi, da validare sul registro.
    val entry = MainActivity.entryOf(app, widgetIntent(WidgetTapUri.ACTION, "mcg-widget:999/0"))
    entry.widgetTap?.let(requests::request)
    assertThat(requests.take()).isEqualTo(WidgetTapRequest(999, 0))
    val nav = show(RootDestination.AwaitingApproval("ABCD-EFGH-JKMN-PQRS"))
    assertUnreachable(nav, MainRoute, SettingsGraphRoute, DiagnosticsGraphRoute, OnboardingGraphRoute)
  }

  /** E-W7: con il blocco app attivo il tocco sul widget non apre niente finche' l'app e' bloccata; allo sblocco si serve, una volta sola. */
  @Test
  fun ilToccoSulWidgetSiServeSoloDopoIlBloccoApp() {
    val controller = AppLockController()
    controller.updateSettings(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_1_MINUTE))
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    val requests = OpenWidgetRequests()
    val authenticator = GatedAuthenticator()
    val opened = mutableListOf<WidgetTapRequest>()
    compose.setContent {
      MyCloudGalleryTheme {
        AppLockGate(controller = controller, authenticator = authenticator, lockedContent = { Text("bloccata") }) {
          Text("sbloccata")
          ServeOpenWidgetRequests(requests) { opened += it }
        }
      }
    }
    requests.request(WidgetTapRequest(12, 3))
    compose.waitForIdle()
    assertThat(opened).isEmpty()
    assertThat(requests.pending.value).isEqualTo(WidgetTapRequest(12, 3))
    assertThat(compose.onAllNodesWithText("sbloccata").fetchSemanticsNodes()).isEmpty()
    authenticator.result.complete(AuthResult.Success)
    compose.waitForIdle()
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    assertThat(opened).containsExactly(WidgetTapRequest(12, 3))
    assertThat(requests.pending.value).isNull()
    compose.waitForIdle()
    assertThat(opened).hasSize(1)
  }
}
