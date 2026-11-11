package dev.pampa.mycloudgallery.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.VpnKeyOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarInlineBehavior
import dev.antigravity.fluidengine.ui.fluid.rememberFluidFloatingTabBarScrollConnection
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GallerySearchBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GallerySearchCircle
import dev.pampa.mycloudgallery.core.designsystem.chrome.gallerySearchPillAlpha
import dev.pampa.mycloudgallery.core.designsystem.chrome.gallerySearchPillScale
import dev.pampa.mycloudgallery.core.designsystem.chrome.rememberGallerySearchBarState
import dev.pampa.mycloudgallery.core.designsystem.chrome.rememberGallerySearchLift
import dev.pampa.mycloudgallery.core.designsystem.instant.onFirstDraw
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlotVisibility
import dev.pampa.mycloudgallery.core.designsystem.chrome.LocalGalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterEntry
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.feature.library.library.LibraryTab
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.library.rememberEmptyLibraryTabState
import dev.pampa.mycloudgallery.feature.search.contract.SearchSelection
import dev.pampa.mycloudgallery.feature.search.rememberSearchLine
import dev.pampa.mycloudgallery.feature.search.searchFieldHint
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.status.GlobalStatus
import kotlinx.coroutines.launch

/** Cio' che lo scaffold passa alle schede Album e Raccolte: lo spazio della pillola, il fondale da rifrangere, l'indicatore. */
@Immutable
class CollectionsSlot(
  val contentPadding: PaddingValues,
  val backdropOut: (GlassBackdropState) -> Unit,
  val statusHeader: @Composable () -> Unit,
)

/**
 * Lo scaffold della galleria (G2, come in Foto di Apple): le tre schede **Libreria · Album · Raccolte** nella pillola di vetro del design
 * system, il **cerchio di Cerca** accanto alla pillola, l'indicatore di stato globale e l'accesso alle Impostazioni.
 *
 * **Fase 3: la Libreria e' a tutto schermo e il viewer sta sopra la pillola** (docs/fase3-progetto.md §1.4, D12).
 * - La scheda Libreria non usa `FluidScreen`: la griglia e' la sua lista, con la propria chrome di vetro adattivo (G2,
 *   docs/grafica-progetto.md §12) e lo stato della rete e del NAS in fondo. La pillola si ripiega scorrendo la Libreria. Gli Album
 *   ([albums], `AlbumsTabHost`) e le Raccolte ([collections], P10) sono pagine `FluidScreen` (le Raccolte, sul tablet, due colonne).
 * - **Cerca** (H4 della fase H, 11/11: «stile ricerca in app Apple») non e' una pagina: toccato il cerchio accanto alla pillola, il cerchio **si
 *   allarga di lato** nella barra di ricerca ([search]) mentre la pillola esce nello stesso movimento, il campo prende il fuoco e la barra **sale con
 *   la tastiera**. Dietro resta la Libreria (anche partendo da Album o Raccolte): in alto la barra dei tre suggerimenti prende il posto dei pezzi di
 *   vetro, e scrivendo i risultati prendono il posto della griglia. Indietro o la X chiudono in un passo: la barra torna cerchio, la pillola rientra,
 *   la scheda di partenza e' dov'era. Non e' una rotta: vale uguale sul tablet e con la pillola ripiegata.
 * - La pillola e' **una sola** e sta fuori dalle schede, cosi' la lente che scivola sotto la scheda scelta non si
 *   ricrea passando dalla Libreria alle altre. Il fondale che rifrange arriva dalla scheda in primo piano.
 * - **In selezione la barra delle azioni prende il posto della pillola** (B3 della fase grafica): la pillola e il cerchio escono come la
 *   capsula `ReplacingTabBar` dell'engine e la barra entra nello stesso posto. Vale per la Libreria e per ogni griglia dentro una scheda (il
 *   pannello delle Raccolte sul tablet), che lo chiede con `TakeGalleryTabBarSlot`.
 * - Il viewer (`ViewerOverlay`) e' una sovrapposizione: lo ospita [GalleryRoot] sopra la navigazione (con l'host dei
 *   pop-up); senza (i test della sola scheda) lo scaffold ne ospita uno suo. Toccare di nuovo "Libreria" riporta in
 *   fondo, sui piu' recenti (D1).
 *
 * @param libraryState cio' che la Libreria mostra: dal `LibraryViewModel` (grafo vero, P11); nei test i fake.
 * @param libraryViewer i servizi del viewer per la Libreria (dettagli, azioni, intent); null = solo i pixel dello stato.
 * @param collections la scheda Raccolte; null = una pagina vuota con il titolo (solo le prove che non guardano le schede: gli screenshot passano
 *   dallo scaffold delle prove, `GalleryScaffold`, con le schede vere; in produzione `AppNavHost` le passa sempre).
 * @param albums la scheda Album (`AlbumsTabHost`); null = una pagina vuota con il titolo.
 * @param search la ricerca dentro la Libreria; null = il cerchio apre la barra, ma senza suggerimenti ne' risultati (le prove che non la guardano).
 * @param searchOpen la ricerca e' aperta sopra [selectedTab]; [onOpenSearch] e [onCloseSearch] la aprono (il cerchio, "Trova simili") e la chiudono
 *   (Indietro, la X). [searchFocus]: aperta dal cerchio il campo prende il fuoco e la tastiera sale; [onSearchFocused] consuma la richiesta.
 *
 * Una navigazione sola su ogni formato (telefono e tablet): lo spazio in piu' del tablet si usa dentro le pagine.
 */
