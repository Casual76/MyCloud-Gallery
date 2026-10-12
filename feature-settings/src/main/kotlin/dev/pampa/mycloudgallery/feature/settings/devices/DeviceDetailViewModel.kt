package dev.pampa.mycloudgallery.feature.settings.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.DeviceView
import dev.pampa.mycloudgallery.core.family.contract.LostReporting
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.port.SettingsClock
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.toFeedback
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * I quattro passi dopo una segnalazione di smarrimento, **nell'ordine in cui vanno fatti** (piano 2.4,
 * `docs/fase2-progetto.md` §2.3):
 *
 *  1. [REPORT]: segnalare (si fa da qui, e firma il registro);
 *  2. [REVOKE_PEER]: revocare il peer WireGuard sul FRITZ!Box;
 *  3. [CHANGE_PASSWORD]: cambiare la password dell'account sul NAS (gli altri dispositivi dello stesso
 *     account dovranno reinserirla);
 *  4. [FIND_DEVICE]: Samsung Find, per bloccare o cancellare il telefono.
 *
 * Il perche' dell'ordine: prima va il segnale firmato, perche' e' l'unica cosa che fa **azzerare** il
 * dispositivo quando si ricollega; poi si chiudono le due porte (VPN e password) che il dispositivo perso
 * potrebbe ancora usare; Samsung Find e' l'ultima rete di sicurezza.
 */
internal enum class GuideStep {
  REPORT,
  REVOKE_PEER,
  CHANGE_PASSWORD,
  FIND_DEVICE,
  ;

  companion object {
    /** L'ordine, per le pagine e per i test. */
    val ordered: List<GuideStep> = entries.toList()
  }
}

/** A che punto e' la segnalazione di smarrimento. Due conferme distinte prima di scrivere (doppia conferma). */
internal enum class LostStage { NONE, FIRST_CONFIRM, SECOND_CONFIRM, WORKING, GUIDE }

/** Un cambio di stato meno grave di "perso", con una sola conferma. */
internal enum class PendingChange(val target: DeviceStatus) {
  /** Dismesso in buono stato: restano validi solo i dati congelati. */
  RETIRE(DeviceStatus.RETIRED),

  /** Revocato: nessun suo dato e' piu' accettato. */
  REVOKE(DeviceStatus.REVOKED),
}

/** Lo stato del dettaglio di un dispositivo. */
internal data class DeviceDetailUiState(
  val device: DeviceUi?,
  val trust: TrustStatus,
  /** Admin: puo' cambiare lo stato di un altro dispositivo approvato. */
  val canManage: Boolean,
  /** Non admin ma proprietario: puo' segnalare come perso un altro dispositivo dello stesso account. */
  val canReportOwn: Boolean,
  val lostStage: LostStage = LostStage.NONE,
  val acknowledged: Boolean = false,
  val guideDone: Set<GuideStep> = emptySet(),
  /** La segnalazione e' del proprietario (l'admin dovra' confermarla), non dell'admin. */
  val reportedByOwner: Boolean = false,
  val pendingChange: PendingChange? = null,
  val feedback: Feedback? = null,
) {
  /** Il prossimo passo della guida ancora da fare, o null se e' finita. */
  val nextGuideStep: GuideStep? get() = GuideStep.ordered.firstOrNull { it !in guideDone }
}

/**
 * Il dettaglio di un dispositivo (piano 2.4): le informazioni e, per chi puo', le azioni.
 *
 *  - **Segnala come perso**: doppia conferma ([LostStage]); poi la guida nell'ordine giusto ([GuideStep]).
 *    L'admin cambia lo stato in `LOST` nel registro; il proprietario pubblica una segnalazione firmata che
 *    l'admin converte (decisione 3a).
 *  - **Revoca** e **Dismetti**: una conferma.
 *
 * Nessuna azione parte da dati letti dal NAS: ogni scrittura nasce da un gesto locale confermato (E-S11).
 */
