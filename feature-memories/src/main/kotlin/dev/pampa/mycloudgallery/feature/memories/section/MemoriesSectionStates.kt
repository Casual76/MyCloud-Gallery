package dev.pampa.mycloudgallery.feature.memories.section

import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.contract.ShelfView
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesListState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesMonth
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import java.time.DateTimeException
import java.time.YearMonth

/**
 * Gli stati della sezione Ricordi delle Raccolte e di "Tutti i ricordi" (docs/fase9-progetto.md §1.5 `MemoriesSectionState` e `MemoriesListState`,
 * §2.5, D14, E-MEM1), come funzioni pure sullo scaffale gia' pronto da mostrare ([ShelfView]) e sulle sue voci dopo il filtro finale dei Nascosti.
 * Provate su ogni caso (`MemoriesSectionStatesTest`).
 *
 *  - **Sezione**: fino a [MemoriesSectionState.Ready.MAX_CARDS] voci, **prima le non viste** (con il segno "nuovo"), poi le piu' recenti
 *    (lo scaffale e' gia' dal piu' recente; l'ordine fra pari si conserva); i non visti contano **tutti**, non solo quelli in vista.
 *  - **Da vuota** "I ricordi arrivano quando ci sono abbastanza foto" (E-MEM1), o "Aspetto la tua libreria" se la Libreria non e' ancora pronta;
 *    **in generazione** "Sto preparando i ricordi" solo al primo giro (D14, §2.5).
 *  - **Tutti i ricordi**: lo scaffale intero per mese **di pubblicazione**, il mese piu' recente per primo, dentro il mese dal piu' recente.
 */
object MemoriesSectionStates {

  fun section(view: ShelfView, cards: List<MemoryCard> = view.items): MemoriesSectionState = when (val state = view.state) {
    ShelfState.Working -> MemoriesSectionState.Working
    is ShelfState.Empty -> if (cards.isEmpty()) MemoriesSectionState.Empty(state.reason) else ready(cards)
    ShelfState.Ready -> if (cards.isEmpty()) MemoriesSectionState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS) else ready(cards)
  }

  private fun ready(cards: List<MemoryCard>): MemoriesSectionState {
    val shown = (cards.filter { it.isNew } + cards.filterNot { it.isNew }).take(MemoriesSectionState.Ready.MAX_CARDS)
    return MemoriesSectionState.Ready(shown, unseen = cards.count { it.isNew })
  }

  /** "Tutti i ricordi" per [cards] (le voci dello scaffale, gia' filtrate): vuoto se non c'e' niente. */
  fun list(cards: List<MemoryCard>): MemoriesListState {
    if (cards.isEmpty()) return MemoriesListState.Empty
    val byMonth = cards.groupBy { monthOf(it.publishedDay) }
    val months = byMonth.entries.sortedByDescending { it.key }
      .map { (month, items) -> MemoriesMonth(month, items.sortedWith(compareByDescending<MemoryCard> { it.publishedDay }.thenBy { it.id.value })) }
    return MemoriesListState.Ready(months)
  }

  /** Il mese di un giorno di pubblicazione `aaaammgg` (un valore rovinato cade in un mese "zero": mai un crollo). */
  fun monthOf(day: Int): YearMonth = try {
    YearMonth.of(day / 10_000, day / 100 % 100)
  } catch (_: DateTimeException) {
    YearMonth.of(1970, 1)
  }

  /** Il codice stabile di una voce (D14): `m_` e i primi 16 caratteri dell'id (un hash: nessun dato dell'utente, valido per `rememberSaveable`). */
  fun codeOf(id: MemoryId): String = MemoriesEntryTarget.MEMORY_PREFIX + id.value.take(MemoriesEntryTarget.CODE_CHARS)
}

/**
 * Cosa apre una voce della sezione Ricordi delle Raccolte: `:app` la riconosce dal codice e porta alla pagina giusta. I codici sono stabili e non
 * portano mai un titolo, un luogo o un nome. Una voce di un ricordo porta solo il **prefisso** dell'id: la sezione sa dire a quale ricordo
 * corrisponde ([MemoriesSectionSources.resolve]).
 */
sealed interface MemoriesEntryTarget {
  /** Una voce di un ricordo: il suo codice (`m_<16 caratteri>`). */
  data class Memory(val code: String) : MemoriesEntryTarget {
    override fun toString(): String = "MemoriesEntryTarget.Memory"
  }

  /** "Tutti i ricordi". */
  data object All : MemoriesEntryTarget

  /** Le righe che dicono come stanno le cose ("I ricordi arrivano quando ci sono abbastanza foto", "Sto preparando"): niente da aprire. */
  data object Inert : MemoriesEntryTarget

  companion object {
    const val ALL: String = "memories_all"
    const val EMPTY: String = "memories_empty"
    const val LIBRARY: String = "memories_library"
    const val WORKING: String = "memories_working"
    const val MEMORY_PREFIX: String = "m_"
    const val CODE_CHARS: Int = 16

    /** Il bersaglio di una voce, o null se il codice non e' di questa sezione (o e' di una versione piu' nuova). */
    fun parse(code: String): MemoriesEntryTarget? = when {
      code == ALL -> All
      code == EMPTY || code == LIBRARY || code == WORKING -> Inert
      code.startsWith(MEMORY_PREFIX) && code.length == MEMORY_PREFIX.length + CODE_CHARS -> Memory(code)
      else -> null
    }
  }
}
