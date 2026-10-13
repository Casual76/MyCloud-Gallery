package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCell
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellActions
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellBadges
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryScrubber
import dev.pampa.mycloudgallery.core.designsystem.grid.forWidth
import dev.pampa.mycloudgallery.core.designsystem.grid.galleryScrubberShouldShow
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineHeader
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineHeaderAction
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineLevel
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridLevels
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * La griglia della galleria, riusata da tutte le liste (la Libreria, le Raccolte filtrate, i Nascosti, gli Eliminati di
 * recente): una `LazyColumn` di **righe precalcolate** dal motore di layout (D3), niente Paging 3 e niente
 * `LazyVerticalGrid`. Ogni riga e' un'intestazione o una fila di celle con altezza e posizioni note.
 *
 * Cosa la tiene a 120 Hz (§2.6, §7):
 *  - **una riga = un elemento pigro**, alto esattamente quanto dice il layout (lo spazio fra le righe e' dentro la
 *    riga): le posizioni sono aritmetica, e scorrere di una riga compone solo la riga che entra;
 *  - **la cella leggera** (`GalleryGridCell`): un nodo, un layer, nessuno stato letto in composizione; una miniatura
 *    che arriva ridisegna solo la sua cella;
 *  - **un solo `pointerInput`** (`gridGestures`) con l'hit test sul layout;
 *  - **le miniature guidate dalla finestra** (`GridThumbWindow`): una richiesta per cambio di riga, non per cella;
 *  - **il pinch sul layer della lista**: la scala si legge nel blocco del layer, nessuna ricomposizione finche' il
 *    livello mostrato non cambia; quando cambia, il layout nuovo e' gia' pronto e la riga sotto il dito resta li';
 *  - **la griglia mostrata e' sempre ingrandita**: durante il pinch si vede il livello piu' fitto ingrandito fino alla
 *    misura del dito, quindi il layer non scopre mai il fondo e non servono righe composte fuori dallo schermo.
 *
 * D1: si apre in fondo, sui piu' recenti, e ci resta finche' l'utente non trascina.
 *
 * @param contentPadding lo spazio della barra in alto e della pillola in basso: il contenuto ci scorre sotto, rifratto.
 * @param footer in fondo, sotto i piu' recenti: il riepilogo "N foto, M video" e lo stato globale della Fase 2.
 * @param backdrop il fondale di vetro della pagina che contiene la griglia (la barra e la pillola lo rifrangono). Se c'e',
 *   la griglia mette in vetro anche l'intestazione agganciata e lo scrubber, che pero' rifrangono un fondale proprio con
 *   la sola lista: mai quello della pagina, che contiene anche loro.
 * @param stickyTop dove si aggancia l'intestazione della sezione (sotto la riga dei controlli della barra).
 * @param onLevelChanged il livello si e' assestato dopo un pinch (per ricordarlo).
 * @param stickyHeader se la griglia disegna da se' l'intestazione agganciata. Chi ha una barra di vetro sopra la griglia
 *   passa `false` e mette [MediaGridStickyHeader] **dopo** la barra: agganciata alla barra, deve starle sopra.
 */
