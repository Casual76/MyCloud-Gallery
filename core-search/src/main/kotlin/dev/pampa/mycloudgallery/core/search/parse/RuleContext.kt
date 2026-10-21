package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import java.time.LocalDate
import java.time.YearMonth

/** Una regola riuscita: consuma [len] token da quello di partenza e produce [chip]; [notes] sono le correzioni dichiarate (cercato, scritto). */
internal class RuleMatch(val len: Int, val chip: SearchChip, val notes: List<Pair<String, String>> = emptyList())

/** Gli strumenti comuni alle regole di data: i token, "oggi" (D39) e i costruttori dei chip. */
internal class RuleContext(val t: List<Token>, val today: LocalDate) {
  /** La parola del token [i], o null se non e' una parola. */
  fun w(i: Int): String? = t.getOrNull(i)?.takeIf { it.isWord }?.word

  /** Il valore del numero (1-4 cifre) al token [i], o -1. */
  fun n(i: Int): Int = t.getOrNull(i)?.takeIf { it.kind == TokenKind.NUMBER }?.num ?: -1

  fun isNumber(i: Int, digits: Int): Boolean =
    t.getOrNull(i)?.let { it.kind == TokenKind.NUMBER && it.word.length == digits } ?: false

  fun dayChip(day: LocalDate, label: String) = SearchChip.DateRange(day, day, everyYear = false, label = label)

  fun rangeChip(from: LocalDate, to: LocalDate, label: String) = SearchChip.DateRange(from, to, everyYear = false, label = label)

  /** Un periodo "tutti gli anni": conta solo mese e giorno (anno 2000, bisestile). */
  fun everyChip(m1: Int, d1: Int, m2: Int, d2: Int, label: String) = SearchChip.DateRange(
    LocalDate.of(ItalianCalendar.ANY_YEAR, m1, d1), LocalDate.of(ItalianCalendar.ANY_YEAR, m2, d2), everyYear = true, label = label,
  )

  fun monthEnd(month: Int): Int = YearMonth.of(ItalianCalendar.ANY_YEAR, month).lengthOfMonth()

  /**
   * Un anno detto dopo il token [j]: quattro cifre (1900-2100), "quest'anno", "l'anno scorso"; le due cifre solo se [twoDigits]
   * (subito dopo un mese: "ago 23"). Restituisce (anno, token consumati).
   */
  fun yearRef(j: Int, twoDigits: Boolean = false): Pair<Int, Int>? {
    val v = n(j)
    if (isNumber(j, 4) && v in 1900..2100) return v to 1
    if (twoDigits && isNumber(j, 2)) return QueryTokenizer.expandYear(v) to 1
    val a = w(j)
    val b = w(j + 1)
    if (a != null && a in QueryVocabulary.QUESTO && b == "anno") return today.year to 2
    if (a == "anno" && b != null && b in QueryVocabulary.SCORSO) return today.year - 1 to 2
    if (a == "l" && b == "anno" && w(j + 2) in QueryVocabulary.SCORSO) return today.year - 1 to 3
    if (a != null && a in QueryVocabulary.SCORSO && b == "anno") return today.year - 1 to 2
    return null
  }

  /** Come [yearRef], ma accetta un "di/del/dell/nel/in" prima dell'anno (anche tre cifre: ["del"] 2023). Restituisce (anno, consumati dal [j]). */
  fun yearAfter(j: Int, twoDigits: Boolean = false): Pair<Int, Int>? {
    yearRef(j, twoDigits)?.let { return it }
    val c = w(j)
    if (c != null && c in YEAR_CONNECTORS) yearRef(j + 1)?.let { return it.first to it.second + 1 }
    return null
  }

  /** Il giorno [day] del [month] di [year] (se l'anno c'e'), corretto se impossibile e dichiarato; null se non e' una data. */
  fun dateMatch(len: Int, day: Int, month: Int, year: Int?, typedDay: String): RuleMatch? {
    if (day !in 1..31) return null
    val name = ItalianCalendar.MONTHS[month - 1]
    val typed = if (year != null) "$typedDay $name $year" else "$typedDay $name"
    if (year != null) {
      val maxDay = YearMonth.of(year, month).lengthOfMonth()
      val d = minOf(day, maxDay)
      val date = LocalDate.of(year, month, d)
      val notes = if (d != day) listOf(ItalianCalendar.full(date) to typed) else emptyList()
      return RuleMatch(len, dayChip(date, ItalianCalendar.full(date)), notes)
    }
    if (day <= monthEnd(month)) {
      return RuleMatch(len, everyChip(month, day, month, day, "${ItalianCalendar.dayMonth(month, day)} (tutti gli anni)"))
    }
    if (month == 2) {
      val date = ItalianCalendar.lastLeapDay(today)
      return RuleMatch(len, dayChip(date, ItalianCalendar.full(date)), listOf(ItalianCalendar.full(date) to typed))
    }
    val d = monthEnd(month)
    val label = ItalianCalendar.dayMonth(month, d)
    return RuleMatch(len, everyChip(month, d, month, d, "$label (tutti gli anni)"), listOf(label to typed))
  }

  companion object {
    val YEAR_CONNECTORS: Set<String> = setOf("di", "del", "dell", "nel", "in")
  }
}
