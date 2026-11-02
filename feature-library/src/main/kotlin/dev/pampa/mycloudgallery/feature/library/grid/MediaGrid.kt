package dev.pampa.mycloudgallery.feature.library.grid

import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragPointer
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragTouch
import dev.pampa.mycloudgallery.core.designsystem.adaptive.mediaDragSource
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCell
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellActions
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellBadges
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryPresence
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.grid.forWidth
import dev.pampa.mycloudgallery.core.designsystem.grid.galleryScrubberShouldShow
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineHeader
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineHeaderAction
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineLevel
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridLevels
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * **Niente vetro proprio** (G2): la chrome sopra la griglia (l'etichetta con la sezione, i comandi, lo scrubber,
 * `GridPageChrome.kt`) rifrange il fondale della pagina che la contiene. La griglia non registra un secondo fondale a
 * tutto schermo e non disegna un'intestazione agganciata: la sezione sotto la riga di chrome la dice l'etichetta in alto
 * ([rememberMediaGridSection]), e le intestazioni della lista le cedono il posto sfumando prima della linea.
 *
 * @param contentPadding lo spazio della chrome in alto e della pillola in basso: il contenuto ci scorre sotto, rifratto.
 * @param footer in fondo, sotto i piu' recenti: il riepilogo "N foto, M video" e lo stato della rete e del NAS.
 * @param stickyTop la linea della chrome in alto: un'intestazione della lista che ci sale sopra sfuma, e l'etichetta in
 *   alto dice quella sezione.
 * @param onLevelChanged il livello si e' assestato dopo un pinch (per ricordarlo).
 * @param cellCornerScale quanto dell'angolo delle celle resta: di serie la parete di foto squadrata ([GalleryWall]).
 * @param gap la fessura fra le celle: di serie quella della parete (1 dp).
 * @param dragOrigin Fase 10 (P6, D20): da dove parte un trascinamento verso un'altra app (la Libreria); null = questa griglia non trascina (i Nascosti, gli Eliminati di recente: mai, E-T18). Cosa si trascina lo decide [gridDragSubject].
 * @param onDeleteKey Canc (Fase 10, P4, D15): **apre il foglio di conferma** dell'eliminazione, mai un'eliminazione diretta. Null: questa griglia non elimina e Canc non fa niente.
 *   Mouse e tastiera (evidenziazione, Invio, Ctrl+A) sono di ogni griglia ([gridKeyboard]).
 */
