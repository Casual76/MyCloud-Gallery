package dev.pampa.mycloudgallery.core.memories.settings

import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PlanHistory
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Una chiave "che non si propone" (docs/fase9-progetto.md D9, E-MEM9): **una sola definizione** per il generatore dei ricordi (P6), quello
 * delle persone (P8) e la pulizia. Un ricordo non si propone se:
 *  - e' gia' sullo scaffale (un ricordo pubblicato non si tocca);
 *  - e' stato scartato ("Non mostrare": mai, su nessun dispositivo, nemmeno fra un anno: l'id e' deterministico);
 *  - e' stato visto negli ultimi 12 mesi ([MemoryRules.SEEN_QUIET_DAYS]), anche se nel frattempo e' uscito dallo scaffale.
 *
 * Il "visto" e' il giorno locale della visione (`aaaammgg`, D10): un valore che non e' una data vale "mai visto".
 */
object MemoryQuiet {

  fun isQuiet(id: MemoryId, history: PlanHistory, choices: MemoriesChoices, today: LocalDate): Boolean {
    if (id in history.onShelf) return true
    if (id.value in choices.dismissed) return true
    val seen = choices.seen[id.value]?.let { MemoryDays.dateOf(it) } ?: return false
    return ChronoUnit.DAYS.between(seen, today) < MemoryRules.SEEN_QUIET_DAYS
  }
}
