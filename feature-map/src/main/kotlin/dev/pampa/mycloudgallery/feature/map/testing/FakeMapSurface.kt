package dev.pampa.mycloudgallery.feature.map.testing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.feature.map.map.MapSurface
import dev.pampa.mycloudgallery.feature.map.map.MapSurfaceContent
import dev.pampa.mycloudgallery.feature.map.map.MapSurfaceRequest
import dev.pampa.mycloudgallery.feature.map.map.MapViewport
import dev.pampa.mycloudgallery.feature.map.map.MapViewportMath
import dev.pampa.mycloudgallery.feature.map.map.WebMercator
import dev.pampa.mycloudgallery.feature.map.ui.MapDimens

/**
 * La mappa **finta** (Fase 9, P4): MapLibre vero non si disegna in Robolectric, quindi gli screenshot e i test di composizione usano questa. Una proiezione di Mercatore con tessere da 512 dp (la stessa di MapLibre
 * e di `GeoGrid`) sopra una camera che si sposta con [pan], [zoomTo] e [moveTo]; ogni movimento fa avanzare il fotogramma ([frame]) e avvisa il controllore come farebbe la mappa vera ([settle] = camera ferma).
 * Il disegno e' solo una carta piatta con le linee di una griglia e il punto selezionato: **non** dice niente su come MapLibre disegnera' la carta (si guarda sul telefono, F119 e F122). Misure in dp; i pixel
 * sono dp x [density]. Nessun dato vero: il centro e le coordinate sono quelli che il test sceglie (citta' pubbliche).
 */
class FakeMapSurface(
  center: GeoE6,
  zoom: Double,
  private val widthDp: Int = 412,
  private val heightDp: Int = 892,
  private val density: Float = 2f,
) : MapSurface() {

  private var centerNow = center
  private var zoomNow = zoom

  /** Le camere chieste con [animateTo], nell'ordine (per i test del tocco su una bolla). */
  val animations: MutableList<CameraSpot> = mutableListOf()

  override val viewport: MapViewport
    get() {
      val cx = WebMercator.x(centerNow.lonE6 / 1_000_000.0, zoomNow)
      val cy = WebMercator.y(centerNow.latE6 / 1_000_000.0, zoomNow)
      val west = WebMercator.lon(cx - widthDp / 2.0, zoomNow)
      val east = WebMercator.lon(cx + widthDp / 2.0, zoomNow)
      val north = WebMercator.lat(cy - heightDp / 2.0, zoomNow)
      val south = WebMercator.lat(cy + heightDp / 2.0, zoomNow)
      return MapViewport(centerNow, zoomNow, MapViewportMath.boxOf(south, west, north, east), widthDp, heightDp)
    }

  override fun project(point: GeoE6): Offset {
    val cx = WebMercator.x(centerNow.lonE6 / 1_000_000.0, zoomNow)
    val cy = WebMercator.y(centerNow.latE6 / 1_000_000.0, zoomNow)
    val x = WebMercator.x(point.lonE6 / 1_000_000.0, zoomNow) - cx + widthDp / 2.0
    val y = WebMercator.y(point.latE6 / 1_000_000.0, zoomNow) - cy + heightDp / 2.0
    return Offset((x * density).toFloat(), (y * density).toFloat())
  }

  override fun moveTo(spot: CameraSpot) {
    centerNow = spot.center
    zoomNow = spot.zoom
    tick()
    listener?.onMoved(viewport)
  }

  override fun animateTo(spot: CameraSpot) {
    animations += spot
    moveTo(spot)
    settle()
  }

  /** Il dito sposta la mappa di [dxDp] x [dyDp] dp (positivo = il contenuto va a destra e in basso): un fotogramma di movimento. */
  fun pan(dxDp: Double, dyDp: Double) {
    val cx = WebMercator.x(centerNow.lonE6 / 1_000_000.0, zoomNow) - dxDp
    val cy = WebMercator.y(centerNow.latE6 / 1_000_000.0, zoomNow) - dyDp
    centerNow = GeoE6(StrictMath.round(WebMercator.lat(cy, zoomNow) * 1_000_000.0).toInt(), StrictMath.round(WebMercator.lon(cx, zoomNow) * 1_000_000.0).toInt())
    tick()
    listener?.onMoved(viewport)
  }

  /** Cambia lo zoom (un gesto di pizzico): un fotogramma di movimento. */
  fun zoomTo(zoom: Double) {
    zoomNow = zoom
    tick()
    listener?.onMoved(viewport)
  }

  /** La camera si ferma: il controllore ricalcola le bolle e salva la posizione. */
  fun settle() {
    listener?.onIdle(viewport)
  }

  /** Una `MapSurfaceContent` che disegna questa mappa finta. */
  fun content(): MapSurfaceContent = FakeMapSurfaceContent(this)
}

/** Il disegno della mappa finta: una carta piatta con le linee di una griglia e il punto selezionato. */
class FakeMapSurfaceContent(private val surface: FakeMapSurface) : MapSurfaceContent {
  override fun createSurface(): MapSurface = surface

  @Composable
  override fun Content(surface: MapSurface, request: MapSurfaceRequest, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val land = if (request.style.offline) scheme.surfaceContainerHigh else scheme.surfaceContainerLow
    val line = scheme.outlineVariant
    val water = scheme.secondaryContainer
    val dot = Color(request.selectionColor)
    val ring = Color(request.selectionRing)
    Box(modifier.fillMaxSize().background(land)) {
      Canvas(Modifier.fillMaxSize()) {
        val step = MapDimens.FakeGrid.toPx()
        val stroke = MapDimens.FakeLine.toPx()
        // Un "mare" in basso a sinistra e le linee della griglia: solo per dare un fondo alla carta.
        drawRect(water, topLeft = Offset(0f, size.height * SEA_TOP), size = androidx.compose.ui.geometry.Size(size.width * SEA_WIDTH, size.height * (1f - SEA_TOP)))
        var x = 0f
        while (x < size.width) {
          drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
          x += step
        }
        var y = 0f
        while (y < size.height) {
          drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
          y += step
        }
        request.selection?.let { at ->
          val p = surface.project(at) ?: return@let
          drawCircle(ring, radius = SELECTION_RING * density, center = p)
          drawCircle(dot, radius = SELECTION_DOT * density, center = p)
        }
      }
    }
  }

  private companion object {
    const val SEA_TOP = 0.62f
    const val SEA_WIDTH = 0.55f
    const val SELECTION_RING = 9f
    const val SELECTION_DOT = 6f
  }
}
