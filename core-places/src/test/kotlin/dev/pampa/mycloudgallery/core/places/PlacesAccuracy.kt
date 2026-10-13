package dev.pampa.mycloudgallery.core.places

import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.dataset.PlacesData

/**
 * La verifica di accuratezza (docs/fase3-progetto.md §2.3): coordinate scritte a mano con i nomi accettati. Una riga e'
 * corretta se il nome del luogo trovato e' fra quelli accettati, la precisione e' una di quelle attese e (se indicato)
 * il fuso e' uno di quelli attesi.
 *
 * Colonne dei file `.tsv` (separate da tabulazione): id, latitudine, longitudine, nomi accettati (separati da `|`),
 * precisioni accettate (`CITY`, `NEAR`, `REGION_ONLY`, `COUNTRY_ONLY`, `NONE` = nessun risultato), fusi accettati
 * (`-` = non si controlla), nota.
 */
internal object PlacesAccuracy {

  class Row(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val names: Set<String>,
    val precisions: Set<String>,
    val zones: Set<String>,
    val note: String,
  )

  class Outcome(val row: Row, val ok: Boolean, val got: String)

  class Report(val file: String, val outcomes: List<Outcome>) {
    val total: Int get() = outcomes.size
    val correct: Int get() = outcomes.count { it.ok }
    val percent: Double get() = 100.0 * correct / total

    fun failures(): String = outcomes.filter { !it.ok }.joinToString("\n") {
      "  ${it.row.id} (${it.row.note}): atteso ${it.row.names} ${it.row.precisions} ${it.row.zones}, trovato ${it.got}"
    }

    override fun toString(): String = "$file: $correct/$total corretti (${"%.1f".format(percent)}%)" +
      if (correct < total) "\n${failures()}" else ""
  }

  fun parse(rows: List<List<String>>): List<Row> = rows.map { c ->
    require(c.size >= 7) { "Riga di verifica con ${c.size} colonne: ${c.firstOrNull()}" }
    Row(
      id = c[0],
      latitude = c[1].toDouble(),
      longitude = c[2].toDouble(),
      names = c[3].split('|').map { it.trim().lowercase() }.toSet(),
      precisions = c[4].split('|').map { it.trim() }.toSet(),
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
        val nameOk = match.place.name.lowercase() in row.names
        val precisionOk = match.precision.name in row.precisions
        val zoneOk = "-" in row.zones || (zone != null && zone in row.zones)
        Outcome(row, ok = nameOk && precisionOk && zoneOk, got = "${match.precision} ${match.place.name} fuso=$zone")
      }
    },
  )
}
