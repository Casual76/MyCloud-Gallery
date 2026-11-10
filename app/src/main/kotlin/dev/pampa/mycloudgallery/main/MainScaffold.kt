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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarInlineBehavior
import dev.antigravity.fluidengine.ui.fluid.rememberFluidFloatingTabBarScrollConnection
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
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
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.status.GlobalStatus
import kotlinx.coroutines.launch

/** Cio' che lo scaffold passa alle schede Album e Raccolte e a Cerca: lo spazio della pillola, il fondale da rifrangere, l'indicatore. */
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
 * - **Cerca** (Fase 6, P11; G2) non e' piu' una scheda: e' il cerchio di vetro della pillola. Toccato, la ricerca ([search], la schermata di
 *   feature-search) prende il posto della scheda con il campo pronto a scrivere; la pillola resta sulla scheda di prima, Indietro (o un tocco
 *   su una scheda) chiude la ricerca e torna li'. E' una sovrapposizione dentro la schermata principale, non una rotta: vale uguale sul
 *   tablet a due pannelli e con la pillola ripiegata (il cerchio e' della pillola), e lo stato della ricerca resta com'era fra un'apertura e
 *   l'altra, come quando era una scheda.
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
 * @param collections la scheda Raccolte; null = il segnaposto (solo le prove che non guardano le schede: gli screenshot passano dallo scaffold
 *   delle prove, `GalleryScaffold`, con le schede vere).
 * @param albums la scheda Album (`AlbumsTabHost`); null = il segnaposto.
 * @param search la ricerca aperta dal cerchio; null = il segnaposto.
 * @param searchOpen Cerca e' aperta sopra [selectedTab]; [onOpenSearch] e [onCloseSearch] la aprono (il cerchio) e la chiudono (Indietro, una scheda).
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
  /** G2: la scheda Album (`AlbumsTabHost`); null = il segnaposto (le prove senza le schede). */
  albums: (@Composable (CollectionsSlot) -> Unit)? = null,
  /** Fase 6 (P11), G2: la ricerca aperta dal cerchio; null = il segnaposto (le prove senza le schede). */
  search: (@Composable (CollectionsSlot) -> Unit)? = null,
  searchOpen: Boolean = false,
  onOpenSearch: () -> Unit = {},
  onCloseSearch: () -> Unit = {},
) {
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
  // G2: con Cerca aperta Indietro la chiude e torna alla scheda di prima (registrato prima delle schede: i loro BackHandler, se ci sono, vincono).
  BackHandler(enabled = searchOpen) { onCloseSearch() }

  // Il posto della pillola (B3): lo prende chi, dentro una scheda, mostra la barra delle azioni di una selezione.
  val tabBarSlot = remember { GalleryTabBarSlot() }
  val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val selecting = selectedTab == GalleryTab.Library && !searchOpen && libraryState.selectionActive
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

  // GF-7 (K3a): ogni pagina (le tre schede e Cerca) tiene cio' che ricorda mentre e' coperta. Senza, la scheda che usciva dalla composizione
  // ripartiva da capo: aprire Cerca o passare a un'altra scheda riportava Album e Raccolte in cima e il pannello del tablet alla prima scelta.
  // La Libreria ricorda gia' la sua griglia (lo stato e' fuori, nel ViewModel) e qui ritrova anche la posizione ancorata a un media, che serve
  // solo a una griglia nuova (il processo ripartito): la griglia viva che torna e' gia' dove era e non si sposta (K6, GS-10).
  val pageStates = rememberSaveableStateHolder()
  val pageKey = if (searchOpen) GalleryTabBarDefaults.SearchKey else selectedTab.route

  Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalGalleryTabBarSlot provides tabBarSlot) {
      pageStates.SaveableStateProvider(pageKey) {
        when {
          searchOpen && search != null -> search(pageSlot)
          searchOpen -> PlaceholderTab(
            key = GalleryTabBarDefaults.SearchKey,
            title = stringResource(R.string.main_search_title),
            status = status,
            onStatusClick = onStatusClick,
            onOpenSettings = onOpenSettings,
            backdropOut = { backdrop = it },
          ) {
            FluidEmptyState(title = stringResource(R.string.main_search_empty_title), detail = stringResource(R.string.main_search_empty_detail))
          }
          selectedTab == GalleryTab.Library -> LibraryTab(
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
          )
          selectedTab == GalleryTab.Albums && albums != null -> albums(pageSlot)
          selectedTab == GalleryTab.Collections && collections != null -> collections(pageSlot)
          else -> PlaceholderTab(
            key = selectedTab.route,
            title = stringResource(selectedTab.label),
            status = status,
            onStatusClick = onStatusClick,
            onOpenSettings = onOpenSettings,
            backdropOut = { backdrop = it },
          ) {
            TabPlaceholder(selectedTab)
          }
        }
      }
    }

    // La pillola e il cerchio di Cerca; in selezione escono e la barra delle azioni entra nello stesso posto (stessa animazione, quella della
    // capsula `ReplacingTabBar` dell'engine).
    GalleryTabBarSlotVisibility(visible = !selecting && !tabBarSlot.taken, modifier = Modifier.align(Alignment.BottomCenter)) {
      GalleryTabBar(
        selected = selectedTab,
        onSelect = { tab ->
          // Con Cerca aperta un tocco su una scheda (anche quella sotto la ricerca) chiude la ricerca e porta li'.
          if (searchOpen) onCloseSearch()
          onSelectTab(tab)
        },
        backdrop = backdrop,
        onReselect = { tab -> if (tab == GalleryTab.Library) scope.launch { libraryState.grid.scrollToNewest() } },
        scrollConnection = tabBarScroll,
        searchOpen = searchOpen,
        onOpenSearch = onOpenSearch,
      )
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

@Composable
private fun PlaceholderTab(
  key: String,
  title: String,
  status: GlobalStatus,
  onStatusClick: () -> Unit,
  onOpenSettings: () -> Unit,
  backdropOut: (GlassBackdropState) -> Unit,
  body: @Composable () -> Unit,
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
    item(key = "tab-$key") { body() }
  }
}

@Composable
private fun TabPlaceholder(tab: GalleryTab) {
  when (tab) {
    // La Libreria non e' un segnaposto (Fase 3): non passa di qui.
    GalleryTab.Library -> Unit
    // Senza la scheda vera (le prove che non la passano) gli Album e le Raccolte restano un segnaposto.
    GalleryTab.Albums -> FluidEmptyState(
      title = stringResource(R.string.main_albums_empty_title),
      detail = stringResource(R.string.main_albums_empty_detail),
    )
    GalleryTab.Collections -> FluidEmptyState(
      title = stringResource(R.string.main_collections_empty_title),
      detail = stringResource(R.string.main_collections_empty_detail),
    )
  }
}

/** Il punto in mezzo delle frasi dell'indicatore globale: prima la cosa, poi la conseguenza. */
private const val StatusSeparator = " · "
