package dev.pampa.mycloudgallery.feature.settings.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.QrPayloadCodec
import dev.pampa.mycloudgallery.core.family.contract.QrPayloads
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.registry.ShareNames
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceMapper
import dev.pampa.mycloudgallery.feature.settings.port.SettingsClock
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.NasUserRules
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal enum class InvitePhase { FORM, CREATING, SHOWING }

/** I preset fra cui l'admin sceglie per il familiare (nessuno = quello della famiglia). `CUSTOM` no: e' per-attivita'. */
internal val InvitablePresets: List<Preset> = Preset.entries.filter { it != Preset.CUSTOM }

internal data class InviteUiState(
  val isAdmin: Boolean,
  val user: String = "",
  val deviceName: String = "",
  /** Vuota = come l'utente NAS. */
  val privateShare: String = "",
  val preset: Preset? = null,
  @androidx.annotation.StringRes val userError: Int? = null,
  @androidx.annotation.StringRes val nameError: Int? = null,
  @androidx.annotation.StringRes val shareError: Int? = null,
  val phase: InvitePhase = InvitePhase.FORM,
  /** Il testo del QR (`MCG1:...`). **Mai nei log**: [toString] non lo stampa. */
  val qrText: String? = null,
  val summary: InviteSummary? = null,
  val feedback: Feedback? = null,
) {
  override fun toString(): String = "InviteUiState(phase=$phase)"
}

/** Cio' che l'admin rilegge accanto al QR: per chi e', dove va, quanto vale. */
internal data class InviteSummary(
  val user: String,
  val deviceName: String,
  val privateShare: String,
  val hoursLeft: Int,
)

/**
 * "Configura un familiare" (piano 2.3, §2.2, lato admin): utente NAS, nome del dispositivo o profilo, area
 * privata e preset; poi il **QR d'invito**, che si mostra sullo schermo e non si invia mai in chat.
 *
 * Il QR non contiene password (C8): senza la password dell'account e senza l'approvazione dell'admin un invito
 * fotografato non serve a niente (E-S8). Vale 24 ore secondo l'ora del NAS e apre la "finestra d'invito"
 * (`RegistryService.createInvite` la apre: i controlli diventano frequenti, E-R15).
 */
@HiltViewModel
internal class InviteViewModel @Inject constructor(
  private val registry: RegistryService,
  private val inviteCodec: QrPayloadCodec<FamilyInvite>,
  private val sessions: NasSessionManager,
  private val clock: SettingsClock,
) : ViewModel() {

  private val _state = MutableStateFlow(InviteUiState(isAdmin = registry.status.value.isAdminStatus()))
  val state: StateFlow<InviteUiState> = _state.asStateFlow()

  fun setUser(value: String) = edit { it.copy(user = value.trim(), userError = null) }

  fun setDeviceName(value: String) = edit { it.copy(deviceName = value, nameError = null) }

  fun setPrivateShare(value: String) = edit { it.copy(privateShare = value.trim(), shareError = null) }

  fun setPreset(value: Preset?) = edit { it.copy(preset = value) }

  private fun edit(change: (InviteUiState) -> InviteUiState) {
    _state.update { if (it.phase == InvitePhase.FORM) change(it).copy(feedback = null) else it }
  }

  /** Controlla i campi e, se vanno bene, crea l'invito. Un secondo tocco mentre lavora non riparte. */
  fun create() {
    val current = _state.value
    if (current.phase != InvitePhase.FORM) return
    if (!registry.status.value.isAdminStatus()) {
      _state.update { it.copy(isAdmin = false) }
      return
    }
    val share = current.privateShare.ifBlank { current.user }
    val userError = when {
      !NasUserRules.isValid(current.user) -> R.string.invite_error_user
      NasUserRules.isAdminAccount(current.user) -> R.string.invite_error_admin
      else -> null
    }
    val nameError = if (isDisplayText(current.deviceName)) null else R.string.invite_error_name
    val shareError = if (userError == null && !isShareName(share)) R.string.invite_error_share else null
    if (userError != null || nameError != null || shareError != null) {
      _state.update { it.copy(userError = userError, nameError = nameError, shareError = shareError) }
      return
    }
    _state.update { it.copy(phase = InvitePhase.CREATING, feedback = null) }
    val params = InviteParams(
      nasUser = current.user,
      deviceName = current.deviceName,
      privateShare = share,
      presetName = current.preset?.name,
    )
    viewModelScope.launch {
      try {
        val invite = registry.createInvite(params)
        val text = inviteCodec.encode(invite)
        check(text.length <= QrPayloads.MAX_CHARS) { "invito troppo lungo" }
        val nasNow = DeviceMapper.nasNow(clock.nowMillis(), sessions.state.value)
        val hours = (invite.expiresAtNas - nasNow).coerceAtLeast(0L).milliseconds.inWholeHours.toInt().coerceAtLeast(1)
        _state.update {
          it.copy(
            phase = InvitePhase.SHOWING,
            qrText = text,
            summary = InviteSummary(current.user, current.deviceName, share, hours),
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Mai il messaggio dell'eccezione: potrebbe contenere dati dell'invito.
        _state.update { it.copy(phase = InvitePhase.FORM, feedback = Feedback(R.string.invite_failed_title, R.string.invite_failed, FluidTone.Danger)) }
      }
    }
  }

  /** Dimentica il QR (la schermata torna al modulo, vuoto): un invito non si lascia in memoria piu' del necessario. */
  fun reset() {
    _state.value = InviteUiState(isAdmin = registry.status.value.isAdminStatus())
  }

  override fun onCleared() {
    _state.value = InviteUiState(isAdmin = false)
  }

  private fun TrustStatus.isAdminStatus(): Boolean = this is TrustStatus.Approved && admin

  private fun isDisplayText(text: String): Boolean = runCatching { TextRules.displayText(text, MAX_DISPLAY_CHARS) }.isSuccess

  private fun isShareName(text: String): Boolean = runCatching { ShareNames.check(text) }.isSuccess

  private companion object {
    /** `TrustLimits.MAX_DISPLAY_TEXT_CHARS`. */
    const val MAX_DISPLAY_CHARS = 64
  }
}
