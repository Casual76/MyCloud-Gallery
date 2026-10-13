package dev.pampa.mycloudgallery.core.places.tool

import dev.pampa.mycloudgallery.core.places.dataset.Mcgp1Writer
import dev.pampa.mycloudgallery.core.places.dataset.Hex
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Lo strumento che genera il dataset dei luoghi: `gradlew :core-places:buildPlacesDataset` (docs/luoghi.md).
 *
 * Legge i dump di GeoNames da `%USERPROFILE%\.mycloudgallery\geonames\` (o da `--input`), scrive il file MCGP1
 * (`--output`) e la sua impronta nel codice (`--pin`: `PlacesDatasetPin.kt`). Lo stesso input da' sempre lo stesso file,
 * quindi lo stesso SHA-256: `--expect-sha256` lo verifica.
 *
 * **Non scarica niente, mai.** Se la cartella o un file mancano si ferma con un messaggio che dice cosa scaricare e
 * dove metterlo, e non scrive nulla. Stampa solo conteggi e impronte: nessun nome di luogo, nessuna coordinata.
 *
 * Codici di uscita: 0 fatto; 1 errore imprevisto; 2 input mancante; 3 argomenti sbagliati; 4 l'impronta non e' quella attesa.
 */
object BuildPlacesDataset {

  const val EXIT_OK = 0
  const val EXIT_ERROR = 1
  const val EXIT_MISSING_INPUT = 2
  const val EXIT_USAGE = 3
  const val EXIT_UNEXPECTED_HASH = 4

  @JvmStatic
  fun main(args: Array<String>) {
    exitProcess(run(args, System.out, System.err))
  }

  /** Il corpo dello strumento, con i flussi di uscita passati dal chiamante (cosi' i test lo provano). */
  fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
    val options = try {
      Options.parse(args)
    } catch (e: IllegalArgumentException) {
      err.println("Argomenti non validi: ${e.message}")
      err.println(USAGE)
      return EXIT_USAGE
    }

    val dir = options.input ?: GeoNamesInput.defaultDirectory()
    val input = try {
      if (!dir.isDirectory) throw MissingInputException(listOf("la cartella ${dir.path}"))
      GeoNamesInput.fromDirectory(dir)
    } catch (e: MissingInputException) {
      err.println("MANCANO I DATI DI GEONAMES: lo strumento non scarica niente e non ha scritto niente.")
      err.println("Cartella cercata: ${dir.path}")
      e.missing.forEach { err.println("  mancante: $it") }
      err.println()
      err.println("Scarica a mano questi file da https://download.geonames.org/export/dump/ e mettili in quella cartella:")
      REQUIRED_DOWNLOADS.forEach { err.println("  $it") }
      err.println("I passi completi (anche l'attribuzione CC BY 4.0) sono in docs/luoghi.md.")
      return EXIT_MISSING_INPUT
    }

    return try {
      val started = System.nanoTime()
      out.println("Letti: ${input.description.joinToString(", ")}")
      val built = PlacesDatasetBuilder.build(input)
      val bytes = Mcgp1Writer.write(built.data)
      val sha = Hex.of(MessageDigest.getInstance("SHA-256").digest(bytes))
      val stats = built.stats

      out.println("Luoghi tenuti: ${stats.placesKept} (da cities1000: ${stats.placesFromCities}, da IT: ${stats.placesFromItaly})")
      out.println("Scartati: ${stats.droppedByCode} per codice, ${stats.droppedTooSmall} troppo piccoli, ${stats.droppedNotPopulatedPlace} non abitati, ${stats.droppedMalformed} malformati")
      out.println("Nomi italiani: ${stats.italianPlaceNames} luoghi, ${stats.italianRegionNames} regioni su ${stats.regions}")
      out.println("Versione del dataset (data del dump): ${stats.dumpDate} -> ${stats.version}")
      out.println("File: ${bytes.size} byte, SHA-256 $sha")

      if (options.expectSha256 != null && !options.expectSha256.equals(sha, ignoreCase = true)) {
        err.println("L'IMPRONTA NON E' QUELLA ATTESA: attesa ${options.expectSha256}, ottenuta $sha. Non ho scritto niente.")
        err.println("Con lo stesso input l'impronta deve essere sempre la stessa: o i dati di GeoNames sono diversi, o c'e' un errore nello strumento.")
        return EXIT_UNEXPECTED_HASH
      }

      options.output?.let {
        writeAtomically(it, bytes)
        out.println("Scritto: ${it.path}")
      }
      options.pin?.let {
        writeAtomically(it, renderPin(stats.version, sha, bytes.size.toLong(), stats.placesKept).toByteArray(Charsets.UTF_8))
        out.println("Impronta scritta in: ${it.path}")
      }
      out.println("Fatto in ${(System.nanoTime() - started) / 1_000_000_000} s. Ora: gradlew :core-places:testDebugUnitTest (docs/luoghi.md, passo 4).")
      EXIT_OK
    } catch (e: Exception) {
      err.println("Errore imprevisto (${e.javaClass.simpleName}): ${e.message}")
      EXIT_ERROR
    }
  }

  /** Il contenuto di `PlacesDatasetPin.kt` per un dataset reale. */
  internal fun renderPin(version: Int, sha256: String, sizeBytes: Long, placeCount: Int): String = """
    package dev.pampa.mycloudgallery.core.places.dataset

    // GENERATO da `gradlew :core-places:buildPlacesDataset` (docs/luoghi.md): non modificare a mano.
    // Questa e' l'impronta del dataset dei luoghi incluso nell'APK. Finche' SHA256 e' vuoto non c'e' nessun dataset
    // reale: PlacesDataset.open() restituisce i null-object e la Libreria funziona senza luoghi.

    /** Il dataset incluso nell'APK: versione (data del dump GeoNames), SHA-256 e dimensione del file `places.mcgp1`. */
    internal object PlacesDatasetPin {
      const val VERSION: Int = $version
      const val SHA256: String = "$sha256"
      const val SIZE_BYTES: Long = ${sizeBytes}L
      const val PLACE_COUNT: Int = $placeCount
      const val ASSET_PATH: String = "places/places.mcgp1"
    }
  """.trimIndent() + "\n"

  private fun writeAtomically(target: File, bytes: ByteArray) {
    target.absoluteFile.parentFile?.mkdirs()
    val temp = File(target.absoluteFile.parentFile, target.name + ".tmp")
    Files.write(temp.toPath(), bytes)
    try {
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private class Options(val input: File?, val output: File?, val pin: File?, val expectSha256: String?) {
    companion object {
      fun parse(args: Array<String>): Options {
        var input: File? = null
        var output: File? = null
        var pin: File? = null
        var expect: String? = null
        var i = 0
        while (i < args.size) {
          val name = args[i]
          require(i + 1 < args.size) { "manca il valore di $name" }
          val value = args[i + 1]
          when (name) {
            "--input" -> input = File(value)
            "--output" -> output = File(value)
            "--pin" -> pin = File(value)
            "--expect-sha256" -> {
              require(value.matches(Regex("[0-9a-fA-F]{64}"))) { "--expect-sha256 vuole 64 cifre esadecimali" }
              expect = value
            }
            else -> throw IllegalArgumentException("opzione sconosciuta $name")
          }
          i += 2
        }
        return Options(input, output, pin, expect)
      }
    }
  }

  private const val USAGE =
    "Uso: BuildPlacesDataset [--input CARTELLA] [--output FILE.mcgp1] [--pin PlacesDatasetPin.kt] [--expect-sha256 HEX]"

  private val REQUIRED_DOWNLOADS = listOf(
    "cities1000.zip        (circa 8 MB)    https://download.geonames.org/export/dump/cities1000.zip",
    "IT.zip                (circa 1 MB)    https://download.geonames.org/export/dump/IT.zip",
    "admin1CodesASCII.txt  (circa 0,2 MB)  https://download.geonames.org/export/dump/admin1CodesASCII.txt",
    "alternateNamesV2.zip  (circa 190 MB)  https://download.geonames.org/export/dump/alternateNamesV2.zip",
  )
}
