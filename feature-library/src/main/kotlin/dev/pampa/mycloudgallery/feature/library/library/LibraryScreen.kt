package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.design.ActionBarHeight
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeMenuAnchor
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTextButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryEdgeScrim
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.design.galleryChromeTag
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryFilterOption
import dev.pampa.mycloudgallery.core.designsystem.grid.galleryFilterMenuEntries
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.countText
import dev.pampa.mycloudgallery.feature.library.grid.ChromeLabelText
import dev.pampa.mycloudgallery.feature.library.grid.GridSelectionBar
import dev.pampa.mycloudgallery.feature.library.grid.GridScrubber
import dev.pampa.mycloudgallery.feature.library.grid.GridTopChrome
import dev.pampa.mycloudgallery.feature.library.grid.GridTopEdgeScrim
import dev.pampa.mycloudgallery.feature.library.grid.gridTopChromeHeight
import dev.pampa.mycloudgallery.feature.library.grid.MediaGrid
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridSection
import dev.pampa.mycloudgallery.feature.library.grid.rememberMediaGridSection
import dev.pampa.mycloudgallery.feature.library.photoVideoSummary
import kotlinx.coroutines.launch

// La Libreria, "vetro adattivo" (direzione C, G2: docs/grafica-progetto.md §4, §11, §12). La struttura:
//
//  - in alto UNA riga di chrome sotto la barra di stato (`GridTopChrome`): a sinistra l'etichetta con la data della sezione
//    in vista (e' l'intestazione agganciata: nessuna seconda fascia di vetro), a destra la capsula con "oggi", "seleziona"
//    e il menu della vista, che porta anche i livelli (niente segmenti: il pinch e' il gesto, il menu la via esplicita);
//  - in basso SOLO la pillola, che si ripiega scorrendo verso i piu' vecchi; in selezione la barra delle azioni prende il
//    suo posto, alla stessa altezza e nella stessa posizione, e la griglia non si sposta (lo spazio in fondo e' lo stesso);
//  - un fondale di vetro solo, quello della pagina: lo scrubber e' disegnato fuori, la griglia non ne registra un secondo;
//  - la griglia e' una parete di foto squadrata con la fessura di 1 dp; i segni della presenza in alto a destra.

