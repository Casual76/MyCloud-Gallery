package dev.pampa.mycloudgallery.feature.onboarding.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.security.input.SecureInput
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinUi
import dev.pampa.mycloudgallery.feature.onboarding.steps.UserIssue
import dev.pampa.mycloudgallery.feature.onboarding.steps.userIssue
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.passwordWeakness
import dev.pampa.mycloudgallery.feature.onboarding.ui.weaknessText
import dev.pampa.mycloudgallery.feature.onboarding.ui.SecretFieldLabel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.SuccessMark
import dev.pampa.mycloudgallery.feature.onboarding.ui.spec
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

// Le pagine di "Entra con il QR" e "Entra senza QR" (docs/fase2-progetto.md §2.2, lato nuovo dispositivo):
// scansione (o dati a mano) -> riepilogo -> password -> nome -> codice di approvazione e attesa.

// --- Entra senza QR: i dati a mano ------------------------------------------------------------------

@Stable
internal class ManualJoinForm(
  ip: String = "",
  user: String = "",
  familyId: String = "",
  fingerprint: String = "",
  deviceName: String = "",
) {
  var ip: String by mutableStateOf(ip)
  var user: String by mutableStateOf(user)
  var familyId: String by mutableStateOf(familyId)
  var fingerprint: String by mutableStateOf(fingerprint)
  var deviceName: String by mutableStateOf(deviceName)

  val address: NasAddress? get() = NasAddress.parseOrNull(ip)
  val userIssue: UserIssue? get() = userIssue(user)
  val isComplete: Boolean
    get() = address != null && userIssue == null && familyId.isNotBlank() && fingerprint.isNotBlank() && deviceName.isNotBlank()
}

internal class ManualJoinActions(
  val onSubmit: (ManualJoinForm) -> Unit = {},
  val onScanFamilyState: () -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

/**
 * "Entra senza QR": i dati che l'admin legge dal suo schermo, scritti a mano (§2.2, "Senza QR"). Indirizzo,
 * utente, id della famiglia e impronta si prendono **dalla schermata dell'admin, mai dal NAS** (B9): l'unico
 * modo per sapere che sono quelli giusti. Senza il QR "stato della famiglia" (M7) si entra con la versione minima
 * del registro piu' bassa, e l'avviso resta in Diagnostica.
 */
@Composable
internal fun JoinManualPage(
  chrome: StepChrome,
  ui: JoinUi,
  form: ManualJoinForm,
  actions: ManualJoinActions,
  modifier: Modifier = Modifier,
) {
  val ipInvalid = form.ip.isNotBlank() && form.address == null
  StepScaffold(
    title = stringResource(R.string.manual_title),
    subtitle = stringResource(R.string.manual_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.NAS_ADDRESS),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = { if (form.isComplete) actions.onSubmit(form) },
      enabled = form.isComplete && !ui.busy,
      loading = ui.busy,
    ),
  ) {
    item(key = "intro") {
      NoticeCard(
        tone = NoticeTone.Info,
        icon = Icons.Rounded.Info,
        title = stringResource(R.string.manual_intro_title),
        body = stringResource(R.string.manual_intro_detail),
      )
    }
    item(key = "fields-header") { FluidSectionHeader(title = stringResource(R.string.manual_section)) }
    item(key = "fields") {
      Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap)) {
        FluidTextField(
          value = form.ip,
          onValueChange = { form.ip = it },
          label = stringResource(R.string.address_ip_label),
          placeholder = stringResource(R.string.address_ip_placeholder),
          isError = ipInvalid,
          supportingText = if (ipInvalid) stringResource(R.string.address_ip_invalid) else null,
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
          ),
        )
        val issue = form.userIssue.takeIf { form.user.isNotBlank() }
        FluidTextField(
          value = form.user,
          onValueChange = { form.user = it },
          label = stringResource(R.string.account_user_label),
          placeholder = stringResource(R.string.account_user_placeholder),
          isError = issue != null,
          supportingText = when (issue) {
            UserIssue.ADMIN -> stringResource(R.string.account_user_admin)
            UserIssue.INVALID -> stringResource(R.string.account_user_invalid)
            else -> null
          },
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
          ),
        )
        FluidTextField(
          value = form.familyId,
          onValueChange = { form.familyId = it },
          label = stringResource(R.string.manual_family_id),
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
          ),
        )
        FluidTextField(
          value = form.fingerprint,
          onValueChange = { form.fingerprint = it },
          label = stringResource(R.string.manual_fingerprint),
          supportingText = stringResource(R.string.manual_fingerprint_hint),
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
          ),
        )
        FluidTextField(
          value = form.deviceName,
          onValueChange = { form.deviceName = it },
          label = stringResource(R.string.manual_device_name),
          placeholder = stringResource(R.string.manual_device_name_placeholder),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { if (form.isComplete) actions.onSubmit(form) }),
        )
      }
    }
    item(key = "state-header") {
      FluidSectionHeader(
        title = stringResource(R.string.manual_state_section),
        detail = stringResource(R.string.manual_state_section_detail),
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
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
  }
}

