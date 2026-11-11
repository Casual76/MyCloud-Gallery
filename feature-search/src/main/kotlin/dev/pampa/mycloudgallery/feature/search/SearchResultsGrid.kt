package dev.pampa.mycloudgallery.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragPointer
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragSubject
import dev.pampa.mycloudgallery.core.designsystem.adaptive.mediaDragSource
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchSelection
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// La GRIGLIA dei risultati (H4 della fase H, 11/11; prima Fase 6, P8, §2.6, D28). Prende il posto della griglia della Libreria, dentro lo stesso fondale.
//  - **Dal basso**: il risultato piu' adatto sta subito sopra la barra di ricerca, dove sta il pollice (la Libreria ha i piu' recenti in basso; qui vale lo
//    stesso). Si scorre verso l'alto per i meno adatti; in coda, dopo un filo sottile, i meno probabili (D27), con la loro intestazione **sopra** di
//    loro, dove si legge un titolo (CU-3 del giro 1).
//  - **Solo per pertinenza, senza intestazioni di data** (scelta dell'utente).
//  - **La stessa cella e la stessa cache** della Libreria (`GalleryGridCell`, il caricatore della Libreria); miniature solo locali (D28, CE-5).
//  - **La selezione e' quella della Libreria** ([SearchSelection]): pressione lunga per entrare, tocco per scegliere; la barra delle azioni e' la sua.
//  - **Un tocco vale solo se il dito si alza davvero** (D1 della fase H): l'Indietro dal bordo che parte sopra un risultato non lo apre.
//  - Scorrendo la tastiera scende ([onUserScroll]); i risultati restano.

/**
 * La griglia dei risultati [results]. [onOpen] apre il viewer sulla vista dei risultati da un media, con l'ancora della griglia (la cella da cui il viewer
 * parte e su cui torna). [contentPadding] e' lo spazio della chrome in alto e della barra di ricerca in basso.
 */
@Composable
fun SearchResultsGrid(
  results: SearchScreenState.Results,
  thumbnails: SearchThumbnails,
  onOpen: (view: OrderedTimelineView, media: MediaId, anchor: ViewerAnchor) -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(),
  selection: SearchSelection? = null,
  onUserScroll: () -> Unit = {},
  gridState: LazyGridState = rememberLazyGridState(),
) {
  val entries = remember(results) { SearchGridEntries.build(results) }
  val opening = remember(results) { SearchGridEntries.openingView(results) }
  val style = rememberGalleryGridCellStyle(cornerScale = GalleryWall.CornerScale)
  val interaction = remember { GalleryGridInteractionState() }
  val anchor = remember(results, gridState) { ResultsAnchor(results, entries, gridState) }
  val scope = rememberCoroutineScope()
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val haptics = LocalFluidHaptics.current
  val resources = LocalContext.current.resources
  val currentOnUserScroll by rememberUpdatedState(onUserScroll)
  val selecting = selection?.active == true

  // La selezione arriva alle celle (che la leggono nel loro nodo, senza ricomporsi), come nella Libreria.
  if (selection != null) {
    val chosen by selection.model.selected.collectAsState()
    LaunchedEffect(chosen) { interaction.selectedKeys = chosen.ids }
    LaunchedEffect(selecting) { interaction.animateSelectionMode(selecting, reduced) }
  }
  LaunchedEffect(entries, thumbnails, gridState) { followWindow(gridState, entries, results, thumbnails) }

  // Il tocco e la pressione lunga leggono sempre i risultati e la selezione di adesso: i gesti delle celle non ripartono quando cambiano.
  val tap = rememberUpdatedState<(MediaId) -> Unit> { id ->
    if (selection?.active == true) selection.toggle(id) else onOpen(opening, id, anchor)
  }
  val longPress = rememberUpdatedState<(MediaId) -> Unit> { id ->
    if (selection != null) {
      haptics.play(FluidHapticEvent.Threshold)
      if (selection.active) selection.toggle(id) else selection.startWith(id)
    }
  }
  val onTap = remember { { id: MediaId -> tap.value(id) } }
  val onLongPress = remember { { id: MediaId -> longPress.value(id) } }
  val describer = remember(results) { describerOf(resources, results) }
  val actions = remember(describer, selecting) {
    ResultCellActions(
      resources = resources,
      describeAt = describer,
      clickLabel = resources.getString(if (selecting) R.string.search_cell_choose else R.string.search_cell_open),
      longClickLabel = resources.getString(R.string.search_cell_select),
      onClick = { key -> (key as? MediaId)?.let(onTap) },
      onLongClick = { key -> (key as? MediaId)?.let(onLongPress) },
    )
  }
  // Il dito che fa scorrere i risultati abbassa la tastiera (una volta per gesto basta: la seconda chiamata non fa niente).
  val keyboardDown = remember {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) currentOnUserScroll()
        return Offset.Zero
      }
    }
  }

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(SearchDimens.CellMin),
      state = gridState,
      modifier = Modifier.fillMaxSize().nestedScroll(keyboardDown),
      contentPadding = contentPadding,
      reverseLayout = true,
      verticalArrangement = Arrangement.spacedBy(GalleryWall.Gap, Alignment.Bottom),
      horizontalArrangement = Arrangement.spacedBy(GalleryWall.Gap),
    ) {
      items(
        items = entries,
        key = { it.key },
        span = { entry -> if (entry is GridEntry.Cell) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
        contentType = { entry ->
          when (entry) {
            is GridEntry.Cell -> "cell"
            is GridEntry.LikelyHeader -> "header"
            GridEntry.LikelyBoundary -> "boundary"
          }
        },
      ) { entry ->
        when (entry) {
          is GridEntry.LikelyHeader -> LikelyHeader(entry.count)
          GridEntry.LikelyBoundary -> LikelyBoundary()
          is GridEntry.Cell -> {
            val view = if (entry.section == ResultSection.CONFIDENT) results.confident else results.likely
            val id = entry.media
            SearchResultCell(
              view = view,
              viewIndex = entry.viewIndex,
              thumbnails = thumbnails,
              style = style,
              interaction = interaction,
              actions = actions,
              modifier = Modifier
                .aspectRatio(1f)
                .testTag(searchResultTag(id))
                .onPlaced { anchor.place(id, it) }
                .resultGestures(id, interaction, scope, reduced, onTap, onLongPress)
                // Fase 10 (P6, D20): la foto (o la selezione) verso un'altra app: dalla selezione col dito, col mouse anche fuori, come nella Libreria.
                .mediaDragSource(enabled = true, origin = DragOrigin.SEARCH.code) { touch ->
                  val chosen = selection?.model?.selected?.value?.ids.orEmpty()
                  val ids = when {
                    id in chosen -> opening.idsInOrder().filter { it in chosen }.map { it.value }
                    touch.pointer == DragPointer.MOUSE && !selecting -> listOf(id.value)
                    else -> null
                  }
                  ids?.let { DragSubject(it, thumbnails.loader.peek(anchor.thumbKey(id))) }
                },
            )
          }
        }
      }
    }
  }
}

