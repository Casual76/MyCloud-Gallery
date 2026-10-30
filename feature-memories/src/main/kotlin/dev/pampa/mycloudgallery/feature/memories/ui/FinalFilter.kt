package dev.pampa.mycloudgallery.feature.memories.ui

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **Il filtro finale dei Nascosti** delle superfici dei ricordi (docs/fase9-progetto.md D31, E-H8, §2.11): subito prima di mostrare, ogni
 * superficie chiede ancora ai nascosti ([HiddenMask]) quali dei suoi media non si possono vedere, anche se il servizio dei ricordi li ha gia'
 * tolti dallo scaffale e dalla vista: un media nascosto un attimo fa non deve comparire in sezione, pagina, "Tutti i ricordi", presentazione o
 * copertina. **Fallisce chiuso**: un guasto della lettura vale "tutti nascosti" (`HiddenMask.hiddenAmong`). Senza maschera ([mask] nulla: `:app`
 * non l'ha ancora legata) vale il solo filtro del servizio, che i nascosti non li ha mai.
 *
 * E' **l'unico file** di questo modulo che nomina il contratto dei nascosti: le superfici passano tutte da qui (`HiddenSurfaces`, riga `MEMORIES`).
 */
class FinalFilter(private val mask: HiddenMask?) {

  /** Quali di [ids] sono nascosti adesso. */
  suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    if (mask == null || ids.isEmpty()) return emptySet()
    return mask.hiddenAmong(ids)
  }

  /** Gli id di [view] che si possono mostrare adesso, nell'ordine della vista. */
  suspend fun shown(view: OrderedTimelineView): List<MediaId> {
    val all = ArrayList<MediaId>(view.size)
    for (at in 0 until view.size) all += view.snapshot.idAt(view.snapshotIndex(at))
    val hidden = hiddenAmong(all)
    return if (hidden.isEmpty()) all else all.filter { it !in hidden }
  }

  /**
   * Le [cards] com'erano nello scaffale, con la copertina e il conto rifatti sui media che si possono mostrare adesso: una voce che resta sotto
   * [Memory.MIN_MEMBERS] elementi, o che il servizio non conosce piu', non c'e'. Senza maschera sono quelle di prima.
   */
  suspend fun refine(memories: Memories, cards: List<MemoryCard>): List<MemoryCard> {
    if (mask == null) return cards
    val out = ArrayList<MemoryCard>(cards.size)
    for (card in cards) {
      val view = withTimeoutOrNull(VIEW_WAIT) { memories.memory(card.id).firstOrNull() }?.view ?: continue
      val shown = shown(view)
      if (shown.size < Memory.MIN_MEMBERS) continue
      out += card.copy(cover = if (card.cover in shown) card.cover else shown.first(), count = shown.size)
    }
    return out
  }

  private companion object {
    val VIEW_WAIT = 2.seconds
  }
}
