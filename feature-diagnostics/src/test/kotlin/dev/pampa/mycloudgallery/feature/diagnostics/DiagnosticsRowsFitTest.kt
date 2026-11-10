package dev.pampa.mycloudgallery.feature.diagnostics

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagActionPolicy
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsSectionScreen
import dev.pampa.mycloudgallery.feature.diagnostics.ui.titleRes
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
 * GF-21 (review della beta): la Diagnostica non era passata dal giro a 360 dp. La pillola di stato ("2 da guardare", "Problema") stava nel `badge` della riga,
 * che prende la larghezza per primo: nella panoramica col testo grande «Attività» diventava «Attivit/à» e «Rete e NAS» «Rete e / NAS», e negli «Avvisi di Android» il
 * testo restava in una colonna di ~145 dp (dieci righe per voce). Adesso la pillola e' in `labels` (come K4 per Impostazioni, Persone e Cerca: `BackupEntryFitTest`).
 *
 * Il telefono e' a 360 dp (il Galaxy S25 dell'utente), alto abbastanza da comporre tutta la lista.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h2400dp-xhdpi")
class DiagnosticsRowsFitTest {

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

  private val app: Application = ApplicationProvider.getApplicationContext()

  private fun show(fontScale: Float, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
      }
    }
    composeRule.waitForIdle()
  }

  private fun layoutsOf(text: String, substring: Boolean = false): List<TextLayoutResult> =
    composeRule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
      val results = ArrayList<TextLayoutResult>()
      node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
      results.single()
    }

  /** Una riga che comincia a meta' di una parola: il testo e' andato a capo dove non c'era uno spazio. */
  private fun brokenMidWord(layout: TextLayoutResult): Boolean {
    val text = layout.layoutInput.text.text
    return (1 until layout.lineCount).any { line ->
      val start = layout.getLineStart(line)
      start in 1 until text.length && !text[start - 1].isWhitespace()
    }
  }

  private fun assertNoTitleIsBroken(scale: Float) {
    show(scale) { DiagnosticsScreen(ScreenFixtures.attentionState(), onBack = {}, onRefresh = {}) }
    for (section in DiagSection.entries) {
      val title = app.getString(section.titleRes())
      val layouts = layoutsOf(title)
      assertWithMessage("«$title» non e' nella panoramica (testo $scale)").that(layouts).isNotEmpty()
      for (layout in layouts) {
        assertWithMessage("«$title» va a capo a meta' parola a 360 dp, testo $scale (${layout.lineCount} righe)").that(brokenMidWord(layout)).isFalse()
      }
    }
  }

  @Test
  fun iTitoliDelleSezioniNonSiSpezzanoATestoNormale() = assertNoTitleIsBroken(1f)

  @Test
  fun iTitoliDelleSezioniNonSiSpezzanoATestoGrande() = assertNoTitleIsBroken(1.3f)

  /** Negli «Avvisi di Android» il testo usa la larghezza della riga (prima la pillola nel badge lo lasciava in ~145 dp di colonna: 40% dello schermo). */
  private fun assertAndroidWarningsUseTheRow(scale: Float) {
    show(scale) {
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
    for (title in listOf("Blocco registrato", "Errore registrato")) {
      val layouts = layoutsOf(title)
      assertWithMessage("«$title» non e' nella sezione (testo $scale)").that(layouts).hasSize(1)
      assertThat(brokenMidWord(layouts.single())).isFalse()
    }
    val screenWidthPx = app.resources.displayMetrics.widthPixels
    // (il punto del codice ha un'opportunita' invisibile di andare a capo dopo i punti e prima dei `$`: si cerca per il solo nome)
    val detail = composeRule.onAllNodesWithText("AppRoot", substring = true, useUnmergedTree = true).fetchSemanticsNodes().single()
    assertWithMessage("il testo di una voce e' largo ${detail.size.width} px su $screenWidthPx (testo $scale)").that(detail.size.width).isAtLeast(screenWidthPx * 55 / 100)
  }

  /**
   * Il punto del codice di un errore registrato («LibraryViewModel$load$1.invokeSuspend (LibraryViewModel.kt:120)») e' una parola sola piu' larga della riga: a capo va fra i suoi pezzi
   * (prima di un `$`, dopo un punto), non dentro un nome («invokeS/uspend»).
   */
  private fun assertCodeLocationBreaksBetweenNames(scale: Float) {
    show(scale) {
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
    val layout = layoutsOf("LibraryViewModel", substring = true).single()
    val text = layout.layoutInput.text.text
    for (line in 1 until layout.lineCount) {
      val start = layout.getLineStart(line)
      assertWithMessage("il punto del codice va a capo dentro un nome, testo $scale («${text.substring(0, start)}|${text.substring(start)}»)")
        .that(text[start - 1].isLetterOrDigit() && text[start].isLetterOrDigit()).isFalse()
    }
  }

  @Test
  fun ilPuntoDelCodiceVaACapoFraINomiATestoNormale() = assertCodeLocationBreaksBetweenNames(1f)

  @Test
  fun ilPuntoDelCodiceVaACapoFraINomiATestoGrande() = assertCodeLocationBreaksBetweenNames(1.3f)

  @Test
  fun negliAvvisiDiAndroidIlTestoUsaLaLarghezzaDellaRigaATestoNormale() = assertAndroidWarningsUseTheRow(1f)

  @Test
  fun negliAvvisiDiAndroidIlTestoUsaLaLarghezzaDellaRigaATestoGrande() = assertAndroidWarningsUseTheRow(1.3f)
}
