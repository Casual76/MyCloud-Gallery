package dev.pampa.mycloudgallery.feature.library.library

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.design.ActionBarHeight
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeActionBar
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeCapsule
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeMenuAnchor
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyle
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyles
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTextButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTitle
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryEdgeScrim
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.design.LocalGalleryChromeInk
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
import dev.pampa.mycloudgallery.feature.library.grid.MediaGrid
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridScrubber
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridSection
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.grid.contentUnder
import dev.pampa.mycloudgallery.feature.library.grid.rememberMediaGridSection
import dev.pampa.mycloudgallery.feature.library.grid.rowsUnder
import dev.pampa.mycloudgallery.feature.library.photoVideoSummary
import kotlinx.coroutines.launch

// I prototipi grafici della Libreria (G1, docs/grafica-progetto.md §4). Una struttura sola, tre trattamenti:
//
//  - in alto UNA riga di chrome sotto la barra di stato: a sinistra la data della sezione in vista (e' l'intestazione
//    agganciata: nessuna seconda fascia di vetro), a destra una capsula con "oggi", "seleziona" e il menu della vista,
//    che porta anche i livelli (i segmenti in basso non ci sono piu': il pinch e' il gesto, il menu la via esplicita);
//  - in basso SOLO la pillola; in selezione la barra delle azioni prende il suo posto, alla stessa altezza e nella stessa
//    posizione, e la griglia non si sposta (lo spazio in fondo e' quello della pillola in tutti e due i casi);
//  - un fondale di vetro solo, quello della pagina: lo scrubber e' disegnato fuori, la griglia non ne registra un secondo.
//
// A, B e C cambiano materiale, inchiostro e sfumature di bordo; il resto e' comune. Solo debug (`LibraryDesign`).

