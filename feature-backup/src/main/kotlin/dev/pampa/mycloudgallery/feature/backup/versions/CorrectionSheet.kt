package dev.pampa.mycloudgallery.feature.backup.versions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.free.BackupChoiceRow
import dev.pampa.mycloudgallery.feature.backup.free.BindDialogBridge
import dev.pampa.mycloudgallery.feature.backup.free.ConfirmTaps
import dev.pampa.mycloudgallery.feature.backup.free.rememberConfirmTaps
import dev.pampa.mycloudgallery.feature.backup.ui.BackupButton

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `CorrectionState`, §2.4, D19, E-L8, E-E8, E-E9, Q6, L40; R7 della 4B): il foglio "Correggi data e
// luogo" e "Ruota" (menu ⋮ del viewer). Come il foglio delle versioni e' un foglio di conferma del design system che `:app` apre alla radice,
// sopra il viewer (scudo contro le sovrapposizioni compreso). L'utente sceglie cosa correggere (data e ora, luogo, rotazione), vede **cosa
// cambia** prima e dopo, e la conferma nasce **solo** dal tocco su "Correggi". Un media del telefono non al sicuro non si corregge
// (`NeedsSafeFirst`); la riga del telefono si riscrive solo con il permesso del sistema; HEIC, RAW e video restano corretti solo nell'app.

/** Il foglio delle correzioni di [subject], aperto da `:app` sopra il viewer; `null` = chiuso. */
@Composable
fun CorrectionSheetHost(subject: CorrectionSubject?, onDismiss: () -> Unit, mode: CorrectionMode = CorrectionMode.ALL) {
  // Il foglio che si chiude fa la sua uscita con il contenuto che aveva.
  var shown by remember { mutableStateOf<CorrectionSubject?>(null) }
  if (subject != null) shown = subject
  val showing = shown ?: return
  CorrectionSheetRoute(subject = showing, mode = mode, visible = subject != null, onDismiss = onDismiss)
}

@Composable
private fun CorrectionSheetRoute(
  subject: CorrectionSubject,
  mode: CorrectionMode,
  visible: Boolean,
  onDismiss: () -> Unit,
  viewModel: CorrectionViewModel = hiltViewModel(key = "correzione-${subject.mediaId.value}"),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  BindDialogBridge(viewModel.bridge)
  LaunchedEffect(subject, visible, mode) {
    if (visible) {
      viewModel.reset()
      viewModel.bind(subject, mode)
    }
  }
  val taps = rememberConfirmTaps((state as? CorrectionUi.Editing)?.plan, viewModel::monotonicMs)
  CorrectionSheet(
    visible = visible,
    state = state,
    taps = taps,
    actions = CorrectionSheetActions(
      edit = viewModel::edit,
      searchPlaces = viewModel::searchPlaces,
      choosePlace = viewModel::choosePlace,
      apply = viewModel::apply,
      safeguardNow = viewModel::safeguardNow,
      close = onDismiss,
    ),
    onDismissRequest = onDismiss,
  )
}

/** Le uscite del foglio: tutte partono da un tocco o da un campo che l'utente scrive. */
internal class CorrectionSheetActions(
  val edit: ((CorrectionDraft) -> CorrectionDraft) -> Unit = {},
  val searchPlaces: (String) -> Unit = {},
  val choosePlace: (PlaceChoice) -> Unit = {},
  val apply: (PhoneConfirmation) -> Unit = {},
  val safeguardNow: () -> Unit = {},
  val close: () -> Unit = {},
)

/** Il foglio nel suo pop-up (con lo scudo contro le sovrapposizioni di `GalleryConfirmSheet`). */
@Composable
internal fun CorrectionSheet(
  visible: Boolean,
  state: CorrectionUi,
  taps: ConfirmTaps?,
  actions: CorrectionSheetActions,
  onDismissRequest: () -> Unit,
) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismissRequest, paneTitle = titleOf(state)) {
    CorrectionSheetContent(state = state, taps = taps, actions = actions)
  }
}

