package dev.pampa.mycloudgallery.feature.settings.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.state.ToggleableState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.ConfirmSheet
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.RowNumber
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens
import dev.pampa.mycloudgallery.feature.settings.ui.StatusPill
import dev.pampa.mycloudgallery.feature.settings.ui.agoText
import androidx.compose.runtime.getValue

/** Cio' che la pagina puo' chiedere al ViewModel (una sola riga per volta, tutte esplicite). */
internal class DeviceDetailActions(
  val onBack: () -> Unit,
  val onStartLost: () -> Unit,
  val onContinueLost: () -> Unit,
  val onAcknowledge: (Boolean) -> Unit,
  val onConfirmLost: () -> Unit,
  val onCancelLost: () -> Unit,
  val onGuideStep: (GuideStep) -> Unit,
  val onOpenGuide: () -> Unit,
  val onCloseGuide: () -> Unit,
  val onAskChange: (PendingChange) -> Unit,
  val onCancelChange: () -> Unit,
  val onConfirmChange: () -> Unit,
  val onStartRename: () -> Unit = {},
  val onRenameText: (String) -> Unit = {},
  val onCancelRename: () -> Unit = {},
  val onConfirmRename: () -> Unit = {},
) {
  companion object {
    val None = DeviceDetailActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
  }
}

@Composable
internal fun DeviceDetailRoute(onBack: () -> Unit) {
  val viewModel: DeviceDetailViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  DeviceDetailScreen(
    state = state,
    actions = DeviceDetailActions(
      onBack = onBack,
      onStartLost = viewModel::startLost,
      onContinueLost = viewModel::continueLost,
      onAcknowledge = viewModel::setAcknowledged,
      onConfirmLost = viewModel::confirmLost,
      onCancelLost = viewModel::cancelLost,
      onGuideStep = viewModel::markGuideStepDone,
      onOpenGuide = viewModel::openGuide,
      onCloseGuide = viewModel::closeGuide,
      onAskChange = viewModel::askChange,
      onCancelChange = viewModel::cancelChange,
      onConfirmChange = viewModel::confirmChange,
      onStartRename = viewModel::startRename,
      onRenameText = viewModel::onRenameText,
      onCancelRename = viewModel::cancelRename,
      onConfirmRename = viewModel::confirmRename,
    ),
  )
}

/**
 * Il dettaglio di un dispositivo (piano 2.4): chi e', in che stato e, per chi puo', le azioni.
 *
 * "Segna come perso" ha una **doppia conferma** (due fogli) e dopo la guida nell'ordine giusto; "Revoca" e
 * "Dismetti" una sola. Su questo dispositivo non c'e' nessuna azione: non ci si revoca da soli.
 */