/**
 * Il filo fra i sicuri e i meno probabili (CU-3): una linea sottile, senza parole (l'intestazione sta sopra i meno probabili). Per TalkBack non c'e': i
 * meno probabili li annuncia la loro intestazione.
 */
@Composable
private fun LikelyBoundary() {
  val color = MaterialTheme.colorScheme.outlineVariant
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(SearchDimens.BoundaryHeight)
      .padding(horizontal = GalleryDimens.Gutter)
      .drawBehind {
        val y = size.height / 2f
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = SearchDimens.BoundaryLine.toPx())
      },
  )
}

/**
 * L'intestazione dei meno probabili, **sopra** le loro celle (la griglia cresce dal basso: e' l'ultima voce, quindi in cima; CU-3). Un'intestazione per
 * TalkBack, che dice anche quanti sono.
 */
@Composable
private fun LikelyHeader(count: Int) {
  val countText = LocalContext.current.resources.getQuantityString(R.plurals.search_results_count, count, GalleryNumbers.format(count))
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(SearchDimens.DividerHeight)
      .semantics(mergeDescendants = true) { heading() }
      .padding(horizontal = GalleryDimens.Gutter),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(text = stringResource(R.string.search_likely_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(text = countText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/**
 * Il tocco e la pressione lunga su un risultato, con la pressione della cella che si vede (come nella Libreria). **Un tocco vale solo se il dito si
 * alza davvero** (D1 della fase H): quando il sistema si prende il gesto (l'Indietro dal bordo) Compose recapita un rilascio sintetico gia' consumato,
 * che qui non apre niente; lo stesso per un rilascio che la griglia ha consumato scorrendo. Un dito che si muove oltre lo slop e' uno scorrimento.
 */
private fun Modifier.resultGestures(
  id: MediaId,
  interaction: GalleryGridInteractionState,
  scope: CoroutineScope,
  reduced: Boolean,
  onTap: (MediaId) -> Unit,
  onLongPress: (MediaId) -> Unit,
): Modifier = pointerInput(id, reduced) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val press = scope.launch { interaction.press(id, reduced) }
    var tapped = false
    var longPressed = false
    try {
      withTimeout(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
          val event = awaitPointerEvent()
          val change = event.changes.firstOrNull { it.id == down.id } ?: break
          if (!change.pressed) {
            tapped = !change.isConsumed
            if (tapped) change.consume()
            break
          }
          if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
        }
      }
    } catch (e: PointerEventTimeoutCancellationException) {
      longPressed = true
    }
    press.cancel()
    if (tapped || longPressed) {
      scope.launch {
        interaction.press(id, reduced)
        interaction.release(reduced)
      }
    } else {
      interaction.cancelPress()
    }
    if (tapped) onTap(id)
    if (longPressed) {
      onLongPress(id)
      // Il resto del gesto e' della pressione lunga: la griglia non scorre sotto il dito finche' non si alza.
      while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        if (!change.pressed) break
      }
    }
  }
}

