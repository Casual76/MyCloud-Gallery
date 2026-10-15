package dev.pampa.mycloudgallery.feature.nas.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.feature.nas.R

// Le frasi con cui le schermate della 4B dicono perche' una cosa non parte o si e' fermata: una per ogni codice stabile dei contratti
// di core-nasops (`OpErrorCode`, `PlanRejection`, `PauseReason`, `StartOutcome`). Mai un testo che viene dal NAS: solo codici nostri
// (§4.3: i percorsi e i nomi sono dati dell'utente e non finiscono in un messaggio d'errore).

@StringRes
internal fun opErrorRes(code: OpErrorCode): Int = when (code) {
  OpErrorCode.SOURCE_MISSING -> R.string.nas_error_source_missing
  OpErrorCode.SOURCE_CHANGED -> R.string.nas_error_source_changed
  OpErrorCode.NAME_TAKEN -> R.string.nas_error_name_taken
  OpErrorCode.READ_ONLY -> R.string.nas_error_read_only
  OpErrorCode.NO_SPACE -> R.string.nas_error_no_space
  OpErrorCode.QUOTA -> R.string.nas_error_quota
  OpErrorCode.VERIFY_FAILED -> R.string.nas_error_verify_failed
  OpErrorCode.INVALID_NAME -> R.string.nas_error_invalid_name
  OpErrorCode.UNREACHABLE -> R.string.nas_error_unreachable
  OpErrorCode.LOCKED -> R.string.nas_error_locked
  OpErrorCode.LOCKED_TOO_LONG -> R.string.nas_error_locked_too_long
  OpErrorCode.PERMISSION_DENIED -> R.string.nas_error_permission_denied
  OpErrorCode.UNEXPECTED -> R.string.nas_error_unexpected
  OpErrorCode.STOPPED_BY_USER -> R.string.nas_ops_pause_user
}

@StringRes
internal fun planRejectionRes(rejection: PlanRejection): Int = when (rejection) {
  PlanRejection.EMPTY -> R.string.nas_reject_empty
  PlanRejection.READ_ONLY -> R.string.nas_reject_read_only
  PlanRejection.INVALID_NAME -> R.string.nas_reject_invalid_name
  PlanRejection.NAME_EXISTS -> R.string.nas_reject_name_exists
  PlanRejection.SAME_LOCATION -> R.string.nas_reject_same_location
  PlanRejection.INTO_ITSELF -> R.string.nas_reject_into_itself
  PlanRejection.SOURCE_MISSING -> R.string.nas_reject_source_missing
  PlanRejection.PATH_NOT_ALLOWED -> R.string.nas_reject_path_not_allowed
  PlanRejection.TOO_MANY_ITEMS -> R.string.nas_reject_too_many_items
  PlanRejection.NOT_ENOUGH_SPACE -> R.string.nas_reject_not_enough_space
  PlanRejection.WRITES_BLOCKED -> R.string.nas_reject_writes_blocked
  PlanRejection.NAS_UNREACHABLE -> R.string.nas_reject_nas_unreachable
  PlanRejection.NOT_MINE -> R.string.nas_reject_not_mine
}

@StringRes
internal fun pauseReasonRes(reason: PauseReason): Int = when (reason) {
  PauseReason.NAS_UNREACHABLE -> R.string.nas_ops_pause_nas_unreachable
  PauseReason.CONDITIONS -> R.string.nas_ops_pause_conditions
  PauseReason.FROZEN -> R.string.nas_ops_pause_frozen
  PauseReason.CLOCK_JUMP -> R.string.nas_ops_pause_clock_jump
  PauseReason.WRITES_STOPPED -> R.string.nas_ops_pause_writes_stopped
  PauseReason.USER -> R.string.nas_ops_pause_user
}

/** La frase per l'esito di un `start`, o null se l'operazione e' partita e non c'e' niente da dire di storto. */
@StringRes
internal fun startOutcomeRes(outcome: StartOutcome): Int = when (outcome) {
  is StartOutcome.Started -> R.string.nas_started
  is StartOutcome.AlreadyStarted -> R.string.nas_already_started
  is StartOutcome.ConfirmationRejected -> when (outcome.check) {
    ConfirmationCheck.Expired, ConfirmationCheck.FromTheFuture -> R.string.nas_confirmation_expired
    else -> R.string.nas_confirmation_wrong
  }
  is StartOutcome.PlanInvalid -> R.string.nas_plan_invalid
  StartOutcome.WritesBlocked -> R.string.nas_writes_blocked
}

@Composable
internal fun planRejectionText(rejection: PlanRejection): String = stringResource(planRejectionRes(rejection))

@Composable
internal fun opErrorText(code: OpErrorCode): String = stringResource(opErrorRes(code))
