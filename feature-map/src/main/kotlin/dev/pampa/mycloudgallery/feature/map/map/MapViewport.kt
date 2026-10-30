package dev.pampa.mycloudgallery.feature.map.map

import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapCameraRules
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

// LA VISTA DELLA MAPPA E LA CAMERA (Fase 9, P4; docs/fase9-progetto.md §2.10 "Apertura" e "Uscita", D28, D29, E-MAP10, E-MAP17). Funzioni **pure**: la vista
// che la mappa (vera o finta) consegna al controllore, la proiezione di Mercatore con tessere da 512 (la stessa di `GeoGrid`), e la regola della camera
// iniziale: **l'ultima posizione salvata solo in locale; la prima volta "tutte le foto" con lo zoom non oltre 5** (circa un paese), cosi' la prima
// apertura online non chiede tessere dettagliate della zona dove ci sono piu' foto (che e' quasi sempre casa). Mai la casa, mai una coordinata nei log.

/** Quello che serve a raggruppare: il centro, lo zoom, il riquadro visibile (con l'antimeridiano, E-MAP10) e la misura in dp. Nessuna coordinata nel `toString()`. */
data class MapViewport(val center: GeoE6, val zoom: Double, val box: GeoBox, val widthDp: Int, val heightDp: Int) {
  override fun toString(): String = "MapViewport($zoom, ${widthDp}x$heightDp)"
}

/** Dove parte la camera di una mappa appena aperta. */
sealed interface MapInitial {
  /** L'ultima posizione salvata in locale (D29): vale quella, anche con lo zoom alto. */
  data class Saved(val spot: CameraSpot) : MapInitial

  /** Mai salvata: tutte le foto, con lo zoom non oltre [MapCameraRules.FIRST_MAX_ZOOM]. */
  data class FitAll(val box: GeoBox) : MapInitial {
    override fun toString(): String = "MapInitial.FitAll"
  }

  /** "Mostra sulla mappa" (E-MAP19): su quel punto, a [spot]. Un'azione dell'utente: lo zoom puo' essere alto. */
  data class Focus(val spot: CameraSpot) : MapInitial

  /** Nessun punto e nessuna camera salvata: il mondo, senza favorire nessun posto. */
  data object World : MapInitial
}

/** La proiezione di Mercatore a tessere da 512 dp (quella di MapLibre e di `GeoGrid`): coordinate di mondo in dp a uno zoom, e il suo inverso. */
object WebMercator {
  const val TILE_DP: Double = 512.0
  private const val MAX_LAT: Double = 85.0511287798

  fun worldDp(zoom: Double): Double = TILE_DP * 2.0.pow(zoom)

  fun x(lonDeg: Double, zoom: Double): Double = (lonDeg + 180.0) / 360.0 * worldDp(zoom)

  fun y(latDeg: Double, zoom: Double): Double {
    val s = sin(Math.toRadians(latDeg.coerceIn(-MAX_LAT, MAX_LAT)))
    return (0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)) * worldDp(zoom)
  }

  fun lon(xDp: Double, zoom: Double): Double = xDp / worldDp(zoom) * 360.0 - 180.0

  fun lat(yDp: Double, zoom: Double): Double = Math.toDegrees(atan(sinh(PI - 2.0 * PI * yDp / worldDp(zoom))))
}

object MapViewportMath {

  /** Il riquadro di microgradi per [south], [west], [north], [east] in gradi, anche fuori dall'intervallo (MapLibre a zoom basso vede piu' di un mondo). */
  fun boxOf(southDeg: Double, westDeg: Double, northDeg: Double, eastDeg: Double): GeoBox {
    val south = toE6(southDeg).coerceIn(-GeoGrid.LAT_E6, GeoGrid.LAT_E6)
    val north = toE6(northDeg).coerceIn(-GeoGrid.LAT_E6, GeoGrid.LAT_E6)
    // Piu' di un giro del mondo (zoom bassissimo): tutto il mondo, senza l'antimeridiano.
    if (eastDeg - westDeg >= 360.0) return GeoBox(-GeoGrid.LON_E6, min(south, north), GeoGrid.LON_E6, max(south, north))
    return GeoBox(wrapLon(westDeg), min(south, north), wrapLon(eastDeg), max(south, north))
  }

