package dev.pampa.mycloudgallery.feature.memories.list

import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.memories.contract.AllMemoriesUi
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionStates
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * "TUTTI I RICORDI" (Fase 9, P9; docs/fase9-progetto.md §1.5 `MemoriesListState`, §2.5, D14, D31, E-H8): lo scaffale intero per mese di pubblicazione.
 * Non sa niente di Compose.
 *
 * **Istantaneo**: il primo fotogramma e' lo scaffale com'e' (titoli, conti), **senza copertine**; subito dopo il filtro finale dei Nascosti
 * ([FinalFilter]) rifa' conti e voci e **solo allora** consegna la Libreria da cui disegnare le copertine ([AllMemoriesUi.snapshot]): un nascosto
 * un attimo fa non compare mai in una copertina. Un ricordo scartato ("Non mostrare") non c'e' (lo scaffale non lo da').
 */
class AllMemoriesController(
  private val memories: Memories,
  private val names: MemoryNames,
  private val filter: FinalFilter,
  scope: CoroutineScope,
) {
  private fun named(cards: List<MemoryCard>): Map<PersonId, String> =
    cards.mapNotNull { it.title.person }.distinct().mapNotNull { id -> names.cachedPerson(id)?.let { id to it } }.toMap()

  private fun first(cards: List<MemoryCard>) = AllMemoriesUi(MemoriesSectionStates.list(cards), snapshot = null, named = named(cards))

  val ui: StateFlow<AllMemoriesUi> = memories.shelf.transformLatest { view ->
    emit(first(view.items))
    val refined = filter.refine(memories, view.items)
    names.learn(refined)
    val snapshot = refined.firstOrNull()?.let { card -> memories.memory(card.id).firstOrNull()?.view?.snapshot }
    emit(AllMemoriesUi(MemoriesSectionStates.list(refined), snapshot, named(refined)))
  }.stateIn(scope, SharingStarted.Eagerly, first(memories.shelf.value.items))
}
