package dev.pampa.mycloudgallery.core.search.parse

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

// Il calendario italiano dell'analizzatore (P7, §2.5): nomi dei mesi, etichette dei chip, Pasqua. Puro e senza orologio (D39).

internal object ItalianCalendar {
  val MONTHS = listOf(
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
  )

  /** Il nome del giorno della settimana, minuscolo. */
  fun dayName(d: DayOfWeek): String = when (d) {
    DayOfWeek.MONDAY -> "lunedì"
    DayOfWeek.TUESDAY -> "martedì"
    DayOfWeek.WEDNESDAY -> "mercoledì"
    DayOfWeek.THURSDAY -> "giovedì"
    DayOfWeek.FRIDAY -> "venerdì"
    DayOfWeek.SATURDAY -> "sabato"
    DayOfWeek.SUNDAY -> "domenica"
  }

  /** L'anno usato per i periodi "tutti gli anni": bisestile, cosi' il 29 febbraio e' rappresentabile. */
  const val ANY_YEAR = 2000

  fun capitalize(s: String): String = s.replaceFirstChar { it.uppercaseChar() }

  /** "20 ottobre 2026". */
  fun full(d: LocalDate): String = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"

  /** "12 agosto" (senza anno). */
  fun dayMonth(month: Int, day: Int): String = "$day ${MONTHS[month - 1]}"

  /** "20 ottobre 2026", "19–25 ottobre 2026", "28 settembre–4 ottobre 2026", "29 dicembre 2025–4 gennaio 2026". */
  fun span(from: LocalDate, to: LocalDate): String = when {
    from == to -> full(from)
    from.year == to.year && from.month == to.month -> "${from.dayOfMonth}–${to.dayOfMonth} ${MONTHS[to.monthValue - 1]} ${to.year}"
    from.year == to.year -> "${from.dayOfMonth} ${MONTHS[from.monthValue - 1]}–${to.dayOfMonth} ${MONTHS[to.monthValue - 1]} ${to.year}"
    else -> "${full(from)}–${full(to)}"
  }

  fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

  fun monthStart(year: Int, month: Int): LocalDate = LocalDate.of(year, month, 1)

  fun monthEnd(year: Int, month: Int): LocalDate = YearMonth.of(year, month).atEndOfMonth()

  /** Il 29 febbraio piu' recente non dopo [today] (per "31 febbraio" senza anno). */
  fun lastLeapDay(today: LocalDate): LocalDate {
    var y = today.year
    while (true) {
      if (java.time.Year.isLeap(y.toLong()) && !LocalDate.of(y, 2, 29).isAfter(today)) return LocalDate.of(y, 2, 29)
      y--
    }
  }

  /**
   * La domenica di Pasqua (calendario gregoriano, algoritmo "anonimo" di Meeus/Jones/Butcher). I test la confrontano con una
   * tabella di date scritta a mano (`resources/parse/pasqua-2015-2035.txt`), non con un secondo calcolo.
   */
  fun easter(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(year, month, day)
  }
}
