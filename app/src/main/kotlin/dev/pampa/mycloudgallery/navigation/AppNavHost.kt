package dev.pampa.mycloudgallery.navigation

import dev.pampa.mycloudgallery.feature.albums.nav.openAlbum
import androidx.activity.compose.ReportDrawnAfter
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import dev.pampa.mycloudgallery.core.common.perf.AfterFirstDraw
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.pampa.mycloudgallery.feature.map.nav.mapGraph
import dev.pampa.mycloudgallery.feature.map.nav.openMapSettings
import dev.pampa.mycloudgallery.feature.map.nav.openPlacesEntry
import dev.pampa.mycloudgallery.feature.map.places.PlacesEntryTarget
import dev.pampa.mycloudgallery.feature.memories.nav.memoriesGraph
import dev.pampa.mycloudgallery.feature.memories.nav.openMemoriesEntry
import dev.pampa.mycloudgallery.feature.memories.nav.openMemoriesSettings
import dev.pampa.mycloudgallery.feature.nas.hub.nasGraph
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import dev.antigravity.fluidengine.ui.navigation.FluidNavHost
import dev.antigravity.fluidengine.ui.navigation.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.antigravity.fluidengine.ui.theme.FluidScreenSurface
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.network.diagnostics.NetworkDiagActions
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions
import dev.pampa.mycloudgallery.nas.NasUnfreezeDialog
import dev.pampa.mycloudgallery.feature.diagnostics.diagnosticsGraph
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.nav.albumsGraph
import dev.pampa.mycloudgallery.feature.people.contract.PeopleActions
import dev.pampa.mycloudgallery.feature.people.nav.openPeopleEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionKind
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsTab
import dev.pampa.mycloudgallery.feature.library.collections.collectionsGraph
import dev.pampa.mycloudgallery.feature.library.collections.openCollection
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.library.LibraryViewModel
import dev.pampa.mycloudgallery.feature.library.utility.utilityGraph
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingGraphRoute
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingRenewRoute
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingResumeRoute
import dev.pampa.mycloudgallery.feature.onboarding.join.AwaitingApprovalScreen
import dev.pampa.mycloudgallery.feature.onboarding.onboardingGraph
import dev.pampa.mycloudgallery.feature.onboarding.onboardingRenewal
import dev.pampa.mycloudgallery.feature.settings.SettingsDevicesRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsGraphRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsNetworkRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsPasswordRoute
import dev.pampa.mycloudgallery.feature.settings.settingsGraph
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.main.GalleryHostViewModel
import dev.pampa.mycloudgallery.main.AlbumsTabHost
import dev.pampa.mycloudgallery.main.GalleryRoot
import dev.pampa.mycloudgallery.main.BackupHostEffect
import dev.pampa.mycloudgallery.main.FiveBHostEffect
import dev.pampa.mycloudgallery.integrations.ShareHostEffect
import dev.pampa.mycloudgallery.main.LibraryCommandHostEffect
import dev.pampa.mycloudgallery.main.LocalViewerHost
import dev.pampa.mycloudgallery.main.MainScaffold
import dev.pampa.mycloudgallery.main.ScaffoldSearch
import dev.pampa.mycloudgallery.feature.search.FindSimilarEffect
import dev.pampa.mycloudgallery.feature.search.SearchViewModel
import dev.pampa.mycloudgallery.feature.search.rememberSearchSource
import dev.pampa.mycloudgallery.main.ViewerHostState
import dev.pampa.mycloudgallery.root.BlockedScreen
import dev.pampa.mycloudgallery.root.KeystoreUi
import dev.pampa.mycloudgallery.root.KeystoreUnavailableScreen
import dev.pampa.mycloudgallery.root.RootDestination
import dev.pampa.mycloudgallery.root.WipedScreen
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.status.StatusAction
import dev.pampa.mycloudgallery.window.WindowStartEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** La galleria: schede, indicatore, Impostazioni. */
@Serializable
data object MainRoute

/**
 * L'attesa dell'approvazione. **Senza argomenti** (revisione 2, F5): il codice da mostrare viene dallo stato del
 * dispositivo (`SetupState.AwaitingApproval`, da `TrustStatus.Pending`, calcolato dalla chiave nel Keystore), mai da
 * un argomento di navigazione che un'altra app potrebbe scegliere.
 */
@Serializable
data object AwaitingApprovalRoute

@Serializable
data object WipedRoute

/** Il motivo viaggia per nome: `core-family` non conosce kotlinx.serialization. */
@Serializable
data class BlockedRoute(val reasonName: String) {
  val reason: BlockReason get() = BlockReason.entries.firstOrNull { it.name == reasonName } ?: BlockReason.NEEDS_APP_UPDATE
}

