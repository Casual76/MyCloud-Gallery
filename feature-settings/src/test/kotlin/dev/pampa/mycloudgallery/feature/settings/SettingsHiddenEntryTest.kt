package dev.pampa.mycloudgallery.feature.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 7 (P9, D33): Impostazioni -> "Nascosti" (nella sezione Protezione). Raggiunge la pagina dei Nascosti anche quando la sezione Nascosti delle
 * Raccolte e' nascosta (P11). La voce c'e' solo se `:app` ha scritto dove porta, e si tocca come le altre righe (bersaglio da 48 dp).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class SettingsHiddenEntryTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun show(links: SettingsLinks) {
    rule.setContent {
      MyCloudGalleryTheme {
        SettingsScreen(
          state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 2, pendingRequests = 0, pendingSteps = emptyList()),
          onBack = {},
          openDiagnostics = {},
          resumeStep = {},
          links = links,
        )
      }
    }
    rule.waitForIdle()
  }

  private fun scrollToHidden() {
    rule.onNode(hasScrollAction()).performScrollToNode(hasText("Nascosti"))
    rule.waitForIdle()
  }

  @Test
  fun conLaDestinazioneLaVoceCEEPortaAiNascosti() {
    var opened = 0
    show(SettingsLinks(openHidden = { opened++ }))
    scrollToHidden()

    rule.onNodeWithText("Nascosti").assertIsDisplayed()
    rule.onNodeWithText("Foto e video nascosti, dietro lo sblocco del telefono").assertIsDisplayed()
    rule.onNodeWithText("Nascosti").performClick()

    assertThat(opened).isEqualTo(1)
  }

  @Test
  fun senzaDestinazioneLaVoceNonCe() {
    show(SettingsLinks.None)

    rule.onAllNodesWithText("Nascosti").assertCountEquals(0)
    rule.onAllNodesWithText("Foto e video nascosti, dietro lo sblocco del telefono").assertCountEquals(0)
  }

  /** Il bersaglio e' una riga intera (48 dp almeno, come tutte le righe delle Impostazioni). */
  @Test
  fun laRigaEAlmenoAlta48dp() {
    show(SettingsLinks(openHidden = {}))
    scrollToHidden()

    val node = rule.onNodeWithText("Nascosti").fetchSemanticsNode()
    // Il testo sta dentro una riga cliccabile: la sua altezza e' quella del bersaglio.
    var row = node
    while (row.config.getOrNull(SemanticsActions.OnClick) == null) row = row.parent ?: break
    val minPx = with(rule.density) { 48.dp.toPx() }
    assertThat(row.config.getOrNull(SemanticsActions.OnClick)).isNotNull()
    assertThat(row.boundsInRoot.height).isAtLeast(minPx - 1f)
  }
}
