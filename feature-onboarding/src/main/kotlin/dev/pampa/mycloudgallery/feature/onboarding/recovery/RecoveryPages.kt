package dev.pampa.mycloudgallery.feature.onboarding.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.TypedFingerprint
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.security.input.SecureInput
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryUi
import dev.pampa.mycloudgallery.feature.onboarding.steps.RecoveryCodeFormat
import dev.pampa.mycloudgallery.feature.onboarding.ui.CardButton
import dev.pampa.mycloudgallery.feature.onboarding.ui.ChoiceRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.LoadingBlock
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.SecretFieldLabel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.SuccessMark
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient
import java.text.DateFormat
import java.util.Locale
import java.util.TimeZone

// Le pagine di "Recupera l'admin" e "Secondo admin" (docs/fase2-progetto.md §2.3, E-S13). Indirizzo e account
// dell'admin si chiedono con le stesse pagine del passo 2 e 3; qui comincia cio' che e' del recupero: il kit
// nell'area privata, la scelta sul vecchio admin, il codice, l'esito.

@Stable
internal class RecoveryForm(code: String = "", reveal: Boolean = true, fate: OldAdminFate? = null, share: String = "", fingerprint: String = "") {
  /** Revisione 2, F3: l'impronta della famiglia scritta sul foglio (serve senza il QR di stato). */
  var fingerprint: String by mutableStateOf(fingerprint)
  /** L'area privata in cui cercare i kit: se vuota, quella proposta (il nome dell'utente). */
  var share: String by mutableStateOf(share)
  var code: String by mutableStateOf(code)
  var reveal: Boolean by mutableStateOf(reveal)
  var fate: OldAdminFate? by mutableStateOf(fate)
}

internal class RecoveryActions(
  val onSelectKit: (Int) -> Unit = {},
  val onKitsContinue: () -> Unit = {},
  val onRetryKits: () -> Unit = {},
  val onSearchKits: (privateShare: String) -> Unit = {},
  val onChooseFate: (OldAdminFate) -> Unit = {},
  val onSubmitCode: (code: CharArray, fingerprint: String) -> Unit = { _, _ -> },
  val onScanFamilyState: () -> Unit = {},
  val onFinish: () -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
  /** Senza kit ne' codice: la guida "ricominciare da zero" (ON-6). */
  val onStartOverGuide: () -> Unit = {},
)

/** Il codice digitato e' abbastanza lungo da provare ad aprire il kit? (La lunghezza esatta la sa il kit.) */
internal const val MIN_RECOVERY_CODE_LENGTH = 8

/** Pagina 1: i kit di recupero trovati in `recovery/` dell'area privata (solo nomi validi). */
@Composable
internal fun RecoveryKitsPage(
  chrome: StepChrome,
  ui: RecoveryUi,
  form: RecoveryForm,
  actions: RecoveryActions,
  modifier: Modifier = Modifier,
) {
  StepScaffold(
    title = stringResource(if (ui.secondAdmin) R.string.recovery_title_second else R.string.recovery_title),
    subtitle = stringResource(R.string.recovery_kits_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.FAMILY_KEY),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = actions.onKitsContinue,
      enabled = ui.kitsLoaded && ui.kits.isNotEmpty() && !ui.busy,
    ),
  ) {
    item(key = "intro") {
      NoticeCard(
        tone = NoticeTone.Info,
        icon = Icons.Rounded.Info,
        title = stringResource(if (ui.secondAdmin) R.string.recovery_intro_second_title else R.string.recovery_intro_title),
        body = stringResource(if (ui.secondAdmin) R.string.recovery_intro_second_detail else R.string.recovery_intro_detail),
      )
    }
    item(key = "share") {
      Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap)) {
        FluidTextField(
          value = form.share.ifEmpty { ui.privateShare },
          onValueChange = { form.share = it },
          label = stringResource(R.string.recovery_share_label),
          supportingText = stringResource(R.string.recovery_share_hint),
          enabled = !ui.busy,
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
          ),
          keyboardActions = KeyboardActions(onSearch = { actions.onSearchKits(form.share.ifEmpty { ui.privateShare }) }),
        )
        FluidButton(
          text = stringResource(R.string.recovery_share_search),
          onClick = { actions.onSearchKits(form.share.ifEmpty { ui.privateShare }) },
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Medium,
          enabled = ui.kitsLoaded,
        )
      }
    }
    when {
      !ui.kitsLoaded -> item(key = "loading") { LoadingBlock(label = stringResource(R.string.recovery_kits_loading)) }
      // L'area non si e' letta (NAS lento, permesso negato, VPN caduta): lo dice il problema qui sotto, con "Riprova". Non e'
      // "nessun kit" (ON-9).
      ui.problem != null -> Unit
      ui.noKits || ui.kits.isEmpty() -> item(key = "none") {
        NoticeCard(
          tone = NoticeTone.Caution,
          icon = Icons.Rounded.SearchOff,
          title = stringResource(R.string.recovery_none_title),
          body = stringResource(R.string.recovery_none_detail),
        ) {
          CardButton(text = stringResource(R.string.action_retry), onClick = actions.onRetryKits, primary = true)
          CardButton(text = stringResource(R.string.startover_link), onClick = actions.onStartOverGuide, primary = false)
        }
      }
      else -> {
        item(key = "header") {
          FluidSectionHeader(
            title = stringResource(R.string.recovery_kits_section),
            detail = stringResource(R.string.recovery_kits_section_detail),
          )
        }
        item(key = "kits") {
          FluidListGroup {
            ui.kits.forEachIndexed { index, kit ->
              if (index > 0) FluidListDivider()
              ChoiceRow(
                title = kit.createdAt?.let { stringResource(R.string.recovery_kit_dated, formatDate(it)) }
                  ?: stringResource(R.string.recovery_kit_undated),
                subtitle = stringResource(R.string.recovery_kit_share, kit.share),
                selected = ui.selectedKit == index,
                onSelect = { actions.onSelectKit(index) },
                icon = Icons.Rounded.Description,
              )
            }
          }
        }
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
  }
}