@Composable
fun MediaGrid(
  state: MediaGridState,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  onOpen: (MediaId) -> Unit = {},
  footer: @Composable () -> Unit = {},
  backdrop: GlassBackdropState? = null,
  stickyTop: Dp = contentPadding.calculateTopPadding(),
  onLevelChanged: (GridLevel) -> Unit = {},
  stickyHeader: Boolean = true,
) {
  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .onGloballyPositioned { state.originInWindow = it.positionInWindow() },
  ) {
    val density = LocalDensity.current
    val widthPx = constraints.maxWidth
    val heightPx = constraints.maxHeight
    val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
    val scope = rememberCoroutineScope()
    val haptics = rememberFluidHaptics()
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnLevelChanged by rememberUpdatedState(onLevelChanged)
    val currentReduced by rememberUpdatedState(reducedMotion)

    // --- I layout -----------------------------------------------------------------------------
    val levels = remember(maxWidth) { GalleryGridLevels.forWidth(maxWidth.value) }
    val gapPx = with(density) { GalleryDimens.GridGap.roundToPx() }
    // Le intestazioni crescono col testo di sistema: il motore le misura in dp per questa densita'.
    val headerDensity = density.density * max(1f, density.fontScale)
    val inputs = remember(state.view, state.style, widthPx, gapPx, headerDensity, levels) {
      GridLayoutInputs(state.view, state.style, widthPx, gapPx, headerDensity, levels.map { it.columns })
    }
    // Il livello mostrato: a riposo quello scelto; durante un pinch o un salto, il piu' fitto che la scala ingrandisce.
    val shownLevel by remember(state, widthPx, gapPx) { derivedStateOf { state.displayLevelFor(widthPx, gapPx) } }
    val level = shownLevel
    val ready = state.layouts.get(inputs, level)
    val immediate = if (ready == null) remember(inputs, level) { state.layouts.layoutNowIfSmall(inputs, level) } else null
    val renderLayout = ready ?: immediate ?: state.layout
    val renderView = if (ready != null || immediate != null) inputs.view else state.shownView

    // --- Lo spazio sullo schermo ----------------------------------------------------------------
    val topPad = contentPadding.calculateTopPadding()
    val bottomPad = contentPadding.calculateBottomPadding()
    val viewport = with(density) { GridViewport(widthPx, heightPx, topPad.roundToPx(), bottomPad.roundToPx()) }

    SideEffect {
      state.adoptLevels(levels)
      state.viewport = viewport
      if (immediate != null) state.layouts.offer(inputs, level, immediate)
      if (renderLayout != null) state.onLayoutShown(renderLayout, renderView)
    }
    LaunchedEffect(inputs) { state.layouts.compute(inputs, state.level) }

    GridEffects(state, reducedMotion)

    val style = rememberGalleryGridCellStyle()
    val strings = rememberGridStrings()
    val actions = remember(state, strings) { GridCellActions(state, strings) { currentOnOpen(it) } }
    val today = remember(state) { state.today() }
    val stickyTopPx = with(density) { stickyTop.roundToPx() }
    val context = remember(state, renderView, style, actions, strings, today, gapPx, stickyTopPx) {
      GridRenderContext(state, renderView, style, actions, strings, today, gapPx, stickyTopPx)
    }
    val callbacks = remember(state, haptics) {
      GridGestureCallbacks(
        onOpen = { currentOnOpen(it) },
        reducedMotion = { currentReduced },
        haptics = haptics,
        onLevelSettled = { currentOnLevelChanged(state.level) },
      )
    }

    // Il vetro della griglia (intestazione agganciata, scrubber) rifrange **solo la lista**: un fondale suo, registrato
    // qui sotto. Se campionasse il fondale della pagina, che contiene anche loro, campionerebbe se stesso (regola del
    // vetro: due registrazioni, non una).
    val listBackdrop = if (backdrop != null) rememberGlassBackdrop() else null
    SideEffect { state.listBackdrop = listBackdrop }
    Box(modifier = Modifier.fillMaxSize().gridGestures(state, scope, callbacks)) {
      LazyColumn(
        state = state.listState,
        modifier = Modifier
          .fillMaxSize()
          .then(if (listBackdrop != null) Modifier.glassBackdropSource(listBackdrop) else Modifier)
          .pinchLayer(state),
        contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
        userScrollEnabled = !state.pinching,
      ) {
        val l = renderLayout
        if (l != null) {
          items(count = l.rowCount, contentType = { l.rowKind(it) }) { row -> GridRow(context, l, row) }
          item(key = FooterKey, contentType = FooterKey) {
            Box(modifier = Modifier.fillMaxWidth().onSizeChanged { state.footerHeight = it.height }) { footer() }
          }
        }
      }
    }

    val shown = renderLayout
    if (shown != null && shown.rowCount > 0) {
      if (stickyHeader && shown.spec.level.hasHeaders) {
        StickyHeader(state, shown, stickyTopPx, listBackdrop, strings, today)
      }
      if (listBackdrop != null && shown.totalHeight > viewport.contentHeight * ScrubberMinScreens) {
        val month by remember(state, shown) { derivedStateOf { state.scrubber.label(shown, state.rowAtCenter(shown)) } }
        GalleryScrubber(
          state = state.scrubberState,
          fraction = { state.scrollFraction() },
          label = GridText.month(month),
          contentDescription = strings.scrubber,
          onScrub = { state.scrubTo(it) },
          backdrop = listBackdrop,
          modifier = Modifier.align(Alignment.TopEnd).padding(top = topPad, bottom = bottomPad),
          reducedMotion = reducedMotion,
          onScrubStarted = { state.userScrolled = true },
        )
      }
    }
  }
}

