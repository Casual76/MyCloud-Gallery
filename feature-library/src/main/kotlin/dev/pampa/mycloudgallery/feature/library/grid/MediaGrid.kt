package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadge
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadgeCluster
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadgeView
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryMediaCell
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineHeader
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineLevel
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.SectionInfo
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.library.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * La griglia della galleria, riusata da tutte le liste (la Libreria, le Raccolte filtrate, i Nascosti, gli Eliminati di
 * recente): una `LazyColumn` di **righe precalcolate** dal [GridLayoutEngine] (D3), niente Paging 3 e niente
 * `LazyVerticalGrid`. Ogni riga e' un'intestazione o una fila di celle con altezza e posizioni note.
 *
 * **Stub di P0.** Disegna le righe del layout con la cella del design system, apre in fondo (D1: i piu' recenti in
 * fondo) e dice quali celle ha disegnato, per il viewer. Mancano, e sono di P7: il pinch ancorato, lo scrubber, il
 * "torna a oggi", l'intestazione agganciata, la selezione a trascinamento con scorrimento automatico, la finestra delle
 * miniature, i budget di ricomposizione, i banner di stato e l'accessibilita' completa.
 *
 * @param footer in fondo, sotto i piu' recenti: il riepilogo "N foto, M video" e lo stato globale della Fase 2.
 */
@Composable
fun MediaGrid(
  state: MediaGridState,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  onOpen: (MediaId) -> Unit = {},
  footer: @Composable () -> Unit = {},
) {
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val columns = columnsFor(state.level, maxWidth)
    val spec = remember(state.view, state.level, state.style, constraints.maxWidth, density.density, columns) {
      GridSpec(
        level = state.level,
        style = state.style,
        columns = columns,
        widthPx = constraints.maxWidth,
        gapPx = with(density) { GalleryDimens.GridGap.roundToPx() },
        densityDp = density.density,
      )
    }
    val layout = remember(spec) { state.engine.layout(state.view, spec).also { state.layout = it } }
    val selected by state.selection.selected.collectAsState()

    // D1: la Libreria si apre in fondo, sui piu' recenti (il piede e' l'ultimo elemento). La barra, il piede e i margini
    // si assestano dopo il primo fotogramma e spostano la fine della lista: si resta in fondo finche' l'utente non
    // trascina (P7 salva la posizione come elemento ancorato + scostamento, E-P18).
    LaunchedEffect(state) {
      state.listState.interactionSource.interactions.first { it is DragInteraction.Start }
      state.userScrolled = true
    }
    LaunchedEffect(state, contentPadding, layout) {
      if (layout.rowCount > 0 && !state.userScrolled) state.listState.scrollToItem(layout.rowCount)
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = state.listState,
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(with(density) { spec.gapPx.toDp() }),
    ) {
      items(count = layout.rowCount, key = { it }) { row ->
        GridRow(
          state = state,
          layout = layout,
          row = row,
          density = density,
          selecting = !selected.isEmpty,
          selected = selected.ids,
          onOpen = onOpen,
        )
      }
      item(key = "footer") { footer() }
    }
  }
}

@Composable
private fun GridRow(
  state: MediaGridState,
  layout: GridLayout,
  row: Int,
  density: Density,
  selecting: Boolean,
  selected: Set<MediaId>,
  onOpen: (MediaId) -> Unit,
) {
  val rowHeight = with(density) { layout.rowHeight(row).toDp() }
  when (layout.rowKind(row)) {
    RowKind.HEADER -> {
      val section = layout.sections[layout.sectionOfRow(row)]
      GalleryTimelineHeader(
        title = sectionTitle(section),
        place = state.placeNames.summary(section.topPlaces, section.distinctPlaces).ifEmpty { null },
        level = when (section.level) {
          GridLevel.YEARS -> GalleryTimelineLevel.Year
          GridLevel.MONTHS -> GalleryTimelineLevel.Month
          else -> GalleryTimelineLevel.Day
        },
        modifier = Modifier.height(rowHeight),
      )
    }
    RowKind.CELLS -> Box(modifier = Modifier.fillMaxWidth().height(rowHeight).clipToBounds()) {
      for (position in layout.itemsOf(row)) {
        GridCell(state, layout, position, rowHeight, density, selecting, selected, onOpen)
      }
    }
  }
}