@HiltViewModel
internal class DeviceDetailViewModel @Inject constructor(
  handle: SavedStateHandle,
  private val registry: RegistryService,
  private val lostReporting: LostReporting,
  sessions: NasSessionManager,
  private val clock: SettingsClock,
) : ViewModel() {

  private val deviceId: String = handle[DEVICE_ID] ?: ""

  private val local = MutableStateFlow(Local())

  val state: StateFlow<DeviceDetailUiState> = combine(
    registry.status,
    registry.devices,
    sessions.state,
    local,
  ) { trust, devices, connection, local ->
    val nasNow = DeviceMapper.nasNow(clock.nowMillis(), connection)
    val device = DeviceMapper.devices(devices, nasNow).firstOrNull { it.id == deviceId }
    val rights = rightsFor(trust, devices)
    DeviceDetailUiState(
      device = device,
      trust = trust,
      canManage = rights.canManage,
      canReportOwn = rights.canReportOwn,
      lostStage = local.lostStage,
      acknowledged = local.acknowledged,
      guideDone = local.guideDone,
      reportedByOwner = local.reportedByOwner,
      pendingChange = local.pendingChange,
      feedback = local.feedback,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = DeviceDetailUiState(device = null, trust = registry.status.value, canManage = false, canReportOwn = false),
  )

  // --- segnala come perso ---------------------------------------------------------------------------

  /** Primo foglio: cosa succede. Solo se si puo' agire su questo dispositivo. */
  fun startLost() {
    val rights = rights()
    if (!(rights.canManage || rights.canReportOwn)) return
    local.update { it.copy(lostStage = LostStage.FIRST_CONFIRM, acknowledged = false, feedback = null) }
  }

  /** Dal primo al secondo foglio. Chiudere il primo non scrive niente. */
  fun continueLost() {
    local.update { if (it.lostStage == LostStage.FIRST_CONFIRM) it.copy(lostStage = LostStage.SECOND_CONFIRM, acknowledged = false) else it }
  }

  /** "Ho capito che non si puo' annullare": abilita il bottone finale. */
  fun setAcknowledged(value: Boolean) {
    local.update { if (it.lostStage == LostStage.SECOND_CONFIRM) it.copy(acknowledged = value) else it }
  }

  fun cancelLost() {
    local.update { if (it.lostStage == LostStage.FIRST_CONFIRM || it.lostStage == LostStage.SECOND_CONFIRM) it.copy(lostStage = LostStage.NONE, acknowledged = false) else it }
  }

  /**
   * La segnalazione vera: solo dal secondo foglio, solo con la presa d'atto. Un secondo tocco mentre sta
   * scrivendo non riparte (`WORKING`). Se riesce si apre la guida con il passo 1 gia' fatto.
   */
  fun confirmLost() {
    val snapshot = local.value
    val rights = rights()
    if (snapshot.lostStage != LostStage.SECOND_CONFIRM || !snapshot.acknowledged) return
    if (!(rights.canManage || rights.canReportOwn)) return
    val asOwner = !rights.canManage
    local.update { it.copy(lostStage = LostStage.WORKING) }
    viewModelScope.launch {
      val result = if (asOwner) lostReporting.reportOwnDeviceLost(deviceId) else registry.changeStatus(deviceId, DeviceStatus.LOST)
      local.update {
        if (result == AdminActionResult.Done) {
          it.copy(lostStage = LostStage.GUIDE, acknowledged = false, guideDone = setOf(GuideStep.REPORT), reportedByOwner = asOwner, feedback = null)
        } else {
          it.copy(lostStage = LostStage.NONE, acknowledged = false, feedback = result.toFeedback())
        }
      }
    }
  }

  /**
   * Segna un passo della guida come fatto. **Solo nell'ordine**: un passo si puo' segnare quando tutti i
   * precedenti lo sono (non si cambia la password prima di aver revocato il peer).
   */
  fun markGuideStepDone(step: GuideStep) {
    local.update { current ->
      if (current.lostStage != LostStage.GUIDE || step in current.guideDone) return@update current
      val previous = GuideStep.ordered.takeWhile { it != step }
      if (!current.guideDone.containsAll(previous)) current else current.copy(guideDone = current.guideDone + step)
    }
  }

  /** Riapre la guida di un dispositivo gia' segnato come perso (il passo 1 e' fatto per definizione). */
  fun openGuide() {
    val device = registry.devices.value.firstOrNull { it.id == deviceId }
    if (device?.status != DeviceStatus.LOST) return
    local.update { it.copy(lostStage = LostStage.GUIDE, guideDone = it.guideDone + GuideStep.REPORT) }
  }

  /** Chiude la guida (si puo' riaprire dal dispositivo, che ora risulta perso). */
  fun closeGuide() {
    local.update { if (it.lostStage == LostStage.GUIDE) it.copy(lostStage = LostStage.NONE) else it }
  }

  // --- revoca e dismissione -------------------------------------------------------------------------

  fun askChange(change: PendingChange) {
    if (!rights().canManage) return
    local.update { it.copy(pendingChange = change, feedback = null) }
  }

  fun cancelChange() {
    local.update { it.copy(pendingChange = null) }
  }

  fun confirmChange() {
    val change = local.value.pendingChange ?: return
    if (!rights().canManage) return
    local.update { it.copy(pendingChange = null) }
    viewModelScope.launch {
      val result = registry.changeStatus(deviceId, change.target)
      local.update { it.copy(feedback = result.toFeedback()) }
    }
  }

  fun dismissFeedback() {
    local.update { it.copy(feedback = null) }
  }

  /** Cosa puo' fare questo dispositivo su quello aperto, riletto dal registro adesso (non da uno stato in cache). */
  private fun rights(): Rights = rightsFor(registry.status.value, registry.devices.value)

  private fun rightsFor(trust: TrustStatus, devices: List<DeviceView>): Rights {
    val device = devices.firstOrNull { it.id == deviceId } ?: return Rights(canManage = false, canReportOwn = false)
    val mine = devices.firstOrNull { it.isThisDevice }
    val approved = device.status == DeviceStatus.APPROVED
    val admin = trust.isAdmin()
    return Rights(
      canManage = admin && approved && !device.isThisDevice,
      canReportOwn = !admin && approved && !device.isThisDevice && mine != null && mine.nasUser.equals(device.nasUser, ignoreCase = true),
    )
  }

  private class Rights(val canManage: Boolean, val canReportOwn: Boolean)

  private data class Local(
    val lostStage: LostStage = LostStage.NONE,
    val acknowledged: Boolean = false,
    val guideDone: Set<GuideStep> = emptySet(),
    val reportedByOwner: Boolean = false,
    val pendingChange: PendingChange? = null,
    val feedback: Feedback? = null,
  )

  companion object {
    /** Il nome dell'argomento della rotta (`DeviceDetailRoute.deviceId`). */
    const val DEVICE_ID = "deviceId"
    private const val STOP_TIMEOUT_MS = 5_000L
  }
}
