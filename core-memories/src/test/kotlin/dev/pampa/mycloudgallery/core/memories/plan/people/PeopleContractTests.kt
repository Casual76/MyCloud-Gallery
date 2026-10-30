package dev.pampa.mycloudgallery.core.memories.plan.people

import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesContract
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.PlannerContract
import dev.pampa.mycloudgallery.core.memories.plan.IndexedPlanner
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.plan.visibilityOf
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After

/**
 * Le suite di contratto di P0 con **il generatore delle persone di P8** al posto del riferimento (docs/fase9-progetto.md D3, D11, D12,
 * E-MEM10, E-MEM11, §5.2): il generatore (`PlannerContract`) e il servizio intero (`MemoriesContract`: motore vero, scaffale, scelte di due
 * dispositivi, pulizia). Le stesse prove che girano sul riferimento.
 */
class PeoplePlannerContractTest : PlannerContract() {
  override fun planner(): MemoryPlanner = IndexedPlanner(people = PeoplePlanner())
}

class MemoryEngineWithPeopleContractTest : MemoriesContract() {

  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  @After
  fun chiudeLoScope() {
    scope.cancel()
  }

  override fun memories(parts: Parts): Memories = MemoryEngine(
    library = visibilityOf(parts.visible, parts.mask),
    personal = parts.personal,
    store = parts.store,
    clock = parts.clock,
    picker = ReferencePicker(),
    scope = scope,
    planner = IndexedPlanner(people = PeoplePlanner()),
    trips = parts.trips,
    people = parts.people,
    dispatcher = Dispatchers.Unconfined,
  )
}
