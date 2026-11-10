package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * GF-24 (review della beta) sul pop-up della nuvola, a 360 dp (il Galaxy S25 dell'utente), a testo normale e a 1,3x:
 *  - la capsula del lavoro in corso non taglia il totale («Primo backup: 1.240 di 6.…»): se non entra su una riga va a capo **fra le parole**, mai dentro un numero, e
 *    solo oltre la seconda riga finirebbe con i puntini;
 *  - TalkBack legge il dettaglio una volta sola: la capsula dice «etichetta. dettaglio», e il testo a parte sotto (che si vede) non lo rilegge.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
internal class LibraryCloudTextFitTest(private val fontScale: Float) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private fun openPopup(backup: LibraryBackupStatus) {
    val state = syntheticLibraryState(backup = backup)
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          Box(modifier = Modifier.fillMaxSize()) {
            LibraryTab(
              contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
              backdropOut = {},
              onOpenViewer = {},
              onOpenSettings = {},
              state = state,
              network = LibraryNetworkStatus(severity = LibraryNetworkSeverity.Fine, label = "In casa", detail = "NAS collegato"),
            )
          }
        }
      }
    }
    settle()
    composeRule.onAllNodesWithContentDescription("Stato della sincronizzazione", substring = true)[0].performClick()
    settle()
  }

  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    composeRule.waitForIdle()
  }

  private fun layoutsOf(text: String): List<TextLayoutResult> =
    composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
      val results = ArrayList<TextLayoutResult>()
      node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
      results.single()
    }

  @Test
  fun ilTotaleDelPrimoBackupNonSiTagliaENonSiSpezzaDentroUnNumero() {
    openPopup(LibraryBackupStatus.FirstBackup(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L))
    val layouts = layoutsOf("Primo backup: 1.240 di 6.658")
    assertWithMessage("«Primo backup: 1.240 di 6.658» nel pop-up (testo ${fontScale}x)").that(layouts).hasSize(1)
    val layout = layouts.single()
    val text = layout.layoutInput.text.text
    assertWithMessage("la frase finisce con i puntini (testo ${fontScale}x, ${layout.lineCount} righe)").that((0 until layout.lineCount).any { layout.isLineEllipsized(it) }).isFalse()
    assertWithMessage("al massimo due righe (testo ${fontScale}x)").that(layout.lineCount).isAtMost(2)
    for (line in 1 until layout.lineCount) {
      val start = layout.getLineStart(line)
      assertWithMessage("la riga $line comincia dentro una parola o un numero («${text.substring(0, start)}|${text.substring(start)}»)").that(text[start - 1].isWhitespace()).isTrue()
    }
  }

  @Test
  fun ilDettaglioLoLeggeTalkBackUnaVoltaSola() {
    openPopup(LibraryBackupStatus.FirstBackup(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L))
    val detail = "circa 1 h 20 min"
    // Il testo a parte c'e' e si vede, ma e' nascosto a TalkBack...
    val texts = composeRule.onAllNodesWithText(detail, useUnmergedTree = true).fetchSemanticsNodes()
    assertThat(texts).isNotEmpty()
    for (node in texts) {
      assertWithMessage("«$detail» e' letto da TalkBack due volte: il testo a parte non e' nascosto").that(node.config.contains(SemanticsProperties.HideFromAccessibility)).isTrue()
    }
    // ... e la capsula lo dice insieme alla sua etichetta.
    composeRule.onAllNodesWithContentDescription("Primo backup: 1.240 di 6.658. $detail").assertCountAtLeast1()
  }

  private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountAtLeast1() {
    assertThat(fetchSemanticsNodes()).isNotEmpty()
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "testo={0}x")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(1f), arrayOf<Any>(1.3f))

    const val SettleMillis = 1_500L
  }
}
