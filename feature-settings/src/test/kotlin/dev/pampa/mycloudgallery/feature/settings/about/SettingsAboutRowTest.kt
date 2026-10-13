package dev.pampa.mycloudgallery.feature.settings.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La pagina principale delle Impostazioni porta a "Informazioni e licenze": una riga nella sezione "Assistenza",
 * dopo la Diagnostica, che chiama `SettingsLinks.openAbout`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class SettingsAboutRowTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun show(links: SettingsLinks, openDiagnostics: () -> Unit = {}) {
    rule.setContent {
      MyCloudGalleryTheme {
        SettingsScreen(
          state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList()),
          onBack = {},
          openDiagnostics = openDiagnostics,
          resumeStep = {},
          links = links,
        )
      }
    }
    rule.waitForIdle()
  }

  private fun scrollTo(text: String) {
    rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    rule.waitForIdle()
  }

  @Test
  fun laRigaInformazioniELicenzeChiamaOpenAbout() {
    var opened = 0
    show(SettingsLinks(openAbout = { opened++ }))

    scrollTo("Informazioni e licenze")
    rule.onNodeWithText("Informazioni e licenze").assertIsDisplayed().performClick()

    assertThat(opened).isEqualTo(1)
  }

  @Test
  fun laRigaStaNellaSezioneAssistenzaDopoLaDiagnostica() {
    var diagnostics = 0
    var opened = 0
    show(SettingsLinks(openAbout = { opened++ }), openDiagnostics = { diagnostics++ })

    scrollTo("Informazioni e licenze")
    val diagnosticsTop = rule.onNodeWithText("Diagnostica").fetchSemanticsNode().boundsInRoot.top
    val aboutTop = rule.onNodeWithText("Informazioni e licenze").fetchSemanticsNode().boundsInRoot.top
    assertThat(aboutTop).isGreaterThan(diagnosticsTop)
    rule.onNodeWithText("Versione, luoghi offline e licenze delle librerie").assertIsDisplayed()

    // Le due righe sono indipendenti: toccare Diagnostica non apre le Informazioni.
    rule.onNodeWithText("Diagnostica").performClick()
    assertThat(diagnostics).isEqualTo(1)
    assertThat(opened).isEqualTo(0)
  }

  @Test
  fun senzaDestinazioneLaRigaNonFaNiente() {
    show(SettingsLinks.None)

    scrollTo("Informazioni e licenze")
    rule.onNodeWithText("Informazioni e licenze").performClick()
  }
}
