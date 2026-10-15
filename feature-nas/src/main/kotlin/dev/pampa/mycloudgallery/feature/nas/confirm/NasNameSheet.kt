package dev.pampa.mycloudgallery.feature.nas.confirm

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.feature.nas.NasDimens
import dev.pampa.mycloudgallery.feature.nas.R
import dev.pampa.mycloudgallery.feature.nas.contract.NasArea
import dev.pampa.mycloudgallery.feature.nas.ui.NasText
import dev.pampa.mycloudgallery.feature.nas.ui.planRejectionText

// Il foglio con un campo di testo: "Nuova cartella" e "Rinomina". Il nome si controlla **mentre si scrive** (E-D9): il ViewModel
// chiede a `NasOperations.plan` il piano per il testo di adesso, e il pulsante si accende solo quando esiste un piano valido. Il
// pulsante e' anche la conferma di *quel* piano (un tocco: nome, posto e effetto si vedono tutti nel foglio), quindi la
// [UserConfirmation] nasce dal suo gestore come in [NasConfirmSheet].

/** Che cosa si sta nominando. */
internal enum class NamePurpose { NEW_FOLDER, RENAME }

/** L'esito del controllo del nome di adesso. */
internal sealed interface NameCheck {
  /** Il campo e' vuoto. */
  data object Empty : NameCheck

  /** Il piano per il testo di adesso e' in calcolo. */
  data object Checking : NameCheck

  /** Il nome va bene: [plan] e' il piano che il pulsante conferma. */
  data class Ready(val plan: OpPlan) : NameCheck

  /** Il nome non e' ammesso; [suggestion] e' la correzione proposta (E-D9), se esiste. */
  data class Invalid(val suggestion: String?) : NameCheck

  /** Esiste gia' qualcosa con questo nome. */
  data object Exists : NameCheck

  /** E' il nome di adesso. */
  data object Same : NameCheck

  /** Il piano e' rifiutato per un altro motivo (sola lettura, NAS irraggiungibile...). */
  data class Refused(val rejection: PlanRejection) : NameCheck
}

/** Il foglio del nome in corso di scrittura. [shareName] e [folder] sono il posto in cui si crea (solo per "Nuova cartella"). */
internal data class NameDraft(
  val purpose: NamePurpose,
  val text: String,
  val check: NameCheck,
  val shareName: String? = null,
  val folder: List<String> = emptyList(),
)

/**
 * Il foglio del nome. [draft] null = chiuso. [onTextChange] e [onUseSuggestion] vanno al ViewModel, che ricalcola il piano.
 */
@Composable
internal fun NasNameSheet(
  draft: NameDraft?,
  areas: List<NasArea>,
  monotonicMs: () -> Long,
  onTextChange: (String) -> Unit,
  onUseSuggestion: (String) -> Unit,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onDismiss: () -> Unit,
) {
  var shown by remember { mutableStateOf(draft) }
  if (draft != null) shown = draft
  val showing = shown
  GalleryConfirmSheet(
    visible = draft != null,
    onDismissRequest = onDismiss,
    paneTitle = if (showing != null) nameTitle(showing.purpose) else "",
  ) {
    if (showing != null) {
      NasNameBody(showing, areas, monotonicMs, onTextChange, onUseSuggestion, onConfirmed, onDismiss)
    }
  }
}

@Composable
private fun nameTitle(purpose: NamePurpose): String =
  stringResource(if (purpose == NamePurpose.NEW_FOLDER) R.string.nas_name_new_folder_title else R.string.nas_name_rename_title)

@Composable
private fun ColumnScope.NasNameBody(
  draft: NameDraft,
  areas: List<NasArea>,
  monotonicMs: () -> Long,
  onTextChange: (String) -> Unit,
  onUseSuggestion: (String) -> Unit,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onDismiss: () -> Unit,
) {
  val plan = (draft.check as? NameCheck.Ready)?.plan
  val sheet = remember(plan) { plan?.let { ConfirmationSheet(it) } }
  var taps by remember(plan) { mutableIntStateOf(0) }
  NasNameSheetContent(
    draft = draft,
    areas = areas,
    onTextChange = onTextChange,
    onUseSuggestion = onUseSuggestion,
    onConfirm = {
      // Un tocco sul pulsante: e' la conferma del piano per il nome che si vede nel campo.
      val confirmation = sheet?.tap(monotonicMs())
      taps = sheet?.tapsDone ?: taps
      if (plan != null && confirmation != null) onConfirmed(plan, confirmation)
    },
    onCancel = {
      sheet?.reset()
      taps = 0
      onDismiss()
    },
  )
}

/** Il contenuto del foglio del nome, senza stato (lo mostrano il foglio vero e gli screenshot). */
@Composable
internal fun NasNameSheetContent(
  draft: NameDraft,
  areas: List<NasArea>,
  onTextChange: (String) -> Unit,
  onUseSuggestion: (String) -> Unit,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val newFolder = draft.purpose == NamePurpose.NEW_FOLDER
  val check = draft.check
  val problem = check is NameCheck.Invalid || check is NameCheck.Exists || check is NameCheck.Same || check is NameCheck.Refused
  val support = when (check) {
    NameCheck.Empty -> null
    NameCheck.Checking -> stringResource(R.string.nas_name_checking)
    is NameCheck.Ready -> stringResource(R.string.nas_name_ok)
    is NameCheck.Invalid -> stringResource(R.string.nas_name_invalid)
    NameCheck.Exists -> stringResource(R.string.nas_name_exists)
    NameCheck.Same -> stringResource(R.string.nas_name_same)
    is NameCheck.Refused -> planRejectionText(check.rejection)
  }
  GallerySheetScaffold(
    icon = if (newFolder) Icons.Rounded.CreateNewFolder else Icons.Rounded.DriveFileRenameOutline,
    tone = GallerySheetTone.Brand,
    title = nameTitle(draft.purpose),
    message = if (newFolder) {
      stringResource(R.string.nas_name_new_folder_message, NasText.path(listOf(areaLabel(draft.shareName, areas)) + draft.folder))
    } else {
      stringResource(R.string.nas_name_rename_message)
    },
    modifier = modifier.widthIn(max = NasDimens.SheetMaxWidth),
    body = {
      FluidTextField(
        value = draft.text,
        onValueChange = onTextChange,
        label = stringResource(R.string.nas_name_field_label),
        isError = problem,
        supportingText = support,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
      )
      val suggestion = (check as? NameCheck.Invalid)?.suggestion
      if (suggestion != null) {
        FluidButton(
          text = stringResource(R.string.nas_name_suggestion, suggestion),
          onClick = { onUseSuggestion(suggestion) },
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Medium,
          fillWidth = true,
        )
      }
    },
    actions = {
      FluidButton(
        text = stringResource(if (newFolder) R.string.nas_name_new_folder_action else R.string.nas_name_rename_action),
        onClick = onConfirm,
        enabled = check is NameCheck.Ready,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.nas_cancel),
        onClick = onCancel,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    },
  )
}
