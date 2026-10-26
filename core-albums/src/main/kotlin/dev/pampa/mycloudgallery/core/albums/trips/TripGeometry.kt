package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.TripRules
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// VIAGGI ED EVENTI (docs/fase7-progetto.md §2.3, D18-D20, §1.1): la geometria. **Tutto `internal`**: le coordinate (e quindi la "casa") non
// escono da trips/. Nessuna API pubblica le restituisce (TripPrivacyTest): fuori da qui si sa solo "questo giorno e' lontano da casa si'/no" e
// la distanza arrotondata a 10 km (`AwayFromHome`).

internal object TripGeometry {
  private const val EARTH_KM = 6_371.0

  /** La distanza sulla sfera (haversine), in km. */
  fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val h = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
    return 2 * EARTH_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
  }

  /** Un GPS che si puo' usare: finito e non il (0, 0) di una posizione mai letta, l'errore piu' comune dei telefoni. */
  fun usable(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() && abs(lat) <= 90.0 && abs(lon) <= 180.0 && !(abs(lat) < NULL_ISLAND && abs(lon) < NULL_ISLAND)

  private const val NULL_ISLAND = 1e-4

  // --- celle di ~1 km (0,01 gradi): una chiave a 64 bit, con le vicine a distanza di un passo -------------------------------------

  fun cellLat(lat: Double): Int = floor(lat / TripRules.HOME_CELL_DEGREES).toInt()

  fun cellLon(lon: Double): Int = floor(lon / TripRules.HOME_CELL_DEGREES).toInt()

  fun cell(latCell: Int, lonCell: Int): Long = (latCell.toLong() shl 32) or (lonCell.toLong() and 0xffffffffL)

  fun cellOf(lat: Double, lon: Double): Long = cell(cellLat(lat), cellLon(lon))

  fun latOf(cell: Long): Int = (cell shr 32).toInt()

  fun lonOf(cell: Long): Int = cell.toInt()

  /** Le nove celle attorno a [cell], la stessa compresa (la "casa" e' la cella piu' pesante con le sue 8 vicine). */
  fun block(cell: Long): LongArray {
    val la = latOf(cell)
    val lo = lonOf(cell)
    return LongArray(9) { i -> cell(la + i / 3 - 1, lo + i % 3 - 1) }
  }

  /** Due celle a non piu' di [steps] passi l'una dall'altra (distanza di Chebyshev). */
  fun closeCells(a: Long, b: Long, steps: Int): Boolean = abs(latOf(a) - latOf(b)) <= steps && abs(lonOf(a) - lonOf(b)) <= steps

  // --- statistica -----------------------------------------------------------------------------------------------------------------

  /** La mediana di [sorted] (gia' in ordine crescente, non vuota). */
  fun median(sorted: DoubleArray): Double {
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
  }

  /** Il percentile [percent] (1..100) di [sorted] (gia' in ordine crescente, non vuota): il valore al posto `ceil(n * p / 100)`. */
  fun percentile(sorted: DoubleArray, percent: Int): Double {
    val rank = (sorted.size * percent + 99) / 100
    return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
  }

  /** La distanza da casa come esce da trips/: arrotondata al multiplo di 10 km piu' vicino (D18, §1.1). */
  fun roundedKm(km: Double): Int {
    val step = TripRules.DISTANCE_ROUNDING_KM
    return (Math.round(km / step) * step).toInt()
  }

  /**
   * Il cerchio che copre [points] (latitudine, longitudine): un centro approssimato dell'insieme piu' piccolo (Badoiu-Clarkson, 48 passi)
   * e il raggio in km fino al punto piu' lontano. Per un viaggio in una citta' e' il centro; per un giro in piu' luoghi non si sposta
   * verso quello con piu' foto. [points] non e' vuoto.
   */
  fun enclosing(points: List<DoubleArray>): Triple<Double, Double, Double> {
    var lat = points.sumOf { it[0] } / points.size
    var lon = points.sumOf { it[1] } / points.size
    for (step in 1..ENCLOSING_STEPS) {
      var far = points[0]
      var best = -1.0
      for (p in points) {
        val d = km(lat, lon, p[0], p[1])
        if (d > best) {
          best = d
          far = p
        }
      }
      lat += (far[0] - lat) / (step + 1)
      lon += (far[1] - lon) / (step + 1)
    }
    return Triple(lat, lon, points.maxOf { km(lat, lon, it[0], it[1]) })
  }

  private const val ENCLOSING_STEPS = 48
}
