package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryNavigationIcon
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTopBar
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryFilterMenu
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryFilterOption
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.grid.GridText
import dev.pampa.mycloudgallery.feature.library.grid.MediaGrid
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridStickyHeader
import kotlinx.coroutines.launch

/**
 * La scheda **Libreria** (docs/fase3-progetto.md §2.4): la griglia del telefono **a tutto schermo** (§1.4: la griglia e' la
 * sua lista, niente `FluidScreen`), con la barra di vetro in cima, i segmenti dei livelli (o, selezionando, la barra delle
 * azioni) in basso, e il riepilogo e lo stato globale in fondo alla lista, sotto i piu' recenti.
 *
 * - [contentPadding]: lo spazio che la pillola delle schede occupa in basso (il contenuto scorre sotto, rifratto);
 * - [backdropOut]: la Libreria consegna a `:app` il fondale di vetro che disegna (la griglia), cosi' la pillola lo
 *   rifrange senza far parte della scheda;
 * - [onOpenViewer]: il viewer e' una sovrapposizione ospitata da `:app` sopra la pillola (D12): la griglia non lo
 *   conosce, manda la richiesta con la geometria della miniatura (`ViewerAnchor`);
 * - [onOpenSettings]: dal menu della vista;
 * - [statusFooter]: l'indicatore globale della Fase 2, in fondo.
 *
 * Gli stati (§2.4): nessun permesso, accesso parziale (E-P1), foto in attesa (E-P21), prima lettura, Libreria vuota,
 * filtro senza risultati. I pop-up (menu, conferma dell'eliminazione) usano il `FluidGlassModalHost` dello scope; se
 * `:app` non ne ha uno, la Libreria ne mette uno suo.
 */
@Composable
fun LibraryTab(
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  onOpenSettings: () -> Unit,
  statusFooter: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  state: LibraryTabState = rememberEmptyLibraryTabState(),
) {
  LibraryModalHost(modifier) {
    LibraryScreen(contentPadding, backdropOut, onOpenViewer, onOpenSettings, statusFooter, state)
  }
}

/** Il punto in cui i pop-up della Libreria si aprono: quello di `:app` se c'e', altrimenti uno sopra la scheda. */
@Composable
private fun LibraryModalHost(modifier: Modifier, content: @Composable () -> Unit) {
  if (LocalFluidGlassModalHostState.current != null) {
    Box(modifier = modifier.fillMaxSize()) { content() }
    return
  }
  val host = rememberFluidGlassModalHostState()
  val backdrop = rememberGlassBackdrop()
  CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
    Box(modifier = modifier.fillMaxSize()) {
      Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop).fluidGlassModalObscured()) { content() }
      FluidGlassModalHost(state = host, backdrop = backdrop)
    }
  }
}

