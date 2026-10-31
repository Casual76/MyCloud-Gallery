package dev.pampa.mycloudgallery.feature.map.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

// LA MAPPA VERA (Fase 9, P4; docs/fase9-progetto.md D21, §2.10 "Apertura", L430, E-MAP17, E-MAP19). L'unico file di `map/` (con `MapLibreMiniMap`) che chiama MapLibre: tutto il resto
// della pagina parla con [MapSurface]. **MapLibre non si disegna in Robolectric**: questa parte si prova sul telefono (F119, F122) e in `MapNetDeviceTest` per la rete; le bolle, il
// controllore e la pagina si provano sopra la superficie finta.
//
//  - **Pigra**: [MapEngineStarter.ensure] (`MapLibreInit`, con il nostro client installato **prima** di ogni `MapView`) parte **dopo il primo fotogramma**, mai nella composizione che
//    apre la pagina. Senza MapLibre pronto non nasce nessuna `MapView` (carta vuota, la pagina funziona).
//  - **Stile da `asset://`** (P2): online o offline come dice [MapSurfaceRequest.style]; **dopo** il caricamento dello stile si aggiunge il livello della selezione (E-MAP19), sopra
//    tutti gli altri. Le bolle non sono livelli della mappa ma composable (D28).
//  - **Attribuzione di MapLibre spenta** (il suo pulsante apre un collegamento nel browser): il credito lo scrive la pagina (`MapCredit`).
//  - **Nord in alto** (niente rotazione ne' inclinazione): le bolle e le celle restano semplici. Lo zoom si ferma al massimo dello stile (9 con la sola carta di base).
//  - Il precaricamento dei livelli piu' bassi resta spento (`MapLibreInit.mapOptions` e `configure`).

/** La [MapSurface] di MapLibre: collega una `MapLibreMap` pronta e la traduce in vista, proiezione e comandi di camera. Tutto sul thread principale. */
internal class MapLibreSurface : MapSurface() {
  private var map: MapLibreMap? = null
  private var view: MapView? = null
  private var density: Float = 1f
  private var sentLevel: Int = -1

  override val viewport: MapViewport? get() = readViewport()

  /**
   * Il tempo di disegno di ogni fotogramma (Fase 9, P12, §5.4: "tempo di disegno di MapLibre, p95 per fotogramma"), come lo misura MapLibre: un contatore di traccia
   * (`MCG:map.drawUs`, in microsecondi; MapLibre lo da' in secondi). Una classe anonima e non una lambda: fuori dalle fusioni di R8 con `MapLibreSurface*`.
   */
  private val drawStats = object : MapView.OnDidFinishRenderingFrameListener {
    override fun onDidFinishRenderingFrame(fully: Boolean, frameEncodingTime: Double, frameRenderingTime: Double) {
      AvvioTrace.counter(AvvioTrace.MAP_DRAW_US, (frameRenderingTime * MICROS_PER_SECOND).toLong())
    }
  }

  fun attach(map: MapLibreMap, view: MapView) {
    this.map = map
    this.view = view
    view.addOnDidFinishRenderingFrameListener(drawStats)
    density = view.resources.displayMetrics.density
    map.addOnCameraMoveListener {
      tick()
      val level = GeoGrid.levelFor(map.cameraPosition.zoom, BubbleEngine.CELL_DP)
      if (level != sentLevel) {
        sentLevel = level
        readViewport()?.let { listener?.onMoved(it) }
      }
    }
    map.addOnCameraIdleListener {
      tick()
      readViewport()?.let {
        sentLevel = GeoGrid.levelFor(it.zoom, BubbleEngine.CELL_DP)
        listener?.onIdle(it)
      }
    }
    tick()
  }

  fun detach() {
    view?.removeOnDidFinishRenderingFrameListener(drawStats)
    map = null
    view = null
  }

  private fun readViewport(): MapViewport? {
    val m = map ?: return null
    val v = view ?: return null
    if (v.width <= 0 || v.height <= 0) return null
    val bounds = m.projection.visibleRegion.latLngBounds
    val position = m.cameraPosition
    val target = position.target ?: return null
    val box = MapViewportMath.boxOf(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast)
    val center = GeoE6(StrictMath.round(target.latitude * 1_000_000.0).toInt(), MapViewportMath.wrapLon(target.longitude))
    return MapViewport(center, position.zoom, box, (v.width / density).toInt(), (v.height / density).toInt())
  }

  override fun project(point: GeoE6): Offset? {
    val m = map ?: return null
    val p = m.projection.toScreenLocation(LatLng(point.latE6 / 1_000_000.0, point.lonE6 / 1_000_000.0))
    return Offset(p.x, p.y)
  }

  override fun moveTo(spot: CameraSpot) {
    map?.moveCamera(CameraUpdateFactory.newCameraPosition(positionOf(spot)))
  }

  override fun animateTo(spot: CameraSpot) {
    map?.animateCamera(CameraUpdateFactory.newCameraPosition(positionOf(spot)), ANIMATION_MS)
  }

