package dev.pampa.mycloudgallery.integration

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
import dev.pampa.mycloudgallery.core.common.diagnostics.NoDiagnosticsLog
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsScreen
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsUiState
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsAggregator
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsSectionScreen
import dev.pampa.mycloudgallery.feature.onboarding.join.AwaitingApprovalScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.main.MainScaffold
import dev.pampa.mycloudgallery.root.WipedScreen
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.work.TrustSyncRun
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot dei **flussi integrati** (P9): ogni schermata e' disegnata con lo stato che il grafo vero (`AppGraph`)
 * produce sopra il NAS simulato e la rete finta, non con stati scritti a mano. Libreria con l'indicatore globale (NAS
 * collegato, non raggiungibile, password da reinserire), attesa dell'approvazione con il codice e il QR veri, dispositivo
 * azzerato, Impostazioni dell'admin con una richiesta in attesa, Diagnostica con i contributori di rete e fiducia.
 *
 * `gradlew :app:recordRoborazziDebug --tests "*P9FlowsScreenshot*"` scrive i PNG in `docs/screenshots/2-P9/` (in
 * locale, fuori dal repository); nei normali `testDebugUnitTest` si verifica solo che le schermate compongano.
 */
internal abstract class P9FlowsScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()
  val temp = TemporaryFolder()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(temp).around(RegisterComponentActivity()).around(composeRule)

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

  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()

  private fun newDevice(name: String): TestDevice = TestDevice(app, File(temp.root, name), TestPlatform(time, backend)).also { devices += it }

  @After
  fun closeDevices() = devices.forEach { it.close() }

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) { content() }
    }
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device.png")
  }

  private fun statusOf(graph: AppGraph): GlobalStatus = GlobalStatus.from(
    graph.environment.snapshot.value,
    graph.session.state.value,
    graph.reachability.lastResult.value,
    graph.breaker.state.value is BreakerState.Open,
  )

  private fun admin(): TestDevice = newDevice("telefono").also { phone ->
    runBlocking {
      phone.graph.start()
      phone.graph.foundFamily()
    }
  }

  @Test
  fun libreriaInCasaConIlNasCollegato() {
    val phone = admin()
    val status = statusOf(phone.graph)
    shoot("01-libreria-nas-collegato") {
      MainScaffold(status, GalleryTab.Library, onSelectTab = {}, onStatusClick = {}, onOpenSettings = {})
    }
  }

  @Test
  fun libreriaConIlNasNonRaggiungibile() {
    val phone = admin()
    val status = runBlocking {
      phone.graph.session.invalidate(InvalidateReason.USER_REQUEST)
      phone.platform.probe.result = ProbeResult.TimedOut
      phone.graph.statusMonitor.probeNow()
      statusOf(phone.graph)
    }
    shoot("02-libreria-nas-non-raggiungibile") {
      MainScaffold(status, GalleryTab.Library, onSelectTab = {}, onStatusClick = {}, onOpenSettings = {})
    }
  }

  @Test
  fun libreriaQuandoLaPasswordDelNasECambiata() {
    val phone = admin()
    val status = runBlocking {
      // La password cambiata dalla dashboard del NAS (F5): un solo tentativo, poi "Serve la password" (E-R8).
      backend.changePassword(TestNas.ADMIN_USER, "una-password-nuova-1")
      phone.graph.session.invalidate(InvalidateReason.USER_REQUEST)
      phone.graph.registry.refresh(Purpose.INTERACTIVE)
      phone.graph.breaker.state.await { it is BreakerState.Open }
      statusOf(phone.graph)
    }
    shoot("03-libreria-serve-la-password") {
      MainScaffold(status, GalleryTab.Library, onSelectTab = {}, onStatusClick = {}, onOpenSettings = {})
    }
  }

  @Test
  fun attesaDellApprovazioneConCodiceEQrVeri() {
    val phone = admin()
    val tablet = newDevice("tablet")
    val awaiting = runBlocking {
      val invite = phone.graph.inviteMember()
      tablet.graph.start()
      tablet.graph.joinWith(invite)
    }
    val qr = tablet.graph.deviceKeyQr()
    shoot("04-attesa-approvazione") { AwaitingApprovalScreen(code = awaiting.code, deviceKeyQr = qr) }
  }

  @Test
  fun impostazioniDellAdminConUnaRichiestaInAttesa() {
    val phone = admin()
    val tablet = newDevice("tablet")
    val state = runBlocking {
      val invite = phone.graph.inviteMember()
      tablet.graph.start()
      tablet.graph.joinWith(invite)
      phone.graph.registry.refresh(Purpose.INTERACTIVE)
      SettingsUiState(
        trust = phone.graph.registry.status.value,
        deviceCount = phone.graph.registry.devices.value.size,
        pendingRequests = phone.graph.registry.pendingRequests.value.size,
        pendingSteps = phone.graph.coordinator.progress.value.pending,
      )
    }
    shoot("05-impostazioni-richiesta") {
      SettingsScreen(state = state, onBack = {}, openDiagnostics = {}, resumeStep = {})
    }
  }

  @Test
  fun dispositivoAzzeratoDopoLaSegnalazione() {
    val phone = admin()
    val tablet = newDevice("tablet")
    runBlocking {
      val invite = phone.graph.inviteMember()
      tablet.graph.start()
      val awaiting = tablet.graph.joinWith(invite)
      phone.graph.registry.refresh(Purpose.INTERACTIVE)
      val request = phone.graph.registry.pendingRequests.value.single()
      phone.graph.registry.approve(request.id, dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof.TypedCode(awaiting.code))
      val id = tablet.graph.engine.myDeviceId()!!
      phone.graph.registry.refresh(Purpose.INTERACTIVE)
      phone.graph.registry.changeStatus(id, DeviceStatus.LOST)
      TrustSyncRun(tablet.graph, notifications = null, notified = null).execute()
      tablet.graph.awaitSetup { it is SetupState.Wiped }
    }
    shoot("06-dispositivo-azzerato") { WipedScreen(onReconfigure = {}) }
  }

  private fun diagnosticsOf(graph: AppGraph): DiagnosticsUiState = runBlocking {
    val snapshot = DiagnosticsAggregator(listOf(graph.networkDiagnostics, graph.trustDiagnostics), NoDiagnosticsLog).collect()
    DiagnosticsUiState(loading = false, sections = snapshot.sections, recentEvents = snapshot.recentEvents)
  }

  @Test
  fun diagnosticaConIContributoriVeri() {
    val phone = admin()
    val state = diagnosticsOf(phone.graph)
    shoot("07-diagnostica") { DiagnosticsScreen(state = state, onBack = {}, onRefresh = {}) }
  }

  @Test
  fun diagnosticaReteENas() {
    val phone = admin()
    val state = diagnosticsOf(phone.graph)
    shoot("08-diagnostica-rete-nas") {
      DiagnosticsSectionScreen(DiagSection.NETWORK_AND_NAS, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {})
    }
  }

  @Test
  fun diagnosticaFiducia() {
    val phone = admin()
    val state = diagnosticsOf(phone.graph)
    shoot("09-diagnostica-fiducia") {
      DiagnosticsSectionScreen(DiagSection.TRUST, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {})
    }
  }

  companion object {
    const val DIR = "../docs/screenshots/2-P9"

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
internal class P9FlowsPhoneScreenshotTest(dark: Boolean) : P9FlowsScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
internal class P9FlowsTabletScreenshotTest(dark: Boolean) : P9FlowsScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false))
  }
}
