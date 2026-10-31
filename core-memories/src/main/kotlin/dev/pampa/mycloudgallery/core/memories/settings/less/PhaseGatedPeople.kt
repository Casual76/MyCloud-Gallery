package dev.pampa.mycloudgallery.core.memories.settings.less

import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Le persone che **non ci sono piu' appena Persone si spegne o si cancella** (docs/fase9-progetto.md D11, D37, E-MEM10), qualunque sia il ritardo
 * dell'adattatore sotto: [available] e' falso con lo stato [PeoplePhase.gone] anche se [inner] non se n'e' ancora accorto, e le altre risposte
 * (`named`, `peopleIn`, `resolve`, `label`) sono vuote. Cosi' il motore, che chiede a `MemoryPeople` se i ricordi `PERSON` si possono ancora mostrare,
 * li toglie dallo scaffale **nello stesso istante** in cui lo stato cambia, e il generatore non ne fa di nuovi. Con [PeoplePhase.UNKNOWN] e
 * [PeoplePhase.ON] passa tutto com'e'. :app (P13) lo mette fra il motore e `MemoryPeople`, con lo stesso [PeopleSwitch] del janitor.
 */
class PhaseGatedPeople(
  private val inner: MemoryPeople,
  private val switch: PeopleSwitch,
  scope: CoroutineScope,
) : MemoryPeople {

  private val gone: Boolean get() = switch.phase.value.gone

  /** MO-1: lo stato di Persone non e' ancora noto ([PeoplePhase.UNKNOWN]): lo scaffale non si pota per cio' che dipende da Persone. */
  override val undecided: Boolean get() = switch.phase.value == PeoplePhase.UNKNOWN

  override val available: StateFlow<Boolean> = combine(inner.available, switch.phase) { available, phase -> available && !phase.gone }
    .stateIn(scope, SharingStarted.Eagerly, inner.available.value && !switch.phase.value.gone)

  override suspend fun named(): List<PersonDigest> = if (gone) emptyList() else inner.named()

  override suspend fun peopleIn(ids: Collection<MediaId>): Map<MediaId, Set<PersonId>> = if (gone) emptyMap() else inner.peopleIn(ids)

  override suspend fun resolve(person: PersonId): PersonId? = if (gone) null else inner.resolve(person)

  override suspend fun label(person: PersonId): String? = if (gone) null else inner.label(person)

  override fun toString(): String = "PhaseGatedPeople"
}