/**
 * La navigazione dell'app. La destinazione di partenza la decide lo stato del dispositivo ([destination],
 * da `RootViewModel`): quando cambia (onboarding finito, approvazione arrivata, azzeramento) il grafo
 * riparte dalla nuova destinazione, senza una pila vecchia da cui "tornare indietro" in uno stato che non
 * vale piu'.
 *
 * I tre `feature-*` non si conoscono: ogni passaggio fra loro e' una callback scritta qui.
 *
 * Le pagine sono quelle del Fluid Engine 3.0 (`FluidNavHost`): opache e laterali (regola 3 di Fluid: una pagina in
 * arrivo non sfuma mai), con il back che segue il dito. Prima erano le transizioni di `NavHost` scritte qui.
 */
@Composable
fun AppNavHost(
  destination: RootDestination,
  status: GlobalStatus,
  actions: RootNavActions,
  /** Per i test: il controller della navigazione appena creato. */
  onNavController: (NavHostController) -> Unit = {},
) {
  if (destination == RootDestination.Loading) {
    FluidScreenSurface(modifier = Modifier.fillMaxSize()) {}
    return
  }
  AvvioTrace.markOnce(AvvioTrace.DESTINATION)
  // Avvio istantaneo: se la prima schermata non e' la galleria (onboarding, attesa, dispositivo fermo), il lavoro rimandato parte
  // dopo il suo primo fotogramma; con la galleria lo fa partire il primo disegno utile della Libreria.
  if (destination != RootDestination.Main) {
    LaunchedEffect(Unit) {
      withFrameNanos { }
      AfterFirstDraw.open()
    }
  }
  key(destination) {
    val nav = rememberNavController()
    onNavController(nav)
    if (destination == RootDestination.Main) {
      // La galleria: host dei pop-up e del viewer sopra tutta la navigazione (Libreria, Raccolte, Impostazioni).
      GalleryRoot { AppNavGraph(destination, nav, status, actions) }
    } else {
      AppNavGraph(destination, nav, status, actions)
    }
  }
}

