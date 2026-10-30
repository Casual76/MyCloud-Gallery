package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesContract
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After

/**
 * La suite di contratto del servizio dei ricordi (P0: D3, D4, D9-D11, D31, E-MEM1, E-MEM5-E-MEM10, E-MEM12, E-H8, R17) sul **motore vero**
 * di P6, con lo scaffale in memoria, le scelte finte di due dispositivi e la scelta delle foto di riferimento. Le stesse prove del riferimento.
 */
class MemoryEngineContractTest : MemoriesContract() {

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
    planner = IndexedPlanner(ReferencePeople),
    trips = parts.trips,
    people = parts.people,
    dispatcher = Dispatchers.Unconfined,
  )
}
