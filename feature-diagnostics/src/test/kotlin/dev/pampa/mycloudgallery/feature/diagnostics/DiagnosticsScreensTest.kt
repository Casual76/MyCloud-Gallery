package dev.pampa.mycloudgallery.feature.diagnostics

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestOutcome
import dev.pampa.mycloudgallery.feature.diagnostics.export.DiagnosticsExporter
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagActionPolicy
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsExportScreen
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsSectionScreen
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
 * Le schermate della Diagnostica su Robolectric: cosa si legge, cosa si tocca, cosa NON c'e' (il tasto di
 * un'azione che nessuno sa eseguire). Il telefono finto e' alto 2400 dp cosi' la lista compone tutte le righe.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h2400dp-xhdpi")
class DiagnosticsScreensTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** L'activity vuota della regola di Compose non e' nel manifest di un modulo libreria: la si registra a mano. */
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

  private val app: Application = ApplicationProvider.getApplicationContext()

  private fun string(id: Int, vararg args: Any) = app.getString(id, *args)

  private fun show(content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) { content() }
    }
    composeRule.waitForIdle()
  }

  // --- La pagina d'ingresso --------------------------------------------------------------------------------------

  @Test
  fun laPanoramicaMostraLeNoveSezioniEIlRiepilogoDegliAvvisi() {
    val state = ScreenFixtures.attentionState()
    show { DiagnosticsScreen(state = state, onBack = {}, onRefresh = {}) }

    DiagSection.entries.forEach { section ->
      composeRule.onNodeWithText(string(titleOf(section))).assertIsDisplayed()
    }
    composeRule.onNodeWithText(string(R.string.diagnostics_export_row_title)).assertIsDisplayed()
    assertThat(state.attentionCount).isGreaterThan(5)
  }

  @Test
  fun toccareUnaSezioneLaApre() {
    val opened = mutableListOf<DiagSection>()
    show { DiagnosticsScreen(ScreenFixtures.attentionState(), onBack = {}, onRefresh = {}, onOpenSection = { opened += it }) }

    composeRule.onNodeWithText(string(R.string.diagnostics_section_android)).performClick()
    composeRule.onNodeWithText(string(R.string.diagnostics_section_connection_test)).performClick()

    assertThat(opened).containsExactly(DiagSection.ANDROID_WARNINGS, DiagSection.CONNECTION_TEST).inOrder()
  }

  @Test
  fun toccareEsportaApreLEsportazione() {
    var exported = 0
    show { DiagnosticsScreen(ScreenFixtures.healthyState(), onBack = {}, onRefresh = {}, onExport = { exported++ }) }

    composeRule.onNodeWithText(string(R.string.diagnostics_export_row_title)).performClick()

    assertThat(exported).isEqualTo(1)
  }

  @Test
  fun inUnaGiornataBuonaIlRiepilogoDiceTuttoAPosto() {
    show { DiagnosticsScreen(ScreenFixtures.healthyState(), onBack = {}, onRefresh = {}) }

    composeRule.onNodeWithText(string(R.string.diagnostics_summary_ok_title)).assertIsDisplayed()
  }

  @Test
  fun durantelaPrimaLetturaIlRiepilogoDiceCheStaControllando() {
    show { DiagnosticsScreen(ScreenFixtures.loadingState(), onBack = {}, onRefresh = {}) }

    composeRule.onNodeWithText(string(R.string.diagnostics_summary_loading_title)).assertIsDisplayed()
  }

  // --- Una sezione --------------------------------------------------------------------------------------------------

  private fun androidSection(actions: DiagActionPolicy? = null, onAction: (DiagAction) -> Unit = {}) {
    val state = ScreenFixtures.attentionState()
    show {
      DiagnosticsSectionScreen(
        section = DiagSection.ANDROID_WARNINGS,
        state = state,
        onBack = {},
        onRefresh = {},
        onRunTest = {},
        onAction = onAction,
        actions = actions ?: DiagActionPolicy(wireGuardAvailable = true),
      )
    }
  }

  @Test
  fun ogniAvvisoHaIlSuoCosaFareEIlTastoDellAzione() {
    val done = mutableListOf<DiagAction>()
    androidSection(onAction = { done += it })

    // La batteria ottimizzata: il titolo, il «cosa fare» e il tasto.
    composeRule.onNodeWithText(string(R.string.diag_and_battery_optimized_title)).assertIsDisplayed()
    composeRule.onNodeWithText(string(R.string.diag_and_battery_optimized_guide)).assertIsDisplayed()
    composeRule.onNodeWithText(string(R.string.diagnostics_action_open_battery)).performClick()

    assertThat(done).containsExactly(DiagAction.OPEN_BATTERY_SETTINGS)
  }

  @Test
  fun unAvvisoHaIlSuoCodiceInPiccolo() {
    androidSection()

    composeRule.onAllNodesWithText(DiagCodes.AND_BATTERY_OPTIMIZED.id, substring = true).fetchSemanticsNodes().also {
      assertThat(it).isNotEmpty()
    }
  }

  @Test
  fun unAzioneChePasseDaAppNonHaTastoSeAppNonLaEsegue() {
    // L'elenco dei dispositivi lo apre :app: senza dichiararlo, il tasto non c'e'.
    val state = ScreenFixtures.attentionState()
    show {
      DiagnosticsSectionScreen(DiagSection.TRUST, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {}, actions = DiagActionPolicy())
    }
    composeRule.onAllNodesWithText(string(R.string.diagnostics_action_open_devices)).fetchSemanticsNodes().also {
      assertThat(it).isEmpty()
    }
  }

  @Test
  fun unAzioneDiAppDichiarataHaIlTasto() {
    val done = mutableListOf<DiagAction>()
    val state = ScreenFixtures.attentionState()
    show {
      DiagnosticsSectionScreen(
        DiagSection.TRUST, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = { done += it },
        actions = DiagActionPolicy(appActions = ScreenFixtures.appActions),
      )
    }

    composeRule.onAllNodesWithText(string(R.string.diagnostics_action_open_devices))[0].performClick()

    assertThat(done).containsExactly(DiagAction.OPEN_DEVICES)
  }

  @Test
  fun ilTastoWireGuardNonCeSeWireGuardNonEInstallato() {
    val state = ScreenFixtures.attentionState()
    show {
      DiagnosticsSectionScreen(
        DiagSection.NETWORK_AND_NAS, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {},
        actions = DiagActionPolicy(wireGuardAvailable = false),
      )
    }

    // L'avviso sulla VPN estranea c'e', ma senza «Apri WireGuard».
    composeRule.onNodeWithText(string(R.string.diag_net_foreign_vpn_title)).assertIsDisplayed()
    assertThat(composeRule.onAllNodesWithText(string(R.string.diagnostics_action_open_wireguard)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilTastoWireGuardCeSeWireGuardEInstallato() {
    val state = ScreenFixtures.attentionState()
    show {
      DiagnosticsSectionScreen(
        DiagSection.NETWORK_AND_NAS, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {},
        actions = DiagActionPolicy(wireGuardAvailable = true),
      )
    }

    assertThat(composeRule.onAllNodesWithText(string(R.string.diagnostics_action_open_wireguard)).fetchSemanticsNodes()).isNotEmpty()
  }

  @Test
  fun unaSezioneVuotaDiceCheNonCeAncoraNiente() {
    show {
      DiagnosticsSectionScreen(
        DiagSection.STORAGE, ScreenFixtures.loadingState(), onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {},
      )
    }

    composeRule.onNodeWithText(string(R.string.diagnostics_no_items)).assertIsDisplayed()
  }

  // --- Il test di connessione ------------------------------------------------------------------------------------

  @Test
  fun ilTestParteSoloToccandoIlPulsante() {
    var runs = 0
    show {
      DiagnosticsSectionScreen(
        DiagSection.CONNECTION_TEST, ScreenFixtures.loadingState(), onBack = {}, onRefresh = {}, onRunTest = { runs++ }, onAction = {},
      )
    }
    assertThat(runs).isEqualTo(0)

    composeRule.onNodeWithText(string(R.string.diagnostics_test_run)).performClick()

    assertThat(runs).isEqualTo(1)
    composeRule.onNodeWithText(string(R.string.diagnostics_test_hint)).assertIsDisplayed()
  }

  @Test
  fun unTestInCorsoMostraIlPulsanteOccupato() {
    val state = ScreenFixtures.loadingState().copy(connectionTest = ConnectionTestUiState.Running)
    show {
      DiagnosticsSectionScreen(DiagSection.CONNECTION_TEST, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {})
    }

    composeRule.onNodeWithText(string(R.string.diagnostics_test_running)).assertIsDisplayed()
  }

  @Test
  fun lEsitoDelTestMostraLeFasiConIlMessaggioDellaPrimaFallita() {
    val state = ScreenFixtures.attentionState(testOutcome = ScreenFixtures.testOutcomeFailed())
    show {
      DiagnosticsSectionScreen(DiagSection.CONNECTION_TEST, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {})
    }

    composeRule.onNodeWithText(string(R.string.diagnostics_stage_tcp)).assertIsDisplayed()
    composeRule.onNodeWithText(string(R.string.diagnostics_stage_share)).assertIsDisplayed()
    // Il messaggio di §3.4, col nome della cartella: sullo schermo del proprio telefono va bene.
    composeRule.onNodeWithText(string(R.string.diagnostics_nas_share_missing, "test-comune")).assertIsDisplayed()
    // Una fase caduta e' il problema: l'avviso «autenticazione non provata» non le si affianca.
    assertThat(composeRule.onAllNodesWithText(string(R.string.diagnostics_test_breaker_title)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun conLInterruttoreApertoIlTestDiceCheNonHaAutenticato() {
    val outcome = ConnectionTestOutcome.Done(
      rows = emptyList(),
      negotiation = null,
      passed = false,
      authSkipped = true,
      breakerOpen = true,
    )
    val state = ScreenFixtures.loadingState().copy(connectionTest = ConnectionTestUiState.Finished(outcome))
    show {
      DiagnosticsSectionScreen(DiagSection.CONNECTION_TEST, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {})
    }

    composeRule.onNodeWithText(string(R.string.diagnostics_test_breaker_title)).assertIsDisplayed()
  }

  @Test
  fun senzaUnNasLaPaginaDelTestLoDice() {
    val state = ScreenFixtures.loadingState().copy(
      connectionTest = ConnectionTestUiState.Finished(ConnectionTestOutcome.NotConfigured),
    )
    show {
      DiagnosticsSectionScreen(DiagSection.CONNECTION_TEST, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {})
    }

    composeRule.onNodeWithText(string(R.string.diagnostics_test_not_configured_title)).assertIsDisplayed()
  }

  // --- L'anteprima dell'esportazione -----------------------------------------------------------------------------

  private fun document() = DiagnosticsExporter.render(
    DiagnosticsSnapshot(mapOf(DiagSection.NETWORK_AND_NAS to ScreenFixtures.networkItems()), recentEvents = 0),
  )

  @Test
  fun lAnteprimaMostraIlFileEIlTastoLoSalva() {
    var saved = 0
    val document = document()
    show {
      DiagnosticsExportScreen(document = document, refused = false, saveResult = null, onBack = {}, onSave = { saved++ })
    }

    composeRule.onNodeWithText(string(R.string.diagnostics_export_contents_title)).assertIsDisplayed()
    composeRule.onNodeWithText("NET_FOREIGN_VPN", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText(string(R.string.diagnostics_export_save)).performClick()

    assertThat(saved).isEqualTo(1)
    // Quel che si legge e' il file: l'anteprima non nasconde righe.
    composeRule.onNodeWithText("\"format\": \"mycloudgallery-diagnostics\"", substring = true).assertIsDisplayed()
  }

  @Test
  fun unSalvataggioRiuscitoOFallitoSiDice() {
    val document = document()
    show { DiagnosticsExportScreen(document, refused = false, saveResult = SaveResult.SAVED, onBack = {}, onSave = {}) }
    composeRule.onNodeWithText(string(R.string.diagnostics_export_saved_title)).assertIsDisplayed()
  }

  @Test
  fun unSalvataggioFallitoSiDice() {
    val document = document()
    show { DiagnosticsExportScreen(document, refused = false, saveResult = SaveResult.FAILED, onBack = {}, onSave = {}) }
    composeRule.onNodeWithText(string(R.string.diagnostics_export_failed_title)).assertIsDisplayed()
  }

  @Test
  fun unaEsportazioneRifiutataNonMostraNeTastoNeFile() {
    show { DiagnosticsExportScreen(document = null, refused = true, saveResult = null, onBack = {}, onSave = {}) }

    composeRule.onNodeWithText(string(R.string.diagnostics_export_refused_title)).assertIsDisplayed()
    assertThat(composeRule.onAllNodesWithText(string(R.string.diagnostics_export_save)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun unaDiagnosticaGrandeSiSpezzaInPiuGruppiSenzaPerdereRighe() {
    val items = (1..400).map { item(DiagCodes.NET_PLACE, severity = DiagSeverity.INFO) }
    val document = DiagnosticsExporter.render(DiagnosticsSnapshot(mapOf(DiagSection.NETWORK_AND_NAS to items), recentEvents = 0))

    val chunks = document.lines.chunked(dev.pampa.mycloudgallery.feature.diagnostics.ui.LINES_PER_GROUP)

    assertThat(chunks.size).isGreaterThan(10)
    assertThat(chunks.flatten()).isEqualTo(document.lines)
  }

  // --- Aiuti ---------------------------------------------------------------------------------------------------------

  private fun titleOf(section: DiagSection): Int = when (section) {
    DiagSection.NETWORK_AND_NAS -> R.string.diagnostics_section_network
    DiagSection.CONNECTION_TEST -> R.string.diagnostics_section_connection_test
    DiagSection.ACTIVITIES -> R.string.diagnostics_section_activities
    DiagSection.RECENT_EVENTS -> R.string.diagnostics_section_recent
    DiagSection.PHONE_LIBRARY -> R.string.diagnostics_section_phone_library
    DiagSection.STORAGE -> R.string.diagnostics_section_storage
    DiagSection.MONTHLY_TRAFFIC -> R.string.diagnostics_section_traffic
    DiagSection.ANDROID_WARNINGS -> R.string.diagnostics_section_android
    DiagSection.TRUST -> R.string.diagnostics_section_trust
    DiagSection.PROFILES -> R.string.diagnostics_section_profiles
  }
}
