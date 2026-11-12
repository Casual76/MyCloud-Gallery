package dev.pampa.mycloudgallery.feature.settings.devices

import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import androidx.compose.material.icons.rounded.SearchOff
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.qr.QrScanner
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.CodeField
import dev.pampa.mycloudgallery.feature.settings.ui.CodeFormat
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.PinnedActionDock
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens
import dev.pampa.mycloudgallery.feature.settings.ui.StatusPill
import dev.pampa.mycloudgallery.feature.settings.ui.agoText
import dev.pampa.mycloudgallery.feature.settings.ui.rememberSystemAuthenticator
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue

/** Cio' che la pagina di approvazione puo' chiedere. */
internal class ApprovalActions(
  val onBack: () -> Unit,
  val onMode: (ProofMode) -> Unit,
  val onCode: (String) -> Unit,
  val onAccountConfirmed: (Boolean) -> Unit,
  val onScanned: (String) -> ScanVerdict,
  val onRescan: () -> Unit,
  val onApprove: () -> Unit,
) {
  companion object {
    val None = ApprovalActions({}, {}, {}, {}, { ScanVerdict.Ignore }, {}, {})
  }
}

@Composable
internal fun ApprovalRoute(onBack: () -> Unit) {
  val viewModel: ApprovalViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val authenticator = rememberSystemAuthenticator()
  val scope = rememberCoroutineScope()
  val prompt = AuthPromptText(
    title = stringResource(R.string.approve_prompt_title),
    subtitle = stringResource(R.string.approve_prompt_subtitle),
  )
  ApprovalScreen(
    state = state,
    actions = ApprovalActions(
      onBack = onBack,
      onMode = viewModel::setMode,
      onCode = viewModel::setCode,
      onAccountConfirmed = viewModel::setAccountConfirmed,
      onScanned = viewModel::onScanned,
      onRescan = viewModel::rescan,
      onApprove = {
        scope.launch {
          viewModel.approve {
            authenticator?.authenticate(prompt) ?: AuthResult.NotAvailable(
              dev.pampa.mycloudgallery.core.security.lock.AuthAvailability.UNSUPPORTED,
            )
          }
        }
      },
    ),
  )
}

/**
 * Approvare un dispositivo nuovo (piano 2.3). Una pagina, tre mosse nell'ordine in cui vanno fatte:
 * conferma dell'account dichiarato, prova (codice digitato o QR della chiave), poi il telefono chiede la sua
 * credenziale. La finestra e' protetta da `FLAG_SECURE` e dalle sovrapposizioni: e' la pagina in cui un tocco
 * sbagliato (o indotto da un'altra app) dara' accesso alla famiglia.
 */
@Composable
internal fun ApprovalScreen(state: ApprovalUiState, actions: ApprovalActions) {
  SensitiveScreen()
  val request = state.request
  // B6 (G3): "Approva" sta **fermo in fondo**, sopra la tastiera, e la pagina gli scorre sotto: prima era l'ultimo elemento della lista, sotto la piega, e la tastiera lo copriva. Solo
  // la pagina del modulo di approvazione ha la barra (non quella dell'esito, ne' quella di una richiesta che non c'e': li' il tasto e' uno e la pagina e' corta).
  val hasDock = state.phase != ApprovalPhase.DONE && request != null
  PinnedActionDock(
    actions = if (hasDock) {
      {
        FluidButton(
          text = stringResource(R.string.approve_action),
          onClick = actions.onApprove,
          size = FluidButtonSize.Large,
          fillWidth = true,
          enabled = state.canSubmit,
          loading = state.phase == ApprovalPhase.SUBMITTING,
        )
      }
    } else {
      null
    },
  ) { pageModifier -> ApprovalPage(state = state, actions = actions, modifier = pageModifier) }
}

