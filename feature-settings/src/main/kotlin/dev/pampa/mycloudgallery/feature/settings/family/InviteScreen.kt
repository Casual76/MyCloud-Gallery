package dev.pampa.mycloudgallery.feature.settings.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.qr.QrCode
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.core.security.screen.SecureScreen
import dev.pampa.mycloudgallery.core.security.screen.OverlayShield
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens
import androidx.compose.runtime.getValue

/** Cio' che la pagina d'invito puo' chiedere. */
internal class InviteActions(
  val onBack: () -> Unit,
  val onUser: (String) -> Unit,
  val onDeviceName: (String) -> Unit,
  val onPrivateShare: (String) -> Unit,
  val onPreset: (Preset?) -> Unit,
  val onCreate: () -> Unit,
  val onNewInvite: () -> Unit,
) {
  companion object {
    val None = InviteActions({}, {}, {}, {}, {}, {}, {})
  }
}

@Composable
internal fun InviteRoute(onBack: () -> Unit) {
  val viewModel: InviteViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  InviteScreen(
    state = state,
    actions = InviteActions(
      onBack = onBack,
      onUser = viewModel::setUser,
      onDeviceName = viewModel::setDeviceName,
      onPrivateShare = viewModel::setPrivateShare,
      onPreset = viewModel::setPreset,
      onCreate = viewModel::create,
      onNewInvite = viewModel::reset,
    ),
  )
}

/**
 * "Configura un familiare": il modulo e poi il QR. **Con il QR a schermo la finestra e' sotto `FLAG_SECURE`**
 * (niente screenshot ne' registrazione dello schermo, anteprima vuota nei recenti): un invito non deve finire
 * in una chat per sbaglio (E-S8). Resta valido 24 ore, ma da solo non basta: servono la password dell'account
 * e l'approvazione dell'admin.
 */
