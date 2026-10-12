package dev.pampa.mycloudgallery.feature.settings.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.DeviceKeyQr
import dev.pampa.mycloudgallery.core.family.contract.QrPayloadCodec
import dev.pampa.mycloudgallery.core.family.contract.QrPayloads
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.feature.settings.port.SettingsClock
import dev.pampa.mycloudgallery.feature.settings.ui.CodeFormat
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.toFeedback
import dev.pampa.mycloudgallery.feature.settings.R
import dev.antigravity.fluidengine.ui.theme.FluidTone
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Come l'admin dimostra di avere davanti il dispositivo nuovo (§2.2): il codice digitato o la chiave scansionata. */
internal enum class ProofMode { CODE, SCAN }

/** Cosa ha letto lo scanner: niente, la chiave di questa richiesta, o la chiave di un altro dispositivo. */
internal enum class ScanState { NONE, MATCHING, WRONG_DEVICE }

internal enum class ApprovalPhase { EDITING, SUBMITTING, DONE }

internal data class ApprovalUiState(
  /** La richiesta, o null se non c'e' piu' (approvata da un altro admin, o sparita). */
  val request: RequestUi?,
  val isAdmin: Boolean,
  val mode: ProofMode = ProofMode.CODE,
  /** Il codice digitato, gia' a gruppi di quattro. */
  val code: String = "",
  val scan: ScanState = ScanState.NONE,
  /** L'admin ha confermato che l'account NAS dichiarato e' quello giusto (B6). */
  val accountConfirmed: Boolean = false,
  val phase: ApprovalPhase = ApprovalPhase.EDITING,
  val feedback: Feedback? = null,
) {
  private val proofReady: Boolean
    get() = when (mode) {
      ProofMode.CODE -> CodeFormat.normalize(code, CodeFormat.APPROVAL_CHARS).length == CodeFormat.APPROVAL_CHARS
      ProofMode.SCAN -> scan == ScanState.MATCHING
    }

  /** Si puo' approvare: admin, finestra aperta, account confermato, prova pronta, nessun invio in corso. */
  val canSubmit: Boolean
    get() = isAdmin && request?.windowOpen == true && accountConfirmed && proofReady && phase == ApprovalPhase.EDITING
}

/**
 * L'approvazione di un dispositivo nuovo (piano 2.3, `docs/fase2-progetto.md` §2.2, lato admin).
 *
 * L'ordine non si cambia: (1) l'admin legge chi chiede e **conferma l'account NAS dichiarato** (lo scrive il
 * richiedente: non e' una prova); (2) dimostra di avere davanti il dispositivo, **digitando il codice** che
 * il dispositivo nuovo mostra oppure **scansionandone il QR** (`MCGK1:`); (3) il telefono chiede la sua
 * credenziale di sistema; (4) solo allora `RegistryService.approve`.
 *
 * Chiudere la pagina non approva niente: un dispositivo senza un'approvazione esplicita resta in sola lettura.
 */