@Composable
private fun LibraryScreen(
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  onOpenSettings: () -> Unit,
  statusFooter: @Composable () -> Unit,
  state: LibraryTabState,
) {
  val grid = state.grid
  val phone by state.phone.collectAsState()
  val backdrop = rememberGlassBackdrop()
  SideEffect { backdropOut(backdrop) }
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion

  var barHeightPx by remember { mutableIntStateOf(0) }
  var noticesHeightPx by remember { mutableIntStateOf(0) }
  var bottomBarHeightPx by remember { mutableIntStateOf(0) }
  var deleting by remember { mutableStateOf(false) }
  var hidingGroups by remember { mutableStateOf(false) }

  LibraryEffects(state)

  // E-P18: la posizione sopravvive alla ricreazione dell'activity come elemento ancorato, mai come indice.
  val keeper = rememberSaveable(saver = positionSaver(grid)) { PositionKeeper(null) }
  SideEffect { keeper.restoreOnce(grid) }

  val barHeight = with(density) { barHeightPx.toDp() }
  val controlsBottom = (barHeight - FluidScreenDefaults.GlassFadeTail).coerceAtLeast(0.dp)
  val noticesHeight = with(density) { noticesHeightPx.toDp() }
  val topPadding = if (noticesHeightPx > 0) maxOf(barHeight, controlsBottom + noticesHeight + NoticeGap) else barHeight
  val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
  val pillInset = contentPadding.calculateBottomPadding()
  val bottomPadding = pillInset + ChromeGap + bottomBarHeight + ChromeGap

  // La linea di aggancio dell'intestazione, dall'altezza **misurata** della barra (normale, di selezione o con il testo
  // grande): sotto la riga dei controlli (la barra finisce con la coda di vetro `GlassFadeTail`, che scorre sopra il
  // contenuto), o sotto gli avvisi.
  val stickyTop = if (noticesHeightPx > 0) topPadding else controlsBottom

  val view = grid.view
  val showPermission = phone.access == MediaAccess.NONE
  val empty = view.size == 0

  Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      when {
        showPermission -> LibraryPermissionState(
          onAllow = { state.commands.requestMediaAccess() },
          modifier = Modifier.padding(top = barHeight, bottom = pillInset),
          footer = statusFooter,
        )
        empty && phone.firstRead != null -> LibraryFirstReadState(
          progress = phone.firstRead,
          modifier = Modifier.padding(top = barHeight, bottom = pillInset),
          footer = statusFooter,
        )
        empty && state.filter != LibraryFilter.ALL -> LibraryFilterEmptyState(
          filter = state.filter,
          onShowAll = { scope.launch { state.chooseFilter(LibraryFilter.ALL) } },
          modifier = Modifier.padding(top = barHeight, bottom = pillInset),
        )
        empty -> LibraryEmptyState(modifier = Modifier.padding(top = barHeight, bottom = pillInset), footer = statusFooter)
        else -> MediaGrid(
          state = grid,
          contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
          onOpen = { id -> onOpenViewer(ViewerRequest(items = state.viewerItems, start = id, anchor = grid.viewerAnchor, secure = false)) },
          footer = { LibraryFooter(state, phone, statusFooter) },
          backdrop = backdrop,
          stickyTop = stickyTop,
          onLevelChanged = { level -> scope.launch { state.preferences.setLevel(level) } },
          stickyHeader = false,
        )
      }
    }

    // Gli avvisi, sotto la barra: accesso parziale (E-P1) e foto in attesa (E-P21).
    if (!showPermission) {
      LibraryNotices(
        phone = phone,
        commands = state.commands,
        backdrop = backdrop,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = controlsBottom + NoticeGap, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter)
          .onSizeChanged { noticesHeightPx = it.height },
      )
    }

    // In basso, sopra la pillola: i segmenti dei livelli, oppure la barra delle azioni della selezione.
    if (!showPermission && !empty) {
      LibraryBottomChrome(
        state = state,
        backdrop = backdrop,
        reducedMotion = reducedMotion,
        onDelete = { deleting = true },
        onHideGroups = { hidingGroups = true },
        onBarHeight = { bottomBarHeightPx = it },
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .padding(bottom = pillInset + ChromeGap, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter),
      )
    }

    LibraryTopBar(
      state = state,
      backdrop = backdrop,
      onOpenSettings = onOpenSettings,
      showSelect = !showPermission && !empty,
      showMenu = !showPermission,
      modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { barHeightPx = it.height }.testTag(LibraryTopBarTag),
    )

    // L'intestazione agganciata sta sopra la barra: e' agganciata a lei (sotto la riga dei controlli, o sotto gli avvisi).
    // Solo a barra misurata: prima della prima misura la linea non si conosce, e l'intestazione non si indovina.
    if (!showPermission && !empty && barHeightPx > 0) {
      MediaGridStickyHeader(state = grid, stickyTop = stickyTop)
    }

    LibraryDeleteSheet(
      visible = deleting,
      state = state,
      onDismiss = { deleting = false },
    )

    LibraryHideGroupSheet(
      visible = hidingGroups,
      state = state,
      onDismiss = { hidingGroups = false },
    )
  }
}

/** Il tag della barra superiore della Libreria (i test ne misurano i controlli). */
internal const val LibraryTopBarTag: String = "libreria-barra-superiore"

@Composable
private fun LibraryTopBar(
  state: LibraryTabState,
  backdrop: GlassBackdropState,
  onOpenSettings: () -> Unit,
  showSelect: Boolean,
  showMenu: Boolean,
  modifier: Modifier = Modifier,
) {
  val grid = state.grid
  val scope = rememberCoroutineScope()
  val selecting = grid.selecting
  val selected by grid.selection.selected.collectAsState()
  // Al livello "Tutte" la data sta nella barra (non ci sono intestazioni): il mese della riga in cima.
  val topMonth by remember(grid) {
    derivedStateOf {
      val l = grid.layout
      if (l == null || l.rowCount == 0 || l.spec.level != GridLevel.ALL) null else grid.scrubber.label(l, grid.rowAtTop(l))
    }
  }
  val filterLabel = if (state.filter == LibraryFilter.ALL) null else filterLabel(state.filter)
  val subtitle = listOfNotNull(filterLabel, topMonth?.let { GridText.month(it) }).joinToString(" · ").ifEmpty { null }
  val title = when {
    !selecting -> stringResource(R.string.library_title)
    selected.isEmpty -> stringResource(R.string.library_select_items)
    else -> pluralStringResource(R.plurals.library_selected, selected.size, selected.size)
  }
  GalleryTopBar(
    title = title,
    backdrop = backdrop,
    modifier = modifier,
    subtitle = if (selecting) null else subtitle,
    onNavigate = if (selecting) ({ grid.stopSelecting() }) else null,
    navigationIcon = GalleryNavigationIcon.Close,
    actions = {
      if (!selecting && showSelect) {
        GalleryBarButton(
          icon = Icons.Rounded.CheckCircleOutline,
          contentDescription = stringResource(R.string.library_select),
          onClick = { grid.startSelecting() },
        )
      }
      if (!selecting && showMenu) {
        LibraryViewMenu(state = state, onOpenSettings = onOpenSettings, onChoose = { action -> scope.launch { action() } })
      }
    },
  )
}

