package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Dock
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMasonry
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.fluidContentColumns
import dev.pampa.mycloudgallery.core.designsystem.R

/**
 * I gruppi del catalogo: uno per famiglia di componenti. Lo [slug] e' il nome che finisce nei file
 * degli screenshot (`celle-chiaro-telefono.png`).
 */
enum class CatalogGroup(
  val slug: String,
  @StringRes val title: Int,
  @StringRes val summary: Int,
  val icon: ImageVector,
) {
  Theme("tema", R.string.group_theme_title, R.string.group_theme_summary, Icons.Rounded.Palette),
  Cells("celle", R.string.group_cells_title, R.string.group_cells_summary, Icons.Rounded.GridView),
  Badges("badge", R.string.group_badges_title, R.string.group_badges_summary, Icons.Rounded.Badge),
  Bars("barre", R.string.group_bars_title, R.string.group_bars_summary, Icons.Rounded.Dock),
  Menu("menu", R.string.group_menu_title, R.string.group_menu_summary, Icons.Rounded.MoreVert),
  Sheets("conferme", R.string.group_sheets_title, R.string.group_sheets_summary, Icons.Rounded.TaskAlt),
  States("stati-vuoti", R.string.group_states_title, R.string.group_states_summary, Icons.Rounded.Inbox),
  Status("stato-globale", R.string.group_status_title, R.string.group_status_summary, Icons.Rounded.CloudDone),
  Headers("intestazioni", R.string.group_headers_title, R.string.group_headers_summary, Icons.Rounded.CalendarMonth),
  Motion("movimento", R.string.group_motion_title, R.string.group_motion_summary, Icons.Rounded.Animation),

  // Fase 3 (P0 li registra, P7 e P8 riempiono le sezioni nei loro file: CatalogGridSections.kt e CatalogViewerSections.kt).
  Grid("griglia", R.string.group_grid_title, R.string.group_grid_summary, Icons.Rounded.Apps),
  Viewer("viewer", R.string.group_viewer_title, R.string.group_viewer_summary, Icons.Rounded.Image),
}

/** Una sezione di un gruppo: un'intestazione e il suo contenuto. */
@Immutable
class CatalogSection(
  @StringRes val title: Int,
  @StringRes val detail: Int? = null,
  val content: @Composable ColumnScope.() -> Unit,
)

/** Le sezioni di un gruppo, nell'ordine in cui il catalogo le mostra. */
fun catalogSections(group: CatalogGroup): List<CatalogSection> = when (group) {
  CatalogGroup.Theme -> themeSections()
  CatalogGroup.Cells -> cellSections()
  CatalogGroup.Badges -> badgeSections()
  CatalogGroup.Bars -> barSections()
  CatalogGroup.Menu -> menuSections()
  CatalogGroup.Sheets -> sheetSections()
  CatalogGroup.States -> stateSections()
  CatalogGroup.Status -> statusSections()
  CatalogGroup.Headers -> headerSections()
  CatalogGroup.Motion -> motionSections()
  CatalogGroup.Grid -> gridSections()
  CatalogGroup.Viewer -> viewerSections()
}

/**
 * Le sezioni di un gruppo, in colonne quando c'e' posto (telefono: una; tablet: due) con la stessa
 * muratura dell'engine: ogni sezione va nella colonna piu' corta, nell'ordine in cui e' scritta.
 */
@Composable
fun CatalogSectionList(
  sections: List<CatalogSection>,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val columns = fluidContentColumns(
      available = maxWidth,
      minColumn = CatalogMinColumn,
      spacing = CatalogColumnSpacing,
      maxColumns = CatalogMaxColumns,
    )
    FluidMasonry(
      columns = columns,
      horizontalSpacing = CatalogColumnSpacing,
      verticalSpacing = CatalogSectionSpacing,
    ) {
      sections.forEach { section ->
        Column(verticalArrangement = Arrangement.spacedBy(CatalogContentSpacing)) {
          FluidSectionHeader(
            title = stringResource(section.title),
            detail = section.detail?.let { stringResource(it) },
          )
          section.content(this)
        }
      }
    }
  }
}

/** La colonna piu' stretta che regge i componenti (un foglio di conferma, una cella e il suo nome). */
internal val CatalogMinColumn: Dp = 360.dp
internal val CatalogColumnSpacing: Dp = 24.dp
internal val CatalogSectionSpacing: Dp = 28.dp
internal val CatalogContentSpacing: Dp = 12.dp
internal const val CatalogMaxColumns: Int = 2
