package dev.pampa.mycloudgallery.feature.settings.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.DeviceView
import dev.pampa.mycloudgallery.core.family.contract.LostReporting
import dev.pampa.mycloudgallery.core.family.contract.PendingRequestView
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.feature.settings.port.SettingsClock
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.toFeedback
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * L'elenco dei dispositivi (P7, piano 2.4): chi c'e', in che stato, chi chiede di entrare.
 *
 * Si apre rileggendo il registro dal NAS ([refresh]): e' uno dei due momenti in cui l'admin vede una richiesta
 * nuova (l'altro e' il controllo in background nella finestra d'invito, §2.2). Le azioni da admin che
 * scrivono sul registro stanno nel dettaglio di un dispositivo e nella pagina di approvazione.
 */
@HiltViewModel
internal class DevicesViewModel @Inject constructor(
  private val registry: RegistryService,
  private val lostReporting: LostReporting,
  sessions: NasSessionManager,
  private val clock: SettingsClock,
) : ViewModel() {

  private val refreshing = MutableStateFlow(false)
  private val feedback = MutableStateFlow<Feedback?>(null)

  private val content: StateFlow<DevicesUiState> = combine(
    registry.status,
    registry.devices,
    registry.pendingRequests,
    sessions.state,
  ) { trust, devices, requests, connection ->
    build(trust, devices, requests, connection)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = build(registry.status.value, registry.devices.value, registry.pendingRequests.value, sessions.state.value),
  )

  val state: StateFlow<DevicesUiState> = combine(content, refreshing, feedback, registry.reissueDue) { base, busy, note, reissue ->
    base.copy(refreshing = busy, feedback = note, reissueDue = reissue && base.isAdmin)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = content.value,
  )

  /** Rilegge il registro dal NAS (interattivo: l'utente aspetta). */
  fun refresh() {
    if (refreshing.value) return
    viewModelScope.launch {
      refreshing.value = true
      try {
        registry.refresh(Purpose.INTERACTIVE)
      } finally {
        refreshing.value = false
      }
    }
  }

  /** Solo admin: trasforma in "perso" le segnalazioni valide dei proprietari (decisione 3a). */
  fun convertReports() {
    viewModelScope.launch {
      feedback.value = lostReporting.convertReports().toFeedback()
    }
  }

  /**
   * Solo admin (revisione 1.4, N3; Fase 10, M3): la versione fidata del registro e' stata sostituita sul NAS e va ripubblicata. Prima si
   * faceva da sola durante un controllo; ora e' questo gesto, con il foglio dell'impronta o del PIN.
   */
  fun reissue() {
    viewModelScope.launch {
      feedback.value = registry.reissue().toFeedback()
    }
  }

  fun dismissFeedback() {
    feedback.value = null
  }

  private fun build(
    trust: TrustStatus,
    devices: List<DeviceView>,
    requests: List<PendingRequestView>,
    connection: NasConnState,
  ): DevicesUiState = DeviceMapper.list(trust, devices, requests, DeviceMapper.nasNow(clock.nowMillis(), connection))

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
