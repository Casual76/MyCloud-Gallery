package dev.pampa.mycloudgallery.feature.onboarding

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Le fondamenta: la rotta di ripresa, i titoli dei passi e il collegamento fra ViewModel e coordinatore. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingSkeletonTest {

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun laRottaDiRipresaPortaIlPassoPerNome() {
    OnboardingStep.entries.forEach { step ->
      assertThat(OnboardingResumeRoute(step).step).isEqualTo(step)
    }
    assertThat(OnboardingResumeRoute("PASSO_INESISTENTE").step).isNull()
  }

  @Test
  fun ogniPassoHaIlSuoTitolo() {
    val titles = OnboardingStep.entries.map { it.titleRes() }
    assertThat(titles.toSet()).hasSize(OnboardingStep.entries.size)
  }

  @Test
  fun ilViewModelMostraIlCoordinatoreERiapreUnPassoSaltato() = runTest {
    val skipped = OnboardingProgress.START.copy(current = OnboardingStep.SUMMARY, skipped = setOf(OnboardingStep.VPN))
    val env = TestEnv(skipped)
    val viewModel = env.viewModel()
    assertThat(viewModel.progress.value.current).isEqualTo(OnboardingStep.SUMMARY)
    viewModel.resume(OnboardingStep.VPN)
    assertThat(viewModel.progress.value.current).isEqualTo(OnboardingStep.VPN)
  }
}
