package dev.pampa.mycloudgallery.feature.onboarding

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.credentials.NasPasswordPolicy
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordActions
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordForm
import dev.pampa.mycloudgallery.feature.onboarding.join.JoinPasswordPage
import dev.pampa.mycloudgallery.feature.onboarding.model.AccountUi
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinPage
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinUi
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountScreen
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Revisione 2, F1: la password del NAS deve reggere un attacco offline. Sotto il campo si spiega che cosa manca, il tasto
 * non manda una password debole, e l'app sa proporne una forte da impostare sul NAS.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.PHONE)
internal class PasswordStrengthUiTest : OnboardingComposeBase() {

  private val app: Application get() = ApplicationProvider.getApplicationContext()

  private fun str(id: Int): String = app.getString(id)

  private fun click(text: String) {
    composeRule.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
    composeRule.waitForIdle()
  }

  @Test
  fun unaPasswordDeboleSiSpiegaENonParte() {
    val sent = mutableListOf<String>()
    val form = AccountForm(user = "marta", password = "Marta-Foto-1975")
    show { AccountScreen(StepChrome(), AccountUi(), form, AccountActions(onSubmit = { _, p -> sent += String(p) })) }
    composeRule.onNodeWithText(str(R.string.password_weak_user)).assertExists()
    click(str(R.string.account_submit))
    assertThat(sent).isEmpty()

    composeRule.runOnIdle { form.password = "Gatto-Nero-7" }
    composeRule.waitForIdle()
    click(str(R.string.account_submit))
    assertThat(sent).containsExactly("Gatto-Nero-7")
  }

  @Test
  fun lAppProponeUnaPasswordForteDaImpostareSulNas() {
    val form = AccountForm(user = "marta")
    show { AccountScreen(StepChrome(), AccountUi(), form, AccountActions()) }
    composeRule.onNodeWithText(str(R.string.password_rule)).assertExists()
    composeRule.onNodeWithText(str(R.string.account_generate_title)).performScrollTo().performClick()
    composeRule.waitForIdle()
    assertThat(form.password).hasLength(NasPasswordPolicy.GENERATED_CHARS)
    assertThat(NasPasswordPolicy.check("marta", form.password.toCharArray())).isNull()
    assertThat(form.reveal).isTrue()
    composeRule.onNodeWithText(str(R.string.account_generated_title)).performScrollTo().assertExists()
  }

  @Test
  fun ancheIlFamiliareNonMandaUnaPasswordDebole() {
    val sent = mutableListOf<String>()
    val form = JoinPasswordForm(password = "mamma1975")
    show {
      JoinPasswordPage(StepChrome(), JoinUi(page = JoinPage.Password), "mamma", form, JoinPasswordActions(onSubmit = { sent += String(it) }))
    }
    composeRule.onNodeWithText(str(R.string.password_weak_short), substring = true).assertExists()
    click(str(R.string.join_password_submit))
    assertThat(sent).isEmpty()
  }
}