/** Gli effetti della griglia che non disegnano niente: miniature, selezione, scrubber, fondo. */
@Composable
private fun GridEffects(state: MediaGridState, reducedMotion: Boolean) {
  val currentReduced by rememberUpdatedState(reducedMotion)
  val scope = rememberCoroutineScope()

  // Le miniature: una richiesta al caricatore per cambio di riga (non per fotogramma, non per cella).
  LaunchedEffect(state) {
    val window = GridThumbWindow(state)
    var lastTop = Int.MIN_VALUE
    var direction = 1
    snapshotFlow { state.layout?.let { l -> WindowProbe(l, state.shownView, state.contentTop(l), state.transform(l), state.viewport) } }
      .filterNotNull()
      .map { probe ->
        if (lastTop != Int.MIN_VALUE && probe.contentTop != lastTop) direction = if (probe.contentTop > lastTop) 1 else -1
        lastTop = probe.contentTop
        GridThumbWindow.rows(state, probe.layout, probe.view, direction)
      }
      .distinctUntilChanged()
      .collect { rows ->
        val plan = window.plan(rows)
        state.loader.window(plan.visible, plan.ahead)
        state.updateVisibleIds(plan.visibleIds)
      }
  }

  // La selezione arriva alle celle (che la leggono nel loro nodo, senza ricomporsi).
  LaunchedEffect(state) { state.selection.selected.collect { state.interaction.selectedKeys = it.ids } }
  LaunchedEffect(state, state.selecting) { state.interaction.animateSelectionMode(state.selecting, currentReduced) }

  // Il primo trascinamento: da li' la griglia non torna piu' in fondo da sola.
  LaunchedEffect(state) {
    state.listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) state.userScrolled = true }
  }

  // La barra e la pillola si misurano dopo il primo fotogramma e spostano la fine della lista: si resta in fondo. (I
  // cambi di layout restano ancorati da soli, in `onLayoutShown`: qui conta solo lo spazio sullo schermo.)
  LaunchedEffect(state, state.viewport) {
    val l = state.layout ?: return@LaunchedEffect
    if (!state.userScrolled && l.rowCount > 0) state.listState.requestScrollToItem(l.rowCount, 0)
  }
  // Un elemento sparito dalla vista (nascosto, eliminato, fuori dal filtro) esce anche dalla selezione.
  LaunchedEffect(state, state.view) {
    val view = state.view
    state.selection.retain { view.viewIndexOf(it) >= 0 }
  }

  // Lo scrubber compare con uno scorrimento lungo (piu' di tre quarti di schermata in un gesto) e sparisce in quiete.
  LaunchedEffect(state) {
    var startTop = 0
    var wasScrolling = false
    snapshotFlow { state.listState.isScrollInProgress to (state.layout?.let { state.contentTop(it) } ?: 0) }
      .collect { (scrolling, top) ->
        if (scrolling) {
          if (!wasScrolling) startTop = top
          if (galleryScrubberShouldShow((top - startTop).toFloat(), state.viewport.contentHeight.toFloat())) {
            scope.launch { state.scrubberState.show(currentReduced) }
          }
        } else if (wasScrolling) {
          state.scrubberState.hideLater(scope, currentReduced)
        }
        wasScrolling = scrolling
      }
  }
}

/** Cio' che la finestra delle miniature guarda, in un istante. */
private data class WindowProbe(
  val layout: GridLayout,
  val view: TimelineView,
  val contentTop: Int,
  val transform: GridTransform,
  val viewport: GridViewport,
)

/**
 * La lista sta su un layer che porta la trasformazione del pinch: la scala attorno al dito. Il blocco
 * del layer legge lo stato del pinch, quindi un pinch dentro lo stesso livello non ricompone e non rimisura niente; e
 * poiche' la griglia mostrata e' sempre quella che la scala ingrandisce ([MediaGridState.displayLevelFor]), il layer non
 * scopre mai il fondo e la lista non deve comporre righe fuori dallo schermo.
 */
private fun Modifier.pinchLayer(state: MediaGridState): Modifier = graphicsLayer {
  val t = state.transform()
  if (!t.isIdentity) {
    scaleX = t.scale
    scaleY = t.scale
    transformOrigin = TransformOrigin(t.originX / size.width.coerceAtLeast(1f), t.originY / size.height.coerceAtLeast(1f))
  }
}

