package dev.pampa.mycloudgallery.feature.onboarding

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.feature.onboarding.model.AccountUi
import dev.pampa.mycloudgallery.feature.onboarding.model.AreasUi
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyPage
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyUi
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingBanner
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AccountScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.KeyShowCodePage
import dev.pampa.mycloudgallery.feature.onboarding.steps.RolesForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.RoleAdvice
import dev.pampa.mycloudgallery.feature.onboarding.steps.RolesScreen
import dev.pampa.mycloudgallery.feature.onboarding.model.LocalStepUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.spec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TalkBack, testo ingrandito, bersagli di tocco: provati sull'albero semantico e su finestre con il carattere piu'
 * grande. Ogni problema, avviso e conferma ha un testo italiano non vuoto; ogni controllo e' alto almeno 48 dp; i
 * codici si leggono lettera per lettera nell'ordine giusto; un problema che compare viene annunciato da solo.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.PHONE)
internal class OnboardingAccessibilityTest : OnboardingComposeBase() {

  private val app: Application get() = ApplicationProvider.getApplicationContext()

  private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

  private fun chrome(step: OnboardingStep = OnboardingStep.ACCOUNT) = Samples.chrome(step)

  // --- Testi ----------------------------------------------------------------------------------------

  @Test
  fun ognunoDeiProblemiHaTitoloEDettaglio() {
    OnboardingProblem.entries.forEach { problem ->
      val spec = problem.spec()
      assertThat(str(spec.title)).isNotEmpty()
      assertThat(str(spec.detail).length).isGreaterThan(20)
      // un problema che si puo' correggere ha almeno un bottone, tranne quelli che si risolvono scrivendo meglio
    }
  }

  @Test
  fun ognunoDegliAvvisiEDelleConfermeHaIlSuoTesto() {
    OnboardingWarning.entries.forEach { warning ->
      val spec = warning.spec()
      assertThat(str(spec.title)).isNotEmpty()
      assertThat(str(spec.detail)).isNotEmpty()
    }
    OnboardingBanner.entries.forEach { banner ->
      val spec = banner.spec()
      assertThat(str(spec.title)).isNotEmpty()
      assertThat(str(spec.detail)).isNotEmpty()
    }
  }

  @Test
  fun ognunoDeiProblemiDiRetePermetteDiFareQualcosa() {
    val withActions = listOf(
      OnboardingProblem.NOT_ON_WIFI,
      OnboardingProblem.VPN_ACTIVE,
      OnboardingProblem.NO_ULA,
      OnboardingProblem.LOCAL_NETWORK_DENIED,
      OnboardingProblem.NAS_NOT_FOUND,
      OnboardingProblem.NAS_SERVICE_DOWN,
      OnboardingProblem.NAS_INSECURE,
      OnboardingProblem.IDENTITY_MISMATCH,
      OnboardingProblem.FAMILY_ALREADY_EXISTS,
      OnboardingProblem.NO_ACCESSIBLE_SHARE,
      OnboardingProblem.NOT_AT_HOME,
    )
    withActions.forEach { assertThat(it.spec().actions).isNotEmpty() }
  }

  // --- Bersagli di tocco ----------------------------------------------------------------------------

  @Test
  fun ilBottonePrincipaleELaBarraDellaPasswordHannoAlmeno48dp() {
    show { AccountScreen(chrome(), AccountUi(), AccountForm("marta", "una-password-molto-lunga"), AccountActions()) }
    composeRule.onNode(hasText(str(R.string.account_submit)) and hasClickAction())
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
    composeRule.onNodeWithContentDescription(str(R.string.reveal_show))
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
  }

