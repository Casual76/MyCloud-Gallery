@file:OptIn(ExperimentalLayoutApi::class)

package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadge
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadgeCluster
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadgeView
import dev.pampa.mycloudgallery.core.designsystem.media.description

/** I badge: la legenda, la prova sopra foto chiare e scure, la forma con etichetta. */
internal fun badgeSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_badges_legend, R.string.sec_badges_legend_detail) { BadgeLegend() },
  CatalogSection(R.string.sec_badges_photos, R.string.sec_badges_photos_detail) { BadgesOnPhotos() },
  CatalogSection(R.string.sec_badges_labeled, R.string.sec_badges_labeled_detail) { BadgesLabeled() },
)

private data class LegendEntry(val badge: GalleryBadge, val meaning: Int, val seed: Int)

private val legend = listOf(
  LegendEntry(GalleryBadge.VideoDuration("0:42"), R.string.legend_video, 2),
  LegendEntry(GalleryBadge.NasOnly, R.string.legend_nas_only, 6),
  LegendEntry(GalleryBadge.PhoneOnly, R.string.legend_phone_only, 9),
  LegendEntry(GalleryBadge.Safe, R.string.legend_safe, 14),
  LegendEntry(GalleryBadge.Uploading(0.62f), R.string.legend_uploading, 19),
  LegendEntry(GalleryBadge.Failed, R.string.legend_failed, 22),
  LegendEntry(GalleryBadge.Favorite, R.string.legend_favorite, 27),
  LegendEntry(GalleryBadge.Shared, R.string.legend_shared, 31),
  LegendEntry(GalleryBadge.EditedVersion, R.string.legend_edited, 35),
  LegendEntry(GalleryBadge.EstimatedDate, R.string.legend_estimated_date, 40),
)

@Composable
private fun BadgeLegend() {
  FluidListGroup {
    legend.forEachIndexed { index, entry ->
      if (index > 0) FluidListDivider()
      LegendRow(entry)
    }
  }
}

@Composable
private fun LegendRow(entry: LegendEntry) {
  Row(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Il badge sta su una miniatura, perche' e' li' che vive: il suo contrasto lo dice la miniatura.
    Box(
      modifier = Modifier
        .size(width = 72.dp, height = 48.dp)
        .clip(ContinuousCornerShape(FluidRadius.Small)),
      contentAlignment = Alignment.Center,
    ) {
      SampleThumb(entry.seed)
      GalleryBadgeView(entry.badge)
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.badge.description(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = stringResource(entry.meaning),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private data class ScenePick(val scene: SampleScene, val caption: Int)

private val scenes = listOf(
  ScenePick(SampleScene.Bright, R.string.scene_bright),
  ScenePick(SampleScene.Dark, R.string.scene_dark),
  ScenePick(SampleScene.Sunset, R.string.scene_sunset),
  ScenePick(SampleScene.Meadow, R.string.scene_meadow),
  ScenePick(SampleScene.Stripes, R.string.scene_stripes),
)

@Composable
private fun BadgesOnPhotos() {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    scenes.forEach { pick ->
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
          modifier = Modifier
            .size(width = SceneWidth, height = SceneHeight)
            .clip(ContinuousCornerShape(FluidRadius.Card)),
        ) {
          SamplePhoto(pick.scene)
          Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              GalleryBadgeView(GalleryBadge.VideoDuration("0:42"))
              GalleryBadgeView(GalleryBadge.Uploading(0.62f))
              GalleryBadgeView(GalleryBadge.Failed)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              GalleryBadgeCluster(
                listOf(GalleryBadge.Favorite, GalleryBadge.Shared, GalleryBadge.EditedVersion, GalleryBadge.EstimatedDate),
              )
              GalleryBadgeCluster(listOf(GalleryBadge.NasOnly))
              GalleryBadgeCluster(listOf(GalleryBadge.Safe))
            }
          }
        }
        Text(
          text = stringResource(pick.caption),
          modifier = Modifier.width(SceneWidth),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

@Composable
private fun BadgesLabeled() {
  CatalogPhotoStage(scene = SampleScene.Dark, height = LabeledStageHeight) { _ ->
    FlowRow(
      modifier = Modifier.padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      legend.forEach { GalleryBadgeView(it.badge, labeled = true) }
    }
  }
}

private val SceneWidth = 168.dp
private val SceneHeight = 96.dp
private val LabeledStageHeight = 188.dp