/** Una riga alta esattamente [heightPx] (la riga piu' lo spazio sotto): le posizioni della lista sono quelle del layout. */
private fun Modifier.exactHeight(heightPx: Int): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, heightPx))
  layout(constraints.maxWidth, heightPx) { placeable.place(0, 0) }
}

/** Cio' che ogni riga legge, uguale per tutte: un oggetto solo, cosi' le righe ferme saltano la ricomposizione. */
@Stable
internal class GridRenderContext(
  val state: MediaGridState,
  val view: TimelineView,
  val style: GalleryGridCellStyle,
  val actions: GalleryGridCellActions,
  val strings: GridStrings,
  val today: LocalDate,
  val gapPx: Int,
  /** La linea di aggancio dell'intestazione (pixel della griglia): sopra, le intestazioni della lista cedono il posto. */
  val stickyTopPx: Int,
)

@Composable
private fun GridRow(context: GridRenderContext, layout: GridLayout, row: Int) {
  context.state.probe?.rowComposed(row)
  when (layout.rowKind(row)) {
    RowKind.HEADER -> HeaderRow(context, layout, row)
    RowKind.CELLS -> CellsRow(context, layout, row)
  }
}

@Composable
private fun HeaderRow(context: GridRenderContext, layout: GridLayout, row: Int) {
  val state = context.state
  val section = layout.sections[layout.sectionOfRow(row)]
  val density = LocalDensity.current
  val height = with(density) { layout.rowHeight(row).toDp() }
  val selecting = state.selecting
  val first = if (row + 1 < layout.rowCount && layout.itemCountIn(row + 1) > 0) layout.firstItemIn(row + 1) else -1
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .exactHeight(layout.rowHeight(row) + context.gapPx)
      // Salita sopra la linea di aggancio, l'intestazione cede il posto a quella agganciata (stessa posizione, stesso
      // testo): nessun doppione sotto la barra. Letto nel layer: scorrere non ricompone.
      .graphicsLayer { alpha = if (headerHandedOver(state, layout, row, context.stickyTopPx)) 0f else 1f },
  ) {
    GalleryTimelineHeader(
      title = GridText.sectionTitle(section, context.today, context.strings),
      place = GridText.sectionPlace(section, state.placeNames),
      level = section.level.timelineLevel(),
      modifier = Modifier.height(height),
      trailing = if (selecting && first >= 0) {
        {
          // In modalita' selezione l'intestazione seleziona la sezione intera ("Seleziona"/"Deseleziona").
          val selected by state.selection.selected.collectAsState()
          val all = remember(selected, first, section.count) {
            section.count <= SectionCheckLimit && state.sectionFullySelected(first, section.count)
          }
          GalleryTimelineHeaderAction(
            label = if (all) context.strings.deselect else context.strings.select,
            onClick = { state.toggleSection(first, section.count) },
          )
        }
      } else {
        null
      },
    )
  }
}

@Composable
private fun CellsRow(context: GridRenderContext, layout: GridLayout, row: Int) {
  val first = layout.firstItemIn(row)
  val count = layout.itemCountIn(row)
  val rowHeight = layout.rowHeight(row)
  Layout(
    content = { for (position in first until first + count) GridCell(context, layout, position, rowHeight) },
    modifier = Modifier.fillMaxWidth(),
  ) { measurables, constraints ->
    val placeables = measurables.mapIndexed { i, measurable ->
      measurable.measure(Constraints.fixed(layout.itemWidth(first + i).coerceAtLeast(1), rowHeight))
    }
    layout(constraints.maxWidth, rowHeight + context.gapPx) {
      // Ogni cella sul suo layer: una cella che si ridisegna (una miniatura arrivata) non ridisegna le vicine.
      placeables.forEachIndexed { i, placeable -> placeable.placeWithLayer(layout.itemLeft(first + i), 0) }
    }
  }
}

