package dev.pampa.mycloudgallery.feature.library.utility

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.library.R

// Fase 7, P8: l'esito dell'ultimo gesto sulla pagina Doppioni, in una riga ferma sotto la barra. Solo codici dentro: le parole sono qui.

@get:StringRes
internal val DuplicatesNotice.message: Int
  get() = when (this) {
    DuplicatesNotice.CLEANUP_STARTED -> R.string.duplicates_notice_cleanup_started
    DuplicatesNotice.CLEANUP_REJECTED -> R.string.duplicates_notice_cleanup_rejected
    DuplicatesNotice.CLEANUP_BLOCKED -> R.string.duplicates_notice_cleanup_blocked
    DuplicatesNotice.CLEANUP_FAILED -> R.string.duplicates_notice_cleanup_failed
    // R3: questi due hanno i numeri nel testo: li compone [DuplicatesNoticeRow] con i plurali.
    DuplicatesNotice.CLEANUP_PROTECTED -> R.string.duplicates_notice_cleanup_protected
    DuplicatesNotice.CLEANUP_PARTLY_PROTECTED -> R.string.duplicates_notice_cleanup_partly_protected
    DuplicatesNotice.LAST_SHOT_STAYS -> R.string.duplicates_notice_last_shot
    DuplicatesNotice.NOTHING_TO_CLEAN -> R.string.duplicates_notice_nothing
    DuplicatesNotice.NO_PHONE_TRASH -> R.string.duplicates_notice_no_phone_trash
    DuplicatesNotice.PHONE_CHANGED -> R.string.duplicates_notice_phone_changed
    DuplicatesNotice.NIGHTLY_NOT_SAVED -> R.string.duplicates_notice_nightly_not_saved
  }

internal val DuplicatesNotice.tone: FluidTone
  get() = when (this) {
    DuplicatesNotice.CLEANUP_STARTED -> FluidTone.Success
    DuplicatesNotice.CLEANUP_FAILED, DuplicatesNotice.CLEANUP_PROTECTED -> FluidTone.Danger
    DuplicatesNotice.CLEANUP_PARTLY_PROTECTED, DuplicatesNotice.CLEANUP_REJECTED, DuplicatesNotice.CLEANUP_BLOCKED, DuplicatesNotice.LAST_SHOT_STAYS, DuplicatesNotice.NOTHING_TO_CLEAN,
    DuplicatesNotice.NO_PHONE_TRASH, DuplicatesNotice.PHONE_CHANGED, DuplicatesNotice.NIGHTLY_NOT_SAVED -> FluidTone.Warning
  }

/**
 * Il testo dell'avviso. R3 (correzioni F): quando la pulizia lascia fuori dei file di un album condiviso l'avviso dice **quanti e perche'**, anche per una
 * selezione mista («Pulizia avviata: 3 copie ... Non toccate 2 copie, perche' fanno parte di un album condiviso...»); gli altri avvisi sono frasi fisse.
 */
@Composable
internal fun duplicatesNoticeText(notice: DuplicatesNotice, started: Int, left: Int): String = when (notice) {
  DuplicatesNotice.CLEANUP_PROTECTED -> pluralStringResource(R.plurals.duplicates_notice_cleanup_protected_count, left.coerceAtLeast(1), GalleryNumbers.format(left))
  DuplicatesNotice.CLEANUP_PARTLY_PROTECTED -> stringResource(
    R.string.duplicates_notice_cleanup_partly_protected,
    pluralStringResource(R.plurals.duplicates_notice_copies_started, started.coerceAtLeast(1), GalleryNumbers.format(started)),
    pluralStringResource(R.plurals.duplicates_notice_copies_left, left.coerceAtLeast(1), GalleryNumbers.format(left)),
  )
  else -> stringResource(notice.message)
}

/** La riga dell'avviso: si chiude con la X. */
@Composable
internal fun DuplicatesNoticeRow(notice: DuplicatesNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier, started: Int = 0, left: Int = 0) {
  PageMessage(
    title = stringResource(R.string.duplicates_notice_title),
    detail = duplicatesNoticeText(notice, started, left),
    tone = notice.tone,
    modifier = modifier,
    onDismiss = onDismiss,
  )
}
