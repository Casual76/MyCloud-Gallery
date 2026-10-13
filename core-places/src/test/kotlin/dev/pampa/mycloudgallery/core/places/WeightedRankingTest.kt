package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.contract.PlacePrecision
import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1Format
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import org.junit.Test

/**
 * La classifica pesata con la popolazione e le enclave dei micro-stati (docs/luoghi.md §4): al posto del centroide piu'
 * vicino vince il luogo col punteggio piu' basso, distanza meno raggio d'influenza (radice della popolazione, tetto 8 km),
 * e il Vaticano, San Marino, Monaco e il Liechtenstein sono poligoni che vincono dentro e non competono fuori.
 */
class WeightedRankingTest {

  private val mini = PlacesDataset.openForTest(TestData.miniBytes)

  private fun nameAt(lat: Double, lon: Double): String? = mini.nearest(GeoPoint(lat, lon))?.place?.name

  // ---- Il raggio d'influenza -----------------------------------------------------------------------------------------

  private fun radiusOf(population: Long): Double = Mcgp1Format.INFLUENCE_RADIUS_M[Mcgp1Format.populationBucket(population)]

  @Test
  fun ilRaggioCresceComeLaRadiceEHaUnTetto() {
    // Roma (2,9 milioni): il tetto di 8 km. Milano: 5,8 km. Una citta' di 80.000 abitanti: 1,4 km. Un paese di 500: 0,1 km.
    assertThat(radiusOf(2_872_800)).isEqualTo(8_000.0)
    assertThat(radiusOf(8_336_000)).isEqualTo(8_000.0)
    assertThat(radiusOf(1_371_498)).isWithin(400.0).of(5_850.0)
    assertThat(radiusOf(80_000)).isWithin(150.0).of(1_414.0)
    assertThat(radiusOf(14_000)).isWithin(80.0).of(590.0)
    assertThat(radiusOf(4_000)).isWithin(50.0).of(316.0)
    assertThat(radiusOf(826)).isWithin(30.0).of(144.0)
    assertThat(radiusOf(500)).isWithin(15.0).of(112.0)
    assertThat(radiusOf(0)).isEqualTo(0.0)
    // Sempre crescente con il byte, mai oltre il tetto, uguale su ogni JVM (solo operazioni esatte).
    val table = Mcgp1Format.INFLUENCE_RADIUS_M
    assertThat(table).hasLength(256)
    for (i in 1 until 256) assertThat(table[i]).isAtLeast(table[i - 1])
    assertThat(table.max()).isEqualTo(Mcgp1Format.INFLUENCE_MAX_M)
  }

  // ---- Roma e il Vaticano ---------------------------------------------------------------------------------------------

  @Test
  fun ilCentroStoricoDiRomaEsulleDueSponde() {
    // Prima di questa regola il centroide piu' vicino era la Citta' del Vaticano.
    assertThat(nameAt(41.8992, 12.4731)).isEqualTo("Roma") // Piazza Navona
    assertThat(nameAt(41.8986, 12.4769)).isEqualTo("Roma") // Pantheon
    assertThat(nameAt(41.9060, 12.4823)).isEqualTo("Roma") // Piazza di Spagna
    assertThat(nameAt(41.9009, 12.4833)).isEqualTo("Roma") // Fontana di Trevi
    assertThat(nameAt(41.8946, 12.4831)).isEqualTo("Roma") // Vittoriano
    assertThat(nameAt(41.8890, 12.4690)).isEqualTo("Roma") // Trastevere
    assertThat(nameAt(41.9031, 12.4663)).isEqualTo("Roma") // Castel Sant'Angelo, a 600 m dal confine
  }

