package dev.pampa.mycloudgallery.feature.settings.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
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
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.security.input.SecureInput
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.messageRes
import androidx.compose.runtime.getValue

internal class PasswordActions(
  val onBack: () -> Unit,
  val onPassword: (String) -> Unit,
  val onToggleVisible: () -> Unit,
  val onSubmit: () -> Unit,
) {
  companion object {
    val None = PasswordActions({}, {}, {}, {})
  }
}

@Composable
internal fun PasswordRoute(onBack: () -> Unit) {
  val viewModel: PasswordViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  PasswordScreen(
    state = state,
    actions = PasswordActions(
      onBack = onBack,
      onPassword = viewModel::setPassword,
      onToggleVisible = viewModel::toggleVisible,
      onSubmit = viewModel::submit,
    ),
  )
}

/**
 * La nuova password del NAS. La finestra e' sotto `FLAG_SECURE` e protetta dalle sovrapposizioni
 * (`SensitiveScreen`); il campo usa la tastiera dei campi riservati (niente suggerimenti, niente
 * apprendimento). Si fa **un solo tentativo**: se non riesce lo dice, e per riprovare serve una password
 * nuova (E-R8: il NAS blocca l'account dopo pochi errori).
 */
@Composable
internal fun PasswordScreen(state: PasswordUiState, actions: PasswordActions) {
  SensitiveScreen()
  FluidScreen(
    title = stringResource(R.string.password_title),
    onBack = actions.onBack,
    ambient = FluidAmbient(
      tone = if (state.blockedBy != null) FluidHeroTone.Alert else FluidHeroTone.Primary,
      motif = FluidHeroMotif.Glow,
    ),
  ) {
    if (!state.available || state.user == null) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.account_unavailable_title),
          detail = stringResource(R.string.account_unavailable_detail),
        )
      }
      return@FluidScreen
    }

    if (state.phase == PasswordPhase.DONE) {
      item {
        FluidHero(
          tone = FluidHeroTone.Primary,
          motif = FluidHeroMotif.Dots,
          eyebrow = stringResource(R.string.password_done_eyebrow),
          value = stringResource(R.string.password_done_value),
          title = stringResource(R.string.password_done_title),
          description = stringResource(R.string.password_done_detail),
          icon = Icons.Rounded.Check,
        )
      }
      item { StageResults(state.stages) }
      item {
        FluidButton(
          text = stringResource(R.string.password_done_back),
          onClick = actions.onBack,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
      return@FluidScreen
    }

    item {
      val blocked = state.blockedBy
      FluidHero(
        tone = if (blocked != null) FluidHeroTone.Alert else FluidHeroTone.PrimaryToSecondary,
        motif = FluidHeroMotif.Ticks,
        eyebrow = stringResource(R.string.password_eyebrow, state.user),
        value = stringResource(if (blocked != null) R.string.password_value_blocked else R.string.password_value),
        title = stringResource(if (blocked != null) R.string.password_hero_title_blocked else R.string.password_hero_title),
        description = stringResource(blocked?.messageRes() ?: R.string.password_hero_detail),
        icon = Icons.Rounded.Key,
      )
    }

    state.feedback?.let { item { FeedbackNote(it) } }
    if (state.stages.isNotEmpty()) item { StageResults(state.stages) }

    item { FluidSectionHeader(title = stringResource(R.string.password_section_new)) }
    item {
      FluidCard {
        FluidTextField(
          value = state.password,
          onValueChange = actions.onPassword,
          label = stringResource(R.string.password_field),
          enabled = state.phase == PasswordPhase.FORM,
          supportingText = if (state.isShort) stringResource(R.string.password_short) else stringResource(R.string.password_field_help),
          isError = false,
          showClearButton = false,
          keyboardOptions = SecureInput.keyboardOptions(ImeAction.Done),
          visualTransformation = if (state.visible) VisualTransformation.None else SecureInput.hidden,
          trailing = {
            IconButton(onClick = actions.onToggleVisible) {
              Icon(
                imageVector = if (state.visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = stringResource(if (state.visible) R.string.password_hide else R.string.password_show),
              )
            }
          },
        )
        FluidButton(
          text = stringResource(R.string.password_submit),
          onClick = actions.onSubmit,
          size = FluidButtonSize.Large,
          fillWidth = true,
          enabled = state.canSubmit,
          loading = state.phase == PasswordPhase.TESTING,
        )
      }
    }
    item {
      Note(
        title = stringResource(R.string.password_once_title),
        message = stringResource(R.string.password_once_message),
        tone = FluidTone.Info,
      )
    }
    item { FluidSectionFootnote(text = stringResource(R.string.password_footnote)) }
  }
}

/** Le fasi della prova, una riga ciascuna: spunta, croce o "non eseguita". */
@Composable
private fun StageResults(stages: List<StageResult>) {
  FluidListGroup {
    stages.forEachIndexed { index, stage ->
      if (index > 0) FluidListDivider()
      val (tone, icon, label) = when (stage.outcome) {
        StageOutcome.PASSED -> Triple(FluidTone.Success, Icons.Rounded.Check, R.string.stage_passed)
        StageOutcome.FAILED -> Triple(FluidTone.Danger, Icons.Rounded.Close, R.string.stage_failed)
        StageOutcome.SKIPPED, StageOutcome.NOT_RUN -> Triple(FluidTone.Neutral, Icons.Rounded.Remove, R.string.stage_not_run)
      }
      FluidListRow(
        title = stringResource(stage.stage.labelRes()),
        subtitle = if (stage.outcome == StageOutcome.FAILED && stage.error != null) stringResource(stage.error!!.messageRes()) else "",
        tone = tone,
        leading = { RowGlyph(icon) },
        disclosure = false,
        badge = { dev.pampa.mycloudgallery.feature.settings.ui.StatusPill(stringResource(label), tone) },
      )
    }
  }
}

private fun Stage.labelRes(): Int = when (this) {
  Stage.ROUTE -> R.string.stage_route
  Stage.TCP -> R.string.stage_tcp
  Stage.NEGOTIATE -> R.string.stage_negotiate
  Stage.GUID -> R.string.stage_guid
  Stage.AUTHENTICATE -> R.string.stage_authenticate
  Stage.SECURITY_CHECK -> R.string.stage_security
  Stage.SHARE -> R.string.stage_share
  Stage.READ -> R.string.stage_read
}
