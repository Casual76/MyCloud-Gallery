package dev.pampa.mycloudgallery.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.DeviceView
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Cio' che la pagina principale delle Impostazioni mostra.
 *
 * Il costruttore e' quello dello scaffold P0 (lo usano gli screenshot di `:app`): i campi nuovi si calcolano
 * da questi, o arrivano in fondo con un valore di serie che non cambia niente per chi non li passa.
 *
 * @property soleAdmin giro 2 della fase I (CU-29): questo dispositivo e' admin ed e' l'unico admin approvato. E' un rischio vero (perso questo
 *   telefono senza il kit, nessuno puo' piu' gestire la famiglia): la testata lo dice con il segno «attenzione», come la pagina Famiglia.
 */
data class SettingsUiState(
  val trust: TrustStatus,
  val deviceCount: Int,
  val pendingRequests: Int,
  val pendingSteps: List<OnboardingStep>,
  val soleAdmin: Boolean = false,
) {
  /** Questo dispositivo tiene la chiave di famiglia: vede anche le azioni da admin. */
  val isAdmin: Boolean get() = trust is TrustStatus.Approved && trust.admin
}

/**
 * Lo stato delle Impostazioni: il registro (P3) e i passi saltati dell'onboarding (P4). Le pagine che
 * partono da qui hanno ognuna il proprio ViewModel (dispositivi, famiglia, account, rete, sicurezza).
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
    SettingsUiState(trust, devices.size, requests.size, progress.pending, soleAdmin = soleAdmin(trust, devices))
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = SettingsUiState(
      trust = registry.status.value,
      deviceCount = registry.devices.value.size,
      pendingRequests = registry.pendingRequests.value.size,
      pendingSteps = coordinator.progress.value.pending,
      soleAdmin = soleAdmin(registry.status.value, registry.devices.value),
    ),
  )

  /** Lo stesso conto della pagina Famiglia: admin approvati. */
  private fun soleAdmin(trust: TrustStatus, devices: List<DeviceView>): Boolean =
    trust is TrustStatus.Approved && trust.admin && devices.count { it.admin && it.status == DeviceStatus.APPROVED } <= 1

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
