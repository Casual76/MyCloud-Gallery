package dev.pampa.mycloudgallery.feature.settings.family

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.family.contract.FamilyReset
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.ResetOutcome
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.security.screen.OverlayShield
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.CodeField
import dev.pampa.mycloudgallery.feature.settings.ui.CodeFormat
import dev.pampa.mycloudgallery.feature.settings.ui.ConfirmSheet
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.toFeedback
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue

/**
 * A che punto e' il reset della famiglia. **Due conferme distinte**, come chiede il piano: prima un foglio
 * che spiega cosa succede ([FIRST_CONFIRM]), poi la parola da riscrivere ([TYPE_WORD]). `FamilyReset.reset`
 * parte solo dopo la seconda e riceve `confirmedTwice = true`.
 */
internal enum class ResetStage { INTRO, FIRST_CONFIRM, TYPE_WORD, WORKING, DONE }

internal data class ResetUiState(
  val isAdmin: Boolean,
  val stage: ResetStage = ResetStage.INTRO,
  val typed: String = "",
  val feedback: Feedback? = null,
  /**
   * Dopo il reset: il codice di recupero del kit **nuovo**, a gruppi, finche' l'utente non l'ha riscritto. **Mai nei
   * log**: [toString] non lo stampa; la pagina e' sotto `FLAG_SECURE` mentre si vede.
   */
  val newCode: List<String> = emptyList(),
  val codeTyped: String = "",
) {
  override fun toString(): String = "ResetUiState(stage=$stage, codeShown=${newCode.isNotEmpty()})"

  /** La parola riscritta coincide (senza maiuscole, spazi ai bordi ignorati). */
  val wordMatches: Boolean get() = typed.trim().equals(CONFIRM_WORD, ignoreCase = true)

  companion object {
    /** La parola che si riscrive per la seconda conferma. */
    const val CONFIRM_WORD = "REIMPOSTA"
  }
}

@HiltViewModel
internal class FamilyResetViewModel @Inject constructor(
  private val registry: RegistryService,
  private val reset: FamilyReset,
) : ViewModel() {

  private val _state = MutableStateFlow(ResetUiState(isAdmin = isAdmin()))
  val state: StateFlow<ResetUiState> = _state.asStateFlow()

  private fun isAdmin(): Boolean = registry.status.value.let { it is TrustStatus.Approved && it.admin }

  /** Apre il primo foglio. Solo l'admin. */
  fun ask() {
    if (!isAdmin()) return
    _state.update { if (it.stage == ResetStage.INTRO) it.copy(stage = ResetStage.FIRST_CONFIRM, feedback = null) else it }
  }

  /** Chiude il primo foglio senza fare niente, o torna indietro dalla parola. */
  fun cancel() {
    _state.update {
      if (it.stage == ResetStage.FIRST_CONFIRM || it.stage == ResetStage.TYPE_WORD) it.copy(stage = ResetStage.INTRO, typed = "") else it
    }
  }

  /** Dal primo foglio alla parola da riscrivere. */
  fun continueToWord() {
    _state.update { if (it.stage == ResetStage.FIRST_CONFIRM) it.copy(stage = ResetStage.TYPE_WORD, typed = "") else it }
  }

  fun setTyped(value: String) {
    _state.update { if (it.stage == ResetStage.TYPE_WORD) it.copy(typed = value, feedback = null) else it }
  }

  /** La seconda conferma: solo con la parola giusta. Un secondo tocco durante il lavoro non riparte. */
  fun confirm() {
    val current = _state.value
    if (current.stage != ResetStage.TYPE_WORD || !current.wordMatches || !isAdmin()) return
    _state.update { it.copy(stage = ResetStage.WORKING, feedback = null) }
    viewModelScope.launch {
      val outcome = reset.reset(confirmedTwice = true)
      _state.update {
        when (outcome) {
          is ResetOutcome.Done -> it.copy(stage = ResetStage.DONE, typed = "", newCode = takeNewCode())
          is ResetOutcome.Failed -> it.copy(stage = ResetStage.TYPE_WORD, feedback = outcome.problem.toFeedback())
        }
      }
    }
  }

  /** Il codice del kit nuovo (P9: `FamilyReset.recoveryCodeForDisplay`), in gruppi; i caratteri originali si azzerano. */
  private fun takeNewCode(): List<String> {
    val display = reset.recoveryCodeForDisplay() ?: return emptyList()
    return display.use { shown -> shown.groups.map { String(it) } }
  }

  fun setCodeTyped(value: String) {
    _state.update { if (it.stage == ResetStage.DONE && it.newCode.isNotEmpty()) it.copy(codeTyped = value, feedback = null) else it }
  }

  /** Il codice riscritto per intero: se coincide, il kit nuovo e' "salvato" sul foglio e il codice si dimentica. */
  fun confirmCode() {
    val current = _state.value
    if (current.newCode.isEmpty() || current.codeTyped.isBlank()) return
    val typed = CodeFormat.normalize(current.codeTyped, CodeFormat.RECOVERY_CHARS).toCharArray()
    val ok = reset.confirmTypedCode(typed)
    _state.update {
      if (ok) {
        it.copy(newCode = emptyList(), codeTyped = "", feedback = null)
      } else {
        it.copy(feedback = Feedback(R.string.reset_code_wrong_title, R.string.reset_code_wrong, FluidTone.Danger))
      }
    }
  }

  override fun onCleared() {
    _state.value = _state.value.copy(newCode = emptyList(), codeTyped = "")
  }
}

