package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.R

// IL SELETTORE DEI MEDIA (Fase 7, P13): "Aggiungi foto" della pagina di un album e le foto d'esempio di un album smart (`AlbumActions.pickMedia` di
// feature-albums, che `:app` apre con questa rotta). E' la griglia di sempre (`CollectionGridPage`, D17) su tutta la Libreria, che **non ha nascosti**
// (E-H1, D22): parte gia' in selezione, una sola azione ("Scegli"), e al piu' [max] media. Non scrive niente: consegna gli id scelti a chi l'ha aperta.

/**
 * Il selettore: [onPicked] riceve i media scelti nell'ordine della Libreria (dal piu' recente), solo se l'utente ne ha scelto almeno uno e non piu' di
 * [max]; oltre, la pagina lo dice e la selezione resta. [onBack] chiude senza scegliere (anche "Fine" e Indietro).
 */
@Composable
fun MediaPickerRoute(
  title: String,
  max: Int,
  onPicked: (List<MediaId>) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(),
  viewModel: CollectionsViewModel = hiltViewModel(),
) {
  val items = remember(viewModel) { viewModel.allItems() }
  var notice by remember { mutableStateOf<ActionNotice?>(null) }
  CollectionGridPage(
    title = title,
    items = items,
    environment = viewModel.environment,
    secure = false,
    onOpenViewer = {},
    empty = {
      CollectionEmptyState(
        icon = Icons.Rounded.PhotoLibrary,
        title = stringResource(R.string.picker_empty_title),
        detail = stringResource(R.string.picker_empty_message),
      )
    },
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    actionsFor = { view, ids ->
      listOf(
        SelectionAction(Icons.Rounded.Check, R.string.picker_choose) { chosen ->
          if (chosen.size > max) {
            notice = ActionNotice.PickTooMany(max)
          } else {
            onPicked(pickedInOrder(view, chosen))
          }
        },
      ).takeIf { ids.isNotEmpty() }.orEmpty()
    },
    notice = notice,
    onDismissNotice = { notice = null },
    pickMode = true,
  )
}

/** Gli id scelti nell'ordine della vista (la Libreria: dal piu' recente); quelli che la vista non ha piu' (nascosti un attimo fa) si saltano. */
internal fun pickedInOrder(view: dev.pampa.mycloudgallery.core.library.contract.TimelineView, chosen: Set<MediaId>): List<MediaId> =
  chosen.mapNotNull { id -> view.viewIndexOf(id).takeIf { it >= 0 }?.let { it to id } }.sortedBy { it.first }.map { it.second }
