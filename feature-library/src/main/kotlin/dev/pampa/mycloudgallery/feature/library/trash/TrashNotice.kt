package dev.pampa.mycloudgallery.feature.library.trash

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsDimens

// Fase 7, P8 (D26): l'esito dell'ultimo gesto sul cestino del NAS, in una riga in cima alla pagina. Solo codici dentro: le parole sono qui.

/** Cosa e' successo all'ultimo gesto sul cestino del NAS (ripristino o "Elimina subito"). */
enum class TrashNotice(@StringRes val message: Int, val tone: FluidTone) {
  RESTORE_STARTED(R.string.trash_notice_restore_started, FluidTone.Success),

  /** ED-2: il ripristino e' partito per una parte della selezione; le altre voci non si possono ripristinare adesso. */
  RESTORE_PARTIAL(R.string.trash_notice_restore_partial, FluidTone.Warning),

  PURGE_STARTED(R.string.trash_notice_purge_started, FluidTone.Success),
  REJECTED(R.string.trash_notice_rejected, FluidTone.Warning),
  BLOCKED(R.string.trash_notice_blocked, FluidTone.Warning),
  FAILED(R.string.trash_notice_failed, FluidTone.Danger),
  ;

  companion object {
    /** L'avviso per l'esito [outcome] di un ripristino ([purge] falso) o di un "Elimina subito" ([purge] vero). */
    fun of(outcome: NasTrashOutcome, purge: Boolean): TrashNotice = when (outcome) {
      NasTrashOutcome.STARTED -> if (purge) PURGE_STARTED else RESTORE_STARTED
      NasTrashOutcome.REJECTED -> REJECTED
      NasTrashOutcome.BLOCKED -> BLOCKED
      NasTrashOutcome.FAILED -> FAILED
    }
  }
}

/** La riga dell'avviso: si chiude con la X. */
@Composable
internal fun TrashNoticeRow(notice: TrashNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  FluidInlineMessage(
    message = stringResource(notice.message),
    title = stringResource(R.string.trash_notice_title),
    modifier = modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = CollectionsDimens.ExplainerGap),
    tone = notice.tone,
    onDismiss = onDismiss,
  )
}
