package dev.pampa.mycloudgallery.feature.onboarding

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.navigation.toRoute
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import kotlinx.serialization.Serializable

/** Il grafo dell'onboarding: la rotta con cui :app lo apre (o lo usa come destinazione iniziale). */
@Serializable
data object OnboardingGraphRoute

/** Il passo corrente, qualunque sia: lo decide il coordinatore (l'avanzamento e' salvato, E-O9). */
@Serializable
data object OnboardingCurrentStepRoute

/**
 * Un passo saltato, riaperto dalle Impostazioni ("Da completare"). Il passo viaggia per nome: la rotta resta
 * serializzabile senza che `core-family` conosca kotlinx.serialization.
 */
@Serializable
data class OnboardingResumeRoute(val stepName: String) {
  constructor(step: OnboardingStep) : this(step.name)

  val step: OnboardingStep? get() = OnboardingStep.entries.firstOrNull { it.name == stepName }
}

/**
 * Le schermate dell'onboarding (scheletro P0, schermate P6). [onDone] lo fornisce :app: e' l'unica uscita
 * verso il resto dell'app (i feature non si conoscono). Dopo [onDone] la destinazione la decide lo stato
 * (`SetupState`), non questa callback.
 *
 * [onPause] e' "Continua piu' tardi" (E-O1, NAS spento o indirizzo sbagliato): :app lascia la configurazione cosi'
 * com'e' (e' salvata a ogni passo) e, per esempio, manda l'app in secondo piano. Di default non fa niente.
 */
fun NavGraphBuilder.onboardingGraph(nav: NavController, onDone: () -> Unit, onPause: () -> Unit = {}) {
  navigation<OnboardingGraphRoute>(startDestination = OnboardingCurrentStepRoute) {
    composable<OnboardingCurrentStepRoute> {
      OnboardingRoute(resumeStep = null, onDone = onDone, onPause = onPause)
    }
    composable<OnboardingResumeRoute> { entry ->
      val route = entry.toRoute<OnboardingResumeRoute>()
      OnboardingRoute(resumeStep = route.step, onDone = { if (!nav.popBackStack()) onDone() }, onPause = onPause)
    }
  }
}
