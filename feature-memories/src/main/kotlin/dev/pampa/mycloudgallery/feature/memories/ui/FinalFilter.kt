package dev.pampa.mycloudgallery.feature.memories.ui

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.firstOrNull

/**
 * **Il filtro finale dei Nascosti** delle superfici dei ricordi (docs/fase9-progetto.md D31, E-H8, §2.11): subito prima di mostrare, ogni
 * superficie chiede ancora ai nascosti ([HiddenMask]) quali dei suoi media non si possono vedere, anche se il servizio dei ricordi li ha gia'
 * tolti dallo scaffale e dalla vista: un media nascosto un attimo fa non deve comparire in sezione, pagina, "Tutti i ricordi", presentazione o
 * copertina. **Fallisce chiuso**: un guasto della lettura vale "tutti nascosti" (`HiddenMask.hiddenAmong`).
 *
 * **La maschera c'e' sempre** (R3, 31/10): [mask] non e' opzionale. Prima, senza il legame di `:app`, il filtro non faceva niente e nessuna prova lo vedeva; ora
 * `MemoriesDependencies` la chiede a Hilt senza `Optional` e la mancanza e' un errore di compilazione di `:app`. Chi prova una superficie senza nascosti passa una
 * maschera che non nasconde niente, in modo che si veda.
 *
 * E' **l'unico file** di questo modulo che nomina il contratto dei nascosti: le superfici passano tutte da qui (`HiddenSurfaces`, riga `MEMORIES`).
 */
class FinalFilter(private val mask: HiddenMask) {

  /** Quali di [ids] sono nascosti adesso. */
  suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
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
   * [Memory.MIN_MEMBERS] elementi, o che il servizio non conosce piu', non c'e'.
   *
   * **Senza l'istantanea della Libreria si aspetta** (RP-2, 31/10): `Memories.memory` non emette finche' la Libreria non e' stata letta, e `null` vuol dire solo
   * "non c'e' piu'". Prima l'attesa aveva un tetto di 2 secondi e, scaduto, buttava la voce: con la Libreria in ritardo ogni voce usciva e la sezione diceva "I ricordi
   * arrivano quando ci sono abbastanza foto" con lo scaffale pieno. Ora non si butta niente per un "non lo so": la superficie tiene lo scaffale com'e' nel file (solo
   * titoli e conti, nessuna miniatura) e il risultato arriva appena la Libreria e' pronta; chi chiama lo annulla se lo scaffale cambia.
   */
  suspend fun refine(memories: Memories, cards: List<MemoryCard>): List<MemoryCard> {
    val out = ArrayList<MemoryCard>(cards.size)
    for (card in cards) {
      val view = memories.memory(card.id).firstOrNull()?.view ?: continue
      val shown = shown(view)
      if (shown.size < Memory.MIN_MEMBERS) continue
      out += card.copy(cover = if (card.cover in shown) card.cover else shown.first(), count = shown.size)
    }
    return out
  }
}
