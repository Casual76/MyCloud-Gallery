package dev.pampa.mycloudgallery.feature.albums.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DropTarget
import dev.pampa.mycloudgallery.core.designsystem.adaptive.mediaDropTarget
import dev.pampa.mycloudgallery.core.designsystem.adaptive.rememberNewWindowEntry
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantBlock
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.instantSkeleton
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumAction
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumUnavailableReason
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText
import dev.pampa.mycloudgallery.feature.albums.ui.SectionNote
import dev.pampa.mycloudgallery.feature.albums.ui.StripRow

// LA PAGINA DI UN ALBUM (Fase 7, P10; docs/fase7-progetto.md §1.5 `AlbumPageState`, D9, D21, D22, D39). In stile Foto: il titolo grande, sotto una riga
// di fatti ("42 elementi · dal piu' recente"), le strisce (cosa e' in corso, cosa chiede un gesto, i permessi) **incolonnate in un gruppo**, poi la
// parete di foto. Il menu ⋮ e' un solo tasto da 48 dp in alto a destra, accanto alla freccia indietro: niente galleggia sopra le foto.
// Solo componenti G2 (`FluidScreen`, gruppi lista, bottoni dell'engine; cella e menu del design system).

/** Cosa la pagina chiede fuori da se': tutto passa da qui, la rotta lo lega al controllore e agli altri fogli. */
interface AlbumPageHandlers {
  fun onBack()

  fun onAction(action: AlbumAction)

  fun onOpenItem(media: MediaId)

  /** La pressione prolungata su una cella: il menu dell'elemento. */
  fun onItemMenu(media: MediaId)

  fun onPickCover(media: MediaId)

  fun onCancelPickCover()

  fun onStrip(action: StripAction)

  fun onDismissMessage()

  fun onOpenCopy(album: AlbumId)

  fun onBackToList()
}

/** Nessuna azione: per le anteprime e gli screenshot. */
object NoAlbumPageHandlers : AlbumPageHandlers {
  override fun onBack() = Unit

  override fun onAction(action: AlbumAction) = Unit

  override fun onOpenItem(media: MediaId) = Unit

  override fun onItemMenu(media: MediaId) = Unit

  override fun onPickCover(media: MediaId) = Unit

  override fun onCancelPickCover() = Unit

  override fun onStrip(action: StripAction) = Unit

  override fun onDismissMessage() = Unit

  override fun onOpenCopy(album: AlbumId) = Unit

  override fun onBackToList() = Unit
}

/** La pagina per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. [fallbackTitle] e' il nome prima della prima lettura. */
@Composable
fun AlbumPageScreen(
  ui: AlbumPageUi,
  fallbackTitle: String,
  thumbnails: AlbumThumbnails,
  handlers: AlbumPageHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
  /** Avvio istantaneo: quante celle ha lo scheletro della griglia finche' l'album non e' letto; null = la riga "Caricamento" di prima. */
  skeletonCells: Int? = null,
) {
  when (val page = ui.page) {
    null -> if (skeletonCells != null && skeletonCells > 0) {
      val columns = rememberFluidScreenMetrics().columns(minColumn = AlbumDimens.CellMin, spacing = GalleryWall.Gap, maxColumns = MaxColumns)
      PlainPage(fallbackTitle, handlers, modifier, listState) { instantSkeleton(InstantShape.of(InstantBlock.Tiles(skeletonCells, columns)), "instant-album") }
    } else {
      PlainPage(fallbackTitle, handlers, modifier, listState) { item(key = "loading") { Text(stringResource(R.string.album_loading)) } }
    }
    is AlbumPageState.Ready -> ReadyPage(ui, page, thumbnails, handlers, modifier, listState)
    is AlbumPageState.Recomputing -> PlainPage(page.album.title, handlers, modifier, listState) { recomputing(page) }
    is AlbumPageState.Closed -> PlainPage(page.notice.title, handlers, modifier, listState) { closed(page, handlers) }
    is AlbumPageState.Unavailable -> PlainPage(fallbackTitle, handlers, modifier, listState) { unavailable(page.reason, handlers) }
  }
}

