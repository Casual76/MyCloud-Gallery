package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryLiveAnnouncement
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryCellCustomAction
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.attempt
import dev.pampa.mycloudgallery.feature.library.countText
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Fase 10, P8 (D23, E-AC7, E-AC9): le azioni di una cella al posto dei gesti, per TalkBack e Switch Access. Fanno quello che fa la
// barra delle azioni della selezione, ma su **un** media: nessuna scorciatoia nuova, nessuna scrittura che la barra non faccia gia'.
// "Elimina" non elimina niente da sola: seleziona quel media e apre lo stesso foglio di conferma ("Elimina 1 elemento dal telefono").

/** Le etichette delle azioni di una cella, risolte una volta (le azioni si costruiscono solo quando il lettore le chiede). */
internal class LibraryCellLabels(
  val favorite: String,
  val unfavorite: String,
  val share: String,
  val hide: String,
  val delete: String,
)

@Composable
internal fun rememberLibraryCellLabels(): LibraryCellLabels {
  val favorite = stringResource(R.string.cell_action_favorite)
  val unfavorite = stringResource(R.string.cell_action_unfavorite)
  val share = stringResource(R.string.cell_action_share)
  val hide = stringResource(R.string.cell_action_hide)
  val delete = stringResource(R.string.cell_action_delete)
  return remember(favorite, unfavorite, share, hide, delete) { LibraryCellLabels(favorite, unfavorite, share, hide, delete) }
}

/**
 * Le azioni personalizzate del media [id]: Preferito (o Togli dai preferiti), Condividi, Nascondi e, dove il cestino di sistema c'e',
 * Elimina dal telefono. [onDelete] e [onHideGroups] aprono i fogli della Libreria, che leggono la selezione.
 */
internal fun libraryCellActions(
  state: LibraryTabState,
  id: MediaId,
  labels: LibraryCellLabels,
  scope: CoroutineScope,
  onDelete: () -> Unit,
  onHideGroups: () -> Unit,
): List<GalleryCellCustomAction> {
  val grid = state.grid
  val ids = setOf(id)
  val favorite = grid.isFavorite(id)
  return buildList {
    add(
      GalleryCellCustomAction(if (favorite) labels.unfavorite else labels.favorite) {
        scope.launch { if (!attempt { state.actions.setFavorite(ids, !favorite) }) state.notice = ActionNotice.FavoriteFailed }
        true
      },
    )
    add(GalleryCellCustomAction(labels.share) { state.shareSelection(ids); true })
    add(
      GalleryCellCustomAction(labels.hide) {
        // Come dalla barra: nascondere la copertina di un gruppo nasconde tutto il gruppo, e prima lo si dice.
        if (grid.hiddenWithGroups(ids) > ids.size) {
          selectOnly(grid, id)
          onHideGroups()
        } else {
          scope.launch { state.hide(ids) }
        }
        true
      },
    )
    if (state.commands.canDeleteFromPhone) {
      add(
        GalleryCellCustomAction(labels.delete) {
          if (state.commands.onPhone(ids).isEmpty()) {
            state.notice = ActionNotice.DeleteNasOnly
          } else {
            selectOnly(grid, id)
            onDelete()
          }
          true
        },
      )
    }
  }
}

/** Il media scelto e basta: la selezione che il foglio di conferma legge. */
private fun selectOnly(grid: MediaGridState, id: MediaId) {
  grid.startSelecting()
  grid.selection.clear()
  grid.selection.toggle(id)
}

/**
 * L'annuncio della selezione per i lettori di schermo: un nodo di un punto, senza disegno, con `liveRegion` educata e il numero
 * degli scelti. Si annuncia a ogni cambio ("12 selezionati") e solo in selezione; senza selezione non c'e' niente da dire.
 */
@Composable
internal fun SelectionAnnouncer(grid: MediaGridState) {
  val selected by grid.selection.selected.collectAsState()
  val text = if (grid.selecting && selected.size > 0) countText(R.plurals.selection_count, selected.size) else null
  if (text != null) GalleryLiveAnnouncement(text)
}
