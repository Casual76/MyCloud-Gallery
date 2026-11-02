package dev.pampa.mycloudgallery.feature.memories.page

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
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragSubject
import dev.pampa.mycloudgallery.core.designsystem.adaptive.mediaDragSource
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
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
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.feature.memories.R
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

// LA PARETE DI FOTO DI UN RICORDO (Fase 9, P9; docs/fase9-progetto.md §2.4, D13). La stessa cella della Libreria (`GalleryGridCell` del design
// system) in righe di una lista pigra, separate da una fessura sola (`GalleryWall`), come la pagina di un album o di una persona. Le foto vengono
// da `Memories.memory` (una `OrderedTimelineView` dell'istantanea **visibile**, poi filtrata dai Nascosti dell'ultimo momento): qui non se ne
// costruisce un'altra. Le miniature sono solo locali (un media senza sorgente locale mostra il segnaposto sfocato).

private const val DEFAULT_VERSION_KEY: Long = 1L

private val NoImage: State<ImageBitmap?> = mutableStateOf(null)

internal fun thumbRequestOf(thumbnails: MemoriesThumbnails, snapshot: TimelineSnapshot, index: Int, bucket: ThumbBucket = ThumbBucket.M): ThumbRequest? {
  val ref = thumbnails.refs.refAt(snapshot, index) ?: return null
  val version = if (snapshot is ColumnarTimelineSnapshot) snapshot.versionKeyAt(index) else DEFAULT_VERSION_KEY
  return ThumbRequest(ThumbKey(snapshot.idAt(index), version, bucket), ref)
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

/** Il giorno di una cella dell'istantanea (`aaaammgg`), o null se la cella non ha data. */
internal fun dayOf(yyyymmdd: Int): LocalDate? {
  if (yyyymmdd <= 0) return null
  return try {
    LocalDate.of(yyyymmdd / 10_000, yyyymmdd / 100 % 100, yyyymmdd % 100)
  } catch (_: DateTimeException) {
    null
  }
}

/** La frase che TalkBack legge per una cella: tipo e giorno dello scatto, mai il nome del file. */
internal fun cellDescription(resources: Resources, snapshot: TimelineSnapshot, index: Int): String {
  // Fase 10 (P7, D22): la frase la scrive il descrittore unico (tipo, data, ora, stato; persone e testo letto solo se :app ha legato le porte).
  return MediaDescribers.describeCell(MediaDescribers.shared(), snapshot, index, ZoneId.systemDefault()) ?: resources.getString(R.string.memorie_cell_undated)
}

/** Le azioni di una cella per il lettore di schermo: aprire la foto. */
internal class CellActions(
  private val resources: Resources,
  private val view: OrderedTimelineView,
  override val clickLabel: String,
  private val onTap: (MediaId) -> Unit,
) : GalleryGridCellActions {
  override val longClickLabel: String? = null

  override fun describe(key: Any): String {
    val media = key as MediaId
    val at = view.viewIndexOf(media)
    if (at < 0) return resources.getString(R.string.memorie_cell_undated)
    return cellDescription(resources, view.snapshot, view.snapshotIndex(at))
  }

  override fun click(key: Any) = onTap(key as MediaId)

  override fun longClick(key: Any) = Unit
}

/** Il numero di righe di una griglia di [size] celle in [columns] colonne. */
internal fun gridRowCount(size: Int, columns: Int): Int = if (size <= 0) 0 else (size + columns - 1) / columns

/** Le righe della griglia di [view] dentro una lista. Il tocco lo riconosce la cella (un `pointerInput` solo), il lettore di schermo ha le sue azioni da [actions]. */
internal fun LazyListScope.memoryGrid(
  keyPrefix: String,
  view: OrderedTimelineView,
  columns: Int,
  thumbnails: MemoriesThumbnails,
  style: GalleryGridCellStyle,
  interaction: GalleryGridInteractionState,
  actions: GalleryGridCellActions,
  onTap: (MediaId) -> Unit,
) {
  val rows = gridRowCount(view.size, columns)
  items(count = rows, key = { "$keyPrefix-$it" }) { row ->
    Row(horizontalArrangement = Arrangement.spacedBy(GalleryWall.Gap), modifier = Modifier.fillMaxWidth()) {
      for (column in 0 until columns) {
        val at = row * columns + column
        if (at < view.size) {
          MemoryCell(view, at, thumbnails, style, interaction, actions, onTap, Modifier.weight(1f).aspectRatio(1f))
        } else {
          Spacer(Modifier.weight(1f).aspectRatio(1f))
        }
      }
    }
  }
}

@Composable
private fun MemoryCell(
  view: OrderedTimelineView,
  viewIndex: Int,
  thumbnails: MemoriesThumbnails,
  style: GalleryGridCellStyle,
  interaction: GalleryGridInteractionState,
  actions: GalleryGridCellActions,
  onTap: (MediaId) -> Unit,
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
      .pointerInput(media) { detectTapGestures(onTap = { onTap(media) }) }
      // Fase 10 (P6, D20): la foto del ricordo verso un'altra app (pressione lunga col tocco, trascinamento col mouse).
      .mediaDragSource(enabled = true, origin = DragOrigin.MEMORY.code) { DragSubject(listOf(media.value), request?.let { thumbnails.loader.peek(it.key) }) },
    placeholder = placeholder,
  )
}

/** Le miniature da caricare sono quelle delle celle in vista (la finestra del caricatore) piu' qualcuna che sta per entrare. */
@Composable
internal fun FollowGridWindow(
  listState: LazyListState,
  thumbnails: MemoriesThumbnails,
  keyPrefix: String,
  view: OrderedTimelineView,
  columns: Int,
  /** Richieste fuori dalla griglia che devono restare in vista (la copertina in testa alla pagina). */
  extra: List<ThumbRequest> = emptyList(),
) {
  LaunchedEffect(listState, thumbnails, keyPrefix, view, columns, extra) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }.distinctUntilChanged().collect { keys ->
      val rows = keys.mapNotNull { key -> (key as? String)?.takeIf { it.startsWith("$keyPrefix-") }?.substringAfterLast('-')?.toIntOrNull() }
      if (rows.isEmpty()) {
        thumbnails.loader.window(extra, emptyList())
        return@collect
      }
      val last = rows.max()
      val visible = extra + rows.flatMap { requestsOfRow(view, columns, it, thumbnails) }
      val ahead = (last + 1..last + AHEAD_ROWS).flatMap { requestsOfRow(view, columns, it, thumbnails) }
      thumbnails.loader.window(visible, ahead)
    }
  }
}

private const val AHEAD_ROWS = 3

private fun requestsOfRow(view: OrderedTimelineView, columns: Int, row: Int, thumbnails: MemoriesThumbnails): List<ThumbRequest> {
  val from = row * columns
  if (from >= view.size) return emptyList()
  return (from until minOf(from + columns, view.size)).mapNotNull { at -> thumbRequestOf(thumbnails, view.snapshot, view.snapshotIndex(at)) }
}

/** Le azioni di cella della pagina, da un composable. */
@Composable
internal fun rememberCellActions(view: OrderedTimelineView, clickLabel: String, onTap: (MediaId) -> Unit): CellActions {
  val resources = LocalContext.current.resources
  return remember(view, clickLabel, onTap) { CellActions(resources, view, clickLabel, onTap) }
}
