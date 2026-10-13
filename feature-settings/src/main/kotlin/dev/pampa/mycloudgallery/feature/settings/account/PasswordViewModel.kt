package dev.pampa.mycloudgallery.feature.settings.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.NasPasswordPolicy
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.port.NasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.messageRes
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class PasswordPhase { FORM, TESTING, DONE }

/** Le fasi del test che la pagina mostra dopo il tentativo, con il loro esito. */
internal val PasswordStages: Set<Stage> = Stage.entries.toSet() - setOf(Stage.SHARE, Stage.READ)

internal data class PasswordUiState(
  val available: Boolean,
  val user: String?,
  /** L'interruttore e' aperto: la password vecchia non vale piu' (E-R8, E-S12). */
  val blockedBy: AuthBlockReason? = null,
  /** La password digitata. **Mai nei log**: [toString] non la stampa. */
  val password: String = "",
  val visible: Boolean = false,
  /** La password nel campo e' quella proposta dall'app: va impostata sul NAS prima di provarla. */
  val generated: Boolean = false,
  val phase: PasswordPhase = PasswordPhase.FORM,
  val stages: List<StageResult> = emptyList(),
  val feedback: Feedback? = null,
) {
  /**
   * Revisione 2, F1: che cosa manca alla password per reggere un attacco offline (`NasPasswordPolicy`), o null se va
   * bene o il campo e' vuoto. Una password debole non si prova nemmeno.
   */
  val weakness: NasPasswordPolicy.Weakness?
    get() {
      if (password.isEmpty()) return null
      val chars = password.toCharArray()
      return try {
        NasPasswordPolicy.check(user, chars)
      } finally {
        chars.fill('\u0000')
      }
    }

  val canSubmit: Boolean
    get() = available && user != null && password.isNotEmpty() && phase == PasswordPhase.FORM && weakness == null

  override fun toString(): String = "PasswordUiState(phase=$phase)"
}

/**
 * Cambiare la password del NAS (E-R8, piano 2.1): **un solo tentativo**, in questo ordine.
 *
 *  - **Interruttore chiuso** (la password vecchia vale ancora): si prova prima, con le credenziali digitate;
 *    solo se il test riesce la password si salva nel Keystore e si avvisa l'interruttore. Una password
 *    sbagliata non sostituisce quella buona.
 *  - **Interruttore aperto** (la vecchia non vale piu', nessuna autenticazione parte da sola): la nuova si
 *    salva, l'interruttore si chiude (`onNewPasswordSaved`) e si fa **un solo** tentativo. Se fallisce
 *    l'interruttore si riapre e si riparte da una password nuova: mai un secondo tentativo con la stessa.
 *
 * La password sta in memoria solo mentre si digita e si dimentica a tentativo finito; le credenziali si chiudono
 * (azzerate) appena usate.
 */
@HiltViewModel
internal class PasswordViewModel @Inject constructor(
  accountSettings: Optional<NasAccountSettings>,
  private val breaker: AuthCircuitBreaker,
  private val tester: ConnectionTester,
  private val sessions: NasSessionManager,
) : ViewModel() {

  private val accounts: NasAccountSettings? = accountSettings.orElse(null)

  private val _state = MutableStateFlow(initial())
  val state: StateFlow<PasswordUiState> = _state.asStateFlow()

  private fun initial() = PasswordUiState(
    available = accounts?.account?.value != null,
    user = accounts?.account?.value?.user,
    blockedBy = (breaker.state.value as? BreakerState.Open)?.reason,
  )

  fun setPassword(value: String) {
    _state.update { if (it.phase == PasswordPhase.FORM) it.copy(password = value, generated = false, feedback = null) else it }
  }

  /** Revisione 2, F1: propone una password forte (16 caratteri a caso) da impostare sul NAS, e la mostra. */
  fun generate() {
    if (_state.value.phase != PasswordPhase.FORM) return
    val chars = NasPasswordPolicy.generate()
    try {
      val proposed = String(chars)
      _state.update { it.copy(password = proposed, visible = true, generated = true, feedback = null) }
    } finally {
      chars.fill('\u0000')
    }
  }

  fun toggleVisible() {
    _state.update { it.copy(visible = !it.visible) }
  }

  /** Un altro tentativo con una password nuova, dopo un fallimento. */
  fun retry() {
    _state.update { initial().copy() }
  }

  fun submit() {
    val current = _state.value
    val account = accounts?.account?.value
    if (!current.canSubmit || account == null) return
    val chars = current.password.toCharArray()
    _state.update { it.copy(phase = PasswordPhase.TESTING, password = "", stages = emptyList(), feedback = null) }
    viewModelScope.launch {
      try {
        val credentials = NasCredentials(account.user, chars)
        try {
          run(account, credentials)
        } finally {
          credentials.close()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: IllegalArgumentException) {
        // Password che il Keystore non accetta (vuota, troppo lunga): non e' mai arrivata al NAS.
        _state.update { initial().copy(feedback = Feedback(R.string.password_invalid_title, R.string.password_invalid, FluidTone.Warning)) }
      } catch (_: Exception) {
        _state.update { initial().copy(feedback = Feedback(R.string.fb_failed_title, R.string.problem_generic, FluidTone.Danger)) }
      } finally {
        chars.fill('\u0000')
      }
    }
  }

  private suspend fun run(account: NasAccount, credentials: NasCredentials) {
    val target = TestTarget(address = account.address, user = account.user, share = null, expectedGuid = account.pinnedGuid)
    val wasBlocked = breaker.state.value is BreakerState.Open
    if (wasBlocked) {
      // Nessuna autenticazione parte con l'interruttore aperto: prima si salva la password nuova e lo si chiude.
      if (!saveAndRearm(credentials)) return
      finish(tester.run(target, credentials, PasswordStages), saved = true)
    } else {
      val report = tester.run(target, credentials, PasswordStages)
      if (report.passed) {
        if (!saveAndRearm(credentials)) return
      }
      finish(report, saved = report.passed)
    }
  }

  /** Salva nel Keystore, poi avvisa l'interruttore e chiude le sessioni vecchie. Falso (con messaggio) se non salva. */
  private suspend fun saveAndRearm(credentials: NasCredentials): Boolean {
    val port = accounts ?: return false
    when (port.savePassword(credentials)) {
      is SaveResult.Saved -> Unit
      SaveResult.HardwareKeyUnavailable -> {
        _state.update { initial().copy(feedback = Feedback(R.string.password_no_keystore_title, R.string.password_no_keystore, FluidTone.Danger)) }
        return false
      }
      SaveResult.Unavailable -> {
        _state.update { initial().copy(feedback = Feedback(R.string.password_not_saved_title, R.string.password_not_saved, FluidTone.Warning)) }
        return false
      }
    }
    breaker.onNewPasswordSaved()
    sessions.invalidate(InvalidateReason.CREDENTIALS_CHANGED)
    return true
  }

  private fun finish(report: ConnectionReport, saved: Boolean) {
    val failure = report.firstFailure
    _state.update {
      if (report.passed && saved) {
        it.copy(phase = PasswordPhase.DONE, stages = report.stages, feedback = null)
      } else {
        it.copy(
          phase = PasswordPhase.FORM,
          stages = report.stages,
          blockedBy = (breaker.state.value as? BreakerState.Open)?.reason,
          feedback = Feedback(
            title = R.string.password_failed_title,
            message = failure?.error?.messageRes() ?: R.string.problem_generic,
            tone = FluidTone.Danger,
          ),
        )
      }
    }
  }

  override fun onCleared() {
    _state.update { it.copy(password = "") }
  }
}
