package dev.pampa.mycloudgallery.feature.people.list

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.OthersUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.review.FaceTile
import dev.pampa.mycloudgallery.feature.people.review.faceGrid
import dev.pampa.mycloudgallery.feature.people.sheets.ForgetSheet
import dev.pampa.mycloudgallery.feature.people.sheets.MergeHandlers
import dev.pampa.mycloudgallery.feature.people.sheets.MergeSheet
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.PeopleMessageBlock
import dev.pampa.mycloudgallery.feature.people.ui.PeopleText
import androidx.compose.material.icons.rounded.Group
import dev.pampa.mycloudgallery.feature.people.ui.PeopleFigureState

// "ALTRI VOLTI" (Fase 8, P9; docs/fase8-progetto.md Q2 (b) del 27/10, §1.5 `FaceReviewState` con `ReviewPurpose.OTHERS`, §2.6): i gruppi
// senza nome con almeno 3 foto, dal piu' grande, a un tocco dalle Raccolte. Una griglia di volti rotondi, uno per gruppo, con **sotto** il conto
// delle foto. Un tocco apre la pagina del gruppo (dove si da' il nome: "Aggiungi un nome"); la pressione lunga entra nella selezione, con
// "Unisci" e "Dimentica" (con la conferma) nella barra. Nessun gruppo sparisce. Solo componenti G2.

internal interface OthersHandlers : MergeHandlers {
  fun onBack()

  fun onOpenPerson(id: PersonId)

  fun onSetSelecting(on: Boolean)

  fun onStartWith(id: PersonId)

  fun onToggle(id: PersonId)

  fun onMergeSelected()

  fun onAskForget()

  fun onConfirmForget()

  fun onDismissForget()

  fun onDismissMessage()
}

private const val MAX_COLUMNS = 8

@Composable
internal fun OthersScreen(
  ui: OthersUi,
  crops: PeopleCrops,
  handlers: OthersHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  val resources = LocalContext.current.resources
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = PeopleDimens.TileMin, spacing = PeopleDimens.BlockGap, maxColumns = MAX_COLUMNS)
  val grid = ui.state as? FaceReviewState.Grid
  val chosen = grid?.faces?.filter { it.face in grid.selection }?.mapNotNull { it.person }.orEmpty()
  FluidScreen(
    title = if (ui.selecting) resources.getQuantityString(R.plurals.persone_selected_groups, chosen.size, chosen.size) else stringResource(R.string.persone_others_title),
    // I1 (fase I): niente frase sotto il titolo, i volti dicono che cosa c'e'.
    modifier = modifier,
    onBack = handlers::onBack,
    listState = listState,
    metrics = metrics,
    contentMaxWidth = PeopleDimens.PageWidth,
    actions = {
      if (ui.selecting) {
        GalleryBarButton(
          icon = Icons.Rounded.CallMerge, contentDescription = stringResource(R.string.persone_merge_action), onClick = handlers::onMergeSelected,
          enabled = chosen.size >= 2,
        )
        GalleryBarButton(
          icon = Icons.Rounded.Delete, contentDescription = stringResource(R.string.persone_forget_action), onClick = handlers::onAskForget,
          enabled = chosen.isNotEmpty(),
        )
        GalleryBarButton(icon = Icons.Rounded.Close, contentDescription = stringResource(R.string.persone_select_done), onClick = { handlers.onSetSelecting(false) })
      } else if (grid != null && grid.faces.isNotEmpty()) {
        GalleryBarButton(icon = Icons.Rounded.Check, contentDescription = stringResource(R.string.persone_select), onClick = { handlers.onSetSelecting(true) })
      }
    },
  ) {
    ui.message?.let { message -> item(key = "message") { PeopleMessageBlock(message, handlers::onDismissMessage) } }
    when {
      grid == null -> item(key = "loading") { FluidLoadingBlock() }
      grid.faces.isEmpty() -> item(key = "empty") {
        PeopleFigureState(
          icon = Icons.Rounded.Group,
          title = stringResource(R.string.persone_others_empty_title),
          why = stringResource(R.string.persone_others_empty_detail),
        )
      }
      else -> faceGrid("others", grid.faces, columns) { _, face, tileModifier ->
        val group = face.person?.let { ui.groups[it] }
        val selected = face.face in grid.selection
        val spoken = group?.let { PeopleText.spoken(resources, it) } ?: resources.getString(R.string.persone_unnamed_spoken)
        FaceTile(
          face = face,
          crops = crops,
          selected = selected,
          spoken = spoken,
          // I1: niente conto sotto il volto (lo dice TalkBack nella frase della tessera).
          label = null,
          selectedLabel = stringResource(R.string.persone_face_selected),
          selectLabel = stringResource(if (selected) R.string.persone_action_deselect else R.string.persone_action_select),
          extraActions = emptyList(),
          onClick = { face.person?.let { if (ui.selecting) handlers.onToggle(it) else handlers.onOpenPerson(it) } },
          onLongClick = if (ui.selecting) null else ({ face.person?.let(handlers::onStartWith) ?: Unit }),
          modifier = tileModifier,
        )
      }
    }
  }
  MergeSheet(ui.merge, crops, handlers)
  ForgetSheet(
    persons = if (ui.forgetting) chosen.mapNotNull { ui.groups[it] } else emptyList(),
    crops = crops,
    onConfirm = handlers::onConfirmForget,
    onDismiss = handlers::onDismissForget,
  )
}

/** Nessuna azione: per le anteprime e gli screenshot. */
internal object NoOthersHandlers : OthersHandlers {
  override fun onBack() = Unit

  override fun onOpenPerson(id: PersonId) = Unit

  override fun onSetSelecting(on: Boolean) = Unit

  override fun onStartWith(id: PersonId) = Unit

  override fun onToggle(id: PersonId) = Unit

  override fun onMergeSelected() = Unit

  override fun onAskForget() = Unit

  override fun onConfirmForget() = Unit

  override fun onDismissForget() = Unit

  override fun onDismissMessage() = Unit

  override fun onToggleCandidate(id: PersonId) = Unit

  override fun onContinueFromChoose() = Unit

  override fun onPick(id: PersonId) = Unit

  override fun onContinueFromName() = Unit

  override fun onConfirmMerge() = Unit

  override fun onRejectProposal() = Unit

  override fun onDismissMerge() = Unit
}