@Composable
private fun titleOf(state: CorrectionUi): String =
  stringResource(if (state is CorrectionUi.Editing && state.mode == CorrectionMode.ROTATE) R.string.backup_correction_title_rotate else R.string.backup_correction_title)

/** Il contenuto del foglio (anche per gli screenshot e i test, senza il pop-up). */
@Composable
internal fun CorrectionSheetContent(state: CorrectionUi, taps: ConfirmTaps?, actions: CorrectionSheetActions, modifier: Modifier = Modifier) {
  when (state) {
    CorrectionUi.Loading -> StatusSheet(
      icon = Icons.Rounded.EditCalendar,
      title = stringResource(R.string.backup_correction_generic_title),
      rowTitle = stringResource(R.string.backup_correction_loading),
      rowDetail = "",
      busy = true,
      modifier = modifier,
    )
    CorrectionUi.Unavailable -> StatusSheet(
      icon = Icons.Rounded.EditCalendar,
      title = stringResource(R.string.backup_correction_generic_title),
      rowTitle = stringResource(R.string.backup_correction_unavailable_title),
      rowDetail = stringResource(R.string.backup_correction_unavailable_detail),
      modifier = modifier,
      actions = { CloseButton(actions.close) },
    )
    is CorrectionUi.Editing -> EditingContent(state, taps, actions, modifier)
    is CorrectionUi.NeedsSafeFirst -> StatusSheet(
      icon = Icons.Rounded.Shield,
      title = stringResource(R.string.backup_correction_generic_title),
      rowTitle = stringResource(R.string.backup_correction_safe_title),
      rowDetail = stringResource(R.string.backup_correction_safe_detail),
      tone = FluidTone.Warning,
      modifier = modifier,
      actions = {
        BackupButton(label = stringResource(R.string.backup_correction_safe_action), onClick = actions.safeguardNow)
        CloseButton(actions.close)
      },
    )
    is CorrectionUi.AwaitingSystemDialog -> StatusSheet(
      icon = Icons.Rounded.EditCalendar,
      title = stringResource(R.string.backup_correction_generic_title),
      rowTitle = stringResource(R.string.backup_correction_waiting_title),
      rowDetail = stringResource(R.string.backup_correction_waiting_detail),
      busy = true,
      modifier = modifier,
    )
    CorrectionUi.Writing -> StatusSheet(
      icon = Icons.Rounded.EditCalendar,
      title = stringResource(R.string.backup_correction_generic_title),
      rowTitle = stringResource(R.string.backup_correction_writing_title),
      rowDetail = stringResource(R.string.backup_correction_writing_detail),
      busy = true,
      modifier = modifier,
    )
    is CorrectionUi.Uploading -> StatusSheet(
      icon = Icons.Rounded.CheckCircle,
      title = stringResource(R.string.backup_correction_generic_title),
      rowTitle = stringResource(R.string.backup_correction_uploading_title),
      rowDetail = stringResource(R.string.backup_correction_uploading_detail),
      busy = true,
      modifier = modifier,
      actions = { CloseButton(actions.close) },
    )
    is CorrectionUi.Done -> StatusSheet(
      icon = Icons.Rounded.CheckCircle,
      title = stringResource(R.string.backup_correction_generic_title),
      rowTitle = stringResource(if (state.onlyInApp) R.string.backup_correction_done_app_title else R.string.backup_correction_done_title),
      rowDetail = stringResource(if (state.onlyInApp) R.string.backup_correction_done_app_detail else R.string.backup_correction_done_detail),
      tone = FluidTone.Success,
      modifier = modifier,
      actions = { CloseButton(actions.close) },
    )
    is CorrectionUi.Failed -> StatusSheet(
      icon = Icons.Rounded.Warning,
      title = stringResource(R.string.backup_correction_generic_title),
      rowTitle = stringResource(R.string.backup_correction_failed_title),
      rowDetail = stringResource(failureRes(state.code)),
      tone = if (state.code == CorrectionFailure.PHONE_NOT_RESTORED) FluidTone.Danger else FluidTone.Warning,
      modifier = modifier,
      actions = { CloseButton(actions.close) },
    )
    is CorrectionUi.Blocked -> StatusSheet(
      icon = Icons.Rounded.Warning,
      title = stringResource(R.string.backup_correction_generic_title),
      rowTitle = stringResource(R.string.backup_correction_blocked_title),
      rowDetail = stringResource(blockedRes(state.reason)),
      tone = FluidTone.Warning,
      modifier = modifier,
      actions = { CloseButton(actions.close) },
    )
  }
}