  /** La longitudine in microgradi riportata fra -180 e 180 (+180 resta 180: e' il bordo, non -180). */
  fun wrapLon(deg: Double): Int {
    var d = deg
    if (d > 180.0 || d < -180.0) {
      d = ((d + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
      if (d == -180.0 && deg > 0) d = 180.0
    }
    return toE6(d).coerceIn(-GeoGrid.LON_E6, GeoGrid.LON_E6)
  }

  private fun toE6(deg: Double): Int = StrictMath.round(deg * 1_000_000.0).coerceIn(-2_000_000_000L, 2_000_000_000L).toInt()

  /**
   * Lo zoom a cui [box] sta in una vista di [widthDp] x [heightDp] con un margine di [paddingDp], al massimo [maxZoom] (la prima volta
   * [MapCameraRules.FIRST_MAX_ZOOM]): un solo punto (riquadro senza ampiezza) vale il massimo. Mai sotto 0.
   */
  fun fitZoom(box: GeoBox, widthDp: Int, heightDp: Int, paddingDp: Int, maxZoom: Double): Double {
    val availW = (widthDp - 2 * paddingDp).coerceAtLeast(1).toDouble()
    val availH = (heightDp - 2 * paddingDp).coerceAtLeast(1).toDouble()
    val spanX = ((box.eastE6 - box.westE6).toDouble() / 1_000_000.0).let { if (it < 0) it + 360.0 else it } / 360.0
    val north = WebMercator.y(box.northE6 / 1_000_000.0, 0.0) / WebMercator.TILE_DP
    val south = WebMercator.y(box.southE6 / 1_000_000.0, 0.0) / WebMercator.TILE_DP
    val spanY = south - north
    val zx = if (spanX <= 0.0) maxZoom else ln(availW / (WebMercator.TILE_DP * spanX)) / ln(2.0)
    val zy = if (spanY <= 0.0) maxZoom else ln(availH / (WebMercator.TILE_DP * spanY)) / ln(2.0)
    return min(zx, zy).coerceIn(0.0, maxZoom)
  }

  /** Il centro di [box] in microgradi (con l'antimeridiano: dall'altra parte del mondo, a meta' del giro). */
  fun centerOf(box: GeoBox): GeoE6 {
    val lat = ((box.southE6.toLong() + box.northE6) / 2).toInt()
    val width = if (box.crossesAntimeridian) box.eastE6.toLong() + 360_000_000L - box.westE6 else box.eastE6.toLong() - box.westE6
    var lon = box.westE6 + width / 2
    if (lon > GeoGrid.LON_E6) lon -= 360_000_000L
    return GeoE6(lat, lon.toInt())
  }
}

/** La regola della camera iniziale (D29), una funzione sola, provata. */
object MapCameraPolicy {
  /** L'inquadratura di una mappa che si apre: chi la salva ricordera' l'ultima. [bounds] = il riquadro di tutte le foto con la posizione (null = nessuna). */
  fun initial(last: CameraSpot?, bounds: GeoBox?, focus: CameraSpot? = null): MapInitial = when {
    focus != null -> MapInitial.Focus(focus)
    last != null -> MapInitial.Saved(last)
    bounds != null -> MapInitial.FitAll(bounds)
    else -> MapInitial.World
  }

  /** Il mondo, quando non c'e' niente: lo zoom basso, il centro neutro (non "dove si vive"). */
  val WORLD: CameraSpot = CameraSpot(GeoE6(20_000_000, 10_000_000), 1.5)

  /** Il punto di partenza per una vista di [widthDp] x [heightDp]: mai oltre lo zoom 5 la prima volta; l'ultima posizione e il fuoco come sono. */
  fun spotFor(initial: MapInitial, widthDp: Int, heightDp: Int): CameraSpot = when (initial) {
    is MapInitial.Saved -> initial.spot
    is MapInitial.Focus -> initial.spot
    is MapInitial.FitAll -> CameraSpot(
      MapViewportMath.centerOf(initial.box),
      MapViewportMath.fitZoom(initial.box, widthDp, heightDp, FIT_PADDING_DP, MapCameraRules.FIRST_MAX_ZOOM),
    )
    MapInitial.World -> WORLD
  }

  /** Il margine attorno a tutte le foto, in dp (le bolle non finiscono sul bordo). */
  const val FIT_PADDING_DP: Int = 48

  /** Lo zoom di "Mostra sulla mappa": abbastanza vicino da riconoscere il posto; con lo stile solo offline si ferma al suo massimo. */
  const val FOCUS_ZOOM: Double = 13.0
}