@Composable
private fun PlainPage(title: String, handlers: AlbumPageHandlers, modifier: Modifier, listState: LazyListState, content: LazyListScope.() -> Unit) {
  FluidScreen(title = title, modifier = modifier, onBack = handlers::onBack, listState = listState, contentMaxWidth = AlbumDimens.SheetWidth, content = content)
}

private fun LazyListScope.recomputing(page: AlbumPageState.Recomputing) {
  item(key = "recomputing") {
    Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.BlockGap)) {
      if (page.total <= 0) {
        // AS-2: il motore sta cercando le foto di uno smart nuovo e non sa quante ne guardera': niente numeri e niente barra a zero.
        FluidEmptyState(title = stringResource(R.string.album_recomputing_title), detail = stringResource(R.string.album_recomputing_wait))
      } else {
        FluidEmptyState(title = stringResource(R.string.album_recomputing_title), detail = stringResource(R.string.album_recomputing_progress, GalleryNumbers.format(page.done), GalleryNumbers.format(page.total)))
        FluidProgressBar(progress = { (page.done.toFloat() / page.total).coerceIn(0f, 1f) })
      }
    }
  }
}

private fun LazyListScope.closed(page: AlbumPageState.Closed, handlers: AlbumPageHandlers) {
  item(key = "closed") {
    Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.BlockGap)) {
      FluidEmptyState(
        title = stringResource(R.string.album_closed_title),
        detail = stringResource(R.string.album_closed_detail, page.notice.title) + page.notice.keptCopy?.let { " " + stringResource(R.string.album_closed_copy) }.orEmpty(),
      )
      val copy = page.notice.keptCopy
      if (copy != null) FluidButton(text = stringResource(R.string.album_closed_open_copy), onClick = { handlers.onOpenCopy(copy) }, fillWidth = true)
      FluidButton(text = stringResource(R.string.album_back_to_list), onClick = handlers::onBackToList, style = FluidButtonStyle.Tinted, fillWidth = true)
    }
  }
}

private fun LazyListScope.unavailable(reason: AlbumUnavailableReason, handlers: AlbumPageHandlers) {
  item(key = "unavailable") {
    Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.BlockGap)) {
      val (title, detail) = when (reason) {
        AlbumUnavailableReason.NOT_FOUND -> R.string.album_unavailable_not_found_title to R.string.album_unavailable_not_found_detail
        AlbumUnavailableReason.LEFT -> R.string.album_unavailable_left_title to R.string.album_unavailable_left_detail
        AlbumUnavailableReason.NO_FAMILY -> R.string.album_unavailable_family_title to R.string.album_unavailable_family_detail
      }
      FluidEmptyState(title = stringResource(title), detail = stringResource(detail))
      FluidButton(text = stringResource(R.string.album_back_to_list), onClick = handlers::onBackToList, style = FluidButtonStyle.Tinted, fillWidth = true)
    }
  }
}

