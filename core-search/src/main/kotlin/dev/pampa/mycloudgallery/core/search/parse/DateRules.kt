package dev.pampa.mycloudgallery.core.search.parse

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

// Le regole di data "dirette" (P7, §2.5): giorni e periodi relativi, "fa", anni, decenni, date, intervalli. Ogni regola guarda i token
// dal punto [i] e restituisce la corrispondenza o null. "Oggi" e' sempre `ctx.today` (D39).

internal class DateRules(private val c: RuleContext) {
  private val today = c.today

  fun match(i: Int): RuleMatch? =
    relativeDay(i) ?: relativePeriod(i) ?: ago(i) ?: interval(i) ?: dateAt(i) ?: decade(i) ?: year(i)

  private fun day(len: Int, d: LocalDate, name: String) = RuleMatch(len, c.dayChip(d, "$name · ${ItalianCalendar.full(d)}"))

  private fun relativeDay(i: Int): RuleMatch? {
    when (c.w(i)) {
      "oggi" -> return day(1, today, "Oggi")
      "ieri" -> return day(1, today.minusDays(1), "Ieri")
      "avantieri" -> return day(1, today.minusDays(2), "L'altro ieri")
      "altro" -> if (c.w(i + 1) == "ieri") return day(2, today.minusDays(2), "L'altro ieri")
      "l" -> if (c.w(i + 1) == "altro" && c.w(i + 2) == "ieri") return day(3, today.minusDays(2), "L'altro ieri")
    }
    val dow = c.w(i)?.let { QueryVocabulary.WEEKDAYS[it] } ?: return null
    // "sabato 12 agosto": il giorno della settimana si ignora, vale la data.
    if (c.t.getOrNull(i + 1)?.let { it.kind == TokenKind.NUMBER || it.kind == TokenKind.NUMDATE } == true) {
      dateAt(i + 1)?.let { return RuleMatch(it.len + 1, it.chip, it.notes) }
    }
    val scorso = c.w(i + 1)?.let { it in QueryVocabulary.SCORSO } == true
    val date = lastWeekday(dow)
    val name = ItalianCalendar.capitalize(ItalianCalendar.dayName(dow)) + if (scorso) " scorso" else ""
    return day(if (scorso) 2 else 1, date, name)
  }

  /** L'ultimo [dow] strettamente prima di oggi. */
  private fun lastWeekday(dow: DayOfWeek): LocalDate = today.with(TemporalAdjusters.previous(dow))

  private fun period(len: Int, from: LocalDate, to: LocalDate, name: String) =
    RuleMatch(len, c.rangeChip(from, to, "$name · ${ItalianCalendar.span(from, to)}"))

  private fun relativePeriod(i: Int): RuleMatch? {
    val a = c.w(i) ?: return null
    // Forme: [questa|quest|questo] N, [scorsa|passata] N, N [scorsa|passata]; N = settimana, mese, anno, weekend, "fine settimana".
    var j = i
    var mode = 0 // 0 = nessuno, 1 = questo, -1 = scorso
    if (a in QueryVocabulary.QUESTO) {
      mode = 1; j++
    } else if (a in QueryVocabulary.SCORSO) {
      mode = -1; j++
    }
    val noun = c.w(j) ?: return null
    var end = j + 1
    val unit = when {
      noun == "settimana" || noun == "mese" || noun == "anno" || noun == "weekend" -> noun
      noun == "fine" && c.w(j + 1) == "settimana" -> {
        end = j + 2; "weekend"
      }
      else -> return null
    }
    if (mode == 0) {
      val s = c.w(end)
      if (s == null || s !in QueryVocabulary.SCORSO) return null
      mode = -1; end++
    }
    if (mode == 1 && unit == "weekend" && c.w(end)?.let { it in QueryVocabulary.SCORSO } == true) return null
    val len = end - i
    val last = mode == -1
    return when (unit) {
      "settimana" -> {
        val start = ItalianCalendar.weekStart(if (last) today.minusDays(7) else today)
        period(len, start, start.plusDays(6), if (last) "Settimana scorsa" else "Questa settimana")
      }
      "weekend" -> {
        val sat = ItalianCalendar.weekStart(if (last) today.minusDays(7) else today).plusDays(5)
        period(len, sat, sat.plusDays(1), if (last) "Weekend scorso" else "Questo weekend")
      }
      "mese" -> {
        val ym = YearMonth.from(today).let { if (last) it.minusMonths(1) else it }
        val name = if (last) "Mese scorso" else "Questo mese"
        RuleMatch(len, c.rangeChip(ym.atDay(1), ym.atEndOfMonth(), "$name · ${ItalianCalendar.MONTHS[ym.monthValue - 1]} ${ym.year}"))
      }
      else -> {
        val y = if (last) today.year - 1 else today.year
        RuleMatch(len, c.rangeChip(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "${if (last) "L'anno scorso" else "Quest'anno"} · $y"))
      }
    }
  }

