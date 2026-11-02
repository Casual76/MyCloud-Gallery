package dev.pampa.mycloudgallery.feature.map.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPaneRules
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPanes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.rememberGalleryWindow
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapActions
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridController
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridScreen
import dev.pampa.mycloudgallery.feature.map.grid.ZoneTarget
import dev.pampa.mycloudgallery.feature.map.grid.handlersOf
import dev.pampa.mycloudgallery.feature.map.nav.MapDependencies
import dev.pampa.mycloudgallery.feature.map.nav.MapDestination
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

// LA ROTTA DELLA MAPPA (Fase 9, P4): lega il controllore, la superficie (vera o finta) e le uscite verso `:app` (la griglia di una zona, Impostazioni -> Mappa). Nessuna logica propria: cio' che
// la pagina fa lo decide `MapController`. Il fuoco di "Mostra sulla mappa" arriva come testo opaco (un id) e se non e' un id si ignora (un dato vecchio o ostile: la mappa si apre normale).

/** L'id di un media da una rotta: null se non e' un id. */
internal fun parseFocus(text: String?): MediaId? = text?.let { runCatching { MediaId(it) }.getOrNull() }

@HiltViewModel
class MapViewModel @Inject constructor(savedStateHandle: SavedStateHandle, deps: MapDependencies) : ViewModel() {
  /** Fase 10 (P5, D13 (2)): la zona aperta accanto alla mappa (da finestra estesa); salvata, passa fra pannello e pagina. */
  internal val zonePane: MapZoneState = MapZoneState(
    handle = savedStateHandle,
    newController = { key, scope -> ZoneGridController(ZoneTarget.Cell(key), deps.points, deps.visible, deps.places, deps.filter, scope) },
    parent = viewModelScope,
  )

  /** La mappa vera (o, nei test, la finta) e la sua superficie: una per pagina. */
  val content: MapSurfaceContent = deps.content
  val surface: MapSurface = content.createSurface()

  /** L'istantanea della Libreria (senza nascosti): da li' le copertine delle bolle e le celle. */
  val snapshot: StateFlow<TimelineSnapshot?> = deps.visible.snapshot

  val controller: MapController = MapController(
    points = deps.points,
    settings = deps.settings,
    regions = deps.regions,
    network = deps.network,
    names = deps.places,
    filter = deps.filter,
    positions = deps.positions,
    snapshot = deps.visible.snapshot,
    scope = viewModelScope,
    focus = parseFocus(savedStateHandle.toRoute<MapDestination>().focus),
    cacheFailed = MapLibreInit.cacheFailed,
  ).also { surface.listener = it }

  override fun onCleared() {
    surface.listener = null
    zonePane.release()
  }
}

@Composable
fun MapRoute(
  actions: MapActions,
  thumbnails: MapThumbnails,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MapViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val surface = viewModel.surface
  val ui by controller.ui.collectAsState()
  val snapshot by viewModel.snapshot.collectAsState()
  val zoneKey by viewModel.zonePane.zone.collectAsState()
  val window = rememberGalleryWindow()
  val windowNow by rememberUpdatedState(window)
  val dark = isDarkTheme()
  LaunchedEffect(controller, dark) { controller.setDark(dark) }
  // Uscendo dalla pagina l'ultima posizione si salva subito, **solo in locale** (D29).
  DisposableEffect(controller) { onDispose { controller.flushCamera() } }
  val handlers = remember(controller, surface, actions, onBack, viewModel) {
    object : MapHandlers {
      override fun onBack() = onBack()

      override fun onBubble(bubble: PlacedBubble) {
        when (val tap = controller.tapOf(bubble)) {
          is BubbleTap.ZoomTo -> surface.animateTo(tap.spot)
          // Da finestra estesa la griglia della zona si apre accanto alla mappa; sotto, e' una pagina (D13 (2)).
          is BubbleTap.OpenZone -> if (GalleryPaneRules.twoPanes(windowNow)) viewModel.zonePane.open(tap.key) else actions.openZone(tap.key)
        }
      }

      override fun onNotice() = actions.openMapSettings()

      override fun onSettings() = actions.openMapSettings()

      override fun onRetry() = controller.retry()
    }
  }
  // Una finestra che si stringe sotto la soglia con una zona aperta: la griglia diventa la pagina di sempre e la zona torna "chiusa" qui (E-T2). Il percorso inverso lo fa la pagina.
  val twoPanes = GalleryPaneRules.twoPanes(window)
  LaunchedEffect(zoneKey, twoPanes) {
    val key = zoneKey
    if (key != null && !twoPanes) {
      viewModel.zonePane.close()
      actions.openZone(key)
    }
  }
  val panes = GalleryPaneRules.mapZone(window, zoneOpen = zoneKey != null)
  val split = remember(window, panes) { (panes as? GalleryPanes.Docked)?.let { GalleryPaneRules.split(window, it) } }
  MapPanesLayout(
    panes = panes,
    split = split,
    modifier = modifier,
    map = { mapModifier ->
      MapScreen(ui = ui, surface = surface, content = viewModel.content, thumbnails = thumbnails, snapshot = snapshot, handlers = handlers, modifier = mapModifier)
    },
    zone = { zoneModifier ->
      val key = zoneKey
      if (key != null) {
        val zone = viewModel.zonePane.controllerFor(key)
        val state by zone.state.collectAsState()
        val zoneHandlers = remember(zone, actions, viewModel) { handlersOf(zone, actions) { viewModel.zonePane.close() } }
        ZoneGridScreen(state, thumbnails, zoneHandlers, zoneModifier)
      }
    },
  )
}
