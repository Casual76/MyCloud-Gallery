package dev.pampa.mycloudgallery.feature.memories.ui

import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPlaceNames
import dev.pampa.mycloudgallery.core.model.PersonId
import java.util.concurrent.ConcurrentHashMap

/**
 * I nomi che i titoli dei ricordi mostrano e **non salvano mai** (D12): il nome di una persona letto adesso dalla porta delle persone (con l'alias
 * risolto: una rinomina si vede, una persona nascosta o dimenticata non ha piu' nome) e i nomi dei luoghi. Si ricordano in memoria (mai su disco, mai
 * nei log) per disegnare il primo fotogramma senza aspettare: la prima volta un ricordo di una persona dice "Una persona", poi il nome.
 */
class MemoryNames(private val people: MemoryPeople?, private val places: MemoryPlaceNames) {

  private val known = ConcurrentHashMap<PersonId, String>()

  /** Il nome di [person] se lo si e' gia' letto in questa sessione (null altrimenti): per il primo fotogramma, senza sospendere. */
  fun cachedPerson(person: PersonId?): String? = person?.let { known[it] }

  /** Il nome di [person] adesso (null: Persone non e' attiva qui, o la persona e' nascosta, dimenticata o senza nome). Si ricorda in memoria. */
  suspend fun person(person: PersonId?): String? {
    if (person == null || people == null || !people.available.value) {
      person?.let { known.remove(it) }
      return null
    }
    val name = people.resolve(person)?.let { people.label(it) }
    if (name == null) known.remove(person) else known[person] = name
    return name
  }

  /** Il nome di ogni persona dei ricordi di [cards] (una lettura per persona). */
  suspend fun learn(cards: List<MemoryCard>) {
    for (person in cards.mapNotNull { it.title.person }.distinct()) person(person)
  }

  /** I nomi dei luoghi di [placeIds] (gia' scritti da `PlaceNames`; mai la parola "casa"). */
  fun places(placeIds: List<Int>): List<String> = if (placeIds.isEmpty()) emptyList() else places.names(placeIds)
}
