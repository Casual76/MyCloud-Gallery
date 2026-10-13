package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.dataset.MicroStates
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import org.junit.Test

/** I poligoni dei micro-stati e la regola delle enclave, su dati sintetici (le prove su dati reali sono in WeightedRankingTest). */
class EnclaveTest {

  @Test
  fun iPoligoniContengonoIPostiGiusti() {
    // Dentro.
    assertThat(MicroStates.VATICAN.contains(41.9022, 12.4539)).isTrue() // Basilica
    assertThat(MicroStates.VATICAN.contains(41.9022, 12.4568)).isTrue() // Piazza San Pietro
    assertThat(MicroStates.VATICAN.contains(41.9065, 12.4536)).isTrue() // Musei
    assertThat(MicroStates.SAN_MARINO.contains(43.9424, 12.4578)).isTrue()
    assertThat(MicroStates.SAN_MARINO.contains(43.9680, 12.4800)).isTrue() // Serravalle
    assertThat(MicroStates.SAN_MARINO.contains(43.9060, 12.4800)).isTrue() // Montegiardino
    assertThat(MicroStates.MONACO.contains(43.7399, 7.4276)).isTrue() // Casino
    assertThat(MicroStates.MONACO.contains(43.7335, 7.4200)).isTrue()
    assertThat(MicroStates.LIECHTENSTEIN.contains(47.1410, 9.5209)).isTrue() // Vaduz
    assertThat(MicroStates.LIECHTENSTEIN.contains(47.1650, 9.5090)).isTrue() // Schaan
    assertThat(MicroStates.LIECHTENSTEIN.contains(47.1030, 9.6120)).isTrue() // Malbun
    // Fuori, anche se vicino.
    assertThat(MicroStates.VATICAN.contains(41.8992, 12.4731)).isFalse() // Navona
    assertThat(MicroStates.VATICAN.contains(41.9031, 12.4663)).isFalse() // Castel Sant'Angelo
    assertThat(MicroStates.SAN_MARINO.contains(44.0600, 12.5680)).isFalse() // Rimini
    assertThat(MicroStates.SAN_MARINO.contains(44.0000, 12.4900)).isFalse()
    assertThat(MicroStates.MONACO.contains(43.7745, 7.4975)).isFalse() // Mentone
    assertThat(MicroStates.MONACO.contains(43.7470, 7.4600)).isFalse()
    assertThat(MicroStates.LIECHTENSTEIN.contains(47.1655, 9.4789)).isFalse() // Buchs
    assertThat(MicroStates.LIECHTENSTEIN.contains(47.2386, 9.5983)).isFalse() // Feldkirch
  }

  @Test
  fun ilMargineEDi300Metri() {
    val boundary = MicroStates.VATICAN.boundaryDistanceM(41.9028, 12.4614)
    assertThat(boundary).isWithin(30.0).of(180.0) // 0,0022 gradi di longitudine a 41,9 N
    assertThat(MicroStates.VATICAN.covers(41.9028, 12.4614, MicroStates.MARGIN_M)).isTrue()
    assertThat(MicroStates.VATICAN.covers(41.9031, 12.4663, MicroStates.MARGIN_M)).isFalse()
    // Il Vaticano e' piccolo: da qualunque punto dentro il confine e' a meno di 400 m. Da Roma, a chilometri.
    assertThat(MicroStates.VATICAN.boundaryDistanceM(41.9022, 12.4539)).isLessThan(400.0)
    assertThat(MicroStates.VATICAN.boundaryDistanceM(41.8919, 12.5113)).isGreaterThan(4_000.0)
    assertThat(MicroStates.enclaveAt(41.9028, 12.4614)?.countryCode).isEqualTo("VA")
    assertThat(MicroStates.enclaveAt(45.4641, 9.1919)).isNull()
    assertThat(MicroStates.enclaveAt(0.0, 0.0)).isNull()
    assertThat(MicroStates.CODES).containsExactly("VA", "SM", "MC", "LI")
  }