private fun failureRes(code: CorrectionFailure): Int = when (code) {
  CorrectionFailure.SOURCE_UNREADABLE -> R.string.backup_correction_failed_unreadable
  CorrectionFailure.RESULT_NOT_VALID -> R.string.backup_correction_failed_invalid
  CorrectionFailure.PHONE_WRITE_FAILED -> R.string.backup_correction_failed_phone_write
  CorrectionFailure.PHONE_NOT_RESTORED -> R.string.backup_correction_failed_not_restored
  CorrectionFailure.PHONE_FULL -> R.string.backup_correction_failed_full
  CorrectionFailure.NAS_FAILED -> R.string.backup_correction_failed_nas
}

private fun blockedRes(reason: CorrectionReadiness): Int = when (reason) {
  CorrectionReadiness.COMMON_AREA_NOTICE -> R.string.backup_correction_blocked_common
  CorrectionReadiness.READ_ONLY_AREA -> R.string.backup_correction_blocked_read_only
  CorrectionReadiness.NAS_UNREACHABLE -> R.string.backup_correction_blocked_nas
  CorrectionReadiness.FROZEN -> R.string.backup_correction_blocked_frozen
  CorrectionReadiness.NOT_MINE -> R.string.backup_correction_blocked_not_mine
  CorrectionReadiness.NOT_AVAILABLE, CorrectionReadiness.READY, CorrectionReadiness.NEEDS_SAFE_FIRST, CorrectionReadiness.FORMAT_NOT_WRITABLE ->
    R.string.backup_correction_blocked_not_available
}

// --- Gli stati senza scelte ---------------------------------------------------------------------------------------------------

@Composable
private fun CloseButton(onClose: () -> Unit) {
  BackupButton(label = stringResource(R.string.backup_correction_close), onClick = onClose, style = FluidButtonStyle.Tinted)
}

/** Un foglio con una sola riga che dice cosa succede: il cerchio che gira se c'e' un lavoro, il tono se e' un avviso. */
@Composable
private fun StatusSheet(
  icon: ImageVector,
  title: String,
  rowTitle: String,
  rowDetail: String,
  modifier: Modifier = Modifier,
  busy: Boolean = false,
  tone: FluidTone = FluidTone.Info,
  actions: @Composable ColumnScope.() -> Unit = {},
) {
  GallerySheetScaffold(
    icon = icon,
    tone = GallerySheetTone.Brand,
    title = title,
    message = null,
    modifier = modifier,
    body = {
      FluidListGroup {
        FluidListRow(
          title = rowTitle,
          subtitle = rowDetail,
          tone = tone,
          leading = if (busy) ({ FluidSpinner(size = BackupDimens.RowGlyph) }) else ({ Icon(imageVector = icon, contentDescription = null) }),
          disclosure = false,
          titleMaxLines = 3,
        )
      }
    },
    actions = actions,
  )
}

// --- La scrittura ------------------------------------------------------------------------------------------------------------

