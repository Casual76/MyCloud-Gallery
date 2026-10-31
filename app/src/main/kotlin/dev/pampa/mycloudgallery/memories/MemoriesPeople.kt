package dev.pampa.mycloudgallery.memories

import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleIndex
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.settings.less.PeoplePhase
import dev.pampa.mycloudgallery.core.memories.settings.less.PeopleSwitch
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.faces.FacesGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// LE PERSONE NEI RICORDI (Fase 9, P13; D11, D12, E-MEM10, E-H10, L274; "per P13" di P8). Sopra le persone montate della Fase 8: l'indice delle persone
// (`PeopleIndex`: nomi, alias) e la ricerca per persona (`PeopleLookup`, il ponte `PeopleBridge`, che porta le celle **visibili** con `PeopleVisibility`:
// `VisibleMedia` e il filtro finale `HiddenMask`). Mai un nome salvato: [IndexMemoryPeople.label] lo legge al momento. Nei log niente.

/**
 * La porta [MemoryPeople] vera (P8: "sopra `PeopleIndex`/`PeopleLookup`/`PeopleVisibility`; `available` falso se Persone non e' `On` con `ACTIVE` o
 * `LOCAL_ONLY` qui; `peopleIn` con gli alias risolti").
 *
 * - [available] e' quello della ricerca per persona (`PeopleIndex.available`: Persone attiva **qui**).
 * - [named]: le persone **con un nome** dell'indice (mai nascoste ne' dimenticate: l'indice non le ha), ognuna con le sue celle **visibili** al livello
 *   sicuro; una persona senza media mostrabili non c'e'. Il risultato si ricorda finche' non cambiano le persone ([version]) o l'istantanea.
 * - [peopleIn]: dai media alle persone, con le stesse celle (un membro di gruppo e' la sua copertina, come nella Libreria).
 * - [resolve]: l'alias di adesso, solo se la persona che resta ha un nome e non e' nascosta.
 *
 * @param index l'indice delle persone montate, null se non sono montate (allora non c'e' nessuno).
 * @param version cio' che cambia quando cambiano le persone o la Libreria visibile (la cache di [named] si butta).
 */
class IndexMemoryPeople(
  private val index: () -> PeopleIndex?,
  private val lookup: PeopleLookup,
  private val version: () -> Any?,
) : MemoryPeople {

  override val available: StateFlow<Boolean> get() = lookup.available

  private val lock = Mutex()
  private var cachedKey: Any? = NONE
  private var cached: List<PersonDigest> = emptyList()

  override suspend fun named(): List<PersonDigest> = lock.withLock {
    if (!available.value) return@withLock emptyList()
    val idx = index() ?: return@withLock emptyList()
    val key = version()
    if (key == cachedKey) return@withLock cached
    val out = ArrayList<PersonDigest>()
    for (person in idx.dictionary().sortedBy { it.id }) {
      if (person.name.isBlank()) continue
      val members = lookup.cells(PersonOption(listOf(person.id), PeopleMode.ANY, ""), includeLikely = false)
      if (members.isNotEmpty()) out += PersonDigest(person.id, members.sortedBy { it.value })
    }
    cachedKey = key
    cached = out
    out
  }

  override suspend fun peopleIn(ids: Collection<MediaId>): Map<MediaId, Set<PersonId>> {
    if (ids.isEmpty()) return emptyMap()
    val wanted = ids.toHashSet()
    val out = HashMap<MediaId, MutableSet<PersonId>>()
    for (digest in named()) {
      for (media in digest.members) if (media in wanted) out.getOrPut(media) { LinkedHashSet() } += digest.person
    }
    return out
  }

  override suspend fun resolve(person: PersonId): PersonId? {
    if (!available.value) return null
    val idx = index() ?: return null
    val now = idx.resolve(person) ?: return null
    return now.takeIf { id -> idx.dictionary().any { it.id == id && it.name.isNotBlank() } }
  }

  override suspend fun label(person: PersonId): String? {
    if (!available.value) return null
    val idx = index() ?: return null
    val now = idx.resolve(person) ?: return null
    return idx.dictionary().firstOrNull { it.id == now }?.name?.takeIf { it.isNotBlank() }
  }

  override fun toString(): String = "IndexMemoryPeople"

  private companion object {
    val NONE = Any()
  }
}

/**
 * Lo stato di Persone per i ricordi (P8, `PeopleSwitch` -> `FacesSwitch`), **dalle persone montate**: prima del montaggio (o senza) e' [PeoplePhase.UNKNOWN],
 * che non cancella mai niente. `Off` vale [PeoplePhase.OFF] solo dopo un giro completo dei dati personali in questo processo (`fresh`): prima potrebbe essere
 * solo un registro locale indietro, e una lapide scritta qui toglierebbe le scelte a tutto l'account. `Erasing` (un diario di cancellazione) vale subito.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FacesPeopleSwitch(faces: FacesGraph, scope: CoroutineScope) : PeopleSwitch {
  override val phase: StateFlow<PeoplePhase> = faces.live.flatMapLatest { live ->
    if (live == null) {
      flowOf(PeoplePhase.UNKNOWN)
    } else {
      val confirmed = live.switch.fresh.scan(false) { seen, fresh -> seen || fresh }
      combine(live.switch.state, confirmed) { state, sure -> phaseOf(state, sure) }
    }
  }.stateIn(scope, SharingStarted.Eagerly, PeoplePhase.UNKNOWN)

  override fun toString(): String = "FacesPeopleSwitch"

  companion object {
    fun phaseOf(state: FacesState, confirmed: Boolean): PeoplePhase = when (state) {
      is FacesState.Erasing -> PeoplePhase.ERASING
      FacesState.Off -> if (confirmed) PeoplePhase.OFF else PeoplePhase.UNKNOWN
      is FacesState.On, is FacesState.AwaitingNotice -> PeoplePhase.ON
      FacesState.NeedsUpdate -> PeoplePhase.UNKNOWN
    }
  }
}
