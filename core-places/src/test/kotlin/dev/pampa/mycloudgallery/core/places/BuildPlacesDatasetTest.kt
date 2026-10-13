package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1Writer
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import dev.pampa.mycloudgallery.core.places.tool.BuildPlacesDataset
import dev.pampa.mycloudgallery.core.places.tool.GeoNamesInput
import dev.pampa.mycloudgallery.core.places.tool.MissingInputException
import dev.pampa.mycloudgallery.core.places.tool.PlacesDatasetBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lo strumento che genera il dataset (docs/luoghi.md): le regole di cosa si tiene, i nomi italiani, la riproducibilita'
 * (stesso input, stesso SHA-256) e il comportamento quando i dati di GeoNames non ci sono (si ferma, non scarica).
 */
class BuildPlacesDatasetTest {

  @get:Rule
  val temp = TemporaryFolder()

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private val built by lazy { PlacesDatasetBuilder.build(TestData.miniInput()) }

  private fun namesKept(): Set<String> = built.data.places.map { it.name }.toSet()

  /** Copia i dump di prova in una cartella come li metterebbe l'utente: tre zip e un file di testo. */
  private fun userFolder(zipped: Boolean = true, shuffle: Random? = null): File {
    val dir = temp.newFolder()
    fun lines(name: String): String {
      val text = File(TestData.miniDirectory, name).readText(Charsets.UTF_8)
      return if (shuffle == null) text else text.lines().filter { it.isNotEmpty() }.shuffled(shuffle).joinToString("\n", postfix = "\n")
    }
    fun put(base: String) {
      if (zipped) {
        ZipOutputStream(File(dir, "$base.zip").outputStream()).use { zip ->
          // Come nei dump veri, accanto al file dei dati c'e' un file di spiegazioni.
          zip.putNextEntry(ZipEntry("readme.txt"))
          zip.write("spiegazioni".toByteArray())
          zip.closeEntry()
          zip.putNextEntry(ZipEntry("$base.txt"))
          zip.write(lines("$base.txt").toByteArray(Charsets.UTF_8))
          zip.closeEntry()
        }
      } else {
        File(dir, "$base.txt").writeText(lines("$base.txt"), Charsets.UTF_8)
      }
    }
    put("cities1000")
    put("IT")
    put("alternateNamesV2")
    File(dir, "admin1CodesASCII.txt").writeText(File(TestData.miniDirectory, "admin1CodesASCII.txt").readText(Charsets.UTF_8), Charsets.UTF_8)
    return dir
  }