@Composable
private fun AppNavGraph(destination: RootDestination, nav: NavHostController, status: GlobalStatus, actions: RootNavActions) {
  // Le Raccolte aprono il viewer del grafo vero sull'host della galleria (D12: il viewer non e' una rotta).
  val viewer = LocalViewerHost.current
  val gallery: GalleryHostViewModel? = if (destination == RootDestination.Main) hiltViewModel() else null
  // Fase 4A (D10): il foglio "Ho controllato, continua", aperto dalla voce del congelamento in Diagnostica.
  var unfreeze by remember { mutableStateOf(false) }
  if (unfreeze && gallery != null) NasUnfreezeDialog(gallery, onDismiss = { unfreeze = false })
  // Fase 4B (P16): un file toccato in Cartelle NAS si apre nel viewer della Libreria (se la Libreria lo mostra).
  val openerScope = rememberCoroutineScope()
  val nasOpener = remember(gallery, viewer) { NasFileOpener(openerScope, gallery, viewer) }
  // Fase 5A (P7): le azioni del backup (navigazione, permessi, conferma dei dati mobili) vivono finche' la galleria e' visibile.
  if (gallery != null) BackupHostEffect(gallery.backupActions, nav, onAccessChanged = gallery::refreshAccess)
  // Fase 5B (P14): i fogli alla radice (Versioni, Correggi, "Elimina solo dal telefono", "Elimina…" delle versioni), "Libera spazio" con la
  // selezione e gli intenti verso le altre app ("Modifica con…", "Condividi").
  if (gallery != null) FiveBHostEffect(gallery.fiveB, nav)
  // Correzioni 5B (R4, MC-1, MC-2): la scelta dell'app di "Condividi" (viewer e selezione della Libreria), anche senza backup.
  if (gallery != null) ShareHostEffect(gallery.shares)
  // Fase 6 (P11): le azioni di Cerca (viewer sui risultati, pagine della ricerca), anche per la pagina Ricerca e modelli.
  val searchActions = if (destination == RootDestination.Main) rememberSearchActions(nav, viewer) else null
  // Fase 6 (TS-3): "Trova simili" da un viewer aperto fuori dalla schermata principale (Raccolte, Cartelle NAS) si consuma qui, alla radice: il viewer
  // si chiude, si torna alla schermata principale e Cerca parte dai simili. Prima lo consumava la schermata principale, che durante le Raccolte non c'e'.
  var findSimilar by remember { mutableStateOf<List<MediaId>?>(null) }
  if (destination == RootDestination.Main) SimilarRequestEffect(nav, viewer) { findSimilar = it }
  // Fase 7 (P13): gli album. Uno stato per l'activity (le rotte degli album e il selettore dei media lo condividono), "Aggiungi ad album" sopra la
  // schermata che l'ha chiesto e il viewer dei Nascosti con le voci degli album che valgono li' (l'album riceve anche il nascosto: richiesta esplicita).
  val albumsHost: AlbumsHostViewModel? = if (destination == RootDestination.Main) hiltViewModel() else null
  val albumActions = remember(nav, viewer, albumsHost, gallery) {
    albumsHost?.let { host -> AppAlbumActions(nav, viewer, host) { gallery?.viewerServices } }
  }
  val openViewer: (ViewerRequest) -> Unit = { request ->
    gallery?.let { host -> viewer?.open(request, albumsHost?.servicesForRequest(host.viewerServices, request) ?: host.viewerServices) }
  }
  if (albumsHost != null) AlbumRequestsHost(albumsHost, nav, viewer)
  // Fase 8 (P13): le persone. Uno stato per l'activity (le rotte delle persone, le miniature, i ritagli), il tocco su una persona dal viewer e l'avviso una tantum.
  val peopleHost: PeopleHostViewModel? = if (destination == RootDestination.Main) hiltViewModel() else null
  val peopleActions = remember(nav, viewer, peopleHost, gallery) {
    peopleHost?.let { host -> AppPeopleActions(nav, viewer, host) { gallery?.viewerServices } }
  }
  // H2, giro di correzione 1: un album toccato nel pannello informazioni chiude il viewer e apre la pagina dell'album.
  if (gallery != null) {
    SideEffect {
      gallery.albumOpener.onOpen = { id ->
        viewer?.close()
        nav.openAlbum(id)
      }
    }
  }
  if (peopleHost != null) {
    SideEffect { peopleHost.opener.bind(nav, viewer) }
    PeopleNoticeEffect(nav, peopleHost)
  }
  // Fase 9 (P13): i ricordi (uscite verso viewer, album e pagine di Android; il tocco sulla notifica servito qui, sotto il blocco app) e la mappa (uscite,
  // "Mostra sulla mappa" dal viewer, tema della mini-mappa). I due ViewModel nascono con la schermata principale senza costruire niente di pesante.
  val memoriesHost: MemoriesHostViewModel? = if (destination == RootDestination.Main) hiltViewModel() else null
  val memoriesActions = memoriesHost?.let { host -> rememberMemoriesActions(nav, viewer, host) { gallery?.viewerServices } }
  if (memoriesHost != null) MemoriesNotificationEffect(nav, memoriesHost)
  // Fase 10 (P13, §2.2, D9): il tocco sul widget, servito qui (sotto il blocco app, solo dalla finestra principale): viewer, pagina del ricordo o Libreria.
  val widgetHost: WidgetHostViewModel? = if (destination == RootDestination.Main) hiltViewModel() else null
  if (widgetHost != null) WidgetTapEffect(nav, viewer, widgetHost, openViewer)
  val mapHost: MapHostViewModel? = if (destination == RootDestination.Main) hiltViewModel() else null
  val mapActions = remember(nav, viewer, mapHost, gallery) { mapHost?.let { host -> AppMapActions(nav, viewer, host) { gallery?.viewerServices } } }
  if (mapHost != null) MapHostEffect(nav, viewer, mapHost)
  // Fase 10 (P5, D13): le pagine di album, persone e ricordi nel pannello di dettaglio delle Raccolte (da finestra estesa): le stesse uscite e miniature delle loro rotte.
  val entryPanes = remember(albumActions, albumsHost, peopleActions, peopleHost, memoriesActions, memoriesHost) {
    if (destination != RootDestination.Main) {
      null
    } else {
      EntryPanes(
        albumActions = albumActions,
        albumThumbnails = albumsHost?.thumbnails,
        peopleActions = peopleActions,
        peopleThumbnails = peopleHost?.thumbnails,
        peopleCrops = peopleHost?.crops,
        memoriesActions = memoriesActions,
        memoriesThumbnails = memoriesHost?.thumbnails,
        resolveMemory = { code -> memoriesHost?.resolveEntry(code) },
      )
    }
  }
  // Fluid Engine 3.0: le pagine sono dell'engine (`FluidNavHost`): si aprono da destra con la pagina sotto che arretra di un terzo, e il back col
  // dito tiene la pagina sotto il dito (da qualunque punto, salvo le pagine con gesti orizzontali propri, che lo chiedono dal bordo o mai). Il
  // back passa dai `BackHandler` delle pagine (selezione, cartelle, Novita'), registrati dopo l'host.
  FluidNavHost(
    navController = nav,
    startDestination = startRoute(destination),
  ) {
      // Revisione 2, F5: per ogni stato si registrano solo le destinazioni raggiungibili da li'. Una richiesta di
      // navigazione arrivata da fuori (oltre all'intent ripulito da MainActivity) non trova le altre.
      when (destination) {
        RootDestination.Main -> {
          composable<MainRoute> {
            MainDestination(
              nav = nav,
              status = status,
              openWireGuard = actions.openWireGuard,
              nasOpener = nasOpener,
              findSimilar = findSimilar,
              onFindSimilarHandled = { findSimilar = null },
              openViewer = openViewer,
              peopleActions = peopleActions,
              memoriesHost = memoriesHost,
              mapHost = mapHost,
              entryPanes = entryPanes,
              albumsHost = albumsHost,
            )
          }
          // Fase 3 (P10): le pagine delle Raccolte sul telefono (griglie filtrate, Nascosti, Eliminati di recente).
          collectionsGraph(nav = nav, onOpenViewer = openViewer)
          // Fase 7 (P8): le pagine dell'Utility (Screenshot, Video, Modificate, WhatsApp, Doppioni); gli Eliminati di recente sono nelle Raccolte.
          utilityGraph(nav = nav, onOpenViewer = openViewer)
          // Fase 7 (P10, P13): le pagine degli album (elenco, pagina, Suggerite, editor delle regole, viaggi) e il selettore dei media.
          if (albumsHost != null && albumActions != null) {
            albumsGraph(nav = nav, actions = albumActions, thumbnails = albumsHost.thumbnails)
            mediaPickerGraph(nav = nav, host = albumsHost)
          }
          // Fase 8 (P13): "Tutte le persone", "Altri volti", la pagina di una persona, la revisione, le nascoste; attivazione, avviso, Impostazioni -> Persone, "Disattiva e cancella".
          if (peopleHost != null && peopleActions != null) peopleScreens(nav = nav, actions = peopleActions, host = peopleHost)
          // Fase 9 (P13): "Tutti i ricordi", la pagina di un ricordo, la presentazione, Impostazioni -> Ricordi e notifiche; la mappa, la griglia di una zona e di
          // un luogo, Impostazioni -> Mappa. Le rotte dei benchmark di P12 sono queste (`MemoryDestination`, `MemoryPlayerDestination`, `MapDestination`).
          if (memoriesHost != null && memoriesActions != null) memoriesGraph(nav = nav, actions = memoriesActions, thumbnails = memoriesHost.thumbnails)
          if (mapHost != null && mapActions != null) mapGraph(nav = nav, actions = mapActions, thumbnails = mapHost.thumbnails)
          // Fase 4B (P16): Cartelle NAS, Spazio NAS, Eliminati di recente del NAS, Operazioni in corso (rotte a schermo intero).
          nasGraph(nav = nav, onOpenFile = nasOpener::open, trashPage = GatedNasTrashPage)
          // Fase 5A (P7): la pagina Backup, le impostazioni, la cartella del dispositivo e il riepilogo del primo backup.
          if (gallery?.backupAvailable == true) {
            backupGraph(
              nav,
              takeFreeSpaceRequest = { gallery.fiveB?.takeFreeSpaceRequest() },
              freeSpaceRequest = { serial -> gallery.fiveB?.freeSpaceRequest(serial) },
            )
          }
          // "Da completare" nelle Impostazioni riapre i passi saltati dell'onboarding.
          onboardingGraph(nav = nav, onDone = {}, onPause = actions.pause)
          settingsGraph(
            nav = nav,
            openDiagnostics = { nav.navigate(DiagnosticsGraphRoute) },
            resumeStep = { step -> nav.navigate(OnboardingResumeRoute(step)) },
            developerSection = DeveloperTools.settingsSection(nav),
            // Fase 5A (D16): la voce "Backup" delle Impostazioni, solo se il backup c'e'.
            openBackup = if (gallery?.backupAvailable == true) ({ nav.openBackupPage() }) else null,
            // Fase 6 (P11): "Ricerca e modelli".
            openSearch = { nav.navigate(SearchSettingsPageRoute) },
            // Fase 7 (P9, D33): Privacy → Nascosti, sempre (anche con la sezione nascosta dalle Raccolte), dietro il suo cancello.
            openHidden = { nav.openCollection(CollectionKind.HIDDEN) },
            // Fase 8 (P7, P13): Impostazioni -> Persone.
            openPeople = { nav.navigate(PeopleSettingsDestination) },
            // Fase 9 (P9, P4, P13): Impostazioni -> Ricordi e notifiche (sezione "Libreria") e Impostazioni -> Mappa (sezione "Protezione").
            openMemories = if (memoriesHost != null) ({ nav.openMemoriesSettings() }) else null,
            openMap = if (mapHost != null) ({ nav.openMapSettings() }) else null,
          )
          // Fase 6 (P11): la pagina Ricerca e modelli e quella di attivazione della lettura del testo.
          searchActions?.let { searchGraph(nav, it) }
          diagnosticsGraph(
            nav = nav,
            appActions = DIAGNOSTICS_APP_ACTIONS,
            onAppAction = { action ->
              when (action) {
                DiagnosticsActions.NAS_STOP_WRITES -> gallery?.setNasWritesStopped(true)
                DiagnosticsActions.NAS_RESUME_WRITES -> gallery?.setNasWritesStopped(false)
                DiagnosticsActions.NAS_RELEASE_FREEZE -> unfreeze = true
                else -> onDiagnosticsAction(nav, action)
              }
            },
          )
          with(DeveloperTools) { destinations(nav) }
        }
        is RootDestination.AwaitingApproval -> {
          composable<AwaitingApprovalRoute> {
            AwaitingDestination(code = destination.code, actions = actions, onRenew = { nav.navigate(OnboardingRenewRoute) })
          }
          // "Rifai la richiesta" (ON-2): la sola pagina del rinnovo (non l'onboarding intero, F5); finita o lasciata, si
          // torna all'attesa.
          onboardingRenewal(nav = nav, onPause = actions.pause)
        }
        RootDestination.Wiped -> composable<WipedRoute> {
          WipedScreen(onReconfigure = actions.reconfigure)
        }
        is RootDestination.Blocked -> composable<BlockedRoute> { entry ->
          // R7: la pagina con "Riprova" (e l'uscita "Configura di nuovo") la decide lo stato del dispositivo, mai l'argomento della rotta.
          if (destination.reason == BlockReason.KEYSTORE_UNAVAILABLE) {
            KeystoreDestination(actions.keystore)
          } else {
            BlockedScreen(reason = entry.toRoute<BlockedRoute>().reason)
          }
        }
        // L'uscita dall'onboarding la decide lo stato (SetupState): onDone non deve fare niente. "Continua piu' tardi"
        // quando non c'e' un passo locale da fare (ingresso, recupero) manda l'app in secondo piano, a configurazione salvata.
        RootDestination.Onboarding, RootDestination.Loading -> onboardingGraph(nav = nav, onDone = {}, onPause = actions.pause)
      }
  }
}

