package dev.pampa.mycloudgallery.feature.memories.page

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantBlock
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.instantSkeleton
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.R
import dev.pampa.mycloudgallery.feature.memories.contract.LessSheetState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryAction
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryHeader
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageUi
import dev.pampa.mycloudgallery.feature.memories.sheets.LessPersonSheet
import dev.pampa.mycloudgallery.feature.memories.sheets.LessSheetHandlers
import dev.pampa.mycloudgallery.feature.memories.sheets.NeverDaysSheet
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesDimens
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesMessageBlock
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesText
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryCover
import dev.pampa.mycloudgallery.feature.memories.ui.UndoBar
import dev.pampa.mycloudgallery.feature.memories.ui.coverRequest

// LA PAGINA DEL RICORDO (Fase 9, P9; docs/fase9-progetto.md §1.5 `MemoryPageState`, §2.4, D13). Il titolo grande ("Un anno fa", "Oslo, marzo 2025",
// "Con Anna"), il sottotitolo (periodo e luoghi), la copertina, "Riproduci" in evidenza, la griglia degli elementi (stessa cella e stesso viewer della
// Libreria) e il menu ⋮ (un solo tasto da 48 dp accanto alla freccia): niente galleggia sulle foto. "Questo ricordo non c'e' piu'" ha la sua
// pagina, con il ritorno ai ricordi. Solo componenti G2; i fogli e l'offerta di "Annulla" sono righe e fogli, mai sovrapposti.

/** Cosa la pagina chiede fuori da se': la navigazione, le azioni del menu (passano dal controllore) e i passi dei fogli. */
internal interface MemoryPageHandlers : LessSheetHandlers {
  fun onBack()

  fun onPlay()

  fun onAction(action: MemoryAction)

  fun onOpenPhoto(media: MediaId)

  fun onUndo()

  fun onDismissMessage()
}

private const val MAX_COLUMNS = 12

/** La pagina per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. */
@Composable
internal fun MemoryPageScreen(
  ui: MemoryPageUi,
  thumbnails: MemoriesThumbnails,
  handlers: MemoryPageHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  val resources = LocalContext.current.resources
  when (val page = ui.page) {
    MemoryPageState.Loading -> {
      // Il primo fotogramma: titolo, periodo e conto della voce dello scaffale (se si conosce), poi lo scheletro; la griglia arriva dopo.
      val card = ui.preview
      val title = card?.let { MemoriesText.title(resources, it.kind, it.title, null, emptyList()) } ?: stringResource(R.string.memorie_page_title_unknown)
      val subtitle = card?.let { MemoriesText.subtitle(resources, it.title, emptyList()) }
      FluidScreen(title = title, subtitle = subtitle, modifier = modifier, onBack = handlers::onBack, listState = listState, contentMaxWidth = MemoriesDimens.SheetWidth) {
        instantSkeleton(InstantShape.of(InstantBlock.Tiles(count = 12, columns = 3)), keyPrefix = "memory")
      }
    }
    is MemoryPageState.Ready -> ReadyPage(ui, page, thumbnails, handlers, modifier, listState)
    MemoryPageState.Gone -> Plain(stringResource(R.string.memorie_page_title_unknown), handlers, modifier, listState) {
      ui.undo?.let { offer -> item(key = "undo") { UndoBar(offer, handlers::onUndo) } }
      item(key = "gone") { EmptyBlock(R.string.memorie_gone_title, R.string.memorie_gone_detail, handlers) }
    }
    MemoryPageState.NotFound -> Plain(stringResource(R.string.memorie_page_title_unknown), handlers, modifier, listState) {
      item(key = "not-found") { EmptyBlock(R.string.memorie_not_found_title, R.string.memorie_not_found_detail, handlers) }
    }
  }
  Sheets(ui, handlers)
}

@Composable
private fun Plain(title: String, handlers: MemoryPageHandlers, modifier: Modifier, listState: LazyListState, content: LazyListScope.() -> Unit) {
  FluidScreen(title = title, modifier = modifier, onBack = handlers::onBack, listState = listState, contentMaxWidth = MemoriesDimens.SheetWidth, content = content)
}