/** Il tag di prova della cella di un risultato (gli strumenti di misura e le prove la trovano per media). */
fun searchResultTag(id: MediaId): String = "risultato-${id.value}"

/** Gli id della vista, nell'ordine mostrato (il trascinamento verso un'altra app li porta cosi'). */
private fun OrderedTimelineView.idsInOrder(): List<MediaId> = (0 until size).map { snapshot.idAt(snapshotIndex(it)) }

/** La frase di TalkBack per la cella di un media (tipo, data, stato), costruita solo quando serve. Sicuri e meno probabili hanno la stessa istantanea. */
private fun describerOf(resources: android.content.res.Resources, results: SearchScreenState.Results): (Any) -> String? = { key ->
  val snapshot = results.confident.snapshot
  val index = (key as? MediaId)?.let { snapshot.indexOf(it) } ?: -1
  if (index >= 0) cellDescription(resources, snapshot, index) else null
}

/**
 * Le miniature da caricare sono quelle delle celle in vista (la finestra del caricatore). Solo cio' che e' sul dispositivo: i media solo-NAS passano per
 * [windowOf] (CE-5).
 */
private suspend fun followWindow(state: LazyGridState, entries: List<GridEntry>, results: SearchScreenState.Results, thumbnails: SearchThumbnails) {
  val cells = entries.filterIsInstance<GridEntry.Cell>().associateBy { it.key }
  snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.key } }.distinctUntilChanged().collect { keys ->
    val visible = keys.mapNotNull { cells[it] }.mapNotNull { cell ->
      val view = if (cell.section == ResultSection.CONFIDENT) results.confident else results.likely
      thumbRequestOf(thumbnails, view.snapshot, view.snapshotIndex(cell.viewIndex))
    }
    val (local, replicaOnly) = windowOf(visible)
    thumbnails.loader.window(local, replicaOnly)
  }
}

/**
 * L'ancora del viewer aperto dai risultati (H4): come quella della Libreria, il viewer parte dalla cella toccata e, chiudendosi, torna sulla cella del
 * media che si stava guardando (portandola in vista se serve). Le posizioni le danno le celle composte ([place]): niente stato, niente lavoro per
 * fotogramma oltre a un riferimento.
 */
internal class ResultsAnchor(
  private val results: SearchScreenState.Results,
  private val entries: List<GridEntry>,
  private val gridState: LazyGridState,
) : ViewerAnchor {
  private val byId = HashMap<MediaId, LayoutCoordinates>()
  private val idOf = HashMap<LayoutCoordinates, MediaId>()

  /** La cella di [id] e' stata posata in [coordinates] (una cella riusata per un altro media toglie quello di prima). */
  fun place(id: MediaId, coordinates: LayoutCoordinates) {
    val previous = idOf[coordinates]
    if (previous == id) return
    if (previous != null) byId.remove(previous)
    idOf[coordinates] = id
    byId[id] = coordinates
  }

  override fun boundsOf(id: MediaId): PixelRect? {
    val coordinates = byId[id]?.takeIf { it.isAttached && idOf[it] == id } ?: return null
    if (gridState.layoutInfo.visibleItemsInfo.none { it.key == id.value }) return null
    val origin = coordinates.positionInWindow()
    return PixelRect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
  }

  override suspend fun reveal(id: MediaId) {
    val index = entries.indexOfFirst { it is GridEntry.Cell && it.media == id }
    if (index < 0) return
    if (gridState.layoutInfo.visibleItemsInfo.none { it.key == id.value }) gridState.scrollToItem(index)
  }

  /** La chiave della miniatura che la cella ha in memoria: la stessa della Libreria ([thumbKeyOf]). */
  override fun thumbKey(id: MediaId): ThumbKey {
    val snapshot = results.confident.snapshot
    val index = snapshot.indexOf(id)
    return if (index >= 0) thumbKeyOf(snapshot, index) else ThumbKey(id, DEFAULT_VERSION_KEY, ThumbBucket.M)
  }
}

