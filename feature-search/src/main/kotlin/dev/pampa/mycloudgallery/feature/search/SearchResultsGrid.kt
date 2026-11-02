package dev.pampa.mycloudgallery.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineHeader
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineLevel
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

// La GRIGLIA dei risultati (Fase 6, P8, §2.6, D28): una cella per gruppo, "Risultati" poi "Meno probabili", nello stesso ordine scelto; in
// "Recenti" le intestazioni di periodo. Stessa cella della Libreria, miniature solo locali. La barra dei chip, il selettore e gli avvisi stanno
// in testa alla griglia ([header]) e scorrono con lei.

private val MonthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.ITALIAN)
private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)

@Composable
internal fun SearchResultsGrid(
  results: SearchScreenState.Results,
  today: LocalDate,
  thumbnails: SearchThumbnails,
  onOpen: (media: MediaId) -> Unit,
  onShowLikely: () -> Unit,
  modifier: Modifier = Modifier,
  gridState: LazyGridState = rememberLazyGridState(),
  header: @Composable () -> Unit,
) {
  val entries = remember(results, today) { SearchGridEntries.build(results, today) }
  val style = rememberGalleryGridCellStyle(cornerScale = GalleryWall.CornerScale)
  val interaction = remember { GalleryGridInteractionState() }
  LaunchedEffect(entries, thumbnails, gridState) { followWindow(gridState, entries, results, thumbnails) }
  LazyVerticalGrid(
    columns = GridCells.Adaptive(SearchDimens.CellMin),
    state = gridState,
    modifier = modifier,
    contentPadding = PaddingValues(bottom = SearchDimens.ListBottom),
    verticalArrangement = Arrangement.spacedBy(GalleryWall.Gap),
    horizontalArrangement = Arrangement.spacedBy(GalleryWall.Gap),
  ) {
    item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
    for (entry in entries) {
      when (entry) {
        is GridEntry.Title -> item(key = entry.key, span = { GridItemSpan(maxLineSpan) }, contentType = "title") { SectionTitle(entry) }
        is GridEntry.Period -> item(key = entry.key, span = { GridItemSpan(maxLineSpan) }, contentType = "period") { PeriodTitle(entry, today) }
        is GridEntry.ShowLikely -> item(key = entry.key, span = { GridItemSpan(maxLineSpan) }, contentType = "show-likely") {
          ShowLikelyRow(entry.count, onShowLikely)
        }
        is GridEntry.Cell -> item(key = entry.key, contentType = "cell") {
          val view = if (entry.section == ResultSection.CONFIDENT) results.confident else results.likely
          SearchResultCell(
            view = view,
            viewIndex = entry.viewIndex,
            thumbnails = thumbnails,
            style = style,
            interaction = interaction,
            onOpen = { onOpen(entry.media) },
            modifier = Modifier.aspectRatio(1f),
          )
        }
      }
    }
  }
}

/**
 * Le miniature da caricare sono quelle delle celle in vista (la finestra del caricatore), piu' quella del chip "Simili a" (TS-5: la cella del media e' esclusa
 * dai risultati, quindi nessuna finestra la chiedeva e il chip restava sull'icona). Solo cio' che e' sul dispositivo: i media solo-NAS passano per [windowOf].
 */
private suspend fun followWindow(state: LazyGridState, entries: List<GridEntry>, results: SearchScreenState.Results, thumbnails: SearchThumbnails) {
  val cells = entries.filterIsInstance<GridEntry.Cell>().associateBy { it.key }
  val chipRequests = similarChipRequests(results.chips, results.confident.snapshot, thumbnails)
  snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.key } }.distinctUntilChanged().collect { keys ->
    val visible = keys.mapNotNull { cells[it] }.mapNotNull { cell ->
      val view = if (cell.section == ResultSection.CONFIDENT) results.confident else results.likely
      thumbRequestOf(thumbnails, view.snapshot, view.snapshotIndex(cell.viewIndex))
    }
    val (local, replicaOnly) = windowOf(chipRequests + visible)
    thumbnails.loader.window(local, replicaOnly)
  }
}

@Composable
private fun SectionTitle(entry: GridEntry.Title) {
  val title = stringResource(if (entry.section == ResultSection.CONFIDENT) R.string.search_results_title else R.string.search_likely_title)
  val count = LocalContext.current.resources.getQuantityString(R.plurals.search_results_count, entry.count, GalleryNumbers.format(entry.count))
  Row(
    modifier = Modifier
      .fillMaxWidth()
      // Fase 10 P8 (D23 (2)): il titolo di una sezione dei risultati e' un'intestazione (e dice anche quanti sono).
      .semantics(mergeDescendants = true) { heading() }
      .padding(horizontal = GalleryDimens.Gutter, vertical = SearchDimens.TightGap),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    Text(text = count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun PeriodTitle(entry: GridEntry.Period, today: LocalDate) {
  GalleryTimelineHeader(title = periodTitle(entry, today), level = entry.level)
}

@Composable
private fun periodTitle(entry: GridEntry.Period, today: LocalDate): String {
  val start = entry.start ?: return stringResource(R.string.search_cell_undated)
  return when (entry.level) {
    GalleryTimelineLevel.Day -> when (start) {
      today -> stringResource(R.string.search_period_today)
      today.minusDays(1) -> stringResource(R.string.search_period_yesterday)
      else -> start.format(DayFormat)
    }
    GalleryTimelineLevel.Month -> start.format(MonthFormat).replaceFirstChar { it.titlecase(Locale.ITALIAN) }
    else -> start.year.toString()
  }
}

@Composable
private fun ShowLikelyRow(count: Int, onShowLikely: () -> Unit) {
  FluidButton(
    text = stringResource(R.string.search_show_likely, GalleryNumbers.format(count)),
    onClick = onShowLikely,
    style = FluidButtonStyle.Tinted,
    // Fase 8 (P10): da 48 dp come ogni controllo (era 42): e' il modo di vedere anche le foto con un volto "forse" di una persona.
    size = FluidButtonSize.Large,
    fillWidth = true,
    modifier = Modifier.padding(horizontal = GalleryDimens.Gutter, vertical = SearchDimens.NoticeInset),
  )
}