  /** "3 giorni fa", "2 settimane fa", "un mese fa", "2 anni fa". */
  private fun ago(i: Int): RuleMatch? {
    val first0 = c.t.getOrNull(i) ?: return null
    val count = if (first0.kind == TokenKind.NUMBER && first0.num in 1..9999) first0.num else c.w(i)?.let { QueryVocabulary.NUMBER_WORDS[it] } ?: return null
    val unit = c.w(i + 1) ?: return null
    if (c.w(i + 2) != "fa") return null
    val one = count == 1
    val first = when (unit) {
      "giorno", "giorni" -> if (one) "Un giorno" else "$count giorni"
      "settimana", "settimane" -> if (one) "Una settimana" else "$count settimane"
      "mese", "mesi" -> if (one) "Un mese" else "$count mesi"
      "anno", "anni" -> if (one) "Un anno" else "$count anni"
      else -> return null
    }
    val name = "$first fa"
    return try {
      when (unit) {
        "giorno", "giorni" -> day(3, today.minusDays(count.toLong()), name)
        "settimana", "settimane" -> {
          val start = ItalianCalendar.weekStart(today.minusWeeks(count.toLong()))
          period(3, start, start.plusDays(6), name)
        }
        "mese", "mesi" -> {
          val ym = YearMonth.from(today).minusMonths(count.toLong())
          RuleMatch(3, c.rangeChip(ym.atDay(1), ym.atEndOfMonth(), "$name · ${ItalianCalendar.MONTHS[ym.monthValue - 1]} ${ym.year}"))
        }
        else -> {
          val y = today.year - count
          if (y < 1900) null else RuleMatch(3, c.rangeChip(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "$name · $y"))
        }
      }
    } catch (e: DateTimeException) {
      null
    }
  }

  /** Un anno ("2023", 1900-2100, anche futuro). */
  private fun year(i: Int): RuleMatch? {
    if (!c.isNumber(i, 4)) return null
    val y = c.n(i)
    if (y !in 1900..2100) return null
    return RuleMatch(1, c.rangeChip(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "$y"))
  }

  /** "anni 90", "anni '90", "anni novanta", "anni 2000", "anni duemila". */
  private fun decade(i: Int): RuleMatch? {
    if (c.w(i) != "anni") return null
    val word = c.w(i + 1)
    val tens = when {
      word != null -> QueryVocabulary.DECADE_WORDS[word] ?: return null
      c.n(i + 1) in 0..2100 && c.t.getOrNull(i + 1)?.kind == TokenKind.NUMBER -> {
        val v = c.n(i + 1)
        val digits = c.t[i + 1].word.length
        when {
          digits == 2 && v % 10 == 0 -> v
          digits == 4 && v % 10 == 0 && v in 1900..2090 -> v
          else -> return null
        }
      }
      else -> return null
    }
    val start = when {
      tens == 0 -> 2000
      tens >= 1900 -> tens
      tens <= today.year % 100 -> 2000 + tens
      else -> 1900 + tens
    }
    val label = if (word != null) "Anni ${word.replaceFirstChar { it.uppercaseChar() }}" else "Anni ${c.t[i + 1].word}"
    return RuleMatch(2, c.rangeChip(LocalDate.of(start, 1, 1), LocalDate.of(start + 9, 12, 31), "$label · $start–${start + 9}"))
  }

  /** Date: numeriche (12/8, 12/08/2023, 8/2023) e a parole ("12 agosto", "primo maggio", "12 agosto 2023"). */
  private fun dateAt(i: Int): RuleMatch? {
    val tk = c.t.getOrNull(i) ?: return null
    if (tk.kind == TokenKind.NUMDATE) {
      if (tk.year != -1 && tk.year !in 1900..2100) return null
      if (tk.num == 0) {
        val y = tk.year
        val label = "${ItalianCalendar.capitalize(ItalianCalendar.MONTHS[tk.month - 1])} $y"
        return RuleMatch(1, c.rangeChip(LocalDate.of(y, tk.month, 1), ItalianCalendar.monthEnd(y, tk.month), label))
      }
      return c.dateMatch(1, tk.num, tk.month, tk.year.takeIf { it != -1 }, tk.num.toString())
    }
    val day = if (c.w(i) == "primo") 1 else if (c.isNumber(i, 1) || c.isNumber(i, 2)) c.n(i) else return null
    val month = c.w(i + 1)?.let { QueryVocabulary.MONTH_WORDS[it] } ?: return null
    val ya = c.yearAfter(i + 2)
    return c.dateMatch(2 + (ya?.second ?: 0), day, month, ya?.first, day.toString())
  }