@Composable
internal fun LibraryPrototypeScreen(
  design: LibraryDesign,
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  onOpenSettings: () -> Unit,
  statusFooter: @Composable () -> Unit,
  state: LibraryTabState,
  tabBarScroll: NestedScrollConnection?,
) {
  val grid = state.grid
  val phone by state.phone.collectAsState()
  val backdrop = rememberGlassBackdrop()
  SideEffect { backdropOut(backdrop) }
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val scheme = MaterialTheme.colorScheme

  var deleting by remember { mutableStateOf(false) }
  var hidingGroups by remember { mutableStateOf(false) }
  var noticesHeightPx by remember { mutableIntStateOf(0) }
  var barHeightPx by remember { mutableIntStateOf(0) }

  val keeper = rememberSaveable(saver = positionSaver(grid)) { PositionKeeper(null) }
  SideEffect { keeper.restoreOnce(grid) }
  if (design == LibraryDesign.A) LightStatusIcons()

  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

  // La riga di chrome in alto: in A e C sotto la barra di stato, in B la barra opaca misurata (cresce col testo grande).
  val topChrome = if (design == LibraryDesign.B && barHeightPx > 0) with(density) { barHeightPx.toDp() } else statusBar + GalleryChromeDefaults.TopRowHeight
  val showPermission = phone.access == MediaAccess.NONE
  val noticesShown = !showPermission && hasNotices(phone, state.notice)
  if (!noticesShown && noticesHeightPx != 0) SideEffect { noticesHeightPx = 0 }
  val noticesHeight = with(density) { noticesHeightPx.toDp() }
  val topPadding = if (noticesHeightPx > 0) topChrome + GalleryChromeDefaults.Gap + noticesHeight + GalleryChromeDefaults.Gap else topChrome

  // Il posto della pillola (e della barra delle azioni, che lo prende in selezione): lo stesso nei due casi.
  val slotBottom = navBottom + FluidFloatingTabBarDefaults.BottomMargin
  val bottomPadding = maxOf(slotBottom + ActionBarHeight + GalleryChromeDefaults.Gap, contentPadding.calculateBottomPadding())
  val view = grid.view
  val empty = view.size == 0
  val section = rememberMediaGridSection(grid, topChrome)
  val pillVeil = rememberPillVeil()

  // I tag della chrome come id di risorsa: gli strumenti di misura sull'emulatore (uiautomator) la trovano per nome.
  Box(modifier = Modifier.fillMaxSize().background(scheme.background).semantics { testTagsAsResourceId = true }) {
    // Il fondale della pagina: la griglia e le sfumature di bordo. Tutto il vetro (capsule, pillola, scrubber) lo rifrange.
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      when {
        showPermission -> LibraryPermissionState(
          onAllow = { state.commands.requestMediaAccess() },
          modifier = Modifier.padding(top = topChrome, bottom = bottomPadding),
          footer = statusFooter,
        )
        empty && (phone.firstRead != null || phone.selectionPending) -> LibraryFirstReadState(
          progress = phone.firstRead,
          modifier = Modifier.padding(top = topChrome, bottom = bottomPadding),
          footer = statusFooter,
        )
        empty && state.filter != LibraryFilter.ALL -> LibraryFilterEmptyState(
          filter = state.filter,
          onShowAll = { scope.launch { state.chooseFilter(LibraryFilter.ALL) } },
          modifier = Modifier.padding(top = topChrome, bottom = bottomPadding),
        )
        empty -> LibraryEmptyState(modifier = Modifier.padding(top = topChrome, bottom = bottomPadding), footer = statusFooter)
        else -> MediaGrid(
          state = grid,
          modifier = if (tabBarScroll != null && design != LibraryDesign.B) Modifier.nestedScroll(tabBarScroll) else Modifier,
          contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
          onOpen = { id -> onOpenViewer(ViewerRequest(items = state.viewerItems, start = id, anchor = grid.viewerAnchor, secure = false)) },
          footer = { LibraryFooter(state, phone, statusFooter) },
          backdrop = null,
          stickyTop = topChrome,
          onLevelChanged = { level -> scope.launch { state.preferences.setLevel(level) } },
          stickyHeader = false,
          cellCornerScale = if (design == LibraryDesign.A) 0f else 1f,
          gap = if (design == LibraryDesign.A) PhotoWallGap else GalleryDimens.GridGap,
        )
      }
      EdgeScrims(design, topChrome, statusBar, bottomPadding, slotBottom, pillVeil)
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

    val menu = prototypeMenu(state, includeLevels = design != LibraryDesign.B, onOpenSettings = onOpenSettings)
    val farFromToday by remember(grid) { derivedStateOf { grid.screensFromNewest() > TodayAfterScreens } }
    val chrome = PrototypeChrome(
      grid = grid,
      section = section,
      summary = photoVideoSummary(state.photoCount, state.videoCount),
      menu = menu,
      showSelect = !showPermission && !empty,
      showToday = farFromToday && !grid.selecting && !empty,
      filterActive = state.filter != LibraryFilter.ALL,
      onToday = { scope.launch { grid.scrollToNewest() } },
    )
    when (design) {
      LibraryDesign.A -> TopChromeA(chrome, statusBar, backdrop, Modifier.align(Alignment.TopStart))
      LibraryDesign.B -> TopChromeB(
        chrome = chrome,
        state = state,
        statusBar = statusBar,
        modifier = Modifier.align(Alignment.TopStart).onSizeChanged { barHeightPx = it.height },
      )
      else -> TopChromeC(chrome, statusBar, backdrop, topChrome, Modifier.align(Alignment.TopStart))
    }

    // Lo scrubber fra la chrome in alto e il posto della pillola: la maniglia non arriva mai sotto un altro controllo.
    if (!showPermission && !empty) {
      val scrubberStyle = scrubberStyle(design)
      MediaGridScrubber(
        state = grid,
        backdrop = backdrop,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = topPadding + GalleryChromeDefaults.Gap, bottom = bottomPadding)
          .galleryChromeTag("scrubber"),
        tint = scrubberStyle.tint,
        ink = scrubberStyle.ink,
        solid = scrubberStyle.solid,
      )
    }

    // In selezione la barra delle azioni sta al posto della pillola (che `:app` nasconde), mai sopra di lei.
    AnimatedVisibility(
      visible = grid.selecting && !showPermission && !empty,
      enter = fadeIn(FluidMotion.fadeIn()),
      exit = fadeOut(FluidMotion.fadeOut()),
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = slotBottom),
    ) {
      PrototypeActionBar(design, state, backdrop, pillVeil, onDelete = { deleting = true }, onHideGroups = { hidingGroups = true })
    }

    LibraryDeleteSheet(visible = deleting, state = state, onDismiss = { deleting = false })
    LibraryHideGroupSheet(visible = hidingGroups, state = state, onDismiss = { hidingGroups = false })
  }
}

