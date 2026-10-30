package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.MemoryExclusions
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Le regole sulle date del generatore (docs/fase9-progetto.md D3, E-MEM3, E-MEM4): giorni locali dello scatto, mai l'orologio del telefono
 * ("oggi" arriva dalla porta `MemoryClock`). Funzioni pure.
 */
internal object PlanDates {

  /**
   * I giorni d'origine di [year] per [today] (E-MEM3): il 29 febbraio esce il 28 negli anni non bisestili, e il 29 febbraio di un anno
   * bisestile si propone solo il 29 (il 28 febbraio di un anno bisestile non riprende il 29 di quelli passati: ha i suoi 28).
   */
  fun originsIn(year: Int, today: LocalDate): List<LocalDate> {
    val leap = Year.isLeap(year.toLong())
    return when {
      today.monthValue == 2 && today.dayOfMonth == 29 -> if (leap) listOf(LocalDate.of(year, 2, 29)) else emptyList()
      today.monthValue == 2 && today.dayOfMonth == 28 && !Year.isLeap(today.year.toLong()) ->
        listOfNotNull(LocalDate.of(year, 2, 28), if (leap) LocalDate.of(year, 2, 29) else null)
      else -> listOf(LocalDate.of(year, today.monthValue, today.dayOfMonth))
    }
  }

  /**
   * Quanti anni fa e' iniziato un viaggio o evento il cui inizio [from] cade a +-3 giorni da [today] in un anno passato (anche a cavallo
   * d'anno: il 31 dicembre e il 2 gennaio), o null se non e' un anniversario.
   */
  fun anniversaryYears(from: LocalDate, today: LocalDate): Int? {
    for (shift in intArrayOf(0, -1, 1)) {
      val year = today.year + shift
      if (from.year >= year) continue
      val anniversary = from.withYear(year)
      if (abs(ChronoUnit.DAYS.between(anniversary, today)) <= MemoryRules.ANNIVERSARY_WINDOW_DAYS) return year - from.year
    }
    return null
  }

  /** Da quanti giorni e' passato il giorno `aaaammgg` [last] fino a [today]; null se non c'e' o non e' una data. */
  fun daysSince(last: Int?, today: LocalDate): Long? = last?.let { MemoryDays.dateOf(it) }?.let { ChronoUnit.DAYS.between(it, today) }

  /** Il primo anno con almeno una cella (per non cercare "anni fa" prima della Libreria): [today]`.year` se la tabella e' vuota. */
  fun firstYear(table: CellTable, today: LocalDate): Int {
    var first = today.year
    for (day in table.daysWithCells()) MemoryDays.dateOf(day)?.let { if (it.year < first) first = it.year }
    return first
  }

  /**
   * Le posizioni di [positions] che non hanno nessuna esclusione di D5 (e nessun "Mai" per periodo), nello stesso ordine. [dateBound] come
   * `PickSpec.dateBound`: i tipi legati a una data scartano anche le date stimate.
   */
  fun good(table: CellTable, choices: MemoriesChoices, positions: IntArray, dateBound: Boolean): IntArray {
    var count = 0
    val out = IntArray(positions.size)
    for (i in positions) if (MemoryExclusions.of(table, i, dateBound, choices) == null) out[count++] = i
    return if (count == out.size) out else out.copyOf(count)
  }
}