@Composable
fun MainScaffold(
  status: GlobalStatus,
  selectedTab: GalleryTab,
  onSelectTab: (GalleryTab) -> Unit,
  onStatusClick: () -> Unit,
  onOpenSettings: () -> Unit,
  onBackupStatusClick: () -> Unit = {},
  // NA-9: la capsula del NAS apre i suoi dettagli (la Diagnostica), non l'azione della rete (WireGuard, la password).
  onNasStatusClick: () -> Unit = onStatusClick,
  libraryState: LibraryTabState = rememberEmptyLibraryTabState(),
  libraryViewer: ViewerServices? = null,
  collections: (@Composable (CollectionsSlot) -> Unit)? = null,
  /** G2: la scheda Album (`AlbumsTabHost`); null = una pagina vuota (le prove senza le schede). */
  albums: (@Composable (CollectionsSlot) -> Unit)? = null,
  /** H4: la ricerca dentro la Libreria (la barra in basso, i suggerimenti in alto, i risultati al posto della griglia). */
  search: ScaffoldSearch? = null,
  searchOpen: Boolean = false,
  onOpenSearch: () -> Unit = {},
  onCloseSearch: () -> Unit = {},
  searchFocus: Boolean = false,
  onSearchFocused: () -> Unit = {},
) {
  // HR: osserviamo solo inizio/fine dello scorrimento, compresa l'inerzia, senza ricomporre la griglia.
  val workLease = remember { dev.pampa.mycloudgallery.work.UiWorkActivity.budget.lease() }
  LaunchedEffect(libraryState.grid, workLease) {
    androidx.compose.runtime.snapshotFlow { libraryState.grid.listState.isScrollInProgress }.collect { workLease.set(it) }
  }
  LaunchedEffect(selectedTab, searchOpen) { dev.pampa.mycloudgallery.work.UiWorkActivity.budget.interacted() }
  DisposableEffect(workLease) { onDispose { workLease.close() } }
  var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
  val rootViewer = LocalViewerHost.current
  val ownViewer = if (rootViewer == null) remember { ViewerHostState(null) } else null
  val viewer = rootViewer ?: checkNotNull(ownViewer)
  val scope = rememberCoroutineScope()
  // Fase 10 (P5, D13 (5)): "Affianca" si ricorda per profilo (le preferenze della Libreria stanno nei dati del profilo): il viewer la legge dall'ospite. Il viewer affiancato non
  // sopravvive a una scheda diversa dalla Libreria (ne' a Cerca, che la copre) ne' all'uscita da questa pagina (impostazioni, altre rotte): il suo posto e' accanto alla griglia.
  val dockPreference by libraryState.preferences.viewerDocked.collectAsState()
  LaunchedEffect(viewer, dockPreference) { viewer.dockChoice = dockPreference }
  SideEffect { viewer.saveDockChoice = { docked -> scope.launch { libraryState.preferences.setViewerDocked(docked) } } }
  LaunchedEffect(selectedTab, searchOpen) { if ((selectedTab != GalleryTab.Library || searchOpen) && viewer.docked) viewer.close() }
  DisposableEffect(viewer) { onDispose { if (viewer.docked) viewer.close() } }
  // La piega della pillola allo scorrimento (G2, scelta 5 dell'utente): la Libreria attacca la connessione alla griglia. In
  // una griglia che si apre in fondo, "verso i piu' vecchi" e' il dito che scende: la pillola si ripiega li'
  // (`OnScrollUp`) e torna tornando verso i recenti.
  val tabBarScroll = rememberFluidFloatingTabBarScrollConnection(inlineBehavior = FluidFloatingTabBarInlineBehavior.OnScrollUp)
  LaunchedEffect(selectedTab, searchOpen) { tabBarScroll.expand() }
  // Con la ricerca aperta Indietro la chiude e torna alla scheda di prima (registrato prima delle schede: i loro BackHandler, come la selezione, vincono).
  BackHandler(enabled = searchOpen) { onCloseSearch() }
  // H4: con la tastiera su, Indietro chiude la ricerca in un passo solo (non prima la tastiera e poi la ricerca).
  SearchImeBack(enabled = searchOpen) { onCloseSearch() }

  // Il posto della pillola (B3): lo prende chi, dentro una scheda, mostra la barra delle azioni di una selezione.
  val tabBarSlot = remember { GalleryTabBarSlot() }
  val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  // H4: con la ricerca aperta dietro c'e' sempre la Libreria, da qualunque scheda si sia partiti.
  val shownTab = if (searchOpen) GalleryTab.Library else selectedTab
  val selecting = shownTab == GalleryTab.Library && libraryState.selectionActive
  // Lo spazio della pillola, che sta sopra la barra di navigazione; in selezione la pillola non c'e' e la barra delle
  // azioni prende il suo posto.
  val pillPadding = PaddingValues(
    bottom = navigationBottom + if (selecting) FluidFloatingTabBarDefaults.BottomMargin - FluidFloatingTabBarDefaults.Spacing else GalleryTabBarDefaults.ContentInset,
  )
  val services = libraryViewer ?: remember(libraryState) {
    ViewerServices(
      images = libraryState.viewerImages,
      thumbnails = libraryState.grid.loader,
      placeholders = libraryState.grid.placeholders,
      places = libraryState.grid.placeNames,
    )
  }
  // Ricordato: lo scaffold si ricompone quando entra o esce una selezione (la pillola), e le schede non devono ricomporsi con lui.
  val pageSlot = remember(status, onStatusClick) {
    CollectionsSlot(
      contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
      backdropOut = { backdrop = it },
      // Lo stesso stato della rete, nello stesso linguaggio, come prima riga dell'elenco (G2).
      statusHeader = { NetworkStatusEntry(status = status, onClick = onStatusClick, alignment = Alignment.Start) },
    )
  }

  // GF-7 (K3a): ogni scheda tiene cio' che ricorda mentre e' coperta (da un'altra scheda, o dalla Libreria che fa da base alla ricerca). Senza, la scheda
  // che usciva dalla composizione ripartiva da capo: aprire Cerca o passare a un'altra scheda riportava Album e Raccolte in cima e il pannello del tablet
  // alla prima scelta.
  // La Libreria ricorda gia' la sua griglia (lo stato e' fuori, nel ViewModel) e qui ritrova anche la posizione ancorata a un media, che serve
  // solo a una griglia nuova (il processo ripartito): la griglia viva che torna e' gia' dove era e non si sposta (K6, GS-10).
  val pageStates = rememberSaveableStateHolder()
  val pageKey = shownTab.route

  // H4: la barra di ricerca. E' composta mentre la ricerca e' aperta e mentre torna cerchio; la pillola esce mentre la barra si apre e se ne va del tutto a
  // barra aperta (non resta sotto a prendere i tocchi), e rientra mentre la barra si richiude. Lo stesso passo per tutte e due (`FluidMotion.standard`).
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val searchCircle = remember { GallerySearchCircle() }
  val searchBar = rememberGallerySearchBarState()
  val searchLift = rememberGallerySearchLift()
  var barAlive by remember { mutableStateOf(searchOpen) }
  SideEffect { if (searchOpen && !barAlive) barAlive = true }
  val pillProgress = animateFloatAsState(
    targetValue = if (searchOpen) 1f else 0f,
    animationSpec = if (reduced) snap() else FluidMotion.standard(),
    label = "pillola e ricerca",
  )
  val pillGone by remember { derivedStateOf { pillProgress.value >= 1f } }
  val pillOrigin = remember { PillOrigin() }
  val focusManager = LocalFocusManager.current
  var searchFocused by remember { mutableStateOf(false) }
  // Il campo lascia il fuoco (e la tastiera scende) quando si entra in selezione e quando si apre il viewer: non resta sopra la barra delle azioni o la foto.
  LaunchedEffect(selecting, searchOpen) { if (selecting && searchOpen) focusManager.clearFocus() }
  LaunchedEffect(viewer, searchOpen) {
    if (searchOpen) snapshotFlow { viewer.request != null }.collect { opened -> if (opened) focusManager.clearFocus() }
  }
  val openSelection = remember(libraryState) {
    SearchSelection(model = libraryState.grid.selection, selecting = { libraryState.grid.selecting }, begin = { libraryState.grid.startSelecting() })
  }
  val searchOpenState = rememberUpdatedState(searchOpen)
  val libraryLayer = remember(search, openSelection, searchLift) {
    search?.let { s ->
      ScaffoldLibrarySearch(
        search = s,
        open = searchOpenState,
        selection = openSelection,
        lift = searchLift,
        onOpen = { view, media, anchor ->
          focusManager.clearFocus()
          s.onResultOpened()
          s.actions.openResults(view, media, anchor)
        },
        onUserScroll = { if (searchFocused) focusManager.clearFocus() },
      )
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalGalleryTabBarSlot provides tabBarSlot) {
      pageStates.SaveableStateProvider(pageKey) {
        when {
          shownTab == GalleryTab.Library -> LibraryTab(
            contentPadding = pillPadding,
            backdropOut = { backdrop = it },
            onOpenViewer = { viewer.open(it, services, fromLibrary = true) },
            onOpenSettings = onOpenSettings,
            // B2 (beta): la rete non e' piu' una voce in fondo alla Libreria, e' uno degli stati della nuvola in alto (LibraryCloudNetwork.kt).
            network = libraryNetworkOf(status),
            onNetworkClick = onStatusClick,
            state = libraryState,
            tabBarScroll = tabBarScroll,
            // I dettagli del NAS (e "Ho controllato, continua" dei lavori fermi) stanno in Diagnostica, come quelli della rete.
            onNasStatusClick = onNasStatusClick,
            // Fase 5A (D16): la terza voce del piede apre la pagina Backup.
            onBackupStatusClick = onBackupStatusClick,
            // H4: la ricerca dentro la Libreria (i suggerimenti in alto, i risultati al posto della griglia).
            search = libraryLayer,
          )
          selectedTab == GalleryTab.Albums && albums != null -> albums(pageSlot)
          selectedTab == GalleryTab.Collections && collections != null -> collections(pageSlot)
          else -> EmptyTab(
            title = stringResource(selectedTab.label),
            status = status,
            onStatusClick = onStatusClick,
            onOpenSettings = onOpenSettings,
            backdropOut = { backdrop = it },
          )
        }
      }
    }

    // La pillola e il cerchio di Cerca; in selezione escono e la barra delle azioni entra nello stesso posto (stessa animazione, quella della
    // capsula `ReplacingTabBar` dell'engine). H4: mentre la barra di ricerca si apre la pillola si raccoglie verso il cerchio e sparisce.
    if (!searchOpen || !pillGone) {
      GalleryTabBarSlotVisibility(visible = !selecting && !tabBarSlot.taken, modifier = Modifier.align(Alignment.BottomCenter)) {
        GalleryTabBar(
          selected = selectedTab,
          onSelect = onSelectTab,
          backdrop = backdrop,
          onReselect = { tab -> if (tab == GalleryTab.Library) scope.launch { libraryState.grid.scrollToNewest() } },
          scrollConnection = tabBarScroll,
          onOpenSearch = onOpenSearch,
          searchCircle = searchCircle,
          modifier = Modifier
            .onPlaced { pillOrigin.coordinates = it }
            .graphicsLayer {
              val p = pillProgress.value
              if (p > 0f) {
                alpha = gallerySearchPillAlpha(p)
                val scale = gallerySearchPillScale(p)
                scaleX = scale
                scaleY = scale
                transformOrigin = pillOrigin.toward(searchCircle)
              }
            },
        )
      }
    }

    // H4: la barra di ricerca nel posto della pillola (in selezione esce e la barra delle azioni prende il posto, come per la pillola).
    if (searchOpen || barAlive) {
      GalleryTabBarSlotVisibility(visible = !selecting && !tabBarSlot.taken, modifier = Modifier.align(Alignment.BottomCenter)) {
        val line = search?.let { rememberSearchLine(it.source) }
        GallerySearchBar(
          open = searchOpen,
          query = search?.source?.query.orEmpty(),
          onQueryChange = { search?.handlers?.onQueryChange(it) },
          onClose = onCloseSearch,
          onSearch = { search?.handlers?.onSubmit() },
          onCollapsed = { barAlive = false },
          lift = searchLift,
          circle = searchCircle,
          state = searchBar,
          backdrop = backdrop,
          placeholder = searchFieldHint(),
          focus = searchFocus,
          onFocused = onSearchFocused,
          onFocusChanged = { searchFocused = it },
          message = line?.text,
          onMessageClick = line?.action?.let { action -> { search?.runLine(action) } },
          messageActionLabel = line?.actionLabel,
          // Avvio istantaneo: l'apertura di Cerca si chiude al primo fotogramma della barra.
          modifier = Modifier.onFirstDraw { AvvioTrace.pageDrawn(AvvioTrace.PAGE_SEARCH) },
        )
      }
    }

    if (ownViewer != null) ViewerLayer(ownViewer)
  }
}