@Composable
private fun GridCell(context: GridRenderContext, layout: GridLayout, position: Int, rowHeight: Int) {
  val state = context.state
  state.probe?.cellComposed(position)
  val view = context.view
  val snapshot = view.snapshot
  val index = view.snapshotIndex(layout.viewIndexAt(position))
  val id = snapshot.idAt(index)
  state.noteIndex(id, index)
  val key = state.thumbKeyAt(snapshot, index, id, GridGeometry.bucketFor(layout.itemWidth(position), rowHeight))
  val loader = state.loader
  val ref = state.refAt(snapshot, index)
  // Senza una copia leggibile adesso (E-P22) niente richiesta: la cella resta sul segnaposto.
  val image = if (ref != null) loader.image(key, ref) else NoImage
  val fallback = if (ref != null && loader.peek(key) == null) fallbackFor(loader, key) else null
  val placeholder = snapshot.thumbHashAt(index)?.let { state.placeholders.image(it) }
  GalleryGridCell(
    key = id,
    image = image,
    badges = badgesOf(snapshot.kindAt(index), snapshot.flagsAt(index)) { snapshot.durationMsAt(index) },
    style = context.style,
    interaction = state.interaction,
    actions = context.actions,
    fallback = fallback,
    placeholder = placeholder,
  )
}

/** Una miniatura di un'altra taglia gia' in memoria: un cambio di livello mostra subito qualcosa, mai un segnaposto vuoto. */
private fun fallbackFor(loader: ThumbnailLoader, key: ThumbKey): ImageBitmap? {
  for (bucket in FallbackOrder.getValue(key.bucket)) loader.peek(key.copy(bucket = bucket))?.let { return it }
  return null
}

private val FallbackOrder: Map<ThumbBucket, List<ThumbBucket>> = mapOf(
  ThumbBucket.S to listOf(ThumbBucket.M, ThumbBucket.L),
  ThumbBucket.M to listOf(ThumbBucket.L, ThumbBucket.S),
  ThumbBucket.L to listOf(ThumbBucket.M, ThumbBucket.S),
)

/** I badge della Fase 3 (D15): durata, preferito, modificata, non leggibile. I badge di presenza restano spenti. */
private inline fun badgesOf(kind: MediaKind, flags: Int, duration: () -> Long): GalleryGridCellBadges {
  val marks = flags and (CellFlags.FAVORITE or CellFlags.EDITED or CellFlags.UNREADABLE)
  if (marks == 0 && kind != MediaKind.VIDEO) return GalleryGridCellBadges.None
  return GalleryGridCellBadges(
    favorite = CellFlags.has(flags, CellFlags.FAVORITE),
    edited = CellFlags.has(flags, CellFlags.EDITED),
    duration = if (kind == MediaKind.VIDEO) GridText.duration(duration()) else null,
    unreadable = CellFlags.has(flags, CellFlags.UNREADABLE),
  )
}

/**
 * L'intestazione **agganciata** (§2.4) della griglia di [state], da mettere dopo la barra di vetro della pagina, alla
 * linea [stickyTop] **misurata** sotto la barra: in vetro (`GalleryTimelineHeader(stuck = true)`), con la sezione della
 * riga che sta sotto la linea. Non sale mai sopra la linea: quando arriva l'intestazione successiva sfuma sul posto, e
 * sparisce durante un pinch. Rifrange solo la lista (il fondale proprio della griglia), mai la pagina che la contiene.
 */
@Composable
fun MediaGridStickyHeader(state: MediaGridState, stickyTop: Dp, modifier: Modifier = Modifier) {
  val layout = state.layout ?: return
  if (layout.rowCount == 0 || !layout.spec.level.hasHeaders) return
  val strings = rememberGridStrings()
  val today = remember(state) { state.today() }
  Box(modifier = modifier) {
    StickyHeader(state, layout, with(LocalDensity.current) { stickyTop.roundToPx() }, state.listBackdrop, strings, today)
  }
}

/**
 * L'intestazione agganciata: si ricompone solo quando la sezione cambia; la posizione e la comparsa si leggono nel layer.
 */