/** Cio' che le tre chrome in alto mostrano e fanno: uguale per tutte. */
private class PrototypeChrome(
  val grid: MediaGridState,
  val section: MediaGridSection?,
  val summary: String,
  val menu: List<GalleryMenuEntry>,
  val showSelect: Boolean,
  val showToday: Boolean,
  val filterActive: Boolean,
  val onToday: () -> Unit,
)

// ---------------------------------------------------------------------------------------------
// A, sopra le foto
// ---------------------------------------------------------------------------------------------

/**
 * **A**: la chrome di un visore di foto. Sotto la barra di stato una sfumatura nera (disegnata nel fondale, in
 * [EdgeScrims]) e sopra il testo **bianco** con un'ombra leggera: la data della sezione e il luogo, in chiaro e in scuro.
 * A destra una capsula di vetro scuro (`darkFloatingTint`). Nessuna barra di vetro a tutta larghezza.
 */
@Composable
private fun TopChromeA(chrome: PrototypeChrome, statusBar: Dp, backdrop: GlassBackdropState, modifier: Modifier) {
  val style = GalleryChromeStyles.overPhoto()
  TopRow(statusBar, modifier) {
    TitleBlock(chrome, style.ink, style, Modifier.weight(1f).galleryChromeTag("titolo"))
    Spacer(Modifier.width(GalleryChromeDefaults.Gap))
    RightCapsule(chrome, style, backdrop)
  }
}

// ---------------------------------------------------------------------------------------------
// B, barra solida
// ---------------------------------------------------------------------------------------------

/**
 * **B**: una barra **opaca** del colore della pagina, con il titolo "Libreria" a sinistra e i comandi a destra (il
 * livello come etichetta con la freccia, la selezione, il menu), e sotto una seconda riga con la data e il luogo della
 * sezione in vista. La griglia comincia sotto la barra: niente passa sotto il testo, quindi il contrasto e' quello del
 * tema e basta. Il vetro resta solo alla pillola.
 */
