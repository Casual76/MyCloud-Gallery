package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.places.dataset.PlaceRow
import kotlin.math.cos
import kotlin.math.max
import kotlin.random.Random
import org.junit.Test

/**
 * La ricerca ad anelli confrontata con la FORZA BRUTA su dataset casuali: stesso luogo, stessa distanza, anche
 * all'antimeridiano e ai poli. E' il test che da' fiducia ai limiti inferiori della ricerca (se fossero sbagliati,
 * la ricerca si fermerebbe troppo presto e sbaglierebbe luogo).
 */
class RingSearchTest {

  private fun checkAgainstBruteForce(
    places: List<PlaceRow>,
    queries: List<Pair<Double, Double>>,
    capM: Double,
    label: String,
  ) {
    val file = TestData.open(TestData.bytes(places))
    var found = 0
    for ((lat, lon) in queries) {
      val expected = TestData.bruteNearest(places, lat, lon, capM)
      val hit = file.nearest(lat, lon, capM)
      if (expected == null) {
        assertThat(hit).isNull()
      } else {
        found++
        assertThat(hit).isNotNull()
        // Stesso punteggio (distanza - raggio d'influenza); stesso luogo salvo parita' entro un millimetro.
        assertThat(hit!!.score).isWithin(1e-3).of(expected.score)
        if (file.placeId(hit.index) != expected.place.id) {
          assertThat(hit.score).isWithin(1e-3).of(expected.score)
        } else {
          assertThat(hit.distanceM).isWithin(max(0.5, expected.distanceM * 1e-9)).of(expected.distanceM)
        }
      }
    }
    println("$label: ${queries.size} ricerche, $found con un luogo entro ${capM / 1000} km")
  }

  /** Da 1.000 a 3 milioni di abitanti in scala logaritmica: i piccoli paesi sono molti, le grandi citta' (col raggio al tetto) poche. */
  private fun randomPopulation(random: Random): Long =
    Math.exp(random.nextDouble(Math.log(1_000.0), Math.log(3_000_000.0))).toLong()

  private fun randomPoint(random: Random): Pair<Double, Double> {
    // Uniforme sulla sfera (non sulla carta): i poli sono rari, come nella realta'.
    val lat = Math.toDegrees(Math.asin(random.nextDouble(-1.0, 1.0)))
    return lat to random.nextDouble(-180.0, 180.0)
  }

  private fun around(random: Random, lat: Double, lon: Double, radiusDeg: Double): Pair<Double, Double> {
    val la = (lat + random.nextDouble(-radiusDeg, radiusDeg)).coerceIn(-90.0, 90.0)
    var lo = lon + random.nextDouble(-radiusDeg, radiusDeg) / max(0.02, cos(Math.toRadians(la)))
    while (lo > 180.0) lo -= 360.0
    while (lo < -180.0) lo += 360.0
    return la to lo
  }

  @Test
  fun suTuttoIlGloboLaRicercaCoincideConLaForzaBruta() {
    val random = Random(20260101)
    val places = (1..3000).map { i ->
      val (lat, lon) = randomPoint(random)
      TestData.place(i, lat, lon, cls = 1 + random.nextInt(3), pop = randomPopulation(random))
    }
    val queries = (1..4000).map { randomPoint(random) } + listOf(90.0 to 0.0, -90.0 to 45.0, 0.0 to 180.0, 0.0 to -180.0, 89.99 to 12.0)
    checkAgainstBruteForce(places, queries, capM = 300_000.0, label = "globo, tetto 300 km")
    checkAgainstBruteForce(places, queries, capM = 2_000_000.0, label = "globo, tetto 2000 km")
    checkAgainstBruteForce(places, queries, capM = 15_000.0, label = "globo, tetto 15 km")
    checkAgainstBruteForce(places, queries, capM = Double.POSITIVE_INFINITY, label = "globo, senza tetto")
  }

  @Test
  fun inCittaAffollateELontanoDaTutto() {
    val random = Random(7)
    val centers = listOf(45.46 to 9.19, 40.85 to 14.27, 51.5 to -0.12, 35.68 to 139.69, -33.87 to 151.2, 64.13 to -21.9, 69.65 to 18.96)
    val places = ArrayList<PlaceRow>()
    var id = 1
    for ((lat, lon) in centers) repeat(600) {
      val (la, lo) = around(random, lat, lon, 0.6)
      places += TestData.place(id++, la, lo, cls = 1 + random.nextInt(3), pop = randomPopulation(random))
    }
    val queries = (1..3000).map { i ->
      val (lat, lon) = centers[i % centers.size]
      around(random, lat, lon, if (i % 5 == 0) 3.0 else 0.7)
    } + (1..500).map { randomPoint(random) }
    checkAgainstBruteForce(places, queries, capM = 300_000.0, label = "citta")
  }

