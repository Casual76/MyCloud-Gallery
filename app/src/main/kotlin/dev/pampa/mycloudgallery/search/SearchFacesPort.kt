package dev.pampa.mycloudgallery.search

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.feature.search.contract.SearchFace
import dev.pampa.mycloudgallery.feature.search.contract.SearchFaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * I2 (fase I): il volto di una persona per l'anteprima di un suggerimento della ricerca, con **lo stesso cancello** dei volti delle Raccolte
 * (`AppCollectionFaceCrops`, CU-19): il volto di copertina della persona (solo persone con un nome e non nascoste, dall'elenco delle persone montate), le sue
 * foto d'origine mostrabili adesso (nessuna = niente volto) e il ritaglio dalla cache in memoria di feature-people (mai su disco). Prima del montaggio delle
 * persone: niente. [revocations] cambia quando i ritagli gia' dati non valgono piu' (Persone montata, spenta o rimontata, ritagli cancellati).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSearchFaces(private val graph: FacesGraph) : SearchFaces {
  override suspend fun face(person: PersonId, sizePx: Int): SearchFace? {
    val live = graph.live.value ?: return null
    val cover = live.people.list.value.named.firstOrNull { it.id == person }?.cover ?: return null
    val origins = live.people.shownCellsOf(cover)
    if (origins.isEmpty()) return null
    val bitmap = live.crops.crop(cover, sizePx) ?: return null
    return SearchFace(bitmap, origins)
  }

  override val revocations: Flow<Any?> =
    graph.live.flatMapLatest { live -> live?.crops?.clears?.map { cleared -> System.identityHashCode(live).toLong() * 31 + cleared } ?: flowOf(null) }

  override fun toString(): String = "AppSearchFaces"
}