@Composable
private fun TopChromeB(chrome: PrototypeChrome, state: LibraryTabState, statusBar: Dp, modifier: Modifier) {
  val scheme = MaterialTheme.colorScheme
  val grid = chrome.grid
  val scope = rememberCoroutineScope()
  val selected by grid.selection.selected.collectAsState()
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  Column(modifier = modifier.fillMaxWidth().background(scheme.background).galleryChromeTag("barra")) {
    Spacer(Modifier.height(statusBar))
    CompositionLocalProvider(LocalGalleryChromeInk provides scheme.onSurface) {
      Row(
        modifier = Modifier.fillMaxWidth().height(BarRowHeight).padding(start = GalleryDimens.Gutter, end = BarEndInset),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (grid.selecting) {
          GalleryChromeIconButton(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.library_cancel_selection),
            onClick = { grid.stopSelecting() },
            modifier = Modifier.galleryChromeTag("chiudi"),
          )
          Spacer(Modifier.width(BarTitleGap))
        }
        Text(
          text = when {
            !grid.selecting -> stringResource(R.string.library_title)
            selected.isEmpty -> stringResource(R.string.library_select_items)
            else -> countText(R.plurals.library_g1_selected, selected.size)
          },
          style = MaterialTheme.typography.headlineSmall,
          color = scheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f).semantics { heading() }.galleryChromeTag("titolo"),
        )
        if (!grid.selecting) {
          if (chrome.showToday) {
            GalleryChromeIconButton(
              icon = Icons.Rounded.KeyboardDoubleArrowDown,
              contentDescription = stringResource(R.string.library_jump_today_description),
              onClick = chrome.onToday,
              modifier = Modifier.galleryChromeTag("oggi"),
            )
          }
          LevelChip(grid = grid, onSelect = { level ->
            scope.launch { grid.selectLevel(level, reducedMotion) }
            scope.launch { state.preferences.setLevel(level) }
          })
          if (chrome.showSelect) {
            GalleryChromeIconButton(
              icon = Icons.Rounded.CheckCircleOutline,
              contentDescription = stringResource(R.string.library_select),
              onClick = { grid.startSelecting() },
              modifier = Modifier.galleryChromeTag("seleziona"),
            )
          }
          MenuButton(chrome, Modifier.galleryChromeTag("vista"))
        }
      }
    }
    // La data agganciata: una riga della barra, non una fascia sopra le foto.
    Row(
      modifier = Modifier.fillMaxWidth().height(SectionRowHeight).padding(horizontal = GalleryDimens.Gutter),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SectionRowGap),
    ) {
      val section = chrome.section
      Text(
        text = section?.title ?: chrome.summary,
        style = MaterialTheme.typography.titleSmall,
        color = scheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      section?.place?.let { place ->
        Text(text = place, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    FluidHairline()
  }
}

/** Il livello come etichetta con la freccia ("Giorni ▾"): apre i quattro livelli. Il pinch resta il gesto principale. */
@Composable
private fun LevelChip(grid: MediaGridState, onSelect: (GridLevel) -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val labels = levelLabels()
  val entries = GridLevel.entries.map { level ->
    GalleryMenuEntry.Item(label = labels[level.ordinal], onClick = { onSelect(level) }, icon = if (level == grid.level) Icons.Rounded.Check else null)
  }
  val current = labels[grid.level.ordinal]
  GalleryChromeMenuAnchor(entries = entries, paneTitle = stringResource(R.string.library_g1_level, current), modifier = Modifier.galleryChromeTag("livello")) { open ->
    Box(modifier = Modifier.padding(horizontal = ChipOuterPadding).background(scheme.surfaceContainerHigh, FluidCapsuleShape)) {
      GalleryChromeTextButton(
        label = current,
        onClick = open,
        trailing = Icons.Rounded.ExpandMore,
        contentDescription = stringResource(R.string.library_g1_level, current),
      )
    }
  }
}

// ---------------------------------------------------------------------------------------------
// C, vetro adattivo
// ---------------------------------------------------------------------------------------------

/**
 * **C**: due oggetti di vetro e niente barra. A sinistra un'etichetta di vetro con la data e il luogo, a destra la
 * capsula dei comandi; sotto, una sfumatura del colore della pagina (lo "scroll edge effect"). Ognuno dei due sceglie da
 * se' pellicola e inchiostro dal contenuto che gli passa sotto ([rememberAdaptiveStyle]): scuro su chiaro sopra le foto
 * chiare, chiaro su scuro sopra quelle scure, con l'opacita' minima che tiene 4,5:1 sul caso peggiore.
 */
@Composable
private fun TopChromeC(chrome: PrototypeChrome, statusBar: Dp, backdrop: GlassBackdropState, topChrome: Dp, modifier: Modifier) {
  val grid = chrome.grid
  val page = MaterialTheme.colorScheme.background
  val density = LocalDensity.current
  var titleBounds by remember { mutableStateOf<Rect?>(null) }
  var capsuleBounds by remember { mutableStateOf<Rect?>(null) }
  val stops = topEdgeStopsC(statusBar, topChrome)
  // Il velo sotto un pezzo di chrome: la sfumatura della pagina alla sua opacita' piu' bassa (il bordo basso del pezzo).
  fun veilAt(bounds: Rect?): Color? = bounds?.let { page.copy(alpha = edgeAlphaAt(stops, with(density) { it.bottom.toDp() })) }
  val titleStyle = rememberAdaptiveStyle(grid, titleBounds, veilAt(titleBounds))
  val capsuleStyle = rememberAdaptiveStyle(grid, capsuleBounds, veilAt(capsuleBounds))
  TopRow(statusBar, modifier) {
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
      Box(
        modifier = Modifier
          .onGloballyPositioned { titleBounds = it.inGrid(grid) }
          .glassSurface(state = backdrop, tint = titleStyle.tint, shape = ContinuousCornerShape(FluidRadius.Card), role = GlassRole.Floating)
          .padding(horizontal = LabelPaddingH, vertical = LabelPaddingV)
          .galleryChromeTag("titolo"),
      ) {
        TitleBlock(chrome, titleStyle.ink, titleStyle, Modifier, compact = true)
      }
    }
    Spacer(Modifier.width(GalleryChromeDefaults.Gap))
    RightCapsule(chrome, capsuleStyle, backdrop, Modifier.onGloballyPositioned { capsuleBounds = it.inGrid(grid) })
  }
}

/**
 * Lo stile adattivo di un pezzo di chrome che sta su [bounds] (pixel della griglia): si ricalcola solo quando sotto
 * entra o esce una riga ([rowsUnder]), dai ThumbHash delle celle ([contentUnder]) composti con il velo [veil]; inchiostro
 * e pellicola cambiano con la molla standard (subito, con le animazioni ridotte).
 */
@Composable
private fun rememberAdaptiveStyle(grid: MediaGridState, bounds: Rect?, veil: Color?): GalleryChromeStyle {
  val palette = GalleryChromeStyles.adaptivePalette()
  val page = MaterialTheme.colorScheme.background
  val text = MaterialTheme.colorScheme.onSurface
  val darkTheme = GlassDefaults.isDarkSurface()
  val key by remember(grid, bounds) { derivedStateOf { bounds?.let { grid.rowsUnder(it) } } }
  val previous = remember { mutableStateOf<Boolean?>(null) }
  val view = grid.view
  val k = key
  val choice = remember(k, view, page, text, veil, palette) {
    val raw = if (bounds == null) palette.unknown else grid.contentUnder(bounds, page, text)
    val range = if (veil == null) raw else ContentRange(raw.samples.map { veil.compositeOver(it) })
    GalleryLegibility.adapt(range, palette, previousDark = previous.value ?: !darkTheme)
  }
  SideEffect { previous.value = choice.darkInk }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val ink by animateColorAsState(choice.ink, if (reduced) snap() else FluidMotion.standard(), label = "inchiostro adattivo")
  val film by animateColorAsState(choice.film, if (reduced) snap() else FluidMotion.standard(), label = "pellicola adattiva")
  return GalleryChromeStyles.adaptive(choice.copy(ink = ink, film = film))
}

// ---------------------------------------------------------------------------------------------
// Pezzi comuni
// ---------------------------------------------------------------------------------------------

@Composable
private fun TopRow(statusBar: Dp, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = statusBar)
      .height(GalleryChromeDefaults.TopRowHeight)
      .padding(start = TitleStart, end = GalleryChromeDefaults.EdgeMargin),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

/** La data della sezione (o "Libreria" e il riepilogo in cima), o in selezione quanti sono gli elementi scelti. */
@Composable
private fun TitleBlock(chrome: PrototypeChrome, ink: Color, style: GalleryChromeStyle, modifier: Modifier, compact: Boolean = false) {
  val grid = chrome.grid
  val selected by grid.selection.selected.collectAsState()
  val titleStyle = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
  val (title, subtitle) = when {
    grid.selecting && selected.isEmpty -> stringResource(R.string.library_select_items) to null
    grid.selecting -> countText(R.plurals.library_g1_selected, selected.size) to null
    chrome.section != null -> chrome.section.title to chrome.section.place
    else -> stringResource(R.string.library_title) to chrome.summary
  }
  GalleryChromeTitle(title = title, subtitle = subtitle, ink = ink, modifier = modifier, shadow = style.shadow, titleStyle = titleStyle)
}

/** La capsula dei comandi a destra: oggi (se lontani), seleziona, la vista; in selezione solo "Fine". */
@Composable
private fun RightCapsule(chrome: PrototypeChrome, style: GalleryChromeStyle, backdrop: GlassBackdropState, modifier: Modifier = Modifier) {
  val grid = chrome.grid
  GalleryChromeCapsule(style = style, backdrop = backdrop, modifier = modifier.galleryChromeTag("comandi")) {
    if (grid.selecting) {
      GalleryChromeTextButton(label = stringResource(R.string.library_g1_done), onClick = { grid.stopSelecting() })
    } else {
      if (chrome.showToday) {
        GalleryChromeIconButton(
          icon = Icons.Rounded.KeyboardDoubleArrowDown,
          contentDescription = stringResource(R.string.library_jump_today_description),
          onClick = chrome.onToday,
        )
      }
      if (chrome.showSelect) {
        GalleryChromeIconButton(
          icon = Icons.Rounded.CheckCircleOutline,
          contentDescription = stringResource(R.string.library_select),
          onClick = { grid.startSelecting() },
        )
      }
      MenuButton(chrome)
    }
  }
}

/** Il menu della vista ("Vista", il nome che cercano anche gli scenari di misura): icona piena se un filtro e' attivo. */
@Composable
private fun MenuButton(chrome: PrototypeChrome, modifier: Modifier = Modifier) {
  val description = stringResource(R.string.library_view_menu)
  GalleryChromeMenuAnchor(entries = chrome.menu, paneTitle = description, modifier = modifier) { open ->
    GalleryChromeIconButton(icon = if (chrome.filterActive) Icons.Rounded.FilterAlt else Icons.Rounded.MoreHoriz, contentDescription = description, onClick = open)
  }
}

/** La barra delle azioni della selezione nello stile della direzione. In C e' adattiva come la chrome in alto. */
@Composable
private fun PrototypeActionBar(
  design: LibraryDesign,
  state: LibraryTabState,
  backdrop: GlassBackdropState,
  pillVeil: Float,
  onDelete: () -> Unit,
  onHideGroups: () -> Unit,
) {
  val actions = selectionActions(state, onDelete, onHideGroups)
  val page = MaterialTheme.colorScheme.background
  var bounds by remember { mutableStateOf<Rect?>(null) }
  val style = when (design) {
    LibraryDesign.A -> GalleryChromeStyles.overPhoto()
    LibraryDesign.B -> GalleryChromeStyles.solid()
    else -> rememberAdaptiveStyle(state.grid, bounds, bounds?.let { page.copy(alpha = pillVeil) })
  }
  GalleryChromeActionBar(
    actions = actions,
    style = style,
    backdrop = backdrop,
    modifier = Modifier.onGloballyPositioned { bounds = it.inGrid(state.grid) }.galleryChromeTag("azioni"),
  )
}

/** Lo stile di maniglia e bolla dello scrubber per direzione: in C, la scelta adattiva per un contenuto qualsiasi. */
@Composable
private fun scrubberStyle(design: LibraryDesign): GalleryChromeStyle = when (design) {
  LibraryDesign.A -> GalleryChromeStyles.overPhoto()
  LibraryDesign.B -> GalleryChromeStyles.solid()
  else -> {
    val palette = GalleryChromeStyles.adaptivePalette()
    val darkTheme = GlassDefaults.isDarkSurface()
    val choice = remember(palette, darkTheme) { GalleryLegibility.adapt(palette.unknown, palette, previousDark = !darkTheme) }
    GalleryChromeStyles.adaptive(choice)
  }
}

/**
 * Le sfumature di bordo, **dentro** il fondale (cosi' il vetro sopra le vede): in A il nero sotto il testo bianco in alto;
 * in A e C il colore della pagina dietro la pillola in basso (la pillola dell'engine si legge sopra qualunque foto); in C
 * il colore della pagina anche in alto; in B la fascia opaca in basso su cui posa la pillola.
 */
@Composable
private fun BoxScope.EdgeScrims(design: LibraryDesign, topChrome: Dp, statusBar: Dp, bottomPadding: Dp, slotBottom: Dp, pillVeil: Float) {
  val scheme = MaterialTheme.colorScheme
  when (design) {
    LibraryDesign.A -> GalleryEdgeScrim(
      color = scheme.scrim,
      stops = listOf(0.dp to TopScrimA, topChrome to TextScrimA, topChrome + ScrimTailA to TailScrimA, topChrome + ScrimFadeA to 0f),
      modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topChrome + ScrimFadeA),
    )
    LibraryDesign.C -> {
      val stops = topEdgeStopsC(statusBar, topChrome)
      GalleryEdgeScrim(color = scheme.background, stops = stops, modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(stops.last().first))
    }
    else -> Unit
  }
  when (design) {
    LibraryDesign.B -> Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomPadding).background(scheme.background))
    else -> {
      val height = bottomPadding + BottomFade
      val pillTop = height - slotBottom - ActionBarHeight
      GalleryEdgeScrim(
        color = scheme.background,
        stops = listOf(0.dp to 0f, pillTop to pillVeil, height to maxOf(pillVeil, BottomVeilAtEdge)),
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height),
      )
    }
  }
}

