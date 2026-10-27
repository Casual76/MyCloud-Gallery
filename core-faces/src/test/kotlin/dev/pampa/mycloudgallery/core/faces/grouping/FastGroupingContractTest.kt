package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.GroupingContract
import dev.pampa.mycloudgallery.core.faces.contract.GroupingEngine

/** La suite di contratto di P0 (§2.5, §5.2) sul nucleo vero di P5. */
class FastGroupingContractTest : GroupingContract() {
  override fun engine(): GroupingEngine = FastGrouping()
}