@Composable
private fun EditingContent(state: CorrectionUi.Editing, taps: ConfirmTaps?, actions: CorrectionSheetActions, modifier: Modifier) {
  val rotateOnly = state.mode == CorrectionMode.ROTATE
  GallerySheetScaffold(
    icon = Icons.Rounded.EditCalendar,
    tone = GallerySheetTone.Brand,
    title = stringResource(if (rotateOnly) R.string.backup_correction_title_rotate else R.string.backup_correction_title),
    message = stringResource(if (rotateOnly) R.string.backup_correction_message_rotate else R.string.backup_correction_message),
    modifier = modifier,
    body = {
      state.message?.let { MessageRow(it) }
      if (state.readiness == CorrectionReadiness.FORMAT_NOT_WRITABLE) {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.backup_correction_format_note),
            subtitle = "",
            tone = FluidTone.Info,
            leading = { Icon(imageVector = Icons.Rounded.Warning, contentDescription = null) },
            disclosure = false,
            titleMaxLines = 4,
          )
        }
      }
      if (!rotateOnly) {
        DateSection(state, actions)
        PlaceSection(state, actions)
      }
      RotationSection(state, actions)
      PreviewSection(state)
    },
    actions = {
      // L'unico punto dove nasce la conferma: il tocco su questo pulsante.
      BackupButton(
        label = stringResource(R.string.backup_correction_apply),
        onClick = { taps?.tap()?.let(actions.apply) },
        enabled = state.plan != null && taps != null,
      )
      BackupButton(
        label = stringResource(R.string.backup_correction_cancel),
        onClick = {
          taps?.reset()
          actions.close()
        },
        style = FluidButtonStyle.Tinted,
      )
    },
  )
}

@Composable
private fun MessageRow(message: CorrectionMessage) {
  val text = when (message) {
    CorrectionMessage.PLAN_CHANGED -> R.string.backup_correction_msg_plan_changed
    CorrectionMessage.CONFIRMATION_EXPIRED -> R.string.backup_correction_msg_expired
    CorrectionMessage.PERMISSION_REFUSED -> R.string.backup_correction_msg_refused
    CorrectionMessage.NO_SYSTEM_DIALOG -> R.string.backup_correction_msg_no_dialog
  }
  FluidListGroup {
    FluidListRow(
      title = stringResource(text),
      subtitle = "",
      tone = FluidTone.Warning,
      leading = { Icon(imageVector = Icons.Rounded.Warning, contentDescription = null) },
      disclosure = false,
      titleMaxLines = 4,
    )
  }
}

/** Una riga con un interruttore: accende o spegne una parte della correzione. Per TalkBack e' un interruttore, con il suo stato detto a parole. */
@Composable
private fun ToggleRow(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onToggle: () -> Unit) {
  val stateText = stringResource(if (checked) R.string.backup_state_on else R.string.backup_state_off)
  FluidListRow(
    title = title,
    subtitle = subtitle,
    tone = if (checked) FluidTone.Primary else FluidTone.Neutral,
    leading = { Icon(imageVector = icon, contentDescription = null) },
    badge = { FluidSwitch(checked = checked, onCheckedChange = null) },
    titleMaxLines = 2,
    disclosure = false,
    onClick = onToggle,
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.Switch
      stateDescription = stateText
    },
  )
}

@Composable
private fun DateSection(state: CorrectionUi.Editing, actions: CorrectionSheetActions) {
  val draft = state.draft
  val now = state.subject.takenAt?.let {
    stringResource(R.string.backup_correction_date_now, stringResource(R.string.backup_correction_date_value, GalleryDates.date(it), GalleryDates.time(it)))
  } ?: stringResource(R.string.backup_correction_date_none)
  FluidListGroup {
    ToggleRow(
      title = stringResource(R.string.backup_correction_date_title),
      subtitle = now,
      icon = Icons.Rounded.EditCalendar,
      checked = draft.changeDate,
      onToggle = { actions.edit { it.copy(changeDate = !it.changeDate) } },
    )
  }
  if (draft.changeDate) {
    Column(verticalArrangement = Arrangement.spacedBy(BackupDimens.LineGap)) {
      FluidTextField(
        value = draft.dateText,
        onValueChange = { text -> actions.edit { it.copy(dateText = text) } },
        label = stringResource(R.string.backup_correction_date_field),
        isError = DraftError.DATE_INVALID in state.errors,
        supportingText = if (DraftError.DATE_INVALID in state.errors) stringResource(R.string.backup_correction_date_error) else null,
        minHeight = GalleryDimens.MinTouchTarget,
        // Il tasto che svuota il campo e' un bersaglio da 28 dp senza nome: qui non serve (si riscrive il testo).
        showClearButton = false,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )
      FluidTextField(
        value = draft.timeText,
        onValueChange = { text -> actions.edit { it.copy(timeText = text) } },
        label = stringResource(R.string.backup_correction_time_field),
        isError = DraftError.TIME_INVALID in state.errors,
        supportingText = if (DraftError.TIME_INVALID in state.errors) stringResource(R.string.backup_correction_time_error) else null,
        minHeight = GalleryDimens.MinTouchTarget,
        // Il tasto che svuota il campo e' un bersaglio da 28 dp senza nome: qui non serve (si riscrive il testo).
        showClearButton = false,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )
    }
  }
}

