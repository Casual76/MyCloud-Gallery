@file:OptIn(ExperimentalLayoutApi::class)

package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconSpec
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIcons
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.icon.label

/** Le icone espressive (I3): le famiglie, le taglie, i segni di stato, e come stanno in una riga vera. */
internal fun iconSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_icons_families, R.string.sec_icons_families_detail) { IconFamilies() },
  CatalogSection(R.string.sec_icons_sizes, R.string.sec_icons_sizes_detail) { IconSizes() },
  CatalogSection(R.string.sec_icons_status, R.string.sec_icons_status_detail) { IconStatuses() },
  CatalogSection(R.string.sec_icons_rows, R.string.sec_icons_rows_detail) { IconRows() },
)

/** Una voce del vocabolario per famiglia, con il nome della famiglia sotto. */
private val familySamples: List<Pair<GalleryIconSpec, Int>> = listOf(
  GalleryIcons.Library to R.string.icon_family_photos,
  GalleryIcons.Backup to R.string.icon_family_backup,
  GalleryIcons.People to R.string.icon_family_people,
  GalleryIcons.Memories to R.string.icon_family_memories,
  GalleryIcons.Map to R.string.icon_family_places,
  GalleryIcons.Search to R.string.icon_family_search,
  GalleryIcons.Security to R.string.icon_family_security,
  GalleryIcons.Network to R.string.icon_family_network,
  GalleryIcons.About to R.string.icon_family_system,
  GalleryIcons.Delete to R.string.icon_family_danger,
)

@Composable
private fun IconFamilies() {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    familySamples.forEach { (spec, name) ->
      Column(modifier = Modifier.width(FamilyCell), horizontalAlignment = Alignment.CenterHorizontally) {
        GalleryIconTile(spec = spec, size = GalleryIconTileDefaults.Medium)
        Text(
          text = stringResource(name),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

@Composable
private fun IconSizes() {
  Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
    GalleryIconTile(spec = GalleryIcons.Backup, size = GalleryIconTileDefaults.Row, status = GalleryIconStatus.Done)
    GalleryIconTile(spec = GalleryIcons.Backup, size = GalleryIconTileDefaults.Medium, status = GalleryIconStatus.Done)
    GalleryIconTile(spec = GalleryIcons.Backup, size = GalleryIconTileDefaults.Large, status = GalleryIconStatus.Done)
  }
}

@Composable
private fun IconStatuses() {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    GalleryIconStatus.entries.forEach { status ->
      Column(modifier = Modifier.width(FamilyCell), horizontalAlignment = Alignment.CenterHorizontally) {
        GalleryIconTile(spec = GalleryIcons.Backup, size = GalleryIconTileDefaults.Medium, status = status)
        Text(
          text = status.label(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
    }
  }
}

/** Come stanno in una riga delle Impostazioni: la piastrella al posto di quella grigia dell'engine, lo stato a destra come segno o valore. */
@Composable
private fun IconRows() {
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.icon_row_backup),
      subtitle = "",
      leading = { GalleryIconTile(spec = GalleryIcons.Backup, status = GalleryIconStatus.Paused) },
      leadingTile = false,
      onClick = {},
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.icon_row_security),
      subtitle = "",
      leading = { GalleryIconTile(spec = GalleryIcons.Security) },
      leadingTile = false,
      badge = { GalleryStatusMark(status = GalleryIconStatus.Done, size = GalleryIconTileDefaults.MarkMax) },
      onClick = {},
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.icon_row_network),
      subtitle = "",
      leading = { GalleryIconTile(spec = GalleryIcons.Network) },
      leadingTile = false,
      badge = {
        Text(
          text = stringResource(R.string.icon_row_network_value),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      onClick = {},
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.icon_row_people),
      subtitle = "",
      leading = { GalleryIconTile(spec = GalleryIcons.People, status = GalleryIconStatus.Attention) },
      leadingTile = false,
      onClick = {},
    )
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.icon_row_about),
      subtitle = "",
      leading = { GalleryIconTile(icon = GalleryIcons.About.icon, family = GalleryIconFamily.System) },
      leadingTile = false,
      onClick = {},
    )
  }
}

/** La colonna di una piastrella con il suo nome sotto. */
private val FamilyCell = 64.dp
