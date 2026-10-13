package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.feature.settings.R

/**
 * L'esito di un'azione, pronto da mostrare in una nota dentro la pagina: un titolo, una frase e il tono.
 *
 * Nessun esito e' muto: ogni `AdminActionResult` e ogni `OnboardingProblem` ha la sua frase, perche' una
 * schermata che non dice cosa e' successo (o cosa fare) e' una schermata bloccata (piano 2.1).
 */
internal class Feedback(
  @StringRes val title: Int,
  @StringRes val message: Int,
  val tone: FluidTone,
)

/** Il feedback di un'azione dell'admin sul registro. */
internal fun AdminActionResult.toFeedback(): Feedback = when (this) {
  AdminActionResult.Done -> Feedback(R.string.fb_done_title, R.string.fb_done, FluidTone.Success)
  AdminActionResult.NewerExists -> Feedback(R.string.fb_newer_title, R.string.fb_newer, FluidTone.Warning)
  AdminActionResult.Fork -> Feedback(R.string.fb_fork_title, R.string.fb_fork, FluidTone.Danger)
  AdminActionResult.Blocked -> Feedback(R.string.fb_blocked_title, R.string.fb_blocked, FluidTone.Danger)
  AdminActionResult.NeedsAppUpdate -> Feedback(R.string.fb_update_title, R.string.fb_update, FluidTone.Warning)
  AdminActionResult.WindowExpired -> Feedback(R.string.fb_expired_title, R.string.fb_expired, FluidTone.Warning)
  AdminActionResult.ProofMismatch -> Feedback(R.string.fb_mismatch_title, R.string.fb_mismatch, FluidTone.Danger)
  AdminActionResult.NotAdmin -> Feedback(R.string.fb_not_admin_title, R.string.fb_not_admin, FluidTone.Warning)
  is AdminActionResult.Failed -> Feedback(R.string.fb_failed_title, error.messageRes(), FluidTone.Danger)
}

/** Il feedback di un passo che non e' riuscito (recupero dell'admin, reset, ingresso...). */
internal fun OnboardingProblem.toFeedback(): Feedback = when (this) {
  OnboardingProblem.AUTH_FAILED -> Feedback(R.string.fb_failed_title, R.string.nas_error_auth_failed, FluidTone.Danger)
  OnboardingProblem.ACCOUNT_LOCKED -> Feedback(R.string.fb_failed_title, R.string.nas_error_account_locked, FluidTone.Danger)
  OnboardingProblem.ACCOUNT_DISABLED -> Feedback(R.string.fb_failed_title, R.string.nas_error_account_disabled, FluidTone.Danger)
  OnboardingProblem.PASSWORD_EXPIRED -> Feedback(R.string.fb_failed_title, R.string.nas_error_password_expired, FluidTone.Danger)
  OnboardingProblem.ACCOUNT_RESTRICTED -> Feedback(R.string.fb_failed_title, R.string.nas_error_account_restricted, FluidTone.Danger)
  OnboardingProblem.IDENTITY_MISMATCH, OnboardingProblem.PASSWORD_SENT_TO_OTHER_NAS ->
    Feedback(R.string.fb_failed_title, R.string.nas_error_identity, FluidTone.Danger)
  OnboardingProblem.NAS_INSECURE -> Feedback(R.string.fb_failed_title, R.string.nas_error_insecure, FluidTone.Danger)
  OnboardingProblem.NAS_NOT_FOUND, OnboardingProblem.NOT_ON_WIFI, OnboardingProblem.NOT_AT_HOME ->
    Feedback(R.string.fb_failed_title, R.string.problem_unreachable, FluidTone.Warning)
  OnboardingProblem.NAS_SERVICE_DOWN -> Feedback(R.string.fb_failed_title, R.string.nas_error_service_down, FluidTone.Warning)
  OnboardingProblem.LOCAL_NETWORK_DENIED -> Feedback(R.string.fb_failed_title, R.string.nas_error_local_network, FluidTone.Warning)
  OnboardingProblem.NAS_WRITE_FAILED, OnboardingProblem.NAS_FULL ->
    Feedback(R.string.fb_failed_title, R.string.problem_write_failed, FluidTone.Danger)
  OnboardingProblem.REGISTRY_UNTRUSTED -> Feedback(R.string.fb_failed_title, R.string.problem_registry_untrusted, FluidTone.Danger)
  OnboardingProblem.NEEDS_APP_UPDATE -> Feedback(R.string.fb_update_title, R.string.fb_update, FluidTone.Warning)
  OnboardingProblem.STEP_NOT_AVAILABLE -> Feedback(R.string.fb_failed_title, R.string.problem_not_available, FluidTone.Warning)
  else -> Feedback(R.string.fb_failed_title, R.string.problem_generic, FluidTone.Danger)
}

/** La nota con un [Feedback] dentro. */
@Composable
internal fun FeedbackNote(feedback: Feedback, modifier: Modifier = Modifier) {
  Note(
    title = stringResource(feedback.title),
    message = stringResource(feedback.message),
    modifier = modifier,
    tone = feedback.tone,
  )
}
