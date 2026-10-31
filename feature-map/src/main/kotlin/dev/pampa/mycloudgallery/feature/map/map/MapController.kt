package dev.pampa.mycloudgallery.feature.map.map

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapMediaPositions
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// IL CONTROLLORE DELLA MAPPA (Fase 9, P4; docs/fase9-progetto.md §1.5 `MapScreenState`, §2.10, D24, D28, D29, D31, E-MAP1-E-MAP5, E-MAP15, E-MAP17-E-MAP19; "Avvio e pagine
// istantanei" in docs/decisioni.md). Senza Compose e senza MapLibre: parla con la mappa solo attraverso [MapSurfaceListener].
//
//  - **Istantaneo**: la camera parte subito dall'**ultima posizione locale** (dopo aver atteso la lettura del file, pochi millisecondi); la prima volta, appena l'indice dice dove
//    sono le foto. L'indice si costruisce **fuori dal thread principale** (`MapPoints.rebuild(OPEN)`: nulla all'avvio dell'app) e la pagina intanto e' una carta con il suo
//    avviso.
//  - **Bolle**: al piu' 60, ricalcolate **a camera ferma** o quando cambia il mezzo livello della griglia, **mai sul thread principale** (`compute`); quando l'indice cambia
//    (nuova istantanea, un nascosto) si rifanno da sole (E-MAP15).
//  - **Camera** (D29): iniziale `MapCameraPolicy.initial` (ultima locale, o tutte le foto con lo zoom <= 5, mai la casa); a ogni sosta si salva **solo in locale**.
//  - **Avvisi** (§2.10): una riga discreta, mai un dialogo.

/** Tutto cio' che la schermata della mappa disegna. [initial] null = non si sa ancora (primo fotogramma: carta senza mappa). Mai coordinate nei log. */
data class MapUi(
  val screen: MapScreenState,
  val style: MapStyleChoice,
  val initial: MapInitial? = null,
  val bubbles: List<PlacedBubble> = emptyList(),
  val selection: GeoE6? = null,
  /** P5: l'avanzamento (0..100) dello scaricamento di una zona senza Internet, o null se nessuno e' in corso: una riga discreta sopra la mappa. */
  val download: Int? = null,
) {
  override fun toString(): String = "MapUi($screen, ${style.asset}, bolle=${bubbles.size})"
}

/** Le impostazioni che hanno una lettura da aspettare prima di decidere la camera (il DataStore vero: `DataStoreMapSettings`); i fake non ne hanno. */
interface SettingsLoaded {
  suspend fun loaded()
}

