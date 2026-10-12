package dev.pampa.mycloudgallery.feature.settings.family

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.mycloudgallery.core.security.input.SecureInput
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.qr.QrScanner
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.CodeField
import dev.pampa.mycloudgallery.feature.settings.ui.CodeFormat
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/** Cio' che la pagina del recupero puo' chiedere. */
internal class RecoveryActions(
  val onBack: () -> Unit,
  val onAdminUser: (String) -> Unit,
  val onPrivateShare: (String) -> Unit,
  val onSearch: () -> Unit,
  val onPickKit: (Int) -> Unit,
  val onBackToSearch: () -> Unit,
  val onCode: (String) -> Unit,
  val onOldAdmin: (OldAdminFate) -> Unit,
  val onStateScanned: (String) -> ScanVerdict,
  val onClearState: () -> Unit,
  val onRecover: () -> Unit,
  val onAdminPassword: (String) -> Unit = {},
) {
  companion object {
    val None = RecoveryActions({}, {}, {}, {}, {}, {}, {}, {}, { ScanVerdict.Ignore }, {}, {})
  }
}

@Composable
internal fun AdminRecoveryRoute(onBack: () -> Unit) {
  val viewModel: AdminRecoveryViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  AdminRecoveryScreen(
    state = state,
    actions = RecoveryActions(
      onBack = onBack,
      onAdminUser = viewModel::setAdminUser,
      onPrivateShare = viewModel::setPrivateShare,
      onSearch = viewModel::search,
      onPickKit = viewModel::pickKit,
      onBackToSearch = viewModel::backToSearch,
      onCode = viewModel::setCode,
      onOldAdmin = viewModel::setOldAdminFate,
      onStateScanned = viewModel::onStateScanned,
      onClearState = viewModel::clearScannedState,
      onRecover = viewModel::recover,
      onAdminPassword = viewModel::setAdminPassword,
    ),
  )
}

/**
 * Diventare admin con il kit: il secondo admin, o il recupero dopo aver perso il primo. La finestra e' sotto
 * `FLAG_SECURE` perche' ci si digita il codice di recupero.
 */