@Composable
private fun ReadyPage(
  ui: AlbumPageUi,
  page: AlbumPageState.Ready,
  thumbnails: AlbumThumbnails,
  handlers: AlbumPageHandlers,
  modifier: Modifier,
  listState: LazyListState,
) {
  val content = page.content
  val album = content.album
  val resources = LocalContext.current.resources
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = AlbumDimens.CellMin, spacing = GalleryWall.Gap, maxColumns = MaxColumns)
  val style = rememberGalleryGridCellStyle(cornerScale = GalleryWall.CornerScale)
  val interaction = remember { GalleryGridInteractionState() }
  // CA-1 (Fase 10): le scritture ferme dal manifest firmato hanno la loro frase nella striscia della condivisione.
  val heldByRelease = dev.pampa.mycloudgallery.core.designsystem.state.LocalWritesHeldByRelease.current
  val strips = remember(page, ui.suggested, ui.creatorName, heldByRelease) { AlbumStrips.of(resources, page, ui.suggested, ui.creatorName, heldByRelease = heldByRelease) }
  val cover = album.cover.media
  val actions = rememberCellActions(
    view = content.items,
    coverMedia = cover,
    clickLabel = stringResource(if (ui.pickingCover) R.string.album_pick_cover_title else R.string.album_cell_open),
    longClickLabel = if (ui.pickingCover) null else stringResource(R.string.album_item_actions),
    onTap = { media -> if (ui.pickingCover) handlers.onPickCover(media) else handlers.onOpenItem(media) },
    onLong = handlers::onItemMenu,
  )
  FollowGridWindow(listState, thumbnails, listOf(GridWindowSource("items", content.items, columns)))
  FluidScreen(
    title = album.title,
    subtitle = facts(album, content),
    // Fase 10 (P6, D21, E-T16): una foto trascinata da un'altra app su questa pagina apre il foglio della ricezione con "Aggiungi ad album"; solo dove l'utente puo' aggiungere (un album
    // condiviso dove "guardano" soltanto non e' una destinazione).
    modifier = modifier.mediaDropTarget(DropTarget.Album(album.id.value), enabled = AlbumAction.ADD_MEDIA in ui.actions),
    onBack = handlers::onBack,
    listState = listState,
    metrics = metrics,
    itemSpacing = GalleryWall.Gap,
    contentMaxWidth = AlbumDimens.PageWidth,
    actions = {
      // Fase 10 (P5, D16): da finestra estesa il menu offre "Apri in una nuova finestra" su questo album.
      val newWindow = rememberNewWindowEntry(stringResource(R.string.album_open_new_window)) { port -> port.openAlbum(album.id.value) }
      GalleryMenuButton(entries = pageMenu(ui.actions, handlers::onAction, newWindow), contentDescription = stringResource(R.string.album_more))
    },
  ) {
    header(ui, strips, handlers, page.permissions != null)
    if (content.items.size == 0) {
      emptyAlbum(album, ui, handlers)
    } else {
      albumGrid(
        keyPrefix = "items", view = content.items, columns = columns, thumbnails = thumbnails, style = style, interaction = interaction, actions = actions,
        onTap = { media -> if (ui.pickingCover) handlers.onPickCover(media) else handlers.onOpenItem(media) },
        onLong = if (ui.pickingCover) null else handlers::onItemMenu,
        dragOrigin = DragOrigin.ALBUM,
      )
    }
  }
}

private const val MaxColumns = 12

/** La riga di fatti sotto il titolo: il conto e, per uno smart o un ex condiviso, che cosa e'. */
@Composable
private fun facts(album: Album, content: AlbumContent): String {
  val resources = LocalContext.current.resources
  val parts = buildList {
    // I4: il conto e, per uno smart o un ex condiviso, che cosa e'. L'ordine si vede nelle foto; chi l'ha condiviso lo dice la riga dei
    // permessi, una volta sola.
    add(AlbumText.items(resources, content.items.size))
    when (album) {
      is Album.Shared -> Unit
      is Album.Smart -> add(stringResource(if (album.frozen) R.string.album_facts_frozen else R.string.album_facts_smart))
      is Album.Manual -> if (album.formerShare != null) add(stringResource(R.string.album_facts_former_shared))
    }
  }
  return parts.joinToString(" · ")
}

/** Il messaggio, la richiesta di una copertina e le strisce, in testa. Ogni blocco ha il suo spazio; fra le foto la fessura e' una sola. */
private fun LazyListScope.header(ui: AlbumPageUi, strips: List<Strip>, handlers: AlbumPageHandlers, shared: Boolean) {
  ui.message?.let { message ->
    item(key = "message") {
      Block {
        FluidInlineMessage(
          message = messageText(message),
          title = stringResource(R.string.album_message_dismiss),
          tone = FluidTone.Warning,
          onDismiss = handlers::onDismissMessage,
        )
      }
    }
  }
  if (ui.pickingCover) {
    item(key = "pick-cover") {
      Block {
        FluidCard(highlighted = true) {
          Text(stringResource(R.string.album_pick_cover_title))
          FluidButton(text = stringResource(R.string.album_pick_cover_cancel), onClick = handlers::onCancelPickCover, style = FluidButtonStyle.Tinted, fillWidth = true)
        }
      }
    }
  }
  if (strips.isNotEmpty()) {
    item(key = "strips") {
      Block {
        FluidListGroup {
          strips.forEachIndexed { index, strip ->
            if (index > 0) FluidListDivider()
            StripRow(
              strip.icon,
              strip.tone,
              strip.title,
              strip.detail,
              onClick = if (strip.action == StripAction.NONE) null else ({ handlers.onStrip(strip.action) }),
              family = stripFamily(strip.id),
              status = stripStatus(strip),
            )
          }
        }
        // La dichiarazione sta sotto i permessi, su ogni pagina di un album condiviso (D10, L57): non si nasconde in un foglio.
        if (shared) SectionNote(stringResource(R.string.album_declaration))
      }
    }
  }
}

