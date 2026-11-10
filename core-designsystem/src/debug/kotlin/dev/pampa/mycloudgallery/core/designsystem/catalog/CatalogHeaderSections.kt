package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineHeader
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineHeaderAction
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineLevel

/** Le intestazioni della timeline: i quattro livelli, e agganciate alla barra. */
internal fun headerSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_headers_levels, R.string.sec_headers_levels_detail) { HeaderLevels() },
  CatalogSection(R.string.sec_headers_stuck, R.string.sec_headers_stuck_detail) { HeadersStuck() },
)

@Composable
private fun HeaderLevels() {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Group))
      .background(MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    GalleryTimelineHeader(
      title = stringResource(R.string.sample_year),
      place = stringResource(R.string.sample_year_detail),
      level = GalleryTimelineLevel.Year,
    )
    FluidListDivider()
    GalleryTimelineHeader(
      title = stringResource(R.string.sample_month),
      place = stringResource(R.string.sample_month_detail),
      level = GalleryTimelineLevel.Month,
    )
    FluidListDivider()
    GalleryTimelineHeader(
      title = stringResource(R.string.sample_day),
      place = stringResource(R.string.sample_place),
      level = GalleryTimelineLevel.Day,
      trailing = { GalleryTimelineHeaderAction(label = stringResource(R.string.header_select), onClick = {}) },
    )
    FluidListDivider()
    GalleryTimelineHeader(
      title = stringResource(R.string.sample_day_short),
      level = GalleryTimelineLevel.All,
    )
  }
}

@Composable
private fun HeadersStuck() {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    // Con le foto che le scorrono sotto: una fascia di vetro (la regola: il vetro sta sulle barre).
    CatalogPhotoStage(
      scene = SampleScene.Meadow,
      height = StuckStageHeight,
      photo = { CatalogStaticGrid(columns = 5, rows = 3, firstSeed = 24) },
    ) { backdrop ->
      GalleryTimelineHeader(
        title = stringResource(R.string.sample_day),
        place = stringResource(R.string.sample_place),
        stuck = true,
        backdrop = backdrop,
        modifier = Modifier.align(Alignment.TopCenter),
      )
    }
    // Senza fondale a disposizione: una fascia piena del colore della pagina.
    CatalogPhotoStage(
      scene = SampleScene.Meadow,
      height = StuckStageHeight,
      photo = { CatalogStaticGrid(columns = 5, rows = 3, firstSeed = 24) },
    ) { _ ->
      GalleryTimelineHeader(
        title = stringResource(R.string.sample_day),
        place = stringResource(R.string.sample_place),
        stuck = true,
        modifier = Modifier.align(Alignment.TopCenter),
      )
    }
  }
}

private val StuckStageHeight = 150.dp