@Composable
private fun PlaceSection(state: CorrectionUi.Editing, actions: CorrectionSheetActions) {
  val draft = state.draft
  val now = when {
    state.subject.placeLabel != null -> stringResource(R.string.backup_correction_place_now, state.subject.placeLabel)
    state.subject.hasLocation -> stringResource(R.string.backup_correction_place_now_unnamed)
    else -> stringResource(R.string.backup_correction_place_none)
  }
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.backup_correction_place_title),
      subtitle = now,
      tone = FluidTone.Neutral,
      leading = { Icon(imageVector = Icons.Rounded.Place, contentDescription = null) },
      disclosure = false,
      titleMaxLines = 2,
    )
    FluidListDivider()
    ChoiceRow(R.string.backup_correction_place_keep, null, draft.place == PlaceEdit.KEEP) { actions.edit { it.copy(place = PlaceEdit.KEEP) } }
    FluidListDivider()
    ChoiceRow(R.string.backup_correction_place_change, null, draft.place == PlaceEdit.CHANGE) { actions.edit { it.copy(place = PlaceEdit.CHANGE) } }
    FluidListDivider()
    ChoiceRow(R.string.backup_correction_place_clear, R.string.backup_correction_place_clear_detail, draft.place == PlaceEdit.CLEAR) {
      actions.edit { it.copy(place = PlaceEdit.CLEAR) }
    }
  }
  if (draft.place == PlaceEdit.CHANGE) {
    Column(verticalArrangement = Arrangement.spacedBy(BackupDimens.LineGap)) {
      if (state.canSearchPlaces) {
        FluidTextField(
          value = draft.placeQuery,
          onValueChange = actions.searchPlaces,
          label = stringResource(R.string.backup_correction_place_search),
          minHeight = GalleryDimens.MinTouchTarget,
          // Il tasto che svuota il campo e' un bersaglio da 28 dp senza nome: qui non serve (si riscrive il testo).
          showClearButton = false,
        )
        if (state.places.isNotEmpty()) {
          FluidListGroup {
            state.places.forEachIndexed { index, choice ->
              if (index > 0) FluidListDivider()
              BackupChoiceRow(choice.label, "", draft.chosenPlace == choice) { actions.choosePlace(choice) }
            }
          }
        } else if (draft.placeQuery.isNotBlank() && draft.chosenPlace == null) {
          FluidListGroup {
            FluidListRow(
              title = stringResource(R.string.backup_correction_place_not_found),
              subtitle = "",
              tone = FluidTone.Neutral,
              disclosure = false,
              titleMaxLines = 3,
            )
          }
        }
      }
      FluidTextField(
        value = draft.coordinatesText,
        onValueChange = { text -> actions.edit { it.copy(coordinatesText = text, chosenPlace = null) } },
        label = stringResource(R.string.backup_correction_place_coordinates),
        placeholder = stringResource(R.string.backup_correction_place_coordinates_hint),
        isError = DraftError.COORDINATES_INVALID in state.errors || DraftError.PLACE_MISSING in state.errors,
        supportingText = when {
          DraftError.COORDINATES_INVALID in state.errors -> stringResource(R.string.backup_correction_place_coordinates_error)
          DraftError.PLACE_MISSING in state.errors -> stringResource(R.string.backup_correction_place_missing)
          else -> null
        },
        minHeight = GalleryDimens.MinTouchTarget,
        // Il tasto che svuota il campo e' un bersaglio da 28 dp senza nome: qui non serve (si riscrive il testo).
        showClearButton = false,
      )
    }
  }
}

