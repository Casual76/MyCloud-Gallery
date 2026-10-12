package dev.pampa.mycloudgallery.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Cio' che la pagina principale delle Impostazioni mostra. */
data class SettingsUiState(
  val trust: TrustStatus,
  val deviceCount: Int,
  val pendingRequests: Int,
  val pendingSteps: List<OnboardingStep>,
)

/**
 * Lo stato delle Impostazioni: il registro (P3) e i passi saltati dell'onboarding (P4). Scheletro P0: P7
 * aggiunge le pagine e le azioni (approvare, segnalare perso, invitare...).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
  registry: RegistryService,
  coordinator: OnboardingCoordinator,
) : ViewModel() {

  val state: StateFlow<SettingsUiState> = combine(
    registry.status,
    registry.devices,
    registry.pendingRequests,
    coordinator.progress,
  ) { trust, devices, requests, progress ->
    SettingsUiState(trust, devices.size, requests.size, progress.pending)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = SettingsUiState(
      trust = registry.status.value,
      deviceCount = registry.devices.value.size,
      pendingRequests = registry.pendingRequests.value.size,
      pendingSteps = coordinator.progress.value.pending,
    ),
  )

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