@Composable
internal fun LibraryScreen(
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  onOpenSettings: () -> Unit,
  statusFooter: @Composable () -> Unit,
  state: LibraryTabState,
  tabBarScroll: NestedScrollConnection?,
  onNasStatusClick: () -> Unit,
) {
  val grid = state.grid
  val phone by state.phone.collectAsState()
  val nas by state.nas.collectAsState()
  val backdrop = rememberGlassBackdrop()
  SideEffect { backdropOut(backdrop) }
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current

  var deleting by remember { mutableStateOf(false) }
  var hidingGroups by remember { mutableStateOf(false) }
  var noticesHeightPx by remember { mutableIntStateOf(0) }

  val keeper = rememberSaveable(saver = positionSaver(grid)) { PositionKeeper(null) }
  SideEffect { keeper.restoreOnce(grid) }

  val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

  // La riga di chrome in alto (cresce con il testo grande): e' la linea sotto cui comincia la griglia.
  val topChrome = gridTopChromeHeight()
  val showPermission = phone.access == MediaAccess.NONE
  val noticesShown = !showPermission && hasNotices(phone, state.notice)
  if (!noticesShown && noticesHeightPx != 0) SideEffect { noticesHeightPx = 0 }
  val noticesHeight = with(density) { noticesHeightPx.toDp() }
  val topPadding = if (noticesHeightPx > 0) topChrome + GalleryChromeDefaults.Gap + noticesHeight + GalleryChromeDefaults.Gap else topChrome

  // Il posto della pillola (e della barra delle azioni, che lo prende in selezione): lo stesso nei due casi.
  val slotBottom = navBottom + FluidFloatingTabBarDefaults.BottomMargin
  val bottomPadding = maxOf(slotBottom + ActionBarHeight + GalleryChromeDefaults.Gap, contentPadding.calculateBottomPadding())
  val empty = grid.view.size == 0
  val section = rememberMediaGridSection(grid, topChrome)
  val pillVeil = rememberPillVeil()
  val status: @Composable () -> Unit = { LibraryStatusBlock(nas = nas, statusFooter = statusFooter, onNasClick = onNasStatusClick) }

  // I tag della chrome come id di risorsa: gli strumenti di misura sull'emulatore (uiautomator) la trovano per nome.
  Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { testTagsAsResourceId = true }) {
    // Il fondale della pagina: la griglia e le sfumature di bordo. Tutto il vetro (chrome, pillola, scrubber) lo rifrange.
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      when {
        showPermission -> LibraryPermissionState(
          onAllow = { state.commands.requestMediaAccess() },
          modifier = Modifier.padding(top = topChrome, bottom = bottomPadding),
          footer = status,
        )
        empty && (phone.firstRead != null || phone.selectionPending) -> LibraryFirstReadState(
          progress = phone.firstRead,
          modifier = Modifier.padding(top = topChrome, bottom = bottomPadding),
          footer = status,
        )
        empty && state.filter != LibraryFilter.ALL -> LibraryFilterEmptyState(
          filter = state.filter,
          onShowAll = { scope.launch { state.chooseFilter(LibraryFilter.ALL) } },
          modifier = Modifier.padding(top = topChrome, bottom = bottomPadding),
        )
        empty -> LibraryEmptyState(modifier = Modifier.padding(top = topChrome, bottom = bottomPadding), footer = status)
        else -> MediaGrid(
          state = grid,
          modifier = if (tabBarScroll != null) Modifier.nestedScroll(tabBarScroll) else Modifier,
          contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
          onOpen = { id -> onOpenViewer(ViewerRequest(items = state.viewerItems, start = id, anchor = grid.viewerAnchor, secure = false)) },
          footer = { LibraryFooter(state, phone, status) },
          stickyTop = topChrome,
          onLevelChanged = { level -> scope.launch { state.preferences.setLevel(level) } },
        )
      }
      GridTopEdgeScrim(topChrome, Modifier.align(Alignment.TopCenter))
      BottomEdgeScrim(bottomPadding, slotBottom, pillVeil)
    }

    if (!showPermission) {
      LibraryNotices(
        phone = phone,
        commands = state.commands,
        notice = state.notice,
        onDismissNotice = { state.notice = null },
        backdrop = backdrop,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = topChrome + GalleryChromeDefaults.Gap, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter)
          .onSizeChanged { noticesHeightPx = it.height }
          .galleryChromeTag("avvisi"),
      )
    }

    val farFromToday by remember(grid) { derivedStateOf { grid.screensFromNewest() > TodayAfterScreens } }
    GridTopChrome(
      grid = grid,
      backdrop = backdrop,
      label = libraryLabel(state, section),
      topChrome = topChrome,
      modifier = Modifier.align(Alignment.TopStart),
      showCommands = !showPermission,
    ) {
      LibraryCommandsContent(
        state = state,
        showSelect = !empty,
        showToday = farFromToday && !grid.selecting && !empty,
        onOpenSettings = onOpenSettings,
      )
    }

    // Lo scrubber fra la chrome in alto e il posto della pillola: la maniglia non arriva mai sotto un altro controllo.
    if (!showPermission && !empty) {
      GridScrubber(
        grid = grid,
        backdrop = backdrop,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = topPadding + GalleryChromeDefaults.Gap, bottom = bottomPadding),
      )
    }

    // In selezione la barra delle azioni sta al posto della pillola (che `:app` nasconde), mai sopra di lei.
    GridSelectionBar(
      visible = grid.selecting && !showPermission && !empty,
      grid = grid,
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = slotBottom),
      veilAlpha = pillVeil,
    ) {
      selectionActions(state, onDelete = { deleting = true }, onHideGroups = { hidingGroups = true })
    }

    LibraryDeleteSheet(visible = deleting, state = state, onDismiss = { deleting = false })
    LibraryHideGroupSheet(visible = hidingGroups, state = state, onDismiss = { hidingGroups = false })
  }
}