@Composable
internal fun FamilyResetRoute(onBack: () -> Unit, onInvite: () -> Unit) {
  val viewModel: FamilyResetViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  FamilyResetScreen(
    state = state,
    onBack = onBack,
    onAsk = viewModel::ask,
    onCancel = viewModel::cancel,
    onContinue = viewModel::continueToWord,
    onTyped = viewModel::setTyped,
    onConfirm = viewModel::confirm,
    onInvite = onInvite,
    onCodeTyped = viewModel::setCodeTyped,
    onConfirmCode = viewModel::confirmCode,
  )
}

/**
 * Reimposta la famiglia (`formato-nas.md` §4.10): chiave nuova, id nuovo, kit nuovo; i dati vecchi vengono
 * **ignorati, non cancellati**, e ogni dispositivo si riconfigura con il QR nuovo. Il tasto non fa niente di
 * irreversibile da solo: apre il primo foglio, poi chiede di riscrivere una parola.
 */
@Composable
internal fun FamilyResetScreen(
  state: ResetUiState,
  onBack: () -> Unit,
  onAsk: () -> Unit,
  onCancel: () -> Unit,
  onContinue: () -> Unit,
  onTyped: (String) -> Unit,
  onConfirm: () -> Unit,
  onInvite: () -> Unit,
  onCodeTyped: (String) -> Unit = {},
  onConfirmCode: () -> Unit = {},
) {
  // Il codice del kit nuovo e' un segreto: niente screenshot ne' anteprima nei recenti finche' si vede.
  SensitiveScreen(enabled = state.newCode.isNotEmpty())
  FluidScreen(
    title = stringResource(R.string.reset_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Alert, motif = FluidHeroMotif.Glow),
  ) {
    if (state.stage == ResetStage.DONE && state.newCode.isNotEmpty()) {
      newKitCode(state, onCodeTyped, onConfirmCode)
      return@FluidScreen
    }
    if (!state.isAdmin && state.stage != ResetStage.DONE) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.reset_not_admin_title),
          detail = stringResource(R.string.reset_not_admin_detail),
        )
      }
      return@FluidScreen
    }

    if (state.stage == ResetStage.DONE) {
      item {
        FluidHero(
          tone = FluidHeroTone.Primary,
          motif = FluidHeroMotif.Dots,
          eyebrow = stringResource(R.string.reset_done_eyebrow),
          value = stringResource(R.string.reset_done_value),
          title = stringResource(R.string.reset_done_title),
          description = stringResource(R.string.reset_done_detail),
          icon = Icons.Rounded.RestartAlt,
        )
      }
      item { FluidSectionHeader(title = stringResource(R.string.reset_done_next)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.reset_next_save_kit),
            subtitle = stringResource(R.string.reset_next_save_kit_detail),
            tone = FluidTone.Warning,
            leading = { RowGlyph(Icons.Rounded.Key) },
            disclosure = false,
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.reset_next_reconfigure),
            subtitle = stringResource(R.string.reset_next_reconfigure_detail),
            tone = FluidTone.Primary,
            leading = { RowGlyph(Icons.Rounded.QrCode2) },
            onClick = onInvite,
          )
        }
      }
      return@FluidScreen
    }

    item {
      FluidHero(
        tone = FluidHeroTone.Alert,
        motif = FluidHeroMotif.Ripples,
        eyebrow = stringResource(R.string.reset_eyebrow),
        value = stringResource(R.string.reset_value),
        title = stringResource(R.string.reset_hero_title),
        description = stringResource(R.string.reset_hero_detail),
        icon = Icons.Rounded.RestartAlt,
      )
    }

    item { FluidSectionHeader(title = stringResource(R.string.reset_section_happens)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.reset_happens_key),
          subtitle = stringResource(R.string.reset_happens_key_detail),
          tone = FluidTone.Danger,
          leading = { RowGlyph(Icons.Rounded.Key) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.reset_happens_devices),
          subtitle = stringResource(R.string.reset_happens_devices_detail),
          tone = FluidTone.Danger,
          leading = { RowGlyph(Icons.Rounded.Devices) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.reset_happens_data),
          subtitle = stringResource(R.string.reset_happens_data_detail),
          tone = FluidTone.Success,
          leading = { RowGlyph(Icons.Rounded.Storage) },
          disclosure = false,
        )
      }
    }

    state.feedback?.let { item { FeedbackNote(it) } }

    if (state.stage == ResetStage.TYPE_WORD || state.stage == ResetStage.WORKING) {
      item {
        FluidSectionHeader(
          title = stringResource(R.string.reset_section_word),
          detail = stringResource(R.string.reset_section_word_detail, ResetUiState.CONFIRM_WORD),
        )
      }
      item {
        FluidCard {
          FluidTextField(
            value = state.typed,
            onValueChange = onTyped,
            label = stringResource(R.string.reset_word_label),
            placeholder = ResetUiState.CONFIRM_WORD,
            enabled = state.stage == ResetStage.TYPE_WORD,
            keyboardOptions = KeyboardOptions(
              capitalization = KeyboardCapitalization.Characters,
              autoCorrectEnabled = false,
              imeAction = ImeAction.Done,
            ),
          )
          FluidButton(
            text = stringResource(R.string.reset_confirm),
            onClick = onConfirm,
            style = FluidButtonStyle.Destructive,
            size = FluidButtonSize.Large,
            fillWidth = true,
            enabled = state.wordMatches,
            loading = state.stage == ResetStage.WORKING,
          )
          FluidButton(
            text = stringResource(R.string.action_cancel),
            onClick = onCancel,
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
            fillWidth = true,
            enabled = state.stage == ResetStage.TYPE_WORD,
          )
        }
      }
    } else {
      item {
        FluidButton(
          text = stringResource(R.string.reset_start),
          onClick = onAsk,
          style = FluidButtonStyle.Destructive,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
      item { FluidSectionFootnote(text = stringResource(R.string.reset_footnote)) }
    }
  }

  ConfirmSheet(
    visible = state.stage == ResetStage.FIRST_CONFIRM,
    onDismiss = onCancel,
    icon = Icons.Rounded.Warning,
    tone = GallerySheetTone.Destructive,
    title = stringResource(R.string.reset_sheet_title),
    message = stringResource(R.string.reset_sheet_message),
    confirmLabel = stringResource(R.string.reset_sheet_confirm),
    onConfirm = onContinue,
  )
  // La parola da riscrivere e' un campo di testo: la finestra va protetta anche quando non c'e' il foglio.
  OverlayShield(enabled = state.stage == ResetStage.TYPE_WORD || state.stage == ResetStage.WORKING)
}