/**
 * Lo stato della rete: la frase dell'indicatore globale ([GlobalStatus]) come voce di stato (`GalleryFooterEntry`): nel
 * piede della Libreria sopra quella del NAS, in cima alle Raccolte e alle altre schede, con un glifo per stato. Le coppie
 * di colori sono quelle verificate su ogni accento (`GalleryFooterStatusTest`): il `FluidStatusBadge` dell'engine, che
 * c'era prima, nel tono di avviso del tema chiaro tiene il testo a 3,5:1 (G2, misurato).
 * Una frase con il punto ("Rete non riconosciuta ·
 * nessuna connessione al NAS") va in capsula la prima parte e sotto il resto, cosi' la capsula non si tronca; TalkBack
 * dice la frase intera. Il tono non e' l'unico segnale: ogni stato ha la sua frase e il suo glifo. Toccandola si apre
 * cio' che [GlobalStatus.action] dice.
 */
@Composable
private fun NetworkStatusEntry(status: GlobalStatus, onClick: () -> Unit, alignment: Alignment.Horizontal = Alignment.CenterHorizontally) {
  val tone = when (status.tone) {
    FluidTone.Success, FluidTone.Primary -> GalleryFooterTone.Calm
    FluidTone.Info -> GalleryFooterTone.Working
    FluidTone.Neutral -> GalleryFooterTone.Waiting
    FluidTone.Warning -> GalleryFooterTone.Attention
    FluidTone.Danger -> GalleryFooterTone.Alarm
  }
  val glyph = when (status) {
    GlobalStatus.HOME_CONNECTED -> Icons.Rounded.CheckCircle
    GlobalStatus.HOME_IDLE -> Icons.Rounded.Home
    GlobalStatus.AWAY_VPN_WIFI, GlobalStatus.AWAY_VPN_MOBILE, GlobalStatus.FOREIGN_VPN -> Icons.Rounded.VpnLock
    GlobalStatus.ROAMING -> Icons.Rounded.Public
    GlobalStatus.VPN_OFF -> Icons.Rounded.VpnKeyOff
    GlobalStatus.UNTRUSTED -> Icons.Rounded.GppMaybe
    GlobalStatus.WAKING_NAS -> null
    GlobalStatus.NAS_UNREACHABLE -> Icons.Rounded.CloudOff
    GlobalStatus.LOCAL_NETWORK_BLOCKED -> Icons.Rounded.Block
    GlobalStatus.NEEDS_PASSWORD -> Icons.Rounded.Key
    GlobalStatus.OFFLINE -> Icons.Rounded.SignalWifiOff
  }
  val sentence = stringResource(status.label)
  val head = sentence.substringBefore(StatusSeparator).trim()
  val rest = sentence.substringAfter(StatusSeparator, missingDelimiterValue = "").trim().ifEmpty { null }
  GalleryFooterEntry(
    note = GalleryFooterNote(
      tone = tone,
      label = head,
      glyph = glyph,
      detail = rest?.replaceFirstChar { it.titlecase() },
      busy = status == GlobalStatus.WAKING_NAS,
      description = sentence,
    ),
    onClick = onClick,
    alignment = alignment,
  )
}

