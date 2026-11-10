package dev.pampa.mycloudgallery.feature.albums.tab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantBlock
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.KeepInstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.rememberInstantShape
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbum
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumCoverThumb
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.pampa.mycloudgallery.core.designsystem.R as DesignR

// LA SCHEDA ALBUM (fase grafica, G2b), in stile Galleria Samsung: una griglia di copertine, tutte con la stessa forma (la foto quadrata con l'angolo
// continuo, sotto il nome e il numero), prima Preferiti, Fotocamera e Video, poi il resto per ultima apertura. Un album smart ha il segno dell'IA sulla
// copertina, uno condiviso il segno del gruppo: una pillola di scrim come i badge delle celle della Libreria, mai vetro (sopra una griglia che scorre
// sarebbe uno strato per copertina). La barra e' la sua (il titolo grande della scheda, come Raccolte): "Nuovo album" e il menu con "Tutti gli album",
// dove restano l'ordine e il riordino di sempre. Lo spazio della pillola delle schede sta in fondo ([contentPadding]); il fondale va a chi la disegna.

/** Cosa la scheda chiede fuori da se'. */
interface AlbumsTabHandlers {
  /** Apre una voce: un album nella sua pagina, una voce speciale nella sua griglia. */
  fun onOpen(entry: AlbumsTabEntry)

  /** "Nuovo album": il foglio di creazione (a mano o smart). */
  fun onCreate()

  /** "Tutti gli album": l'elenco di sempre, con l'ordine, il riordino e gli album che non si mostrano piu'. */
  fun onOpenAllAlbums()

  fun onDismissNotice(notice: SharedAlbumNotice)

  /** "Apri la copia" dopo l'avviso di un album chiuso con la copia (Q5 b). */
  fun onOpenCopy(id: AlbumId)
}

/** Nessuna azione: per gli screenshot e le anteprime. */
object NoAlbumsTabHandlers : AlbumsTabHandlers {
  override fun onOpen(entry: AlbumsTabEntry) = Unit

  override fun onCreate() = Unit

  override fun onOpenAllAlbums() = Unit

  override fun onDismissNotice(notice: SharedAlbumNotice) = Unit

  override fun onOpenCopy(id: AlbumId) = Unit
}

/** Il codice della forma dello scheletro della scheda (Avvio istantaneo): quante copertine di album c'erano l'ultima volta. */
const val ALBUMS_TAB_SHAPE: String = "albums-tab"

/** Le copertine dello scheletro di una scheda mai vista con gli album letti: una riga e mezza. */
private const val DEFAULT_SKELETON_TILES = 4

/**
 * La finestra delle copertine segue lo scorrimento (FAB-10: prima era fissa sulle prime 48 voci e le altre non si caricavano mai): le righe in vista sono le miniature
 * "visibili", e queste righe prima e dopo sono quelle "in arrivo" (le prime a essere chieste, nella direzione dello scorrimento).
 */
private const val COVER_ROWS_BEHIND = 1
private const val COVER_ROWS_AHEAD = 2

/**
 * La scheda Album per uno [state] dato. Senza effetti: la usano la rotta, i test e gli screenshot.
 *
 * @param contentPadding lo spazio che la pillola delle schede occupa in basso (il contenuto scorre sotto).
 * @param backdropOut consegna a chi disegna la pillola il fondale di questa pagina, che la pillola rifrange.
 */