@Composable
internal fun DeviceDetailScreen(state: DeviceDetailUiState, actions: DeviceDetailActions) {
  val device = state.device
  FluidScreen(
    title = device?.name ?: stringResource(R.string.device_detail_title),
    onBack = actions.onBack,
    ambient = FluidAmbient(
      tone = if (device != null && !device.isApproved) FluidHeroTone.Alert else FluidHeroTone.Primary,
      motif = FluidHeroMotif.Glow,
    ),
  ) {
    if (device == null) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.device_not_found_title),
          detail = stringResource(R.string.device_not_found_detail),
        )
      }
      return@FluidScreen
    }

    if (state.lostStage == LostStage.GUIDE) {
      item { LostGuideHero(done = state.guideDone, deviceName = device.name) }
      item { FluidSectionHeader(title = stringResource(R.string.guide_section)) }
      item {
        LostGuideSteps(
          done = state.guideDone,
          deviceName = device.name,
          nasUser = device.nasUser,
          byOwner = state.reportedByOwner,
          onStep = actions.onGuideStep,
        )
      }
      item { FluidSectionFootnote(text = stringResource(R.string.guide_footnote)) }
      item {
        FluidButton(
          text = stringResource(R.string.guide_close),
          onClick = actions.onCloseGuide,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
    }

    item { DeviceIdentity(device = device) }
    state.feedback?.let { item { FeedbackNote(it) } }

    if (device.lookAlikeOfName != null) {
      item {
        Note(
          title = stringResource(R.string.look_alike_title),
          message = stringResource(R.string.look_alike_message, device.lookAlikeOfName),
          tone = FluidTone.Warning,
        )
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.device_info_section)) }
    item { FluidListGroup { DeviceInfoRows(device) } }

    // F27: il nome di questo dispositivo lo cambia l'admin (e' nella voce firmata del registro); la cartella del backup non cambia (E-B7).
    if (state.canRename) {
      item { FluidSectionHeader(title = stringResource(R.string.device_actions_section)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.rename_row),
            subtitle = stringResource(R.string.rename_row_detail),
            tone = FluidTone.Neutral,
            leading = { RowGlyph(Icons.Rounded.Edit) },
            onClick = actions.onStartRename,
          )
        }
      }
    }

    val canAct = state.canManage || state.canReportOwn
    if (canAct) {
      item { FluidSectionHeader(title = stringResource(R.string.device_actions_section)) }
      item {
        FluidListGroup {
          if (state.canManage) {
            FluidListRow(
              title = stringResource(R.string.action_retire),
              subtitle = stringResource(R.string.action_retire_detail),
              tone = FluidTone.Neutral,
              leading = { RowGlyph(Icons.Rounded.Inventory2) },
              onClick = { actions.onAskChange(PendingChange.RETIRE) },
            )
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.action_revoke),
              subtitle = stringResource(R.string.action_revoke_detail),
              tone = FluidTone.Danger,
              leading = { RowGlyph(Icons.Rounded.PersonRemove) },
              onClick = { actions.onAskChange(PendingChange.REVOKE) },
            )
            FluidListDivider()
          }
          FluidListRow(
            title = stringResource(if (state.canManage) R.string.action_lost else R.string.action_lost_own),
            subtitle = stringResource(if (state.canManage) R.string.action_lost_detail else R.string.action_lost_own_detail),
            tone = FluidTone.Danger,
            leading = { RowGlyph(Icons.Rounded.PhonelinkErase) },
            onClick = actions.onStartLost,
          )
        }
      }
      item { FluidSectionFootnote(text = stringResource(R.string.device_actions_footnote)) }
    } else if (device.isThisDevice) {
      item { FluidSectionFootnote(text = stringResource(R.string.device_this_footnote)) }
    } else if (device.status == DeviceStatus.LOST && state.lostStage != LostStage.GUIDE) {
      item { FluidSectionHeader(title = stringResource(R.string.guide_section)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.guide_reopen),
            subtitle = stringResource(R.string.guide_reopen_detail),
            tone = FluidTone.Warning,
            leading = { RowGlyph(Icons.Rounded.Help) },
            onClick = actions.onOpenGuide,
          )
        }
      }
    }
  }

  // Le conferme. Fuori da FluidScreen: i fogli vivono nell'host alla radice.
  val name = device?.name.orEmpty()
  ConfirmSheet(
    visible = state.lostStage == LostStage.FIRST_CONFIRM,
    onDismiss = actions.onCancelLost,
    icon = Icons.Rounded.PhonelinkErase,
    tone = GallerySheetTone.Destructive,
    title = stringResource(R.string.lost_first_title, name),
    message = stringResource(R.string.lost_first_message),
    confirmLabel = stringResource(R.string.lost_first_confirm),
    onConfirm = actions.onContinueLost,
    body = { LostConsequences() },
  )
  ConfirmSheet(
    visible = state.lostStage == LostStage.SECOND_CONFIRM,
    onDismiss = actions.onCancelLost,
    icon = Icons.Rounded.LockClock,
    tone = GallerySheetTone.Destructive,
    title = stringResource(R.string.lost_second_title),
    message = stringResource(R.string.lost_second_message, name),
    confirmLabel = stringResource(R.string.lost_second_confirm),
    onConfirm = actions.onConfirmLost,
    confirmEnabled = state.acknowledged,
    body = { AcknowledgeRow(checked = state.acknowledged, onChange = actions.onAcknowledge) },
  )
  val renaming = state.rename
  ConfirmSheet(
    visible = renaming != null,
    onDismiss = actions.onCancelRename,
    icon = Icons.Rounded.Edit,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.rename_title),
    message = stringResource(R.string.rename_message),
    confirmLabel = stringResource(R.string.rename_confirm),
    onConfirm = actions.onConfirmRename,
    confirmStyle = FluidButtonStyle.Filled,
    confirmEnabled = renaming != null && renaming.problem == null && !renaming.saving,
    body = {
      if (renaming != null) {
        FluidTextField(
          value = renaming.text,
          onValueChange = actions.onRenameText,
          label = stringResource(R.string.rename_field),
          enabled = !renaming.saving,
          isError = renaming.problem != null && renaming.problem != RenameProblem.SAME,
          supportingText = stringResource(renaming.problem?.messageRes() ?: R.string.rename_help),
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
      }
    },
  )
  val change = state.pendingChange
  ConfirmSheet(
    visible = change != null,
    onDismiss = actions.onCancelChange,
    icon = if (change == PendingChange.REVOKE) Icons.Rounded.Block else Icons.Rounded.Inventory2,
    tone = if (change == PendingChange.REVOKE) GallerySheetTone.Destructive else GallerySheetTone.Caution,
    title = stringResource(if (change == PendingChange.REVOKE) R.string.revoke_title else R.string.retire_title, name),
    message = stringResource(if (change == PendingChange.REVOKE) R.string.revoke_message else R.string.retire_message),
    confirmLabel = stringResource(if (change == PendingChange.REVOKE) R.string.revoke_confirm else R.string.retire_confirm),
    onConfirm = actions.onConfirmChange,
    confirmStyle = if (change == PendingChange.REVOKE) FluidButtonStyle.Destructive else FluidButtonStyle.Filled,
  )
}

