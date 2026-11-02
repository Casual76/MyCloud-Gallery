package dev.pampa.mycloudgallery.feature.albums.page

import java.time.ZoneId
import dev.pampa.mycloudgallery.core.describe.rules.MediaDescribers
import android.content.res.Resources
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragPointer
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragSubject
import dev.pampa.mycloudgallery.core.designsystem.adaptive.mediaDragSource
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCell
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellActions
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellBadges
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryPresence
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

// LA GRIGLIA DI UN ALBUM (Fase 7, P10; docs/fase7-progetto.md D21, D22). La stessa cella della Libreria (`GalleryGridCell` del design system) in
// righe di una lista pigra: righe di [columns] celle quadrate separate da una fessura sola (la "parete di foto", `GalleryWall`). Le miniature sono
// **solo locali** (come Cerca): un media senza sorgente locale mostra il segnaposto sfocato. La vista viene da `Albums.content`, che non ha mai
// un nascosto; qui non se ne costruisce un'altra.

private const val DEFAULT_VERSION_KEY: Long = 1L

private val NoImage: State<ImageBitmap?> = mutableStateOf(null)

/** La richiesta di miniatura della cella [index], o null se il media non ha una sorgente locale. */
internal fun thumbRequestOf(thumbnails: AlbumThumbnails, snapshot: TimelineSnapshot, index: Int): ThumbRequest? {
  val ref = thumbnails.refs.refAt(snapshot, index) ?: return null
  val version = if (snapshot is ColumnarTimelineSnapshot) snapshot.versionKeyAt(index) else DEFAULT_VERSION_KEY
  return ThumbRequest(ThumbKey(snapshot.idAt(index), version, ThumbBucket.M), ref)
}

/** I segni sulla cella, come in Libreria: preferito, modificata, durata, illeggibile e dove sta. */
internal fun badgesOf(snapshot: TimelineSnapshot, index: Int): GalleryGridCellBadges {
  val flags = snapshot.flagsAt(index)
  val video = snapshot.kindAt(index) == MediaKind.VIDEO
  return GalleryGridCellBadges(
    favorite = CellFlags.has(flags, CellFlags.FAVORITE),
    edited = CellFlags.has(flags, CellFlags.EDITED),
    duration = if (video) duration(snapshot.durationMsAt(index)) else null,
    unreadable = CellFlags.has(flags, CellFlags.UNREADABLE),
    presence = snapshot.presenceAt(index)?.let(::presenceOf),
  )
}

private fun presenceOf(badge: PresenceBadge): GalleryPresence = when (badge) {
  PresenceBadge.PHONE_ONLY -> GalleryPresence.PhoneOnly
  PresenceBadge.NAS_ONLY -> GalleryPresence.NasOnly
  PresenceBadge.BOTH -> GalleryPresence.Both
  PresenceBadge.SAFE -> GalleryPresence.Safe
  PresenceBadge.TO_VERIFY -> GalleryPresence.ToVerify
  PresenceBadge.PROBLEM -> GalleryPresence.Problem
}