/** Un blocco di pagina fra le foto: lo spazio sopra e sotto, perche' la lista ha fra gli elementi solo la fessura della parete di foto. */
@Composable
private fun Block(content: @Composable () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = AlbumDimens.BlockGap / 2), verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    content()
  }
}

private fun LazyListScope.emptyAlbum(album: Album, ui: AlbumPageUi, handlers: AlbumPageHandlers) {
  item(key = "empty") {
    Block {
      val canAdd = AlbumAction.ADD_MEDIA in ui.actions
      val (title, detail) = when {
        album is Album.Smart -> R.string.album_empty_smart_title to R.string.album_empty_smart_detail
        album is Album.Shared && !canAdd -> R.string.album_empty_shared_title to R.string.album_empty_view_only
        album is Album.Shared -> R.string.album_empty_shared_title to R.string.album_empty_shared_detail
        else -> R.string.album_empty_title to R.string.album_empty_detail
      }
      FluidEmptyState(title = stringResource(title), detail = stringResource(detail))
      if (album is Album.Smart && AlbumAction.EDIT_RULES in ui.actions) {
        FluidButton(text = stringResource(R.string.album_action_edit_rules), onClick = { handlers.onAction(AlbumAction.EDIT_RULES) }, style = FluidButtonStyle.Tinted, fillWidth = true)
      }
      if (canAdd) FluidButton(text = stringResource(R.string.album_action_add), onClick = { handlers.onAction(AlbumAction.ADD_MEDIA) }, fillWidth = true)
    }
  }
}

@Composable
private fun messageText(message: AlbumMessage): String {
  val resources = LocalContext.current.resources
  return when (message) {
    is AlbumMessage.InvalidTitle -> message.problem?.let { stringResource(AlbumText.titleProblem(it)) } ?: stringResource(R.string.album_message_not_in_album)
    AlbumMessage.NotAllowed -> stringResource(R.string.album_message_not_allowed)
    is AlbumMessage.Blocked -> stringResource(AlbumText.blocked(message.why))
    AlbumMessage.NotFound -> stringResource(R.string.album_message_not_found)
    is AlbumMessage.NothingToAdd ->
      resources.getString(R.string.album_message_nothing_to_add, message.excluded.entries.joinToString(", ") { (reason, n) -> AlbumText.excluded(resources, reason, n) })
    AlbumMessage.RulesTooLong -> stringResource(R.string.album_message_rules_too_long)
    AlbumMessage.NotInAlbum -> stringResource(R.string.album_message_not_in_album)
    AlbumMessage.NotDone -> stringResource(R.string.album_message_not_done)
    AlbumMessage.NotSaved -> stringResource(R.string.album_message_not_saved)
  }
}

/**
 * I4 (fase I): il colore della piastrella di una striscia, per famiglia: la condivisione e le cartelle del NAS il backup, le proposte i
 * ricordi, i permessi la sicurezza, le persone le persone, il resto le foto. Lo stesso segno ovunque nell'app (`GalleryIcons`).
 */
private fun stripFamily(id: String): GalleryIconFamily = when (id) {
  "sharing", "incoming", "to_share", "files_from_pc", "folder_changed", "revoked" -> GalleryIconFamily.Backup
  "suggested" -> GalleryIconFamily.Memories
  "permissions" -> GalleryIconFamily.Security
  "people_off", "people_lost", "inherited" -> GalleryIconFamily.People
  else -> GalleryIconFamily.Photos
}

/** Il segno nell'angolo: in corso per la condivisione che va, fatto per quella finita, attenzione per un avviso. */
private fun stripStatus(strip: Strip): GalleryIconStatus? = when {
  strip.tone == FluidTone.Warning || strip.tone == FluidTone.Danger -> GalleryIconStatus.Attention
  strip.id == "sharing" && strip.tone == FluidTone.Success -> GalleryIconStatus.Done
  strip.id == "sharing" -> GalleryIconStatus.Running
  else -> null
}
