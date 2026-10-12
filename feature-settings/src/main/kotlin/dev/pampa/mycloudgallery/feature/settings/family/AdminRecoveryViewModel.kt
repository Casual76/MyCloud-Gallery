package dev.pampa.mycloudgallery.feature.settings.family

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.AdminRecovery
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.QrPayloadCodec
import dev.pampa.mycloudgallery.core.family.contract.QrPayloads
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.registry.ShareNames
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.port.NasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.ui.CodeFormat
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.NasUserRules
import dev.pampa.mycloudgallery.feature.settings.ui.messageRes
import dev.pampa.mycloudgallery.feature.settings.ui.toFeedback
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Il secondo admin (il primo resta approvato) o il recupero (il vecchio admin e' perso o da revocare). */
internal enum class RecoveryMode { SECOND_ADMIN, REPLACE_ADMIN }

internal enum class RecoveryStage { SEARCH, PICK, CODE, WORKING, DONE }

internal data class RecoveryUiState(
  val mode: RecoveryMode,
  /** C'e' un account su cui lavorare? Senza, la pagina lo dice e non propone nulla. */
  val available: Boolean,
  val adminUser: String = "",
  /**
   * La password dell'account dell'admin, solo se non e' l'account di questo dispositivo (l'area privata dell'admin si
   * apre solo con il suo account): serve a UNA ricerca e poi si dimentica. **Mai nei log**: [toString] non la stampa.
   */
  val adminPassword: String = "",
  /** L'utente NAS di questo dispositivo: se l'admin e' lui, la password e' gia' salvata e non si chiede. */
  val ownUser: String = "",
  val privateShare: String = "",
  val stage: RecoveryStage = RecoveryStage.SEARCH,
  val searching: Boolean = false,
  val kits: List<RecoveryKitRef> = emptyList(),
  val selectedKit: Int? = null,
  /** Il codice di recupero digitato, a gruppi. **Mai nei log**: [toString] non lo stampa. */
  val code: String = "",
  /** La versione del registro letta dal QR "stato della famiglia" (M7), se e' stato scansionato. */
  val stateVersion: Long? = null,
  val oldAdmin: OldAdminFate = OldAdminFate.LOST,
  val feedback: Feedback? = null,
) {
  /** L'admin indicato non e' l'account di questo dispositivo: serve la sua password (P9, contratto `findKits`). */
  val needsAdminPassword: Boolean get() = adminUser.isNotBlank() && !adminUser.equals(ownUser, ignoreCase = true)

  val canSearch: Boolean get() = available && adminUser.isNotBlank() && !searching && (!needsAdminPassword || adminPassword.isNotEmpty())

  val canRecover: Boolean
    get() = stage == RecoveryStage.CODE && selectedKit != null && code.isNotBlank()

  override fun toString(): String = "RecoveryUiState(mode=$mode, stage=$stage)"
}

/**
 * Diventare admin con il kit di recupero (piano 2.4, E-S13, proposta 15).
 *
 * Quattro mosse: (1) indicare l'account dell'admin e la sua area privata; (2) scegliere il kit tra quelli
 * trovati in `recovery/`; (3) digitare il codice (e, se c'e', scansionare lo "stato della famiglia" di un
 * dispositivo gia' approvato: senza, la versione minima del registro e' 0 e l'app lo dice); (4)
 * `AdminRecovery.recover`. Il codice e' un `CharArray` che il contratto azzera dopo l'uso.
 *
 * L'esito `WrongCode` non fa perdere niente: si puo' riscrivere. I tentativi sul codice non toccano il NAS.
 */
@HiltViewModel
internal class AdminRecoveryViewModel @Inject constructor(
  handle: SavedStateHandle,
  private val recovery: AdminRecovery,
  private val stateCodec: QrPayloadCodec<FamilyStateQr>,
  accountSettings: Optional<NasAccountSettings>,
) : ViewModel() {

  private val mode = if (handle.get<Boolean>(REPLACE) == true) RecoveryMode.REPLACE_ADMIN else RecoveryMode.SECOND_ADMIN
  private val accounts: NasAccountSettings? = accountSettings.orElse(null)

  private var familyState: FamilyStateQr? = null

  private val _state = MutableStateFlow(
    RecoveryUiState(
      mode = mode,
      available = accounts?.account?.value != null,
      ownUser = accounts?.account?.value?.user.orEmpty(),
      oldAdmin = if (mode == RecoveryMode.SECOND_ADMIN) OldAdminFate.KEEP_APPROVED else OldAdminFate.LOST,
    ),
  )
  val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

  fun setAdminUser(value: String) = edit { it.copy(adminUser = value.trim()) }

  fun setAdminPassword(value: String) = edit { it.copy(adminPassword = value) }

  fun setPrivateShare(value: String) = edit { it.copy(privateShare = value.trim()) }

  fun setCode(value: String) = edit { it.copy(code = CodeFormat.format(value, CodeFormat.RECOVERY_CHARS)) }

  /** Solo nel recupero: cosa fare del vecchio admin. Nel secondo admin resta approvato. */
  fun setOldAdminFate(fate: OldAdminFate) {
    if (mode != RecoveryMode.REPLACE_ADMIN || fate == OldAdminFate.KEEP_APPROVED) return
    edit { it.copy(oldAdmin = fate) }
  }

  private fun edit(change: (RecoveryUiState) -> RecoveryUiState) {
    _state.update {
      if (it.stage == RecoveryStage.WORKING || it.stage == RecoveryStage.DONE) it else change(it).copy(feedback = null)
    }
  }

  /** Cerca i kit nell'area privata dell'admin. L'account e' quello di questo dispositivo, con l'utente indicato. */
  fun search() {
    val current = _state.value
    val base = accounts?.account?.value
    if (base == null || current.searching || current.stage != RecoveryStage.SEARCH) return
    val share = current.privateShare.ifBlank { current.adminUser }
    val missingPassword = current.needsAdminPassword && current.adminPassword.isEmpty()
    if (!NasUserRules.isValid(current.adminUser) || !runCatching { ShareNames.check(share) }.isSuccess || missingPassword) {
      _state.update { it.copy(feedback = Feedback(R.string.recovery_error_fields_title, R.string.recovery_error_fields, FluidTone.Warning)) }
      return
    }
    // La password dell'admin vale una ricerca: si toglie subito dallo stato, il contratto azzera la sua copia.
    val password = if (current.needsAdminPassword) current.adminPassword.toCharArray() else null
    _state.update { it.copy(searching = true, feedback = null, adminPassword = "") }
    viewModelScope.launch {
      val account = NasAccount(base.address, current.adminUser, base.credentialSlot, base.pinnedGuid)
      try {
        when (val result = recovery.findKits(account, share, password)) {
          is NasResult.Ok -> _state.update {
            when {
              result.value.isEmpty() -> it.copy(
                searching = false,
                feedback = Feedback(R.string.recovery_no_kit_title, R.string.recovery_no_kit, FluidTone.Warning),
              )
              result.value.size == 1 -> it.copy(
                searching = false, stage = RecoveryStage.CODE, kits = result.value, selectedKit = 0, privateShare = share,
              )
              else -> it.copy(searching = false, stage = RecoveryStage.PICK, kits = result.value, privateShare = share)
            }
          }
          is NasResult.Err -> _state.update {
            it.copy(searching = false, feedback = Feedback(R.string.fb_failed_title, result.error.messageRes(), FluidTone.Danger))
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        _state.update { it.copy(searching = false, feedback = Feedback(R.string.fb_failed_title, R.string.problem_generic, FluidTone.Danger)) }
      } finally {
        password?.fill(CLEARED)
      }
    }
  }

  fun pickKit(index: Int) {
    _state.update {
      if (it.stage == RecoveryStage.PICK && index in it.kits.indices) it.copy(stage = RecoveryStage.CODE, selectedKit = index) else it
    }
  }

  /** Torna alla ricerca (altro account, altra area). */
  fun backToSearch() {
    _state.update { if (it.stage == RecoveryStage.PICK || it.stage == RecoveryStage.CODE) it.copy(stage = RecoveryStage.SEARCH, kits = emptyList(), selectedKit = null, code = "") else it }
  }

  /** Il QR "stato della famiglia" (`MCGS1:`) letto dallo scanner. */
  fun onStateScanned(text: String): ScanVerdict {
    if (QrPayloads.looksLikeWireGuardConfig(text)) return ScanVerdict.Reject(ScanRejection.WIREGUARD_SECRET)
    if (!text.startsWith(QrPayloads.FAMILY_STATE_PREFIX)) return ScanVerdict.Reject(ScanRejection.NOT_OURS)
    val parsed = (stateCodec.decode(text) as? Verdict.Accepted)?.value ?: return ScanVerdict.Reject(ScanRejection.MALFORMED)
    familyState = parsed
    _state.update { it.copy(stateVersion = parsed.registryVersion) }
    return ScanVerdict.Accept
  }

  fun clearScannedState() {
    familyState = null
    _state.update { it.copy(stateVersion = null) }
  }

  /** Il recupero vero. Un secondo tocco mentre lavora non riparte; il codice digitato si dimentica subito. */
  fun recover() {
    val current = _state.value
    val index = current.selectedKit ?: return
    val kit = current.kits.getOrNull(index) ?: return
    if (!current.canRecover) return
    val chars = CodeFormat.normalize(current.code, CodeFormat.RECOVERY_CHARS).toCharArray()
    _state.update { it.copy(stage = RecoveryStage.WORKING, code = "", feedback = null) }
    viewModelScope.launch {
      val outcome = try {
        recovery.recover(kit, chars, familyState, current.oldAdmin)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        RecoveryOutcome.Failed(OnboardingProblem.NAS_WRITE_FAILED)
      } finally {
        chars.fill('\u0000')
      }
      _state.update {
        when (outcome) {
          RecoveryOutcome.Recovered -> it.copy(stage = RecoveryStage.DONE)
          RecoveryOutcome.WrongCode ->
            it.copy(stage = RecoveryStage.CODE, feedback = Feedback(R.string.recovery_wrong_code_title, R.string.recovery_wrong_code, FluidTone.Danger))
          is RecoveryOutcome.Failed -> it.copy(stage = RecoveryStage.CODE, feedback = outcome.problem.toFeedback())
        }
      }
    }
  }

  override fun onCleared() {
    familyState = null
    _state.value = _state.value.copy(code = "", adminPassword = "")
  }

  companion object {
    private const val CLEARED = '\u0000'

    /** Il nome dell'argomento della rotta (`AdminRecoveryRoute.replace`). */
    const val REPLACE = "replace"
  }
}