@Composable
fun AlbumsTabScreen(
  state: AlbumsTabState,
  thumbnails: AlbumThumbnails,
  handlers: AlbumsTabHandlers,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: (GlassBackdropState) -> Unit = {},
  listState: LazyListState = rememberLazyListState(),
) {
  val ready = state as AlbumsTabState.Ready
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = AlbumDimens.TabCardMin, spacing = AlbumDimens.TabGap, maxColumns = AlbumDimens.TabMaxColumns)
  // Avvio istantaneo: finche' gli album non sono letti, al loro posto tante copertine ferme quante ce n'erano l'ultima volta.
  val remembered = rememberInstantShape(ALBUMS_TAB_SHAPE, InstantShape.of(InstantBlock.Tiles(DEFAULT_SKELETON_TILES, columns)))
  if (!ready.albumsPending && ready.albumCount > 0) {
    KeepInstantShape(ALBUMS_TAB_SHAPE, InstantShape.of(InstantBlock.Tiles(ready.albumCount, columns)))
  }
  val tiles = remember(ready, remembered) { tilesOf(ready, skeletonCount(remembered)) }
  // Le copertine in una finestra sola che segue le righe in vista (come la griglia della Libreria): ogni richiesta fuori dalla finestra si annulla.
  FollowCoverWindow(listState, tiles, columns, thumbnails)
  FluidScreen(
    title = stringResource(R.string.albums_title),
    modifier = modifier,
    listState = listState,
    metrics = metrics,
    contentMaxWidth = AlbumDimens.PageWidth,
    extraBottomPadding = contentPadding.calculateBottomPadding(),
    actions = {
      GalleryBarButton(icon = Icons.Rounded.Add, contentDescription = stringResource(R.string.albums_new), onClick = handlers::onCreate)
      GalleryMenuButton(
        entries = listOf(GalleryMenuEntry.Item(label = stringResource(R.string.albums_all), onClick = handlers::onOpenAllAlbums, icon = Icons.Rounded.PhotoAlbum)),
        contentDescription = stringResource(R.string.albums_tab_more),
      )
    },
    // La pillola non e' qui dentro (e' una sola per tutte le schede): questa pagina le consegna solo il suo fondale.
    overlay = { pageBackdrop -> SideEffect { backdropOut(pageBackdrop) } },
  ) {
    notices(ready.notices, handlers)
    tileRows(tiles, columns, thumbnails, handlers)
    if (!ready.albumsPending && ready.albumCount == 0) noAlbumsYet(handlers)
  }
}

/** Una cella della griglia: una voce vera o una copertina ferma dello scheletro. */
@Immutable
internal sealed interface TabTile {
  data class Entry(val entry: AlbumsTabEntry) : TabTile

  data class Skeleton(val index: Int) : TabTile
}

private fun skeletonCount(shape: InstantShape): Int =
  (shape.blocks.firstOrNull() as? InstantBlock.Tiles)?.count?.coerceIn(1, InstantShape.MAX_ITEMS) ?: DEFAULT_SKELETON_TILES

private fun tilesOf(state: AlbumsTabState.Ready, skeleton: Int): List<TabTile> {
  val out = ArrayList<TabTile>(state.entries.size + skeleton)
  state.entries.mapTo(out) { TabTile.Entry(it) }
  if (state.albumsPending) repeat(skeleton) { out += TabTile.Skeleton(it) }
  return out
}

/**
 * FAB-10: la finestra del caricatore segue le **righe in vista** (piu' un margine prima e dopo), non una finestra fissa sulle prime voci. Si aggiorna **solo quando cambia l'insieme
 * delle righe in vista** (`snapshotFlow` + `distinctUntilChanged`: mai una richiesta per fotogramma) o quando cambiano le voci o le colonne (l'effetto riparte e rilegge le righe in
 * vista). Le righe sono quelle di [tileRows]: la chiave `row-N` dice quale. Prima di aver misurato niente non c'e' nessuna riga in vista e non si chiede niente.
 */
@Composable
private fun FollowCoverWindow(listState: LazyListState, tiles: List<TabTile>, columns: Int, thumbnails: AlbumThumbnails) {
  LaunchedEffect(listState, tiles, columns, thumbnails) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { rowOfKey(it.key) } }
      .distinctUntilChanged()
      .collect { rows ->
        if (rows.isEmpty()) return@collect
        val window = coverWindow(rows, tiles, columns, thumbnails)
        thumbnails.loader.window(window.visible, window.ahead)
      }
  }
}

/** Le miniature delle copertine da tenere: quelle delle righe in vista e quelle delle righe vicine, in arrivo. */
internal class CoverWindow(val visible: List<ThumbRequest>, val ahead: List<ThumbRequest>)

/** Funzione pura: dalle [visibleRows] alle richieste, con [COVER_ROWS_BEHIND] righe prima e [COVER_ROWS_AHEAD] dopo come "in arrivo". */
internal fun coverWindow(visibleRows: List<Int>, tiles: List<TabTile>, columns: Int, thumbnails: AlbumThumbnails): CoverWindow {
  val first = visibleRows.min()
  val last = visibleRows.max()
  val visible = visibleRows.flatMap { row -> coverRequestsOfRow(row, tiles, columns, thumbnails) }
  val ahead = ((first - COVER_ROWS_BEHIND until first) + (last + 1..last + COVER_ROWS_AHEAD)).filter { it >= 0 }.flatMap { row -> coverRequestsOfRow(row, tiles, columns, thumbnails) }
  return CoverWindow(visible, ahead)
}

private fun coverRequestsOfRow(row: Int, tiles: List<TabTile>, columns: Int, thumbnails: AlbumThumbnails): List<ThumbRequest> {
  val from = row * columns
  if (from >= tiles.size) return emptyList()
  return tiles.subList(from, minOf(from + columns, tiles.size)).mapNotNull { tile ->
    (tile as? TabTile.Entry)?.entry?.cover?.let { thumbnails.refs.request(it) }
  }
}