/** La pagina di approvazione: il modulo che scorre. [modifier] la fa finire sopra la barra dei tasti ([PinnedActionDock]). */
@Composable
private fun ApprovalPage(state: ApprovalUiState, actions: ApprovalActions, modifier: Modifier) {
  val request = state.request
  FluidScreen(
    title = stringResource(R.string.approve_title),
    modifier = modifier,
    onBack = actions.onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.TertiaryToPrimary, motif = FluidHeroMotif.Glow),
  ) {
    if (state.phase == ApprovalPhase.DONE) {
      item {
        FluidHero(
          tone = FluidHeroTone.Primary,
          motif = FluidHeroMotif.Dots,
          eyebrow = stringResource(R.string.approve_done_eyebrow),
          value = stringResource(R.string.approve_done_value),
          title = stringResource(R.string.approve_done_title),
          description = stringResource(R.string.approve_done_detail),
          icon = Icons.Rounded.CheckCircle,
        )
      }
      item {
        FluidButton(
          text = stringResource(R.string.approve_done_back),
          onClick = actions.onBack,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
      return@FluidScreen
    }

    if (request == null) {
      item {
        // Giro 2 della fase I (CS-17): la forma unica dello stato vuoto (figura e titolo); la frase dietro la «i».
        GalleryFigureState(
          icon = Icons.Rounded.SearchOff,
          title = stringResource(R.string.approve_missing_title),
          tone = GalleryIllustrationTone.Caution,
          fill = false,
          action = { SettingsInfo(label = stringResource(R.string.settings_state_why), text = stringResource(R.string.approve_missing_detail)) },
        )
      }
      item {
        FluidButton(
          text = stringResource(R.string.approve_back_to_devices),
          onClick = actions.onBack,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
      return@FluidScreen
    }

    if (!state.isAdmin) {
      item {
        Note(
          title = stringResource(R.string.fb_not_admin_title),
          message = stringResource(R.string.fb_not_admin),
          tone = FluidTone.Warning,
        )
      }
    }

    item { RequestCard(request) }

    if (!request.windowOpen) {
      item {
        Note(
          title = stringResource(R.string.fb_expired_title),
          message = stringResource(R.string.fb_expired),
          tone = FluidTone.Warning,
        )
      }
    }
    if (request.lookAlikeOfName != null) {
      item {
        Note(
          title = stringResource(R.string.look_alike_title),
          message = stringResource(R.string.look_alike_message, request.lookAlikeOfName),
          tone = FluidTone.Warning,
        )
      }
    }

    // 1. L'account dichiarato.
    item {
      GallerySectionHeader(
        title = stringResource(R.string.approve_step_account),
        detail = stringResource(R.string.approve_step_account_detail),
      )
    }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.approve_account_confirm, request.declaredNasUser),
          subtitle = stringResource(R.string.approve_account_confirm_detail),
          tone = FluidTone.Info,
          leading = { RowTile(Icons.Rounded.AdminPanelSettings) },
          leadingTile = false,
          disclosure = false,
          onClick = { actions.onAccountConfirmed(!state.accountConfirmed) },
          badge = { FluidSwitch(checked = state.accountConfirmed, onCheckedChange = null) },
          modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Switch
            toggleableState = ToggleableState(state.accountConfirmed)
          },
        )
      }
    }

    // 2. La prova.
    item {
      GallerySectionHeader(
        title = stringResource(R.string.approve_step_proof),
        detail = stringResource(R.string.approve_step_proof_detail),
      )
    }
    item {
      val codeLabel = stringResource(R.string.approve_mode_code)
      val scanLabel = stringResource(R.string.approve_mode_scan)
      FluidSegmentedControl(
        options = ProofMode.entries,
        selected = state.mode,
        onSelect = actions.onMode,
        label = { if (it == ProofMode.CODE) codeLabel else scanLabel },
      )
    }
    item {
      when (state.mode) {
        ProofMode.CODE -> CodeProof(state, actions)
        ProofMode.SCAN -> ScanProof(state, actions)
      }
    }

    state.feedback?.let { item { FeedbackNote(it) } }

    // Il tasto "Approva" e' nella barra ferma in fondo (sopra): la lista finisce dove comincia la barra, qui resta la nota.
    item { FluidSectionFootnote(text = stringResource(R.string.approve_footnote)) }
  }
}

/** Chi chiede di entrare: nome, modello, account dichiarato e da quanto. */
@Composable
private fun RequestCard(request: RequestUi) {
  FluidHero(
    tone = if (request.windowOpen) FluidHeroTone.TertiaryToPrimary else FluidHeroTone.Secondary,
    motif = FluidHeroMotif.Figures,
    eyebrow = stringResource(R.string.approve_eyebrow),
    value = stringResource(if (request.windowOpen) R.string.approve_value_open else R.string.approve_value_expired),
    title = request.name,
    description = stringResource(R.string.approve_request_detail, request.model, agoText(request.ageMs)),
    icon = if (request.windowOpen) Icons.Rounded.HourglassTop else Icons.Rounded.Devices,
  )
}

@Composable
private fun CodeProof(state: ApprovalUiState, actions: ApprovalActions) {
  FluidCard {
    Text(
      text = stringResource(R.string.approve_code_explain),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CodeField(
      value = state.code,
      onValueChange = actions.onCode,
      maxChars = CodeFormat.APPROVAL_CHARS,
      label = stringResource(R.string.approve_code_label),
      placeholder = stringResource(R.string.approve_code_placeholder),
      isError = state.feedback?.message == R.string.fb_mismatch,
      supportingText = stringResource(R.string.approve_code_hint),
      enabled = state.phase == ApprovalPhase.EDITING,
    )
  }
}

@Composable
private fun ScanProof(state: ApprovalUiState, actions: ApprovalActions) {
  FluidCard {
    when (state.scan) {
      ScanState.NONE -> {
        Text(
          text = stringResource(R.string.approve_scan_explain),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QrScanner(
          accept = actions.onScanned,
          onCancel = { actions.onMode(ProofMode.CODE) },
          modifier = Modifier.fillMaxWidth(),
        )
      }
      ScanState.MATCHING -> ScanResult(
        tone = FluidTone.Success,
        title = R.string.approve_scan_ok_title,
        detail = R.string.approve_scan_ok_detail,
        onRescan = actions.onRescan,
      )
      ScanState.WRONG_DEVICE -> ScanResult(
        tone = FluidTone.Danger,
        title = R.string.approve_scan_wrong_title,
        detail = R.string.approve_scan_wrong_detail,
        onRescan = actions.onRescan,
      )
    }
  }
}

@Composable
private fun ScanResult(tone: FluidTone, title: Int, detail: Int, onRescan: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardGap)) {
    StatusPill(
      label = stringResource(if (tone == FluidTone.Success) R.string.approve_scan_ok_pill else R.string.approve_scan_wrong_pill),
      tone = tone,
    )
    Text(
      text = stringResource(title),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = stringResource(detail),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FluidButton(
      text = stringResource(R.string.approve_scan_again),
      onClick = onRescan,
      style = FluidButtonStyle.Tinted,
      size = FluidButtonSize.Medium,
    )
  }
}
