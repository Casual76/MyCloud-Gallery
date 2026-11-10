package dev.pampa.mycloudgallery.core.designsystem.sheet

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K4: il foglio "Condividere con la famiglia?" sul telefono dell'utente (360 dp), con testo normale e grande. Il valore di una riga ("Gli altri
 * aggiungono soltanto") stava a destra, nel `badge` della riga, che prende la larghezza per primo: all'etichetta restava una colonna in cui
 * "Permessi" si spezzava in "Perm/essi". Adesso il valore sta sotto l'etichetta: l'etichetta sta su una riga, intera, e il valore si legge per intero.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
class GalleryShareSheetFitTest {

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

  private fun show(fontScale: Float) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          GalleryShareSheetContent(
            count = 12,
            onConfirm = {},
            onCancel = {},
            albumName = "Oslo 2026",
            permissionLabel = "Gli altri aggiungono soltanto",
          )
        }
      }
    }
    composeRule.waitForIdle()
  }

  /** Il disegno del testo esattamente "[text]" (nell'albero non unito: il nodo del solo testo). */
  private fun layoutOf(text: String): TextLayoutResult {
    val nodes = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
    assertWithMessage("\"$text\" non e' sullo schermo").that(nodes).hasSize(1)
    val results = ArrayList<TextLayoutResult>()
    nodes.single().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
    return results.single()
  }

  private fun assertFits(scale: String) {
    for (label in listOf("Album", "Permessi")) {
      val layout = layoutOf(label)
      assertWithMessage("\"$label\" a 360 dp, testo $scale, va a capo").that(layout.lineCount).isEqualTo(1)
    }
    for (value in listOf("Oslo 2026", "Gli altri aggiungono soltanto")) {
      val layout = layoutOf(value)
      assertWithMessage("\"$value\" a 360 dp, testo $scale, e' tagliato").that(layout.hasVisualOverflow).isFalse()
    }
  }

  @Test
  fun leEtichetteDelleRigheStannoInteregATestoNormale() {
    show(fontScale = 1f)
    assertFits("normale")
  }

  @Test
  fun leEtichetteDelleRigheStannoInteregATestoGrande() {
    show(fontScale = 1.3f)
    assertFits("grande")
  }
}
