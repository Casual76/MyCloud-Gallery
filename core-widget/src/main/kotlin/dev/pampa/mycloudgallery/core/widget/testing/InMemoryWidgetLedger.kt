package dev.pampa.mycloudgallery.core.widget.testing

import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCellRef
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import java.io.IOException

/**
 * Il "disco" del registro finto: cio' che sopravvive alla morte del processo. Una scrittura passa da un temporaneo e diventa vera solo alla
 * rinomina (come il file atomico di P2): [dieBeforeRename] simula la morte fra le due (la scrittura lancia e il registro committato resta
 * quello di prima); [corrupt] simula un file rovinato (alla riapertura il registro e' vuoto, mai un crollo).
 */
class SimulatedLedgerDisk {
  internal var committed: Map<WidgetSlot, LedgerRow> = emptyMap()
  internal var damaged: Boolean = false

  /** La prossima scrittura muore fra il temporaneo e la rinomina. */
  var dieBeforeRename: Boolean = false

  /** Le scritture arrivate fino alla rinomina. */
  var renames: Int = 0
    internal set

  fun corrupt() {
    damaged = true
  }
}

/** Una riga del registro: l'epoca e le celle del piano (null = un messaggio, nessuna cella). */
internal data class LedgerRow(val epoch: Long, val cells: List<WidgetCellRef>)

/**
 * Il registro in memoria (§5.2): fedele al contratto di [WidgetLedger], sopra un [SimulatedLedgerDisk] condiviso fra un'istanza e la sua
 * "riapertura" dopo una morte. La suite `WidgetLedgerContract` gira qui e su `FileWidgetLedger` di P2 (file troncati e rovinati).
 */
class InMemoryWidgetLedger(private val disk: SimulatedLedgerDisk = SimulatedLedgerDisk()) : WidgetLedger {

  private var rows: Map<WidgetSlot, LedgerRow> = open()

  private fun open(): Map<WidgetSlot, LedgerRow> {
    if (disk.damaged) {
      disk.damaged = false
      disk.committed = emptyMap()
    }
    return disk.committed
  }

  private fun commit(next: Map<WidgetSlot, LedgerRow>) {
    if (disk.dieBeforeRename) {
      disk.dieBeforeRename = false
      throw IOException("morte simulata fra la scrittura del temporaneo e la rinomina")
    }
    disk.committed = next
    disk.renames++
    rows = next
  }

  override suspend fun record(plan: WidgetPlan) {
    val current = rows[plan.slot]
    if (current != null && plan.epoch < current.epoch) return
    val memory = (plan.mode as? WidgetMode.Memory)?.id
    val cells = plan.cells.map { WidgetCellRef(it.media, if (it.index == 0) memory else null) }
    commit(rows + (plan.slot to LedgerRow(plan.epoch, cells)))
  }

  override suspend fun cell(slot: WidgetSlot, index: Int): WidgetCellRef? = rows[slot]?.cells?.getOrNull(index)

  override suspend fun showing(ids: Set<MediaId>): Set<WidgetSlot> =
    rows.filterValues { row -> row.cells.any { it.media in ids } }.keys.toSet()

  override suspend fun showingMemory(id: MemoryId): Set<WidgetSlot> =
    rows.filterValues { row -> row.cells.any { it.memory == id } }.keys.toSet()

  override suspend fun slots(): Set<WidgetSlot> = rows.keys.toSet()

  override suspend fun forget(slot: WidgetSlot) {
    if (slot in rows) commit(rows - slot)
  }

  override suspend fun clear() {
    commit(emptyMap())
  }
}
