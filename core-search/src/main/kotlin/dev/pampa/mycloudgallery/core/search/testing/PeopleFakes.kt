package dev.pampa.mycloudgallery.core.search.testing

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fase 8 (P0, D26): le persone finte per i test della ricerca (P10). Un nome per persona ([name]), le celle **sicure** ([sure]) e quelle
 * nella fascia "forse" ([likely]) per persona. Le celle nascoste non ci devono essere: chi costruisce il fake le toglie (come fa la porta
 * vera con `VisibleMedia` e `HiddenMask`). Con [available] falso il dizionario e' vuoto, come sul dispositivo escluso (E-F37).
 */
class FakePeopleLookup : PeopleLookup {
  override val available = MutableStateFlow(true)

  /** Il primo calcolo dei volti in corso (percentuale), o null: lo legge Cerca per l'avviso "Sto ancora riconoscendo i volti". */
  val progress = MutableStateFlow<Int?>(null)

  override val progressPercent: StateFlow<Int?> get() = progress

  private val names = LinkedHashMap<PersonId, String>()
  private val sure = HashMap<PersonId, MutableSet<MediaId>>()
  private val likely = HashMap<PersonId, MutableSet<MediaId>>()

  /** Quante volte e' stato chiesto il dizionario (P10 lo legge una volta per query). */
  var dictionaryCalls: Int = 0
    private set

  @Synchronized
  fun name(id: PersonId, label: String): FakePeopleLookup {
    names[id] = label
    return this
  }

  @Synchronized
  fun sure(id: PersonId, media: Collection<MediaId>): FakePeopleLookup {
    sure.getOrPut(id) { LinkedHashSet() } += media
    return this
  }

  @Synchronized
  fun likely(id: PersonId, media: Collection<MediaId>): FakePeopleLookup {
    likely.getOrPut(id) { LinkedHashSet() } += media
    return this
  }

  override suspend fun dictionary(): PeopleDictionary = synchronized(this) {
    dictionaryCalls++
    if (!available.value) return PeopleDictionary.EMPTY
    PeopleDictionary(
      names.mapNotNull { (id, label) ->
        val count = sure[id].orEmpty().size
        if (count == 0) null else PersonEntry(PeopleDictionary.normalize(label), id, label, count)
      },
    )
  }

  override suspend fun cells(option: PersonOption, includeLikely: Boolean): Set<MediaId> = synchronized(this) {
    if (!available.value) return emptySet()
    fun of(id: PersonId): Set<MediaId> = sure[id].orEmpty() + (if (includeLikely) likely[id].orEmpty() else emptySet())
    val sets = option.ids.map { of(it) }
    when (option.mode) {
      PeopleMode.ANY -> sets.flatten().toSet()
      PeopleMode.ALL -> sets.reduce { acc, s -> acc intersect s }
    }
  }
}