/**
 * Il velo del colore della pagina dietro la pillola (A e C), all'altezza del suo bordo alto: il minimo che porta le
 * etichette della pillola dell'engine (`onSurface` sulla pellicola `floatingTint`) a 4,5:1 piu' il margine misurato sopra il
 * bianco e il nero pieni. Nel tema chiaro e' quasi niente; nel tema scuro, dove la pellicola grigia sopra una foto bianca
 * lasciava le etichette a 3,2:1 (misurato), circa 0,6.
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

/** Le opacita' della sfumatura della pagina in alto, in C: piena sotto la barra di stato, a meta' sotto i controlli. */
private fun topEdgeStopsC(statusBar: Dp, topChrome: Dp): List<Pair<Dp, Float>> =
  listOf(0.dp to EdgeTopC, statusBar to EdgeStatusC, topChrome to EdgeRowC, topChrome + EdgeFadeC to 0f)

/** L'opacita' di una sfumatura a [y], interpolando fra le tappe. */
private fun edgeAlphaAt(stops: List<Pair<Dp, Float>>, y: Dp): Float {
  if (y <= stops.first().first) return stops.first().second
  for (i in 1 until stops.size) {
    val (y1, a1) = stops[i]
    if (y <= y1) {
      val (y0, a0) = stops[i - 1]
      val t = if (y1 > y0) (y - y0) / (y1 - y0) else 1f
      return a0 + (a1 - a0) * t
    }
  }
  return stops.last().second
}