  /** Lo stile della richiesta e, **dopo il suo caricamento**, la selezione sopra tutti gli altri livelli. */
  fun applyStyle(request: MapSurfaceRequest) {
    val m = map ?: return
    m.setMinZoomPreference(0.0)
    m.setMaxZoomPreference(request.style.maxZoom)
    m.setStyle(Style.Builder().fromUri(request.style.uri)) { style ->
      val at = request.selection ?: return@setStyle
      val json = "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[${at.lonE6 / 1_000_000.0},${at.latE6 / 1_000_000.0}]},\"properties\":{}}"
      style.addSource(GeoJsonSource(SELECTION_SOURCE, json))
      style.addLayer(
        CircleLayer(SELECTION_LAYER, SELECTION_SOURCE).withProperties(
          PropertyFactory.circleRadius(SELECTION_RADIUS),
          PropertyFactory.circleColor(request.selectionColor),
          PropertyFactory.circleStrokeColor(request.selectionRing),
          PropertyFactory.circleStrokeWidth(SELECTION_STROKE),
        ),
      )
    }
  }

  private fun positionOf(spot: CameraSpot): CameraPosition =
    CameraPosition.Builder().target(LatLng(spot.center.latE6 / 1_000_000.0, spot.center.lonE6 / 1_000_000.0)).zoom(spot.zoom).build()

  private companion object {
    const val MICROS_PER_SECOND = 1_000_000.0
    const val ANIMATION_MS = 450
    const val SELECTION_SOURCE = "mcg-selection"
    const val SELECTION_LAYER = "mcg-selection-layer"
    const val SELECTION_RADIUS = 8f
    const val SELECTION_STROKE = 3f
  }
}

/** La mappa vera dentro la pagina. [starter] avvia MapLibre (con il nostro client) la prima volta, dopo il primo fotogramma. */
class MapLibreSurfaceContent(private val starter: MapEngineStarter) : MapSurfaceContent {

  override fun createSurface(): MapSurface = MapLibreSurface()

  @Composable
  override fun Content(surface: MapSurface, request: MapSurfaceRequest, modifier: Modifier) {
    val real = surface as MapLibreSurface
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ready = starter.ensure(context) }
    if (!ready) {
      Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer))
      return
    }
    BoxWithConstraints(modifier) {
      val spot = remember(request.initial) { MapCameraPolicy.spotFor(request.initial, maxWidth.value.toInt(), maxHeight.value.toInt()) }
      val view = remember {
        val options = MapLibreInit.mapOptions(context).camera(
          CameraPosition.Builder().target(LatLng(spot.center.latE6 / 1_000_000.0, spot.center.lonE6 / 1_000_000.0)).zoom(spot.zoom).build(),
        )
        MapView(context, options)
      }
      MapViewLifecycle(view)
      LaunchedEffect(view) {
        view.getMapAsync { map ->
          MapLibreInit.configure(map)
          map.uiSettings.apply {
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isAttributionEnabled = false
            isLogoEnabled = false
            isCompassEnabled = false
          }
          real.attach(map, view)
          real.applyStyle(request)
        }
      }
      LaunchedEffect(request.style.asset, request.selection) { real.applyStyle(request) }
      DisposableEffect(view) { onDispose { real.detach() } }
      AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    }
  }
}

/** Il ciclo di vita della `MapView` segue quello della pagina (MapLibre lo vuole): creata, avviata, ripresa, e al contrario; e a ogni richiamo di memoria (app in secondo piano o memoria che manca) la libera (`onTrimMemory`, §1.4). */
// Fase 9, P13: senza gruppo di ripartenza (non legge stato: due soli effetti). La lambda di ripartenza che Compose genera porterebbe `MapView` nella firma, e
// R8 la fondeva con lambda di :app della stessa forma: il passo 7h di tools/check.ps1 vedeva "MapLibre chiamato da codice nostro fuori da feature/map/".
@Composable
@NonRestartableComposable
private fun MapViewLifecycle(view: MapView) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val context = LocalContext.current
  DisposableEffect(lifecycle, view) {
    var created = false
    var started = false
    var resumed = false
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_CREATE -> if (!created) { view.onCreate(null); created = true }
        Lifecycle.Event.ON_START -> if (created && !started) { view.onStart(); started = true }
        Lifecycle.Event.ON_RESUME -> if (started && !resumed) { view.onResume(); resumed = true }
        Lifecycle.Event.ON_PAUSE -> if (resumed) { view.onPause(); resumed = false }
        Lifecycle.Event.ON_STOP -> if (started) { view.onStop(); started = false }
        Lifecycle.Event.ON_DESTROY -> if (created) { view.onDestroy(); created = false }
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      if (resumed) view.onPause()
      if (started) view.onStop()
      if (created) view.onDestroy()
    }
  }
  DisposableEffect(context, view) {
    val callbacks = object : ComponentCallbacks2 {
      override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) view.onLowMemory()
      }

      override fun onConfigurationChanged(newConfig: Configuration) = Unit

      @Suppress("OVERRIDE_DEPRECATION")
      override fun onLowMemory() = view.onLowMemory()
    }
    context.registerComponentCallbacks(callbacks)
    onDispose { context.unregisterComponentCallbacks(callbacks) }
  }
}
