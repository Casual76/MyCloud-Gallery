package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import org.junit.Test

/**
 * La ricerca di un luogo per nome (Fase 9, P5: "la zona attorno a un luogo"): senza maiuscole ne' accenti, i nomi che cominciano per la ricerca prima di quelli con una parola che comincia cosi', i piu' grandi
 * prima, solo luoghi qualificati, e il centro del luogo con le sue coordinate. Su un dataset **di prova** (luoghi inventati con coordinate qualunque).
 */
class PlaceSearchByNameTest {

  private val data = PlacesDataset.openForTest(
    TestData.bytes(
      listOf(
        TestData.place(1, 43.318, 11.331, "Siena", pop = 54_000, cls = 2),
        TestData.place(2, 43.771, 11.254, "Firenze", pop = 380_000, cls = 3),
        TestData.place(3, 44.494, 11.343, "Bologna", pop = 390_000, cls = 3),
        TestData.place(4, 44.646, 10.926, "Modena", pop = 185_000, cls = 2),
        TestData.place(5, 44.699, 10.629, "Reggio nell'Emilia", pop = 170_000, cls = 2),
        TestData.place(6, 38.115, 13.361, "Palermo", pop = 630_000, cls = 3),
        TestData.place(7, 42.047, 13.927, "Sulmona", pop = 24_000, cls = 1),
        TestData.place(8, 43.0, 11.0, "Sienarossa", pop = 2_000, cls = 1),
        TestData.place(9, 43.1, 11.1, "Borgo Siena Nuova", pop = 9_000, cls = 1),
        TestData.place(10, 43.2, 11.2, "Cittadella", pop = 12_000, cls = 1),
        TestData.place(11, 43.25, 11.25, "Città Vecchia", pop = 5_000, cls = 1),
        TestData.place(12, 43.4, 11.4, "Cascina", pop = 500, cls = 0),
      ),
    ),
  )

  @Test
  fun siTrovaPerNomeSenzaMaiuscoleNeAccenti() {
    assertThat(data.search("siena").first().name).isEqualTo("Siena")
    assertThat(data.search("SIENA").first().name).isEqualTo("Siena")
    assertThat(data.search("  fir ").map { it.name }).containsExactly("Firenze")
    assertThat(data.search("città").map { it.name }).containsExactly("Cittadella", "Città Vecchia").inOrder()
    assertThat(data.search("citta").map { it.name }).containsExactly("Cittadella", "Città Vecchia").inOrder()
  }

  @Test
  fun iNomiCheCominciano_PrimaPoiUnaParola_IPiuGrandiPrima() {
    // "siena": Siena (popolazione piu' grande) prima di Sienarossa, poi "Borgo Siena Nuova" (una parola comincia cosi').
    assertThat(data.search("siena").map { it.name }).containsExactly("Siena", "Sienarossa", "Borgo Siena Nuova").inOrder()
    // Una parola dopo l'apostrofo.
    assertThat(data.search("emilia").map { it.name }).containsExactly("Reggio nell'Emilia")
    // I piu' grandi prima, al piu' [limit].
    assertThat(data.search("mo", limit = 5).map { it.name }).containsExactly("Modena")
    assertThat(data.search("bo", limit = 1).map { it.name }).containsExactly("Bologna")
  }

  @Test
  fun soloLuoghiQualificatiEDueLetterePerCercare() {
    assertThat(data.search("cas")).isEmpty() // Cascina ha 500 abitanti: non e' un luogo qualificato
    assertThat(data.search("s")).isEmpty()
    assertThat(data.search("")).isEmpty()
    assertThat(data.search("zzzz")).isEmpty()
  }

  @Test
  fun ilCentroELePopolazioniSonoQuelliDelLuogo() {
    val siena = data.search("siena").first()
    assertThat(siena.latitude).isWithin(0.0001).of(43.318)
    assertThat(siena.longitude).isWithin(0.0001).of(11.331)
    assertThat(siena.population).isGreaterThan(40_000.0)
    assertThat(siena.countryName).isNotEmpty()
    val located = data.locate(PlaceId(7))!!
    assertThat(located.name).isEqualTo("Sulmona")
    assertThat(located.latitude).isWithin(0.0001).of(42.047)
    assertThat(located.longitude).isWithin(0.0001).of(13.927)
    assertThat(data.locate(PlaceId(999_999))).isNull()
  }

  @Test
  fun nelToStringNienteLuoghi() {
    assertThat(data.search("siena").first().toString()).isEqualTo("PlaceCandidate")
  }
}