/** `m:ss` (o `h:mm:ss`) come nella Libreria. */
internal fun duration(ms: Long): String {
  val total = (ms / 1_000L).coerceAtLeast(0)
  val h = total / 3_600L
  val m = total % 3_600L / 60L
  val s = total % 60L
  return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** La frase che TalkBack legge per una cella: tipo e giorno dello scatto, mai il nome del file; "copertina" se e' la copertina. */
internal fun cellDescription(resources: Resources, snapshot: TimelineSnapshot, index: Int, cover: Boolean): String {
  // Fase 10 (P7, D22): la frase la scrive il descrittore unico (tipo, data, ora, stato; persone e testo letto solo se :app ha legato le porte).
  val base = MediaDescribers.describeCell(MediaDescribers.shared(), snapshot, index, ZoneId.systemDefault()) ?: resources.getString(R.string.album_cell_undated)
  return if (cover) resources.getString(R.string.album_cell_cover_suffix, base) else base
}

/** Le azioni di una cella per il lettore di schermo: aprire (o scegliere, o selezionare) e, con la pressione lunga, il menu dell'elemento. */
internal class CellActions(
  private val resources: Resources,
  private val view: OrderedTimelineView,
  private val coverMedia: MediaId?,
  override val clickLabel: String,
  override val longClickLabel: String?,
  private val onTap: (MediaId) -> Unit,
  private val onLong: (MediaId) -> Unit,
) : GalleryGridCellActions {
  override fun describe(key: Any): String {
    val media = key as MediaId
    val at = view.viewIndexOf(media)
    if (at < 0) return resources.getString(R.string.album_cell_undated)
    return cellDescription(resources, view.snapshot, view.snapshotIndex(at), cover = media == coverMedia)
  }

  override fun click(key: Any) = onTap(key as MediaId)

  override fun longClick(key: Any) {
    if (longClickLabel != null) onLong(key as MediaId)
  }
}

/** Il numero di righe di una griglia di [size] celle in [columns] colonne. */
internal fun gridRowCount(size: Int, columns: Int): Int = if (size <= 0) 0 else (size + columns - 1) / columns

/**
 * Le righe della griglia di [view] dentro una lista. [keyPrefix] distingue piu' griglie nella stessa lista (le due viste di una pagina).
 * Il tocco e la pressione lunga li riconosce la cella (un `pointerInput` solo), il lettore di schermo ha le sue azioni da [actions].
 */
internal fun LazyListScope.albumGrid(
  keyPrefix: String,
  view: OrderedTimelineView,
  columns: Int,
  thumbnails: AlbumThumbnails,
  style: GalleryGridCellStyle,
  interaction: GalleryGridInteractionState,
  actions: GalleryGridCellActions,
  onTap: (MediaId) -> Unit,
  onLong: ((MediaId) -> Unit)?,
  /** Fase 10 (P6, D20): da dove parte un trascinamento verso un'altra app (la pagina dell'album); null = questa griglia non trascina (le suggerite). */
  dragOrigin: DragOrigin? = null,
) {
  val rows = gridRowCount(view.size, columns)
  items(rows, key = { "$keyPrefix-$it" }) { row ->
    Row(horizontalArrangement = Arrangement.spacedBy(GalleryWall.Gap), modifier = Modifier.fillMaxWidth()) {
      for (column in 0 until columns) {
        val at = row * columns + column
        if (at < view.size) {
          AlbumCell(view, at, thumbnails, style, interaction, actions, onTap, onLong, dragOrigin, Modifier.weight(1f).aspectRatio(1f))
        } else {
          Spacer(Modifier.weight(1f).aspectRatio(1f))
        }
      }
    }
  }
}

private fun LazyListScope.items(count: Int, key: (Int) -> Any, content: @Composable (Int) -> Unit) {
  items(count = count, key = key) { index -> content(index) }
}

@Composable
private fun AlbumCell(
  view: OrderedTimelineView,
  viewIndex: Int,
  thumbnails: AlbumThumbnails,
  style: GalleryGridCellStyle,
  interaction: GalleryGridInteractionState,
  actions: GalleryGridCellActions,
  onTap: (MediaId) -> Unit,
  onLong: ((MediaId) -> Unit)?,
  dragOrigin: DragOrigin?,
  modifier: Modifier,
) {
  val snapshot = view.snapshot
  val index = view.snapshotIndex(viewIndex)
  val media = remember(snapshot, index) { snapshot.idAt(index) }
  val request = remember(snapshot, index) { thumbRequestOf(thumbnails, snapshot, index) }
  val image = if (request != null) thumbnails.loader.image(request.key, request.ref) else NoImage
  val placeholder = remember(snapshot, index) { snapshot.thumbHashAt(index)?.let { thumbnails.placeholders.image(it) } }
  GalleryGridCell(
    key = media,
    image = image,
    badges = badgesOf(snapshot, index),
    style = style,
    interaction = interaction,
    actions = actions,
    modifier = modifier
      .pointerInput(media, onLong != null) {
        detectTapGestures(onTap = { onTap(media) }, onLongPress = if (onLong != null) ({ onLong(media) }) else null)
      }
      // Fase 10 (P6, D20): la foto dell'album verso un'altra app. La pressione lunga della cella e' il suo menu: col tocco si trascina solo se non c'e' (scelta della copertina); il mouse trascina sempre.
      .mediaDragSource(enabled = dragOrigin != null, origin = dragOrigin?.code.orEmpty()) { touch ->
        if (touch.pointer == DragPointer.MOUSE || onLong == null) DragSubject(listOf(media.value), request?.let { thumbnails.loader.peek(it.key) }) else null
      },
    placeholder = placeholder,
  )
}

/**
 * Le miniature da caricare sono quelle delle celle in vista (la finestra del caricatore) piu' qualcuna che sta per entrare. [grids] sono le
 * griglie della pagina con il prefisso delle loro chiavi: una pagina con piu' viste (le sue foto, le suggerite) ha una finestra sola.
 */
@Composable
internal fun FollowGridWindow(
  listState: LazyListState,
  thumbnails: AlbumThumbnails,
  grids: List<GridWindowSource>,
) {
  LaunchedEffect(listState, thumbnails, grids) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }.distinctUntilChanged().collect { keys ->
      val visible = ArrayList<ThumbRequest>()
      val ahead = ArrayList<ThumbRequest>()
      for (source in grids) {
        val rows = keys.mapNotNull { key -> (key as? String)?.takeIf { it.startsWith(source.keyPrefix + "-") }?.substringAfterLast('-')?.toIntOrNull() }
        if (rows.isEmpty()) continue
        val last = rows.max()
        for (row in rows) visible += requestsOfRow(source, row, thumbnails)
        for (row in last + 1..last + AHEAD_ROWS) ahead += requestsOfRow(source, row, thumbnails)
      }
      thumbnails.loader.window(visible, ahead)
    }
  }
}

/** Una griglia che la pagina mostra: il prefisso delle chiavi delle sue righe, la sua vista e le sue colonne. */
internal class GridWindowSource(val keyPrefix: String, val view: OrderedTimelineView, val columns: Int)

private const val AHEAD_ROWS = 3

private fun requestsOfRow(source: GridWindowSource, row: Int, thumbnails: AlbumThumbnails): List<ThumbRequest> {
  val from = row * source.columns
  if (from >= source.view.size) return emptyList()
  return (from until minOf(from + source.columns, source.view.size)).mapNotNull { at ->
    thumbRequestOf(thumbnails, source.view.snapshot, source.view.snapshotIndex(at))
  }
}

/** Le risorse della pagina per le azioni di cella, da un composable. */
@Composable
internal fun rememberCellActions(
  view: OrderedTimelineView,
  coverMedia: MediaId?,
  clickLabel: String,
  longClickLabel: String?,
  onTap: (MediaId) -> Unit,
  onLong: (MediaId) -> Unit,
): CellActions {
  val resources = LocalContext.current.resources
  return remember(view, coverMedia, clickLabel, longClickLabel, onTap, onLong) {
    CellActions(resources, view, coverMedia, clickLabel, longClickLabel, onTap, onLong)
  }
}
