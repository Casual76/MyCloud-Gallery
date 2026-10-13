package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuPanel
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuSurface
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryFilterOption
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCell
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellActions
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellBadges
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridJumpButton
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridLevelBar
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridNotice
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryScrubber
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryScrubberState
import dev.pampa.mycloudgallery.core.designsystem.grid.forWidth
import dev.pampa.mycloudgallery.core.designsystem.grid.galleryFilterMenuEntries
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridLevels
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/**
 * Il gruppo **Griglia** del catalogo (Fase 3, P7): la cella leggera della Libreria nei suoi stati, la selezione, i livelli
 * per larghezza, la chrome che galleggia sulla griglia (segmenti, avvisi, "Oggi"), lo scrubber e il menu della vista. Le
 * stringhe del gruppo stanno in `values/strings_catalog_grid.xml`.
 */
internal fun gridSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_grid_cells, R.string.sec_grid_cells_detail) { GridCellStates() },
  CatalogSection(R.string.sec_grid_selection, R.string.sec_grid_selection_detail) { GridCellSelection() },
  CatalogSection(R.string.sec_grid_levels, R.string.sec_grid_levels_detail) { GridLevelsForWidth() },
  CatalogSection(R.string.sec_grid_chrome, R.string.sec_grid_chrome_detail) { GridChrome() },
  CatalogSection(R.string.sec_grid_scrubber, R.string.sec_grid_scrubber_detail) { GridScrubberSample() },
  CatalogSection(R.string.sec_grid_menu, R.string.sec_grid_menu_detail) { GridViewMenu() },
)

/** Le azioni di una cella del catalogo: la descrizione e' la didascalia, i tocchi non fanno niente. */
private class CatalogCellActions(private val describe: (Any) -> String) : GalleryGridCellActions {
  override fun describe(key: Any): String = describe.invoke(key)

  override fun click(key: Any) = Unit

  override fun longClick(key: Any) = Unit

  override val clickLabel: String? = null
  override val longClickLabel: String? = null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GridCellStates() {
  val style = rememberGalleryGridCellStyle()
  val interaction = remember { GalleryGridInteractionState() }
  LaunchedEffect(interaction) { interaction.press(PressedKey, reducedMotion = true) }
  val photo = sampleState(seed = 4)
  val empty = remember { mutableStateOf<ImageBitmap?>(null) }
  val captions = listOf(
    R.string.grid_cell_thumbnail, R.string.grid_cell_placeholder, R.string.grid_cell_video,
    R.string.grid_cell_marks, R.string.grid_cell_unreadable, R.string.grid_cell_pressed,
  ).map { stringResource(it) }
  val actions = remember(captions) { CatalogCellActions { key -> captions.getOrElse((key as? String)?.removePrefix("c")?.toIntOrNull() ?: 0) { "" } } }
  val placeholder = remember { sampleBitmap(seed = 9, side = PlaceholderSide) }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(CellGap), verticalArrangement = Arrangement.spacedBy(CellGap)) {
    CaptionedCell(captions[0]) { GalleryGridCell("c0", photo, GalleryGridCellBadges.None, style, interaction, actions, Modifier.size(CellSide)) }
    CaptionedCell(captions[1]) { GalleryGridCell("c1", empty, GalleryGridCellBadges.None, style, interaction, actions, Modifier.size(CellSide), placeholder = placeholder) }
    CaptionedCell(captions[2]) {
      GalleryGridCell("c2", sampleState(seed = 11), GalleryGridCellBadges(duration = stringResource(R.string.grid_sample_duration)), style, interaction, actions, Modifier.size(CellSide))
    }
    CaptionedCell(captions[3]) {
      GalleryGridCell("c3", sampleState(seed = 17), GalleryGridCellBadges(favorite = true, edited = true), style, interaction, actions, Modifier.size(CellSide))
    }
    CaptionedCell(captions[4]) {
      GalleryGridCell("c4", empty, GalleryGridCellBadges(unreadable = true), style, interaction, actions, Modifier.size(CellSide))
    }
    CaptionedCell(captions[5]) { GalleryGridCell(PressedKey, sampleState(seed = 23), GalleryGridCellBadges.None, style, interaction, actions, Modifier.size(CellSide)) }
  }
}

@Composable
private fun GridCellSelection() {
  val style = rememberGalleryGridCellStyle()
  val interaction = remember { GalleryGridInteractionState() }
  LaunchedEffect(interaction) {
    interaction.selectedKeys = setOf("s1", "s2", "s5")
    interaction.animateSelectionMode(on = true, reducedMotion = true)
  }
  val description = stringResource(R.string.grid_cell_thumbnail)
  val actions = remember(description) { CatalogCellActions { description } }
  Column(verticalArrangement = Arrangement.spacedBy(GalleryDimens.GridGap)) {
    repeat(SelectionRows) { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(GalleryDimens.GridGap)) {
        repeat(SelectionColumns) { column ->
          val index = row * SelectionColumns + column
          GalleryGridCell(
            key = "s$index",
            image = sampleState(seed = 30 + index),
            badges = if (index == 3) GalleryGridCellBadges(duration = stringResource(R.string.grid_sample_duration)) else GalleryGridCellBadges.None,
            style = style,
            interaction = interaction,
            actions = actions,
            modifier = Modifier.size(SelectionCell),
          )
        }
      }
    }
  }
}

