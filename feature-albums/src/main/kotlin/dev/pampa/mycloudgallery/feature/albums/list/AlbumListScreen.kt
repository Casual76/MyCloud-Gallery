package dev.pampa.mycloudgallery.feature.albums.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumListState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPeople
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumCoverThumb
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText

// L'ELENCO DEGLI ALBUM (Fase 7, P10; docs/fase7-progetto.md §1.5 `AlbumListState`, D21, D33). In stile Foto: una griglia di album, ognuno con la
// sua copertina quadrata e **sotto** il nome e il conto (mai testo sopra la foto: si legge sempre). Prima i miei e i condivisi, poi gli smart.
// Il controllo dell'ordine sta nel menu in alto, "Nuovo album" e' un tasto proprio: due bersagli da 48 dp affiancati, mai sovrapposti.
// Solo componenti G2: `FluidScreen`, gruppi lista e bottoni dell'engine.

/** Cosa l'elenco chiede fuori da se': aprire un album, creare, riordinare, ritrovare un album lasciato. */
interface AlbumListHandlers {
  fun onOpen(album: Album)

  fun onCreate()

  fun onSort(sort: AlbumListSort)

  fun onMove(id: AlbumId, move: ListMove)

  fun onRejoin(id: AlbumId)

  fun onDismissNotice(notice: SharedAlbumNotice)

  /** "Apri la copia" dopo un avviso di chiusura con la copia (Q5 b). */
  fun onOpenCopy(id: AlbumId)
}

/** Nessuna azione: per le anteprime e gli screenshot. */
object NoAlbumListHandlers : AlbumListHandlers {
  override fun onOpen(album: Album) = Unit

  override fun onCreate() = Unit

  override fun onSort(sort: AlbumListSort) = Unit

  override fun onMove(id: AlbumId, move: ListMove) = Unit

  override fun onRejoin(id: AlbumId) = Unit

  override fun onDismissNotice(notice: SharedAlbumNotice) = Unit

  override fun onOpenCopy(id: AlbumId) = Unit
}

/** L'elenco per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. */
@Composable
fun AlbumListScreen(
  ui: AlbumListUi,
  thumbnails: AlbumThumbnails,
  people: AlbumPeople,
  handlers: AlbumListHandlers,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  listState: LazyListState = rememberLazyListState(),
) {
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = AlbumDimens.CardMin, spacing = AlbumDimens.BlockGap, maxColumns = MaxColumns)
  val ready = ui.state as? AlbumListState.Ready
  // Le copertine di tutti gli album in vista in una finestra sola: ogni richiesta fuori dalla finestra si annulla.
  val covers = remember(ready) { ready?.let { coverRequests(it, thumbnails) }.orEmpty() }
  LaunchedEffect(covers, thumbnails) { thumbnails.loader.window(covers, emptyList()) }
  FluidScreen(
    title = stringResource(R.string.albums_title),
    modifier = modifier,
    onBack = onBack,
    listState = listState,
    metrics = metrics,
    contentMaxWidth = AlbumDimens.PageWidth,
    actions = {
      GalleryBarButton(icon = Icons.Rounded.Add, contentDescription = stringResource(R.string.albums_new), onClick = handlers::onCreate)
      GalleryMenuButton(entries = sortEntries(ui.state, handlers), contentDescription = stringResource(R.string.albums_sort))
    },
  ) {
    when (val state = ui.state) {
      AlbumListState.Loading -> item(key = "loading") { Text(stringResource(R.string.albums_loading), color = MaterialTheme.colorScheme.onSurfaceVariant) }
      AlbumListState.Empty -> emptyState(handlers)
      is AlbumListState.Ready -> {
        notices(state, handlers)
        if (state.mineAndShared.isNotEmpty()) {
          item(key = "header-mine") { FluidSectionHeader(title = stringResource(R.string.albums_header_mine)) }
          albumCards("mine", state.mineAndShared, columns, thumbnails, people, ui.reorderable, handlers)
        }
        if (state.smart.isNotEmpty()) {
          item(key = "header-smart") { FluidSectionHeader(title = stringResource(R.string.albums_header_smart)) }
          albumCards("smart", state.smart, columns, thumbnails, people, ui.reorderable, handlers)
        }
        leftAlbums(ui.left, handlers)
      }
    }
  }
}

private const val MaxColumns = 6

/** Le richieste di miniatura delle copertine (solo quelle con una sorgente locale). */
private fun coverRequests(state: AlbumListState.Ready, thumbnails: AlbumThumbnails): List<ThumbRequest> =
  (state.mineAndShared + state.smart).mapNotNull { album -> album.cover.media?.let { thumbnails.refs.request(it) } }

private fun LazyListScope.emptyState(handlers: AlbumListHandlers) {
  item(key = "empty") {
    Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.BlockGap)) {
      FluidEmptyState(title = stringResource(R.string.albums_empty_title), detail = stringResource(R.string.albums_empty_detail))
      FluidButton(text = stringResource(R.string.albums_new), onClick = handlers::onCreate, fillWidth = true)
    }
  }
}

private fun LazyListScope.notices(state: AlbumListState.Ready, handlers: AlbumListHandlers) {
  items(state.notices, key = { notice -> "notice-" + notice.hashCode() }) { notice ->
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
      if (copy != null) {
        FluidButton(text = stringResource(R.string.albums_notice_open_copy), onClick = { handlers.onOpenCopy(copy) }, fillWidth = true)
      }
    }
  }
}

