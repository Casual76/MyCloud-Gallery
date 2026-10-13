package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryScrubberState
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridLevel
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridLevels
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridPinchState
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.Anchor
import dev.pampa.mycloudgallery.core.library.contract.GridAnchors
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.PositionIds
import dev.pampa.mycloudgallery.core.library.contract.ScrubberModel
import dev.pampa.mycloudgallery.core.library.contract.SelectionModel
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.layout.TimelineGridAnchors
import dev.pampa.mycloudgallery.core.library.layout.TimelineScrubber
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dove si era nella griglia, **come elemento ancorato e scostamento, mai come indice** (E-P18): l'elemento al centro
 * dell'area dei contenuti e a che altezza stava. Sopravvive a rotazioni, ridimensionamenti, cambi di livello, di stile e
 * di filtro, perche' dopo uno di questi cambi lo stesso indice indica un altro elemento.
 *
 * @property fy dove, nell'altezza della cella, stava il centro (0..1).
 * @property screenFraction a che altezza dell'area dei contenuti stava (0..1).
 * @property atNewest si era in fondo, sui piu' recenti: si torna in fondo (anche se nel frattempo ne sono arrivati altri).
 */
@Immutable
data class SavedGridPosition(val mediaId: String, val fy: Float, val screenFraction: Float, val atNewest: Boolean)

/**
 * Lo stato della griglia: tutto cio' che [MediaGrid] disegna e ricorda, e solo quello. E' il contratto fra la griglia e
 * chi la usa: la Libreria, le Raccolte filtrate, i Nascosti e gli Eliminati di recente costruiscono ciascuno il proprio
 * [MediaGridState] e passano la stessa griglia (docs/fase3-progetto.md §1.2).
 *
 * **Come si muove** (D3): una `LazyColumn` di righe precalcolate. Il layout di ogni livello si calcola fuori dal thread
 * principale ([GridLayoutStore]); la posizione si esprime sempre come elemento ancorato, e ogni volta che il layout
 * cambia (pinch, segmenti, rotazione, filtro, istantanea nuova) la griglia rimette lo stesso elemento nello stesso punto
 * con `requestScrollToItem`, **nello stesso fotogramma** in cui il layout nuovo entra ([onLayoutShown]).
 *
 * @param refOf dove stanno i byte di un media, per id: il viewer e le azioni.
 * @param refAt lo stesso per posizione nell'indice, senza passare dall'id: e' quello che usa la cella (percorso caldo).
 *   Null se adesso non c'e' una copia leggibile (E-P22): la cella resta sul segnaposto. Di default l'indice a colonne di
 *   P5 (`ColumnarTimelineSnapshot.refAt`), altrimenti [refOf].
 * @param versionKeyAt la parte della chiave della miniatura che cambia con una modifica del file (`ThumbKey.versionKey`):
 *   di default `ColumnarTimelineSnapshot.versionKeyAt`.
 * @param anchors l'ancoraggio del pinch e del ritorno dal viewer: `TimelineGridAnchors` di P5.
 * @param scrubber la mappa dello scrubber: `TimelineScrubber` di P5.
 * @param layoutDispatcher dove si impaginano le viste grandi.
 * @param today il giorno di oggi, per "Oggi" e "Ieri" nelle intestazioni.
 */