  @Test
  fun dentroIlVaticanoEIlVaticano() {
    assertThat(nameAt(41.9022, 12.4568)).isEqualTo("Città del Vaticano") // Piazza San Pietro
    assertThat(nameAt(41.9022, 12.4539)).isEqualTo("Città del Vaticano") // Basilica
    assertThat(nameAt(41.9065, 12.4536)).isEqualTo("Città del Vaticano") // Musei Vaticani
    assertThat(nameAt(41.9035, 12.4490)).isEqualTo("Città del Vaticano") // Giardini
    val match = mini.nearest(GeoPoint(41.9022, 12.4568))!!
    assertThat(match.precision).isEqualTo(PlacePrecision.CITY)
    assertThat(match.place.countryCode).isEqualTo("VA")
    assertThat(match.place.zone).isEqualTo("Europe/Vatican")
    assertThat(mini.zoneAt(GeoPoint(41.9022, 12.4568))!!.id).isEqualTo("Europe/Vatican")
    assertThat(mini.zoneAt(GeoPoint(41.8992, 12.4731))!!.id).isEqualTo("Europe/Rome")
  }

  @Test
  fun alConfineVinceChiEDentroEntroIlMargine() {
    // Via della Conciliazione, 180 m fuori dal poligono: entro il margine di 300 m, e' ancora Vaticano.
    assertThat(nameAt(41.9028, 12.4614)).isEqualTo("Città del Vaticano")
    // 590 m fuori: Roma.
    assertThat(nameAt(41.9031, 12.4663)).isEqualTo("Roma")
  }

  // ---- Milano e Sesto San Giovanni -----------------------------------------------------------------------------------

  @Test
  fun unaCittaMediaTieneIlSuoCentroEUnaGrandeCittaSiPrendeIConfini() {
    assertThat(nameAt(45.5342, 9.2339)).isEqualTo("Sesto San Giovanni") // il centro di Sesto, non Milano
    assertThat(nameAt(45.5345, 9.2330)).isEqualTo("Sesto San Giovanni")
    assertThat(nameAt(45.5551, 9.2146)).isEqualTo("Cinisello Balsamo")
    // Il confine fra le due e' dove il peso di Milano (5,8 km) compensa la distanza: Bicocca e' Milano.
    assertThat(nameAt(45.5190, 9.2130)).isEqualTo("Milano")
    assertThat(nameAt(45.4863, 9.2051)).isEqualTo("Milano") // Stazione Centrale
    assertThat(nameAt(45.4455, 9.2767)).isEqualTo("Milano") // Linate
  }

  @Test
  fun lePiccoleDentroLaGrandeCittaNonLaPrendonoMaIlCentroSi() {
    // Fiesole (14.000 abitanti) a 5 km da Firenze (380.000): nel suo centro e' Fiesole, a 2 km dal suo centro verso Firenze no.
    assertThat(nameAt(43.8076, 11.2929)).isEqualTo("Fiesole")
    assertThat(nameAt(43.7930, 11.2820)).isEqualTo("Firenze")
    assertThat(nameAt(43.7731, 11.2560)).isEqualTo("Firenze")
  }

  // ---- Una frazione vicino a una citta' media ---------------------------------------------------------------------