/** Cio' che la navigazione chiede alla radice (RootViewModel e Android). */
class RootNavActions(
  /** "Apri WireGuard": falso se non e' installato. */
  val openWireGuard: () -> Boolean,
  val deviceKeyQr: () -> String?,
  val watchApproval: () -> Flow<TrustStatus>,
  val reconfigure: () -> Unit,
  val pause: () -> Unit,
  /** La pagina "Keystore non disponibile" (R7). */
  val keystore: KeystoreActions = KeystoreActions.None,
)

/** La pagina "Keystore non disponibile" (R7): lo stato e i gesti, da `RootViewModel`. */
class KeystoreActions(
  val state: StateFlow<KeystoreUi>,
  val retry: () -> Unit,
  val askStartOver: () -> Unit,
  val cancelStartOver: () -> Unit,
  val confirmStartOver: () -> Unit,
) {
  companion object {
    /** Nessuna azione (test della navigazione che non arrivano alla pagina). */
    val None = KeystoreActions(MutableStateFlow(KeystoreUi()), {}, {}, {}, {})
  }
}

@Composable
private fun KeystoreDestination(actions: KeystoreActions) {
  val ui by actions.state.collectAsStateWithLifecycle()
  KeystoreUnavailableScreen(
    ui = ui,
    onRetry = actions.retry,
    onAskStartOver = actions.askStartOver,
    onCancelStartOver = actions.cancelStartOver,
    onConfirmStartOver = actions.confirmStartOver,
  )
}

