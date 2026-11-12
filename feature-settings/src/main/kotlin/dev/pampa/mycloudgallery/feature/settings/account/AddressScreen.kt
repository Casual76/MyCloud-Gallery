package dev.pampa.mycloudgallery.feature.settings.account

import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import androidx.compose.material.icons.rounded.CloudOff
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.network.SaveScope
import dev.pampa.mycloudgallery.feature.settings.port.NasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.ui.ConfirmSheet
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.messageRes
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue

/** Le fasi del cambio d'indirizzo: digitare, verificare l'identita', confermare, fatto. */
internal enum class AddressPhase { FORM, VERIFYING, VERIFIED, MISMATCH, CONFIRM, DONE }

internal data class AddressUiState(
  val available: Boolean,
  val currentIp: String?,
  val typed: String = "",
  val phase: AddressPhase = AddressPhase.FORM,
  val feedback: Feedback? = null,
  /** Questo dispositivo e' l'admin: l'indirizzo nuovo vale anche per i nuovi inviti (RE-5). */
  val admin: Boolean = false,
  /** Dopo la conferma: fin dove vale il cambio (RE-5). */
  val saved: SaveScope? = null,
) {
  val parsed: NasAddress? get() = NasAddress.parseOrNull(typed)

  val canVerify: Boolean get() = available && parsed != null && phase == AddressPhase.FORM
}

/**
 * Cambiare l'indirizzo del NAS (E-R7, C4): **mai in automatico**. L'utente digita l'indirizzo nuovo; l'app
 * controlla senza autenticare (percorso, connessione, protocollo, e il GUID del NAS, che deve essere quello
 * gia' memorizzato: e' l'unica conferma possibile prima di dare la password); solo se risponde lo stesso NAS e'
 * l'utente a confermare in un foglio.
 *
 * Se il GUID e' diverso nessuna password parte, e non si cambia niente.
 */
