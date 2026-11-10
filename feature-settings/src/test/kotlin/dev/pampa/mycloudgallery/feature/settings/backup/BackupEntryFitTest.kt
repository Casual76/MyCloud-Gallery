package dev.pampa.mycloudgallery.feature.settings.backup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K4: la voce "Backup" con la pillola "Da controllare" sul telefono dell'utente (360 dp) e con il testo grande (1,3x). La pillola era nel `badge`
 * della riga, che prende la larghezza per primo, e il titolo restava in una colonna di sillabe ("Backu/p"). Adesso la pillola sta in `labels`: se
 * non c'e' posto accanto al titolo va sotto, e il titolo sta su una riga sola, intero.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h780dp-xhdpi")
class BackupEntryFitTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun show(status: BackupStatus?, fontScale: Float) {
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          Box(Modifier.fillMaxSize().padding(16.dp)) {
            FluidListGroup { BackupEntryRow(status = status, onClick = {}) }
          }
        }
      }
    }
    rule.waitForIdle()
  }

  /** Quante righe scrive il testo esattamente "[text]" (nell'albero non unito: il nodo del solo testo). */
  private fun lineCounts(text: String): List<Int> =
    rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
      val results = ArrayList<TextLayoutResult>()
      node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
      results.single().lineCount
    }

  private fun assertWhole(text: String, scale: Float) {
    val counts = lineCounts(text)
    assertWithMessage("\"$text\" non e' sullo schermo (testo $scale)").that(counts).hasSize(1)
    assertWithMessage("\"$text\" a 360 dp, testo $scale, va a capo: $counts").that(counts.single()).isEqualTo(1)
  }

  @Test
  fun iTitoloNonSiSpezzaConLaPillolaATestoNormale() {
    show(BackupStatus.Problem(BackupProblemCode.QUOTA, 0), fontScale = 1f)
    assertWhole("Backup", 1f)
    assertWhole("Da controllare", 1f)
  }

  @Test
  fun iTitoloNonSiSpezzaConLaPillolaATestoGrande() {
    show(BackupStatus.Problem(BackupProblemCode.QUOTA, 0), fontScale = 1.3f)
    assertWhole("Backup", 1.3f)
    assertWhole("Da controllare", 1.3f)
  }
}
