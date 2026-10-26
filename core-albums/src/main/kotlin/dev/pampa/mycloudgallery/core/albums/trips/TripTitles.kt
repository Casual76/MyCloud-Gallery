package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames

// I TITOLI DELLE PROPOSTE (docs/fase7-progetto.md D19): il luogo dalla Fase 6 (`PlaceNames.summary`) e il periodo in italiano.
// "Oslo, marzo 2026", "Toscana e altri 2 luoghi, agosto 2025"; un evento: "Sabato 12 aprile 2026" e, se c'e', il luogo. I nomi dei mesi e dei
// giorni sono scritti qui (nessuna dipendenza dalla lingua della JVM o del telefono: l'app e' solo in italiano). Il titolo diventa quello
// dell'album (`Albums.create`): al piu' [AlbumTitles.MAX_CHARS] caratteri e senza caratteri che il codec rifiuta.

internal class TripTitles(private val places: PlaceNames) {

  /** Il luogo a parole ("Oslo", "Firenze e Fiesole", "Toscana e altri 2 luoghi"), o vuoto se nessun luogo e' noto al dataset. */
  fun placeLabel(stats: PlaceStats): String =
    if (stats.distinct == 0) "" else places.summary(stats.top, stats.distinct).trim().filter { isPlain(it) }

  /** "Oslo, marzo 2026"; senza luogo "Viaggio, marzo 2026". */
  fun trip(range: LocalDayRange, stats: PlaceStats): String =
    join(placeLabel(stats).ifEmpty { GENERIC }, period(range), variableFirst = true).takeIf(::valid) ?: join(GENERIC, period(range), variableFirst = true)

  /** "Sabato 12 aprile 2026" (il primo giorno dell'evento) e, se c'e' un luogo lontano da casa, ", luogo". */
  fun event(range: LocalDayRange, stats: PlaceStats): String {
    val day = range.from
    val date = "${WEEKDAYS[day.dayOfWeek.value - 1]} ${day.dayOfMonth} ${MONTHS[day.monthValue - 1]} ${day.year}"
    val place = placeLabel(stats)
    return if (place.isEmpty()) date else join(place, date, variableFirst = false).takeIf(::valid) ?: date
  }

  /** Un titolo che l'album accetta e che il codec di chi legge non rifiuta: un nome di luogo strano non deve rendere la proposta inaccettabile. */
  private fun valid(title: String): Boolean = AlbumTitles.problem(title) == null

  private fun period(range: LocalDayRange): String {
    val a = range.from
    val b = range.to
    return when {
      a == b -> "${a.dayOfMonth} ${MONTHS[a.monthValue - 1]} ${a.year}"
      a.year == b.year && a.monthValue == b.monthValue -> "${MONTHS[a.monthValue - 1]} ${a.year}"
      a.year == b.year -> "${MONTHS[a.monthValue - 1]}–${MONTHS[b.monthValue - 1]} ${a.year}"
      else -> "${MONTHS[a.monthValue - 1]} ${a.year}–${MONTHS[b.monthValue - 1]} ${b.year}"
    }
  }

  /** "[luogo], [data]" (o il contrario) entro i 64 caratteri del titolo: se non ci sta si accorcia il luogo, mai la data. */
  private fun join(place: String, date: String, variableFirst: Boolean): String {
    val room = AlbumTitles.MAX_CHARS - date.length - SEPARATOR.length
    var shown = place
    if (shown.length > room) {
      shown = shown.substring(0, (room - 1).coerceAtLeast(0))
      if (shown.isNotEmpty() && Character.isHighSurrogate(shown.last())) shown = shown.dropLast(1)
      shown = shown.trimEnd() + "…"
    }
    return if (variableFirst) "$shown$SEPARATOR$date" else "$date$SEPARATOR$shown"
  }

  /** Niente caratteri di controllo ne' invisibili dentro un nome di luogo del dataset: il titolo deve passare `AlbumTitles.problem`. */
  private fun isPlain(c: Char): Boolean = !c.isISOControl() && c.code !in INVISIBLE

  private companion object {
    const val SEPARATOR = ", "
    const val GENERIC = "Viaggio"

    /** Il segno di direzione sinistra-destra e destra-sinistra e lo spazio di larghezza zero (BOM): invisibili, il codec li rifiuta. */
    val INVISIBLE = setOf(0x200E, 0x200F, 0xFEFF)
    val MONTHS = arrayOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
    val WEEKDAYS = arrayOf("Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica")
  }
}