  @Test
  fun leRigheConInterruttoreSonoAlte48dp() {
    show { RolesScreen(chrome(OnboardingStep.ROLES), LocalStepUi(), RolesForm(true, true), RoleAdvice(true, true), { _, _ -> }, {}) }
    val toggles = composeRule.onAllNodes(isToggleable())
    assertThat(toggles.fetchSemanticsNodes()).hasSize(2)
    toggles[0].assertHeightIsAtLeast(48.dp)
    toggles[1].assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun unProblemaDiAutenticazioneSiVede() {
    show {
      AccountScreen(
        chrome(),
        AccountUi(problem = OnboardingProblem.AUTH_FAILED, needsNewPassword = true),
        AccountForm("marta"),
        AccountActions(),
      )
    }
    composeRule.onNodeWithText(str(R.string.problem_auth_failed_title)).assertIsDisplayed()
  }

  @Test
  fun gliAvvisiSiChiudonoConUnBersaglioDa48dp() {
    show { AreasScreen(Samples.chrome(OnboardingStep.AREAS, warnings = listOf(OnboardingWarning.ACCOUNT_SEES_OTHER_PRIVATE_SHARES)), AreasUi(loading = true), AreasForm(), AreasActions()) }
    composeRule.onNodeWithContentDescription(str(R.string.common_dismiss))
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
  }

  // --- TalkBack -------------------------------------------------------------------------------------

  @Test
  fun ilCodiceSiLeggeLetteraPerLetteraEGruppoPerGruppoNellOrdineGiusto() {
    show { KeyShowCodePage(chrome(OnboardingStep.FAMILY_KEY), KeyUi(KeyPage.ShowCode, groups = Samples.codeGroups), KeyForm(), KeyActions()) }
    val spoken = composeRule
      .onAllNodes(SemanticsMatcher("gruppo del codice") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Gruppo ") == true
      })
      .fetchSemanticsNodes()
      .map { it.config[SemanticsProperties.ContentDescription].first() }
    assertThat(spoken).hasSize(7)
    assertThat(spoken.first()).isEqualTo("Gruppo 1 di 7: K, 7, Q, D")
    assertThat(spoken.last()).isEqualTo("Gruppo 7 di 7: 9, H, B, E")
    assertThat(spoken).isInOrder(compareBy<String> { it.removePrefix("Gruppo ").substringBefore(' ').toInt() })
  }

  @Test
  fun unProblemaCheCompareSiAnnunciaDaSolo() {
    show {
      AccountScreen(
        chrome(),
        AccountUi(problem = OnboardingProblem.ACCOUNT_LOCKED, needsNewPassword = true),
        AccountForm("marta"),
        AccountActions(),
      )
    }
    val node = composeRule.onNode(hasText(str(R.string.problem_account_locked_title), substring = true), useUnmergedTree = false)
      .fetchSemanticsNode()
    assertThat(node.config.getOrNull(SemanticsProperties.LiveRegion)).isEqualTo(LiveRegionMode.Polite)
  }

  @Test
  fun ilTitoloVieneLettoPrimaDelBottonePrincipale() {
    show { AccountScreen(chrome(), AccountUi(), AccountForm("marta", "una-password-molto-lunga"), AccountActions()) }
    val title = str(R.string.account_title)
    val button = str(R.string.account_submit)
    val order = composeRule.onAllNodes(hasText(title) or (hasText(button) and hasClickAction())).fetchSemanticsNodes()
      .map { node -> node.config[SemanticsProperties.Text].first().text }
    assertThat(order.first()).isEqualTo(title)
    assertThat(order.last()).isEqualTo(button)
  }

  @Test
  fun ilCamminoEUnaFrasePerIlLettoreDiSchermo() {
    show { AccountScreen(chrome(), AccountUi(), AccountForm(), AccountActions()) }
    composeRule.onNodeWithContentDescription(str(R.string.step_of, 3, 14)).assertIsDisplayed()
  }

  // --- Testo grande ---------------------------------------------------------------------------------

  @Test
  fun ilBottonePrincipaleRestaInVistaConIlTestoGrande() {
    show(fontScale = 1.3f) { AccountScreen(chrome(), AccountUi(), AccountForm("marta", "una-password-molto-lunga"), AccountActions()) }
    composeRule.onNode(hasText(str(R.string.account_submit)) and hasClickAction()).assertIsDisplayed()
  }
}
