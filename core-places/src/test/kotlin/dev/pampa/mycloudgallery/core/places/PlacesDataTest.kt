package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.contract.PlacePrecision
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import dev.pampa.mycloudgallery.core.places.dataset.PlacesLimits
import dev.pampa.mycloudgallery.core.places.dataset.RegionRow
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

/** Il dataset aperto come geocoder, cercatore di fusi e nomi per la UI. */
class PlacesDataTest {

  private val regions = listOf(
    RegionRow("IT.16", 3_165_361, "IT", "Toscana"),
    RegionRow("GB.ENG", 6_269_131, "GB", "Inghilterra"),
  )

  private val places = listOf(
    TestData.place(100, 43.7696, 11.2558, "Firenze", "IT", "IT.16", "Europe/Rome", cls = 2, pop = 380_000),
    TestData.place(101, 43.8056, 11.2931, "Fiesole", "IT", "IT.16", "Europe/Rome", cls = 2, pop = 14_000),
    TestData.place(102, 43.4677, 11.0430, "San Gimignano", "IT", "IT.16", "Europe/Rome", cls = 2, pop = 7_000),
    TestData.place(200, 51.5085, -0.1257, "Londra", "GB", "GB.ENG", "Europe/London", cls = 3, pop = 8_900_000),
    TestData.place(300, 78.2232, 15.6267, "Longyearbyen", "SJ", null, "Arctic/Longyearbyen", cls = 2, pop = 2_100),
    TestData.place(400, 10.0, 10.0, "Senza fuso", "NG", null, null),
    TestData.place(500, -10.0, 10.0, "Fuso sconosciuto", "AO", null, "Marte/Olympus_Mons"),
  )

  private val data = PlacesDataset.openForTest(TestData.bytes(places, regions, version = 20260314))

  /** Un punto a [meters] metri a nord di ([lat], [lon]): lungo un meridiano la distanza di haversine e' esatta. */
  private fun north(lat: Double, lon: Double, meters: Double) = GeoPoint(lat + meters / 111_194.9266, lon)

  @Test
  fun laVersioneEIlConteggio() {
    assertThat(data.dataset).isEqualTo(20260314)
    assertThat(data.placeCount).isEqualTo(places.size)
  }

  @Test
  fun inCentroCittaLaPrecisioneECity() {
    val match = data.nearest(GeoPoint(43.7696, 11.2558))!!
    assertThat(match.precision).isEqualTo(PlacePrecision.CITY)
    assertThat(match.place.name).isEqualTo("Firenze")
    assertThat(match.place.id).isEqualTo(PlaceId(100))
    assertThat(match.place.admin1).isEqualTo("Toscana")
    assertThat(match.place.countryCode).isEqualTo("IT")
    assertThat(match.place.countryName).isEqualTo("Italia")
    assertThat(match.place.zone).isEqualTo("Europe/Rome")
    assertThat(match.distanceM).isAtMost(1)
  }

  @Test
  fun leSogliePassanoDaCityANearARegionEACountry() {
    // Un luogo solo (Londra, con regione) e un punto a distanze note da lui.
    val origin = GeoPoint(51.5085, -0.1257)
    fun at(meters: Double) = data.nearest(north(origin.latitude, origin.longitude, meters))

    assertThat(at(14_800.0)!!.precision).isEqualTo(PlacePrecision.CITY)
    assertThat(at(15_200.0)!!.precision).isEqualTo(PlacePrecision.NEAR)
    assertThat(at(49_800.0)!!.precision).isEqualTo(PlacePrecision.NEAR)

    val region = at(50_300.0)!!
    assertThat(region.precision).isEqualTo(PlacePrecision.REGION_ONLY)
    // Una ricerca che conosce solo la regione non mente: il "luogo" e' la regione.
    assertThat(region.place.name).isEqualTo("Inghilterra")
    assertThat(region.place.admin1).isEqualTo("Inghilterra")
    assertThat(region.place.countryName).isEqualTo("Regno Unito")
    assertThat(region.place.id).isEqualTo(PlaceId(6_269_131))
    assertThat(at(149_500.0)!!.precision).isEqualTo(PlacePrecision.REGION_ONLY)

    val country = at(150_500.0)!!
    assertThat(country.precision).isEqualTo(PlacePrecision.COUNTRY_ONLY)
    assertThat(country.place.name).isEqualTo("Regno Unito")
    assertThat(country.place.admin1).isNull()
    assertThat(at(299_000.0)!!.precision).isEqualTo(PlacePrecision.COUNTRY_ONLY)

    // Oltre il tetto (oceano): nessun risultato, mai un nome inventato.
    assertThat(at(301_000.0)).isNull()
    assertThat(data.nearest(GeoPoint(30.0, -40.0))).isNull()
  }

  @Test
  fun senzaRegioneLaRicercaChiudeSulPaese() {
    // Longyearbyen non ha regione: oltre i 50 km si sa solo il paese.
    val match = data.nearest(north(78.2232, 15.6267, 80_000.0))!!
    assertThat(match.precision).isEqualTo(PlacePrecision.COUNTRY_ONLY)
    assertThat(match.place.countryCode).isEqualTo("SJ")
    assertThat(match.place.zone).isEqualTo("Arctic/Longyearbyen")
  }

