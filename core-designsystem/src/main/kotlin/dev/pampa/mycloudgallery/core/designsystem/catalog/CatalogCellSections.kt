@file:OptIn(ExperimentalLayoutApi::class)

package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadge
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadgeCluster
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadgeView
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryMediaCell

/** Le celle della griglia: forme, stati, badge e una griglia di insieme. */
internal fun cellSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_cells_shapes, R.string.sec_cells_shapes_detail) { CellShapes() },
  CatalogSection(R.string.sec_cells_states, R.string.sec_cells_states_detail) { CellStates() },
  CatalogSection(R.string.sec_cells_badges, R.string.sec_cells_badges_detail) { CellBadges() },
  CatalogSection(R.string.sec_cells_grid, R.string.sec_cells_grid_detail) { CellGrid() },
)

@Composable
private fun CellCaption(text: Int, modifier: Modifier = Modifier) {
  Text(
    text = stringResource(text),
    modifier = modifier,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
  )
}

@Composable
private fun CellShapes() {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(CellGap),
    verticalArrangement = Arrangement.spacedBy(CellGap),
  ) {
    ShapeSample(R.string.cell_shape_square, aspect = 1f, seed = 3)
    ShapeSample(R.string.cell_shape_landscape, aspect = 4f / 3f, seed = 8)
    ShapeSample(R.string.cell_shape_portrait, aspect = 3f / 4f, seed = 12)
    ShapeSample(R.string.cell_shape_panorama, aspect = 2f, seed = 17)
  }
}

@Composable
private fun ShapeSample(caption: Int, aspect: Float, seed: Int) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    GalleryMediaCell(
      contentDescription = stringResource(R.string.sample_photo, seed),
      modifier = Modifier.height(ShapeSampleHeight),
      aspectRatio = aspect,
    ) { SampleThumb(seed) }
    CellCaption(caption)
  }
}

@Composable
private fun CellStates() {
  val pressSource = remember { MutableInteractionSource() }
  // Il dito "resta giu'" su una cella: serve aspettare due fotogrammi prima di emettere, o la pressione
  // parte prima che la cella si sia iscritta alla sorgente di interazioni e nessuno la sente.
  LaunchedEffect(pressSource) {
    withFrameNanos { }
    withFrameNanos { }
    pressSource.emit(PressInteraction.Press(Offset.Zero))
  }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(CellGap),
    verticalArrangement = Arrangement.spacedBy(CellGap),
  ) {
    StateSample(R.string.cell_state_normal) { seed ->
      GalleryMediaCell(stringResource(R.string.sample_photo, seed), onClick = {}) { SampleThumb(seed) }
    }
    StateSample(R.string.cell_state_loading) { seed ->
      GalleryMediaCell(stringResource(R.string.sample_photo, seed), loaded = false) { SampleThumb(seed) }
    }
    StateSample(R.string.cell_state_pressed) { seed ->
      GalleryMediaCell(
        contentDescription = stringResource(R.string.sample_photo, seed),
        interactionSource = pressSource,
        onClick = {},
      ) { SampleThumb(seed) }
    }
    StateSample(R.string.cell_state_selectable) { seed ->
      GalleryMediaCell(
        stringResource(R.string.sample_photo, seed),
        selectionMode = true,
        selected = false,
        onClick = {},
      ) { SampleThumb(seed) }
    }
    StateSample(R.string.cell_state_selected) { seed ->
      GalleryMediaCell(
        stringResource(R.string.sample_photo, seed),
        selectionMode = true,
        selected = true,
        onClick = {},
      ) { SampleThumb(seed) }
    }
  }
}

@Composable
private fun StateSample(caption: Int, cell: @Composable (seed: Int) -> Unit) {
  Column(
    modifier = Modifier.width(StateSampleSize),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    cell(StateSeed)
    CellCaption(caption)
  }
}

@Composable
private fun CellBadges() {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(CellGap),
    verticalArrangement = Arrangement.spacedBy(CellGap),
  ) {
    BadgeSample(seed = 21, bottomEnd = { GalleryBadgeView(GalleryBadge.VideoDuration("0:42")) })
    BadgeSample(
      seed = 25,
      topEnd = { GalleryBadgeCluster(listOf(GalleryBadge.NasOnly)) },
      bottomStart = { GalleryBadgeCluster(listOf(GalleryBadge.Favorite)) },
    )
    BadgeSample(
      seed = 29,
      topEnd = { GalleryBadgeView(GalleryBadge.Uploading(0.62f)) },
      bottomStart = { GalleryBadgeCluster(listOf(GalleryBadge.Shared)) },
    )
    BadgeSample(seed = 33, topEnd = { GalleryBadgeView(GalleryBadge.Failed) })
    BadgeSample(
      seed = 37,
      topEnd = { GalleryBadgeCluster(listOf(GalleryBadge.Safe)) },
      bottomStart = { GalleryBadgeCluster(listOf(GalleryBadge.EditedVersion, GalleryBadge.Favorite)) },
    )
    BadgeSample(
      seed = 41,
      topEnd = { GalleryBadgeCluster(listOf(GalleryBadge.PhoneOnly)) },
      bottomStart = { GalleryBadgeCluster(listOf(GalleryBadge.EstimatedDate)) },
      bottomEnd = { GalleryBadgeView(GalleryBadge.VideoDuration("1:03:20")) },
    )
  }
}

@Composable
private fun BadgeSample(
  seed: Int,
  topEnd: (@Composable () -> Unit)? = null,
  bottomStart: (@Composable () -> Unit)? = null,
  bottomEnd: (@Composable () -> Unit)? = null,
) {
  GalleryMediaCell(
    contentDescription = stringResource(R.string.sample_photo, seed),
    modifier = Modifier.width(StateSampleSize),
    topEnd = topEnd,
    bottomStart = bottomStart,
    bottomEnd = bottomEnd,
    onClick = {},
  ) { SampleThumb(seed) }
}

/** Una griglia come quella della Libreria: celle quasi a contatto, con un po' di tutto sopra. */
@Composable
private fun CellGrid() {
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val columns = (maxWidth / GridCellTarget).toInt().coerceIn(MinGridColumns, MaxGridColumns)
    CatalogStaticGrid(columns = columns, rows = GridRows, firstSeed = GridFirstSeed) { index ->
      GalleryMediaCell(
        contentDescription = stringResource(R.string.sample_photo, index),
        topEnd = when (index % 7) {
          1 -> ({ GalleryBadgeCluster(listOf(GalleryBadge.NasOnly)) })
          3 -> ({ GalleryBadgeCluster(listOf(GalleryBadge.Safe)) })
          5 -> ({ GalleryBadgeView(GalleryBadge.Uploading(0.4f)) })
          else -> null
        },
        bottomStart = if (index % 4 == 0) ({ GalleryBadgeCluster(listOf(GalleryBadge.Favorite)) }) else null,
        bottomEnd = if (index % 5 == 2) ({ GalleryBadgeView(GalleryBadge.VideoDuration("0:${10 + index % 50}")) }) else null,
        onClick = {},
      ) { SampleThumb(index) }
    }
  }
}

private val CellGap = 12.dp
private val ShapeSampleHeight = 104.dp
private val StateSampleSize = 92.dp
private val GridCellTarget = 84.dp
private const val StateSeed = 5
private const val MinGridColumns = 3
private const val MaxGridColumns = 8
private const val GridRows = 4
private const val GridFirstSeed = 50