  private fun runTool(vararg args: String): Triple<Int, String, String> {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = BuildPlacesDataset.run(arrayOf(*args), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
    return Triple(code, out.toString("UTF-8"), err.toString("UTF-8"))
  }

  // ---- Cosa si tiene e cosa no -------------------------------------------------------------------------------------

  @Test
  fun siTengonoSoloIluoghiAbitatiVeri() {
    val kept = namesKept()
    // Quartieri (PPLX, anche con piu' di 1000 abitanti), storici (PPLH), abbandonati (PPLQ), frazioni sotto i 1000
    // abitanti che non sono sede amministrativa e cio' che non e' un luogo abitato: fuori.
    listOf(
      "Brera", "Trastevere", "Sibari", "Craco", "Stromboli", "Manarola", "Madonna di Campiglio", "Breuil-Cervinia",
      "Porto Cervo", "Misurina", "Monte Bianco", "Lago di Como", "Vesuvio",
    ).forEach { assertThat(kept).doesNotContain(it) }
    // I comuni sotto i 1000 abitanti (PPLA3) entrano: sono luoghi veri e capoluoghi del loro comune.
    listOf("Portofino", "Varenna", "Sestriere", "Alagna Valsesia", "Macugnaga", "Ceresole Reale", "Castel del Monte", "La Thuile")
      .forEach { assertThat(kept).contains(it) }
    assertThat(built.stats.droppedByCode).isAtLeast(4)
    assertThat(built.stats.droppedTooSmall).isAtLeast(5)
    assertThat(built.stats.droppedNotPopulatedPlace).isAtLeast(3)
  }

  @Test
  fun lePolitichedelleClassi() {
    assertThat(PlacesDatasetBuilder.classify("PPLC", 800)).isEqualTo(3)
    assertThat(PlacesDatasetBuilder.classify("PPLA3", 12)).isEqualTo(2)
    assertThat(PlacesDatasetBuilder.classify("PPLG", 0)).isEqualTo(2)
    assertThat(PlacesDatasetBuilder.classify("PPL", 1000)).isEqualTo(1)
    assertThat(PlacesDatasetBuilder.classify("PPL", 999)).isEqualTo(0)
    assertThat(PlacesDatasetBuilder.classify("PPLX", 500_000)).isEqualTo(-1)
    assertThat(PlacesDatasetBuilder.classify("PPLH", 5_000)).isEqualTo(-1)
    assertThat(PlacesDatasetBuilder.classify("PPLQ", 5_000)).isEqualTo(-1)
  }

  @Test
  fun iNomiSonoInItalianoQuandoEsistono() {
    val kept = namesKept()
    listOf(
      "Londra", "Parigi", "Monaco di Baviera", "Lisbona", "Oslo", "Vienna", "Praga", "Atene", "Mosca", "Il Cairo",
      "Milano", "Roma", "Firenze", "Venezia", "Napoli", "Torino", "Genova", "Cefalù", "Forlì", "Gressoney-La-Trinité",
      "Città del Vaticano", "Capodistria", "Pola", "Fiume", "Colonia", "Francoforte sul Meno", "Istanbul", "L'Aia",
    ).forEach { assertThat(kept).contains(it) }
    // Il nome inglese o locale non resta quando c'e' quello italiano.
    listOf("London", "Paris", "Munich", "Lisbon", "Rome", "Florence", "Milan", "Koeln", "Rijeka", "Pula", "Vatican City")
      .forEach { assertThat(kept).doesNotContain(it) }
    assertThat(built.stats.italianPlaceNames).isAtLeast(90)
  }

  @Test
  fun traPiuNomiItalianiVinceIlPreferitoNonQuelloAbbreviatoColloquialeOStorico() {
    val kept = namesKept()
    // "Monaco" (abbreviato) perde contro "Monaco di Baviera" (preferito): Monaco di Baviera e' l'unico luogo tedesco che inizia cosi'.
    val germanMonaco = built.data.places.filter { it.countryCode == "DE" && it.name.startsWith("Monaco") }.map { it.name }
    assertThat(germanMonaco).containsExactly("Monaco di Baviera")
    assertThat(kept).doesNotContain("Londra capitale")
    assertThat(kept).doesNotContain("Costantinopoli")
    assertThat(kept).doesNotContain("Roma capitale")
    assertThat(kept).doesNotContain("Sankt Veit am Flaum")
    assertThat(kept).contains("Monaco di Baviera")
  }

  @Test
  fun leRegioniSonoInItaliano() {
    val regions = built.data.regions.associate { it.key to it.name }
    assertThat(regions["IT.09"]).isEqualTo("Lombardia")
    assertThat(regions["IT.16"]).isEqualTo("Toscana")
    assertThat(regions["IT.07"]).isEqualTo("Lazio")
    assertThat(regions["IT.12"]).isEqualTo("Piemonte")
    assertThat(regions["IT.13"]).isEqualTo("Puglia")
    assertThat(regions["IT.14"]).isEqualTo("Sardegna")
    assertThat(regions["IT.15"]).isEqualTo("Sicilia")
    assertThat(regions["IT.19"]).isEqualTo("Valle d'Aosta")
    assertThat(regions["IT.20"]).isEqualTo("Veneto") // gia' italiano: resta com'e'
    assertThat(regions["DE.02"]).isEqualTo("Baviera")
    assertThat(regions["GB.ENG"]).isEqualTo("Inghilterra")
    assertThat(regions["GB.SCT"]).isEqualTo("Scozia")
    assertThat(regions["CH.TI"]).isEqualTo("Ticino")
    // Solo le regioni usate da almeno un luogo tenuto.
    assertThat(built.data.regions.map { it.key }).containsNoDuplicates()
    assertThat(built.stats.italianRegionNames).isAtLeast(15)
  }

  @Test
  fun iLuoghiSenzaRegioneNonNeInventano() {
    val noRegion = built.data.places.filter { it.regionKey == null }.map { it.name }
    assertThat(noRegion).containsAtLeast("Monaco", "Città del Vaticano", "Nova Gorica", "Capodistria", "Longyearbyen", "Hong Kong")
  }

  @Test
  fun unLuogoInPiuFileSiTieneUnaVolta() {
    val ids = built.data.places.map { it.id }
    assertThat(ids).containsNoDuplicates()
    // Gran parte dei luoghi italiani e' sia in cities1000 che in IT: il totale tenuto e' minore della somma.
    assertThat(built.stats.placesKept).isLessThan(built.stats.placesFromCities + built.stats.placesFromItaly)
  }

  @Test
  fun laVersioneEIlDumpPiuRecente() {
    assertThat(built.stats.dumpDate).isEqualTo("2025-02-14")
    assertThat(built.data.version).isEqualTo(20250214)
    assertThat(PlacesDatasetBuilder.versionOf("2026-10-13")).isEqualTo(20261013)
    assertThrows(IllegalStateException::class.java) { PlacesDatasetBuilder.versionOf("") }
  }

  @Test
  fun ilDatasetCostruitoSiApreESiInterroga() {
    val data = PlacesDataset.openForTest(TestData.miniBytes)
    assertThat(data.dataset).isEqualTo(20250214)
    assertThat(data.placeCount).isEqualTo(built.stats.placesKept)
    val roma = data.nearest(dev.pampa.mycloudgallery.core.model.GeoPoint(41.8902, 12.4922))!!.place
    assertThat(roma.name).isEqualTo("Roma")
    assertThat(roma.admin1).isEqualTo("Lazio")
    assertThat(roma.countryName).isEqualTo("Italia")
    assertThat(roma.zone).isEqualTo("Europe/Rome")
    val londra = data.nearest(dev.pampa.mycloudgallery.core.model.GeoPoint(51.5007, -0.1246))!!.place
    assertThat(londra.name).isEqualTo("Londra")
    assertThat(londra.admin1).isEqualTo("Inghilterra")
    assertThat(londra.countryName).isEqualTo("Regno Unito")
  }

  // ---- Riproducibilita' -------------------------------------------------------------------------------------------

  @Test
  fun lostessoInputDaLoStessoSha256() {
    val first = sha256(Mcgp1Writer.write(PlacesDatasetBuilder.build(TestData.miniInput()).data))
    val second = sha256(Mcgp1Writer.write(PlacesDatasetBuilder.build(TestData.miniInput()).data))
    assertThat(second).isEqualTo(first)
    assertThat(sha256(TestData.miniBytes)).isEqualTo(first)
  }

  @Test
  fun zipOTestoEOrdineDelleRigheNonCambianoIlRisultato() {
    val reference = sha256(TestData.miniBytes)
    for (dir in listOf(userFolder(zipped = true), userFolder(zipped = false), userFolder(zipped = true, shuffle = Random(3)))) {
      val rebuilt = PlacesDatasetBuilder.build(GeoNamesInput.fromDirectory(dir))
      assertThat(sha256(Mcgp1Writer.write(rebuilt.data))).isEqualTo(reference)
    }
  }

  // ---- La riga di comando ----------------------------------------------------------------------------------------

  @Test
  fun senzaLaCartellaSiFermaConUnMessaggioChiaroENonScriveNulla() {
    val missing = File(temp.root, "non-esiste")
    val output = File(temp.root, "out/places.mcgp1")
    val pin = File(temp.root, "out/Pin.kt")
    val (code, stdout, stderr) = runTool("--input", missing.path, "--output", output.path, "--pin", pin.path)
    assertThat(code).isEqualTo(BuildPlacesDataset.EXIT_MISSING_INPUT)
    assertThat(stdout).isEmpty()
    assertThat(stderr).contains("non scarica niente")
    assertThat(stderr).contains("cities1000.zip")
    assertThat(stderr).contains("IT.zip")
    assertThat(stderr).contains("admin1CodesASCII.txt")
    assertThat(stderr).contains("alternateNamesV2.zip")
    assertThat(stderr).contains("docs/luoghi.md")
    assertThat(output.exists()).isFalse()
    assertThat(pin.exists()).isFalse()
    assertThat(File(temp.root, "out").exists()).isFalse()
  }

  @Test
  fun conAlcuniFileMancantiElencaSoloQuelli() {
    val dir = temp.newFolder()
    File(dir, "admin1CodesASCII.txt").writeText("IT.09\tLombardy\tLombardy\t1\n")
    val e = assertThrows(MissingInputException::class.java) { GeoNamesInput.fromDirectory(dir) }
    assertThat(e.missing).containsExactly("cities1000.zip", "IT.zip", "alternateNamesV2.zip")
    val (code, _, stderr) = runTool("--input", dir.path)
    assertThat(code).isEqualTo(BuildPlacesDataset.EXIT_MISSING_INPUT)
    assertThat(stderr).contains("mancante: cities1000.zip")
    assertThat(stderr).doesNotContain("mancante: admin1CodesASCII.txt")
  }

  @Test
  fun laCartellaPredefinitaEQuellaDelProfiloUtente() {
    val dir = GeoNamesInput.defaultDirectory()
    assertThat(dir.name).isEqualTo("geonames")
    assertThat(dir.parentFile.name).isEqualTo(".mycloudgallery")
  }

  @Test
  fun dallaCartellaDellUtenteAlFileEAlPin() {
    val dir = userFolder()
    val output = File(temp.root, "assets/places/places.mcgp1")
    val pin = File(temp.root, "kotlin/PlacesDatasetPin.kt")
    val (code, stdout, stderr) = runTool("--input", dir.path, "--output", output.path, "--pin", pin.path)
    assertThat(stderr).isEmpty()
    assertThat(code).isEqualTo(BuildPlacesDataset.EXIT_OK)
    assertThat(output.readBytes()).isEqualTo(TestData.miniBytes)
    val sha = sha256(TestData.miniBytes)
    assertThat(stdout).contains(sha)
    assertThat(stdout).doesNotContain("Roma") // il resoconto ha solo conteggi
    assertThat(pin.readText()).isEqualTo(BuildPlacesDataset.renderPin(20250214, sha, TestData.miniBytes.size.toLong(), built.stats.placesKept))
    assertThat(pin.readText()).contains("const val SHA256: String = \"$sha\"")
    assertThat(pin.readText()).contains("const val VERSION: Int = 20250214")
    // Nessun file temporaneo rimasto.
    assertThat(output.parentFile.listFiles()!!.map { it.name }).containsExactly("places.mcgp1")
  }

  @Test
  fun conLImprontaAttesaDiversaNonScriveNulla() {
    val dir = userFolder()
    val output = File(temp.root, "o/places.mcgp1")
    val (bad, _, stderr) = runTool("--input", dir.path, "--output", output.path, "--expect-sha256", "0".repeat(64))
    assertThat(bad).isEqualTo(BuildPlacesDataset.EXIT_UNEXPECTED_HASH)
    assertThat(stderr).contains("NON E' QUELLA ATTESA")
    assertThat(output.exists()).isFalse()
    val (good, _, _) = runTool("--input", dir.path, "--output", output.path, "--expect-sha256", sha256(TestData.miniBytes))
    assertThat(good).isEqualTo(BuildPlacesDataset.EXIT_OK)
    assertThat(output.isFile).isTrue()
  }

  @Test
  fun gliArgomentiSbagliatiDannoCodice3() {
    assertThat(runTool("--boh", "x").first).isEqualTo(BuildPlacesDataset.EXIT_USAGE)
    assertThat(runTool("--input").first).isEqualTo(BuildPlacesDataset.EXIT_USAGE)
    assertThat(runTool("--expect-sha256", "abc").first).isEqualTo(BuildPlacesDataset.EXIT_USAGE)
  }

  @Test
  fun ilPinCommittatoEQuelloDiNessunDatasetOQuelloDellAsset() {
    val pinFile = File("src/main/kotlin/dev/pampa/mycloudgallery/core/places/dataset/PlacesDatasetPin.kt")
    val text = pinFile.readText()
    val asset = File("src/main/assets/places/places.mcgp1")
    if (!asset.isFile) {
      // Finche' non c'e' l'asset reale il pin e' esattamente quello "vuoto" che lo strumento sa scrivere.
      assertThat(text).isEqualTo(BuildPlacesDataset.renderPin(0, "", 0L, 0))
    } else {
      val bytes = asset.readBytes()
      assertThat(text).contains("const val SHA256: String = \"${sha256(bytes)}\"")
      assertThat(text).contains("const val SIZE_BYTES: Long = ${bytes.size}L")
      val opened = PlacesDataset.openForTest(bytes)
      assertThat(text).contains("const val VERSION: Int = ${opened.dataset}")
      assertThat(text).contains("const val PLACE_COUNT: Int = ${opened.placeCount}")
    }
  }
}
