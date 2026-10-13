package dev.pampa.mycloudgallery.core.places

import dev.pampa.mycloudgallery.core.places.dataset.DatasetData
import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1
import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1Format
import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1Writer
import dev.pampa.mycloudgallery.core.places.dataset.PlaceRow
import dev.pampa.mycloudgallery.core.places.dataset.RegionRow
import dev.pampa.mycloudgallery.core.places.tool.GeoNamesInput
import dev.pampa.mycloudgallery.core.places.tool.PlacesDatasetBuilder
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Attrezzi comuni dei test di core-places. */
internal object TestData {

  fun place(
    id: Int,
    lat: Double,
    lon: Double,
    name: String = "Luogo $id",
    cc: String = "IT",
    region: String? = null,
    zone: String? = "Europe/Rome",
    cls: Int = 1,
    pop: Long = 5_000L,
  ) = PlaceRow(id, lat, lon, name, cc, region, zone, cls, pop)

  fun bytes(places: List<PlaceRow>, regions: List<RegionRow> = emptyList(), version: Int = 20240101): ByteArray =
    Mcgp1Writer.write(DatasetData(version, places, regions))

  fun open(bytes: ByteArray, verify: Boolean = true): Mcgp1 = Mcgp1.open(ByteBuffer.wrap(bytes), verify)

  /** La distanza di haversine fra due punti (gradi), in metri, esattamente come la calcola il lettore sulle unita' arrotondate. */
  fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = phi2 - phi1
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
    return 2 * Mcgp1Format.EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
  }

  /** La latitudine e la longitudine di un punto come le vede il file: arrotondate a 1e-5 gradi. */
  fun snapped(lat: Double, lon: Double): Pair<Double, Double> {
    val la = Mcgp1Format.latUnits(lat) / 100_000.0 - 90.0
    var lo = Mcgp1Format.lonUnits(lon) / 100_000.0 - 180.0
    if (lo > 180.0) lo -= 360.0
    return la to lo
  }

  /** Il risultato della forza bruta: il luogo, la sua distanza e il suo punteggio (distanza meno raggio d'influenza). */
  class Brute(val place: PlaceRow, val distanceM: Double, val score: Double)

  /**
   * Il luogo qualificato col punteggio piu' basso per forza bruta, sulle stesse coordinate arrotondate del file e con lo
   * STESSO punteggio della ricerca: distanza - raggio d'influenza (dal byte di popolazione). Solo i luoghi entro [capM].
   * Non conosce le enclave: i dataset di prova casuali non hanno luoghi di micro-stati.
   */
  fun bruteNearest(places: List<PlaceRow>, lat: Double, lon: Double, capM: Double = Double.POSITIVE_INFINITY): Brute? {
    val (qLat, qLon) = snapped(lat, lon)
    var best: Brute? = null
    for (p in places) {
      if (p.cls == 0) continue
      val (pLat, pLon) = snapped(p.latitude, p.longitude)
      val d = haversineM(qLat, qLon, pLat, pLon)
      if (d > capM) continue
      val score = d - Mcgp1Format.INFLUENCE_RADIUS_M[Mcgp1Format.populationBucket(p.population)]
      val current = best
      if (current == null || score < current.score || (score == current.score && p.id < current.place.id)) {
        best = Brute(p, d, score)
      }
    }
    return best
  }

  /** La cartella con i dump di prova di GeoNames (sul disco, perche' lo strumento legge file). */
  val miniDirectory: File get() = File("src/test/resources/places/geonames-mini")

  private fun resource(name: String): InputStream =
    checkNotNull(TestData::class.java.classLoader.getResourceAsStream(name)) { "Manca la risorsa di test $name" }

  /** I dump di prova come flussi. */
  fun miniInput() = GeoNamesInput(
    cities = { resource("places/geonames-mini/cities1000.txt") },
    italy = { resource("places/geonames-mini/IT.txt") },
    admin1 = { resource("places/geonames-mini/admin1CodesASCII.txt") },
    alternateNames = { resource("places/geonames-mini/alternateNamesV2.txt") },
    description = listOf("mini"),
  )

  /** Il dataset di prova (poche centinaia di luoghi reali) costruito dallo stesso strumento che costruira' quello vero. */
  val miniBytes: ByteArray by lazy { Mcgp1Writer.write(PlacesDatasetBuilder.build(miniInput()).data) }

  fun readTsv(name: String): List<List<String>> =
    resource("places/$name").bufferedReader(Charsets.UTF_8).readLines()
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .map { it.split('\t') }
}
