package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import java.io.File
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Prove sul dataset VERO (l'asset generato da `buildPlacesDataset`, docs/luoghi.md): girano solo se l'asset c'e'. Non
 * sono ritarature: sono i difetti trovati sul dataset vero, fissati come test.
 */
class RealDatasetTest {

  private val asset = File("src/main/assets/places/places.mcgp1")

  private val data by lazy { PlacesDataset.openForTest(asset.readBytes()) }

  private fun nameAt(lat: Double, lon: Double): String? = data.nearest(GeoPoint(lat, lon))?.place?.name

  private fun requireAsset() = assumeTrue("Nessun dataset reale in assets/places.", asset.isFile)

  @Test
  fun ilCentroDelleGrandiCittaEIlNomeDellaCitta() {
    requireAsset()
    // Il nome principale di un punto centrale e' la citta', non un quartiere, un borough o un distretto (T095: Manhattan).
    val central = mapOf(
      "Roma" to listOf(41.8902 to 12.4922, 41.8992 to 12.4731, 41.8986 to 12.4769, 41.9009 to 12.4833, 41.9009 to 12.5016),
      "Milano" to listOf(45.4641 to 9.1919, 45.4705 to 9.1795, 45.4863 to 9.2051, 45.4500 to 9.1700),
      "Londra" to listOf(51.5007 to -0.1246, 51.5100 to -0.1344, 51.5055 to -0.0754, 51.5390 to -0.1426),
      "Parigi" to listOf(48.8584 to 2.2945, 48.8606 to 2.3376, 48.8530 to 2.3499, 48.8867 to 2.3431),
      "New York" to listOf(40.7580 to -73.9855, 40.7829 to -73.9654, 40.7060 to -74.0088, 40.6501 to -73.9496),
      "Tokyo" to listOf(35.6896 to 139.7006, 35.6595 to 139.7005),
    )
    val wrong = central.flatMap { (city, points) ->
      points.mapNotNull { (lat, lon) ->
        val got = nameAt(lat, lon)
        if (got != null && PlacesAccuracy.normalize(got) == PlacesAccuracy.normalize(city)) null else "$city: ${PlacesAccuracy.ascii(got.toString())}"
      }
    }
    assertThat(wrong).isEmpty()
  }

  @Test
  fun dentroIMicroStatiRispondeLUnitaAmministrativa() {
    requireAsset()
    // T078: dentro San Marino vinceva Valdragone (un villaggio di 7.000 abitanti, piu' del capoluogo).
    val sanMarino = data.nearest(GeoPoint(43.9367, 12.4464))!!.place
    assertThat(sanMarino.name).isEqualTo("San Marino")
    assertThat(sanMarino.countryCode).isEqualTo("SM")
    assertThat(PlacesAccuracy.normalize(nameAt(43.9424, 12.4578)!!)).isNotEqualTo("valdragone")
    assertThat(nameAt(41.9022, 12.4568)).isEqualTo("Città del Vaticano")
    assertThat(nameAt(41.8992, 12.4731)).isEqualTo("Roma")
    assertThat(nameAt(43.7399, 7.4276)).isEqualTo("Monaco")
    assertThat(nameAt(47.1410, 9.5209)).isEqualTo("Vaduz")
  }

  @Test
  fun leLocalitaMoltoNoteSiTrovano() {
    requireAsset()
    // T069: Madonna di Campiglio e' una localita' da 656 abitanti con 12 nomi alternativi.
    assertThat(nameAt(46.2300, 10.8260)).isEqualTo("Madonna di Campiglio")
  }

  /** Accenti e apostrofi tipografici sono memorizzati in UTF-8 corretto (non si confrontano con il testo del rapporto, che dipende dalla console). */
  @Test
  fun inomiConApostrofiEDiacriticiSonoInUtf8Corretto() {
    requireAsset()
    assertThat(nameAt(40.7313, 13.9654)).isEqualTo("Porto d’Ischia") // apostrofo tipografico U+2019
    val chukotka = data.nearest(GeoPoint(64.5, 179.9))!!.place.name
    assertThat(chukotka).endsWith("Čukotka") // C con caron
    assertThat(nameAt(41.9022, 12.4568)).isEqualTo("Città del Vaticano")
    assertThat(nameAt(21.2793, -157.8292)).isEqualTo("Waikīkī") // i con macron, due volte
    // Il confronto dei test e' insensibile ad accenti, maiuscole e apostrofi.
    assertThat(PlacesAccuracy.normalize("Porto d’Ischia")).isEqualTo(PlacesAccuracy.normalize("Porto d'Ischia"))
    assertThat(PlacesAccuracy.normalize("Čukotka")).isEqualTo("cukotka")
    assertThat(PlacesAccuracy.normalize("Gressoney-La-Trinité")).isEqualTo(PlacesAccuracy.normalize("GRESSONEY-LA-TRINITE"))
  }

  /** "100 mila ricerche in meno di 2 s" (docs/fase3-progetto.md §4.3), sul dataset vero e su punti sparsi in Europa e dintorni. */
  @Test
  fun centomilaRicercheSulDatasetVeroInMenoDiDueSecondi() {
    requireAsset()
    val random = kotlin.random.Random(12)
    val queries = (0 until 100_000).map { GeoPoint(random.nextDouble(30.0, 62.0), random.nextDouble(-10.0, 40.0)) }
    data.nearest(queries[0]) // il primo accesso costruisce solo le cache dei nomi
    val started = System.nanoTime()
    var found = 0
    for (q in queries) if (data.nearest(q) != null) found++
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    println("dataset vero: 100000 ricerche in $elapsedMs ms ($found con un luogo)")
    assertThat(elapsedMs).isLessThan(2_000)
  }

  @Test
  fun lAssetEQuelloDelPin() {
    requireAsset()
    val sha = MessageDigest.getInstance("SHA-256").digest(asset.readBytes()).joinToString("") { "%02x".format(it) }
    val pin = File("src/main/kotlin/dev/pampa/mycloudgallery/core/places/dataset/PlacesDatasetPin.kt").readText()
    assertThat(pin).contains("const val SHA256: String = \"$sha\"")
    assertThat(data.placeCount).isGreaterThan(150_000)
    assertThat(asset.length()).isLessThan(5_500_000)
  }
}