/**
 * L'attesa dell'approvazione (E-S14): codice e QR della chiave (pagina di feature-onboarding). Il registro si rilegge
 * ogni 10 secondi **solo** mentre la pagina e' visibile (`repeatOnLifecycle(STARTED)`): niente lavoro in background.
 * Quando l'admin approva, lo stato del dispositivo cambia e la radice porta alla Libreria.
 */
@Composable
private fun AwaitingDestination(code: String, actions: RootNavActions, onRenew: () -> Unit) {
  val qr = remember { actions.deviceKeyQr() }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      actions.watchApproval().collect { }
    }
  }
  AwaitingApprovalScreen(code = code, deviceKeyQr = qr, onRenew = onRenew)
}

/** Le azioni della Diagnostica che portano fuori dalla Diagnostica: le sa fare solo :app (i feature non si conoscono). */
internal val DIAGNOSTICS_APP_ACTIONS = setOf(
  DiagAction.REENTER_NAS_PASSWORD,
  DiagAction.OPEN_DEVICES,
  DiagAction.RESUME_ONBOARDING,
  // Verifica funzionale, RE-10 e RE-1/RE-2: «Ripeti il riconoscimento», «Consenti il controllo», «Riattiva il controllo» stanno in Rete e VPN.
  NetworkDiagActions.RECAPTURE_HOME,
  NetworkDiagActions.OPEN_VPN_CONTROL,
  // Fase 4A (§1.4): l'emergenza e lo scongelamento del NAS nella Libreria.
  DiagnosticsActions.NAS_STOP_WRITES,
  DiagnosticsActions.NAS_RESUME_WRITES,
  DiagnosticsActions.NAS_RELEASE_FREEZE,
  // Fase 8 (correzioni E, DG-2): "Fallo su questo dispositivo" di `FACES_NO_INDEXER` porta a Ricerca e modelli, dove si accende il ruolo di Indicizzatore.
  DiagnosticsActions.OPEN_INDEXER_ROLE,
)