@Composable
private fun GridCell(
  state: MediaGridState,
  layout: GridLayout,
  position: Int,
  rowHeight: Dp,
  density: Density,
  selecting: Boolean,
  selected: Set<MediaId>,
  onOpen: (MediaId) -> Unit,
) {
  val snapshot = state.view.snapshot
  val index = state.view.snapshotIndex(layout.viewIndexAt(position))
  val id = snapshot.idAt(index)
  val cellWidth = with(density) { layout.itemWidth(position).toDp() }
  val key = state.thumbKey(id, bucketFor(layout.itemWidth(position)))
  val image by state.loader.image(key, state.refOf(id))
  val flags = snapshot.flagsAt(index)
  val kind = snapshot.kindAt(index)
  val date = dateText(snapshot.localDayAt(index))
  val description = when (kind) {
    MediaKind.VIDEO -> stringResource(R.string.grid_cell_video, date)
    MediaKind.MOTION_PHOTO -> stringResource(R.string.grid_cell_motion, date)
    else -> stringResource(R.string.grid_cell_photo, date)
  }
  GalleryMediaCell(
    contentDescription = description,
    modifier = Modifier
      .offset { IntOffset(layout.itemLeft(position), 0) }
      .width(cellWidth)
      .onGloballyPositioned { state.cellBounds[id] = it.boundsInWindow().let { b -> PixelRect(b.left, b.top, b.right, b.bottom) } },
    aspectRatio = layout.itemWidth(position).toFloat() / max(1f, with(density) { rowHeight.toPx() }),
    loaded = image != null,
    selectionMode = selecting,
    selected = id in selected,
    onClick = { if (selecting) state.selection.toggle(id) else onOpen(id) },
    onLongClick = { state.selection.toggle(id) },
    bottomStart = if (CellFlags.has(flags, CellFlags.FAVORITE)) {
      { GalleryBadgeCluster(listOf(GalleryBadge.Favorite)) }
    } else {
      null
    },
    bottomEnd = if (kind == MediaKind.VIDEO) {
      { GalleryBadgeView(GalleryBadge.VideoDuration(durationText(snapshot.durationMsAt(index)))) }
    } else {
      null
    },
  ) {
    image?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
  }
}

/** Le colonne del livello alla larghezza data: 7, 5, 4 e 3 sul telefono, fino a 16, 12, 10 e 8 sul tablet (§2.4). */
private fun columnsFor(level: GridLevel, width: Dp): Int {
  val (targetDp, maxColumns) = when (level) {
    GridLevel.YEARS -> 56 to 16
    GridLevel.MONTHS -> 80 to 12
    GridLevel.DAYS -> 100 to 10
    GridLevel.ALL -> 130 to 8
  }
  return min(maxColumns, max(1, (width.value / targetDp).roundToInt()))
}

private fun bucketFor(widthPx: Int) = ThumbBucket.covering(widthPx)

private val ItalianMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.ITALIAN)
private val ItalianDay: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)
private val ItalianFullDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)

private fun localDate(day: Int): LocalDate = LocalDate.of(day / 10_000, (day / 100) % 100, day % 100)

private fun dateText(day: Int): String = localDate(day).format(ItalianFullDate)

private fun sectionTitle(section: SectionInfo): String = when (section.level) {
  GridLevel.YEARS -> (section.firstDay / 10_000).toString()
  GridLevel.MONTHS, GridLevel.ALL -> localDate(section.firstDay).format(ItalianMonth).capitalized()
  GridLevel.DAYS -> localDate(section.firstDay).format(ItalianDay).capitalized()
}

private fun String.capitalized(): String = replaceFirstChar { it.titlecase(Locale.ITALIAN) }

private fun durationText(durationMs: Long): String {
  val total = (durationMs / 1000).coerceAtLeast(0)
  val hours = total / 3600
  val minutes = (total % 3600) / 60
  val seconds = total % 60
  return if (hours > 0) "%d:%02d:%02d".format(Locale.ITALIAN, hours, minutes, seconds) else "%d:%02d".format(Locale.ITALIAN, minutes, seconds)
}