/** Una scelta fra piu' (raggio) con i testi dalle stringhe. */
@Composable
private fun ChoiceRow(titleRes: Int, detailRes: Int?, selected: Boolean, onSelect: () -> Unit) {
  BackupChoiceRow(stringResource(titleRes), detailRes?.let { stringResource(it) }.orEmpty(), selected, onSelect)
}

@Composable
private fun RotationSection(state: CorrectionUi.Editing, actions: CorrectionSheetActions) {
  val turns = state.draft.quarterTurns
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.backup_correction_rotate_title),
      subtitle = rotationText(turns),
      tone = if (turns == 0) FluidTone.Neutral else FluidTone.Primary,
      leading = { Icon(imageVector = Icons.Rounded.RotateRight, contentDescription = null) },
      disclosure = false,
      titleMaxLines = 2,
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.backup_correction_rotate_left_action),
      subtitle = "",
      tone = FluidTone.Neutral,
      leading = { Icon(imageVector = Icons.Rounded.RotateLeft, contentDescription = null) },
      disclosure = false,
      onClick = { actions.edit { it.copy(quarterTurns = (it.quarterTurns + 3) % 4) } },
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.backup_correction_rotate_right_action),
      subtitle = "",
      tone = FluidTone.Neutral,
      leading = { Icon(imageVector = Icons.Rounded.RotateRight, contentDescription = null) },
      disclosure = false,
      onClick = { actions.edit { it.copy(quarterTurns = (it.quarterTurns + 1) % 4) } },
    )
  }
}

@Composable
private fun rotationText(turns: Int): String = stringResource(
  when (turns) {
    1 -> R.string.backup_correction_rotate_right
    2 -> R.string.backup_correction_rotate_half
    3 -> R.string.backup_correction_rotate_left
    else -> R.string.backup_correction_rotate_none
  },
)

/** "Cosa cambia": prima e dopo, solo per cio' che si corregge. Senza piano (bozza non valida) dice di scegliere. */
@Composable
private fun PreviewSection(state: CorrectionUi.Editing) {
  val draft = state.draft
  val lines = buildList {
    if (state.mode == CorrectionMode.ALL && draft.changeDate && state.plan != null) {
      val before = state.subject.takenAt?.let { stringResource(R.string.backup_correction_date_value, GalleryDates.date(it), GalleryDates.time(it)) }
        ?: stringResource(R.string.backup_correction_date_unknown_before)
      val date = CorrectionParsing.parseDate(draft.dateText)
      val time = CorrectionParsing.parseTime(draft.timeText)
      if (date != null && time != null) {
        val after = stringResource(R.string.backup_correction_date_value, GalleryDates.date(date), GalleryDates.time(java.time.LocalDateTime.of(date, time)))
        add(stringResource(R.string.backup_correction_preview_date, before, after))
      }
    }
    if (state.mode == CorrectionMode.ALL && draft.place != PlaceEdit.KEEP && state.plan != null) {
      val before = state.subject.placeLabel
        ?: stringResource(if (state.subject.hasLocation) R.string.backup_correction_place_before_unnamed else R.string.backup_correction_place_unknown_before)
      val after = if (draft.place == PlaceEdit.CLEAR) {
        stringResource(R.string.backup_correction_place_after_clear)
      } else {
        draft.chosenPlace?.label ?: stringResource(R.string.backup_correction_place_after_set)
      }
      add(stringResource(R.string.backup_correction_preview_place, before, after))
    }
    if (draft.quarterTurns != 0) add(stringResource(R.string.backup_correction_preview_rotation, rotationText(draft.quarterTurns)))
  }
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.backup_correction_preview_title),
      subtitle = if (lines.isEmpty()) stringResource(R.string.backup_correction_preview_nothing) else "",
      tone = FluidTone.Neutral,
      disclosure = false,
    )
    lines.forEach { line ->
      FluidListDivider()
      FluidListRow(
        title = line,
        subtitle = "",
        tone = FluidTone.Primary,
        disclosure = false,
        titleMaxLines = 4,
      )
    }
  }
}