/** La data di un kit, in italiano e sempre nello stesso fuso (cosi' una schermata non cambia da un telefono all'altro). */
private fun formatDate(millis: Long): String {
  val format = DateFormat.getDateInstance(DateFormat.LONG, Locale.ITALY)
  format.timeZone = TimeZone.getTimeZone("UTC")
  return format.format(java.util.Date(millis))
}

/**
 * Pagina 2 (solo recupero): cosa fare del vecchio admin. E' una scelta locale e dell'utente, mai di un dato letto dal
 * NAS: "perso" (si azzera appena si ricollega) o "revocato" (il telefono resta suo, ma senza piu' accesso alla
 * famiglia). Nessuna delle due e' preselezionata.
 */
@Composable
internal fun RecoveryFatePage(
  chrome: StepChrome,
  form: RecoveryForm,
  actions: RecoveryActions,
  modifier: Modifier = Modifier,
) {
  val fate = form.fate
  StepScaffold(
    title = stringResource(R.string.recovery_fate_title),
    subtitle = stringResource(R.string.recovery_fate_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.FAMILY_KEY),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = { if (fate != null) actions.onChooseFate(fate) },
      enabled = fate != null,
    ),
  ) {
    item(key = "header") { FluidSectionHeader(title = stringResource(R.string.recovery_fate_section)) }
    item(key = "options") {
      FluidListGroup {
        ChoiceRow(
          title = stringResource(R.string.recovery_fate_lost),
          subtitle = stringResource(R.string.recovery_fate_lost_detail),
          selected = fate == OldAdminFate.LOST,
          onSelect = { form.fate = OldAdminFate.LOST },
          icon = Icons.Rounded.PhoneAndroid,
          tone = FluidTone.Danger,
        )
        FluidListDivider()
        ChoiceRow(
          title = stringResource(R.string.recovery_fate_revoked),
          subtitle = stringResource(R.string.recovery_fate_revoked_detail),
          selected = fate == OldAdminFate.REVOKED,
          onSelect = { form.fate = OldAdminFate.REVOKED },
          icon = Icons.Rounded.Block,
          tone = FluidTone.Warning,
        )
      }
    }
    item(key = "warning") {
      NoticeCard(
        tone = NoticeTone.Caution,
        icon = Icons.Rounded.Warning,
        title = stringResource(R.string.recovery_fate_warning_title),
        body = stringResource(R.string.recovery_fate_warning_detail),
      )
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.recovery_fate_footnote)) }
  }
}

/**
 * Pagina 3: il codice di recupero, che apre il kit. Pagina protetta e `SecureInput`, come ogni campo segreto.
 * Un codice sbagliato non costa niente (e' un confronto locale con il kit gia' letto, senza toccare il NAS):
 * si riprova quante volte si vuole. Facoltativo, lo "stato della famiglia" di un altro dispositivo (M7): senza,
 * la versione minima del registro e' 0 e l'avviso resta in Diagnostica.
 */
