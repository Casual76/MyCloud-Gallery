package dev.pampa.mycloudgallery.feature.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridNotice
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * Un'azione dell'utente che non e' riuscita o non e' partita, e che la schermata **deve dire** (revisione di sicurezza, R8
 * e I8). Prima un errore del catalogo era ingoiato: la selezione si chiudeva come se "Nascondi" fosse andato a buon fine, e
 * pochi istanti dopo le foto private tornavano in griglia senza una parola. Ora l'azione dice se e' riuscita ([attempt]) e,
 * se no, la schermata mostra l'avviso e lascia la selezione aperta.
 */
@Immutable
internal sealed interface ActionNotice {
  /** "Nascondi" non e' riuscito: gli elementi sono ancora visibili. */
  data object HideFailed : ActionNotice

  /** "Mostra di nuovo nella Libreria" non e' riuscito: gli elementi restano nascosti. */
  data object ShowFailed : ActionNotice

  /** Il preferito non e' stato salvato. */
  data object FavoriteFailed : ActionNotice

  /** La selezione ha piu' elementi di quanti il selettore di condivisione ne regge (limite delle transazioni Binder). */
  data class ShareTooMany(val limit: Int) : ActionNotice

  /** La selezione ha piu' elementi di quanti una richiesta di cestino ne accetta. */
  data class DeleteTooMany(val limit: Int) : ActionNotice

  /** Il selettore di condivisione non e' partito (Android lo ha rifiutato). */
  data object ShareFailed : ActionNotice

  /** La richiesta di eliminazione non e' partita. */
  data object DeleteFailed : ActionNotice
}

/**
 * Esegue un'azione sul catalogo e dice se e' riuscita. L'azione puo' sollevare (disco pieno, database in errore, galleria
 * ferma): l'eccezione non arriva alla composizione, che chiuderebbe l'app, ne' si ingoia senza dirlo: chi chiama mostra un
 * [ActionNotice] se la risposta e' falso. Solo l'annullamento passa.
 */
internal suspend fun attempt(action: suspend () -> Unit): Boolean =
  try {
    action()
    true
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    false
  }

/** Quanto resta l'avviso se non lo si chiude (ms). */
private const val NoticeMillis: Long = 6_000L

/** L'avviso di [notice], in vetro sopra la griglia: si chiude da solo dopo qualche secondo o con "OK". */
@Composable
internal fun ActionNoticeCard(notice: ActionNotice, backdrop: GlassBackdropState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  LaunchedEffect(notice) {
    delay(NoticeMillis)
    onDismiss()
  }
  val title: String
  val message: String
  when (notice) {
    ActionNotice.HideFailed -> {
      title = stringResource(R.string.notice_hide_failed_title)
      message = stringResource(R.string.notice_hide_failed_message)
    }
    ActionNotice.ShowFailed -> {
      title = stringResource(R.string.notice_show_failed_title)
      message = stringResource(R.string.notice_show_failed_message)
    }
    ActionNotice.FavoriteFailed -> {
      title = stringResource(R.string.notice_favorite_failed_title)
      message = stringResource(R.string.notice_favorite_failed_message)
    }
    is ActionNotice.ShareTooMany -> {
      title = stringResource(R.string.notice_share_too_many_title, GalleryNumbers.format(notice.limit))
      message = stringResource(R.string.notice_too_many_message)
    }
    is ActionNotice.DeleteTooMany -> {
      title = stringResource(R.string.notice_delete_too_many_title, GalleryNumbers.format(notice.limit))
      message = stringResource(R.string.notice_too_many_message)
    }
    ActionNotice.ShareFailed -> {
      title = stringResource(R.string.notice_share_failed_title)
      message = stringResource(R.string.notice_share_failed_message)
    }
    ActionNotice.DeleteFailed -> {
      title = stringResource(R.string.notice_delete_failed_title)
      message = stringResource(R.string.notice_delete_failed_message)
    }
  }
  GalleryGridNotice(
    icon = Icons.Rounded.ReportProblem,
    title = title,
    message = message,
    backdrop = backdrop,
    modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    actionLabel = stringResource(R.string.notice_dismiss),
    onAction = onDismiss,
  )
}
