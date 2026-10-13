package dev.pampa.mycloudgallery.core.places.dataset

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * I micro-stati come **enclavi** (docs/luoghi.md §4): Citta' del Vaticano, San Marino, Monaco e Liechtenstein.
 *
 * Con il solo "luogo piu' vicino" un luogo piccolo ma "qualificato" (la Citta' del Vaticano e' capitale, con 826 abitanti)
 * a pochi km da una grande citta' vince su mezza Roma; con il solo peso della popolazione, una foto in Piazza San Pietro
 * diventerebbe "Roma". Il confine di uno stato non lo conosce nessun centroide, quindi qui ci sono i **poligoni**, scritti
 * a mano con vertici approssimati (sono dati di fatto: i contorni di quattro stati, non una copia di nessun dataset), e
 * la regola e' una sola:
 *
 * - se il punto sta **dentro** il poligono di un micro-stato (o a meno di [MARGIN_M] dal suo confine: la precisione di un
 *   GPS e' di decine di metri e i poligoni sono approssimati), vince quel paese: la ricerca guarda solo i suoi luoghi;
 * - altrimenti i luoghi dei micro-stati **non competono**: una foto a Roma non puo' essere "Citta' del Vaticano" e una
 *   foto a Mentone non puo' essere "Monaco", per quanto il centroide del micro-stato sia vicino.
 *
 * Gli stati sono indicati dal codice paese: se il dataset non ha luoghi di quel paese, il poligono non ha effetto.
 * Nessun poligono attraversa l'antimeridiano o un polo.
 */
internal object MicroStates {

  /** Entro questa distanza dal confine un punto conta come dentro (un GPS sbaglia di decine di metri). */
  const val MARGIN_M = 300.0

  private const val METERS_PER_DEGREE = Mcgp1Format.EARTH_RADIUS_M * Math.PI / 180.0

  class Enclave(val countryCode: String, vertices: DoubleArray) {
    private val n = vertices.size / 2

    init {
      require(n >= 3 && vertices.size % 2 == 0) { "Un poligono ha almeno tre vertici." }
    }

    private val lat = DoubleArray(n) { vertices[it * 2] }
    private val lon = DoubleArray(n) { vertices[it * 2 + 1] }
    private val minLat = lat.min()
    private val maxLat = lat.max()
    private val minLon = lon.min()
    private val maxLon = lon.max()

    /** Vero se il punto e' dentro il poligono o a meno di [marginM] dal suo confine. */
    fun covers(latitude: Double, longitude: Double, marginM: Double): Boolean {
      val dLat = marginM / METERS_PER_DEGREE
      val dLon = marginM / (METERS_PER_DEGREE * max(0.01, cos(Math.toRadians(latitude))))
      if (latitude < minLat - dLat || latitude > maxLat + dLat || longitude < minLon - dLon || longitude > maxLon + dLon) return false
      return contains(latitude, longitude) || boundaryDistanceM(latitude, longitude) <= marginM
    }

    /** Punto nel poligono (regola pari-dispari). Il poligono e' piccolo: bastano le coordinate in gradi. */
    fun contains(latitude: Double, longitude: Double): Boolean {
      var inside = false
      var j = n - 1
      for (i in 0 until n) {
        if ((lat[i] > latitude) != (lat[j] > latitude) &&
          longitude < (lon[j] - lon[i]) * (latitude - lat[i]) / (lat[j] - lat[i]) + lon[i]
        ) {
          inside = !inside
        }
        j = i
      }
      return inside
    }

    /** La distanza (m) dal punto al lato piu' vicino del poligono, su un piano locale centrato sul punto. */
    fun boundaryDistanceM(latitude: Double, longitude: Double): Double {
      val cosLat = cos(Math.toRadians(latitude))
      var best = Double.POSITIVE_INFINITY
      var j = n - 1
      for (i in 0 until n) {
        best = min(
          best,
          segmentDistance(
            (lon[j] - longitude) * cosLat * METERS_PER_DEGREE, (lat[j] - latitude) * METERS_PER_DEGREE,
            (lon[i] - longitude) * cosLat * METERS_PER_DEGREE, (lat[i] - latitude) * METERS_PER_DEGREE,
          ),
        )
        j = i
      }
      return best
    }

    /** La distanza dall'origine al segmento (ax, ay)-(bx, by). */
    private fun segmentDistance(ax: Double, ay: Double, bx: Double, by: Double): Double {
      val dx = bx - ax
      val dy = by - ay
      val length2 = dx * dx + dy * dy
      val t = if (length2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / length2
      val c = t.coerceIn(0.0, 1.0)
      val px = ax + c * dx
      val py = ay + c * dy
      return sqrt(px * px + py * py)
    }
  }

  // Vertici (latitudine, longitudine) in senso orario. Approssimati a circa 100 m: la regola ha un margine di 300 m.

  /** 0,44 km2. Dalla Piazza San Pietro (lato est) ai Giardini (lato ovest) e ai Musei (lato nord). */
  val VATICAN = Enclave(
    "VA",
    doubleArrayOf(
      41.9068, 12.4455, 41.9078, 12.4520, 41.9070, 12.4565, 41.9050, 12.4585, 41.9028, 12.4592,
      41.9006, 12.4585, 41.8998, 12.4550, 41.8990, 12.4500, 41.8995, 12.4465, 41.9025, 12.4448,
    ),
  )

  /** 61 km2. Dal monte Titano e dalle ville di Borgo Maggiore e Serravalle ai castelli del sud. */
  val SAN_MARINO = Enclave(
    "SM",
    doubleArrayOf(
      43.9920, 12.4650, 43.9800, 12.5050, 43.9450, 12.5160, 43.9100, 12.5020, 43.8970, 12.4750,
      43.9000, 12.4300, 43.9250, 12.4040, 43.9600, 12.4150, 43.9850, 12.4400,
    ),
  )

  /** 2 km2. La striscia costiera da Fontvieille a Larvotto. */
  val MONACO = Enclave(
    "MC",
    doubleArrayOf(
      43.7525, 7.4395, 43.7500, 7.4300, 43.7475, 7.4240, 43.7430, 7.4190, 43.7370, 7.4130, 43.7310, 7.4085,
      43.7265, 7.4100, 43.7285, 7.4190, 43.7315, 7.4280, 43.7380, 7.4380, 43.7440, 7.4425,
    ),
  )

  /** 160 km2. La valle del Reno da Ruggell a Balzers e i versanti fino a Malbun. */
  val LIECHTENSTEIN = Enclave(
    "LI",
    doubleArrayOf(
      47.2700, 9.5200, 47.2350, 9.5650, 47.1800, 9.6050, 47.1100, 9.6300, 47.0550, 9.6000,
      47.0500, 9.5200, 47.0700, 9.4970, 47.1500, 9.4980, 47.2300, 9.5050,
    ),
  )

  val ALL: List<Enclave> = listOf(VATICAN, SAN_MARINO, MONACO, LIECHTENSTEIN)

  /** I codici dei paesi trattati come enclavi. */
  val CODES: Set<String> = ALL.map { it.countryCode }.toSet()

  /** L'enclave che contiene il punto (o ne e' a meno di [MARGIN_M]), o null. */
  fun enclaveAt(latitude: Double, longitude: Double): Enclave? {
    for (enclave in ALL) if (enclave.covers(latitude, longitude, MARGIN_M)) return enclave
    return null
  }
}