private fun onDiagnosticsAction(nav: NavHostController, action: DiagAction) {
  diagnosticsRouteOf(action)?.let { nav.navigate(it) }
}

/**
 * Dove porta un'azione della Diagnostica che esce dalla Diagnostica (null = non e' una di queste). Pura, provata senza schermo: ogni
 * azione di [DIAGNOSTICS_APP_ACTIONS] che non ha un gestore proprio deve avere una rotta qui, altrimenti sarebbe un tasto che non fa niente.
 */
internal fun diagnosticsRouteOf(action: DiagAction): Any? = when (action) {
  DiagAction.REENTER_NAS_PASSWORD -> SettingsPasswordRoute
  DiagAction.OPEN_DEVICES -> SettingsDevicesRoute
  DiagAction.RESUME_ONBOARDING -> SettingsGraphRoute
  NetworkDiagActions.RECAPTURE_HOME, NetworkDiagActions.OPEN_VPN_CONTROL -> SettingsNetworkRoute
  DiagnosticsActions.OPEN_INDEXER_ROLE -> SearchSettingsPageRoute
  else -> null
}

/**
 * La galleria (P11): la Libreria vera dal suo ViewModel (grafo della Fase 3; in debug anche la Libreria sintetica), i
 * comandi della Libreria legati all'activity (permessi solo da un tocco, condivisione, cestino di sistema), le Raccolte
 * con le loro rotte, il viewer sull'host della galleria.
 */
