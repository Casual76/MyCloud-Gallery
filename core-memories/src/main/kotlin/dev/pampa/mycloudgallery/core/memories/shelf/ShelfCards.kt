package dev.pampa.mycloudgallery.core.memories.shelf

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.contract.ShelfView
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices

/**
 * Lo scaffale come lo mostra la sezione Ricordi (docs/fase9-progetto.md D14, D31, E-MEM5, E-H8): le voci, il conto dei nuovi e lo stato. Una
 * funzione pura sul file, sulle scelte e sull'istantanea: a ogni cambio di una delle tre la sezione si rifa'.
 *
 *  - **Mai uno scartato** ("Non mostrare" lo toglie dalla vista; il ricordo resta nel file per "Annulla", D10).
 *  - **Mai un nascosto**: con l'istantanea (che i nascosti non li ha) un elemento che non c'e' piu' non si conta e non puo' essere la copertina;
 *    sotto 3 elementi la voce non c'e'. E' un controllo in memoria, senza database, che vale anche nell'istante fra un "Nascondi" e la
 *    pulizia dello scaffale (`prune`); senza istantanea (l'avvio, prima che la Libreria sia pronta) si disegna lo scaffale com'e' nel file, e la
 *    superficie applica comunque il filtro finale prima di mostrare.
 *  - **Stato**: mai un giro e Libreria pronta = "sto preparando" (solo il primo); mai un giro e Libreria non pronta, o scaffale vuoto con la
 *    Libreria non pronta = [ShelfEmptyReason.LIBRARY_NOT_READY]; scaffale vuoto dopo un giro = [ShelfEmptyReason.NOT_ENOUGH_PHOTOS] (E-MEM1).
 */
internal object ShelfCards {

  fun view(file: ShelfFile?, choices: MemoriesChoices, snapshot: TimelineSnapshot?): ShelfView {
    val cards = file?.memories.orEmpty().mapNotNull { card(it, choices, snapshot) }
    if (cards.isNotEmpty()) return ShelfView(cards, cards.count { it.isNew }, ShelfState.Ready)
    val neverRan = file == null || file.lastRunDay == 0
    return when {
      snapshot == null -> ShelfView(emptyList(), 0, ShelfState.Empty(ShelfEmptyReason.LIBRARY_NOT_READY))
      neverRan && file?.memories.isNullOrEmpty() -> ShelfView.WORKING
      else -> ShelfView.EMPTY
    }
  }

  fun card(memory: Memory, choices: MemoriesChoices, snapshot: TimelineSnapshot?): MemoryCard? {
    if (memory.id.value in choices.dismissed) return null
    val shown = if (snapshot == null) memory.members else memory.members.filter { snapshot.indexOf(it) >= 0 }
    if (shown.size < Memory.MIN_MEMBERS) return null
    val cover = if (snapshot == null || snapshot.indexOf(memory.cover) >= 0) memory.cover else shown.first()
    return MemoryCard(memory.id, memory.kind, memory.title, cover, shown.size, memory.publishedDay, isNew = memory.id.value !in choices.seen)
  }
}