class MapController(
  private val points: MapPoints,
  private val settings: MapSettings,
  private val regions: MapRegions,
  network: MapNetworkState,
  names: MapPlaceNames,
  private val filter: FinalFilter,
  private val positions: MapMediaPositions,
  /** L'istantanea della Libreria (senza nascosti): da li' si sa quali copertine hanno una cella da disegnare. */
  private val snapshot: StateFlow<TimelineSnapshot?>,
  private val scope: CoroutineScope,
  /** Il fuoco di "Mostra sulla mappa" (E-MAP19): il media da mostrare, o null. */
  private val focus: MediaId? = null,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
  /** La calma prima di salvare l'ultima camera (tempo del `CoroutineScope`: virtuale nei test). */
  private val saveCalmMs: Long = SAVE_CALM_MS,
  /** R5: vero se MapLibre non ha potuto spostare il suo database in `map/` (`MapLibreInit.cacheFailed`): la mappa resta sullo stile offline, qualunque cosa dica la scelta. */
  private val cacheFailed: StateFlow<Boolean> = NEVER_FAILED,
) : MapSurfaceListener {

  private val engine = BubbleEngine(names, filter)

  private val dark = MutableStateFlow(false)
  private val initial = MutableStateFlow<MapInitial?>(null)
  private val selection = MutableStateFlow<GeoE6?>(null)
  private val bubbles = MutableStateFlow<List<PlacedBubble>>(emptyList())
  private val pending = MutableStateFlow<MapViewport?>(null)

  @Volatile
  private var lastIdle: MapViewport? = null

  @Volatile
  private var lastLevel: Int = -1

  private var saveJob: Job? = null

  private val hasRegions: StateFlow<Boolean> = regions.regions.map { it.isNotEmpty() }.stateIn(scope, SharingStarted.Eagerly, regions.regions.value.isNotEmpty())

  /** La mappa online come la vede la pagina: con la cache fuori posto (R5) e' "spenta" (solo la carta semplice, nessuna richiesta). */
  private val onlineNow: StateFlow<MapOnline> = combine(settings.online, cacheFailed) { online, failed -> effectiveOnline(online, failed) }
    .stateIn(scope, SharingStarted.Eagerly, effectiveOnline(settings.online.value, cacheFailed.value))

  private val chosenStyle: StateFlow<MapStyleChoice> = combine(onlineNow, hasRegions, dark, cacheFailed) { online, any, isDark, failed -> MapStyles.choose(online, any && !failed, isDark) }
    .stateIn(scope, SharingStarted.Eagerly, MapStyles.choose(onlineNow.value, regions.regions.value.isNotEmpty() && !cacheFailed.value, dark = false))

  private val screen: StateFlow<MapScreenState> = combine(points.index, onlineNow, settings.wifiOnly, network.current) { index, online, wifiOnly, net ->
    when (index) {
      GeoIndexState.Building -> MapScreenState.Loading
      is GeoIndexState.Ready -> MapScreenState.Ready(online, MapNotices.of(online, wifiOnly, net), index.withoutPosition)
      is GeoIndexState.Empty -> MapScreenState.Empty(index.withoutPosition)
      // MA-1: un errore non e' "nessuna foto con la posizione".
      GeoIndexState.Failed -> MapScreenState.Failed
    }
  }.stateIn(scope, SharingStarted.Eagerly, MapScreenState.Loading)

  /** P5: lo scaricamento di una zona in corso (percentuale), per la riga sopra la mappa. */
  private val downloading: StateFlow<Int?> = regions.regions.map { list -> list.firstOrNull { it.status == RegionStatus.DOWNLOADING }?.percent }
    .stateIn(scope, SharingStarted.Eagerly, regions.regions.value.firstOrNull { it.status == RegionStatus.DOWNLOADING }?.percent)

  /** Lo stato di adesso. */
  val ui: StateFlow<MapUi> = combine(combine(screen, chosenStyle, initial, bubbles, selection) { s, st, start, b, sel -> MapUi(s, st, start, b, sel) }, downloading) { base, percent ->
    base.copy(download = percent)
  }.stateIn(scope, SharingStarted.Eagerly, MapUi(MapScreenState.Loading, chosenStyle.value, download = downloading.value))

  init {
    // L'indice si costruisce fuori dal thread principale e solo ora (mai all'avvio dell'app).
    scope.launch(compute) { points.rebuild(RebuildReason.OPEN) }
    scope.launch { decideInitial() }
    scope.launch {
      combine(pending.filterNotNull(), points.index) { viewport, state -> viewport to state }.collect { (viewport, state) ->
        val ready = state as? GeoIndexState.Ready
        bubbles.value = if (ready == null) emptyList() else withContext(compute) { engine.compute(ready.index, viewport, drawable(snapshot.value)) }
      }
    }
  }

  private fun drawable(snap: TimelineSnapshot?): (MediaId) -> Boolean = if (snap == null) {
    { true }
  } else {
    { id -> snap.indexOf(id) >= 0 }
  }

  /** "Riprova" dopo "Non riesco a leggere le posizioni delle foto" (MA-1): rifa `rebuild(OPEN)` fuori dal thread principale. */
  fun retry() {
    scope.launch(compute) { points.rebuild(RebuildReason.OPEN) }
  }

  /** Il tema scuro o chiaro dell'app, per scegliere lo stile. */
  fun setDark(isDark: Boolean) {
    dark.value = isDark
  }

  /** Il punto di partenza della camera: il fuoco, l'ultima posizione locale, tutte le foto (zoom <= 5) o il mondo. L'indice non si aspetta se c'e' una camera salvata. */
  private suspend fun decideInitial() {
    (settings as? SettingsLoaded)?.loaded()
    val focusSpot = focusSpot()
    val last = settings.lastCamera.value
    if (focusSpot != null) {
      selection.value = focusSpot.center
      initial.value = MapCameraPolicy.initial(last, null, focusSpot)
      return
    }
    if (last != null) {
      initial.value = MapCameraPolicy.initial(last, null)
      return
    }
    val state = points.index.first { it !is GeoIndexState.Building }
    initial.value = MapCameraPolicy.initial(null, (state as? GeoIndexState.Ready)?.index?.boundsOfAll())
  }

  /** "Mostra sulla mappa": la posizione del media, **mai** per un nascosto (E-H9); null se non c'e' o se non si puo' mostrare. */
  private suspend fun focusSpot(): CameraSpot? {
    val media = focus ?: return null
    if (media in filter.hiddenAmong(setOf(media))) return null
    val at = positions.positionOf(media) ?: return null
    return CameraSpot(at, MapCameraPolicy.FOCUS_ZOOM)
  }

  override fun onMoved(viewport: MapViewport) {
    val level = GeoGrid.levelFor(viewport.zoom, BubbleEngine.CELL_DP)
    if (level == lastLevel) return
    lastLevel = level
    pending.value = viewport
  }

  override fun onIdle(viewport: MapViewport) {
    lastLevel = GeoGrid.levelFor(viewport.zoom, BubbleEngine.CELL_DP)
    lastIdle = viewport
    // Una mappa ricreata (rotazione, E-MAP17) riparte da dove l'utente l'ha lasciata, non dal punto di partenza della prima volta.
    initial.value = MapInitial.Saved(CameraSpot(viewport.center, viewport.zoom.coerceIn(0.0, CameraSpot.MAX_ZOOM)))
    pending.value = viewport
    saveJob?.cancel()
    saveJob = scope.launch {
      delay(saveCalmMs)
      settings.saveCamera(CameraSpot(viewport.center, viewport.zoom.coerceIn(0.0, CameraSpot.MAX_ZOOM)))
    }
  }

  /** Cosa fa il tocco su [bubble] (D28): avvicinarsi o aprire la griglia della zona. */
  fun tapOf(bubble: PlacedBubble): BubbleTap {
    val zoom = lastIdle?.zoom ?: MapCameraPolicy.WORLD.zoom
    return BubbleTap.of(bubble, zoom, chosenStyle.value.maxZoom)
  }

  /** Salva subito l'ultima posizione (uscendo dalla pagina): solo in locale (D29). Parte subito, senza aspettare la calma. */
  fun flushCamera() {
    val viewport = lastIdle ?: return
    saveJob?.cancel()
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      settings.saveCamera(CameraSpot(viewport.center, viewport.zoom.coerceIn(0.0, CameraSpot.MAX_ZOOM)))
    }
  }

  companion object {
    /** La calma prima di salvare l'ultima camera. */
    const val SAVE_CALM_MS: Long = 1_000

    private val NEVER_FAILED: StateFlow<Boolean> = MutableStateFlow(false)

    /** Con la cache della mappa fuori posto la mappa online vale "spenta" (R5: fallisce chiusa); l'interruttore di build spento resta "non disponibile". */
    internal fun effectiveOnline(online: MapOnline, cacheFailed: Boolean): MapOnline = if (cacheFailed && online is MapOnline.On) MapOnline.Off else online
  }
}
