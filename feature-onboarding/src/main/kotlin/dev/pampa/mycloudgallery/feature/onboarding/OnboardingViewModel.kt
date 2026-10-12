package dev.pampa.mycloudgallery.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Lo stato delle schermate dell'onboarding: tutto viene dal coordinatore di `core-family` (P4), che salva
 * l'avanzamento a ogni passo. Il ViewModel non decide niente: inoltra i gesti e mostra [progress].
 *
 * Scheletro P0: P6 aggiungera' qui (o in ViewModel per passo) gli stati di ogni schermata e d'errore.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
  private val coordinator: OnboardingCoordinator,
) : ViewModel() {

  val progress: StateFlow<OnboardingProgress> = coordinator.progress

  /** Riapre un passo saltato (arrivando da "Da completare"). */
  fun resume(step: OnboardingStep) {
    viewModelScope.launch { coordinator.resume(step) }
  }
}