/** I limiti di un nodo nei pixel della griglia (che parte dall'origine della finestra della Libreria). */
private fun LayoutCoordinates.inGrid(grid: MediaGridState): Rect = boundsInWindow().translate(-grid.originInWindow)

/** Il menu della vista: i livelli (in A e C; B li ha nell'etichetta), il filtro, lo stile, il debug, le Impostazioni. */
@Composable
private fun prototypeMenu(state: LibraryTabState, includeLevels: Boolean, onOpenSettings: () -> Unit): List<GalleryMenuEntry> {
  val scope = rememberCoroutineScope()
  val grid = state.grid
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val levels = if (includeLevels) {
    val labels = levelLabels()
    GridLevel.entries.map { level ->
      GalleryFilterOption(labels[level.ordinal], selected = level == grid.level) {
        scope.launch { grid.selectLevel(level, reducedMotion) }
        scope.launch { state.preferences.setLevel(level) }
      }
    }
  } else {
    emptyList()
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
  val sizes = state.debug?.let { hooks ->
    hooks.sizes.map { size ->
      val label = if (size == 0) stringResource(R.string.library_debug_phone) else stringResource(R.string.library_debug_synthetic, GalleryNumbers.format(size))
      GalleryFilterOption(label, selected = size == hooks.current) { hooks.select(size) }
    }
  }.orEmpty()
  return galleryFilterMenuEntries(
    groups = listOf(levels, filters, styles, designOptions(state), sizes),
    trailing = listOf(GalleryMenuEntry.Item(label = stringResource(R.string.library_open_settings), onClick = onOpenSettings, icon = Icons.Rounded.Settings)),
  )
}

/** Solo debug (G1): le voci "Prototipo grafico: …" del menu della vista. */
@Composable
internal fun designOptions(state: LibraryTabState): List<GalleryFilterOption> = state.debug?.let { hooks ->
  LibraryDesign.entries.map { design -> GalleryFilterOption(design.label(), selected = design == hooks.design) { hooks.chooseDesign(design) } }
}.orEmpty()

@Composable
private fun levelLabels(): List<String> = listOf(
  stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_years),
  stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_months),
  stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_days),
  stringResource(dev.pampa.mycloudgallery.core.designsystem.R.string.level_all),
)