@HiltViewModel
internal class AddressViewModel @Inject constructor(
  accountSettings: Optional<NasAccountSettings>,
  private val tester: ConnectionTester,
  private val sessions: NasSessionManager,
  private val registry: RegistryService,
) : ViewModel() {

  private val accounts: NasAccountSettings? = accountSettings.orElse(null)

  private val _state = MutableStateFlow(
    AddressUiState(
      available = accounts?.account?.value != null,
      currentIp = accounts?.account?.value?.address?.ip,
      admin = registry.status.value.isAdmin(),
    ),
  )
  val state: StateFlow<AddressUiState> = _state.asStateFlow()

  fun setTyped(value: String) {
    _state.update { if (it.phase == AddressPhase.FORM) it.copy(typed = value.trim(), feedback = null) else it }
  }

  /** Verifica l'identita' del NAS al nuovo indirizzo. Nessuna password, nessuna autenticazione. */
  fun verify() {
    val account = accounts?.account?.value ?: return
    val current = _state.value
    val address = current.parsed ?: return
    if (current.phase != AddressPhase.FORM) return
    _state.update { it.copy(phase = AddressPhase.VERIFYING, feedback = null) }
    viewModelScope.launch {
      try {
        val report = tester.run(
          target = TestTarget(address = address, user = null, share = null, expectedGuid = account.pinnedGuid),
          credentials = null,
          stages = setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID),
        )
        val failure = report.firstFailure
        _state.update {
          when {
            failure == null && report.passed -> it.copy(phase = AddressPhase.VERIFIED)
            failure?.error == NasError.IdentityMismatch || failure?.stage == Stage.GUID -> it.copy(phase = AddressPhase.MISMATCH)
            else -> it.copy(
              phase = AddressPhase.FORM,
              feedback = Feedback(R.string.address_failed_title, failure?.error?.messageRes() ?: R.string.problem_generic, FluidTone.Danger),
            )
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        _state.update { it.copy(phase = AddressPhase.FORM, feedback = Feedback(R.string.address_failed_title, R.string.problem_generic, FluidTone.Danger)) }
      }
    }
  }

  /** Apre il foglio di conferma (solo dopo una verifica riuscita). */
  fun askConfirm() {
    _state.update { if (it.phase == AddressPhase.VERIFIED) it.copy(phase = AddressPhase.CONFIRM) else it }
  }

  fun cancelConfirm() {
    _state.update { if (it.phase == AddressPhase.CONFIRM) it.copy(phase = AddressPhase.VERIFIED) else it }
  }

  /** Torna al modulo (indirizzo sbagliato, NAS diverso). */
  fun edit() {
    _state.update { if (it.phase != AddressPhase.DONE) it.copy(phase = AddressPhase.FORM, feedback = null) else it }
  }

  /**
   * La conferma: l'indirizzo cambia su questo telefono, le sessioni vecchie si chiudono e, se e' l'admin, l'indirizzo entra anche nella
   * configurazione **firmata** della famiglia (RE-5): da li' nasce l'indirizzo dei nuovi inviti, che prima restava quello vecchio. Chi non
   * e' admin lo cambia solo per se', e la pagina lo dice. Prima si cambia qui (la firma si scrive sul NAS al nuovo indirizzo).
   */
  fun confirm() {
    val address = _state.value.parsed ?: return
    if (_state.value.phase != AddressPhase.CONFIRM) return
    val port = accounts ?: return
    viewModelScope.launch {
      try {
        port.updateAddress(address)
        sessions.invalidate(InvalidateReason.USER_REQUEST)
        val scope = shareWithFamily(address.ip)
        _state.update { it.copy(phase = AddressPhase.DONE, currentIp = address.ip, saved = scope) }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        _state.update { it.copy(phase = AddressPhase.VERIFIED, feedback = Feedback(R.string.address_failed_title, R.string.problem_generic, FluidTone.Danger)) }
      }
    }
  }

  /** "Riprova" dopo una firma non riuscita: l'indirizzo e' gia' cambiato qui, si ripete solo la parte della famiglia. */
  fun retryShare() {
    val ip = _state.value.currentIp ?: return
    if (_state.value.saved != SaveScope.FAMILY_FAILED) return
    viewModelScope.launch {
      try {
        val scope = shareWithFamily(ip)
        _state.update { it.copy(saved = scope) }
      } catch (e: CancellationException) {
        throw e
      }
    }
  }

  private suspend fun shareWithFamily(ip: String): SaveScope {
    val result = try {
      registry.updateNasAddress(ip)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      AdminActionResult.Failed(NasError.Unexpected(null))
    }
    return when (result) {
      AdminActionResult.Done -> SaveScope.FAMILY
      AdminActionResult.NotAdmin -> SaveScope.THIS_PHONE
      else -> {
        GalleryLog.d(TAG) { "indirizzo del NAS non firmato: ${result.javaClass.simpleName}" }
        SaveScope.FAMILY_FAILED
      }
    }
  }

  private fun TrustStatus.isAdmin(): Boolean = this is TrustStatus.Approved && admin

  private companion object {
    const val TAG = "AddressViewModel"
  }
}

internal class AddressActions(
  val onBack: () -> Unit,
  val onTyped: (String) -> Unit,
  val onVerify: () -> Unit,
  val onAskConfirm: () -> Unit,
  val onCancelConfirm: () -> Unit,
  val onEdit: () -> Unit,
  val onConfirm: () -> Unit,
  val onRetryShare: () -> Unit = {},
) {
  companion object {
    val None = AddressActions({}, {}, {}, {}, {}, {}, {})
  }
}

@Composable
internal fun AddressRoute(onBack: () -> Unit) {
  val viewModel: AddressViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  AddressScreen(
    state = state,
    actions = AddressActions(
      onBack = onBack,
      onTyped = viewModel::setTyped,
      onVerify = viewModel::verify,
      onAskConfirm = viewModel::askConfirm,
      onCancelConfirm = viewModel::cancelConfirm,
      onEdit = viewModel::edit,
      onConfirm = viewModel::confirm,
      onRetryShare = viewModel::retryShare,
    ),
  )
}

@Composable
internal fun AddressScreen(state: AddressUiState, actions: AddressActions) {
  FluidScreen(
    title = stringResource(R.string.address_title),
    onBack = actions.onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Secondary, motif = FluidHeroMotif.Glow),
  ) {
    if (!state.available) {
      item {
        // Giro 2 della fase I (CS-17): la forma unica dello stato vuoto (figura e titolo); la frase dietro la «i».
        GalleryFigureState(
          icon = Icons.Rounded.CloudOff,
          title = stringResource(R.string.account_unavailable_title),
          tone = GalleryIllustrationTone.Caution,
          fill = false,
          action = { SettingsInfo(label = stringResource(R.string.settings_state_why), text = stringResource(R.string.account_unavailable_detail)) },
        )
      }
      return@FluidScreen
    }

    if (state.phase == AddressPhase.DONE) {
      item {
        FluidHero(
          tone = FluidHeroTone.Primary,
          motif = FluidHeroMotif.Dots,
          eyebrow = stringResource(R.string.address_eyebrow),
          value = stringResource(R.string.address_done_value),
          title = stringResource(R.string.address_done_title),
          description = stringResource(R.string.address_done_detail),
          icon = Icons.Rounded.Check,
        )
      }
      state.saved?.let { saved ->
        item {
          Note(
            title = stringResource(R.string.address_scope_title),
            message = stringResource(
              when (saved) {
                SaveScope.FAMILY -> R.string.address_scope_family
                SaveScope.THIS_PHONE -> R.string.address_scope_phone
                SaveScope.FAMILY_FAILED -> R.string.address_scope_failed
              },
            ),
            tone = if (saved == SaveScope.FAMILY_FAILED) FluidTone.Warning else FluidTone.Info,
          )
        }
        if (saved == SaveScope.FAMILY_FAILED) {
          item {
            FluidButton(
              text = stringResource(R.string.address_retry_share),
              onClick = actions.onRetryShare,
              style = FluidButtonStyle.Tinted,
              size = FluidButtonSize.Large,
              fillWidth = true,
            )
          }
        }
      }
      item {
        FluidButton(
          text = stringResource(R.string.address_done_back),
          onClick = actions.onBack,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
      return@FluidScreen
    }

    item {
      FluidHero(
        tone = FluidHeroTone.PrimaryToSecondary,
        motif = FluidHeroMotif.Ripples,
        eyebrow = stringResource(R.string.address_eyebrow),
        value = state.currentIp.orEmpty(),
        title = stringResource(R.string.address_hero_title),
        description = stringResource(R.string.address_hero_detail),
        icon = Icons.Rounded.Dns,
      )
    }

    if (state.phase == AddressPhase.MISMATCH) {
      item {
        Note(
          title = stringResource(R.string.address_mismatch_title),
          message = stringResource(R.string.address_mismatch_message),
          tone = FluidTone.Danger,
        )
      }
      item {
        FluidButton(
          text = stringResource(R.string.address_edit),
          onClick = actions.onEdit,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
    } else {
      state.feedback?.let { item { FeedbackNote(it) } }
      item { GallerySectionHeader(title = stringResource(R.string.address_section_new)) }
      item {
        FluidCard {
          FluidTextField(
            value = state.typed,
            onValueChange = actions.onTyped,
            label = stringResource(R.string.address_field),
            placeholder = stringResource(R.string.address_field_hint),
            enabled = state.phase == AddressPhase.FORM,
            isError = state.typed.isNotEmpty() && state.parsed == null,
            supportingText = stringResource(
              if (state.typed.isNotEmpty() && state.parsed == null) R.string.address_field_error else R.string.address_field_help,
            ),
            keyboardOptions = KeyboardOptions(
              capitalization = KeyboardCapitalization.None,
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Uri,
              imeAction = ImeAction.Done,
            ),
          )
          if (state.phase == AddressPhase.VERIFIED || state.phase == AddressPhase.CONFIRM) {
            Note(
              title = stringResource(R.string.address_verified_title),
              message = stringResource(R.string.address_verified_message),
              tone = FluidTone.Success,
            )
            FluidButton(
              text = stringResource(R.string.address_confirm_open),
              onClick = actions.onAskConfirm,
              size = FluidButtonSize.Large,
              fillWidth = true,
            )
            FluidButton(
              text = stringResource(R.string.address_edit),
              onClick = actions.onEdit,
              style = FluidButtonStyle.Tinted,
              size = FluidButtonSize.Large,
              fillWidth = true,
            )
          } else {
            FluidButton(
              text = stringResource(R.string.address_verify),
              onClick = actions.onVerify,
              size = FluidButtonSize.Large,
              fillWidth = true,
              enabled = state.canVerify,
              loading = state.phase == AddressPhase.VERIFYING,
            )
          }
        }
      }
      item { FluidSectionFootnote(text = stringResource(if (state.admin) R.string.address_footnote_admin else R.string.address_footnote)) }
    }
  }

  ConfirmSheet(
    visible = state.phase == AddressPhase.CONFIRM,
    onDismiss = actions.onCancelConfirm,
    icon = Icons.Rounded.SwapHoriz,
    tone = GallerySheetTone.Caution,
    title = stringResource(R.string.address_sheet_title),
    message = stringResource(if (state.admin) R.string.address_sheet_message_admin else R.string.address_sheet_message),
    confirmLabel = stringResource(R.string.address_sheet_confirm),
    confirmStyle = FluidButtonStyle.Filled,
    onConfirm = actions.onConfirm,
    body = {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.address_sheet_old),
          subtitle = "",
          disclosure = false,
          leading = { RowTile(Icons.Rounded.Dns) },
          leadingTile = false,
          badge = { FluidRowValue(text = state.currentIp.orEmpty()) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.address_sheet_new),
          subtitle = "",
          disclosure = false,
          tone = FluidTone.Primary,
          leading = { RowTile(Icons.Rounded.SwapHoriz) },
          leadingTile = false,
          badge = { FluidRowValue(text = state.typed) },
        )
      }
    },
  )
}