private const val ROW_KEY_PREFIX = "row-"

private fun rowKey(row: Int): String = ROW_KEY_PREFIX + row

private fun rowOfKey(key: Any): Int? = (key as? String)?.takeIf { it.startsWith(ROW_KEY_PREFIX) }?.substring(ROW_KEY_PREFIX.length)?.toIntOrNull()

private fun LazyListScope.notices(notices: List<SharedAlbumNotice>, handlers: AlbumsTabHandlers) {
  items(notices, key = { notice -> "notice-" + notice.hashCode() }) { notice ->
    val closed = notice as? SharedAlbumNotice.Closed
    val message = when {
      closed == null -> notice.title
      closed.keptCopy != null -> stringResource(R.string.albums_notice_closed_copy, closed.title)
      else -> stringResource(R.string.albums_notice_closed, closed.title)
    }
    Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
      FluidInlineMessage(
        message = message,
        title = stringResource(R.string.albums_notice_title),
        tone = FluidTone.Info,
        onDismiss = { handlers.onDismissNotice(notice) },
      )
      val copy = closed?.keptCopy
      if (copy != null) FluidButton(text = stringResource(R.string.albums_notice_open_copy), onClick = { handlers.onOpenCopy(copy) }, fillWidth = true)
    }
  }
}

/** Le righe di copertine: [columns] per riga; l'ultima non allarga le copertine (i posti vuoti restano vuoti). */
private fun LazyListScope.tileRows(tiles: List<TabTile>, columns: Int, thumbnails: AlbumThumbnails, handlers: AlbumsTabHandlers) {
  val rows = tiles.chunked(columns)
  items(rows.size, key = { rowKey(it) }, contentType = { "albums-row" }) { rowIndex ->
    Row(horizontalArrangement = Arrangement.spacedBy(AlbumDimens.TabGap), modifier = Modifier.fillMaxWidth()) {
      val row = rows[rowIndex]
      for (tile in row) {
        when (tile) {
          is TabTile.Entry -> AlbumsTabTile(tile.entry, thumbnails, onClick = { handlers.onOpen(tile.entry) }, modifier = Modifier.weight(1f))
          is TabTile.Skeleton -> SkeletonTile(first = tile.index == 0, modifier = Modifier.weight(1f))
        }
      }
      repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
  }
}

/** Una voce: la copertina quadrata con i suoi segni, sotto il nome e il numero. Un bottone solo per TalkBack, che dice tutto in una frase. */
@Composable
private fun AlbumsTabTile(entry: AlbumsTabEntry, thumbnails: AlbumThumbnails, onClick: () -> Unit, modifier: Modifier) {
  val resources = LocalContext.current.resources
  val title = entryTitle(entry)
  val emptyFavorites = entry is AlbumsTabEntry.Special && entry.kind == SpecialAlbum.FAVORITES && entry.count == 0
  val caption = if (emptyFavorites) stringResource(R.string.albums_tab_favorites_empty) else GalleryNumbers.format(entry.count)
  val marks = marksOf(entry)
  val smartSpoken = stringResource(R.string.albums_tab_smart_spoken)
  val sharedSpoken = stringResource(R.string.albums_tab_shared_spoken)
  val spoken = buildString {
    append(title).append(", ")
    append(if (emptyFavorites) caption else AlbumText.items(resources, entry.count))
    if (CoverMark.SMART in marks) append(", ").append(smartSpoken)
    if (CoverMark.SHARED in marks) append(", ").append(sharedSpoken)
  }
  Column(
    modifier = modifier
      .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
      .fluidPressable(onClick = onClick, role = Role.Button)
      .semantics(mergeDescendants = true) { contentDescription = spoken },
  ) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
      AlbumCoverThumb(
        media = entry.cover,
        thumbnails = thumbnails,
        modifier = Modifier.matchParentSize(),
        shape = ContinuousCornerShape(FluidRadius.Card),
        glyph = glyphOf(entry),
      )
      if (marks.isNotEmpty()) CoverMarks(marks, Modifier.align(Alignment.TopEnd).padding(GalleryDimens.BadgeInset))
    }
    Spacer(Modifier.height(AlbumDimens.TabCoverGap))
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(AlbumDimens.TabCaptionGap))
    Text(
      text = caption,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** I segni sulla copertina: l'IA per uno smart (anche condiviso dal suo creatore), il gruppo per un condiviso. */
private enum class CoverMark(val glyph: ImageVector) {
  SMART(Icons.Rounded.AutoAwesome),
  SHARED(Icons.Rounded.Group),
}

private fun marksOf(entry: AlbumsTabEntry): List<CoverMark> {
  val album = (entry as? AlbumsTabEntry.Item)?.album ?: return emptyList()
  return buildList {
    if (album.kind == AlbumKind.SMART) add(CoverMark.SMART)
    if (album is Album.Shared) add(CoverMark.SHARED)
  }
}

/**
 * La pillola dei segni: la stessa dei badge delle celle (`GalleryBadgeCluster`: scrim al 60% e glifo bianco, leggibile su qualunque foto), con le
 * misure di `GalleryDimens`. Niente da leggere: la frase della voce lo dice gia'.
 */
@Composable
private fun CoverMarks(marks: List<CoverMark>, modifier: Modifier) {
  Row(
    modifier = modifier
      .height(GalleryDimens.BadgeHeight)
      .defaultMinSize(minWidth = GalleryDimens.BadgeHeight)
      .background(GalleryOnPhoto.scrim(), FluidCapsuleShape)
      .padding(horizontal = GalleryDimens.BadgeInset),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(AlbumDimens.TabCaptionGap, Alignment.CenterHorizontally),
  ) {
    marks.forEach { mark ->
      Icon(imageVector = mark.glyph, contentDescription = null, modifier = Modifier.size(GalleryDimens.BadgeGlyph), tint = GalleryOnPhoto.Content)
    }
  }
}

/** Una copertina ferma dello scheletro: il quadrato e una riga, con le misure della voce vera. TalkBack sente una frase sola, sulla prima. */
@Composable
private fun SkeletonTile(first: Boolean, modifier: Modifier) {
  val description = stringResource(DesignR.string.instant_skeleton_description)
  val fill = MaterialTheme.colorScheme.surfaceContainerHighest
  Column(modifier = modifier.clearAndSetSemantics { if (first) contentDescription = description }) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(fill, ContinuousCornerShape(FluidRadius.Card)))
    Spacer(Modifier.height(AlbumDimens.TabCoverGap))
    Box(Modifier.fillMaxWidth(SKELETON_LINE_FRACTION).height(AlbumDimens.TabSkeletonLine).background(fill, ContinuousCornerShape(FluidRadius.Small)))
  }
}