@Composable
internal fun InviteScreen(state: InviteUiState, actions: InviteActions) {
  val showing = state.phase == InvitePhase.SHOWING && state.qrText != null
  // La protezione resta finche' il QR e' in composizione: la pagina del modulo non ne ha bisogno.
  SecureScreen(enabled = showing)
  OverlayShield(enabled = showing)
  FluidScreen(
    title = stringResource(R.string.invite_title),
    onBack = actions.onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Glow),
  ) {
    if (!state.isAdmin) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.invite_not_admin_title),
          detail = stringResource(R.string.invite_not_admin_detail),
        )
      }
      return@FluidScreen
    }
    if (showing) {
      inviteShown(state, actions)
    } else {
      inviteForm(state, actions)
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.inviteForm(state: InviteUiState, actions: InviteActions) {
  item {
    FluidHero(
      tone = FluidHeroTone.PrimaryToSecondary,
      motif = FluidHeroMotif.Figures,
      eyebrow = stringResource(R.string.invite_eyebrow),
      value = stringResource(R.string.invite_value),
      title = stringResource(R.string.invite_hero_title),
      description = stringResource(R.string.invite_hero_detail),
      icon = Icons.Rounded.PersonAdd,
    )
  }
  state.feedback?.let { item { FeedbackNote(it) } }

  item { FluidSectionHeader(title = stringResource(R.string.invite_section_who)) }
  item {
    FluidCard {
      FluidTextField(
        value = state.user,
        onValueChange = actions.onUser,
        label = stringResource(R.string.invite_field_user),
        placeholder = stringResource(R.string.invite_field_user_hint),
        isError = state.userError != null,
        supportingText = stringResource(state.userError ?: R.string.invite_field_user_help),
        enabled = state.phase == InvitePhase.FORM,
        keyboardOptions = KeyboardOptions(
          capitalization = KeyboardCapitalization.None,
          autoCorrectEnabled = false,
          imeAction = ImeAction.Next,
        ),
      )
      FluidTextField(
        value = state.deviceName,
        onValueChange = actions.onDeviceName,
        label = stringResource(R.string.invite_field_name),
        placeholder = stringResource(R.string.invite_field_name_hint),
        isError = state.nameError != null,
        supportingText = stringResource(state.nameError ?: R.string.invite_field_name_help),
        enabled = state.phase == InvitePhase.FORM,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
      )
      FluidTextField(
        value = state.privateShare,
        onValueChange = actions.onPrivateShare,
        label = stringResource(R.string.invite_field_share),
        placeholder = state.user.ifBlank { stringResource(R.string.invite_field_share_hint) },
        isError = state.shareError != null,
        supportingText = stringResource(state.shareError ?: R.string.invite_field_share_help),
        enabled = state.phase == InvitePhase.FORM,
        keyboardOptions = KeyboardOptions(
          capitalization = KeyboardCapitalization.None,
          autoCorrectEnabled = false,
          imeAction = ImeAction.Done,
        ),
      )
    }
  }

  item {
    FluidSectionHeader(
      title = stringResource(R.string.invite_section_preset),
      detail = stringResource(R.string.invite_section_preset_detail),
    )
  }
  item {
    FluidListGroup {
      PresetRow(
        title = stringResource(R.string.invite_preset_default),
        detail = stringResource(R.string.invite_preset_default_detail),
        selected = state.preset == null,
        onSelect = { actions.onPreset(null) },
      )
      InvitablePresets.forEach { preset ->
        FluidListDivider()
        val info = PresetCatalog.info(preset)
        PresetRow(
          title = info.title,
          detail = info.forWhom,
          selected = state.preset == preset,
          onSelect = { actions.onPreset(preset) },
        )
      }
    }
  }

  item {
    FluidButton(
      text = stringResource(R.string.invite_create),
      onClick = actions.onCreate,
      size = FluidButtonSize.Large,
      fillWidth = true,
      loading = state.phase == InvitePhase.CREATING,
    )
  }
  item { FluidSectionFootnote(text = stringResource(R.string.invite_form_footnote)) }
}

@Composable
private fun PresetRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
  FluidListRow(
    title = title,
    subtitle = detail,
    selected = selected,
    disclosure = false,
    onClick = onSelect,
    badge = {
      if (selected) {
        Icon(
          imageVector = Icons.Rounded.Check,
          contentDescription = stringResource(R.string.selected),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    },
  )
}

private fun androidx.compose.foundation.lazy.LazyListScope.inviteShown(state: InviteUiState, actions: InviteActions) {
  val summary = state.summary
  item {
    FluidCard {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardGap),
      ) {
        QrCode(
          content = state.qrText.orEmpty(),
          contentDescription = stringResource(R.string.invite_qr_description),
          secure = true,
          modifier = Modifier.widthIn(max = SettingsDimens.QrMaxWidth),
        )
      }
    }
  }
  if (summary != null) {
    item { FluidSectionHeader(title = stringResource(R.string.invite_shown_section)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.invite_summary_device),
          subtitle = "",
          disclosure = false,
          leading = { RowGlyph(Icons.Rounded.Devices) },
          badge = { FluidRowValue(text = summary.deviceName) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.invite_summary_account),
          subtitle = "",
          disclosure = false,
          leading = { RowGlyph(Icons.Rounded.Lock) },
          badge = { FluidRowValue(text = summary.user) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.invite_summary_share),
          subtitle = "",
          disclosure = false,
          leading = { RowGlyph(Icons.Rounded.Storage) },
          badge = { FluidRowValue(text = summary.privateShare) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.invite_summary_expires),
          subtitle = "",
          disclosure = false,
          leading = { RowGlyph(Icons.Rounded.Schedule) },
          badge = { FluidRowValue(text = pluralStringResource(R.plurals.invite_expires_value, summary.hoursLeft, summary.hoursLeft)) },
        )
      }
    }
  }
  item {
    Note(
      title = stringResource(R.string.invite_safe_title),
      message = stringResource(R.string.invite_safe_message),
      tone = FluidTone.Info,
    )
  }
  item {
    Note(
      title = stringResource(R.string.invite_screen_only_title),
      message = stringResource(R.string.invite_screen_only_message),
      tone = FluidTone.Warning,
    )
  }
  item {
    FluidButton(
      text = stringResource(R.string.invite_done),
      onClick = actions.onNewInvite,
      size = FluidButtonSize.Large,
      fillWidth = true,
    )
  }
  item {
    FluidButton(
      text = stringResource(R.string.invite_back),
      onClick = actions.onBack,
      style = FluidButtonStyle.Tinted,
      size = FluidButtonSize.Large,
      fillWidth = true,
    )
  }
  item { FluidSectionFootnote(text = stringResource(R.string.invite_shown_footnote)) }
}