  /** Un poligono semplice non ha lati che si incrociano: i vertici scritti a mano sono nell'ordine giusto. */
  @Test
  fun iPoligoniSonoSemplici() {
    for (enclave in MicroStates.ALL) {
      val vertices = verticesOf(enclave)
      val n = vertices.size
      assertThat(n).isAtLeast(8)
      for (i in 0 until n) {
        for (j in i + 1 until n) {
          // Lati adiacenti si toccano nel vertice comune: non contano.
          if (j == i + 1 || (i == 0 && j == n - 1)) continue
          val crossing = segmentsCross(vertices[i], vertices[(i + 1) % n], vertices[j], vertices[(j + 1) % n])
          assertThat(crossing).isFalse()
        }
      }
    }
  }

  private fun verticesOf(enclave: MicroStates.Enclave): List<Pair<Double, Double>> {
    // I vertici sono privati: si leggono con la riflessione, e' un test.
    val latField = enclave.javaClass.getDeclaredField("lat").also { it.isAccessible = true }
    val lonField = enclave.javaClass.getDeclaredField("lon").also { it.isAccessible = true }
    val lat = latField.get(enclave) as DoubleArray
    val lon = lonField.get(enclave) as DoubleArray
    return lat.indices.map { lat[it] to lon[it] }
  }

  private fun orientation(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Int {
    val v = (b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)
    return if (v > 0) 1 else if (v < 0) -1 else 0
  }

  private fun segmentsCross(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>, d: Pair<Double, Double>): Boolean =
    orientation(a, b, c) != orientation(a, b, d) && orientation(c, d, a) != orientation(c, d, b)

  // ---- La regola, su dati sintetici ------------------------------------------------------------------------------------

  private val rome = TestData.place(1, 41.8919, 12.5113, "Roma", "IT", pop = 2_872_800, cls = 3)
  private val vatican = TestData.place(2, 41.9022, 12.4531, "Vaticano", "VA", zone = "Europe/Vatican", pop = 826, cls = 3)

  @Test
  fun fuoriDalPoligonoIlMicroStatoNonCompeteNemmenoSeERimastoDaSolo() {
    val onlyVatican = PlacesDataset.openForTest(TestData.bytes(listOf(vatican)))
    assertThat(onlyVatican.nearest(GeoPoint(41.8992, 12.4731))).isNull() // Navona: non e' Vaticano
    assertThat(onlyVatican.nearest(GeoPoint(41.8919, 12.5113))).isNull()
    assertThat(onlyVatican.nearest(GeoPoint(41.9022, 12.4568))!!.place.name).isEqualTo("Vaticano")
    assertThat(onlyVatican.zoneAt(GeoPoint(41.8992, 12.4731))).isNull()
  }

  @Test
  fun dentroIlPoligonoVinceIlPaeseAnchePiccolo() {
    val data = PlacesDataset.openForTest(TestData.bytes(listOf(rome, vatican)))
    assertThat(data.nearest(GeoPoint(41.9022, 12.4568))!!.place.name).isEqualTo("Vaticano")
    assertThat(data.nearest(GeoPoint(41.9065, 12.4536))!!.place.name).isEqualTo("Vaticano")
    assertThat(data.nearest(GeoPoint(41.8992, 12.4731))!!.place.name).isEqualTo("Roma")
  }

  @Test
  fun senzaLuoghiDelMicroStatoNelFileIlPoligonoNonHaEffetto() {
    // Il dataset non ha luoghi del Vaticano: in Piazza San Pietro si risponde con quello che c'e'.
    val data = PlacesDataset.openForTest(TestData.bytes(listOf(rome)))
    assertThat(data.nearest(GeoPoint(41.9022, 12.4568))!!.place.name).isEqualTo("Roma")
  }

  @Test
  fun unPaeseConCodiceDiMicroStatoMaFuoriDalPoligonoNonCompete() {
    // Un luogo "SM" lontano da San Marino (dati sbagliati o di prova) non vince mai: i micro-stati vincono solo dentro il loro poligono.
    val strayRoman = TestData.place(7, 45.0, 9.0, "Sbagliato", "SM", pop = 1_000_000, cls = 3)
    val city = TestData.place(8, 45.2, 9.0, "Citta", "IT", pop = 20_000, cls = 2)
    val data = PlacesDataset.openForTest(TestData.bytes(listOf(strayRoman, city)))
    assertThat(data.nearest(GeoPoint(45.0, 9.0))!!.place.name).isEqualTo("Citta")
  }
}