  /** "dal 3 al 10 agosto 2023", "dal 28 luglio al 3 agosto", "tra marzo e maggio", "dal 2019 al 2021". */
  private fun interval(i: Int): RuleMatch? {
    val head = c.w(i) ?: return null
    if (head != "dal" && head != "da" && head != "tra" && head != "fra") return null
    val link = if (head == "tra" || head == "fra") "e" else null
    var j = i + 1
    if (c.w(j) == "il") j++
    val monthA = c.w(j)?.let { QueryVocabulary.MONTH_WORDS[it] }
    return when {
      monthA != null -> monthInterval(i, j, monthA, link)
      c.isNumber(j, 4) -> yearInterval(i, j, link)
      c.isNumber(j, 1) || c.isNumber(j, 2) -> dayInterval(i, j, link)
      else -> null
    }
  }

  private fun isLink(word: String?, link: String?): Boolean = word != null && (word == link || link == null && (word == "al" || word == "a"))

  private fun yearInterval(i: Int, j: Int, link: String?): RuleMatch? {
    val y1 = c.n(j)
    var k = j + 1
    if (!isLink(c.w(k), link)) return null
    k++
    if (c.w(k) == "il") k++
    if (!c.isNumber(k, 4)) return null
    val y2 = c.n(k)
    if (y1 !in 1900..2100 || y2 !in y1..2100) return null
    return RuleMatch(k + 1 - i, c.rangeChip(LocalDate.of(y1, 1, 1), LocalDate.of(y2, 12, 31), "$y1–$y2"))
  }

  private fun monthInterval(i: Int, j: Int, m1: Int, link: String?): RuleMatch? {
    var k = j + 1
    if (!isLink(c.w(k), link)) return null
    k++
    val m2 = c.w(k)?.let { QueryVocabulary.MONTH_WORDS[it] } ?: return null
    k++
    val ya = c.yearAfter(k)
    val n1 = ItalianCalendar.MONTHS[m1 - 1]
    val n2 = ItalianCalendar.MONTHS[m2 - 1]
    val len = k + (ya?.second ?: 0) - i
    if (ya == null) {
      return RuleMatch(len, c.everyChip(m1, 1, m2, c.monthEnd(m2), "${ItalianCalendar.capitalize(n1)}–$n2 (tutti gli anni)"))
    }
    val y = ya.first
    val startYear = if (m1 > m2) y - 1 else y
    val from = LocalDate.of(startYear, m1, 1)
    val to = LocalDate.of(y, m2, 1).with(TemporalAdjusters.lastDayOfMonth())
    val label = if (startYear == y) "${ItalianCalendar.capitalize(n1)}–$n2 $y" else "${ItalianCalendar.capitalize(n1)} $startYear–$n2 $y"
    return RuleMatch(len, c.rangeChip(from, to, label))
  }

  private fun dayInterval(i: Int, j: Int, link: String?): RuleMatch? {
    val d1 = c.n(j)
    var k = j + 1
    var m1 = c.w(k)?.let { QueryVocabulary.MONTH_WORDS[it] }
    if (m1 != null) k++
    if (!isLink(c.w(k), link)) return null
    k++
    if (c.w(k) == "il") k++
    if (!(c.isNumber(k, 1) || c.isNumber(k, 2))) return null
    val d2 = c.n(k)
    val m2 = c.w(k + 1)?.let { QueryVocabulary.MONTH_WORDS[it] } ?: return null
    if (m1 == null) m1 = m2
    k += 2
    val ya = c.yearAfter(k)
    val len = k + (ya?.second ?: 0) - i
    if (d1 !in 1..31 || d2 !in 1..31 || m1 == m2 && d1 > d2) return null
    val n1 = ItalianCalendar.MONTHS[m1 - 1]
    val n2 = ItalianCalendar.MONTHS[m2 - 1]
    val head = if (m1 == m2) "Dal $d1 al $d2 $n2" else "Dal $d1 $n1 al $d2 $n2"
    if (ya == null) {
      return RuleMatch(len, c.everyChip(m1, minOf(d1, c.monthEnd(m1)), m2, minOf(d2, c.monthEnd(m2)), "$head (tutti gli anni)"))
    }
    val y = ya.first
    val startYear = if (m1 > m2) y - 1 else y
    val from = LocalDate.of(startYear, m1, minOf(d1, YearMonth.of(startYear, m1).lengthOfMonth()))
    val to = LocalDate.of(y, m2, minOf(d2, YearMonth.of(y, m2).lengthOfMonth()))
    return RuleMatch(len, c.rangeChip(from, to, "$head $y"))
  }
}