@Composable
private fun MainDestination(
  nav: NavHostController,
  status: GlobalStatus,
  openWireGuard: () -> Boolean,
  nasOpener: NasFileOpener,
  /** "Trova simili" chiesto da un viewer (TS-2, TS-3) o dalla selezione della Libreria (TS-4): i media di cui cercare i simili, finche' Cerca non li ha presi. */
  findSimilar: List<MediaId>?,
  onFindSimilarHandled: () -> Unit,
  /** Il viewer della galleria per le Raccolte (con le voci degli album dei Nascosti, se la richiesta e' dei Nascosti). */
  openViewer: (ViewerRequest) -> Unit,
  /** Fase 8 (P13): le uscite delle persone (la sezione delle Raccolte apre le loro pagine). */
  peopleActions: PeopleActions?,
  /** Fase 9 (P13): i ricordi (il giro `OPEN` all'apertura di Raccolte, le voci della sezione). */
  memoriesHost: MemoriesHostViewModel? = null,
  /** Fase 9 (correzioni MA-1): la mappa (il "Riprova" della sezione Luoghi rifa la lettura dei punti). */
  mapHost: MapHostViewModel? = null,
  /** Fase 10 (P5): le pagine di album, persone e ricordi nel pannello di dettaglio delle Raccolte. */
  entryPanes: EntryPanes? = null,
  /** G2: la scheda Album della pillola (l'elenco degli album, le sue miniature e le sue rotte). */
  albumsHost: AlbumsHostViewModel? = null,
) {
  var tab by rememberSaveable { mutableStateOf(GalleryTab.Library) }
  // H4: la ricerca e' la barra che nasce dal cerchio accanto alla pillola, aperta sopra la scheda [tab] con la Libreria dietro (Indietro la chiude e torna
  // li'). [searchFocus]: aperta dal cerchio il campo e' pronto a scrivere; aperta da "Trova simili" no.
  var searchOpen by rememberSaveable { mutableStateOf(false) }
  var searchFocus by rememberSaveable { mutableStateOf(false) }
  // Fase 10 (P4, D16): una finestra nuova parte dalla pagina scelta (album, ricordo, mappa), con un evento interno e solo dopo il blocco app.
  WindowStartEffect(nav)
  val library: LibraryViewModel = hiltViewModel()
  val gallery: GalleryHostViewModel = hiltViewModel()
  val viewer = LocalViewerHost.current
  val state by library.state.collectAsStateWithLifecycle()
  val libraryViewer = remember(state) { gallery.viewerServicesFor(state) }
  // Fase 4B: la Libreria di adesso per aprire nel viewer un file toccato in Cartelle NAS.
  SideEffect {
    nasOpener.library = state
    nasOpener.services = libraryViewer
  }
  // H4: la ricerca dentro la Libreria (il motore, i suggerimenti, i risultati) e "Trova simili" dal viewer o dalla selezione.
  val searchHost: SearchHostViewModel = hiltViewModel()
  val searchActions = rememberSearchActions(nav, viewer, searchHost)
  val searchModel: SearchViewModel = hiltViewModel()
  val searchController = searchModel.controller
  val searchSource = rememberSearchSource(searchController)
  val search = remember(searchSource, searchController, searchHost, searchActions) {
    ScaffoldSearch(
      source = searchSource,
      handlers = searchController,
      thumbnails = searchHost.thumbnails,
      actions = searchActions,
      onResultOpened = searchController::onResultOpened,
    )
  }
  // "Trova simili" (dal viewer, TS-2/TS-3, o dalla selezione della Libreria, TS-4) apre la ricerca sopra la scheda di adesso, senza tastiera, con i simili
  // gia' cercati. La richiesta si consegna una volta (TS-2).
  FindSimilarEffect(
    request = findSimilar,
    onFind = { media ->
      searchFocus = false
      if (!searchOpen) AvvioTrace.pageOpened(AvvioTrace.PAGE_SEARCH)
      searchOpen = true
      searchController.onOpened()
      searchController.findSimilar(media)
    },
    onHandled = onFindSimilarHandled,
  )
  // Fase 9 (MO-3): il giro dei ricordi di oggi anche al ritorno in primo piano con Raccolte gia' aperta (l'app viva da giorni, il ripristino dopo la morte del processo).
  if (memoriesHost != null) MemoriesForegroundRound(memoriesHost, collectionsOpen = tab == GalleryTab.Collections && !searchOpen)

  LibraryCommandHostEffect(state.commands, onAccessChanged = gallery::refreshAccess)
  ReportLibraryDrawn(state)
  RestoreLibraryViewer(state, libraryViewer)

  MainScaffold(
    status = status,
    selectedTab = tab,
    onSelectTab = { selected ->
      // Avvio istantaneo: l'apertura di Raccolte si misura dal tocco al primo fotogramma con l'ultimo stato o lo scheletro.
      if (selected != tab || searchOpen) {
        when (selected) {
          GalleryTab.Collections -> {
            AvvioTrace.pageOpened(AvvioTrace.PAGE_COLLECTIONS)
            // P9: all'apertura di Raccolte il giro dei ricordi di oggi, se non c'e' ancora stato (fuori dal thread principale, dopo il fotogramma).
            memoriesHost?.memories?.onCollectionsOpened()
          }
          else -> Unit
        }
      }
      tab = selected
    },
    search = search,
    searchOpen = searchOpen,
    onOpenSearch = {
      // Avvio istantaneo: l'apertura di Cerca si misura dal tocco sul cerchio al primo fotogramma della barra aperta.
      if (!searchOpen) AvvioTrace.pageOpened(AvvioTrace.PAGE_SEARCH)
      searchFocus = true
      searchOpen = true
      searchController.onOpened()
    },
    onCloseSearch = {
      searchOpen = false
      searchFocus = false
      // La prossima apertura riparte da capo: il campo vuoto, la Libreria dietro.
      searchController.clear()
    },
    searchFocus = searchFocus,
    onSearchFocused = { searchFocus = false },
    onStatusClick = {
      when (status.action) {
        StatusAction.OPEN_WIREGUARD -> if (!openWireGuard()) nav.navigate(DiagnosticsGraphRoute)
        StatusAction.ENTER_PASSWORD -> nav.navigate(SettingsPasswordRoute)
        StatusAction.OPEN_DIAGNOSTICS -> nav.navigate(DiagnosticsGraphRoute)
      }
    },
    onOpenSettings = { nav.navigate(SettingsGraphRoute) },
    // NA-9: "Lavori fermi" e le altre voci del NAS aprono la Diagnostica; l'azione della rete (WireGuard, la password) resta alla capsula della rete.
    onNasStatusClick = { nav.navigate(DiagnosticsGraphRoute) },
    // Fase 5A (D16): la capsula del backup nel piede della Libreria porta alla pagina Backup.
    onBackupStatusClick = { nav.openBackupPage() },
    libraryState = state,
    libraryViewer = libraryViewer,
    collections = { slot ->
      CollectionsTab(
        contentPadding = slot.contentPadding,
        backdropOut = slot.backdropOut,
        onOpenViewer = openViewer,
        onOpenCollection = { kind -> nav.openCollection(kind) },
        onOpenSettings = { nav.navigate(SettingsGraphRoute) },
        statusHeader = slot.statusHeader,
        // Fase 7 (P11, P13): le sezioni arrivano dai fornitori (`CollectionsModule`); le voci date come dati si aprono qui. Sul telefono la rotta, sul
        // tablet la pagina nel pannello di dettaglio (le pagine degli album sono rotte anche li').
        onOpenEntry = { entry ->
          openCollectionEntry(
            entry.code,
            nav,
            openFreeSpace = { gallery.fiveB?.openFreeSpace(null) },
            onPeople = { target -> peopleActions?.let { actions -> nav.openPeopleEntry(target, actions) } },
            onMemories = { target -> nav.openMemoriesEntry(target) { code -> memoriesHost?.resolveEntry(code) } },
            // MA-1: "Riprova" sotto "Non riesco a leggere le posizioni delle foto" non apre una pagina, rifa la lettura dei punti.
            onPlaces = { target -> if (target == PlacesEntryTarget.Retry) mapHost?.map?.retryPoints() else nav.openPlacesEntry(target) },
          )
        },
        entryDetail = { entry -> CollectionEntryDetail(entry, nav, slot.contentPadding, nasOpener, openViewer, entryPanes) },
        entryHasDetail = { entry -> hasEntryDetail(entry.code, entryPanes) },
      )
    },
    // G2 + G2b: la scheda Album (la griglia di copertine di G2b, ospitata da `AlbumsTabHost`).
    albums = if (albumsHost != null) {
      { slot -> AlbumsTabHost(slot = slot, nav = nav) }
    } else {
      null
    },
  )
}