// --- Il riepilogo dell'invito -----------------------------------------------------------------------

internal class JoinSummaryActions(
  val onContinue: () -> Unit = {},
  val onRescan: () -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

/**
 * Il riepilogo dell'invito, **prima di toccare la rete**: chi e' la famiglia, quale NAS, quale account, quale
 * dispositivo, con l'impronta della famiglia in gruppi (e' la cosa da confrontare con l'admin se si ha un dubbio).
 * Solo dopo "Continua" l'app controlla la casa e l'identita' del NAS e chiede la password. Un invito scaduto o di
 * un'altra famiglia (E-O5) non arriva fin qui: lo scanner lo rifiuta; se arriva un problema dopo, la pagina lo mostra.
 */
@Composable
internal fun JoinSummaryPage(
  chrome: StepChrome,
  ui: JoinUi,
  invite: FamilyInvite,
  expiresLabel: String?,
  actions: JoinSummaryActions,
  modifier: Modifier = Modifier,
) {
  val problem = ui.problem
  val bigProblem = problem != null && problem.spec().big
  StepScaffold(
    title = stringResource(R.string.summary_invite_title),
    subtitle = stringResource(R.string.summary_invite_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.NAS_ADDRESS, urgent = bigProblem && problem?.spec()?.tone == NoticeTone.Danger),
    modifier = modifier,
    primary = if (bigProblem) {
      null
    } else {
      DockAction(
        label = stringResource(if (ui.busy) R.string.summary_invite_checking else R.string.action_continue),
        onClick = actions.onContinue,
        enabled = !ui.busy,
        loading = ui.busy,
      )
    },
    secondary = DockAction(
      label = stringResource(R.string.summary_invite_rescan),
      onClick = actions.onRescan,
      enabled = !ui.busy,
      style = FluidButtonStyle.Plain,
    ),
  ) {
    if (problem != null && bigProblem) {
      item(key = "problem-big") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    } else {
      item(key = "rows") {
        FluidListGroup {
          InviteRow(Icons.Rounded.Dns, R.string.summary_invite_nas, invite.nasName ?: invite.nasIp, invite.nasIp.takeIf { invite.nasName != null })
          FluidListDivider()
          InviteRow(Icons.Rounded.Person, R.string.summary_invite_account, invite.nasUser, null)
          FluidListDivider()
          InviteRow(Icons.Rounded.Smartphone, R.string.summary_invite_device, invite.deviceName, null)
          FluidListDivider()
          InviteRow(Icons.Rounded.FolderShared, R.string.summary_invite_common, invite.commonShare, null)
          FluidListDivider()
          InviteRow(Icons.Rounded.FolderSpecial, R.string.summary_invite_private, invite.privateShare, null)
          if (expiresLabel != null) {
            FluidListDivider()
            InviteRow(Icons.Rounded.Timer, R.string.summary_invite_expires, expiresLabel, null)
          }
        }
      }
      item(key = "fingerprint-header") {
        FluidSectionHeader(
          title = stringResource(R.string.summary_invite_fingerprint),
          detail = stringResource(R.string.summary_invite_fingerprint_detail),
        )
      }
      item(key = "fingerprint") {
        FluidListGroup {
          FluidListRow(
            title = invite.familyKeyFingerprint.chunked(FINGERPRINT_GROUP).joinToString(" "),
            subtitle = "",
            tone = FluidTone.Primary,
            leading = { Icon(Icons.Rounded.Fingerprint, contentDescription = null) },
            disclosure = false,
          )
        }
      }
      item(key = "next") {
        NoticeCard(
          tone = NoticeTone.Info,
          icon = Icons.Rounded.Shield,
          title = stringResource(R.string.summary_invite_next_title),
          body = stringResource(R.string.summary_invite_next_detail),
        )
      }
      if (problem != null) {
        item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
      }
    }
  }
}

private const val FINGERPRINT_GROUP = 4

@Composable
private fun InviteRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, value: String, detail: String?) {
  FluidListRow(
    title = value,
    subtitle = detail ?: "",
    eyebrow = stringResource(label),
    tone = FluidTone.Primary,
    leading = { Icon(icon, contentDescription = null) },
    disclosure = false,
  )
}

// --- La password ------------------------------------------------------------------------------------

@Stable
internal class JoinPasswordForm(password: String = "", reveal: Boolean = false) {
  var password: String by mutableStateOf(password)
  var reveal: Boolean by mutableStateOf(reveal)
}