@Composable
private fun StickyHeader(
  state: MediaGridState,
  layout: GridLayout,
  stickyTopPx: Int,
  backdrop: GlassBackdropState?,
  strings: GridStrings,
  today: LocalDate,
) {
  if (!layout.spec.level.hasHeaders) return
  val headerRow by remember(state, layout, stickyTopPx) { derivedStateOf { stickyHeaderRow(state, layout, stickyTopPx) } }
  val row = headerRow
  if (row < 0) return
  val section = layout.sections[layout.sectionOfRow(row)]
  val heightPx = layout.rowHeight(row)
  val nextHeader = nextHeaderRow(layout, row)
  val fadePx = with(LocalDensity.current) { StickyFade.toPx() }
  val height = with(LocalDensity.current) { heightPx.toDp() }
  // La fascia di vetro arriva sfumando (l'intensita' del materiale); il testo no: e' lo stesso della lista, allo stesso
  // posto, e passa dall'una all'altra in un fotogramma.
  // Le righe [row] e [nextHeader] valgono solo per [layout]: se il layout sullo schermo e' gia' un altro (un cambio di
  // livello appena avvenuto, prima della ricomposizione), l'intestazione non si mostra.
  val band: () -> Float = {
    if (state.layout !== layout) 0f else stickyAlpha(state, layout, row, state.contentTop(layout), state.viewport, stickyTopPx, fadePx)
  }
  val bandModifier = if (backdrop != null) {
    Modifier.glassSurface(state = backdrop, tint = GlassDefaults.barTint(), edge = GlassEdge.Bottom, role = GlassRole.Bar, intensity = band)
  } else {
    val page = MaterialTheme.colorScheme.background
    Modifier.drawBehind { drawRect(page, alpha = band()) }
  }
  // Sempre alla linea di aggancio, sotto la barra: non sale mai. Quando arriva l'intestazione della sezione successiva,
  // sfuma sul posto nei primi [StickyPushFade] (prima che i due testi si tocchino), e alla linea le cede il posto.
  Box(
    modifier = Modifier
      .offset { IntOffset(0, stickyTopPx) }
      .fillMaxWidth()
      .height(height)
      .clipToBounds()
      .testTag(MediaGridStickyHeaderTag),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(height)
        .graphicsLayer {
          if (state.layout !== layout) {
            alpha = 0f
          } else {
            val top = state.contentTop(layout)
            val v = state.viewport
            val shown = stickyAlpha(state, layout, row, top, v, stickyTopPx, fadePx) > 0f
            val overlap = if (nextHeader < 0) 0 else stickyTopPx + heightPx - (v.padTop + layout.rowTop(nextHeader) - top)
            alpha = if (!shown) 0f else (1f - overlap / StickyPushFade.toPx()).coerceIn(0f, 1f)
          }
        }
        // Il primo e l'ultimo fotogramma di un nodo possono disegnare con le proprieta' di default: a zero non si disegna.
        .drawWithContent { if (band() > 0f) drawContent() },
    ) {
      // La fascia e il testo in due nodi: l'intensita' del vetro sfuma il pannello e tutto cio' che ci sta dentro, e il
      // testo deve restare pieno dal primo fotogramma.
      Box(modifier = Modifier.matchParentSize().then(bandModifier))
      GalleryTimelineHeader(
        title = GridText.sectionTitle(section, today, strings),
        place = GridText.sectionPlace(section, state.placeNames),
        level = section.level.timelineLevel(),
        modifier = Modifier.height(height),
      )
    }
  }
}

/** Vero se l'intestazione della lista [row] e' salita sopra la linea di aggancio (la mostra quella agganciata). */
private fun headerHandedOver(state: MediaGridState, layout: GridLayout, row: Int, stickyTopPx: Int): Boolean {
  if (!state.transform(layout).isIdentity || state.layout !== layout) return false
  val ownTop = GridGeometry.rowScreenTop(layout, row, state.contentTop(layout), state.viewport)
  return ownTop < stickyTopPx
}

/** Quanto si vede l'intestazione agganciata: compare mentre quella della lista passa sotto la linea di aggancio. */
private fun stickyAlpha(
  state: MediaGridState,
  layout: GridLayout,
  header: Int,
  contentTop: Int,
  v: GridViewport,
  stickyTopPx: Int,
  fadePx: Float,
): Float {
  // Durante un pinch la griglia si trasforma sotto: l'intestazione si toglie di mezzo.
  if (header >= layout.rowCount || !state.transform(layout).isIdentity) return 0f
  // Una fascia appena sotto la linea: il bordo alto dell'intestazione della lista e la linea coincidono.
  if (GridGeometry.rowScreenTop(layout, header, contentTop, v) >= stickyTopPx) return 0f
  val ownTop = v.padTop + layout.rowTop(header) - contentTop
  return ((stickyTopPx - ownTop) / fadePx).coerceIn(MinBand, 1f)
}

