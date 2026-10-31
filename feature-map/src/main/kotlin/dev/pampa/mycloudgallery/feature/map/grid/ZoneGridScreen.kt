package dev.pampa.mycloudgallery.feature.map.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantBlock
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.instantSkeleton
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.R
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.ui.MapDimens
import dev.pampa.mycloudgallery.feature.map.ui.MapText

// LA PAGINA DELLA GRIGLIA DI UNA ZONA O DI UN LUOGO (Fase 9, P4; docs/fase9-progetto.md §1.5 `ZoneGridState`, §2.10, D28, D30). Il nome del posto (o "Questa zona"), quante foto e la parete di
// celle della Libreria; un tocco apre il viewer. "Primo fotogramma subito": finche' la griglia non e' pronta c'e' lo scheletro con la sua intestazione. Solo componenti G2; niente testo sopra le foto.

/** Cosa la pagina chiede fuori da se': la navigazione e il viewer. */
internal interface ZoneGridHandlers {
  fun onBack()

  fun onOpenPhoto(media: MediaId)

  /** "Riprova" dopo "Non riesco a leggere le posizioni delle foto" (MA-1). */
  fun onRetry()
}

internal object NoZoneGridHandlers : ZoneGridHandlers {
  override fun onBack() = Unit

  override fun onOpenPhoto(media: MediaId) = Unit

  override fun onRetry() = Unit
}

private const val MAX_COLUMNS = 12

/** La pagina per lo stato dato ([state] null = si sta preparando): nessun effetto, la usano la rotta, i test e gli screenshot. */
@Composable
internal fun ZoneGridScreen(
  state: ZoneGridState?,
  thumbnails: MapThumbnails,
  handlers: ZoneGridHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  val resources = LocalContext.current.resources
  when (state) {
    null -> FluidScreen(
      title = stringResource(R.string.mappa_zone_title),
      modifier = modifier,
      onBack = handlers::onBack,
      listState = listState,
      contentMaxWidth = MapDimens.SheetWidth,
    ) {
      instantSkeleton(InstantShape.of(InstantBlock.Tiles(count = 12, columns = 3)), keyPrefix = "zone")
    }
    ZoneGridState.Empty -> FluidScreen(
      title = stringResource(R.string.mappa_zone_title),
      modifier = modifier,
      onBack = handlers::onBack,
      listState = listState,
      contentMaxWidth = MapDimens.SheetWidth,
    ) {
      item(key = "empty") {
        Column(verticalArrangement = Arrangement.spacedBy(MapDimens.BlockGap)) {
          FluidEmptyState(title = stringResource(R.string.mappa_zone_empty_title), detail = stringResource(R.string.mappa_zone_empty_detail))
          FluidButton(text = stringResource(R.string.mappa_zone_back), onClick = handlers::onBack, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
        }
      }
    }
    // MA-1: le posizioni non si sono lette, quindi non si sa cosa c'e' qui: non e' "nessuna foto qui". Si dice e si puo' riprovare.
    ZoneGridState.Failed -> FluidScreen(
      title = stringResource(R.string.mappa_zone_title),
      modifier = modifier,
      onBack = handlers::onBack,
      listState = listState,
      contentMaxWidth = MapDimens.SheetWidth,
    ) {
      item(key = "failed") {
        Column(verticalArrangement = Arrangement.spacedBy(MapDimens.BlockGap)) {
          FluidEmptyState(title = stringResource(R.string.mappa_failed_title), detail = stringResource(R.string.mappa_failed_detail))
          FluidButton(text = stringResource(R.string.mappa_retry), onClick = handlers::onRetry, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
          FluidButton(text = stringResource(R.string.mappa_zone_back), onClick = handlers::onBack, style = FluidButtonStyle.Plain, size = FluidButtonSize.Large, fillWidth = true)
        }
      }
    }
    is ZoneGridState.Ready -> {
      val metrics = rememberFluidScreenMetrics()
      val columns = metrics.columns(minColumn = MapDimens.CellMin, spacing = GalleryWall.Gap, maxColumns = MAX_COLUMNS)
      val style = rememberGalleryGridCellStyle(cornerScale = GalleryWall.CornerScale)
      val interaction = remember { GalleryGridInteractionState() }
      val view = state.view
      val actions = rememberCellActions(view, stringResource(R.string.mappa_cell_open), handlers::onOpenPhoto)
      FollowGridWindow(listState, thumbnails, "zone", view, columns)
      FluidScreen(
        title = state.label ?: stringResource(R.string.mappa_zone_title),
        subtitle = MapText.photos(resources, view.size),
        modifier = modifier,
        onBack = handlers::onBack,
        listState = listState,
        metrics = metrics,
        itemSpacing = GalleryWall.Gap,
        contentMaxWidth = MapDimens.PageWidth,
      ) {
        zoneGrid("zone", view, columns, thumbnails, style, interaction, actions, handlers::onOpenPhoto)
      }
    }
  }
}
