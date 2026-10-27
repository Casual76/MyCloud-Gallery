package dev.pampa.mycloudgallery.core.memories.testing

import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryExclusions
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryOrigin
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPriority
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Il generatore di riferimento (Fase 9, P0, D3, §5.2): i quattro tipi **per forza bruta**, una scansione di tutta la tabella per ogni chiave,
 * senza indici ne' scorciatoie. Lento di proposito: e' l'oracolo. Il generatore vero di P6 (plan/) da' gli stessi candidati, con le stesse chiavi
 * e le stesse celle, su 2.000 giorni casuali (§5.2). Le interpretazioni di P0 (gli eventi solo per anniversario, la riscoperta solo per i
 * viaggi, una sola persona per giro) sono scritte in `docs/decisioni.md`, "Fase 9 P0".
 */
class ReferencePlanner : MemoryPlanner {

  override fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    val out = ArrayList<MemoryCandidate>()
    out += onThisDay(input, today)
    out += tripsAndEvents(input, today)
    out += person(input, today)
    out += month(input, today)
    return out.sortedWith(compareBy<MemoryCandidate> { it.priority }.thenBy { it.key.text })
  }

  // --- Regole comuni -----------------------------------------------------------------------------------------------------------------

  /** Una chiave che non si propone: gia' sullo scaffale, scartata, vista negli ultimi 12 mesi (D9). */
  private fun quiet(id: MemoryId, input: PlanInput, today: LocalDate): Boolean {
    if (id in input.history.onShelf) return true
    if (id.value in input.choices.dismissed) return true
    val seen = input.choices.seen[id.value]?.let { MemoryDays.dateOf(it) } ?: return false
    return ChronoUnit.DAYS.between(seen, today) < MemoryRules.SEEN_QUIET_DAYS
  }

  /** Le celle che soddisfano [where] e nessuna esclusione di D5: una scansione intera. */
  private fun scan(table: CellTable, input: PlanInput, dateBound: Boolean, where: (LocalDate) -> Boolean): IntArray {
    val out = ArrayList<Int>()
    for (i in 0 until table.size) {
      val date = table.dateAt(i) ?: continue
      if (!where(date)) continue
      if (MemoryExclusions.of(table, i, dateBound, input.choices) != null) continue
      out += i
    }
    return out.toIntArray()
  }

  private fun daysSince(last: Int?, today: LocalDate): Long? = last?.let { MemoryDays.dateOf(it) }?.let { ChronoUnit.DAYS.between(it, today) }

  // --- "N anni fa oggi" (D3, E-MEM3) -------------------------------------------------------------------------------------------------

  /** I giorni d'origine di [year] per oggi: il 29 febbraio esce il 28 negli anni non bisestili (E-MEM3). */
  private fun originsIn(year: Int, today: LocalDate): List<LocalDate> {
    val leap = Year.isLeap(year.toLong())
    return when {
      today.monthValue == 2 && today.dayOfMonth == 29 -> if (leap) listOf(LocalDate.of(year, 2, 29)) else emptyList()
      today.monthValue == 2 && today.dayOfMonth == 28 && !Year.isLeap(today.year.toLong()) ->
        listOfNotNull(LocalDate.of(year, 2, 28), if (leap) LocalDate.of(year, 2, 29) else null)
      else -> listOf(LocalDate.of(year, today.monthValue, today.dayOfMonth))
    }
  }

  private fun onThisDay(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    val table = input.cells
    var firstYear = today.year
    for (i in 0 until table.size) table.dateAt(i)?.let { if (it.year < firstYear) firstYear = it.year }
    val qualifying = ArrayList<Pair<LocalDate, IntArray>>()
    for (year in firstYear until today.year) {
      for (origin in originsIn(year, today)) {
        val key = MemoryKey.OnThisDay(origin)
        if (quiet(MemoryIds.of(key), input, today)) continue
        val cells = scan(table, input, dateBound = true) { it == origin }
        if (cells.size >= MemoryRules.OTD_MIN_GOOD) qualifying += origin to cells
      }
    }
    if (qualifying.isEmpty()) return emptyList()
    qualifying.sortByDescending { it.first }
    val chosen = listOf(qualifying.first() to MemoryPriority.OTD_RECENT) +
      if (qualifying.size > 1) listOf(qualifying.last() to MemoryPriority.OTD_OLDEST) else emptyList()
    return chosen.map { (pair, priority) ->
      val (origin, cells) = pair
      MemoryCandidate(MemoryKey.OnThisDay(origin), priority, cells, yearsAgo = today.year - origin.year, days = DayRange.of(origin))
    }
  }

  // --- Viaggi ed eventi (D3, D12, E-MEM12, E-MEM13) ----------------------------------------------------------------------------------

  /** Gli anni dell'anniversario se l'inizio [from] cade a +-3 giorni da oggi in un anno passato (anche a cavallo d'anno); null altrimenti. */
  private fun anniversaryYears(from: LocalDate, today: LocalDate): Int? {
    for (k in intArrayOf(0, -1, 1)) {
      val year = today.year + k
      if (from.year >= year) continue
      val anniversary = from.withYear(year)
      if (abs(ChronoUnit.DAYS.between(anniversary, today)) <= MemoryRules.ANNIVERSARY_WINDOW_DAYS) return year - from.year
    }
    return null
  }

  private fun tripsAndEvents(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    val table = input.cells
    val out = ArrayList<MemoryCandidate>()
    val rediscoveries = ArrayList<MemoryCandidate>()
    for (trip in input.trips) {
      if (!trip.days.to.isBefore(today)) continue
      val key = trip.key()
      if (quiet(MemoryIds.of(key), input, today)) continue
      // E-MEM12: un viaggio che tocca un periodo "Mai" anche per un giorno non diventa un ricordo.
      if (input.choices.neverDays.any { never -> !never.last.isBefore(trip.days.from) && !never.first.isAfter(trip.days.to) }) continue
      val spec = MemoryRules.spec(key.kind)
      val cells = trip.members.map { table.indexOf(it) }.filter { it >= 0 }.distinct().sorted()
        .filter { MemoryExclusions.of(table, it, spec.dateBound, input.choices) == null }.toIntArray()
      if (cells.size < spec.minSize) continue
      val origin = trip.album?.let { MemoryOrigin.TripAlbum(it) }
      val years = anniversaryYears(trip.days.from, today)
      if (years != null) {
        out += MemoryCandidate(key, MemoryPriority.ANNIVERSARY, cells, years, trip.days, trip.label, trip.placeIds, origin = origin)
      } else if (trip.kind == TripKind.TRIP && ChronoUnit.DAYS.between(trip.days.to, today) > MemoryRules.REDISCOVERY_MIN_AGE_DAYS) {
        rediscoveries += MemoryCandidate(key, MemoryPriority.REDISCOVERY, cells, null, trip.days, trip.label, trip.placeIds, origin = origin, rediscovery = true)
      }
    }
    val since = daysSince(input.history.lastRediscovery, today)
    if (rediscoveries.isNotEmpty() && (since == null || since >= MemoryRules.REDISCOVERY_EVERY_DAYS)) {
      out += rediscoveries.sortedWith(
        compareByDescending<MemoryCandidate> { it.days.days }.thenByDescending { it.days.from }.thenBy { it.key.text },
      ).first()
    }
    return out
  }

  // --- Persone (D3, D11, D12, Q9 (a)) ------------------------------------------------------------------------------------------------

  private fun person(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    if (!input.peopleAvailable || !input.choices.peopleMemories) return emptyList()
    val since = daysSince(input.history.lastPublished[MemoryKind.PERSON], today)
    if (since != null && since < MemoryRules.PERSON_EVERY_DAYS) return emptyList()
    val table = input.cells
    val ordered = input.people.sortedWith(compareByDescending<dev.pampa.mycloudgallery.core.memories.contract.PersonDigest> { it.members.size }.thenBy { it.person })
    for (digest in ordered) {
      if (input.choices.people[digest.person] != null) continue
      val cells = digest.members.map { table.indexOf(it) }.filter { it >= 0 }.distinct().sorted()
        .filter { MemoryExclusions.of(table, it, dateBound = false, choices = input.choices) == null }
      val dates = cells.mapNotNull { table.dateAt(it) }
      if (cells.size < MemoryRules.PERSON_MIN_PHOTOS || dates.distinct().size < MemoryRules.PERSON_MIN_DAYS) continue
      val byYear = cells.groupBy { table.dateAt(it)!!.year }.filterKeys { it < today.year }
      for (year in byYear.keys.sortedDescending()) {
        val inYear = byYear.getValue(year)
        if (inYear.size < MemoryRules.PERSON_YEAR_MIN_PHOTOS) continue
        val key = MemoryKey.Person(digest.person, PersonWindow.Year(year))
        if (quiet(MemoryIds.of(key), input, today)) continue
        val days = DayRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
        return listOf(MemoryCandidate(key, MemoryPriority.PERSON, inYear.toIntArray(), null, days, person = digest.person))
      }
      val window = DayRange(today.minusDays(MemoryRules.PERSON_RECENT_DAYS), today.minusDays(1))
      val recent = cells.filter { table.dateAt(it)!! in window }
      if (recent.size >= MemoryRules.PERSON_YEAR_MIN_PHOTOS) {
        val key = MemoryKey.Person(digest.person, PersonWindow.Recent)
        if (!quiet(MemoryIds.of(key), input, today)) {
          return listOf(MemoryCandidate(key, MemoryPriority.PERSON, recent.toIntArray(), null, window, person = digest.person))
        }
      }
    }
    return emptyList()
  }

  // --- Il meglio del mese (D3) -------------------------------------------------------------------------------------------------------

  private fun month(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    if (today.dayOfMonth > MemoryRules.MONTH_LAST_DAY) return emptyList()
    val month = YearMonth.from(today).minusMonths(1)
    val key = MemoryKey.Month(month)
    if (quiet(MemoryIds.of(key), input, today)) return emptyList()
    val cells = scan(input.cells, input, dateBound = true) { YearMonth.from(it) == month }
    if (cells.size < MemoryRules.MONTH_MIN_CANDIDATES) return emptyList()
    return listOf(MemoryCandidate(key, MemoryPriority.MONTH, cells, null, DayRange(month.atDay(1), month.atEndOfMonth()), month = month))
  }
}
