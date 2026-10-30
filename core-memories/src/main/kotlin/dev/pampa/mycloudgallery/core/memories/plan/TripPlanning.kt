package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryOrigin
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPriority
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.settings.MemoryQuiet
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Viaggi ed eventi passati (docs/fase9-progetto.md D3, D12, E-MEM12, E-MEM13): i viaggi arrivano dalla porta `MemoryTrips` (solo media propri e
 * visibili, i scartati non ci sono mai, nessuna coordinata). Due modi:
 *  - **anniversario**: l'inizio cade a +-3 giorni da oggi in un anno passato ([MemoryPriority.ANNIVERSARY], con il numero di anni);
 *  - **riscoperta**: un viaggio (non un evento) di piu' di un anno fa che l'utente non ha visto e non e' sullo scaffale, al piu' uno a settimana
 *    ([MemoryRules.REDISCOVERY_EVERY_DAYS]), il piu' lungo prima ([MemoryPriority.REDISCOVERY]).
 *
 * Un viaggio che tocca un periodo "Mai" anche per un solo giorno non diventa un ricordo (E-MEM12). Le celle di un viaggio sono i suoi media che
 * stanno nella tabella (visibili) e non hanno esclusioni di D5, in ordine di posizione.
 */
internal object TripPlanning {

  fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate> {
    val table = input.cells
    val out = ArrayList<MemoryCandidate>()
    val rediscoveries = ArrayList<MemoryCandidate>()
    for (trip in input.trips) {
      if (!trip.days.to.isBefore(today)) continue
      val key = trip.key()
      if (MemoryQuiet.isQuiet(MemoryIds.of(key), input.history, input.choices, today)) continue
      if (touchesNever(trip, input)) continue
      val spec = MemoryRules.spec(key.kind)
      val cells = PlanDates.good(table, input.choices, positions(trip, input), spec.dateBound)
      if (cells.size < spec.minSize) continue
      val origin = trip.album?.let { MemoryOrigin.TripAlbum(it) }
      val years = PlanDates.anniversaryYears(trip.days.from, today)
      if (years != null) {
        out += MemoryCandidate(key, MemoryPriority.ANNIVERSARY, cells, years, trip.days, trip.label, trip.placeIds, origin = origin)
      } else if (trip.kind == TripKind.TRIP && ChronoUnit.DAYS.between(trip.days.to, today) > MemoryRules.REDISCOVERY_MIN_AGE_DAYS) {
        rediscoveries += MemoryCandidate(
          key, MemoryPriority.REDISCOVERY, cells, null, trip.days, trip.label, trip.placeIds, origin = origin, rediscovery = true,
        )
      }
    }
    val since = PlanDates.daysSince(input.history.lastRediscovery, today)
    if (rediscoveries.isNotEmpty() && (since == null || since >= MemoryRules.REDISCOVERY_EVERY_DAYS)) {
      out += rediscoveries.sortedWith(
        compareByDescending<MemoryCandidate> { it.days.days }.thenByDescending { it.days.from }.thenBy { it.key.text },
      ).first()
    }
    return out
  }

  /** Un viaggio che tocca un periodo "Mai" anche per un giorno (i giorni `aaaammgg` si ordinano come le date). */
  private fun touchesNever(trip: TripDigestView, input: PlanInput): Boolean {
    if (input.choices.neverDays.isEmpty()) return false
    val from = MemoryDays.dayOf(trip.days.from)
    val to = MemoryDays.dayOf(trip.days.to)
    return input.choices.neverDays.any { it.to >= from && it.from <= to }
  }

  /** Le posizioni nella tabella dei media del viaggio (quelli che ci sono), senza doppioni, in ordine di posizione. */
  private fun positions(trip: TripDigestView, input: PlanInput): IntArray {
    val found = IntArray(trip.members.size)
    var count = 0
    for (id in trip.members) {
      val i = input.cells.indexOf(id)
      if (i >= 0) found[count++] = i
    }
    val sorted = found.copyOf(count).also { it.sort() }
    var unique = 0
    for (k in sorted.indices) if (k == 0 || sorted[k] != sorted[k - 1]) sorted[unique++] = sorted[k]
    return sorted.copyOf(unique)
  }
}
