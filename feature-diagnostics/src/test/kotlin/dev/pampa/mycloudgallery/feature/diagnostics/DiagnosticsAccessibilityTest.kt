package dev.pampa.mycloudgallery.feature.diagnostics

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagActionPolicy
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsExportScreen
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsSectionScreen
import org.junit.Rule
import com.github.takahirom.roborazzi.checkRoboAccessibility
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
 * Fase 10, P8 (D23, D25-D27, D39, §2.7 "Backup, NAS, Diagnostica", D28): la Diagnostica letta come la legge TalkBack. La panoramica, ogni
 * sezione, il test di connessione e l'anteprima dell'esportazione, a testo 1,0 e 2,0: nome e ruolo per ogni elemento attivo, bersagli da 48 dp
 * misurati, nessun id o nome di file, testo non tagliato; i numeri sono letti come numeri.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
internal class DiagnosticsAccessibilityTest {

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

  /** La scala del testo di adesso: si cambia senza rimostrare la scena (`setContent` si puo' chiamare una volta per prova). */
  private val scale = mutableStateOf(1f)

  private fun show(content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) { content() }
      }
    }
    composeRule.waitForIdle()
  }

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun config() = A11yConfig(density = composeRule.density)

  private fun section(
    section: DiagSection,
    state: DiagnosticsUiState = ScreenFixtures.attentionState(),
  ): @Composable () -> Unit = {
    DiagnosticsSectionScreen(
      section, state, onBack = {}, onRefresh = {}, onRunTest = {}, onAction = {},
      actions = DiagActionPolicy(appActions = ScreenFixtures.appActions, wireGuardAvailable = true),
    )
  }

  /** Mostra [content] e controlla le regole a testo 1,0 e poi a 2,0 sulla stessa scena. */
  private fun clean(name: String, content: @Composable () -> Unit) {
    show(content)
    for (value in listOf(1f, 2f)) {
      scale.value = value
      composeRule.waitForIdle()
      val found = A11yAssertions.check(root(), config())
      assertWithMessage("$name a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  // --- La panoramica ---------------------------------------------------------------------------------------------------------

  @Test fun panoramica() = clean("panoramica") { DiagnosticsScreen(ScreenFixtures.attentionState(), onBack = {}, onRefresh = {}) }

  @Test fun panoramicaTuttoAPosto() = clean("tutto a posto") { DiagnosticsScreen(ScreenFixtures.healthyState(), onBack = {}, onRefresh = {}) }

  @Test fun panoramicaInCaricamento() = clean("in caricamento") { DiagnosticsScreen(ScreenFixtures.loadingState(), onBack = {}, onRefresh = {}) }

  // --- Le sezioni -----------------------------------------------------------------------------------------------------------

  @Test fun reteENas() = clean("rete e NAS", section(DiagSection.NETWORK_AND_NAS))

  @Test fun attivita() = clean("attivita", section(DiagSection.ACTIVITIES))

  @Test fun ultimiEsiti() = clean("ultimi esiti", section(DiagSection.RECENT_EVENTS))

  @Test fun spazio() = clean("spazio", section(DiagSection.STORAGE))

  @Test fun consumi() = clean("consumi", section(DiagSection.MONTHLY_TRAFFIC))

  @Test fun avvisiDiAndroid() = clean("avvisi di Android", section(DiagSection.ANDROID_WARNINGS))

  @Test fun fiducia() = clean("fiducia", section(DiagSection.TRUST))

  @Test fun profili() = clean("profili", section(DiagSection.PROFILES))

  @Test fun testDiConnessioneIniziale() = clean("test iniziale", section(DiagSection.CONNECTION_TEST, ScreenFixtures.healthyState()))

  @Test
  fun testDiConnessioneConEsito() =
    clean("test con esito", section(DiagSection.CONNECTION_TEST, ScreenFixtures.attentionState(testOutcome = ScreenFixtures.testOutcomeFailed())))

  // --- L'esportazione ---------------------------------------------------------------------------------------------------------

  @Test
  fun anteprimaDellEsportazione() = clean("anteprima dell'esportazione") {
    DiagnosticsExportScreen(ScreenFixtures.attentionState().export, refused = false, saveResult = null, onBack = {}, onSave = {})
  }

  @Test
  fun esportazioneBloccata() = clean("esportazione bloccata") {
    DiagnosticsExportScreen(document = null, refused = true, saveResult = null, onBack = {}, onSave = {})
  }

  // --- La semantica attesa del 2.7 ---------------------------------------------------------------------------------------------

  @Test
  fun leSezioniHannoIntestazioni() {
    show { DiagnosticsScreen(ScreenFixtures.attentionState(), onBack = {}, onRefresh = {}) }
    assertThat(A11yAssertions.headingNames(root())).isNotEmpty()
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfPanoramica() {
    show { DiagnosticsScreen(ScreenFixtures.attentionState(), onBack = {}, onRefresh = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfSezione() {
    show(section(DiagSection.NETWORK_AND_NAS))
    composeRule.onRoot().checkRoboAccessibility()
  }
}

