package dev.pampa.mycloudgallery.feature.settings.family

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.ConfirmSheet
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.toFeedback
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class RejoinStage { INTRO, CONFIRM, WORKING, DONE }

internal data class RejoinUiState(
  /** Questo dispositivo tiene la chiave di famiglia: rientrando la perde (la pagina lo dice prima). */
  val isAdmin: Boolean,
  val stage: RejoinStage = RejoinStage.INTRO,
  val feedback: Feedback? = null,
)

/**
 * "Rientra con un QR nuovo" (verifica funzionale, ON-5): dopo un reset della famiglia gli altri dispositivi restano nella
 * famiglia vecchia e non avevano un posto dove scansionare il QR nuovo. Qui il dispositivo esce dalla famiglia
 * (`OnboardingCoordinator.startOver`: account, password, stato fidato e chiavi; non le foto ne' i dati locali) e la radice
 * lo riporta al benvenuto, dove si entra con il QR. Parte solo dopo la conferma nel foglio.
 */
@HiltViewModel
internal class FamilyRejoinViewModel @Inject constructor(
  registry: RegistryService,
  private val coordinator: OnboardingCoordinator,
) : ViewModel() {

  private val _state = MutableStateFlow(RejoinUiState(isAdmin = registry.status.value.let { it is TrustStatus.Approved && it.admin }))
  val state: StateFlow<RejoinUiState> = _state.asStateFlow()

  fun ask() = _state.update { if (it.stage == RejoinStage.INTRO) it.copy(stage = RejoinStage.CONFIRM, feedback = null) else it }

  fun cancel() = _state.update { if (it.stage == RejoinStage.CONFIRM) it.copy(stage = RejoinStage.INTRO) else it }

  /** La conferma del foglio. Un secondo tocco durante il lavoro non riparte. */
  fun confirm() {
    if (_state.value.stage != RejoinStage.CONFIRM) return
    _state.update { it.copy(stage = RejoinStage.WORKING) }
    viewModelScope.launch {
      val result = try {
        coordinator.startOver()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
      _state.update {
        when (result) {
          // La radice vede "mai configurato" e porta al benvenuto: qui non resta niente da fare.
          is StepResult.Advanced -> it.copy(stage = RejoinStage.DONE)
          is StepResult.Problem -> it.copy(stage = RejoinStage.INTRO, feedback = result.problem.toFeedback())
          null -> it.copy(stage = RejoinStage.INTRO, feedback = Feedback(R.string.fb_failed_title, R.string.problem_generic, FluidTone.Danger))
        }
      }
    }
  }
}

@Composable
internal fun FamilyRejoinRoute(onBack: () -> Unit) {
  val viewModel: FamilyRejoinViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  FamilyRejoinScreen(state = state, onBack = onBack, onAsk = viewModel::ask, onCancel = viewModel::cancel, onConfirm = viewModel::confirm)
}

@Composable
internal fun FamilyRejoinScreen(
  state: RejoinUiState,
  onBack: () -> Unit,
  onAsk: () -> Unit,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
) {
  FluidScreen(
    title = stringResource(R.string.rejoin_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Tertiary, motif = FluidHeroMotif.Glow),
  ) {
    item {
      FluidHero(
        tone = FluidHeroTone.TertiaryToPrimary,
        motif = FluidHeroMotif.Cards,
        eyebrow = stringResource(R.string.rejoin_eyebrow),
        value = stringResource(R.string.rejoin_value),
        title = stringResource(R.string.rejoin_hero_title),
        description = stringResource(R.string.rejoin_hero_detail),
        icon = Icons.Rounded.SwitchAccount,
      )
    }
    if (state.stage == RejoinStage.WORKING || state.stage == RejoinStage.DONE) {
      item { FluidLoadingBlock() }
      return@FluidScreen
    }
    state.feedback?.let { item { FeedbackNote(it) } }
    if (state.isAdmin) {
      item {
        Note(title = stringResource(R.string.rejoin_admin_title), message = stringResource(R.string.rejoin_admin_message), tone = FluidTone.Danger)
      }
    }
    item { GallerySectionHeader(title = stringResource(R.string.rejoin_section)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.rejoin_forget),
          subtitle = stringResource(R.string.rejoin_forget_detail),
          tone = FluidTone.Warning,
          leading = { RowGlyph(Icons.Rounded.Key) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.rejoin_scan),
          subtitle = stringResource(R.string.rejoin_scan_detail),
          tone = FluidTone.Primary,
          leading = { RowGlyph(Icons.Rounded.QrCodeScanner) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.rejoin_keep),
          subtitle = stringResource(R.string.rejoin_keep_detail),
          tone = FluidTone.Success,
          leading = { RowGlyph(Icons.Rounded.PhotoLibrary) },
          disclosure = false,
        )
      }
    }
    item {
      FluidButton(
        text = stringResource(R.string.rejoin_start),
        onClick = onAsk,
        style = FluidButtonStyle.Destructive,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
    item { FluidSectionFootnote(text = stringResource(R.string.rejoin_footnote)) }
  }

  ConfirmSheet(
    visible = state.stage == RejoinStage.CONFIRM,
    onDismiss = onCancel,
    icon = Icons.Rounded.Warning,
    tone = GallerySheetTone.Destructive,
    title = stringResource(R.string.rejoin_sheet_title),
    message = stringResource(if (state.isAdmin) R.string.rejoin_sheet_message_admin else R.string.rejoin_sheet_message),
    confirmLabel = stringResource(R.string.rejoin_sheet_confirm),
    onConfirm = onConfirm,
  )
}