  @Test
  fun unaFrazioneAUnoDueKmDaUnaCittaMediaEDellaCitta() {
    // Una citta' di 50.000 abitanti (raggio 1,1 km) e un paese di 1.500 qualificato (raggio 0,2 km) a 1,7 km. Il paese tiene
    // solo i suoi primi 400 m: oltre, e' un quartiere della citta'. E una frazione (meno di 1.000 abitanti, non sede
    // amministrativa: non e' nel dataset, classe 0) a 1,5 km dalla citta' non esiste mai come risposta: si risponde la citta'.
    val city = TestData.place(1, 45.0, 9.0, "Citta", pop = 50_000, cls = 2)
    val village = TestData.place(2, 45.0, 9.0 + 1.7 / (111.195 * Math.cos(Math.toRadians(45.0))), "Paese", pop = 1_500, cls = 1)
    val hamlet = TestData.place(3, 45.0 + 1.5 / 111.195, 9.0, "Frazione", pop = 400, cls = 0)
    val data = PlacesDataset.openForTest(TestData.bytes(listOf(city, village, hamlet)))
    fun kmEast(km: Double) = 9.0 + km / (111.195 * Math.cos(Math.toRadians(45.0)))

    assertThat(data.nearest(GeoPoint(45.0, kmEast(1.7)))!!.place.name).isEqualTo("Paese") // nel centro del paese
    assertThat(data.nearest(GeoPoint(45.0, kmEast(1.5)))!!.place.name).isEqualTo("Paese") // a 200 m dal paese
    assertThat(data.nearest(GeoPoint(45.0, kmEast(1.1)))!!.place.name).isEqualTo("Citta") // a 600 m dal paese
    assertThat(data.nearest(GeoPoint(45.0, 9.0))!!.place.name).isEqualTo("Citta")
    // La frazione non qualificata: a casa sua, 1,5 km a nord della citta', la risposta e' la citta'.
    val atHamlet = data.nearest(GeoPoint(45.0 + 1.5 / 111.195, 9.0))!!
    assertThat(atHamlet.place.name).isEqualTo("Citta")
    assertThat(atHamlet.precision).isEqualTo(PlacePrecision.CITY)
  }

  // ---- San Marino e Rimini ---------------------------------------------------------------------------------------------

  @Test
  fun sanMarinoEdentroIlPoligono() {
    assertThat(nameAt(43.9367, 12.4464)).isEqualTo("San Marino") // Piazza della Liberta', il centro della citta'
    assertThat(nameAt(43.9680, 12.4800)).isEqualTo("Serravalle") // dentro, vince il suo luogo piu' adatto
    assertThat(nameAt(43.9419, 12.4474)).isEqualTo("Borgo Maggiore")
    assertThat(mini.zoneAt(GeoPoint(43.9367, 12.4464))!!.id).isEqualTo("Europe/San_Marino")
    assertThat(mini.nearest(GeoPoint(43.9367, 12.4464))!!.place.countryName).isEqualTo("San Marino")
  }

  @Test
  fun dentroUnMicroStatoRispondeLUnitaAmministrativaNonUnVillaggio() {
    // Valdragone e' un villaggio di 7.000 abitanti, piu' del capoluogo (4.500), a 1,1 km da Piazza della Liberta'. Un punto a
    // 300 m da Valdragone non deve dare "Valdragone": dentro un micro-stato rispondono la capitale e i castelli (sedi
    // amministrative), non i villaggi. Prima di questa regola era l'errore di T078.
    val match = mini.nearest(GeoPoint(43.9424, 12.4578))!!
    assertThat(match.place.name).isIn(listOf("San Marino", "Borgo Maggiore"))
    assertThat(match.place.countryCode).isEqualTo("SM")
    assertThat(nameAt(43.9402, 12.4562)).isNotEqualTo("Valdragone") // proprio nel villaggio
    // Fuori dai micro-stati un villaggio qualificato resta una risposta valida (qui non ce n'e' uno in dataset di prova).
  }

  // ---- New York e i suoi borough -----------------------------------------------------------------------------------

  @Test
  fun timesSquareENewYorkNonManhattan() {
    // Manhattan (1,5 milioni, raggio 6 km) batteva New York (8,8 milioni, raggio 8 km) di 400 m per il suo centroide piu'
    // vicino: un borough e' un distretto della citta' e non compete (DistrictAbsorption).
    assertThat(nameAt(40.7580, -73.9855)).isEqualTo("New York") // Times Square
    assertThat(nameAt(40.7829, -73.9654)).isEqualTo("New York") // Central Park
    assertThat(nameAt(40.7060, -74.0088)).isEqualTo("New York") // Wall Street
    assertThat(nameAt(40.6501, -73.9496)).isEqualTo("New York") // centro di Brooklyn
    assertThat(nameAt(40.6815, -73.8365)).isEqualTo("New York") // centro del Queens
    // Il Bronx dista 19 km dal centroide di New York, oltre la somma dei due raggi: li' compete e vince il suo nome.
    assertThat(nameAt(40.8499, -73.8664)).isEqualTo("The Bronx")
  }