@Composable
fun MediaGrid(
  state: MediaGridState,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  onOpen: (MediaId) -> Unit = {},
  footer: @Composable () -> Unit = {},
  stickyTop: Dp = contentPadding.calculateTopPadding(),
  onLevelChanged: (GridLevel) -> Unit = {},
  cellCornerScale: Float = GalleryWall.CornerScale,
  gap: Dp = GalleryWall.Gap,
  onDeleteKey: (() -> Unit)? = null,
  dragOrigin: DragOrigin? = null,
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
    val currentOnDeleteKey by rememberUpdatedState(onDeleteKey)
    val currentOnLevelChanged by rememberUpdatedState(onLevelChanged)
    val currentDragOrigin by rememberUpdatedState(dragOrigin)
    val currentReduced by rememberUpdatedState(reducedMotion)

    // --- I layout -----------------------------------------------------------------------------
    val levels = remember(maxWidth) { GalleryGridLevels.forWidth(maxWidth.value) }
    val gapPx = with(density) { gap.roundToPx() }
    // Le intestazioni crescono col testo di sistema: il motore le misura in dp per questa densita'.
    val headerDensity = density.density * max(1f, density.fontScale)
    val inputs = remember(state.view, state.style, widthPx, gapPx, headerDensity, levels) {
      GridLayoutInputs(state.view, state.style, widthPx, gapPx, headerDensity, levels.map { it.columns })
    }
    // Il livello mostrato: a riposo quello scelto; durante un pinch o un salto, il piu' fitto che la scala ingrandisce.
    val shownLevel by remember(state, widthPx, gapPx) { derivedStateOf { state.displayLevelFor(widthPx, gapPx) } }
    val level = shownLevel
    val ready = state.layouts.get(inputs, level)
    // Avvio istantaneo: la vista grande della Libreria trova il layout gia' preparato mentre il processo nasceva (`GridWarmup`).
    val immediate = if (ready == null) remember(inputs, level) { state.layouts.layoutNowIfSmall(inputs, level) ?: GridWarmup.take(inputs, level) } else null
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
      if (renderLayout != null && state.reportsStartup) AvvioTrace.markOnce(AvvioTrace.GRID_SHOWN)
    }
    LaunchedEffect(inputs) { state.layouts.compute(inputs, state.level) }

    GridEffects(state, reducedMotion)

    val style = rememberGalleryGridCellStyle(cellCornerScale)
    val strings = rememberGridStrings()
    val actions = remember(state, strings) { GridCellActions(state, strings) { currentOnOpen(it) } }
    val today = remember(state) { state.today() }
    val stickyTopPx = with(density) { stickyTop.roundToPx() }
    val handOverPx = with(density) { HeaderHandOver.toPx() }
    val context = remember(state, renderView, style, actions, strings, today, gapPx, stickyTopPx, handOverPx) {
      GridRenderContext(state, renderView, style, actions, strings, today, gapPx, stickyTopPx, handOverPx)
    }
    val callbacks = remember(state, haptics) {
      GridGestureCallbacks(
        onOpen = { currentOnOpen(it) },
        reducedMotion = { currentReduced },
        haptics = haptics,
        onLevelSettled = { currentOnLevelChanged(state.level) },
        // La pressione lunga di una cella che comincia un trascinamento verso un'altra app non e' una selezione (P6): la stessa scelta della sorgente.
        ownsLongPress = { point, mouse ->
          currentDragOrigin != null && gridDragSubject(state, DragTouch(point, if (mouse) DragPointer.MOUSE else DragPointer.TOUCH)) != null
        },
      )
    }

    val keyCallbacks = remember(onDeleteKey != null) { GridKeyCallbacks(onOpen = { currentOnOpen(it) }, onDeleteSheet = onDeleteKey?.let { { currentOnDeleteKey?.invoke() } }) }
    Box(
      modifier = Modifier
        .fillMaxSize()
        .gridKeyboard(state, keyCallbacks)
        .gridGestures(state, scope, callbacks)
        // Fase 10 (P6, D20): trascinare le foto (o la selezione) verso un'altra app. Senza origine, o senza porta installata, non fa niente.
        .mediaDragSource(enabled = dragOrigin != null, origin = dragOrigin?.code.orEmpty()) { touch -> gridDragSubject(state, touch) },
    ) {
      LazyColumn(
        state = state.listState,
        modifier = Modifier
          .fillMaxSize()
          // Avvio istantaneo: la prima finestra delle miniature parte appena la lista e' misurata, nello stesso fotogramma.
          .onPlaced { state.sendFirstWindow() }
          .pinchLayer(state),
        contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
        userScrollEnabled = !state.pinching,
      ) {
        val l = renderLayout
        if (l != null) {
          items(count = l.rowCount, key = { rowKey(l, it) }, contentType = { l.rowKind(it) }) { row -> GridRow(context, l, row) }
          item(key = FooterKey, contentType = FooterKey) {
            Box(modifier = Modifier.fillMaxWidth().onSizeChanged { state.footerHeight = it.height }) { footer() }
          }
        }
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
        if (state.watchingUseful) state.updateVisibleThumbs(plan.visible.map { it.key })
      }
  }

  // Avvio istantaneo: il primo disegno utile della Libreria (ogni miniatura in vista arrivata), una volta per processo; poi non si segue piu'.
  if (state.reportsStartup) {
    LaunchedEffect(state) { state.loader.stats.first { state.noteUsefulIfReady(); !state.watchingUseful } }
  }

  // I segnaposto delle celle attorno a dove si e', per tutti i livelli, decodificati su un thread di lavoro: la
  // composizione delle celle (un cambio di livello, una riga che entra) li trova gia' pronti (`PlaceholderWarmup`).
  LaunchedEffect(state) {
    val cache = state.placeholders
    if (!cache.threadSafe) return@LaunchedEffect
    snapshotFlow { state.layout?.let { l -> PlaceholderWarmup.around(state, l) } }
      .filterNotNull()
      .distinctUntilChanged()
      .collectLatest { target -> withContext(Dispatchers.Default) { target.warm(cache) } }
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
  // Il piede cresce mentre lo si guarda (arriva la voce del NAS, un dettaglio va a capo): la lista lo segue, cosi' resta
  // tutto sopra la pillola. Solo se si era in fondo (il piede in vista, o la griglia mai trascinata): altrove non si salta.
  LaunchedEffect(state) {
    var previous = state.footerHeight
    snapshotFlow { state.footerHeight }.collect { height ->
      val grew = previous in 1 until height
      previous = height
      val l = state.layout ?: return@collect
      val footerShown = state.listState.layoutInfo.visibleItemsInfo.any { it.key == FooterKey }
      if (grew && l.rowCount > 0 && (!state.userScrolled || footerShown)) state.listState.requestScrollToItem(l.rowCount, 0)
    }
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

/**
 * La chiave di una riga della lista: la riga **nel suo layout** (livello e stile). Senza chiave le righe pigre sono
 * indicizzate per posizione: a un cambio di livello (un pinch, il menu) la lista ricomponeva le righe che erano sullo
 * schermo con lo **stesso indice nel layout nuovo**, cioe' righe lontanissime (sull'S25, 15/10: celle a 30.000 posizioni
 * da quelle in vista, con i loro segnaposto da decodificare), e subito dopo, nella misura, componeva quelle giuste
 * dell'ancora. Con la chiave le righe del layout vecchio escono e basta. Una vista nuova allo stesso livello (dati
 * aggiornati) tiene le stesse chiavi: le righe si ricompongono sul posto come prima.
 */
private fun rowKey(l: GridLayout, row: Int): Long =
  (l.spec.level.ordinal.toLong() shl LevelKeyShift) or (l.spec.style.ordinal.toLong() shl StyleKeyShift) or row.toLong()

private const val LevelKeyShift = 40
private const val StyleKeyShift = 32

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
  /** La linea della chrome in alto (pixel della griglia): sopra, le intestazioni della lista hanno ceduto il posto. */
  val stickyTopPx: Int,
  /** La strada (pixel) in cui un'intestazione sfuma prima della linea. */
  val handOverPx: Float,
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
      // Avvicinandosi alla linea della chrome l'intestazione sfuma, e quando la tocca l'etichetta in alto dice la sua
      // sezione: si passa il testo, nessun doppione. Letto nel layer: scorrere non ricompone.
      // L'alfa si applica a ogni disegno (`ModulateAlpha`), non a un buffer fuori schermo: l'intestazione e' testo che non
      // si sovrappone, quindi si vede uguale, e il RenderThread non alloca un layer a tutta larghezza per ogni
      // intestazione che sfuma (S25, 15/10: memoria GPU riallocata a ogni passaggio, docs/bench/misure-s25-2026-10-15.md).
      .graphicsLayer {
        alpha = headerAlpha(state, layout, row, context.stickyTopPx, context.handOverPx)
        compositingStrategy = CompositingStrategy.ModulateAlpha
      },
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
    badges = badgesOf(snapshot.kindAt(index), snapshot.flagsAt(index), snapshot.presenceAt(index), { snapshot.durationMsAt(index) }, { snapshot.groupSizeAt(index) }),
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

/**
 * I segni di una cella: quelli della Fase 3 (D15: durata, preferito, modificata, non leggibile) e la presenza della
 * Fase 4 (D18, `TimelineSnapshot.presenceAt`: l'indice non da' "solo telefono" finche' nessuna cella e' sul NAS).
 */
private inline fun badgesOf(
  kind: MediaKind,
  flags: Int,
  presence: PresenceBadge?,
  duration: () -> Long,
  groupSize: () -> Int,
): GalleryGridCellBadges {
  val marks = flags and (CellFlags.FAVORITE or CellFlags.EDITED or CellFlags.UNREADABLE or CellFlags.SIMILAR_COPIES)
  if (marks == 0 && kind != MediaKind.VIDEO && presence == null) return GalleryGridCellBadges.None
  return GalleryGridCellBadges(
    favorite = CellFlags.has(flags, CellFlags.FAVORITE),
    edited = CellFlags.has(flags, CellFlags.EDITED),
    duration = if (kind == MediaKind.VIDEO) GridText.duration(duration()) else null,
    unreadable = CellFlags.has(flags, CellFlags.UNREADABLE),
    presence = presence?.toGallery(),
    // D36: il segno "copie" dipende dal solo flag della cella (D15); il numero e' la dimensione del gruppo che l'indice ha gia'.
    copies = if (CellFlags.has(flags, CellFlags.SIMILAR_COPIES)) groupSize() else 0,
  )
}

/** La presenza dell'indice come la disegna il design system. */
internal fun PresenceBadge.toGallery(): GalleryPresence = when (this) {
  PresenceBadge.PHONE_ONLY -> GalleryPresence.PhoneOnly
  PresenceBadge.NAS_ONLY -> GalleryPresence.NasOnly
  PresenceBadge.BOTH -> GalleryPresence.Both
  PresenceBadge.SAFE -> GalleryPresence.Safe
  PresenceBadge.TO_VERIFY -> GalleryPresence.ToVerify
  // Fase 5 (D15): il disco intero del catalogo G2 (§12.4 della grafica); PresenceBadge.of lo da' prima di "al sicuro".
  PresenceBadge.PROBLEM -> GalleryPresence.Problem
}

/**
 * Quanto si vede l'intestazione della lista [row]: piena finche' sta sotto la linea della chrome, poi sfuma salendoci
 * sopra per [handOverPx], e quando e' sparita l'etichetta in alto dice la sua sezione ([handOverLine]): si passa il testo,
 * nessun doppione. In cima alla lista la prima intestazione sta sulla linea e si vede. Durante un pinch e mentre un
 * layout nuovo sta entrando resta piena: la linea vale solo per il layout sullo schermo, fermo.
 */
private fun headerAlpha(state: MediaGridState, layout: GridLayout, row: Int, stickyTopPx: Int, handOverPx: Float): Float {
  if (stickyTopPx <= 0 || !state.transform(layout).isIdentity || state.layout !== layout) return 1f
  val ownTop = GridGeometry.rowScreenTop(layout, row, state.contentTop(layout), state.viewport)
  if (ownTop >= stickyTopPx) return 1f
  if (handOverPx <= 0f) return 0f
  return (1f - (stickyTopPx - ownTop) / handOverPx).coerceIn(0f, 1f)
}

/** La linea su cui l'etichetta in alto legge la sezione: sopra la linea della chrome, dove le intestazioni sono sparite. */
internal fun handOverLine(stickyTop: Dp): Dp = stickyTop - HeaderHandOver

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

/** Oltre questo numero di elementi l'intestazione non verifica se la sezione e' tutta selezionata (sarebbe lento). */
private const val SectionCheckLimit = 600

/**
 * La strada in cui un'intestazione della lista sfuma salendo sopra la linea della chrome: li' sta sotto la sfumatura di
 * bordo e accanto al fondo dell'etichetta, che la dice appena e' sparita.
 */
private val HeaderHandOver: Dp = 20.dp