/** La riga d'intestazione della sezione sotto la linea di aggancio, se quell'intestazione e' gia' salita sopra la linea. */
private fun stickyHeaderRow(state: MediaGridState, layout: GridLayout, stickyTopPx: Int): Int {
  if (layout.rowCount == 0) return -1
  val v = state.viewport
  val top = state.contentTop(layout)
  val y = GridGeometry.contentY(stickyTopPx.toFloat(), top, v)
  if (y < 0f) return -1
  val row = layout.rowAtOffset(y.toInt().coerceAtMost(max(0, layout.totalHeight - 1)))
  val header = headerRowOfSection(layout, layout.sectionOfRow(row))
  if (header < 0) return -1
  val headerTop = v.padTop + layout.rowTop(header) - top
  return if (headerTop < stickyTopPx) header else -1
}

/** La prima riga della sezione [section] (la sua intestazione): ricerca binaria, le sezioni sono contigue. */
private fun headerRowOfSection(layout: GridLayout, section: Int): Int {
  var lo = 0
  var hi = layout.rowCount - 1
  while (lo < hi) {
    val mid = (lo + hi) ushr 1
    if (layout.sectionOfRow(mid) < section) lo = mid + 1 else hi = mid
  }
  return if (layout.rowKind(lo) == RowKind.HEADER) lo else -1
}

private fun nextHeaderRow(layout: GridLayout, header: Int): Int {
  val section = layout.sectionOfRow(header)
  if (section + 1 >= layout.sections.size) return -1
  return headerRowOfSection(layout, section + 1)
}

private fun GridLevel.timelineLevel(): GalleryTimelineLevel = when (this) {
  GridLevel.YEARS -> GalleryTimelineLevel.Year
  GridLevel.MONTHS -> GalleryTimelineLevel.Month
  GridLevel.DAYS -> GalleryTimelineLevel.Day
  GridLevel.ALL -> GalleryTimelineLevel.All
}

/** Le azioni di una cella per l'accessibilita' (i tocchi veri li riconosce la griglia). Una per griglia. */
private class GridCellActions(
  private val state: MediaGridState,
  private val strings: GridStrings,
  private val open: (MediaId) -> Unit,
) : GalleryGridCellActions {

  private val zone: ZoneId = ZoneId.systemDefault()

  override fun describe(key: Any): String {
    val id = key as? MediaId ?: return ""
    val index = state.indexOf(id)
    if (index < 0) return ""
    return GridText.describe(state.shownView.snapshot, index, strings, state.placeNames, zone)
  }

  override fun click(key: Any) {
    val id = key as? MediaId ?: return
    if (state.selecting) state.selection.toggle(id) else open(id)
  }

  override fun longClick(key: Any) {
    val id = key as? MediaId ?: return
    if (!state.selecting) state.startSelecting()
    if (id !in state.selection.selected.value) state.selection.toggle(id)
  }

  override val clickLabel: String get() = if (state.selecting) strings.select else strings.open
  override val longClickLabel: String get() = strings.select
}

/** La miniatura di una cella senza copia leggibile: nessuna, per sempre. */
private val NoImage: State<ImageBitmap?> = mutableStateOf(null)

private const val FooterKey = "footer"

/** Lo scrubber serve solo a una Libreria lunga: almeno tre schermate. */
private const val ScrubberMinScreens = 3

/** Oltre questo numero di elementi l'intestazione non verifica se la sezione e' tutta selezionata (sarebbe lento). */
private const val SectionCheckLimit = 600

private const val Invisible = 0.004f

/** Il tag dell'intestazione agganciata (test e strumenti di misura). */
const val MediaGridStickyHeaderTag: String = "griglia-intestazione-agganciata"

/**
 * Spinta dalla sezione successiva, l'intestazione agganciata e' sparita quando l'altra le e' salita sotto di tanto: i
 * testi di un'intestazione stanno al centro, con almeno 8 dp sopra e 8 dp sotto, quindi il titolo che arriva non tocca
 * quello agganciato prima di 16 dp.
 */
private val StickyPushFade = 16.dp

/** Il minimo della fascia appena l'intestazione e' agganciata (sopra zero: il testo c'e' gia'). */
private const val MinBand = 0.01f

/** La strada (in altezza) in cui l'intestazione agganciata compare sopra quella della lista. */
private val StickyFade: Dp = 12.dp
