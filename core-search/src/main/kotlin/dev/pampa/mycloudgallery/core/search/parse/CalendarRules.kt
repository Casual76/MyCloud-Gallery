package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import java.time.LocalDate
import java.time.YearMonth

// Le regole di calendario (P7, §2.5): mesi, stagioni meteorologiche e feste. Senza anno valgono "tutti gli anni"; con l'anno (o
// "scorso", "quest'anno") un periodo preciso. Pasqua e Pasquetta cambiano ogni anno: senza anno sono tutte (un `SearchChip.DateSet`
// con il giorno di ogni anno, aggiunto da P6), con "scorsa" l'ultima gia' passata.

internal class CalendarRules(private val c: RuleContext) {
  private val today = c.today

  fun match(i: Int): RuleMatch? = holiday(i) ?: season(i) ?: month(i)

  private fun cap(s: String) = ItalianCalendar.capitalize(s)

  private fun isScorso(i: Int): Boolean = c.w(i)?.let { it in QueryVocabulary.SCORSO } == true

  // ---- Mesi ----

  private fun month(i: Int): RuleMatch? {
    val m = c.w(i)?.let { QueryVocabulary.MONTH_WORDS[it] } ?: return null
    val name = ItalianCalendar.MONTHS[m - 1]
    if (isScorso(i + 1)) {
      val y = if (m < today.monthValue) today.year else today.year - 1
      return RuleMatch(2, c.rangeChip(LocalDate.of(y, m, 1), ItalianCalendar.monthEnd(y, m), "${cap(name)} $y"))
    }
    val ya = c.yearAfter(i + 1, twoDigits = true)
    if (ya != null) {
      val y = ya.first
      return RuleMatch(1 + ya.second, c.rangeChip(LocalDate.of(y, m, 1), ItalianCalendar.monthEnd(y, m), "${cap(name)} $y"))
    }
    return RuleMatch(1, c.everyChip(m, 1, m, c.monthEnd(m), "${cap(name)} (tutti gli anni)"))
  }

  // ---- Stagioni ----

  private fun seasonRange(startMonth: Int, y: Int): Pair<LocalDate, LocalDate> {
    val from = LocalDate.of(y, startMonth, 1)
    val to = YearMonth.of(y, startMonth).plusMonths(2).atEndOfMonth()
    return from to to
  }

  private fun seasonLabel(startMonth: Int, y: Int): String =
    if (startMonth == 12) "Inverno $y–${((y + 1) % 100).toString().padStart(2, '0')}"
    else "${cap(ItalianCalendar.MONTHS[startMonth - 1])}–${ItalianCalendar.MONTHS[startMonth + 1]} $y"

  private fun season(i: Int): RuleMatch? {
    val first = c.w(i) ?: return null
    var j = i
    var mode = 0 // 1 = questa, -1 = scorsa
    if (first in QueryVocabulary.QUESTO) {
      mode = 1; j++
    } else if (first in QueryVocabulary.SCORSO) {
      mode = -1; j++
    }
    val sm = c.w(j)?.let { QueryVocabulary.SEASONS[it] } ?: return null
    var end = j + 1
    var year: Int? = null
    if (mode == 0) {
      if (isScorso(end)) {
        mode = -1; end++
      } else {
        c.yearAfter(end, twoDigits = true)?.let { year = it.first; end += it.second }
      }
    }
    val len = end - i
    if (mode == 0 && year == null) {
      val label = if (sm == 12) "Dicembre–febbraio (tutti gli anni)" else
        "${cap(ItalianCalendar.MONTHS[sm - 1])}–${ItalianCalendar.MONTHS[sm + 1]} (tutti gli anni)"
      val toMonth = if (sm == 12) 2 else sm + 2
      return RuleMatch(len, c.everyChip(sm, 1, toMonth, c.monthEnd(toMonth), label))
    }
    val y = year ?: if (mode == 1) latestStarted(sm) else latestEnded(sm)
    val (from, to) = seasonRange(sm, y)
    return RuleMatch(len, c.rangeChip(from, to, seasonLabel(sm, y)))
  }

  /** L'ultimo anno in cui la stagione e' gia' cominciata. */
  private fun latestStarted(sm: Int): Int {
    var y = today.year
    while (LocalDate.of(y, sm, 1).isAfter(today)) y--
    return y
  }

  /** L'ultimo anno in cui la stagione e' gia' finita (prima di oggi). */
  private fun latestEnded(sm: Int): Int {
    var y = today.year
    while (!seasonRange(sm, y).second.isBefore(today)) y--
    return y
  }

  // ---- Feste ----

  private class Holiday(val name: String, val m1: Int, val d1: Int, val m2: Int, val d2: Int, val shiftYear: Boolean = false)