private const val SKELETON_LINE_FRACTION = 0.6f

/** Nessun album ancora: sotto le voci speciali, l'invito a crearne uno. */
private fun LazyListScope.noAlbumsYet(handlers: AlbumsTabHandlers) {
  item(key = "no-albums") {
    Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.BlockGap), modifier = Modifier.padding(top = AlbumDimens.BlockGap)) {
      FluidEmptyState(title = stringResource(R.string.albums_tab_none_title), detail = stringResource(R.string.albums_empty_detail))
      FluidButton(text = stringResource(R.string.albums_new), onClick = handlers::onCreate, fillWidth = true)
    }
  }
}

@Composable
private fun entryTitle(entry: AlbumsTabEntry): String = when (entry) {
  is AlbumsTabEntry.Item -> entry.album.title
  is AlbumsTabEntry.Special -> stringResource(
    when (entry.kind) {
      SpecialAlbum.FAVORITES -> R.string.albums_tab_favorites
      SpecialAlbum.CAMERA -> R.string.albums_tab_camera
      SpecialAlbum.VIDEOS -> R.string.albums_tab_videos
      SpecialAlbum.SCREENSHOTS -> R.string.albums_tab_screenshots
      SpecialAlbum.MESSAGING -> R.string.albums_tab_messaging
    },
  )
}

/** Il glifo di una copertina senza foto (vuota, o finche' la miniatura non c'e'). */
private fun glyphOf(entry: AlbumsTabEntry): ImageVector = when (entry) {
  is AlbumsTabEntry.Special -> when (entry.kind) {
    SpecialAlbum.FAVORITES -> Icons.Rounded.Favorite
    SpecialAlbum.CAMERA -> Icons.Rounded.PhotoCamera
    SpecialAlbum.VIDEOS -> Icons.Rounded.Videocam
    SpecialAlbum.SCREENSHOTS -> Icons.Rounded.Screenshot
    SpecialAlbum.MESSAGING -> Icons.AutoMirrored.Rounded.Chat
  }
  is AlbumsTabEntry.Item -> if (entry.album.kind == AlbumKind.SMART) Icons.Rounded.AutoAwesome else Icons.Rounded.PhotoAlbum
}