/**
 * TS-2, TS-3: la richiesta di "Trova simili" di un viewer, consumata alla radice. Chiude il viewer, riporta alla schermata principale (chiude Raccolte o
 * Cartelle NAS, se il viewer era stato aperto da li') e consegna il media a chi tiene la scheda Cerca ([onTarget]).
 */
@Composable
private fun SimilarRequestEffect(nav: NavHostController, viewer: ViewerHostState?, onTarget: (List<MediaId>) -> Unit) {
  val host: SearchHostViewModel = hiltViewModel()
  val request by host.similar.pending.collectAsStateWithLifecycle()
  LaunchedEffect(request) {
    val id = host.similar.consume() ?: return@LaunchedEffect
    viewer?.close()
    nav.popBackStack<MainRoute>(inclusive = false)
    onTarget(id)
  }
}

/**
 * Il TTFD dell'avvio a freddo (§2.6, misurato da P9): `reportFullyDrawn` quando la prima pagina di miniature e' disegnata,
 * o al piu' 300 ms dopo il primo layout della griglia. Una Libreria vuota o senza permessi lo dice subito.
 */
@Composable
private fun ReportLibraryDrawn(state: LibraryTabState) {
  ReportDrawnAfter {
    val grid = state.grid
    withTimeoutOrNull(LAYOUT_WAIT_MS) {
      snapshotFlow { grid.layout != null || state.phone.value.access == MediaAccess.NONE }.first { it }
    }
    withTimeoutOrNull(THUMBNAILS_WAIT_MS) {
      grid.loader.stats.first { it.inFlight == 0 && it.started + it.memoryHits > 0 }
    }
  }
}

/** E-V8: dopo una ricreazione la Libreria riapre il viewer sull'elemento che si stava guardando (se c'e' ancora). */
@Composable
private fun RestoreLibraryViewer(state: LibraryTabState, services: ViewerServices) {
  val viewer = LocalViewerHost.current ?: return
  val pending = viewer.pendingLibraryRestore ?: return
  LaunchedEffect(pending, state) {
    val id = runCatching { MediaId(pending) }.getOrNull()
    val ready = id != null && withTimeoutOrNull(RESTORE_WAIT_MS) {
      snapshotFlow { state.grid.layout != null && state.grid.view.viewIndexOf(id) >= 0 }.first { it }
    } == true
    viewer.consumeLibraryRestore()
    if (ready && id != null) {
      viewer.open(ViewerRequest(state.viewerItems, id, state.grid.viewerAnchor, secure = false), services, fromLibrary = true)
    }
  }
}

private const val LAYOUT_WAIT_MS = 5_000L
private const val THUMBNAILS_WAIT_MS = 300L
private const val RESTORE_WAIT_MS = 5_000L

/** La rotta di partenza per ogni destinazione della radice. */
internal fun startRoute(destination: RootDestination): Any = when (destination) {
  RootDestination.Loading, RootDestination.Onboarding -> OnboardingGraphRoute
  is RootDestination.AwaitingApproval -> AwaitingApprovalRoute
  RootDestination.Main -> MainRoute
  RootDestination.Wiped -> WipedRoute
  is RootDestination.Blocked -> BlockedRoute(destination.reason.name)
}