/**
 * In A la chrome in alto e' scura anche nel tema chiaro: le icone della barra di stato diventano chiare finche' A e' sullo
 * schermo, e tornano quelle del tema quando se ne va.
 */
@Composable
private fun LightStatusIcons() {
  val view = LocalView.current
  val dark = GlassDefaults.isDarkSurface()
  DisposableEffect(view, dark) {
    val window = view.context.findActivity()?.window
    val controller = window?.let { WindowCompat.getInsetsController(it, view) }
    controller?.isAppearanceLightStatusBars = false
    onDispose { controller?.isAppearanceLightStatusBars = !dark }
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

/** La fessura fra le celle in A: una parete di foto, come Foto e Galleria. */
private val PhotoWallGap = 1.dp

private val TitleStart = 20.dp
private val BarRowHeight = 56.dp
private val BarEndInset = 4.dp
private val BarTitleGap = 4.dp
private val SectionRowHeight = 36.dp
private val SectionRowGap = 8.dp
private val ChipOuterPadding = 4.dp
private val LabelPaddingH = 14.dp
private val LabelPaddingV = 6.dp

// A: la sfumatura nera in alto. Sotto il testo resta >= 0,58 (`OverPhotoTextScrim`): il bianco sopra una foto bianca fa 5,3:1.
private const val TopScrimA = 0.64f
private const val TextScrimA = GalleryChromeStyles.OverPhotoTextScrim
private const val TailScrimA = 0.24f
private val ScrimTailA = 28.dp
private val ScrimFadeA = 64.dp

// C: la sfumatura della pagina in alto. Densa solo sotto la barra di stato (le icone di sistema seguono il tema e
// non si adattano), leggera sotto le capsule: il fondo delle capsule lo garantisce la loro pellicola adattiva, e un velo
// forte farebbe seguire sempre il tema alla polarita' (misurato con il velo a 0,82-0,50: non cambiava mai).
private const val EdgeTopC = 0.85f
private const val EdgeStatusC = 0.55f
private const val EdgeRowC = 0.15f
private val EdgeFadeC = 44.dp

// A e C: la sfumatura della pagina in basso, dietro la pillola (l'opacita' al bordo della pillola e' calcolata).
private val BottomFade = 32.dp
// Il minimo al bordo della pillola non e' solo aritmetica: l'etichetta della scheda scelta sta sul pomello, tinto
// d'accento dall'engine, e sopra una foto qualunque scendeva a 3,7:1 con il velo calcolato per le altre (misurato).
private const val MinBottomVeil = 0.55f
private const val MaxBottomVeil = 0.90f
private const val BottomVeilAtEdge = 0.90f

/** Da quante schermate di distanza dai piu' recenti compare "vai a oggi". */
private const val TodayAfterScreens = 2f