/** Il menu della vista: filtro (D16), stile (D4), le Impostazioni; in debug la dimensione della Libreria sintetica. */
@Composable
private fun LibraryViewMenu(state: LibraryTabState, onOpenSettings: () -> Unit, onChoose: (suspend () -> Unit) -> Unit) {
  val filters = if (state.timeline != null) {
    LibraryFilter.entries.map { filter ->
      GalleryFilterOption(filterLabel(filter), selected = filter == state.filter) { onChoose { state.chooseFilter(filter) } }
    }
  } else {
    emptyList()
  }
  val styles = GridStyle.entries.map { style ->
    GalleryFilterOption(
      label = stringResource(if (style == GridStyle.SQUARE) R.string.library_style_square else R.string.library_style_justified),
      selected = style == state.grid.style,
    ) { onChoose { state.chooseStyle(style) } }
  }
  val debug = state.debug?.let { hooks ->
    hooks.sizes.map { size ->
      // 0 = la Libreria vera del telefono (P11: la sintetica ha un database a parte e si sceglie da qui).
      val label = if (size == 0) {
        stringResource(R.string.library_debug_phone)
      } else {
        stringResource(R.string.library_debug_synthetic, String.format(java.util.Locale.ITALIAN, "%,d", size))
      }
      GalleryFilterOption(label, selected = size == hooks.current) {
        hooks.select(size)
      }
    }
  }.orEmpty()
  GalleryFilterMenu(
    groups = listOf(filters, styles, debug),
    contentDescription = stringResource(R.string.library_view_menu),
    active = state.filter != LibraryFilter.ALL,
    trailing = listOf(
      GalleryMenuEntry.Item(label = stringResource(R.string.library_open_settings), onClick = onOpenSettings, icon = Icons.Rounded.Settings),
    ),
  )
}

@Composable
private fun LibraryBottomChrome(
  state: LibraryTabState,
  backdrop: GlassBackdropState,
  reducedMotion: Boolean,
  onDelete: () -> Unit,
  onHideGroups: () -> Unit,
  onBarHeight: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val grid = state.grid
  val scope = rememberCoroutineScope()
  val farFromToday by remember(grid) { derivedStateOf { grid.screensFromNewest() > TodayAfterScreens } }
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ChromeGap)) {
    // "Oggi": compare lontano dai piu' recenti (§2.4), solo fuori dalla selezione.
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
      TodaySlot(visible = farFromToday && !grid.selecting, backdrop = backdrop, onClick = { scope.launch { grid.scrollToNewest() } })
    }
    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { onBarHeight(it.height) }, contentAlignment = Alignment.Center) {
      AnimatedContent(
        targetState = grid.selecting,
        transitionSpec = { fadeIn(FluidMotion.fadeIn()) togetherWith fadeOut(FluidMotion.fadeOut()) },
        label = "library bottom chrome",
      ) { selecting ->
        if (selecting) {
          LibrarySelectionBar(state = state, backdrop = backdrop, onDelete = onDelete, onHideGroups = onHideGroups)
        } else {
          LibraryLevelBar(
            level = grid.level,
            backdrop = backdrop,
            onSelect = { level ->
              scope.launch { grid.selectLevel(level, reducedMotion) }
              scope.launch { state.preferences.setLevel(level) }
            },
          )
        }
      }
    }
  }
}

/** "Oggi" compare e scompare sfumando: e' chrome, non una pagina. */
@Composable
private fun TodaySlot(visible: Boolean, backdrop: GlassBackdropState, onClick: () -> Unit) {
  AnimatedVisibility(visible = visible, enter = fadeIn(FluidMotion.fadeIn()), exit = fadeOut(FluidMotion.fadeOut())) {
    LibraryTodayButton(backdrop = backdrop, onClick = onClick)
  }
}

/** Uno spazio fra i pezzi di chrome in basso (segmenti, "Oggi", pillola). */
internal val ChromeGap: Dp = 8.dp

/** Lo spazio fra la barra e gli avvisi sotto di lei. */
private val NoticeGap: Dp = 8.dp

/** Da quante schermate di distanza dai piu' recenti compare "Oggi". */
private const val TodayAfterScreens = 2f