/**
 * Che cosa dice l'etichetta in alto: in selezione quanti sono gli scelti; scorrendo la sezione sotto la riga di chrome
 * (data e luogo); in cima, dove non c'e' ancora una sezione, "Libreria" e il riepilogo. Un filtro attivo si dice sempre,
 * davanti al luogo ("Video · Oslo"): la griglia non mostra tutto, e lo si vede senza aprire il menu.
 */
@Composable
private fun libraryLabel(state: LibraryTabState, section: MediaGridSection?): ChromeLabelText {
  val grid = state.grid
  val selected by grid.selection.selected.collectAsState()
  val filter = if (state.filter == LibraryFilter.ALL) null else filterLabel(state.filter)
  return when {
    grid.selecting && selected.isEmpty -> ChromeLabelText(stringResource(R.string.library_select_items), null)
    grid.selecting -> ChromeLabelText(countText(R.plurals.selection_count, selected.size), null)
    section != null -> ChromeLabelText(section.title, listOfNotNull(filter, section.place).joinToString(" · ").ifEmpty { null })
    else -> {
      val summary = if (state.photoCount + state.videoCount > 0) photoVideoSummary(state.photoCount, state.videoCount) else null
      ChromeLabelText(stringResource(R.string.library_title), listOfNotNull(filter, summary).joinToString(" · ").ifEmpty { null })
    }
  }
}

/** Dentro la capsula dei comandi: oggi (se lontani), seleziona, la vista; in selezione solo "Fine". */
@Composable
private fun LibraryCommandsContent(state: LibraryTabState, showSelect: Boolean, showToday: Boolean, onOpenSettings: () -> Unit) {
  val grid = state.grid
  val scope = rememberCoroutineScope()
  if (grid.selecting) {
    GalleryChromeTextButton(label = stringResource(R.string.library_done), onClick = { grid.stopSelecting() }, modifier = Modifier.galleryChromeTag("fine"))
    return
  }
  if (showToday) {
    GalleryChromeIconButton(
      icon = Icons.Rounded.KeyboardDoubleArrowDown,
      contentDescription = stringResource(R.string.library_jump_today_description),
      onClick = { scope.launch { grid.scrollToNewest() } },
      modifier = Modifier.galleryChromeTag("oggi"),
    )
  }
  if (showSelect) {
    GalleryChromeIconButton(
      icon = Icons.Rounded.CheckCircleOutline,
      contentDescription = stringResource(R.string.library_select),
      onClick = { grid.startSelecting() },
      modifier = Modifier.galleryChromeTag("seleziona"),
    )
  }
  val description = stringResource(R.string.library_view_menu)
  val filterActive = state.filter != LibraryFilter.ALL
  GalleryChromeMenuAnchor(entries = libraryMenu(state, onOpenSettings), paneTitle = description, modifier = Modifier.galleryChromeTag("vista")) { open ->
    GalleryChromeIconButton(icon = if (filterActive) Icons.Rounded.FilterAlt else Icons.Rounded.MoreHoriz, contentDescription = description, onClick = open)
  }
}

/**
 * Il menu della vista ("Vista", il nome che cercano anche gli scenari di misura): i livelli (scelta 4 dell'utente: nel
 * menu, non sempre in vista), il filtro (D16), lo stile (D4), in debug la Libreria sintetica, le Impostazioni.
 */