@HiltViewModel
internal class ApprovalViewModel @Inject constructor(
  handle: SavedStateHandle,
  private val registry: RegistryService,
  private val deviceKeyCodec: QrPayloadCodec<DeviceKeyQr>,
  sessions: NasSessionManager,
  private val clock: SettingsClock,
) : ViewModel() {

  private val requestId: String = handle[REQUEST_ID] ?: ""

  private val local = MutableStateFlow(Local())
  private var scannedKey: EcPublicKey? = null

  val state: StateFlow<ApprovalUiState> = combine(
    registry.status,
    registry.devices,
    registry.pendingRequests,
    sessions.state,
    local,
  ) { trust, devices, requests, connection, local ->
    val nasNow = DeviceMapper.nasNow(clock.nowMillis(), connection)
    ApprovalUiState(
      request = DeviceMapper.requests(requests, devices, nasNow).firstOrNull { it.id == requestId },
      isAdmin = trust.isAdmin(),
      mode = local.mode,
      code = local.code,
      scan = local.scan,
      accountConfirmed = local.accountConfirmed,
      phase = local.phase,
      feedback = local.feedback,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = ApprovalUiState(request = null, isAdmin = registry.status.value.isAdmin()),
  )

  fun setMode(mode: ProofMode) {
    local.update { if (it.phase == ApprovalPhase.EDITING) it.copy(mode = mode, feedback = null) else it }
  }

  fun setCode(typed: String) {
    local.update {
      if (it.phase != ApprovalPhase.EDITING) it else it.copy(code = CodeFormat.format(typed, CodeFormat.APPROVAL_CHARS), feedback = null)
    }
  }

  fun setAccountConfirmed(confirmed: Boolean) {
    local.update { if (it.phase == ApprovalPhase.EDITING) it.copy(accountConfirmed = confirmed) else it }
  }

  /**
   * Il testo letto dallo scanner. Un QR che non e' `MCGK1:` o non si legge viene rifiutato con il suo motivo
   * (lo scanner lo mostra e continua a cercare); un QR valido ma di **un altro dispositivo** si accetta e si
   * dice a parole ([ScanState.WRONG_DEVICE]) con il tasto per scansionare di nuovo.
   */
  fun onScanned(text: String): ScanVerdict {
    if (QrPayloads.looksLikeWireGuardConfig(text)) return ScanVerdict.Reject(ScanRejection.WIREGUARD_SECRET)
    if (!text.startsWith(QrPayloads.DEVICE_KEY_PREFIX)) return ScanVerdict.Reject(ScanRejection.NOT_OURS)
    val qr = (deviceKeyCodec.decode(text) as? Verdict.Accepted)?.value ?: return ScanVerdict.Reject(ScanRejection.MALFORMED)
    val key = try {
      EcPublicKey.fromSpki(qr.publicKeySpki)
    } catch (_: Exception) {
      return ScanVerdict.Reject(ScanRejection.MALFORMED)
    }
    scannedKey = key
    local.update { it.copy(scan = if (qr.deviceId == requestId) ScanState.MATCHING else ScanState.WRONG_DEVICE, feedback = null) }
    return ScanVerdict.Accept
  }

  fun rescan() {
    scannedKey = null
    local.update { it.copy(scan = ScanState.NONE) }
  }

  /**
   * Approva. [authenticate] mostra la credenziale di sistema del telefono (la fornisce la pagina: serve
   * un'Activity); senza successo non parte niente. Ogni esito di `approve` ha il suo messaggio.
   */
  suspend fun approve(authenticate: suspend () -> AuthResult) {
    if (!canSubmitNow()) return
    val proof = proofNow() ?: return
    local.update { it.copy(phase = ApprovalPhase.SUBMITTING, feedback = null) }
    when (val auth = authenticate()) {
      AuthResult.Success -> Unit
      AuthResult.Cancelled, AuthResult.Interrupted -> {
        local.update { it.copy(phase = ApprovalPhase.EDITING) }
        return
      }
      else -> {
        local.update { it.copy(phase = ApprovalPhase.EDITING, feedback = authFeedback(auth)) }
        return
      }
    }
    val result = registry.approve(requestId, proof)
    if (result == AdminActionResult.NewerExists) registry.refresh(Purpose.INTERACTIVE)
    local.update {
      if (result == AdminActionResult.Done) {
        it.copy(phase = ApprovalPhase.DONE, code = "", feedback = null)
      } else {
        it.copy(phase = ApprovalPhase.EDITING, feedback = result.toFeedback())
      }
    }
  }

  /** Le condizioni di `canSubmit`, rilette adesso (lo stato in cache puo' non avere ancora chi lo osserva). */
  private fun canSubmitNow(): Boolean {
    val l = local.value
    val requestOpen = registry.pendingRequests.value.firstOrNull { it.id == requestId }?.windowOpen == true
    val proofReady = when (l.mode) {
      ProofMode.CODE -> CodeFormat.normalize(l.code, CodeFormat.APPROVAL_CHARS).length == CodeFormat.APPROVAL_CHARS
      ProofMode.SCAN -> l.scan == ScanState.MATCHING
    }
    return registry.status.value.isAdmin() && requestOpen && l.accountConfirmed && proofReady && l.phase == ApprovalPhase.EDITING
  }

  private fun proofNow(): ApprovalProof? {
    val l = local.value
    return when (l.mode) {
      ProofMode.CODE -> ApprovalProof.TypedCode(l.code)
      ProofMode.SCAN -> scannedKey?.let { ApprovalProof.ScannedKey(it) }
    }
  }

  private fun authFeedback(result: AuthResult): Feedback = when (result) {
    is AuthResult.LockedOut -> Feedback(R.string.fb_auth_locked_title, R.string.fb_auth_locked, FluidTone.Warning)
    is AuthResult.NotAvailable -> Feedback(R.string.fb_auth_unavailable_title, R.string.fb_auth_unavailable, FluidTone.Warning)
    else -> Feedback(R.string.fb_auth_error_title, R.string.fb_auth_error, FluidTone.Warning)
  }

  private data class Local(
    val mode: ProofMode = ProofMode.CODE,
    val code: String = "",
    val scan: ScanState = ScanState.NONE,
    val accountConfirmed: Boolean = false,
    val phase: ApprovalPhase = ApprovalPhase.EDITING,
    val feedback: Feedback? = null,
  )

  companion object {
    /** Il nome dell'argomento della rotta (`ApprovalRoute.requestId`). */
    const val REQUEST_ID = "requestId"
    private const val STOP_TIMEOUT_MS = 5_000L
  }
}
