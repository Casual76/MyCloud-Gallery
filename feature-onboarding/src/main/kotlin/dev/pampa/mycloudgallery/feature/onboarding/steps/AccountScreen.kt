package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
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
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/** Sotto questa lunghezza la password funziona ma si avvisa (§2.1 passo 3). */
internal const val MIN_PASSWORD_LENGTH = 12

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
  val canSubmit = issue == null && form.password.isNotEmpty() && !ui.submitting
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
    item(key = "header") { FluidSectionHeader(title = stringResource(R.string.account_section)) }
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
        val short = form.password.isNotEmpty() && form.password.length < MIN_PASSWORD_LENGTH
        SecretFieldLabel(
          label = stringResource(R.string.account_password_label),
          revealed = form.reveal,
          onToggle = { form.reveal = !form.reveal },
        )
        FluidTextField(
          value = form.password,
          onValueChange = { form.password = it },
          enabled = !ui.submitting,
          supportingText = if (short) stringResource(R.string.account_password_short) else null,
          keyboardOptions = SecureInput.keyboardOptions(ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { submit() }),
          visualTransformation = if (form.reveal) VisualTransformation.None else SecureInput.hidden,
          showClearButton = false,
        )
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