@Composable
private fun libraryMenu(state: LibraryTabState, onOpenSettings: () -> Unit): List<GalleryMenuEntry> {
  val scope = rememberCoroutineScope()
  val grid = state.grid
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val labels = levelLabels()
  val levels = GridLevel.entries.map { level ->
    GalleryFilterOption(labels[level.ordinal], selected = level == grid.level) {
      scope.launch { grid.selectLevel(level, reducedMotion) }
      scope.launch { state.preferences.setLevel(level) }
    }
  }
  val filters = if (state.timeline != null) {
    LibraryFilter.entries.map { filter -> GalleryFilterOption(filterLabel(filter), selected = filter == state.filter) { scope.launch { state.chooseFilter(filter) } } }
  } else {
    emptyList()
  }
  val styles = GridStyle.entries.map { style ->
    GalleryFilterOption(
      label = stringResource(if (style == GridStyle.SQUARE) R.string.library_style_square else R.string.library_style_justified),
      selected = style == grid.style,
    ) { scope.launch { state.chooseStyle(style) } }
  }
  // Solo debug: 0 = la Libreria vera del telefono (P11: la sintetica ha un database a parte e si sceglie da qui).
  val sizes = state.debug?.let { hooks ->
    hooks.sizes.map { size ->
      val label = if (size == 0) stringResource(R.string.library_debug_phone) else stringResource(R.string.library_debug_synthetic, GalleryNumbers.format(size))
      GalleryFilterOption(label, selected = size == hooks.current) { hooks.select(size) }
    }
  }.orEmpty()
  return galleryFilterMenuEntries(
    groups = listOf(levels, filters, styles, sizes),
    trailing = listOf(GalleryMenuEntry.Item(label = stringResource(R.string.library_open_settings), onClick = onOpenSettings, icon = Icons.Rounded.Settings)),
  )
}

@Composable
private fun levelLabels(): List<String> = listOf(
  stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_years),
  stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_months),
  stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_days),
  stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_all),
)

/**
 * La sfumatura del colore della pagina in basso, **dentro** il fondale: dietro la pillola dell'engine, che ha la sua
 * pellicola fissa, garantisce un fondo alle etichette sopra qualunque foto. Al bordo della pillola l'opacita' e' calcolata
 * ([rememberPillVeil]), sotto sale fino al bordo dello schermo.
 */
@Composable
private fun BoxScope.BottomEdgeScrim(bottomPadding: Dp, slotBottom: Dp, pillVeil: Float) {
  val height = bottomPadding + BottomFade
  val pillTop = height - slotBottom - ActionBarHeight
  GalleryEdgeScrim(
    color = MaterialTheme.colorScheme.background,
    stops = listOf(0.dp to 0f, pillTop to pillVeil, height to maxOf(pillVeil, BottomVeilAtEdge)),
    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height),
  )
}

/**
 * Il velo del colore della pagina dietro la pillola, all'altezza del suo bordo alto: il minimo che porta le etichette della
 * pillola dell'engine (`onSurface` sulla pellicola `floatingTint`) a 4,5:1 piu' il margine misurato sopra il bianco e il
 * nero pieni. Nel tema chiaro e' poco; nel tema scuro, dove la pellicola grigia sopra una foto bianca lasciava le etichette
 * a 3,2:1 (misurato in G1), circa 0,6.
 */
@Composable
private fun rememberPillVeil(): Float {
  val scheme = MaterialTheme.colorScheme
  val film = GlassDefaults.floatingTint().overlay
  return remember(scheme, film) {
    val worst = ContentRange(listOf(GalleryOnPhoto.Content, scheme.scrim))
    GalleryLegibility.minimalUnderlayAlpha(scheme.onSurface, film, scheme.background, worst, GalleryChromeDefaults.MeasuredTarget, MinBottomVeil, MaxBottomVeil)
  }
}

// La sfumatura della pagina in basso, dietro la pillola (l'opacita' al bordo della pillola e' calcolata).
private val BottomFade = 32.dp
// Il minimo al bordo della pillola non e' solo aritmetica: l'etichetta della scheda scelta sta sul pomello, tinto
// d'accento dall'engine, e sopra una foto qualunque scendeva a 3,7:1 con il velo calcolato per le altre (misurato in G1).
private const val MinBottomVeil = 0.55f
private const val MaxBottomVeil = 0.90f
private const val BottomVeilAtEdge = 0.90f

/** Da quante schermate di distanza dai piu' recenti compare "vai a oggi". */
private const val TodayAfterScreens = 2f