  private val natale = Holiday("Natale", 12, 24, 12, 26)
  private val vigiliaNatale = Holiday("Vigilia di Natale", 12, 24, 12, 24)
  private val vigiliaCapodanno = Holiday("Vigilia di Capodanno", 12, 31, 12, 31)
  private val santoStefano = Holiday("Santo Stefano", 12, 26, 12, 26)
  private val capodanno = Holiday("Capodanno", 12, 31, 1, 1, shiftYear = true)
  private val epifania = Holiday("Epifania", 1, 6, 1, 6)
  private val ferragosto = Holiday("Ferragosto", 8, 15, 8, 15)
  private val sanValentino = Holiday("San Valentino", 2, 14, 2, 14)

  private fun detail(from: LocalDate, to: LocalDate): String = when {
    from.year != to.year -> ItalianCalendar.span(from, to)
    from == to -> "${from.dayOfMonth} ${ItalianCalendar.MONTHS[from.monthValue - 1]}"
    from.month == to.month -> "${from.dayOfMonth}–${to.dayOfMonth} ${ItalianCalendar.MONTHS[to.monthValue - 1]}"
    else -> "${ItalianCalendar.dayMonth(from.monthValue, from.dayOfMonth)}–${ItalianCalendar.dayMonth(to.monthValue, to.dayOfMonth)}"
  }

  /** L'istanza della festa fissa nell'anno [y] ("Capodanno 2024" = 31/12/2023-1/1/2024). */
  private fun instance(h: Holiday, y: Int): Pair<LocalDate, LocalDate> =
    if (h.shiftYear) LocalDate.of(y - 1, h.m1, h.d1) to LocalDate.of(y, h.m2, h.d2)
    else LocalDate.of(y, h.m1, h.d1) to LocalDate.of(y, h.m2, h.d2)

  private fun holiday(i: Int): RuleMatch? {
    val a = c.w(i) ?: return null
    return when (a) {
      "natale" -> fixed(natale, i, 1)
      "capodanno" -> fixed(capodanno, i, 1)
      "epifania" -> fixed(epifania, i, 1)
      "ferragosto" -> fixed(ferragosto, i, 1)
      "pasqua" -> easter(i, 1, 0)
      "pasquetta" -> easter(i, 1, 1)
      "vigilia" -> {
        val b = c.w(i + 1)
        val of = c.w(i + 2)
        when {
          (b == "di" || b == "del") && of == "capodanno" -> fixed(vigiliaCapodanno, i, 3)
          (b == "di" || b == "del") && of == "natale" -> fixed(vigiliaNatale, i, 3)
          else -> fixed(vigiliaNatale, i, 1)
        }
      }
      "santo" -> if (c.w(i + 1) == "stefano") fixed(santoStefano, i, 2) else null
      "san" -> if (c.w(i + 1) == "valentino") fixed(sanValentino, i, 2) else null
      "s" -> when (c.w(i + 1)) {
        "stefano" -> fixed(santoStefano, i, 2)
        "valentino" -> fixed(sanValentino, i, 2)
        else -> null
      }
      else -> null
    }
  }

  /** Dopo la festa: "scorso", un anno, o niente (tutti gli anni). Restituisce (anno, "scorso"?, token). */
  private fun suffix(j: Int): Triple<Int?, Boolean, Int> {
    if (isScorso(j)) return Triple(null, true, 1)
    c.yearAfter(j)?.let { return Triple(it.first, false, it.second) }
    return Triple(null, false, 0)
  }

  private fun fixed(h: Holiday, i: Int, base: Int): RuleMatch {
    val (year, last, extra) = suffix(i + base)
    val len = base + extra
    if (year == null && !last) {
      val label = "${h.name} (${detail(LocalDate.of(2000, h.m1, h.d1), LocalDate.of(2000, h.m2, h.d2))}, tutti gli anni)"
      return RuleMatch(len, c.everyChip(h.m1, h.d1, h.m2, h.d2, label))
    }
    var y = year ?: today.year
    if (last) while (!instance(h, y).second.isBefore(today)) y--
    val (from, to) = instance(h, y)
    return RuleMatch(len, c.rangeChip(from, to, "${h.name} $y (${detail(from, to)})"))
  }

  private fun easter(i: Int, base: Int, shift: Long): RuleMatch {
    val name = if (shift == 0L) "Pasqua" else "Pasquetta"
    val (year, last, extra) = suffix(i + base)
    var y = year ?: today.year
    fun day(yy: Int) = ItalianCalendar.easter(yy).plusDays(shift)
    if (year == null && !last) {
      // Senza anno: tutte le Pasque (P6, richiesta di P7), dal primo anno delle feste mobili fino a quella di quest'anno.
      val days = (SearchChip.DateSet.FIRST_YEAR..today.year).mapTo(LinkedHashSet()) { day(it) }
      return RuleMatch(base + extra, SearchChip.DateSet(days, "$name (tutti gli anni)"))
    }
    if (year == null) while (!day(y).isBefore(today)) y--
    val d = day(y)
    return RuleMatch(base + extra, c.dayChip(d, "$name $y (${d.dayOfMonth} ${ItalianCalendar.MONTHS[d.monthValue - 1]})"))
  }
}