@Composable
private fun GridLevelsForWidth() {
  val labels = GalleryGridLevels.Default.map { stringResource(it.label) }
  Column(verticalArrangement = Arrangement.spacedBy(LevelRowGap)) {
    listOf(
      R.string.grid_width_narrow to NarrowWidth,
      R.string.grid_width_phone to PhoneWidth,
      R.string.grid_width_foldable to FoldableWidth,
      R.string.grid_width_tablet to TabletWidth,
    ).forEach { (name, width) ->
      val levels = GalleryGridLevels.forWidth(width)
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(name, width.toInt()),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = levels.mapIndexed { i, level -> "${labels[i]} ${level.columns}" }.joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun GridChrome() {
  CatalogPhotoStage(
    scene = SampleScene.Bright,
    height = ChromeStageHeight,
    photo = { CatalogStaticGrid(columns = StageColumns, rows = StageRows, firstSeed = 40) },
  ) { backdrop ->
    GalleryGridNotice(
      icon = Icons.Rounded.PhotoLibrary,
      title = stringResource(R.string.grid_notice_title),
      message = stringResource(R.string.grid_notice_message),
      backdrop = backdrop,
      actionLabel = stringResource(R.string.grid_notice_action),
      onAction = {},
      modifier = Modifier.align(Alignment.TopCenter).padding(StagePadding),
    )
    Column(
      modifier = Modifier.align(Alignment.BottomCenter).padding(StagePadding),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(StagePadding),
    ) {
      GalleryGridJumpButton(
        label = stringResource(R.string.grid_today),
        icon = Icons.Rounded.KeyboardDoubleArrowDown,
        onClick = {},
        backdrop = backdrop,
      )
      GalleryGridLevelBar(
        labels = GalleryGridLevels.Default.map { stringResource(it.label) },
        selected = 2,
        onSelect = {},
        backdrop = backdrop,
      )
    }
  }
}

@Composable
private fun GridScrubberSample() {
  val state = remember { GalleryScrubberState() }
  LaunchedEffect(state) {
    state.show(reducedMotion = true)
    state.dragging = true
  }
  CatalogPhotoStage(
    scene = SampleScene.Bright,
    height = ScrubberStageHeight,
    photo = { CatalogStaticGrid(columns = StageColumns, rows = StageRows, firstSeed = 60) },
  ) { backdrop ->
    GalleryScrubber(
      state = state,
      fraction = { ScrubberFraction },
      label = stringResource(R.string.grid_scrubber_label),
      contentDescription = stringResource(R.string.grid_scrubber_description),
      onScrub = {},
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.TopEnd).padding(vertical = StagePadding),
      reducedMotion = true,
    )
  }
}

@Composable
private fun GridViewMenu() {
  CatalogPhotoStage(
    scene = SampleScene.Bright,
    height = MenuStageHeight,
    photo = { CatalogStaticGrid(columns = StageColumns, rows = MenuStageRows, firstSeed = 80) },
  ) { backdrop ->
    val filters = listOf(
      R.string.grid_filter_all, R.string.grid_filter_no_screens, R.string.grid_filter_screenshots,
      R.string.grid_filter_whatsapp, R.string.grid_filter_videos, R.string.grid_filter_favorites,
    ).mapIndexed { index, label -> GalleryFilterOption(stringResource(label), selected = index == 4) {} }
    val styles = listOf(R.string.grid_style_square, R.string.grid_style_justified)
      .mapIndexed { index, label -> GalleryFilterOption(stringResource(label), selected = index == 0) {} }
    val entries = galleryFilterMenuEntries(
      groups = listOf(filters, styles),
      trailing = listOf(GalleryMenuEntry.Item(stringResource(R.string.menu_library_settings), {}, Icons.Rounded.Settings)),
    )
    Box(modifier = Modifier.align(Alignment.TopEnd).padding(StagePadding)) {
      GalleryMenuSurface(backdrop = backdrop) { GalleryMenuPanel(entries = entries) }
    }
  }
}

@Composable
private fun CaptionedCell(caption: String, cell: @Composable () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CaptionGap)) {
    cell()
    Text(text = caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Una miniatura di prova come stato (la cella lo legge nel suo nodo, come quella vera). */
@Composable
private fun sampleState(seed: Int): State<ImageBitmap?> = remember(seed) { mutableStateOf<ImageBitmap?>(sampleBitmap(seed, SampleSide)) }

/** La stessa foto di prova di `SampleThumb` (un gradiente con una luce), ma come bitmap: la cella disegna bitmap. */
private fun sampleBitmap(seed: Int, side: Int): ImageBitmap {
  val bitmap = ImageBitmap(side, side)
  val hue = (seed * HueStep % FullTurn).toFloat()
  CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(side.toFloat(), side.toFloat())) {
    drawRect(
      Brush.linearGradient(
        colors = listOf(Color.hsv(hue, 0.55f, 0.9f), Color.hsv((hue + 38f) % FullTurn, 0.7f, 0.5f)),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
      ),
    )
    drawCircle(
      color = Color.hsv((hue + 20f) % FullTurn, 0.25f, 1f).copy(alpha = 0.55f),
      radius = size.minDimension * 0.28f,
      center = Offset(size.width * (0.3f + (seed % 3) * 0.2f), size.height * 0.32f),
    )
  }
  return bitmap
}

private const val PressedKey = "c5"
private const val HueStep = 47
private const val FullTurn = 360
private const val SampleSide = 192
private const val PlaceholderSide = 8
private const val SelectionRows = 2
private const val SelectionColumns = 4
private const val StageColumns = 5
private const val StageRows = 6
private const val MenuStageRows = 9
private const val ScrubberFraction = 0.4f
private const val NarrowWidth = 320f
private const val PhoneWidth = 412f
private const val FoldableWidth = 600f
private const val TabletWidth = 1280f
private val CellSide = 96.dp
private val SelectionCell = 82.dp
private val CellGap = 12.dp
private val CaptionGap = 6.dp
private val LevelRowGap = 10.dp
private val StagePadding = 12.dp
private val ChromeStageHeight = 420.dp
private val ScrubberStageHeight = 360.dp
private val MenuStageHeight = 640.dp