@Composable
internal fun AdminRecoveryScreen(state: RecoveryUiState, actions: RecoveryActions) {
  SensitiveScreen()
  val second = state.mode == RecoveryMode.SECOND_ADMIN
  FluidScreen(
    title = stringResource(if (second) R.string.recovery_title_second else R.string.recovery_title_replace),
    onBack = actions.onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.TertiaryToPrimary, motif = FluidHeroMotif.Glow),
  ) {
    if (state.stage == RecoveryStage.DONE) {
      item {
        FluidHero(
          tone = FluidHeroTone.Primary,
          motif = FluidHeroMotif.Dots,
          eyebrow = stringResource(R.string.recovery_done_eyebrow),
          value = stringResource(R.string.recovery_done_value),
          title = stringResource(if (second) R.string.recovery_done_title_second else R.string.recovery_done_title_replace),
          description = stringResource(if (second) R.string.recovery_done_detail_second else R.string.recovery_done_detail_replace),
          icon = Icons.Rounded.AdminPanelSettings,
        )
      }
      item {
        FluidButton(
          text = stringResource(R.string.recovery_done_back),
          onClick = actions.onBack,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
      return@FluidScreen
    }

    item {
      FluidHero(
        tone = if (second) FluidHeroTone.PrimaryToSecondary else FluidHeroTone.Alert,
        motif = FluidHeroMotif.Ticks,
        eyebrow = stringResource(if (second) R.string.recovery_eyebrow_second else R.string.recovery_eyebrow_replace),
        value = stringResource(R.string.recovery_value),
        title = stringResource(if (second) R.string.recovery_hero_title_second else R.string.recovery_hero_title_replace),
        description = stringResource(if (second) R.string.recovery_hero_detail_second else R.string.recovery_hero_detail_replace),
        icon = if (second) Icons.Rounded.AdminPanelSettings else Icons.Rounded.SwitchAccount,
      )
    }

    if (!state.available) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.recovery_unavailable_title),
          detail = stringResource(R.string.recovery_unavailable_detail),
        )
      }
      return@FluidScreen
    }

    state.feedback?.let { item { FeedbackNote(it) } }

    when (state.stage) {
      RecoveryStage.SEARCH -> recoverySearch(state, actions)
      RecoveryStage.PICK -> recoveryPick(state, actions)
      RecoveryStage.CODE -> recoveryCode(state, actions)
      RecoveryStage.WORKING -> item { FluidLoadingBlock() }
      RecoveryStage.DONE -> Unit
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recoverySearch(state: RecoveryUiState, actions: RecoveryActions) {
  item {
    FluidSectionHeader(
      title = stringResource(R.string.recovery_section_where),
      detail = stringResource(R.string.recovery_section_where_detail),
    )
  }
  item {
    FluidCard {
      FluidTextField(
        value = state.adminUser,
        onValueChange = actions.onAdminUser,
        label = stringResource(R.string.recovery_field_user),
        placeholder = stringResource(R.string.invite_field_user_hint),
        supportingText = stringResource(R.string.recovery_field_user_help),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
      )
      // L'area privata dell'admin si apre solo con il suo account: se non e' quello di questo dispositivo serve la sua
      // password, per una sola ricerca (mai salvata).
      if (state.needsAdminPassword) {
        FluidTextField(
          value = state.adminPassword,
          onValueChange = actions.onAdminPassword,
          label = stringResource(R.string.recovery_field_password),
          supportingText = stringResource(R.string.recovery_field_password_help),
          keyboardOptions = SecureInput.keyboardOptions(ImeAction.Next),
          visualTransformation = SecureInput.hidden,
        )
      }
      FluidTextField(
        value = state.privateShare,
        onValueChange = actions.onPrivateShare,
        label = stringResource(R.string.recovery_field_share),
        placeholder = state.adminUser.ifBlank { stringResource(R.string.invite_field_share_hint) },
        supportingText = stringResource(R.string.recovery_field_share_help),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
      )
    }
  }
  item {
    FluidButton(
      text = stringResource(R.string.recovery_search),
      onClick = actions.onSearch,
      size = FluidButtonSize.Large,
      fillWidth = true,
      enabled = state.canSearch,
      loading = state.searching,
    )
  }
  item { FluidSectionFootnote(text = stringResource(R.string.recovery_search_footnote)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recoveryPick(state: RecoveryUiState, actions: RecoveryActions) {
  item {
    FluidSectionHeader(
      title = stringResource(R.string.recovery_section_kits),
      detail = stringResource(R.string.recovery_section_kits_detail),
    )
  }
  item {
    FluidListGroup {
      state.kits.forEachIndexed { index, kit ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.recovery_kit_row, index + 1),
          subtitle = kit.fileName,
          tone = FluidTone.Warning,
          leading = { RowGlyph(Icons.Rounded.Description) },
          onClick = { actions.onPickKit(index) },
        )
      }
    }
  }
  item {
    FluidButton(
      text = stringResource(R.string.recovery_other_account),
      onClick = actions.onBackToSearch,
      style = FluidButtonStyle.Tinted,
      size = FluidButtonSize.Large,
      fillWidth = true,
    )
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recoveryCode(state: RecoveryUiState, actions: RecoveryActions) {
  item {
    FluidSectionHeader(
      title = stringResource(R.string.recovery_section_code),
      detail = stringResource(R.string.recovery_section_code_detail),
    )
  }
  item {
    FluidCard {
      CodeField(
        value = state.code,
        onValueChange = actions.onCode,
        maxChars = CodeFormat.RECOVERY_CHARS,
        label = stringResource(R.string.recovery_code_label),
        placeholder = stringResource(R.string.recovery_code_placeholder),
        secret = true,
        isError = state.feedback?.message == R.string.recovery_wrong_code,
        supportingText = stringResource(R.string.recovery_code_hint),
      )
    }
  }

  item { StateScanSection(state = state, actions = actions) }

  if (state.mode == RecoveryMode.REPLACE_ADMIN) {
    item {
      FluidSectionHeader(
        title = stringResource(R.string.recovery_section_old),
        detail = stringResource(R.string.recovery_section_old_detail),
      )
    }
    item {
      FluidListGroup {
        OldAdminRow(R.string.recovery_old_lost, R.string.recovery_old_lost_detail, state.oldAdmin == OldAdminFate.LOST) {
          actions.onOldAdmin(OldAdminFate.LOST)
        }
        FluidListDivider()
        OldAdminRow(R.string.recovery_old_revoked, R.string.recovery_old_revoked_detail, state.oldAdmin == OldAdminFate.REVOKED) {
          actions.onOldAdmin(OldAdminFate.REVOKED)
        }
      }
    }
  }

  item {
    FluidButton(
      text = stringResource(R.string.recovery_action),
      onClick = actions.onRecover,
      size = FluidButtonSize.Large,
      fillWidth = true,
      enabled = state.canRecover,
    )
  }
  item { FluidSectionFootnote(text = stringResource(R.string.recovery_action_footnote)) }
}

/** Lo "stato della famiglia" (M7): facoltativo ma consigliato, alza la versione minima del registro accettato. */
@Composable
private fun StateScanSection(state: RecoveryUiState, actions: RecoveryActions) {
  var scanning by rememberSaveable { mutableStateOf(false) }
  FluidCard {
    when {
      state.stateVersion != null -> {
        FluidListRow(
          title = stringResource(R.string.recovery_state_read, state.stateVersion),
          subtitle = stringResource(R.string.recovery_state_read_detail),
          tone = FluidTone.Success,
          leading = { RowGlyph(Icons.Rounded.Check) },
          disclosure = false,
        )
        FluidButton(
          text = stringResource(R.string.recovery_state_remove),
          onClick = { actions.onClearState(); scanning = false },
          style = FluidButtonStyle.Plain,
          size = FluidButtonSize.Medium,
        )
      }
      scanning -> {
        QrScanner(
          accept = { text ->
            val verdict = actions.onStateScanned(text)
            if (verdict == ScanVerdict.Accept) scanning = false
            verdict
          },
          onCancel = { scanning = false },
          modifier = Modifier.fillMaxWidth(),
        )
      }
      else -> {
        Note(
          title = stringResource(R.string.recovery_state_title),
          message = stringResource(R.string.recovery_state_message),
          tone = FluidTone.Warning,
        )
        FluidButton(
          text = stringResource(R.string.recovery_state_scan),
          onClick = { scanning = true },
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Medium,
          leading = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) },
        )
      }
    }
  }
}

@Composable
private fun OldAdminRow(title: Int, detail: Int, selected: Boolean, onSelect: () -> Unit) {
  FluidListRow(
    title = stringResource(title),
    subtitle = stringResource(detail),
    selected = selected,
    disclosure = false,
    onClick = onSelect,
    badge = {
      if (selected) {
        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary)
      }
    },
  )
}
