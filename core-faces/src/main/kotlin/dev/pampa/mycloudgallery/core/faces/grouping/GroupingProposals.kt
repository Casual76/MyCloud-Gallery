package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.MergeSuggestion
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource

/**
 * Le proposte "E' la stessa persona?" (D20 (a), §2.6 "Proposte", E-F2) dello stato di adesso, **senza** la notte e su ogni dispositivo
 * (anche chi non e' custode): le stesse coppie che la manutenzione proporrebbe (volti non correnti, correzioni, esemplari rinnovati, poi
 * le unioni prudenti: le coppie che la notte unirebbe da sola non si propongono). Puro: lo stato in ingresso non cambia. Per P9 (pagina
 * persona "Forse e' anche…", cima di "Tutte le persone"); le risposte diventano voci `people` (`alias` o `apart`).
 */
object GroupingProposals {
  fun of(input: GroupingInput): List<MergeSuggestion> = GroupingPass(input, NO_IDS).run {
    dropStale()
    applyCorrections()
    refreshExemplars()
    merges()
    suggestions.toList()
  }

  private val NO_IDS = PersonIdSource { error("Le proposte non creano persone.") }
}