  @Test
  fun allAntimeridianoLaColonnaSiRiavvolge() {
    val random = Random(11)
    // Un arcipelago a cavallo di 180 gradi (come le Figi) e i suoi dintorni.
    val places = (1..300).map { i ->
      val (lat, lon) = around(random, -17.0, 180.0, 2.5)
      TestData.place(i, lat, lon, pop = randomPopulation(random))
    }
    val queries = (1..3000).map { around(random, -17.0, 180.0, 4.0) } + listOf(-17.0 to 180.0, -17.0 to -180.0, -16.9 to 179.9999, -16.9 to -179.9999)
    checkAgainstBruteForce(places, queries, capM = 300_000.0, label = "antimeridiano")
  }

  @Test
  fun unLuogoDallAltraParteDellAntimeridianoSiTrova() {
    // Waiyevo (Taveuni, Figi) sta appena a ovest di 180 gradi; un punto appena a est e' a una manciata di km.
    val places = listOf(
      TestData.place(1, -16.82, -179.98, "Ovest"),
      TestData.place(2, -16.78, 179.34, "Lontano"),
    )
    val file = TestData.open(TestData.bytes(places))
    val hit = file.nearest(-16.85, 179.97, 300_000.0)!!
    assertThat(file.placeId(hit.index)).isEqualTo(1)
    assertThat(hit.distanceM).isWithin(1_000.0).of(TestData.haversineM(-16.85, 179.97, -16.82, -179.98))
    assertThat(hit.distanceM).isLessThan(8_000.0)
    // E dall'altra parte.
    val back = file.nearest(-16.8, -179.99, 300_000.0)!!
    assertThat(file.placeId(back.index)).isEqualTo(1)
  }

  @Test
  fun aiPoliLaDistanzaPassaDalPolo() {
    val random = Random(5)
    // Una calotta polare: molti luoghi oltre gli 85 gradi, anche su meridiani opposti.
    val north = (1..400).map { i ->
      TestData.place(i, 85.0 + random.nextDouble(0.0, 5.0), random.nextDouble(-180.0, 180.0), pop = randomPopulation(random))
    }
    val south = (401..800).map { i ->
      TestData.place(i, -85.0 - random.nextDouble(0.0, 5.0), random.nextDouble(-180.0, 180.0), pop = randomPopulation(random))
    }
    val queries = (1..2000).map {
      val sign = if (it % 2 == 0) 1 else -1
      sign * random.nextDouble(84.0, 90.0) to random.nextDouble(-180.0, 180.0)
    } + listOf(90.0 to 0.0, -90.0 to 0.0, 89.9999 to 90.0, -89.9999 to -90.0)
    checkAgainstBruteForce(north + south, queries, capM = 300_000.0, label = "poli")
    checkAgainstBruteForce(north + south, queries, capM = 1_500_000.0, label = "poli, tetto 1500 km")

    // A mano: due luoghi sullo stesso parallelo, uno di qua e uno di la' del polo.
    val two = listOf(TestData.place(1, 89.9, 0.0), TestData.place(2, 89.9, 180.0))
    val file = TestData.open(TestData.bytes(two))
    val hit = file.nearest(89.95, 170.0, 300_000.0)!!
    assertThat(file.placeId(hit.index)).isEqualTo(2)
    // Dal luogo 1, a 0 gradi, il punto (89.95, 170) e' piu' lontano passando per il polo che sul parallelo.
    assertThat(hit.distanceM).isLessThan(TestData.haversineM(89.95, 170.0, 89.9, 0.0))
  }

  @Test
  fun iLuoghiMinoriNonVinconoMai() {
    val places = listOf(
      TestData.place(1, 45.001, 9.001, "Frazione", cls = 0),
      TestData.place(2, 45.05, 9.05, "Comune", cls = 1),
    )
    val file = TestData.open(TestData.bytes(places))
    assertThat(file.placeId(file.nearest(45.0, 9.0, 300_000.0)!!.index)).isEqualTo(2)
    val onlyMinor = TestData.open(TestData.bytes(places.take(1)))
    assertThat(onlyMinor.nearest(45.0, 9.0, 300_000.0)).isNull()
  }

  @Test
  fun aPariDistanzaVinceIlIdPiuPiccolo() {
    // Due luoghi alla stessa distanza esatta (stessa latitudine, simmetrici in longitudine).
    val places = listOf(
      TestData.place(7, 0.0, 0.1),
      TestData.place(3, 0.0, -0.1),
    )
    val file = TestData.open(TestData.bytes(places))
    assertThat(file.placeId(file.nearest(0.0, 0.0, 100_000.0)!!.index)).isEqualTo(3)
  }

  @Test
  fun iTettiTagliano() {
    val file = TestData.open(TestData.bytes(listOf(TestData.place(1, 45.0, 9.0))))
    val oneDegreeNorth = 111_194.9
    assertThat(file.nearest(46.0, 9.0, oneDegreeNorth + 100.0)).isNotNull()
    assertThat(file.nearest(46.0, 9.0, oneDegreeNorth - 100.0)).isNull()
    assertThat(file.nearest(46.0, 9.0, 0.0)).isNull()
  }
}