  @Test
  fun iNomiDiRegioniEPaesiHannoUnIdRisolvibile() {
    assertThat(data.place(PlaceId(3_165_361))!!.name).isEqualTo("Toscana")
    assertThat(data.place(PlaceId(3_165_361))!!.countryName).isEqualTo("Italia")
    val gb = data.nearest(north(51.5085, -0.1257, 200_000.0))!!.place
    assertThat(data.place(gb.id)!!.name).isEqualTo("Regno Unito")
    assertThat(data.labelOfId(gb.id.value)).isEqualTo("Regno Unito")
    assertThat(data.place(PlaceId(123_456))).isNull()
    assertThat(data.labelOfId(0)).isNull()
    assertThat(data.labelOfId(-5)).isNull()
  }

  @Test
  fun iNomiPerLaUi() {
    assertThat(data.label(PlaceRef(100, 20260314))).isEqualTo("Firenze")
    assertThat(data.labelOfId(101)).isEqualTo("Fiesole")
    assertThat(data.label(PlaceRef(999_999, 20260314))).isNull()
    assertThat(data.summary(intArrayOf(100), 1)).isEqualTo("Firenze")
    assertThat(data.summary(intArrayOf(100, 101), 2)).isEqualTo("Firenze e Fiesole")
    assertThat(data.summary(intArrayOf(100, 101, 102), 3)).isEqualTo("Firenze e altri 2 luoghi")
    assertThat(data.summary(intArrayOf(100, 101, 102), 7)).isEqualTo("Firenze e altri 6 luoghi")
    // Due luoghi ma uno solo noto: singolare, non "altri 1 luoghi".
    assertThat(data.summary(intArrayOf(100, 999_999), 2)).isEqualTo("Firenze e un altro luogo")
    assertThat(data.summary(intArrayOf(999_999, 101), 2)).isEqualTo("Fiesole e un altro luogo")
    assertThat(data.summary(intArrayOf(), 0)).isEmpty()
    assertThat(data.summary(intArrayOf(999_999), 1)).isEmpty()
    assertThat(data.summary(intArrayOf(100), 0)).isEmpty()
  }

  @Test
  fun iFusiOrari() {
    assertThat(data.zoneAt(GeoPoint(43.77, 11.25))).isEqualTo(ZoneId.of("Europe/Rome"))
    assertThat(data.zoneAt(GeoPoint(51.5, -0.12))).isEqualTo(ZoneId.of("Europe/London"))
    assertThat(data.zoneAt(GeoPoint(78.2, 15.6))).isEqualTo(ZoneId.of("Arctic/Longyearbyen"))
    // Oltre i 150 km dal luogo piu' vicino il fuso non si indovina: il chiamante ripiega.
    assertThat(data.zoneAt(north(51.5085, -0.1257, PlacesLimits.ZONE_M - 1_000.0))).isEqualTo(ZoneId.of("Europe/London"))
    assertThat(data.zoneAt(north(51.5085, -0.1257, PlacesLimits.ZONE_M + 1_000.0))).isNull()
    // Un luogo senza fuso, o con un fuso che questo sistema non conosce: nessun fuso, nessuna eccezione.
    assertThat(data.zoneAt(GeoPoint(10.0, 10.0))).isNull()
    assertThat(data.zoneAt(GeoPoint(-10.0, 10.0))).isNull()
    assertThat(data.nearest(GeoPoint(-10.0, 10.0))!!.place.zone).isEqualTo("Marte/Olympus_Mons")
  }

  @Test
  fun iNomiDeiPaesiSonoInItaliano() {
    val extra = listOf(
      TestData.place(1, 48.85, 2.35, "Parigi", "FR"),
      TestData.place(2, 52.52, 13.40, "Berlino", "DE"),
      TestData.place(3, 40.71, -74.0, "New York", "US"),
      TestData.place(4, 59.91, 10.75, "Oslo", "NO"),
      TestData.place(5, 38.72, -9.14, "Lisbona", "PT"),
      TestData.place(6, 46.2, 6.14, "Ginevra", "CH"),
    )
    val countries = PlacesDataset.openForTest(TestData.bytes(extra))
    val byCode = extra.associate { it.countryCode to countries.nearest(GeoPoint(it.latitude, it.longitude))!!.place.countryName }
    assertThat(byCode).containsEntry("FR", "Francia")
    assertThat(byCode).containsEntry("DE", "Germania")
    assertThat(byCode).containsEntry("US", "Stati Uniti")
    assertThat(byCode).containsEntry("NO", "Norvegia")
    assertThat(byCode).containsEntry("PT", "Portogallo")
    assertThat(byCode).containsEntry("CH", "Svizzera")
  }

  @Test
  fun piuThreadPossonoCercareInsieme() {
    val executor = Executors.newFixedThreadPool(8)
    try {
      val expected = (0 until 200).map { i -> data.nearest(GeoPoint(43.0 + i * 0.01, 11.0 + i * 0.005))?.place?.id?.value }
      val futures = (0 until 8).map {
        executor.submit<List<Int?>> {
          (0 until 200).map { i ->
            // Anche place() e labelOfId() costruiscono l'indice per id alla prima chiamata: qui da piu' thread insieme.
            data.labelOfId(100 + i % 3)
            data.nearest(GeoPoint(43.0 + i * 0.01, 11.0 + i * 0.005))?.place?.id?.value
          }
        }
      }
      futures.forEach { assertThat(it.get(30, TimeUnit.SECONDS)).isEqualTo(expected) }
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun iToStringNonMostranoNeCoordinateNeNomi() {
    // Il dataset e la sua cache non hanno un toString che stampi dati: e' quello di Any.
    assertThat(data.toString()).startsWith("dev.pampa.mycloudgallery.core.places.dataset.PlacesData@")
    assertThat(GeoPoint(43.77, 11.25).toString()).doesNotContain("43")
  }
}
