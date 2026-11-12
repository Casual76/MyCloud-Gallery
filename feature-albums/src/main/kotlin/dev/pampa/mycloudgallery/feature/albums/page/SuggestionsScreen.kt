package dev.pampa.mycloudgallery.feature.albums.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumFigureState
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText
import dev.pampa.mycloudgallery.feature.albums.ui.SectionNote

// "SUGGERITE" E "DA CONDIVIDERE" (Fase 7, P10; docs/fase7-progetto.md D14, D17, Q4 (a), E-AL3-E-AL7). Una griglia di foto che l'app **non** ha messo da
// sola, e sotto, in una barra che sta nel layout (mai sopra le foto), cosa si puo' decidere: "Accetta" / "Escludi" per le suggerite, "Condividi" per
// quelle da condividere. Senza una selezione l'azione vale per tutte e il bottone lo dice col numero. Il tocco su una foto la apre nel viewer;
// con "Seleziona" il tocco sceglie. Le foto non sono mai nascoste: la vista viene da `Albums.content`.

interface SuggestionsHandlers {
  fun onBack()

  fun onToggleSelecting()

  fun onToggle(media: MediaId)

  fun onOpen(media: MediaId)

  fun onSelectAll()

  fun onClear()

  fun onAccept()

  fun onExclude()

  fun onShare()

  fun onDismissMessage()
}

object NoSuggestionsHandlers : SuggestionsHandlers {
  override fun onBack() = Unit

  override fun onToggleSelecting() = Unit

  override fun onToggle(media: MediaId) = Unit

  override fun onOpen(media: MediaId) = Unit

  override fun onSelectAll() = Unit

  override fun onClear() = Unit

  override fun onAccept() = Unit

  override fun onExclude() = Unit

  override fun onShare() = Unit

  override fun onDismissMessage() = Unit
}

@Composable
fun SuggestionsScreen(
  ui: SuggestionsUi,
  thumbnails: AlbumThumbnails,
  handlers: SuggestionsHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  val toShare = ui.kind == SuggestionKind.TO_SHARE
  val resources = LocalContext.current.resources
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = AlbumDimens.CellMin, spacing = GalleryWall.Gap, maxColumns = 12)
  val style = rememberGalleryGridCellStyle(cornerScale = GalleryWall.CornerScale)
  val interaction = remember { GalleryGridInteractionState() }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  LaunchedEffect(ui.selecting) { interaction.animateSelectionMode(ui.selecting, reduced) }
  SideEffect { interaction.selectedKeys = ui.selected }
  val view = ui.view
  FollowGridWindow(listState, thumbnails, if (view == null) emptyList() else listOf(GridWindowSource("sug", view, columns)))
  val actions = view?.let {
    rememberCellActions(
      view = it, coverMedia = null,
      clickLabel = stringResource(if (ui.selecting) R.string.suggestions_toggle else R.string.suggestions_open),
      longClickLabel = null,
      onTap = { media -> if (ui.selecting) handlers.onToggle(media) else handlers.onOpen(media) },
      onLong = {},
    )
  }
  Column(modifier = modifier.fillMaxSize()) {
    FluidScreen(
      title = stringResource(if (toShare) R.string.suggestions_to_share_title else R.string.suggestions_title),
      subtitle = ui.album?.title,
      modifier = Modifier.weight(1f),
      onBack = handlers::onBack,
      listState = listState,
      metrics = metrics,
      itemSpacing = GalleryWall.Gap,
      contentMaxWidth = AlbumDimens.PageWidth,
      actions = {
        if (ui.count > 0) {
          GalleryBarButton(
            icon = if (ui.selecting) Icons.Rounded.Check else Icons.Rounded.CheckCircleOutline,
            contentDescription = stringResource(if (ui.selecting) R.string.suggestions_done else R.string.suggestions_select),
            onClick = handlers::onToggleSelecting,
          )
        }
      },
    ) {
      item(key = "intro") {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = AlbumDimens.BlockGap), verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
          ui.message?.let { message ->
            FluidInlineMessage(message = messageText(message), title = stringResource(R.string.album_message_dismiss), tone = FluidTone.Warning, onDismiss = handlers::onDismissMessage)
          }
          if (ui.count > 0) {
            Text(
              text = resources.getQuantityString(if (toShare) R.plurals.suggestions_to_share_intro else R.plurals.suggestions_intro, ui.count, ui.count, ui.album?.title.orEmpty()),
              style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      if (view == null || ui.count == 0) {
        item(key = "empty") {
          AlbumFigureState(
            icon = Icons.Rounded.PhotoLibrary,
            title = stringResource(R.string.suggestions_empty_title),
            detail = stringResource(if (toShare) R.string.suggestions_to_share_empty_detail else R.string.suggestions_empty_detail),
          )
        }
      } else if (actions != null) {
        albumGrid(
          keyPrefix = "sug", view = view, columns = columns, thumbnails = thumbnails, style = style, interaction = interaction, actions = actions,
          onTap = { media -> if (ui.selecting) handlers.onToggle(media) else handlers.onOpen(media) }, onLong = null,
        )
      }
    }
    if (ui.count > 0) DecisionBar(ui, toShare, handlers)
  }
}

/** La barra delle decisioni: sta sotto la griglia nel layout (la griglia finisce dove comincia lei), con i bottoni incolonnati da 50 dp. */
@Composable
private fun DecisionBar(ui: SuggestionsUi, toShare: Boolean, handlers: SuggestionsHandlers) {
  val resources = LocalContext.current.resources
  val picked = ui.selected.size
  val n = if (picked > 0) picked else ui.count
  fun label(all: Int, selected: Int): String = resources.getString(if (picked > 0) selected else all, n)
  Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
    Column(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AlbumDimens.Inset, vertical = AlbumDimens.BlockGap),
      verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap),
    ) {
      if (ui.selecting) {
        FluidButton(
          text = stringResource(if (picked > 0) R.string.suggestions_done else R.string.suggestions_select_all),
          onClick = if (picked > 0) handlers::onClear else handlers::onSelectAll,
          style = FluidButtonStyle.Plain, size = FluidButtonSize.Large, fillWidth = true,
        )
      }
      if (toShare) {
        FluidButton(text = label(R.string.suggestions_share_all, R.string.suggestions_share_selected), onClick = handlers::onShare, size = FluidButtonSize.Large, fillWidth = true)
      } else {
        FluidButton(text = label(R.string.suggestions_accept_all, R.string.suggestions_accept_selected), onClick = handlers::onAccept, size = FluidButtonSize.Large, fillWidth = true)
        FluidButton(
          text = label(R.string.suggestions_exclude_all, R.string.suggestions_exclude_selected), onClick = handlers::onExclude,
          style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true,
        )
        SectionNote(stringResource(R.string.suggestions_accept_note) + " " + stringResource(R.string.suggestions_exclude_note))
      }
    }
  }
}

@Composable
private fun messageText(message: AlbumMessage): String = when (message) {
  AlbumMessage.NotFound -> stringResource(R.string.album_message_not_found)
  is AlbumMessage.Blocked -> stringResource(AlbumText.blocked(message.why))
  AlbumMessage.NotAllowed -> stringResource(R.string.album_message_not_allowed)
  else -> stringResource(R.string.album_message_not_in_album)
}