/**
 * Dopo il reset: il codice di recupero del kit nuovo (P9). Senza, il kit nuovo sul NAS non si apre: si mostra una
 * volta, in gruppi, e si chiede di riscriverlo per intero (come al passo 12 dell'onboarding). Poi la pagina passa ai
 * passi successivi (salvare il foglio, riconfigurare gli altri dispositivi).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.newKitCode(
  state: ResetUiState,
  onCodeTyped: (String) -> Unit,
  onConfirmCode: () -> Unit,
) {
  item {
    FluidSectionHeader(
      title = stringResource(R.string.reset_code_section),
      detail = stringResource(R.string.reset_code_section_detail),
    )
  }
  item {
    FluidListGroup {
      state.newCode.forEachIndexed { index, group ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = group,
          subtitle = stringResource(R.string.reset_code_group, index + 1, state.newCode.size),
          disclosure = false,
        )
      }
    }
  }
  state.feedback?.let { item { FeedbackNote(it) } }
  item {
    FluidCard {
      CodeField(
        value = state.codeTyped,
        onValueChange = onCodeTyped,
        maxChars = CodeFormat.RECOVERY_CHARS,
        label = stringResource(R.string.reset_code_label),
        secret = true,
      )
      FluidButton(
        text = stringResource(R.string.reset_code_confirm),
        onClick = onConfirmCode,
        size = FluidButtonSize.Large,
        fillWidth = true,
        enabled = state.codeTyped.isNotBlank(),
      )
    }
  }
  item { FluidSectionFootnote(text = stringResource(R.string.reset_code_footnote)) }
}
