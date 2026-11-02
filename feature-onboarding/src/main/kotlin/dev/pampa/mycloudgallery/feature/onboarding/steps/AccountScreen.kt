package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import androidx.compose.material3.Icon
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.security.credentials.NasPasswordPolicy
import dev.pampa.mycloudgallery.core.security.input.SecureInput
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.AccountUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.SecretFieldLabel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.passwordWeakness
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient
import dev.pampa.mycloudgallery.feature.onboarding.ui.weaknessText

/** Che cosa non va nel nome utente scritto (controllo subito, prima ancora di toccare la rete). */
internal enum class UserIssue { EMPTY, ADMIN, INVALID }

private val UserPattern = Regex("^[A-Za-z0-9._-]{1,64}$")

/**
 * Il nome utente va bene? `admin` (senza distinzione di maiuscole) non si usa mai nell'app (§8); i nomi del NAS
 * sono ASCII. Il coordinatore rifa' gli stessi controlli: questi servono a non far partire l'unico tentativo con
 * un nome che verrebbe rifiutato comunque.
 */
internal fun userIssue(user: String): UserIssue? {
  val name = user.trim()
  return when {
    name.isEmpty() -> UserIssue.EMPTY
    name.equals("admin", ignoreCase = true) -> UserIssue.ADMIN
    !UserPattern.matches(name) -> UserIssue.INVALID
    else -> null
  }
}

/** Cio' che si sta scrivendo al passo 3. La password resta qui (mai in uno stato salvato). */
@Stable
internal class AccountForm(user: String = "", password: String = "", reveal: Boolean = false) {
  var user: String by mutableStateOf(user)
  var password: String by mutableStateOf(password)
  var reveal: Boolean by mutableStateOf(reveal)

  /** La password nel campo e' quella proposta dall'app (va impostata sul NAS prima di continuare). */
  var generated: Boolean by mutableStateOf(false)

  /** Revisione 2, F1: propone una password forte, la mette nel campo e la mostra (per copiarla sul NAS). */
  fun generate() {
    val chars = NasPasswordPolicy.generate()
    try {
      password = String(chars)
    } finally {
      chars.fill('\u0000')
    }
    reveal = true
    generated = true
  }
}

internal class AccountActions(
  val onSubmit: (user: String, password: CharArray) -> Unit = { _, _ -> },
  val onProblemAction: (ProblemAction) -> Unit = {},
)

/**
 * Passo 3: account e password (§2.1).
 *
 * La pagina e' protetta (`SensitiveScreen`: niente screenshot ne' registrazione dello schermo, sovrapposizioni
 * nascoste, tocchi "oscurati" scartati) e la password usa `SecureInput` (tastiera "password", nessun
 * correttore, punti al posto dei caratteri, con un "mostra" a richiesta perche' un errore di battitura costa
 * un tentativo: ne basta uno per aprire l'interruttore di sicurezza, E-R8). Il tentativo e' **uno**: dopo un
 * errore d'autenticazione il bottone resta spento finche' la password non e' riscritta.
 *
 * Revisione 2, F1: la password deve superare `NasPasswordPolicy` (12 caratteri con tre tipi, o una frase di 16): sotto
 * il campo si spiega che cosa manca e il tasto resta spento. "Proponimi una password forte" ne genera una da impostare
 * sul NAS.
 */
@Composable
internal fun AccountScreen(
  chrome: StepChrome,
  ui: AccountUi,
  form: AccountForm,
  actions: AccountActions,
  modifier: Modifier = Modifier,
  title: String = stringResource(R.string.account_title),
  subtitle: String = stringResource(R.string.account_subtitle),
) {
  SensitiveScreen()
  // Dopo un rifiuto del NAS la password si riscrive da capo: non si rilascia il tentativo con la stessa.
  LaunchedEffect(ui.needsNewPassword) {
    if (ui.needsNewPassword) form.password = ""
  }
  val issue = userIssue(form.user)
  val weakness = passwordWeakness(form.user, form.password)
  val canSubmit = issue == null && form.password.isNotEmpty() && weakness == null && !ui.submitting
  val submit = {
    if (canSubmit) actions.onSubmit(form.user.trim(), form.password.toCharArray())
  }
  StepScaffold(
    title = title,
    subtitle = subtitle,
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.ACCOUNT, urgent = ui.problem.isAuthFailure()),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(if (ui.submitting) R.string.account_submitting else R.string.account_submit),
      onClick = submit,
      enabled = canSubmit,
      loading = ui.submitting,
    ),
  ) {
    item(key = "one-attempt") {
      NoticeCard(
        tone = NoticeTone.Caution,
        icon = Icons.Rounded.Shield,
        title = stringResource(R.string.account_one_attempt_title),
        body = stringResource(R.string.account_one_attempt_detail),
      )
    }
    item(key = "header") { GallerySectionHeader(title = stringResource(R.string.account_section)) }
    item(key = "fields") {
      Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap)) {
        val userError = issue == UserIssue.ADMIN || (issue == UserIssue.INVALID)
        FluidTextField(
          value = form.user,
          onValueChange = { form.user = it },
          label = stringResource(R.string.account_user_label),
          placeholder = stringResource(R.string.account_user_placeholder),
          enabled = !ui.submitting,
          isError = userError,
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
        SecretFieldLabel(
          label = stringResource(R.string.account_password_label),
          revealed = form.reveal,
          onToggle = { form.reveal = !form.reveal },
        )
        FluidTextField(
          value = form.password,
          onValueChange = {
            form.password = it
            form.generated = false
          },
          enabled = !ui.submitting,
          supportingText = when {
            form.password.isEmpty() -> stringResource(R.string.password_rule)
            weakness != null -> weaknessText(weakness)
            else -> null
          },
          keyboardOptions = SecureInput.keyboardOptions(ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { submit() }),
          visualTransformation = if (form.reveal) VisualTransformation.None else SecureInput.hidden,
          showClearButton = false,
        )
      }
    }
    item(key = "generate") {
      if (form.generated) {
        NoticeCard(
          tone = NoticeTone.Info,
          icon = Icons.Rounded.Key,
          title = stringResource(R.string.account_generated_title),
          body = stringResource(R.string.account_generated_detail),
        )
      } else {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.account_generate_title),
            subtitle = stringResource(R.string.account_generate_detail),
            tone = FluidTone.Primary,
            leading = { Icon(Icons.Rounded.Password, contentDescription = null) },
            onClick = if (ui.submitting) null else form::generate,
          )
        }
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.account_footnote)) }
  }
}

private fun OnboardingProblem?.isAuthFailure(): Boolean = when (this) {
  OnboardingProblem.AUTH_FAILED,
  OnboardingProblem.ACCOUNT_LOCKED,
  OnboardingProblem.ACCOUNT_DISABLED,
  OnboardingProblem.PASSWORD_EXPIRED,
  OnboardingProblem.ACCOUNT_RESTRICTED,
  -> true
  else -> false
}
