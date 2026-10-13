package dev.pampa.mycloudgallery.feature.onboarding

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.feature.onboarding.join.AwaitingApprovalScreen
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordActions
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordForm
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordPage
import dev.pampa.mycloudgallery.feature.onboarding.model.AccountUi
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinPage
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinUi
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyPage
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyUi
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryPage
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryUi
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryActions
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryCodePage
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryDonePage
import dev.pampa.mycloudgallery.feature.onboarding.recovery.RecoveryForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyRetypePage
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyShowCodePage
import dev.pampa.mycloudgallery.feature.onboarding.steps.WelcomeScreen
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La sicurezza dello schermo: `FLAG_SECURE` dove c'e' un segreto (password, codice di recupero, QR della chiave),
 * `SecureInput` sui campi segreti, tocchi "oscurati" scartati sulle pagine che decidono qualcosa. E il contrario: una
 * pagina che non ha segreti non blocca gli screenshot, e quando una pagina con un segreto esce la protezione si toglie.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.PHONE)
internal class OnboardingSecurityTest : OnboardingComposeBase() {

  private val noChrome = StepChrome()

  private fun welcome(): @androidx.compose.runtime.Composable () -> Unit = {
    WelcomeScreen(noChrome, isSystemUser = true, busy = false, problem = null, onChoose = {}, onProblemAction = {})
  }

  @Test
  fun ilBenvenutoNonBloccaGliScreenshot() {
    show(content = welcome())
    assertThat(isSecure()).isFalse()
  }

  @Test
  fun laPaginaDellAccountEProtetta() {
    show { AccountScreen(noChrome, AccountUi(), AccountForm(), AccountActions()) }
    assertThat(isSecure()).isTrue()
    assertThat(discardsObscuredTouches()).isTrue()
  }

  @Test
  fun uscendoDallAccountLaProtezioneSiToglie() {
    var page by mutableStateOf("account")
    show {
      if (page == "account") {
        AccountScreen(noChrome, AccountUi(), AccountForm(), AccountActions())
      } else {
        welcome()()
      }
    }
    assertThat(isSecure()).isTrue()
    composeRule.runOnIdle { page = "welcome" }
    composeRule.waitForIdle()
    assertThat(isSecure()).isFalse()
    assertThat(discardsObscuredTouches()).isFalse()
  }

  @Test
  fun ilCodiceDiRecuperoSiMostraSuUnaPaginaProtetta() {
    show { KeyShowCodePage(noChrome, KeyUi(KeyPage.ShowCode, groups = Samples.codeGroups), KeyForm(), KeyActions()) }
    assertThat(isSecure()).isTrue()
  }

  /** Revisione 2, F3: sul foglio va anche l'impronta della famiglia, l'ancora del recupero senza QR di stato. */
  @Test
  fun ilFoglioPortaAncheLImprontaDellaFamiglia() {
    val fingerprint = "abcdefghjkmnpqrstvwxyz0123"
    show { KeyShowCodePage(noChrome, KeyUi(KeyPage.ShowCode, groups = Samples.codeGroups, fingerprint = fingerprint), KeyForm(), KeyActions()) }
    composeRule.onNode(androidx.compose.ui.test.hasText("abcd efgh jkmn pqrs tvwx yz01 23")).performScrollTo().assertExists()
  }

  @Test
  fun ilCodiceRiscrittoSiScriveSuUnaPaginaProtettaEConSecureInput() {
    show { KeyRetypePage(noChrome, KeyUi(KeyPage.Retype, groups = Samples.codeGroups), KeyForm(reveal = false), KeyActions()) }
    assertThat(isSecure()).isTrue()
    assertThat(discardsObscuredTouches()).isTrue()
    assertThat(isPasswordField(0)).isTrue()
  }

  @Test
  fun laPasswordDiAccountEUnCampoSecureInput() {
    show { AccountScreen(noChrome, AccountUi(), AccountForm(), AccountActions()) }
    val fields = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
    assertThat(fields).hasSize(2)
    // il nome utente e' un campo normale, la password no
    assertThat(fields[0].config.contains(SemanticsProperties.Password)).isFalse()
    assertThat(fields[1].config.contains(SemanticsProperties.Password)).isTrue()
  }

  @Test
  fun lAccountDiUnaFamigliaEntraConLaPasswordProtetta() {
    show {
      JoinPasswordPage(
        noChrome,
        JoinUi(page = JoinPage.Password, invite = sampleInvite()),
        "marta",
        JoinPasswordForm(),
        JoinPasswordActions(),
      )
    }
    assertThat(isSecure()).isTrue()
    assertThat(isPasswordField(0)).isTrue()
  }

  @Test
  fun ilCodiceDelRecuperoDellAdminEProtettoESegreto() {
    show { RecoveryCodePage(noChrome, RecoveryUi(page = RecoveryPage.Code, kitsLoaded = true), RecoveryForm(reveal = false), RecoveryActions()) }
    assertThat(isSecure()).isTrue()
    assertThat(isPasswordField(0)).isTrue()
  }

  @Test
  fun ilQrDellaChiaveDelDispositivoEProtetto() {
    show { AwaitingApprovalScreen(code = "K7QD-2M9X-FA4T", deviceKeyQr = "MCGK1:chiave") }
    assertThat(isSecure()).isTrue()
  }

  /** Revisione 2, F5: anche senza QR il codice e' cio' che l'admin digita: niente cattura, niente sovrapposizioni. */
  @Test
  fun ancheSenzaIlQrLAttesaEProtetta() {
    show { AwaitingApprovalScreen(code = "K7QD-2M9X-FA4T", deviceKeyQr = null) }
    assertThat(isSecure()).isTrue()
    assertThat(discardsObscuredTouches()).isTrue()
  }

  @Test
  fun laCornicedellOnboardingScartaITocchiOscurati() {
    val env = TestEnv()
    show {
      OnboardingHost(
        viewModel = env.viewModel(),
        platform = env.platform,
        resumeStep = null,
        onDone = {},
        onPause = {},
      )
    }
    assertThat(discardsObscuredTouches()).isTrue()
    assertThat(isSecure()).isFalse()
  }

  @Test
  fun lEsitoDelRecuperoNonHaSegreti() {
    show { RecoveryDonePage(noChrome, RecoveryUi(page = RecoveryPage.Done), OldAdminFate.LOST, RecoveryActions()) }
    assertThat(isSecure()).isFalse()
  }

  private fun isPasswordField(index: Int): Boolean =
    composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()[index].config.contains(SemanticsProperties.Password)
}