@Composable
private fun EmptyBlock(@StringRes title: Int, @StringRes detail: Int, handlers: MemoryPageHandlers) {
  Column(verticalArrangement = Arrangement.spacedBy(MemoriesDimens.BlockGap)) {
    FluidEmptyState(title = stringResource(title), detail = stringResource(detail))
    FluidButton(text = stringResource(R.string.memorie_back_to_memories), onClick = handlers::onBack, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
  }
}

/** I fogli aperti sopra la pagina. Ognuno sta chiuso finche' il suo stato non c'e'. */
@Composable
private fun Sheets(ui: MemoryPageUi, handlers: MemoryPageHandlers) {
  LessPersonSheet(ui.less as? LessSheetState.Person, handlers)
  NeverDaysSheet(ui.less as? LessSheetState.Days, handlers)
}

@Composable
private fun ReadyPage(
  ui: MemoryPageUi,
  page: MemoryPageState.Ready,
  thumbnails: MemoriesThumbnails,
  handlers: MemoryPageHandlers,
  modifier: Modifier,
  listState: LazyListState,
) {
  val resources = LocalContext.current.resources
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = MemoriesDimens.CellMin, spacing = GalleryWall.Gap, maxColumns = MAX_COLUMNS)
  val style = rememberGalleryGridCellStyle(cornerScale = GalleryWall.CornerScale)
  val interaction = remember { GalleryGridInteractionState() }
  val actions = rememberCellActions(page.view, stringResource(R.string.memorie_cell_open), handlers::onOpenPhoto)
  val header = page.header
  val cover = remember(page.view, page.cover) { coverRequest(thumbnails, page.view.snapshot, page.cover, ThumbBucket.L) }
  FollowGridWindow(listState, thumbnails, "photos", page.view, columns, extra = listOfNotNull(cover))
  FluidScreen(
    title = MemoriesText.title(resources, header.kind, header.title, header.personName, header.placeNames),
    subtitle = MemoriesText.subtitle(resources, header.title, header.placeNames),
    modifier = modifier,
    onBack = handlers::onBack,
    listState = listState,
    metrics = metrics,
    itemSpacing = GalleryWall.Gap,
    contentMaxWidth = MemoriesDimens.PageWidth,
    actions = { GalleryMenuButton(entries = menuEntries(header, page.actions, handlers), contentDescription = stringResource(R.string.memorie_more)) },
  ) {
    ui.message?.let { message -> item(key = "message") { MemoriesMessageBlock(message, handlers::onDismissMessage) } }
    ui.undo?.let { offer -> item(key = "undo") { UndoBar(offer, handlers::onUndo) } }
    item(key = "header") {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = MemoriesDimens.BlockGap),
        verticalArrangement = Arrangement.spacedBy(MemoriesDimens.BlockGap),
      ) {
        MemoryCover(thumbnails, page.view.snapshot, page.cover, Modifier.fillMaxWidth().aspectRatio(COVER_RATIO), ThumbBucket.L)
        FluidButton(
          text = stringResource(R.string.memorie_play),
          onClick = handlers::onPlay,
          size = FluidButtonSize.Large,
          fillWidth = true,
          leading = { Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null) },
        )
      }
    }
    memoryGrid("photos", page.view, columns, thumbnails, style, interaction, actions, handlers::onOpenPhoto)
  }
}

private const val COVER_RATIO = 16f / 10f

/** Una voce del menu ⋮: l'azione, il testo, l'icona. */
private class MenuSpec(val action: MemoryAction, val icon: ImageVector, val destructive: Boolean = false)

private val MenuSpecs = listOf(
  MenuSpec(MemoryAction.LESS_PERSON, Icons.Rounded.Person),
  MenuSpec(MemoryAction.NEVER_DAYS, Icons.Rounded.EventBusy),
  MenuSpec(MemoryAction.OPEN_ALBUM, Icons.Rounded.PhotoAlbum),
  MenuSpec(MemoryAction.DISMISS, Icons.Rounded.VisibilityOff, destructive = true),
)

/** Il menu ⋮: "Riproduci" e' il bottone grande, qui le altre azioni nell'ordine d'uso; "Non mostrare questo ricordo" in fondo, dopo un'interruzione. */
@Composable
private fun menuEntries(header: MemoryHeader, actions: Set<MemoryAction>, handlers: MemoryPageHandlers): List<GalleryMenuEntry> {
  val entries = ArrayList<GalleryMenuEntry>()
  for (spec in MenuSpecs.filter { it.action in actions }) {
    if (spec.destructive) entries += GalleryMenuEntry.GroupBreak
    val label = when (spec.action) {
      MemoryAction.LESS_PERSON ->
        header.personName?.let { stringResource(R.string.memorie_menu_less_person, it) } ?: stringResource(R.string.memorie_menu_less_person_unnamed)
      MemoryAction.NEVER_DAYS -> stringResource(R.string.memorie_menu_never_days)
      MemoryAction.OPEN_ALBUM -> stringResource(R.string.memorie_menu_open_album)
      MemoryAction.DISMISS -> stringResource(R.string.memorie_menu_dismiss)
      MemoryAction.PLAY -> stringResource(R.string.memorie_play)
    }
    entries += GalleryMenuEntry.Item(label = label, onClick = { handlers.onAction(spec.action) }, icon = spec.icon, destructive = spec.destructive)
  }
  return entries
}

/** Nessuna azione: per le anteprime e gli screenshot. */
internal object NoMemoryPageHandlers : MemoryPageHandlers {
  override fun onBack() = Unit

  override fun onPlay() = Unit

  override fun onAction(action: MemoryAction) = Unit

  override fun onOpenPhoto(media: MediaId) = Unit

  override fun onUndo() = Unit

  override fun onDismissMessage() = Unit

  override fun onChooseLess(level: LessLevel?) = Unit

  override fun onAdjustDays(fromDelta: Int, toDelta: Int) = Unit

  override fun onOnlyFirstDay() = Unit

  override fun onWholePeriod() = Unit

  override fun onConfirmDays() = Unit

  override fun onDismissSheet() = Unit
}