@Composable
internal fun RecoveryCodePage(
  chrome: StepChrome,
  ui: RecoveryUi,
  form: RecoveryForm,
  actions: RecoveryActions,
  modifier: Modifier = Modifier,
) {
  SensitiveScreen()
  val normalized = RecoveryCodeFormat.normalize(form.code)
  // Revisione 2, F3: senza il QR di stato serve l'impronta del foglio (il nome del file sul NAS non e' un'ancora).
  val needsFingerprint = ui.familyState == null
  val fingerprintOk = !needsFingerprint || TypedFingerprint.isComplete(form.fingerprint)
  val canSubmit = normalized.length >= MIN_RECOVERY_CODE_LENGTH && fingerprintOk && !ui.busy
  val submit = { if (canSubmit) actions.onSubmitCode(normalized.toCharArray(), if (needsFingerprint) form.fingerprint else "") }
  StepScaffold(
    title = stringResource(R.string.recovery_code_title),
    subtitle = stringResource(R.string.recovery_code_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.FAMILY_KEY, urgent = ui.wrongCode),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(if (ui.busy) R.string.recovery_code_opening else R.string.recovery_code_submit),
      onClick = submit,
      enabled = canSubmit,
      loading = ui.busy,
    ),
  ) {
    item(key = "field") {
      Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap)) {
      SecretFieldLabel(
        label = stringResource(R.string.recovery_code_label),
        revealed = form.reveal,
        onToggle = { form.reveal = !form.reveal },
      )
      FluidTextField(
        value = form.code,
        onValueChange = { form.code = it.uppercase() },
        supportingText = stringResource(R.string.recovery_code_hint),
        enabled = !ui.busy,
        keyboardOptions = SecureInput.keyboardOptions(ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        visualTransformation = if (form.reveal) VisualTransformation.None else SecureInput.hidden,
        showClearButton = false,
      )
      }
    }
    if (ui.wrongCode) {
      item(key = "wrong") {
        NoticeCard(
          tone = NoticeTone.Danger,
          icon = Icons.Rounded.ErrorOutline,
          title = stringResource(R.string.recovery_wrong_title),
          body = stringResource(R.string.recovery_wrong_detail),
        ) {
          CardButton(text = stringResource(R.string.startover_link_code), onClick = actions.onStartOverGuide, primary = false)
        }
      }
    }
    item(key = "state-header") {
      FluidSectionHeader(
        title = stringResource(R.string.manual_state_section),
        detail = stringResource(R.string.recovery_state_detail),
      )
    }
    item(key = "state") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(if (ui.familyState != null) R.string.manual_state_scanned else R.string.manual_state_scan),
          subtitle = if (ui.familyState != null) {
            stringResource(R.string.manual_state_scanned_detail, ui.familyState.registryVersion)
          } else {
            stringResource(R.string.manual_state_scan_detail)
          },
          tone = if (ui.familyState != null) FluidTone.Success else FluidTone.Info,
          leading = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) },
          onClick = actions.onScanFamilyState,
        )
      }
    }
    if (needsFingerprint) {
      item(key = "fingerprint") {
        FluidTextField(
          value = form.fingerprint,
          onValueChange = { form.fingerprint = it.take(MAX_FINGERPRINT_INPUT) },
          label = stringResource(R.string.recovery_fingerprint_label),
          supportingText = stringResource(R.string.recovery_fingerprint_hint),
          isError = form.fingerprint.isNotBlank() && !TypedFingerprint.isComplete(form.fingerprint),
          enabled = !ui.busy,
        )
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.recovery_code_footnote)) }
  }
}

/** Pagina 4: l'esito. */
@Composable
internal fun RecoveryDonePage(
  chrome: StepChrome,
  ui: RecoveryUi,
  fate: OldAdminFate?,
  actions: RecoveryActions,
  modifier: Modifier = Modifier,
) {
  StepScaffold(
    title = stringResource(if (ui.secondAdmin) R.string.recovery_done_title_second else R.string.recovery_done_title),
    subtitle = stringResource(R.string.recovery_done_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.SUMMARY),
    modifier = modifier,
    primary = DockAction(label = stringResource(R.string.action_continue), onClick = actions.onFinish),
  ) {
    item(key = "hero") {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = OnboardingDimens.CardGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
      ) {
        SuccessMark()
        Text(
          text = stringResource(if (ui.secondAdmin) R.string.recovery_done_detail_second else R.string.recovery_done_detail),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
    item(key = "list") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.recovery_done_key),
          subtitle = stringResource(R.string.recovery_done_key_detail),
          tone = FluidTone.Success,
          leading = { Icon(Icons.Rounded.Key, contentDescription = null) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.recovery_done_approved),
          subtitle = stringResource(R.string.recovery_done_approved_detail),
          tone = FluidTone.Success,
          leading = { Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null) },
          disclosure = false,
        )
        if (!ui.secondAdmin && fate != null) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(
              if (fate == OldAdminFate.LOST) R.string.recovery_done_old_lost else R.string.recovery_done_old_revoked,
            ),
            subtitle = stringResource(R.string.recovery_done_old_detail),
            tone = FluidTone.Info,
            leading = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
            disclosure = false,
          )
        }
      }
    }
    if (ui.familyState == null) {
      item(key = "no-state") {
        NoticeCard(
          tone = NoticeTone.Caution,
          icon = Icons.Rounded.QrCodeScanner,
          title = stringResource(R.string.warning_no_min_version_title),
          body = stringResource(R.string.warning_no_min_version_detail),
        )
      }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.recovery_done_footnote)) }
  }
}

/** Spazi e trattini compresi: piu' di cosi' non e' un'impronta. */
private const val MAX_FINGERPRINT_INPUT = 64