  // ---- Le localita' molto note --------------------------------------------------------------------------------------

  @Test
  fun unaLocalitaSotto1000AbitantiMaMoltoNotaEUnRisultato() {
    // Paestum: 150 abitanti, 15 nomi alternativi. Una frazione con 0 nomi alternativi (Manarola nel dataset di prova) no.
    val paestum = mini.nearest(GeoPoint(40.4225, 15.0058))!!
    assertThat(paestum.place.name).isEqualTo("Paestum")
    assertThat(paestum.precision).isEqualTo(PlacePrecision.CITY)
    assertThat(nameAt(44.1073, 9.7285)).isEqualTo("Riomaggiore")
  }

  @Test
  fun riminiNonVieneRubataDallaVicinaSanMarino() {
    assertThat(nameAt(44.0600, 12.5680)).isEqualTo("Rimini") // il centro
    // 1,7 km a nord del confine, ma piu' vicino al centroide di Serravalle (3,5 km) che a quello di Rimini (9 km):
    // i luoghi di San Marino fuori dal suo poligono non competono.
    val match = mini.nearest(GeoPoint(44.0000, 12.4900))!!
    assertThat(match.place.name).isEqualTo("Rimini")
    assertThat(match.place.countryCode).isEqualTo("IT")
    assertThat(mini.zoneAt(GeoPoint(44.0000, 12.4900))!!.id).isEqualTo("Europe/Rome")
  }

  // ---- Monaco ------------------------------------------------------------------------------------------------------

  @Test
  fun monacoEMonacoSoloDentro() {
    assertThat(nameAt(43.7399, 7.4276)).isEqualTo("Monaco") // Casino di Monte Carlo
    assertThat(nameAt(43.7335, 7.4200)).isEqualTo("Monaco") // Porto Ercole
    assertThat(mini.zoneAt(GeoPoint(43.7399, 7.4276))!!.id).isEqualTo("Europe/Monaco")
    // 1,4 km a est dell'ultimo vertice: piu' vicino al centroide di Monaco che a Mentone, ma e' Francia.
    val outside = mini.nearest(GeoPoint(43.7470, 7.4600))!!
    assertThat(outside.place.name).isEqualTo("Mentone")
    assertThat(outside.place.countryCode).isEqualTo("FR")
  }

  // ---- Liechtenstein ---------------------------------------------------------------------------------------------

  @Test
  fun ilLiechtensteinEdentroIlPoligono() {
    assertThat(nameAt(47.1410, 9.5209)).isEqualTo("Vaduz")
    assertThat(nameAt(47.1650, 9.5090)).isEqualTo("Schaan")
    assertThat(nameAt(47.1030, 9.6120)).isEqualTo("Vaduz") // Malbun, in alta valle
    assertThat(mini.zoneAt(GeoPoint(47.1410, 9.5209))!!.id).isEqualTo("Europe/Vaduz")
    // Oltre il Reno e' Svizzera, anche se il centroide di Vaduz e' vicino.
    assertThat(nameAt(47.1655, 9.4789)).isEqualTo("Buchs")
    assertThat(nameAt(47.1700, 9.4850)).isEqualTo("Buchs")
  }

  // ---- Il resto non cambia ---------------------------------------------------------------------------------------------

  @Test
  fun uncentroNormaleNonSiAccorge() {
    assertThat(nameAt(45.4641, 9.1919)).isEqualTo("Milano")
    assertThat(nameAt(43.7680, 11.2531)).isEqualTo("Firenze")
    assertThat(nameAt(51.5007, -0.1246)).isEqualTo("Londra")
    assertThat(nameAt(30.0, -40.0)).isNull()
  }
}
