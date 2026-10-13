package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * L'accuratezza sulle 200 coordinate (docs/fase3-progetto.md §2.3): `taratura-100.tsv` serve a tarare, `verifica-100.tsv`
 * e' cieco e non si usa mai per tarare. Qui girano sul mini-dataset di prova; sul dataset reale girano gli stessi file
 * (RealDatasetAccuracyTest, appena l'asset esiste). Soglia: almeno il 95% corretti su ciascun file.
 */
class PlacesAccuracyTest {

  private val data = PlacesDataset.openForTest(TestData.miniBytes)

  private fun report(file: String): PlacesAccuracy.Report {
    val rows = PlacesAccuracy.parse(TestData.readTsv(file))
    assertThat(rows).hasSize(100)
    return PlacesAccuracy.evaluate(data, file, rows).also { println(it) }
  }

  @Test
  fun taratura100AlmenoIl95PerCento() {
    val report = report("taratura-100.tsv")
    assertThat(report.percent).isAtLeast(95.0)
  }

  @Test
  fun verifica100CiecoAlmenoIl95PerCento() {
    val report = report("verifica-100.tsv")
    assertThat(report.percent).isAtLeast(95.0)
  }

  @Test
  fun iDueFileNonHannoCoordinateInComune() {
    val a = TestData.readTsv("taratura-100.tsv").map { it[1] to it[2] }.toSet()
    val b = TestData.readTsv("verifica-100.tsv").map { it[1] to it[2] }.toSet()
    assertThat(a).hasSize(100)
    assertThat(b).hasSize(100)
    assertThat(a.intersect(b)).isEmpty()
    // Gli id non si ripetono e dicono da che file vengono.
    assertThat(TestData.readTsv("taratura-100.tsv").map { it[0] }.all { it.startsWith("T") }).isTrue()
    assertThat(TestData.readTsv("verifica-100.tsv").map { it[0] }.all { it.startsWith("V") }).isTrue()
  }

  @Test
  fun leDueCategorieDiRigheCopronoITipiDiCasoDelProgetto() {
    // Il progetto (§2.3) elenca cosa devono contenere i due file: centri e periferie, piccoli comuni, isole, coste, rifugi,
    // confini (Como/Chiasso, Gorizia/Nova Gorica, Ventimiglia/Menton, San Marino, Vaticano) ed estero con esonimi.
    val notes = (TestData.readTsv("taratura-100.tsv") + TestData.readTsv("verifica-100.tsv")).joinToString("\n") { it.last() }
    listOf(
      "Chiasso", "Piazza Transalpina", "Ponte San Luigi", "San Marino", "Piazza San Pietro", "Rifugio", "Bicocca",
      "Faraglioni", "Stromboli", "Big Ben", "Torre Eiffel", "Marienplatz", "Praca do Comercio", "Municipio di Oslo",
    ).forEach { assertThat(notes).contains(it) }
  }

  /** Lo stesso controllo, sull'asset vero, appena `buildPlacesDataset` lo avra' generato (docs/luoghi.md, passo 4). */
  @Test
  fun realDatasetAccuracy() {
    val asset = File("src/main/assets/places/places.mcgp1")
    assumeTrue("Nessun dataset reale in assets/places: si prova dopo buildPlacesDataset.", asset.isFile)
    val real = PlacesDataset.openForTest(asset.readBytes())
    for (file in listOf("taratura-100.tsv", "verifica-100.tsv")) {
      val report = PlacesAccuracy.evaluate(real, file, PlacesAccuracy.parse(TestData.readTsv(file)))
      println(report)
      assertThat(report.percent).isAtLeast(95.0)
    }
  }
}
