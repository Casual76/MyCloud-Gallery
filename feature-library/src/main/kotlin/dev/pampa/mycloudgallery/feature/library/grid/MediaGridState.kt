package dev.pampa.mycloudgallery.feature.library.grid

import dev.pampa.mycloudgallery.core.common.perf.AfterFirstDraw
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
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
 * La selezione di una griglia, come si salva per una ricreazione vera (densita', lingua, tema, processo morto in secondo piano: Fase 10, P4, D14, E-T8).
 * **Mai una selezione troncata in silenzio**: fino a [MAX_IDS] elementi si salvano gli id; oltre, o se sono selezionati **tutti** gli elementi della vista
 * (Ctrl+A, "Seleziona" su ogni giorno), si salva solo [all] e al ritorno si riseleziona la vista di allora con il suo filtro (che la Libreria ricorda a parte). Chi
 * agisce sulla selezione ripristinata agisce quindi su cio' che vede, mai su un pezzo di quello che aveva scelto.
 *
 * @property ids gli id selezionati (i `MediaId.value`), vuoto se [all] o se erano piu' di [MAX_IDS].
 * @property all erano selezionati tutti gli elementi della vista.
 */
@Immutable
data class SavedSelection(val ids: List<String>, val all: Boolean) {
  companion object {
    /** Fino a quanti id si salvano (D14: 500). Il `Bundle` dello stato salvato ha un tetto di circa 1 MB; 500 id da 36 caratteri sono meno di 40 KB. */
    const val MAX_IDS: Int = 500
  }
}

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

  /**
   * Il giorno di oggi come lo vede la griglia (FAB-1): da qui le intestazioni e l'etichetta in alto scelgono "Oggi" e "Ieri". E' uno stato, non
   * una lettura fatta una volta: con l'app rimasta viva dalla sera, la mattina "Oggi" sarebbe ancora ieri. Si aggiorna con [refreshDay] (la
   * finestra che torna in primo piano) e a ogni vista nuova; cambia una volta al giorno, quindi chi lo legge si ricompone una volta al giorno.
   */
  private var dayState: LocalDate by mutableStateOf(today())

  /** Il giorno di oggi per "Oggi" e "Ieri": letto da chi disegna le intestazioni, aggiornato da [refreshDay]. */
  val currentDay: LocalDate get() = dayState

  /** Rilegge l'orologio: se il giorno e' cambiato, "Oggi" e "Ieri" si spostano. Non scrive niente se il giorno e' lo stesso. */
  fun refreshDay() {
    val now = today()
    if (now != dayState) dayState = now
  }

  /** La vista che la griglia deve mostrare (con il suo filtro). Cambiarla rimette la griglia sullo stesso elemento. */
  var view: TimelineView
    get() = viewState
    set(value) {
      viewState = value
      viewFlow.value = value
      // Una vista nuova e' un buon momento per rileggere il giorno: costa una lettura dell'orologio.
      refreshDay()
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

  /** H3, giro 1 (CU-10): i giorni con foto della vista, per il calendario del salto a una data, tenuti per vista del catalogo. */
  internal val days = GridDaysCache()
  internal var viewport: GridViewport by mutableStateOf(GridViewport())

  /** Dove sta la griglia nella finestra (per i bordi del viewer). */
  internal var originInWindow: Offset = Offset.Zero

  /** L'altezza del piede (riepilogo e stato globale), per sapere dove finisce la lista. */
  internal var footerHeight: Int by mutableIntStateOf(0)

  /** L'origine della scala del pinch, in pixel della griglia. */
  internal var origin: Offset by mutableStateOf(Offset.Zero)

  /** Vero finche' due dita pizzicano. */
  internal var pinching: Boolean by mutableStateOf(false)

  /**
   * La griglia **si muove per un gesto** (P2, 10/11): due dita che pizzicano, la scala del pinch che torna a posto dopo il rilascio (il
   * livello puo' ancora cambiare), uno scorrimento o un lancio in corso, la maniglia dello scrubber trascinata. Lo legge l'etichetta di
   * vetro in alto, che mentre e' vero prende subito la larghezza del testo nuovo invece di seguirla con la molla
   * (`GalleryChromeLabel(contentMoving)`). Sono letture di stato: chi lo guarda da un `derivedStateOf` si ricompone solo quando la
   * risposta cambia (all'inizio e alla fine del gesto), non a ogni fotogramma.
   */
  internal val inMotion: Boolean
    get() = pinching || pinch.scale.value != 1f || listState.isScrollInProgress || scrubberState.dragging

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

  /**
   * Fase H (D10): quanto il tiro oltre il fondo ha spostato la lista in su (pixel, >= 0). Lo legge chi converte fra schermo e contenuto (l'hit
   * test, i bordi per il viewer): mentre l'aggiornamento lavora la lista resta spostata e un tocco deve aprire la cella che si vede. Lo imposta
   * [MediaGrid] se la griglia ha il gesto.
   */
  internal var pullOffset: () -> Float = { 0f }

  /** La cella sotto il punto [point] della griglia (schermo, trasformazione inclusa), o -1. */
  internal fun positionAt(point: Offset): Int {
    val l = layout ?: return -1
    val p = transform(l).toGrid(point.x, point.y + pullOffset())
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

  /**
   * Fase H (D3): il layout [next], fatto da [nextView], **aspetta la griglia ferma** prima di entrare. Vero solo per un cambio dei **dati** (una
   * vista nuova del catalogo allo stesso livello e alla stessa larghezza) mentre la lista scorre per il dito o per uno slancio.
   *
   * La causa del difetto del primo avvio (l'utente sull'S25, 11/11: «blocca lo scorrimento non appena lascio il dito»): ogni vista nuova entrava
   * con l'ancora di [onLayoutShown], cioe' con un `requestScrollToItem`, e in foundation 1.10.5 `requestScrollToItem` **ferma lo scorrimento in
   * corso** (`stopScroll()` se `isScrollInProgress`, verificato sul bytecode). Durante la prima lettura le viste arrivano a raffica e ogni slancio
   * moriva al primo arrivo. Ora mentre si scorre si continua a mostrare il layout di prima (con la sua vista: righe e celle coerenti fra loro), e
   * appena la lista si ferma il layout nuovo entra ancorato come sempre, senza niente da interrompere. Un pinch o un cambio di livello non
   * aspettano: sono il gesto stesso.
   *
   * La lettura di `isScrollInProgress` avviene solo quando un layout nuovo aspetta: la griglia si ricompone alla fine dello scorrimento solo in
   * quel caso.
   */
  internal fun holdsDataChange(next: GridLayout, nextView: TimelineView): Boolean {
    val shown = layout ?: return false
    if (shown.rowCount == 0 || next === shown || nextView === shownView) return false
    if (next.spec.level != shown.spec.level || next.spec.widthPx != shown.spec.widthPx) return false
    return listState.isScrollInProgress
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

  /**
   * Rimette una posizione salvata: al primo layout se la griglia e' nuova (il processo ripartito, l'avvio), subito se ce l'ha gia'.
   *
   * **La griglia viva che e' gia' li' non si tocca** (K6, GS-10). La griglia della Libreria sta fuori dalla pagina (nel ViewModel) e conserva
   * scorrimento, layout e misura quando la pagina esce dalla composizione: Cerca aperta, un'altra scheda, l'activity ricreata con il ViewModel
   * ancora vivo. Tornando, lo stato salvato della pagina le riporta la posizione che lei stessa ha adesso, e riapplicarla non e' neutro: con il
   * centro su un'intestazione l'ancora e' il bordo alto della prima foto del giorno ([GridAnchors.anchorAt], la semantica del pinch) e la si
   * rimetteva al centro, cioe' la griglia saltava della distanza fra il centro e quella foto (fino all'altezza dell'intestazione). Quindi se la
   * posizione di adesso, salvata, e' la stessa, non si fa niente. Se la griglia si e' mossa o e' nuova l'ancora serve e si applica; un cambio di
   * misura (rotazione, finestra) lo ancora [onLayoutShown] quando il layout nuovo entra, come quando la pagina e' in vista.
   */
  fun restorePosition(saved: SavedGridPosition?) {
    if (saved == null) return
    val l = layout
    if (l == null || l.rowCount == 0) {
      pendingRestore = saved
      return
    }
    if (savePosition() == saved) return
    val (row, offset) = restoreTarget(l, shownView, saved)
    listState.requestScrollToItem(row, offset)
  }

  /**
   * La selezione di adesso per salvarla (Fase 10, P4, D14, E-T8). Null se non si sta selezionando. Con la modalita' selezione accesa e nessun elemento scelto si salva
   * la sola modalita' (un elenco vuoto): l'utente torna dove era.
   */
  fun saveSelection(): SavedSelection? {
    if (!selecting) return null
    val ids = selection.selected.value.ids
    // La selezione e' sempre dentro la vista (`retain` toglie chi esce): se ha la stessa misura della vista, ci sono tutti.
    val all = ids.isNotEmpty() && ids.size == view.size
    if (all || ids.size > SavedSelection.MAX_IDS) return SavedSelection(emptyList(), all = all)
    return SavedSelection(ids.map { it.value }, all = false)
  }

  /** Ctrl+A (Fase 10, P4, D15): modalita' selezione con **tutta la vista** (con il suo filtro). La selezione resta dentro la vista: `GridEffects` toglie chi esce. */
  internal fun selectAllInView() {
    startSelecting()
    val v = view
    val all = LinkedHashSet<MediaId>(v.size)
    for (i in 0 until v.size) all += v.snapshot.idAt(v.snapshotIndex(i))
    selection.selectAll(all)
  }

  /**
   * Rimette una selezione salvata (dopo una ricreazione): accende la modalita' selezione e sceglie gli elementi che sono ancora nella vista. Va chiamata quando la vista
   * ha degli elementi (`GridEffects` toglie dalla selezione chi non e' nella vista, quindi con la vista ancora vuota non resterebbe niente).
   */
  fun restoreSelection(saved: SavedSelection) {
    startSelecting()
    val v = view
    val ids: Collection<MediaId> = if (saved.all) {
      (0 until v.size).mapTo(LinkedHashSet()) { v.snapshot.idAt(v.snapshotIndex(it)) }
    } else {
      saved.ids.mapNotNullTo(LinkedHashSet()) { raw -> runCatching { MediaId(raw) }.getOrNull()?.takeIf { v.viewIndexOf(it) >= 0 } }
    }
    selection.selectAll(ids)
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
      val lift = pullOffset()
      return PixelRect(o.x + a.x, o.y + a.y - lift, o.x + b.x, o.y + b.y - lift)
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

    // CU-1: la cella d'origine resta vuota mentre la foto del viewer ne esce o ci rientra (letta nel disegno della cella).
    override fun hide(id: MediaId?) {
      interaction.hiddenKey = id
    }
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

  /**
   * Avvio istantaneo (28/10): vero solo per la griglia della Libreria, che scrive nella traccia il **primo disegno utile**
   * (`AvvioTrace.LIBRARY_USEFUL`): la prima volta che tutte le celle in vista hanno la loro risposta (la miniatura in memoria, o un tentativo
   * finito senza: quella cella resta sul segnaposto e riprova da sola).
   */
  var reportsStartup: Boolean = false

  @Volatile
  private var visibleThumbKeys: List<ThumbKey> = emptyList()

  private var firstWindowSent = false

  /**
   * Avvio istantaneo: la **prima** finestra delle miniature parte nello stesso fotogramma in cui la griglia compare, appena la lista e' misurata
   * (`onPlaced`), e non dopo: l'effetto che la manda di solito gira solo quando il thread principale e' libero, cioe' dopo che il primo
   * fotogramma (quello che sull'S25 compila la grafica, 250-300 ms) e' finito. Cosi' le miniature si decodificano mentre il primo fotogramma si
   * disegna. Una volta sola; poi la finestra la segue l'effetto di sempre (la stessa richiesta non riparte).
   */
  internal fun sendFirstWindow() {
    if (firstWindowSent) return
    val l = layout ?: return
    if (l.rowCount == 0 || viewport.height <= 0) return
    firstWindowSent = true
    val plan = GridThumbWindow(this).plan(GridThumbWindow.rows(this, l, shownView, 1))
    loader.window(plan.visible, plan.ahead)
    updateVisibleIds(plan.visibleIds)
    updateVisibleThumbs(plan.visible.map { it.key })
  }

  /** Le chiavi delle miniature in vista (dalla finestra del caricatore): servono al segno del primo disegno utile. */
  internal fun updateVisibleThumbs(keys: List<ThumbKey>) {
    visibleThumbKeys = keys
    noteUsefulIfReady()
  }

  /** Vero finche' questa griglia aspetta il primo disegno utile del processo (dopo, niente si segue piu': nessun costo per fotogramma). */
  internal val watchingUseful: Boolean get() = reportsStartup && !AvvioTrace.marked(AvvioTrace.LIBRARY_USEFUL)

  /** Scrive il segno del primo disegno utile, una volta per processo, quando ogni cella in vista ha la sua risposta. */
  internal fun noteUsefulIfReady() {
    if (!watchingUseful) return
    val keys = visibleThumbKeys
    if (keys.isEmpty()) return
    if (keys.all { loader.settled(it) }) {
      AvvioTrace.markOnce(AvvioTrace.LIBRARY_USEFUL)
      // Cio' che aspettava il primo disegno utile (NAS, lavori, ricerca, lettura del telefono) parte adesso.
      AfterFirstDraw.open()
    }
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
