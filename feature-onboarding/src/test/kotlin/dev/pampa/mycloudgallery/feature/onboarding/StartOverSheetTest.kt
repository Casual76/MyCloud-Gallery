package dev.pampa.mycloudgallery.feature.onboarding

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.onboarding.model.RemovalReason
import dev.pampa.mycloudgallery.feature.onboarding.model.RemovalUi
import dev.pampa.mycloudgallery.feature.onboarding.steps.RemovedScreen
import dev.pampa.mycloudgallery.feature.onboarding.ui.StartOverSheetContent
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R7 della revisione di sicurezza della Fase 5B: la pagina "fuori dalla famiglia" non configura di nuovo con un tocco solo, e il
 * foglio di conferma dice a un admin cosa perde e come lo recupera (kit e codice). Si prova il contenuto del foglio, senza il
 * pop-up (che e' `GalleryConfirmSheet`, provato dal design system).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.PHONE)
internal class StartOverSheetTest : OnboardingComposeBase() {

  private var asked = 0
  private var started = 0
  private var cancelled = 0

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  @Test
  fun ilTastoDellaPaginaApreLaConfermaENonConfiguraDiNuovo() {
    show {
      RemovedScreen(
        chrome = StepChrome(),
        ui = RemovalUi(RemovalReason.REVOKED),
        onAskStartOver = { asked++ },
        onCancelStartOver = { cancelled++ },
        onStartOver = { started++ },
        onProblemAction = {},
      )
    }

    // Prima il tasto chiamava subito la configurazione: chiavi e password sparivano con un tocco solo.
    button("Configura di nuovo").performClick()

    assertThat(asked).isEqualTo(1)
    assertThat(started).isEqualTo(0)
  }

  @Test
  fun ilFoglioDiUnFamiliareDiceCosaSiDimenticaENonParlaDellaChiave() {
    show { StartOverSheetContent(holdsKey = false, unknownCause = false, onDismiss = { cancelled++ }, onConfirm = { started++ }) }

    composeRule.onNodeWithText("Configuri di nuovo questo dispositivo?").assertExists()
    composeRule.onNodeWithText("dimentica account, password e chiavi della famiglia di prima", substring = true).assertExists()
    assertThat(composeRule.onAllNodes(hasText("kit di recupero", substring = true)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilFoglioDiUnAdminDiceCosaSiPerdeEIndicaIlKit() {
    show { StartOverSheetContent(holdsKey = true, unknownCause = false, onDismiss = { cancelled++ }, onConfirm = { started++ }) }

    composeRule.onNodeWithText("è un admin", substring = true).assertExists()
    composeRule.onNodeWithText("non può più approvare dispositivi", substring = true).assertExists()
    composeRule.onNodeWithText("kit di recupero e il suo codice", substring = true).assertExists()
    composeRule.onNodeWithText("Recupera l’admin", substring = true).assertExists()
    // Con una causa nota (revocato, dismesso) non c'e' il suggerimento del guasto.
    assertThat(composeRule.onAllNodes(hasText("guasto", substring = true)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun seLaCausaESconosciutaIlFoglioDiUnAdminRicordaCheEPotutoEssereUnGuasto() {
    show { StartOverSheetContent(holdsKey = true, unknownCause = true, onDismiss = { cancelled++ }, onConfirm = { started++ }) }

    composeRule.onNodeWithText("Se il telefono ha solo avuto un guasto", substring = true).assertExists()
    composeRule.onNodeWithText("kit di recupero e il suo codice", substring = true).assertExists()
  }

  @Test
  fun laConfermaEdAnnullaChiamanoCiascunaLaSuaPortaUnaVolta() {
    show { StartOverSheetContent(holdsKey = true, unknownCause = false, onDismiss = { cancelled++ }, onConfirm = { started++ }) }

    button("Annulla").performClick()
    assertThat(cancelled).isEqualTo(1)
    assertThat(started).isEqualTo(0)

    button("Configura di nuovo").performClick()
    assertThat(started).isEqualTo(1)
    assertThat(cancelled).isEqualTo(1)
  }
}