/** La frase sotto il campo del nome quando qualcosa non va (F27). */
@androidx.annotation.StringRes
private fun RenameProblem.messageRes(): Int = when (this) {
  RenameProblem.EMPTY -> R.string.rename_err_empty
  RenameProblem.TOO_LONG -> R.string.rename_err_long
  RenameProblem.INVALID -> R.string.rename_err_invalid
  RenameProblem.TAKEN -> R.string.rename_err_taken
  RenameProblem.SAME -> R.string.rename_err_same
}

/** L'identita' del dispositivo in cima al dettaglio: icona grande, nome, modello e stato. */
@Composable
private fun DeviceIdentity(device: DeviceUi) {
  FluidCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(SettingsDimens.CardPadding),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val container = when (device.status.tone()) {
        FluidTone.Success -> MaterialTheme.colorScheme.primaryContainer
        FluidTone.Danger -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
      }
      val glyph = when (device.status.tone()) {
        FluidTone.Success -> MaterialTheme.colorScheme.onPrimaryContainer
        FluidTone.Danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
      }
      Box(
        modifier = Modifier
          .size(SettingsDimens.IdentityTile)
          .clip(ContinuousCornerShape(FluidRadius.Card))
          .background(container),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = device.icon(),
          contentDescription = null,
          modifier = Modifier.size(SettingsDimens.IdentityGlyph),
          tint = glyph,
        )
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDimens.TightGap)) {
        Text(
          text = device.name,
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = device.model,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(SettingsDimens.PillGap),
          modifier = Modifier.padding(top = SettingsDimens.PillGap),
        ) {
          StatusPill(stringResource(device.status.labelRes()), device.status.tone())
          if (device.isThisDevice) StatusPill(stringResource(R.string.pill_this_device), FluidTone.Info)
          if (device.admin && device.isApproved) StatusPill(stringResource(R.string.pill_admin), FluidTone.Primary)
        }
      }
    }
  }
}

@Composable
private fun DeviceInfoRows(device: DeviceUi) {
  val roleLabels = device.roles.map { stringResource(it.labelRes()) }
  InfoRow(R.string.info_account, device.nasUser)
  FluidListDivider()
  InfoRow(
    R.string.info_roles,
    if (roleLabels.isEmpty()) stringResource(R.string.info_roles_none) else roleLabels.joinToString(", "),
  )
  FluidListDivider()
  InfoRow(
    R.string.info_last_seen,
    device.lastSeenAgoMs?.let { stringResource(R.string.device_seen_short, agoText(it)) } ?: stringResource(R.string.info_unknown),
  )
  FluidListDivider()
  InfoRow(R.string.info_app_version, device.appVersion ?: stringResource(R.string.info_unknown))
  FluidListDivider()
  InfoRow(R.string.info_model, device.model)
}

@Composable
private fun InfoRow(label: Int, value: String) {
  FluidListRow(
    title = stringResource(label),
    subtitle = "",
    disclosure = false,
    badge = { FluidRowValue(text = value) },
  )
}

