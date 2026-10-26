package dev.pampa.mycloudgallery.feature.library.utility

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.library.R

// Fase 7, P8: l'esito dell'ultimo gesto sulla pagina Doppioni, in una riga ferma sotto la barra. Solo codici dentro: le parole sono qui.

@get:StringRes
internal val DuplicatesNotice.message: Int
  get() = when (this) {
    DuplicatesNotice.CLEANUP_STARTED -> R.string.duplicates_notice_cleanup_started
    DuplicatesNotice.CLEANUP_REJECTED -> R.string.duplicates_notice_cleanup_rejected
    DuplicatesNotice.CLEANUP_BLOCKED -> R.string.duplicates_notice_cleanup_blocked
    DuplicatesNotice.CLEANUP_FAILED -> R.string.duplicates_notice_cleanup_failed
    DuplicatesNotice.LAST_SHOT_STAYS -> R.string.duplicates_notice_last_shot
    DuplicatesNotice.NOTHING_TO_CLEAN -> R.string.duplicates_notice_nothing
  }

internal val DuplicatesNotice.tone: FluidTone
  get() = when (this) {
    DuplicatesNotice.CLEANUP_STARTED -> FluidTone.Success
    DuplicatesNotice.CLEANUP_FAILED -> FluidTone.Danger
    DuplicatesNotice.CLEANUP_REJECTED, DuplicatesNotice.CLEANUP_BLOCKED, DuplicatesNotice.LAST_SHOT_STAYS, DuplicatesNotice.NOTHING_TO_CLEAN -> FluidTone.Warning
  }

/** La riga dell'avviso: si chiude con la X. */
@Composable
internal fun DuplicatesNoticeRow(notice: DuplicatesNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  PageMessage(
    title = stringResource(R.string.duplicates_notice_title),
    detail = stringResource(notice.message),
    tone = notice.tone,
    modifier = modifier,
    onDismiss = onDismiss,
  )
}
