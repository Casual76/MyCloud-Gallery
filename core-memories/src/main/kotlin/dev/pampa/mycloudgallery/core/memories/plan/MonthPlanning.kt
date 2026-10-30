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
import java.time.YearMonth

/**
 * "Il meglio del mese" (docs/fase9-progetto.md D3): dal 1 al 7 del mese, per il mese prima, se ha almeno [MemoryRules.MONTH_MIN_CANDIDATES]
 * candidati buoni. Si cercano i giorni del mese nell'indice della tabella (28-31 ricerche, mai una scansione); le celle sono rimesse in ordine
 * di posizione (con i fusi la posizione e il giorno locale non vanno sempre nello stesso verso).
 */
internal object MonthPlanning {

  fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    if (today.dayOfMonth > MemoryRules.MONTH_LAST_DAY) return emptyList()
    val month = YearMonth.from(today).minusMonths(1)
    val table = input.cells
    val parts = ArrayList<IntArray>(month.lengthOfMonth())
    var total = 0
    for (d in 1..month.lengthOfMonth()) {
      val good = PlanDates.good(table, input.choices, table.onDay(MemoryDays.dayOf(month.atDay(d))), dateBound = true)
      if (good.isNotEmpty()) {
        parts += good
        total += good.size
      }
    }
    if (total < MemoryRules.MONTH_MIN_CANDIDATES) return emptyList()
    val key = MemoryKey.Month(month)
    if (MemoryQuiet.isQuiet(MemoryIds.of(key), input.history, input.choices, today)) return emptyList()
    val cells = IntArray(total)
    var at = 0
    for (part in parts) {
      part.copyInto(cells, at)
      at += part.size
    }
    cells.sort()
    return listOf(MemoryCandidate(key, MemoryPriority.MONTH, cells, null, DayRange(month.atDay(1), month.atEndOfMonth()), month = month))
  }
}
