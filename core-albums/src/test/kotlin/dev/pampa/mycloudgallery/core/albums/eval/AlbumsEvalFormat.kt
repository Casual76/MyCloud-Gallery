package dev.pampa.mycloudgallery.core.albums.eval

import java.time.LocalDate
import java.time.format.DateTimeParseException

// IL FORMATO DEL FILE DELLE ETICHETTE DEI VIAGGI (`viaggi.mcgalb`, in `noBackupFilesDir/eval/albums/`). Testo UTF-8, una riga per voce, campi
// separati da tabulazione; la prima riga e' l'intestazione [AlbumsLabelFormat.MAGIC]. Un file rovinato da' un errore con il **numero** della riga,
// mai il contenuto (le righe sono dati privati). Stesso file in core-albums (test) e in :app (debug).
//
//   MCGALB01
//   V <tab> v01 <tab> 2025-03-12 <tab> 2025-03-19 <tab> titolo come lo scrive l'utente (vuoto: solo nell'elenco)
//   Y <tab> v01 <tab> <id del media>      una foto del viaggio
//   N <tab> v01 <tab> <id del media>      una foto che il motore ha messo e non e' del viaggio
//   C                                     l'elenco dei viaggi e' chiuso (confrontato con le proposte)

object AlbumsLabelFormat {
  const val MAGIC: String = "MCGALB01"
  const val FILE_NAME: String = "viaggi.mcgalb"

  private val MEDIA = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

  /** Un id di media e' un UUID minuscolo (`MediaId`). */
  fun isMedia(id: String): Boolean = MEDIA.matches(id)

  fun serialize(labels: AlbumsLabelSet): String {
    val out = StringBuilder(MAGIC).append('\n')
    for (t in labels.trips) {
      out.append("V\t").append(t.id).append('\t').append(t.from).append('\t').append(t.to).append('\t').append(escape(t.title.orEmpty())).append('\n')
    }
    for (t in labels.trips) for (media in labels.inTripOf(t.id).sorted()) out.append("Y\t").append(t.id).append('\t').append(media).append('\n')
    for (t in labels.trips) for (media in labels.notInTripOf(t.id).sorted()) out.append("N\t").append(t.id).append('\t').append(media).append('\n')
    if (labels.listClosed) out.append("C\n")
    return out.toString()
  }

  /** Rilegge un file; un file rovinato da' un errore con il **numero** della riga, mai il contenuto. */
  fun parse(text: String): AlbumsLabelSet {
    val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    require(lines.firstOrNull() == MAGIC) { "Non e' un file di etichette dei viaggi (intestazione)." }
    val trips = ArrayList<EvalTrip>()
    val inTrip = LinkedHashMap<String, MutableSet<String>>()
    val notInTrip = LinkedHashMap<String, MutableSet<String>>()
    var closed = false
    lines.drop(1).forEachIndexed { index, line ->
      val n = index + 2
      val c = line.split('\t')
      try {
        when (c[0]) {
          "V" -> {
            require(c.size == 5)
            trips += EvalTrip(c[1], LocalDate.parse(c[2]), LocalDate.parse(c[3]), unescape(c[4]).ifEmpty { null })
          }
          "Y" -> {
            require(c.size == 3 && isMedia(c[2]))
            inTrip.getOrPut(c[1]) { LinkedHashSet() } += c[2]
          }
          "N" -> {
            require(c.size == 3 && isMedia(c[2]))
            notInTrip.getOrPut(c[1]) { LinkedHashSet() } += c[2]
          }
          "C" -> {
            require(c.size == 1)
            closed = true
          }
          else -> throw IllegalArgumentException()
        }
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Riga $n del file di etichette dei viaggi non valida.")
      } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Riga $n del file di etichette dei viaggi non valida.")
      }
    }
    return try {
      AlbumsLabelSet(trips, inTrip, notInTrip, closed)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("Il file di etichette dei viaggi non e' coerente.")
    }
  }

  private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

  private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        i++
        out.append(
          when (s[i]) {
            'n' -> '\n'
            't' -> '\t'
            else -> s[i]
          },
        )
      } else {
        out.append(c)
      }
      i++
    }
    return out.toString()
  }
}