@Stable
class MediaGridState(
  view: TimelineView,
  val loader: ThumbnailLoader,
  val placeholders: PlaceholderCache,
  val placeNames: PlaceNames,
  val engine: GridLayoutEngine,
  val refOf: (MediaId) -> MediaRef,
  level: GridLevel = GridLevel.DAYS,
  style: GridStyle = GridStyle.SQUARE,
  val anchors: GridAnchors = TimelineGridAnchors,
  val scrubber: ScrubberModel = TimelineScrubber,
  val refAt: (TimelineSnapshot, Int) -> MediaRef? = { snapshot, index ->
    if (snapshot is ColumnarTimelineSnapshot) snapshot.refAt(index) else runCatching { refOf(snapshot.idAt(index)) }.getOrNull()
  },
  val versionKeyAt: (TimelineSnapshot, Int) -> Long = ::defaultVersionKeyAt,
  layoutDispatcher: CoroutineDispatcher = Dispatchers.Default,
  val today: () -> LocalDate = { LocalDate.now() },
) {

  private val viewFlow = MutableStateFlow(view)
  private var viewState: TimelineView by mutableStateOf(view)

  /** La vista che la griglia deve mostrare (con il suo filtro). Cambiarla rimette la griglia sullo stesso elemento. */
  var view: TimelineView
    get() = viewState
    set(value) {
      viewState = value
      viewFlow.value = value
    }

  /** Lo stile delle celle (D4). Cambiarlo resta ancorato. */
  var style: GridStyle by mutableStateOf(style)

  internal var pinch: GalleryGridPinchState by mutableStateOf(GalleryGridPinchState(GalleryGridLevels.Default, level.ordinal))
    private set

  /** Il livello della griglia. Assegnarlo salta senza animazione; i segmenti passano da [selectLevel], il pinch dal gesto. */
  var level: GridLevel
    get() = GridLevel.entries[pinch.level]
    set(value) {
      if (value.ordinal == pinch.level) return
      origin = viewportCenter()
      pinch = GalleryGridPinchState(pinch.levels, value.ordinal)
    }

  /** La selezione a trascinamento. */
  val selection: SelectionModel = SelectionModel()

  /** Vero in modalita' selezione ("Seleziona", o una pressione lunga): ogni cella mostra il cerchio. */
  var selecting: Boolean by mutableStateOf(false)
    internal set

  /** Lo scorrimento. Si salva come elemento ancorato ([savePosition]), mai come indice. */
  val listState: LazyListState = LazyListState()

  /** Il layout sullo schermo adesso: la geometria per il "porta in vista", l'hit test e l'ancoraggio. */
  var layout: GridLayout? by mutableStateOf(null)
    internal set

  /** La vista da cui e' fatto [layout] (puo' restare indietro di [view] mentre il layout nuovo si impagina). */
  internal var shownView: TimelineView by mutableStateOf(view)
    private set

  internal val interaction = GalleryGridInteractionState()
  internal val scrubberState = GalleryScrubberState()
  internal val layouts = GridLayoutStore(engine, layoutDispatcher)
  internal var viewport: GridViewport by mutableStateOf(GridViewport())

  /** Il fondale di vetro con la sola lista (lo registra [MediaGrid]): lo rifrangono l'intestazione agganciata e lo scrubber. */
  internal var listBackdrop: GlassBackdropState? by mutableStateOf(null)

  /** Dove sta la griglia nella finestra (per i bordi del viewer). */
  internal var originInWindow: Offset = Offset.Zero

  /** L'altezza del piede (riepilogo e stato globale), per sapere dove finisce la lista. */
  internal var footerHeight: Int = 0

  /** L'origine della scala del pinch, in pixel della griglia. */
  internal var origin: Offset by mutableStateOf(Offset.Zero)

  /** Vero finche' due dita pizzicano. */
  internal var pinching: Boolean by mutableStateOf(false)

  /** Vero dopo che l'utente ha trascinato: da li' la griglia non si riporta piu' in fondo da sola. */
  internal var userScrolled: Boolean = false

  /** Una posizione salvata da rimettere al primo layout (E-P18). */
  private var pendingRestore: SavedGridPosition? = null

  /** Solo per i test di budget: chi conta le ricomposizioni di righe e celle. */
  internal var probe: GridProbe? = null

  private val recentIndex = object : LinkedHashMap<MediaId, Int>(RecentCells, LoadFactor, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaId, Int>?): Boolean = size > RecentCells
  }

  private val visibleIdsFlow = MutableStateFlow<List<MediaId>>(emptyList())

  /**
   * I media sullo schermo adesso (`MetaPriority.visible`, D11): `:app` lo collega alla fase META, cosi' i metadati di cio'
   * che l'utente guarda arrivano per primi. Si aggiorna quando cambia la finestra, non a ogni fotogramma.
   */
  val visibleIds: StateFlow<List<MediaId>> = visibleIdsFlow.asStateFlow()

  // ------------------------------------------------------------------------------------------
  // Geometria corrente
  // ------------------------------------------------------------------------------------------

  internal val scroll: GridScroll get() = GridScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)

  /** La coordinata del contenuto al bordo alto dell'area dei contenuti, nel layout [l]. */
  internal fun contentTop(l: GridLayout): Int = GridGeometry.contentTop(l, scroll)

  /**
   * La trasformazione del pinch sul layout [shown]: la scala e' la dimensione **apparente** delle celle (quella del
   * livello del pinch, per la sua scala) divisa per quella delle celle mostrate. Cosi' un layout che arriva in ritardo
   * (impaginazione in corso) si vede comunque alla dimensione giusta, e il cambio di livello non fa saltare niente.
   */
  internal fun transform(shown: GridLayout? = layout): GridTransform {
    val l = shown ?: return GridTransform.Identity
    val p = pinch
    val pinchScale = p.scale.value
    val scale = if (l.spec.level.ordinal == p.level) {
      pinchScale
    } else {
      val columns = p.levels[p.level].columns
      val apparent = (l.spec.widthPx - l.spec.gapPx * (columns - 1)).toFloat() / columns * pinchScale
      apparent / l.spec.cellPx.coerceAtLeast(1)
    }
    if (scale == 1f) return GridTransform.Identity
    return GridTransform(origin.x, origin.y, scale)
  }

  /**
   * Il livello da **mostrare** mentre la scala del pinch e' in moto: quello con le celle piu' grandi fra quelli che non
   * superano la dimensione apparente. Cosi' la griglia mostrata e' sempre ingrandita (scala >= 1) e non scopre mai il
   * fondo: zoomando si vede il livello piu' fitto ingrandito, e al passaggio per la misura naturale di un livello la
   * griglia passa a quello, ancorata al dito. Oltre "Anni" (l'elastico) resta "Anni", appena rimpicciolito. A riposo
   * (scala 1) e' il livello del pinch.
   */
  internal fun displayLevelFor(widthPx: Int, gapPx: Int): GridLevel {
    val p = pinch
    val scale = p.scale.value
    if (scale == 1f) return GridLevel.entries[p.level]
    val columns = p.levels.map { it.columns }
    fun cell(index: Int): Float = (widthPx - gapPx * (columns[index] - 1)).toFloat() / columns[index]
    val apparent = cell(p.level) * scale * (1f + LevelTolerance)
    var best = 0
    for (index in columns.indices) if (cell(index) <= apparent && cell(index) >= cell(best)) best = index
    return GridLevel.entries[best]
  }

  /** Il centro dell'area dei contenuti: l'origine dei cambi di livello che non vengono da un dito. */
  internal fun viewportCenter(): Offset {
    val v = viewport
    return Offset(v.width / 2f, v.padTop + v.contentHeight / 2f)
  }

  /** La cella sotto il punto [point] della griglia (schermo, trasformazione inclusa), o -1. */
  internal fun positionAt(point: Offset): Int {
    val l = layout ?: return -1
    val p = transform(l).toGrid(point.x, point.y)
    return GridGeometry.positionAt(l, p.x, p.y, contentTop(l), viewport)
  }

  /** La posizione nell'indice della cella [position] del layout sullo schermo. */
  internal fun snapshotIndexAt(l: GridLayout, position: Int): Int = shownView.snapshotIndex(l.viewIndexAt(position))

  /** L'identita' della cella in posizione [position] (crea un `MediaId`: per i gesti, non per il disegno). */
  internal fun idAt(position: Int): MediaId? {
    val l = layout ?: return null
    if (position !in 0 until l.itemCount) return null
    val index = snapshotIndexAt(l, position)
    return shownView.snapshot.idAt(index).also { noteIndex(it, index) }
  }

  internal val positionIds: PositionIds = PositionIds { position -> idAt(position) ?: MediaId(NoId) }

  /** La posizione nell'indice di un media visto di recente (le celle composte), senza cercare nell'indice intero. */
  internal fun indexOf(id: MediaId): Int = recentIndex[id] ?: shownView.snapshot.indexOf(id)

  internal fun noteIndex(id: MediaId, index: Int) {
    recentIndex[id] = index
  }

  /** La chiave della miniatura della cella [index] dell'indice, alla taglia [bucket]. */
  internal fun thumbKeyAt(snapshot: TimelineSnapshot, index: Int, id: MediaId, bucket: ThumbBucket): ThumbKey =
    ThumbKey(id, versionKeyAt(snapshot, index), bucket)

  /**
   * La chiave della miniatura di [id], per partire dalla stessa bitmap della cella (`ThumbnailLoader.peek`): la taglia e'
   * quella della cella nel layout sullo schermo; [bucket] se il media non e' nella griglia.
   */
  fun thumbKey(id: MediaId, bucket: ThumbBucket = ThumbBucket.L): ThumbKey {
    val v = shownView
    val l = layout
    val viewIndex = v.viewIndexOf(id)
    if (viewIndex < 0 || l == null || l.itemCount == 0) return ThumbKey(id, DefaultVersionKey, bucket)
    val position = l.positionOfViewIndex(viewIndex)
    val cellBucket = GridGeometry.bucketFor(l.itemWidth(position), l.rowHeight(l.rowOfItem(position)))
    return thumbKeyAt(v.snapshot, v.snapshotIndex(viewIndex), id, cellBucket)
  }

  // ------------------------------------------------------------------------------------------
  // Cambi di layout, sempre ancorati
  // ------------------------------------------------------------------------------------------

  /**
   * Il layout [next] (fatto da [nextView]) entra al posto di quello sullo schermo. Si chiama nello stesso fotogramma in
   * cui la griglia lo compone (`SideEffect`), prima della misura della lista: `requestScrollToItem` vale gia' li'.
   */
  internal fun onLayoutShown(next: GridLayout, nextView: TimelineView) {
    val previous = layout
    if (previous === next) return
    val previousView = shownView
    val target: Pair<Int, Int>? = when {
      next.rowCount == 0 -> null
      previous == null || previous.rowCount == 0 -> pendingRestore?.let { restoreTarget(next, nextView, it) } ?: bottom(next)
      // Solo il livello e' cambiato (pinch, segmenti): l'elemento sotto il dito (o al centro) resta li'.
      onlyLevelChanged(previous, previousView, next, nextView) -> fingerTarget(previous, previousView, next, nextView)
      stickToBottom() -> bottom(next)
      else -> centerTarget(previous, previousView, next, nextView)
    }
    if (next.rowCount > 0) pendingRestore = null
    layout = next
    shownView = nextView
    target?.let { (row, offset) -> listState.requestScrollToItem(row, offset) }
  }

  private fun onlyLevelChanged(previous: GridLayout, previousView: TimelineView, next: GridLayout, nextView: TimelineView): Boolean {
    val a = previous.spec
    val b = next.spec
    return a.level != b.level && previousView === nextView && a.style == b.style && a.widthPx == b.widthPx &&
      a.gapPx == b.gapPx && a.densityDp == b.densityDp
  }

  /** In fondo, sui piu' recenti (D1): si chiede il piede, e la lista si ferma da sola all'ultima posizione possibile. */
  private fun bottom(l: GridLayout): Pair<Int, Int> = l.rowCount to 0

  /** Si resta in fondo finche' l'utente non trascina, o se ci e' tornato. */
  internal fun stickToBottom(): Boolean = !userScrolled || !listState.canScrollForward

  /**
   * La riga dell'elemento sotto il dito resta sotto il dito (pinch e segmenti). In orizzontale l'elemento prende la
   * colonna che ha nel livello nuovo: spostare tutta la griglia per tenerlo sotto il dito scoprirebbe mezza schermata.
   */
  private fun fingerTarget(previous: GridLayout, previousView: TimelineView, next: GridLayout, nextView: TimelineView): Pair<Int, Int> {
    val v = viewport
    val finger = origin
    val grid = transform(previous).toGrid(finger.x, finger.y)
    val contentY = GridGeometry.contentY(grid.y, contentTop(previous), v)
    val row = previous.rowAtOffset(contentY.toInt().coerceIn(0, max(0, previous.totalHeight - 1)))
    val anchor = anchors.anchorAt(previous, row, grid.x, contentY - previous.rowTop(row), finger.y - v.padTop)
    val placed = sameItems(previous, previousView, next, nextView, anchor)
    val target = anchors.place(next, placed)
    return target.row to target.offsetPx
  }

  /** Lo stesso elemento al centro dell'area dei contenuti (rotazione, stile, filtro, istantanea nuova). */
  private fun centerTarget(previous: GridLayout, previousView: TimelineView, next: GridLayout, nextView: TimelineView): Pair<Int, Int> {
    val v = viewport
    val centerY = v.padTop + v.contentHeight / 2f
    val contentY = GridGeometry.contentY(centerY, contentTop(previous), v)
    val row = previous.rowAtOffset(contentY.toInt().coerceIn(0, max(0, previous.totalHeight - 1)))
    val anchor = anchors.anchorAt(previous, row, v.width / 2f, contentY - previous.rowTop(row), centerY - v.padTop)
    val target = anchors.place(next, sameItems(previous, previousView, next, nextView, anchor))
    return target.row to target.offsetPx
  }

  /**
   * Un'ancora di [previous] riportata alle posizioni di [next]. Con la stessa vista le posizioni coincidono; con una vista
   * nuova (filtro, istantanea) si passa dall'identita' del media, e se il media non c'e' piu' dal piu' vicino nel tempo.
   */
  private fun sameItems(previous: GridLayout, previousView: TimelineView, next: GridLayout, nextView: TimelineView, anchor: Anchor): Anchor {
    if (previousView === nextView || previous.itemCount == 0) return anchor
    if (next.itemCount == 0) return anchor.copy(position = 0)
    val position = anchor.position.coerceIn(0, previous.itemCount - 1)
    val oldIndex = previousView.snapshotIndex(previous.viewIndexAt(position))
    val id = previousView.snapshot.idAt(oldIndex)
    val viewIndex = nextView.viewIndexOf(id).let { if (it >= 0) it else nearestByTime(nextView, previousView.snapshot.sortTimeAt(oldIndex)) }
    if (viewIndex < 0) return anchor.copy(position = 0)
    return anchor.copy(position = next.positionOfViewIndex(viewIndex))
  }

  private fun restoreTarget(next: GridLayout, nextView: TimelineView, saved: SavedGridPosition): Pair<Int, Int> {
    if (saved.atNewest) return bottom(next)
    val viewIndex = nextView.viewIndexOf(MediaId(saved.mediaId))
    if (viewIndex < 0) return bottom(next)
    val v = viewport
    val anchor = Anchor(next.positionOfViewIndex(viewIndex), 0.5f, saved.fy, saved.screenFraction * v.contentHeight)
    userScrolled = true
    val target = anchors.place(next, anchor)
    return target.row to target.offsetPx
  }

  /** Dove si e' adesso, come elemento ancorato (E-P18). Null se la griglia e' vuota. */
  fun savePosition(): SavedGridPosition? {
    val l = layout ?: return null
    if (l.itemCount == 0) return null
    val v = viewport
    if (stickToBottom()) {
      val last = shownView.snapshot.idAt(snapshotIndexAt(l, l.itemCount - 1))
      return SavedGridPosition(last.value, 0f, 0f, atNewest = true)
    }
    val centerY = v.padTop + v.contentHeight / 2f
    val contentY = GridGeometry.contentY(centerY, contentTop(l), v)
    val row = l.rowAtOffset(contentY.toInt().coerceIn(0, max(0, l.totalHeight - 1)))
    val anchor = anchors.anchorAt(l, row, v.width / 2f, contentY - l.rowTop(row), centerY - v.padTop)
    val id = shownView.snapshot.idAt(snapshotIndexAt(l, anchor.position.coerceIn(0, l.itemCount - 1)))
    val fraction = if (v.contentHeight > 0) anchor.screenY / v.contentHeight else Half
    return SavedGridPosition(id.value, anchor.fy, fraction, atNewest = false)
  }

  /** Rimette una posizione salvata (dopo una ricreazione dell'activity): subito se c'e' un layout, se no al primo. */
  fun restorePosition(saved: SavedGridPosition?) {
    if (saved == null) return
    val l = layout
    if (l == null || l.rowCount == 0) {
      pendingRestore = saved
      return
    }
    val (row, offset) = restoreTarget(l, shownView, saved)
    listState.requestScrollToItem(row, offset)
  }

  // ------------------------------------------------------------------------------------------
  // Livelli, pinch e segmenti
  // ------------------------------------------------------------------------------------------

  /** Le colonne dei livelli per la larghezza corrente (rotazione, tablet, finestre): il pinch si rifa' con le nuove. */
  internal fun adoptLevels(levels: List<GalleryGridLevel>) {
    if (levels.map { it.columns } != pinch.levels.map { it.columns }) pinch = GalleryGridPinchState(levels, pinch.level)
  }

  /** Un passo del pinch: [zoom] relativo, [centroid] in pixel della griglia. Il cambio di livello si ancora al dito. */
  internal suspend fun pinchBy(zoom: Float, centroid: Offset) {
    pinching = true
    userScrolled = true
    val v = viewport
    origin = centroid
    pinch.onPinch(zoom, Offset(centroid.x / max(1, v.width), centroid.y / max(1, v.height)))
  }

  /** Le dita si alzano: la scala torna a 1 con la molla dell'assestamento (`GalleryMotion.settleSpec`). */
  internal suspend fun endPinch(reducedMotion: Boolean) {
    pinching = false
    pinch.onRelease(reducedMotion)
  }

  /** I segmenti dei livelli: salta a [target] ancorato al centro, con la molla del pinch (`jumpTo`). */
  suspend fun selectLevel(target: GridLevel, reducedMotion: Boolean) {
    if (target.ordinal == pinch.level) return
    userScrolled = true
    origin = viewportCenter()
    pinch.jumpTo(target.ordinal, reducedMotion)
  }

  // ------------------------------------------------------------------------------------------
  // Scorrimento: oggi, scrubber, porta in vista
  // ------------------------------------------------------------------------------------------

  /**
   * "Torna a oggi" (D1): toccare di nuovo "Libreria" o il tasto "Oggi" riporta in fondo, sui piu' recenti. Da lontano la
   * lista salta vicino e fa a molla solo l'ultimo tratto (`animateScrollToItem`).
   */
  suspend fun scrollToNewest() {
    val rows = layout?.rowCount ?: return
    listState.animateScrollToItem(rows)
    userScrolled = false
  }

  /** La frazione (0 in cima, 1 in fondo) della posizione corrente: la maniglia dello scrubber. */
  internal fun scrollFraction(): Float {
    val l = layout ?: return 1f
    val max = GridGeometry.maxContentTop(l, footerHeight, viewport)
    return if (max <= 0) 1f else (contentTop(l).toFloat() / max).coerceIn(0f, 1f)
  }

  /** Lo scrubber porta a [fraction] (0..1 della corsa): la riga dalla mappa dello scrubber, lo scostamento dal resto. */
  internal fun scrubTo(fraction: Float) {
    val l = layout ?: return
    if (l.rowCount == 0) return
    userScrolled = true
    val max = GridGeometry.maxContentTop(l, footerHeight, viewport)
    val top = (fraction.coerceIn(0f, 1f) * max).roundToInt()
    val row = scrubber.rowAt(l, if (l.totalHeight > 0) top.toFloat() / l.totalHeight else 0f).coerceIn(0, l.rowCount - 1)
    listState.requestScrollToItem(row, top - l.rowTop(row))
  }

  /** La riga al centro dell'area dei contenuti (la bolla dello scrubber). */
  internal fun rowAtCenter(l: GridLayout): Int {
    val v = viewport
    val y = GridGeometry.contentY(v.padTop + v.contentHeight / 2f, contentTop(l), v)
    return l.rowAtOffset(y.toInt().coerceIn(0, max(0, l.totalHeight - 1)))
  }

  /** La riga in cima all'area dei contenuti (il mese nella barra al livello "Tutte"). */
  internal fun rowAtTop(l: GridLayout): Int = l.rowAtOffset(contentTop(l).coerceIn(0, max(0, l.totalHeight - 1)))

  /** Quanto si e' lontani dai piu' recenti, in schermate (il tasto "Oggi"). */
  internal fun screensFromNewest(): Float {
    val l = layout ?: return 0f
    val v = viewport
    if (v.contentHeight <= 0) return 0f
    val max = GridGeometry.maxContentTop(l, footerHeight, v)
    return (max - contentTop(l)).coerceAtLeast(0) / v.contentHeight.toFloat()
  }

  /**
   * L'ancora per il viewer (D12): i bordi delle celle nella finestra e il "porta in vista" per il ritorno. Tutto
   * aritmetica sul layout, niente `onGloballyPositioned` per cella.
   */
  val viewerAnchor: ViewerAnchor = object : ViewerAnchor {
    override fun boundsOf(id: MediaId): PixelRect? {
      val l = layout ?: return null
      val viewIndex = shownView.viewIndexOf(id)
      if (viewIndex < 0) return null
      val position = l.positionOfViewIndex(viewIndex)
      val row = l.rowOfItem(position)
      val v = viewport
      val top = GridGeometry.rowScreenTop(l, row, contentTop(l), v).toFloat()
      val bottom = top + l.rowHeight(row)
      if (bottom <= 0f || top >= v.height) return null
      val t = transform(l)
      val a = t.toScreen(l.itemLeft(position).toFloat(), top)
      val b = t.toScreen((l.itemLeft(position) + l.itemWidth(position)).toFloat(), bottom)
      val o = originInWindow
      return PixelRect(o.x + a.x, o.y + a.y, o.x + b.x, o.y + b.y)
    }

    override suspend fun reveal(id: MediaId) {
      val l = layout ?: return
      val viewIndex = shownView.viewIndexOf(id)
      if (viewIndex < 0) return
      val row = l.rowOfItem(l.positionOfViewIndex(viewIndex))
      val v = viewport
      val top = GridGeometry.rowScreenTop(l, row, contentTop(l), v)
      if (top >= v.padTop && top + l.rowHeight(row) <= v.height - v.padBottom) return
      // Fuori dall'area dei contenuti: al centro, poi si aspetta che la lista si sia misurata.
      userScrolled = true
      listState.scrollToItem(row, l.rowHeight(row) / 2 - v.contentHeight / 2)
      withFrameNanos { }
    }

    override fun thumbKey(id: MediaId): ThumbKey = this@MediaGridState.thumbKey(id)
  }

  /** Gli elementi del viewer: la stessa vista della griglia (stesso filtro), che ne segue i cambi (E-V6). */
  val viewerItems: ViewerItems = object : ViewerItems {
    override val view: StateFlow<TimelineView> = viewFlow.asStateFlow()

    override fun ref(id: MediaId): MediaRef = refOf(id)

    override fun refOrNull(id: MediaId): MediaRef? = try {
      refOf(id)
    } catch (e: NoSuchElementException) {
      null
    }
  }

  // ------------------------------------------------------------------------------------------
  // Selezione
  // ------------------------------------------------------------------------------------------

  /** Entra in modalita' selezione ("Seleziona"). */
  fun startSelecting() {
    selecting = true
  }

  /** Esce dalla modalita' selezione e svuota la selezione. */
  fun stopSelecting() {
    selection.clear()
    selecting = false
  }

  /** Seleziona o deseleziona una sezione intera (l'azione "Seleziona"/"Deseleziona" dell'intestazione). */
  internal fun toggleSection(first: Int, count: Int) {
    if (count <= 0) return
    val ids = (first until first + count).mapNotNullTo(LinkedHashSet()) { idAt(it) }
    if (sectionFullySelected(first, count)) selection.retain { it !in ids } else selection.selectAll(ids)
  }

  internal fun sectionFullySelected(first: Int, count: Int): Boolean {
    val selected = selection.selected.value
    if (selected.size < count) return false
    for (p in first until first + count) if (idAt(p)?.let { it in selected } != true) return false
    return true
  }

  internal fun updateVisibleIds(ids: List<MediaId>) {
    if (ids != visibleIdsFlow.value) visibleIdsFlow.value = ids
  }

  internal companion object {
    /** La chiave di versione quando chi usa la griglia non ne fornisce una (la miniatura non cambia mai chiave). */
    const val DefaultVersionKey: Long = 1L

    /** Quante celle recenti ricordano la propria posizione nell'indice (accessibilita', tocchi, viewer). */
    const val RecentCells: Int = 512

    private const val LoadFactor = 0.75f

    /** Un filo di tolleranza sul confronto delle misure delle celle (arrotondamenti dei pixel). */
    private const val LevelTolerance = 0.0005f
    private const val Half = 0.5f
    private const val NoId: String = "00000000-0000-0000-0000-000000000000"
  }
}

private fun defaultVersionKeyAt(snapshot: TimelineSnapshot, index: Int): Long =
  if (snapshot is ColumnarTimelineSnapshot) snapshot.versionKeyAt(index) else MediaGridState.DefaultVersionKey

/** La posizione nella vista del media scattato piu' vicino a [sortTime] (la vista e' in ordine di tempo decrescente). */
internal fun nearestByTime(view: TimelineView, sortTime: Long): Int {
  if (view.size == 0) return -1
  var lo = 0
  var hi = view.size - 1
  while (lo < hi) {
    val mid = (lo + hi) ushr 1
    if (view.snapshot.sortTimeAt(view.snapshotIndex(mid)) > sortTime) lo = mid + 1 else hi = mid
  }
  return lo
}

/** Solo per i test: conta le composizioni delle righe e delle celle (budget di ricomposizione, §2.6). */
internal interface GridProbe {
  fun rowComposed(row: Int)

  fun cellComposed(position: Int)
}
