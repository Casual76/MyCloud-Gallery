package dev.pampa.mycloudgallery.core.places

import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.dataset.PlacesData
import java.text.Normalizer
import java.util.Locale

/**
 * La verifica di accuratezza (docs/fase3-progetto.md §2.3, docs/luoghi.md §7.1): coordinate scritte a mano con i nomi
 * accettati. Una riga e' corretta se il nome del luogo trovato e' fra quelli accettati, la precisione e' una di quelle
 * attese e (se indicato) il fuso e' uno di quelli attesi.
 *
 * Colonne dei file `.tsv` (separate da tabulazione): id, latitudine, longitudine, nomi accettati (separati da `|`),
 * precisioni accettate (`CITY`, `NEAR`, `REGION_ONLY`, `COUNTRY_ONLY`, `NONE` = nessun risultato), fusi accettati
 * (`-` = non si controlla), nota.
 *
 * **Il criterio** (docs/luoghi.md §7.1):
 * - il confronto dei nomi **normalizza** maiuscole, accenti e apostrofi (' e tipografici): "Cukotka" = "Čukotka";
 * - una precisione con il suffisso `+` (per esempio `REGION_ONLY+`) vuol dire "questa o piu' fine": `CITY`, `NEAR` o
 *   `REGION_ONLY` nella regione attesa sono tutti corretti, e il nome puo' essere quello del luogo, della sua regione o del
 *   suo paese.
 */
internal object PlacesAccuracy {

  class Row(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val names: Set<String>,
    val precisions: Set<String>,
    /** Vero se una precisione aveva il suffisso `+`: nome anche per regione o paese, e precisioni piu' fini valide. */
    val orFiner: Boolean,
    val zones: Set<String>,
    val note: String,
  )

  class Outcome(val row: Row, val ok: Boolean, val got: String)

  class Report(val file: String, val outcomes: List<Outcome>) {
    val total: Int get() = outcomes.size
    val correct: Int get() = outcomes.count { it.ok }
    val percent: Double get() = 100.0 * correct / total

    fun failures(): String = outcomes.filter { !it.ok }.joinToString("\n") {
      "  ${it.row.id} (${ascii(it.row.note)}): atteso ${ascii(it.row.names.toString())} ${it.row.precisions}" +
        "${if (it.row.orFiner) "+" else ""} ${it.row.zones}, trovato ${ascii(it.got)}"
    }

    override fun toString(): String = "$file: $correct/$total corretti (${"%.1f".format(percent)}%)" +
      if (correct < total) "\n${failures()}" else ""
  }

  /** Minuscolo, senza accenti, con un solo tipo di apostrofo e di trattino: per confrontare i nomi. */
  fun normalize(name: String): String {
    val decomposed = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    return decomposed
      .replace(Regex("[‘’ʼ`´]"), "'")
      .replace(Regex("[‐‑‒–—]"), "-")
      .lowercase(Locale.ROOT)
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  /** Il testo con i caratteri non ASCII scritti come `\\uXXXX`: i rapporti dei test li leggono sempre uguali, qualunque codifica abbia la console. */
  fun ascii(text: String): String = buildString {
    for (c in text) if (c.code < 128) append(c) else append("\\u").append("%04x".format(c.code))
  }

  private val ORDER = listOf("COUNTRY_ONLY", "REGION_ONLY", "NEAR", "CITY")

  fun parse(rows: List<List<String>>): List<Row> = rows.map { c ->
    require(c.size >= 7) { "Riga di verifica con ${c.size} colonne: ${c.firstOrNull()}" }
    val rawPrecisions = c[4].split('|').map { it.trim() }
    val orFiner = rawPrecisions.any { it.endsWith("+") }
    val precisions = rawPrecisions.flatMap { token ->
      val base = token.removeSuffix("+")
      if (token.endsWith("+")) ORDER.drop(ORDER.indexOf(base)) else listOf(base)
    }.toSet()
    Row(
      id = c[0],
      latitude = c[1].toDouble(),
      longitude = c[2].toDouble(),
      names = c[3].split('|').map { normalize(it) }.toSet(),
      precisions = precisions,
      orFiner = orFiner,
      zones = c[5].split('|').map { it.trim() }.toSet(),
      note = c[6],
    )
  }

  fun evaluate(data: PlacesData, file: String, rows: List<Row>): Report = Report(
    file = file,
    outcomes = rows.map { row ->
      val point = GeoPoint(row.latitude, row.longitude)
      val match = data.nearest(point)
      if ("NONE" in row.precisions) {
        Outcome(row, ok = match == null, got = if (match == null) "nessun risultato" else "${match.precision} ${match.place.name}")
      } else if (match == null) {
        Outcome(row, ok = false, got = "nessun risultato")
      } else {
        val zone = data.zoneAt(point)?.id
        val place = match.place
        val nameOk = normalize(place.name) in row.names ||
          (row.orFiner && (place.admin1?.let { normalize(it) in row.names } == true || normalize(place.countryName) in row.names))
        val precisionOk = match.precision.name in row.precisions
        val zoneOk = "-" in row.zones || (zone != null && zone in row.zones)
        Outcome(
          row,
          ok = nameOk && precisionOk && zoneOk,
          got = "${match.precision} ${place.name} (${place.admin1 ?: "-"}, ${place.countryName}) fuso=$zone",
        )
      }
    },
  )
}
