package dev.pampa.mycloudgallery.feature.albums.editor

import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

// LE DATE SCRITTE A MANO nell'editor delle regole (Fase 7, P10; D13, D36): "gg/mm/aaaa", giorni locali, nessuna ora. Funzioni pure, provate senza
// Compose. Un giorno che non esiste ("31/02/2026") o scritto male non vale: il bottone resta spento e il foglio lo dice.

object DateInput {
  private val Format: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ITALIAN).withResolverStyle(ResolverStyle.STRICT)
  private val Show: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ITALIAN)

  /** Il giorno scritto in [text] ("14/3/2026" o "14/03/2026", spazi ai bordi tolti), o null se non e' un giorno. */
  fun parse(text: String): LocalDate? = try {
    LocalDate.parse(text.trim(), Format)
  } catch (_: DateTimeParseException) {
    null
  }

  /** Il giorno come si scrive nel campo: "14/03/2026". */
  fun show(day: LocalDate): String = day.format(Show)

  /** Il periodo da due testi, o null se uno dei due non e' un giorno o il primo viene dopo il secondo. */
  fun range(from: String, to: String): LocalDayRange? {
    val a = parse(from) ?: return null
    val b = parse(to) ?: return null
    return if (b.isBefore(a)) null else LocalDayRange(a, b)
  }
}