internal class JoinPasswordActions(
  val onSubmit: (CharArray) -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

/**
 * La password: l'**unico campo** da scrivere per entrare con il QR. L'account lo dice l'invito. Pagina protetta
 * (`SensitiveScreen`), `SecureInput` e un solo tentativo: dopo un errore la password va riscritta (E-R8, E-S12).
 */
@Composable
internal fun JoinPasswordPage(
  chrome: StepChrome,
  ui: JoinUi,
  account: String,
  form: JoinPasswordForm,
  actions: JoinPasswordActions,
  modifier: Modifier = Modifier,
) {
  SensitiveScreen()
  LaunchedEffect(ui.needsNewPassword) {
    if (ui.needsNewPassword) form.password = ""
  }
  // Revisione 2, F1: la stessa soglia dell'admin; se la password ricevuta e' debole la cambia chi gestisce il NAS.
  val weakness = passwordWeakness(account, form.password)
  val canSubmit = form.password.isNotEmpty() && weakness == null && !ui.busy
  val submit = { if (canSubmit) actions.onSubmit(form.password.toCharArray()) }
  StepScaffold(
    title = stringResource(R.string.join_password_title),
    subtitle = stringResource(R.string.join_password_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.ACCOUNT, urgent = ui.problem != null),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(if (ui.busy) R.string.account_submitting else R.string.join_password_submit),
      onClick = submit,
      enabled = canSubmit,
      loading = ui.busy,
    ),
  ) {
    item(key = "account") {
      FluidListGroup {
        FluidListRow(
          title = account,
          subtitle = stringResource(R.string.join_password_account_detail),
          eyebrow = stringResource(R.string.summary_invite_account),
          tone = FluidTone.Primary,
          leading = { Icon(Icons.Rounded.Person, contentDescription = null) },
          badge = { Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
          disclosure = false,
        )
      }
    }
    item(key = "field") {
      Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap)) {
      SecretFieldLabel(
        label = stringResource(R.string.account_password_label),
        revealed = form.reveal,
        onToggle = { form.reveal = !form.reveal },
      )
      FluidTextField(
        value = form.password,
        onValueChange = { form.password = it },
        enabled = !ui.busy,
        supportingText = weakness?.let { weaknessText(it) + " " + stringResource(R.string.join_password_weak_ask_admin) },
        keyboardOptions = SecureInput.keyboardOptions(ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        visualTransformation = if (form.reveal) VisualTransformation.None else SecureInput.hidden,
        showClearButton = false,
      )
      }
    }
    item(key = "one-attempt") {
      NoticeCard(
        tone = NoticeTone.Caution,
        icon = Icons.Rounded.Shield,
        title = stringResource(R.string.account_one_attempt_title),
        body = stringResource(R.string.account_one_attempt_detail),
      )
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.join_password_footnote)) }
  }
}

// --- Il nome del dispositivo ------------------------------------------------------------------------

@Stable
internal class JoinNameForm(name: String = "") {
  var name: String by mutableStateOf(name)
}

internal class JoinNameActions(
  val onSubmit: (String) -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

/** Il nome con cui l'admin vedra' questo dispositivo (o profilo del tablet), proposto dall'app e modificabile. */
@Composable
internal fun JoinNamePage(
  chrome: StepChrome,
  ui: JoinUi,
  form: JoinNameForm,
  actions: JoinNameActions,
  modifier: Modifier = Modifier,
) {
  val canSubmit = form.name.isNotBlank() && !ui.busy
  val submit = { if (canSubmit) actions.onSubmit(form.name.trim()) }
  StepScaffold(
    title = stringResource(R.string.join_name_title),
    subtitle = stringResource(R.string.join_name_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.ACCOUNT),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(if (ui.busy) R.string.join_name_sending else R.string.join_name_submit),
      onClick = submit,
      enabled = canSubmit,
      loading = ui.busy,
    ),
  ) {
    item(key = "field") {
      FluidTextField(
        value = form.name,
        onValueChange = { form.name = it },
        label = stringResource(R.string.manual_device_name),
        placeholder = stringResource(R.string.manual_device_name_placeholder),
        enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
      )
    }
    item(key = "hint") {
      NoticeCard(
        tone = NoticeTone.Info,
        icon = Icons.Rounded.Info,
        title = stringResource(R.string.join_name_hint_title),
        body = stringResource(R.string.join_name_hint_detail),
      )
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
  }
}

// --- Approvato --------------------------------------------------------------------------------------

/** L'admin ha approvato: il dispositivo e' in famiglia. */
@Composable
internal fun JoinApprovedPage(chrome: StepChrome, onFinish: () -> Unit, modifier: Modifier = Modifier) {
  StepScaffold(
    title = stringResource(R.string.approved_title),
    subtitle = stringResource(R.string.approved_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.SUMMARY),
    modifier = modifier,
    primary = DockAction(label = stringResource(R.string.summary_finish), onClick = onFinish),
  ) {
    item(key = "hero") {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = OnboardingDimens.CardGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
      ) {
        SuccessMark()
        Text(
          text = stringResource(R.string.approved_detail),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}
