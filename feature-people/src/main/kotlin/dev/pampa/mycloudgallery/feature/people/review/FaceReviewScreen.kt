package dev.pampa.mycloudgallery.feature.people.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.contract.ReviewPurpose
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.PeopleMessageBlock
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.TaskAlt
import dev.pampa.mycloudgallery.feature.people.ui.PeopleFigureState

// "CONTROLLA LE FOTO" E "DIVIDI" (Fase 8, P9; docs/fase8-progetto.md §1.5 `FaceReviewState`, §2.6, E-F20, E-F21, E-F3). La griglia dei volti di
// una persona, dal meno sicuro. In "Controlla le foto" si toccano i volti che non sono di questa persona e si dice "Non e' …" (o "Ignora" per un
// poster, una TV: E-F3); in "Dividi" si scelgono i volti di una persona nuova ("Sposta in una persona nuova", non tutti). I bottoni stanno in
// cima, sotto il titolo (50 dp, con il testo) e, sempre raggiungibili, come icone nella barra: niente galleggia sui volti ne' sotto la barra
// delle schede. TalkBack: ogni volto ha "Seleziona" e, in "Controlla le foto", "Non e' …".

internal interface FaceReviewHandlers {
  fun onBack()

  fun onToggle(face: FaceLocator)

  fun onReject()

  /** L'azione di TalkBack "Non e' …" su un solo volto, senza toccare la selezione. */
  fun onRejectOne(face: FaceLocator)

  fun onIgnore()

  fun onSplit()

  fun onDismissMessage()
}

private const val MAX_COLUMNS = 10

/** La griglia per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. [personName] e' il nome della persona (per "Non e' Anna"). */
@Composable
internal fun FaceReviewScreen(
  ui: FaceReviewUi,
  personName: String?,
  crops: PeopleCrops,
  handlers: FaceReviewHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  val resources = LocalContext.current.resources
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = PeopleDimens.FaceMin, spacing = PeopleDimens.BlockGap, maxColumns = MAX_COLUMNS)
  val grid = ui.state as? FaceReviewState.Grid
  val split = grid?.purpose == ReviewPurpose.SPLIT
  val who = personName ?: stringResource(R.string.persone_unnamed_in_sentence)
  FluidScreen(
    title = stringResource(if (split) R.string.persone_split_title else R.string.persone_review_title),
    subtitle = grid?.let { if (split) stringResource(R.string.persone_split_subtitle) else stringResource(R.string.persone_review_subtitle, who) },
    modifier = modifier,
    onBack = handlers::onBack,
    listState = listState,
    metrics = metrics,
    contentMaxWidth = PeopleDimens.PageWidth,
    actions = {
      if (grid != null && !split) {
        GalleryBarButton(
          icon = Icons.Rounded.Close, contentDescription = stringResource(R.string.persone_review_not_icon, who), onClick = handlers::onReject,
          enabled = grid.selection.isNotEmpty(),
        )
        GalleryBarButton(
          icon = Icons.Rounded.VisibilityOff, contentDescription = stringResource(R.string.persone_review_ignore), onClick = handlers::onIgnore,
          enabled = grid.selection.isNotEmpty(),
        )
      }
      if (grid != null && split) {
        GalleryBarButton(
          icon = Icons.Rounded.CallSplit, contentDescription = stringResource(R.string.persone_split_apply), onClick = handlers::onSplit, enabled = grid.canApply,
        )
      }
    },
  ) {
    ui.message?.let { message -> item(key = "message") { PeopleMessageBlock(message, handlers::onDismissMessage) } }
    when (val state = ui.state) {
      FaceReviewState.Applying -> item(key = "applying") { FluidLoadingBlock() }
      FaceReviewState.Done -> item(key = "done") {
        // Giro 1 (CU-23): la figura della spunta e "Fatto"; quanti volti e dove vale, dietro la «i».
        PeopleFigureState(
          icon = Icons.Rounded.TaskAlt,
          title = stringResource(R.string.persone_review_done_title),
          why = if (ui.changed > 0) resources.getQuantityString(R.plurals.persone_review_done_detail, ui.changed, ui.changed) else stringResource(R.string.persone_review_done_none),
        )
      }
      is FaceReviewState.Grid -> {
        item(key = "actions") {
          Column(verticalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap)) {
            if (split) {
              FluidButton(
                text = stringResource(R.string.persone_split_apply), onClick = handlers::onSplit, enabled = state.canApply,
                size = FluidButtonSize.Large, fillWidth = true,
              )
            } else {
              Row(horizontalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap)) {
                FluidButton(
                  text = stringResource(R.string.persone_review_not, who), onClick = handlers::onReject, enabled = state.canApply,
                  style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, modifier = Modifier.weight(1f),
                )
                FluidButton(
                  text = stringResource(R.string.persone_review_ignore), onClick = handlers::onIgnore, enabled = state.canApply,
                  style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
        if (state.faces.isEmpty()) {
          item(key = "empty") { PeopleFigureState(icon = Icons.Rounded.Face, title = stringResource(R.string.persone_review_empty_title)) }
        } else {
          faceGrid("faces", state.faces, columns) { index, face, tileModifier ->
            val chosen = face.face in state.selection
            val spoken = resources.getString(if (chosen) R.string.persone_face_spoken_selected else R.string.persone_face_spoken, index + 1, state.faces.size)
            val extra = if (split) {
              emptyList()
            } else {
              listOf(
                CustomAccessibilityAction(resources.getString(R.string.persone_review_not, personName ?: resources.getString(R.string.persone_unnamed_in_sentence))) {
                  handlers.onRejectOne(face.face)
                  true
                },
              )
            }
            FaceTile(
              face = face,
              crops = crops,
              selected = chosen,
              spoken = spoken,
              label = null,
              selectedLabel = stringResource(R.string.persone_face_selected),
              selectLabel = stringResource(if (chosen) R.string.persone_action_deselect else R.string.persone_action_select),
              extraActions = extra,
              onClick = { handlers.onToggle(face.face) },
              onLongClick = null,
              modifier = tileModifier,
            )
          }
        }
      }
    }
  }
}
