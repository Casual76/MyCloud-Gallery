package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit

// IL PERIODO DI UN ALBUM SMART DAL TITOLO (D13, §2.2 passi 1 e 2). I chip di data dell'analizzatore diventano un [SmartPeriod]; un periodo lungo
// con un luogo si raffina sulle foto (le serie di giorni, E-AL1, E-AL12).

internal object PeriodChips {

  /**
   * Il periodo dei chip di data di un titolo, o null se non ce ne sono. Un chip solo: il suo periodo ("Natale 2025" e' il periodo natalizio, da
   * Natale all'Epifania: 24/12/2025-6/1/2026; l'analizzatore da' i soli tre giorni di festa). Piu' chip di date precise: l'unione (anche a
   * cavallo d'anno, E-AL1). Altrimenti vale il primo (un "ogni anno" non si unisce ad altro).
   */
  fun period(chips: List<SearchChip>): SmartPeriod? {
    val dates = chips.filter { it is SearchChip.DateRange || it is SearchChip.DateSet }
    if (dates.isEmpty()) return null
    val plain = dates.filterIsInstance<SearchChip.DateRange>().filter { !it.everyYear }
    if (plain.size == dates.size && plain.size > 1) return SmartPeriod.Ranges(merge(plain.map { christmasWindow(it) ?: LocalDayRange(it.from, it.to) }))
    return when (val first = dates.first()) {
      is SearchChip.DateSet -> SmartPeriod.Days(first.days)
      is SearchChip.DateRange ->
        if (first.everyYear) {
          if (isChristmas(first)) SmartPeriod.EveryYear(MonthDay.of(12, 24), MonthDay.of(1, 6)) else SmartPeriod.EveryYear(MonthDay.from(first.from), MonthDay.from(first.to))
        } else {
          SmartPeriod.Ranges(listOf(christmasWindow(first) ?: LocalDayRange(first.from, first.to)))
        }
      else -> null
    }
  }

  /** Il chip e' il Natale dell'analizzatore (24-26 dicembre): per un album diventa il periodo delle feste, fino all'Epifania. */
  private fun isChristmas(chip: SearchChip.DateRange): Boolean =
    chip.label.startsWith("Natale") && chip.from.monthValue == 12 && chip.from.dayOfMonth == 24 && chip.to.monthValue == 12 && chip.to.dayOfMonth == 26

  private fun christmasWindow(chip: SearchChip.DateRange): LocalDayRange? =
    if (chip.everyYear || !isChristmas(chip)) null else LocalDayRange(chip.from, LocalDate.of(chip.to.year + 1, 1, 6))

  /** Gli intervalli in ordine, quelli che si toccano o sono attaccati fusi in uno. */
  fun merge(ranges: List<LocalDayRange>): List<LocalDayRange> {
    val out = ArrayList<LocalDayRange>()
    for (range in ranges.sortedBy { it.from }) {
      val last = out.lastOrNull()
      if (last != null && !range.from.isAfter(last.to.plusDays(1))) {
        if (range.to.isAfter(last.to)) out[out.lastIndex] = LocalDayRange(last.from, range.to)
      } else {
        out += range
      }
    }
    return out
  }

  /** Quanti giorni dura il periodo (per sapere se e' "lungo", D13): un periodo di giorni singoli (una festa mobile) non lo e' mai. */
  fun lengthDays(period: SmartPeriod): Long = when (period) {
    is SmartPeriod.Ranges -> period.ranges.maxOf { it.days }
    is SmartPeriod.EveryYear -> {
      // Un anno non bisestile qualunque: da `from` a `to`, attraversando il capodanno se `to` viene prima.
      val from = period.from.atYear(2001)
      val to = period.to.atYear(if (period.to.isBefore(period.from)) 2002 else 2001)
      ChronoUnit.DAYS.between(from, to) + 1
    }
    is SmartPeriod.Days -> 1
  }
}

internal object PeriodSeries {

  /**
   * Le serie di giorni di un viaggio (D13): i giorni con foto nel luogo, due giorni di fila nella stessa serie se distano al piu'
   * [gapDays], ogni serie allargata di [marginDays] per parte (il giorno prima e quello dopo), le serie che si toccano fuse. Una serie che
   * attraversa il 31/12 resta una (E-AL1): i giorni sono date, non anni.
   */
  fun build(
    days: Collection<LocalDate>,
    gapDays: Long = SmartTuning.SERIES_GAP_DAYS,
    marginDays: Long = 1,
  ): List<LocalDayRange> {
    val sorted = days.distinct().sorted()
    if (sorted.isEmpty()) return emptyList()
    val raw = ArrayList<LocalDayRange>()
    var start = sorted.first()
    var previous = start
    for (day in sorted.drop(1)) {
      if (ChronoUnit.DAYS.between(previous, day) > gapDays) {
        raw += LocalDayRange(start, previous)
        start = day
      }
      previous = day
    }
    raw += LocalDayRange(start, previous)
    return PeriodChips.merge(raw.map { it.widen(marginDays) })
  }
}