/** Le tre cose che succedono a un dispositivo segnato come perso: nel primo foglio, una riga ciascuna. */
@Composable
private fun LostConsequences() {
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.lost_cons_wipe),
      subtitle = stringResource(R.string.lost_cons_wipe_detail),
      tone = FluidTone.Danger,
      leading = { RowGlyph(Icons.Rounded.PhonelinkErase) },
      disclosure = false,
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.lost_cons_untrusted),
      subtitle = stringResource(R.string.lost_cons_untrusted_detail),
      tone = FluidTone.Danger,
      leading = { RowGlyph(Icons.Rounded.VisibilityOff) },
      disclosure = false,
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.lost_cons_final),
      subtitle = stringResource(R.string.lost_cons_final_detail),
      tone = FluidTone.Warning,
      leading = { RowGlyph(Icons.Rounded.LockClock) },
      disclosure = false,
    )
  }
}

/** "Ho capito": la presa d'atto che abilita il bottone finale del secondo foglio. */
@Composable
private fun AcknowledgeRow(checked: Boolean, onChange: (Boolean) -> Unit) {
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.lost_ack_title),
      subtitle = stringResource(R.string.lost_ack_detail),
      disclosure = false,
      onClick = { onChange(!checked) },
      badge = { FluidSwitch(checked = checked, onCheckedChange = null) },
      modifier = Modifier.semantics(mergeDescendants = true) {
        role = Role.Switch
        toggleableState = ToggleableState(checked)
      },
    )
  }
}

// --- la guida dopo la segnalazione ------------------------------------------------------------------

@Composable
private fun LostGuideHero(done: Set<GuideStep>, deviceName: String) {
  FluidHero(
    tone = FluidHeroTone.Alert,
    motif = FluidHeroMotif.Ticks,
    eyebrow = stringResource(R.string.guide_eyebrow),
    value = stringResource(R.string.guide_progress, done.size, GuideStep.ordered.size),
    title = stringResource(R.string.guide_title),
    description = stringResource(R.string.guide_detail, deviceName),
    icon = Icons.Rounded.PhonelinkErase,
  )
}

/**
 * I quattro passi, in ordine. Quello fatto e' verde con la spunta; il prossimo e' quello dell'accento e si
 * tocca per segnarlo; i successivi sono grigi e aspettano (si leggono, non si toccano): **non si puo' cambiare
 * la password prima di aver revocato il peer**.
 */
@Composable
internal fun LostGuideSteps(
  done: Set<GuideStep>,
  deviceName: String,
  nasUser: String,
  byOwner: Boolean,
  onStep: (GuideStep) -> Unit,
) {
  val next = GuideStep.ordered.firstOrNull { it !in done }
  FluidListGroup {
    GuideStep.ordered.forEachIndexed { index, step ->
      if (index > 0) FluidListDivider()
      val isDone = step in done
      val isNext = step == next
      val (title, detail) = when (step) {
        GuideStep.REPORT ->
          stringResource(if (byOwner) R.string.guide_step_report_owner else R.string.guide_step_report) to
            stringResource(if (byOwner) R.string.guide_step_report_owner_detail else R.string.guide_step_report_detail)
        GuideStep.REVOKE_PEER -> stringResource(R.string.guide_step_revoke) to stringResource(R.string.guide_step_revoke_detail, deviceName)
        GuideStep.CHANGE_PASSWORD ->
          stringResource(R.string.guide_step_password) to stringResource(R.string.guide_step_password_detail, nasUser)
        GuideStep.FIND_DEVICE -> stringResource(R.string.guide_step_find) to stringResource(R.string.guide_step_find_detail)
      }
      FluidListRow(
        title = title,
        subtitle = detail,
        tone = when {
          isDone -> FluidTone.Success
          isNext -> FluidTone.Primary
          else -> FluidTone.Neutral
        },
        leading = {
          if (isDone) RowGlyph(Icons.Rounded.Check) else if (step == GuideStep.REVOKE_PEER) RowGlyph(Icons.Rounded.Router) else RowNumber(index + 1)
        },
        labels = {
          when {
            isDone -> StatusPill(stringResource(R.string.guide_pill_done), FluidTone.Success)
            isNext -> StatusPill(stringResource(R.string.guide_pill_now), FluidTone.Primary)
          }
        },
        disclosure = false,
        onClick = if (isNext && step != GuideStep.REPORT) ({ onStep(step) }) else null,
      )
    }
  }
}
