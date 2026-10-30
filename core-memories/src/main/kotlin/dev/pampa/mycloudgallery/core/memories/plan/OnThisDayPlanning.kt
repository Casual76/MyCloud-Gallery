package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPriority
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.settings.MemoryQuiet
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.time.LocalDate

/**
 * "N anni fa oggi" (docs/fase9-progetto.md D3, E-MEM1-E-MEM4): per ogni anno passato i media dello stesso giorno dell'anno, cercati **per
 * giorno** nell'indice della tabella (una ricerca per anno, mai una scansione della Libreria). Un anno si qualifica con almeno
 * [MemoryRules.OTD_MIN_GOOD] media buoni dopo le esclusioni di D5; fra gli anni che si qualificano si propongono il piu' recente
 * ([MemoryPriority.OTD_RECENT]) e il piu' vecchio ([MemoryPriority.OTD_OLDEST]).
 */
internal object OnThisDayPlanning {

  fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    val table = input.cells
    val qualifying = ArrayList<Pair<LocalDate, IntArray>>()
    for (year in PlanDates.firstYear(table, today) until today.year) {
      for (origin in PlanDates.originsIn(year, today)) {
        val cells = PlanDates.good(table, input.choices, table.onDay(MemoryDays.dayOf(origin)), dateBound = true)
        if (cells.size < MemoryRules.OTD_MIN_GOOD) continue
        if (MemoryQuiet.isQuiet(MemoryIds.of(MemoryKey.OnThisDay(origin)), input.history, input.choices, today)) continue
        qualifying += origin to cells
      }
    }
    if (qualifying.isEmpty()) return emptyList()
    qualifying.sortByDescending { it.first }
    val chosen = ArrayList<Pair<Pair<LocalDate, IntArray>, Int>>(2)
    chosen += qualifying.first() to MemoryPriority.OTD_RECENT
    if (qualifying.size > 1) chosen += qualifying.last() to MemoryPriority.OTD_OLDEST
    return chosen.map { (pair, priority) ->
      val (origin, cells) = pair
      MemoryCandidate(MemoryKey.OnThisDay(origin), priority, cells, yearsAgo = today.year - origin.year, days = DayRange.of(origin))
    }
  }
}
