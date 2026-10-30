package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.PlannerContract

/**
 * La suite di contratto del generatore (P0: D3, D5, D9, D11, E-MEM1-E-MEM3, E-MEM12, E-MEM13, E-MEM18) sul generatore vero di P6. I ricordi di
 * persone li da' l'innesto di P8: qui il riferimento ([ReferencePeople]), per provare l'insieme.
 */
class IndexedPlannerContractTest : PlannerContract() {
  override fun planner(): MemoryPlanner = IndexedPlanner(ReferencePeople)
}