/**
 * La pagina di una scheda che lo scaffold non ha ricevuto (solo nelle prove che non passano le schede vere): il titolo, la riga della rete e
 * nient'altro. In produzione non si vede mai: `AppNavHost` passa sempre Raccolte, Album e Cerca. Z1 (10/11): prima diceva "… arriveranno qui".
 */
@Composable
private fun EmptyTab(
  title: String,
  status: GlobalStatus,
  onStatusClick: () -> Unit,
  onOpenSettings: () -> Unit,
  backdropOut: (GlassBackdropState) -> Unit,
) {
  FluidScreen(
    title = title,
    actions = {
      GalleryBarButton(
        icon = Icons.Rounded.Settings,
        contentDescription = stringResource(R.string.main_open_settings),
        onClick = onOpenSettings,
      )
    },
    extraBottomPadding = GalleryTabBarDefaults.ContentInset,
    // La pillola non e' qui dentro (e' una sola per tutte le schede): questa pagina le consegna solo il suo fondale.
    overlay = { pageBackdrop -> SideEffect { backdropOut(pageBackdrop) } },
  ) {
    item(key = "status") { NetworkStatusEntry(status = status, onClick = onStatusClick, alignment = Alignment.Start) }
  }
}

/** Il punto in mezzo delle frasi dell'indicatore globale: prima la cosa, poi la conseguenza. */
private const val StatusSeparator = " · "

/**
 * Dove sta la pillola, per raccoglierla verso il cerchio di Cerca mentre la barra di ricerca si apre (H4): l'origine della scala e' il centro del cerchio,
 * nelle coordinate della pillola. Letta nel disegno, solo mentre l'apertura e' in corso.
 */
private class PillOrigin {
  var coordinates: LayoutCoordinates? = null

  fun toward(circle: GallerySearchCircle): TransformOrigin {
    val pill = coordinates?.takeIf { it.isAttached } ?: return TransformOrigin.Center
    val center = circle.centerXInWindow() ?: return TransformOrigin.Center
    val left = pill.positionInWindow().x
    val width = pill.size.width.toFloat()
    if (width <= 0f) return TransformOrigin.Center
    return TransformOrigin(((center - left) / width).coerceIn(0f, 1f), PillPivotY)
  }
}

/** La pillola si raccoglie verso il centro della sua altezza (dove sta il centro del cerchio). */
private const val PillPivotY = 0.5f