/** La griglia di album: [columns] schede per riga, ognuna con la copertina quadrata e sotto il nome e il conto. */
private fun LazyListScope.albumCards(
  key: String,
  albums: List<Album>,
  columns: Int,
  thumbnails: AlbumThumbnails,
  people: AlbumPeople,
  reorderable: Boolean,
  handlers: AlbumListHandlers,
) {
  val rows = albums.chunked(columns)
  items(rows.size, key = { "$key-row-$it" }) { rowIndex ->
    Row(horizontalArrangement = Arrangement.spacedBy(AlbumDimens.BlockGap), modifier = Modifier.fillMaxWidth()) {
      val row = rows[rowIndex]
      for (album in row) {
        val at = rowIndex * columns + row.indexOf(album)
        AlbumCard(album, thumbnails, people, positionOf(albums, at), reorderable, handlers, Modifier.weight(1f))
      }
      // L'ultima riga non allarga le schede: i posti vuoti restano vuoti.
      repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
  }
}

private fun positionOf(albums: List<Album>, at: Int): Pair<Int, Int> = (at + 1) to albums.size

@Composable
private fun AlbumCard(
  album: Album,
  thumbnails: AlbumThumbnails,
  people: AlbumPeople,
  position: Pair<Int, Int>,
  reorderable: Boolean,
  handlers: AlbumListHandlers,
  modifier: Modifier,
) {
  val resources = LocalContext.current.resources
  val shared = album as? Album.Shared
  val count = AlbumText.items(resources, album.count)
  val detail = when {
    shared != null && !shared.mine -> resources.getString(R.string.albums_row_by, count, people.nameOf(shared.creator))
    album is Album.Smart && album.suggested > 0 ->
      resources.getString(R.string.albums_row_suggested, count, resources.getQuantityString(R.plurals.albums_suggested, album.suggested, album.suggested))
    else -> count
  }
  val spoken = buildString {
    append(album.title).append(", ").append(detail)
    if (shared != null) append(", ").append(resources.getString(R.string.albums_badge_shared))
    if (album is Album.Smart) append(", ").append(resources.getString(R.string.albums_smart_section))
    if (reorderable) append(", ").append(resources.getString(R.string.albums_row_position, position.first, position.second))
  }
  val move = if (reorderable) listOf(ListMove.UP to R.string.albums_move_up, ListMove.DOWN to R.string.albums_move_down) else emptyList()
  val actions = move.map { (direction, label) ->
    CustomAccessibilityAction(resources.getString(label)) {
      handlers.onMove(album.id, direction)
      true
    }
  }
  Column(
    modifier = modifier
      .fluidPressable(onClick = { handlers.onOpen(album) }, role = Role.Button)
      .semantics(mergeDescendants = true) {
        contentDescription = spoken
        customActions = actions
      },
    verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap),
  ) {
    AlbumCoverThumb(
      media = album.cover.media,
      thumbnails = thumbnails,
      modifier = Modifier.fillMaxWidth().aspectRatio(1f),
      shape = ContinuousCornerShape(FluidRadius.Card),
      glyph = if (album is Album.Smart) Icons.Rounded.AutoAwesome else Icons.Rounded.PhotoAlbum,
    )
    Text(
      text = album.title,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (shared != null) {
      Row(horizontalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap), verticalAlignment = Alignment.CenterVertically) {
        FluidStatusBadge(label = stringResource(R.string.albums_badge_shared), tone = FluidTone.Info)
      }
    }
  }
}

private fun LazyListScope.leftAlbums(left: List<Album.Shared>, handlers: AlbumListHandlers) {
  if (left.isEmpty()) return
  item(key = "header-left") { FluidSectionHeader(title = stringResource(R.string.albums_header_left), detail = stringResource(R.string.albums_left_detail)) }
  item(key = "left") {
    FluidListGroup {
      left.forEachIndexed { index, album ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = album.title,
          subtitle = stringResource(R.string.albums_left_show_again),
          tone = FluidTone.Info,
          leading = { Icon(imageVector = Icons.Rounded.Group, contentDescription = null) },
          disclosure = false,
          onClick = { handlers.onRejoin(album.id) },
        )
      }
    }
  }
}

/** Il menu ⋮ dell'elenco: i quattro ordini, quello scelto con la spunta. */
@Composable
private fun sortEntries(state: AlbumListState, handlers: AlbumListHandlers): List<GalleryMenuEntry> {
  val current = (state as? AlbumListState.Ready)?.sort
  return listOf(
    sortEntry(R.string.albums_sort_recent, AlbumListSort.RECENT, current, handlers),
    sortEntry(R.string.albums_sort_name, AlbumListSort.NAME, current, handlers),
    sortEntry(R.string.albums_sort_media_date, AlbumListSort.MEDIA_DATE, current, handlers),
    sortEntry(R.string.albums_sort_custom, AlbumListSort.CUSTOM, current, handlers),
  )
}

@Composable
private fun sortEntry(label: Int, sort: AlbumListSort, current: AlbumListSort?, handlers: AlbumListHandlers): GalleryMenuEntry =
  GalleryMenuEntry.Item(label = stringResource(label), onClick = { handlers.onSort(sort) }, icon = if (sort == current) Icons.Rounded.Check else null)
