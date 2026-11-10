@file:OptIn(ExperimentalLayoutApi::class)

package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateKind
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateView
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatus
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatusIndicator

/** L'indicatore di stato globale in tutte le forme. */
internal fun statusSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_status_tonal, R.string.sec_status_tonal_detail) { StatusTonal() },
  CatalogSection(R.string.sec_status_compact, R.string.sec_status_compact_detail) { StatusCompact() },
  CatalogSection(R.string.sec_status_glass, R.string.sec_status_glass_detail) { StatusGlass() },
)

@Composable
private fun allStatuses(): List<GalleryStatus> = listOf(
  GalleryStatus.AllSafe,
  GalleryStatus.BackingUp(done = 32, total = 120),
  GalleryStatus.WaitingForCharge,
  GalleryStatus.NasUnreachable,
  GalleryStatus.WakingNas,
  GalleryStatus.Error(reason = stringResource(R.string.status_error_quota)),
)

@Composable
private fun StatusTonal() {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(0.dp),
  ) {
    allStatuses().forEach { GalleryStatusIndicator(status = it, onClick = {}) }
  }
}

@Composable
private fun StatusCompact() {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    allStatuses().forEach { GalleryStatusIndicator(status = it, onClick = {}, compact = true) }
  }
}

@Composable
private fun StatusGlass() {
  // La timeline vera e' una griglia di foto: e' quella che la lente deve rifrangere.
  CatalogPhotoStage(
    scene = SampleScene.Meadow,
    height = GlassStageHeight,
    photo = { CatalogStaticGrid(columns = 6, rows = 6, firstSeed = 11) },
  ) { backdrop ->
    FlowRow(
      modifier = Modifier.padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      allStatuses().forEach { GalleryStatusIndicator(status = it, onClick = {}, backdrop = backdrop) }
    }
  }
}

/** Gli stati vuoti e d'errore: una scheda per stato. */
internal fun stateSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_states_library) {
    StateCard { GalleryStateView(GalleryStateKind.LibraryEmpty, onAction = {}) }
  },
  CatalogSection(R.string.sec_states_search) {
    StateCard {
      GalleryStateView(
        kind = GalleryStateKind.NoSearchResults,
        query = stringResource(R.string.sample_query),
        onAction = {},
      )
    }
  },
  CatalogSection(R.string.sec_states_nas) {
    StateCard { GalleryStateView(GalleryStateKind.NasUnreachable, onAction = {}) }
  },
  CatalogSection(R.string.sec_states_permission) {
    StateCard { GalleryStateView(GalleryStateKind.PermissionDenied, onAction = {}) }
  },
)

@Composable
private fun StateCard(content: @Composable () -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Group))
      .background(MaterialTheme.colorScheme.surfaceContainerLow),
  ) { content() }
}

private val GlassStageHeight = 372.dp
