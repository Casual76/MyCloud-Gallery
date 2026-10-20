package dev.pampa.mycloudgallery.feature.onboarding

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AreaCandidate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.feature.onboarding.model.AreasUi
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreaState
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasActions
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasForm
import dev.pampa.mycloudgallery.feature.onboarding.steps.AreasScreen
import dev.pampa.mycloudgallery.feature.onboarding.steps.state
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Prove sul campo, punto 8: al passo 4, con l'account di prova, `Public` e `NAS` comparivano come "Accesso negato". Li esclude la recinzione
 * delle prove (D33), non il NAS: la riga lo dice con parole sue e la share non si puo' scegliere.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.PHONE_TALL)
internal class AreasRecintoTest : OnboardingComposeBase() {

  private val candidates = listOf(
    AreaCandidate("test", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = null, hasFamily = false),
    AreaCandidate("test-comune", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = null, hasFamily = false),
    AreaCandidate("test2", NasResult.Ok(ShareAccess.DENIED), isPublic = null, hasFamily = false),
    AreaCandidate("Public", NasResult.Ok(ShareAccess.DENIED), isPublic = true, hasFamily = false, fenced = true),
  )

  @Test
  fun unaShareEsclusaDallaRecinzioneNonDiceAccessoNegato() {
    assertThat(candidates.map { it.state() })
      .containsExactly(AreaState.READ_WRITE, AreaState.READ_WRITE, AreaState.DENIED, AreaState.FENCED).inOrder()

    show { AreasScreen(Samples.chrome(OnboardingStep.AREAS), AreasUi(loading = false, candidates = candidates), AreasForm(), AreasActions()) }

    // La share esclusa compare in tutti e due i gruppi (area comune e area privata), sempre come esclusa.
    assertThat(composeRule.onAllNodesWithText("Esclusa per l’account di prova").fetchSemanticsNodes()).isNotEmpty()
    // "Accesso negato" resta solo per la share che il NAS rifiuta davvero (`test2`, di un altro account).
    val denied = composeRule.onAllNodesWithText("Accesso negato").fetchSemanticsNodes().size
    val fenced = composeRule.onAllNodesWithText("Esclusa per l’account di prova").fetchSemanticsNodes().size
    assertThat(denied).isEqualTo(fenced)
  }
}
