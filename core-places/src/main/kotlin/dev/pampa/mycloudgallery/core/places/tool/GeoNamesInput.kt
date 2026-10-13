package dev.pampa.mycloudgallery.core.places.tool

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

// Lo strumento `buildPlacesDataset` (docs/luoghi.md): legge i dump di GeoNames che l'UTENTE ha scaricato a mano e ne
// ricava il file MCGP1. Questo e' il primo pezzo: dove stanno i file e come si leggono. Niente rete, di proposito:
// se un file manca lo strumento si ferma e dice cosa scaricare, non lo scarica.

/** Uno o piu' file di input mancanti: [missing] sono i nomi dei file attesi. */
internal class MissingInputException(val missing: List<String>) : Exception("File GeoNames mancanti.")

/**
 * Le quattro sorgenti di input come flussi riaprendibili (si leggono in momenti diversi). Ognuna e' un testo UTF-8 nel
 * formato dei dump di GeoNames (colonne separate da tabulazione).
 */
internal class GeoNamesInput(
  /** `cities1000.txt`: i luoghi del mondo con almeno 1000 abitanti o sede amministrativa. */
  val cities: () -> InputStream,
  /** `IT.txt`: tutti i luoghi italiani (anche i comuni sotto i 1000 abitanti). */
  val italy: () -> InputStream,
  /** `admin1CodesASCII.txt`: codice, nome, nome ASCII e id GeoNames di ogni regione. */
  val admin1: () -> InputStream,
  /** `alternateNamesV2.txt`: tutti i nomi alternativi (si leggono solo le righe `it` dei luoghi tenuti). */
  val alternateNames: () -> InputStream,
  /** Cosa e' stato letto, per il resoconto dello strumento (nomi di file, non percorsi personali). */
  val description: List<String>,
) {
  companion object {
    const val CITIES = "cities1000"
    const val ITALY = "IT"
    const val ADMIN1 = "admin1CodesASCII"
    const val ALTERNATE_NAMES = "alternateNamesV2"

    /** Cartella predefinita: `%USERPROFILE%\.mycloudgallery\geonames`. */
    fun defaultDirectory(): File {
      val home = System.getenv("USERPROFILE")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home") ?: "."
      return File(File(home, ".mycloudgallery"), "geonames")
    }

    /**
     * Cerca i file in [dir]: `cities1000.zip` (oppure `cities1000.txt` gia' estratto), `IT.zip`/`IT.txt`,
     * `alternateNamesV2.zip`/`alternateNamesV2.txt` e `admin1CodesASCII.txt`. Se ne manca anche uno solo lancia
     * [MissingInputException] con l'elenco completo dei mancanti, **senza leggere ne' scrivere niente**.
     */
    fun fromDirectory(dir: File): GeoNamesInput {
      val missing = mutableListOf<String>()
      val description = mutableListOf<String>()

      fun source(base: String): (() -> InputStream)? {
        val zip = File(dir, "$base.zip")
        val txt = File(dir, "$base.txt")
        if (zip.isFile) {
          description += zip.name
          return { openZipEntry(zip, "$base.txt") }
        }
        if (txt.isFile) {
          description += txt.name
          return { FileInputStream(txt) }
        }
        missing += "$base.zip"
        return null
      }

      val cities = source(CITIES)
      val italy = source(ITALY)
      val alternate = source(ALTERNATE_NAMES)
      val admin1File = File(dir, "$ADMIN1.txt")
      if (admin1File.isFile) description += admin1File.name else missing += admin1File.name
      if (missing.isNotEmpty()) throw MissingInputException(missing)
      return GeoNamesInput(
        cities = cities!!,
        italy = italy!!,
        admin1 = { FileInputStream(admin1File) },
        alternateNames = alternate!!,
        description = description,
      )
    }

    /** Apre l'elemento [entryName] di [zip] (il nome, senza cartelle, senza badare alle maiuscole). */
    private fun openZipEntry(zip: File, entryName: String): InputStream {
      val stream = ZipInputStream(FileInputStream(zip).buffered(1 shl 16))
      while (true) {
        val entry = stream.nextEntry
        if (entry == null) {
          stream.close()
          throw MissingInputException(listOf("$entryName dentro ${zip.name}"))
        }
        if (!entry.isDirectory && entry.name.substringAfterLast('/').equals(entryName, ignoreCase = true)) return stream
      }
    }
  }
}

/** Un luogo di un dump di GeoNames (solo i campi che servono). */
internal class GeoPlace(
  val id: Int,
  val name: String,
  val latitude: Double,
  val longitude: Double,
  val featureCode: String,
  val countryCode: String,
  val admin1: String,
  val population: Long,
  val timezone: String,
  /** La data di modifica del record, `aaaa-mm-gg`. */
  val modified: String,
  /** Quanti nomi alternativi diversi ha (nelle lingue e nelle grafie di tutto il mondo): un indice di notorieta'. */
  val alternateNameCount: Int = 0,
)

/** Una riga di `admin1CodesASCII.txt`. */
internal class Admin1(val key: String, val name: String, val id: Int)

/** Un nome alternativo italiano con i criteri per sceglierne uno. */
internal class ItalianName(val alternateNameId: Long, val name: String, val preferred: Boolean, val short: Boolean, val colloquial: Boolean) {
  /** Vero se questo nome e' da preferire a [other]: preferito, poi non abbreviato, poi non colloquiale, poi il piu' vecchio. */
  fun beats(other: ItalianName): Boolean {
    if (preferred != other.preferred) return preferred
    if (short != other.short) return !short
    if (colloquial != other.colloquial) return !colloquial
    return alternateNameId < other.alternateNameId
  }
}

/** Il lettore dei formati di GeoNames. Ogni funzione ignora le righe malformate (si contano a parte). */
internal object GeoNamesParser {

  fun reader(stream: InputStream): BufferedReader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 1 shl 16)

  /**
   * Una riga di `cities1000.txt` / `IT.txt` (19 colonne). Null se malformata o se non e' un luogo abitato (classe `P`).
   * Colonne: 0 id, 1 nome, 4 latitudine, 5 longitudine, 6 classe, 7 codice, 8 paese, 10 admin1, 14 popolazione,
   * 17 fuso, 18 data di modifica.
   */
  fun parsePlace(line: String): GeoPlace? {
    val f = line.split('\t')
    if (f.size < 18) return null
    if (f[6] != "P") return null
    val id = f[0].toIntOrNull() ?: return null
    val lat = f[4].toDoubleOrNull() ?: return null
    val lon = f[5].toDoubleOrNull() ?: return null
    if (id <= 0 || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val name = f[1].trim()
    if (name.isEmpty()) return null
    return GeoPlace(
      id = id,
      name = name,
      latitude = lat,
      longitude = lon,
      featureCode = f[7],
      countryCode = f[8].trim().uppercase(),
      admin1 = f[10].trim(),
      population = f[14].toLongOrNull() ?: 0L,
      timezone = f[17].trim(),
      modified = if (f.size > 18) f[18].trim() else "",
      alternateNameCount = f[3].split(',').filter { it.isNotBlank() }.toSet().size,
    )
  }

  /** Una riga di `admin1CodesASCII.txt`: `IT.09<TAB>Lombardy<TAB>Lombardy<TAB>3174618`. */
  fun parseAdmin1(line: String): Admin1? {
    val f = line.split('\t')
    if (f.size < 4) return null
    val key = f[0].trim()
    val id = f[3].trim().toIntOrNull() ?: return null
    if (!key.matches(ADMIN1_KEY) || id <= 0 || f[1].isBlank()) return null
    return Admin1(key, f[1].trim(), id)
  }

  private val ADMIN1_KEY = Regex("[A-Z]{2}\\.[A-Za-z0-9]+")

  /**
   * Legge `alternateNamesV2.txt` e tiene, per ognuno degli id in [wanted], il miglior nome italiano. Colonne: 0 id del
   * nome, 1 id GeoNames, 2 lingua, 3 nome, 4 preferito, 5 abbreviato, 6 colloquiale, 7 storico. Le righe che non sono
   * `it` si scartano guardando solo i primi campi: il file ha piu' di un miliardo di byte.
   */
  fun readItalianNames(stream: InputStream, wanted: Set<Int>): Map<Int, ItalianName> {
    val best = HashMap<Int, ItalianName>()
    reader(stream).use { reader ->
      while (true) {
        val line = reader.readLine() ?: break
        val t1 = line.indexOf('\t')
        if (t1 <= 0) continue
        val t2 = line.indexOf('\t', t1 + 1)
        if (t2 < 0) continue
        val t3 = line.indexOf('\t', t2 + 1)
        if (t3 < 0) continue
        if (t3 - t2 - 1 != 2 || line[t2 + 1] != 'i' || line[t2 + 2] != 't') continue
        val geonameId = line.substring(t1 + 1, t2).toIntOrNull() ?: continue
        if (geonameId !in wanted) continue
        val f = line.split('\t')
        if (f.size < 4 || f[3].isBlank()) continue
        if (f.size > 7 && f[7] == "1") continue // storico
        val candidate = ItalianName(
          alternateNameId = f[0].toLongOrNull() ?: Long.MAX_VALUE,
          name = f[3].trim(),
          preferred = f.size > 4 && f[4] == "1",
          short = f.size > 5 && f[5] == "1",
          colloquial = f.size > 6 && f[6] == "1",
        )
        val current = best[geonameId]
        if (current == null || candidate.beats(current)) best[geonameId] = candidate
      }
    }
    return best
  }
}
