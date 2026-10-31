package dev.pampa.mycloudgallery.core.widget.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.core.widget.testing.SimulatedLedgerDisk
import java.io.IOException
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto del registro del widget (Fase 10, P0; `docs/fase10-progetto.md` D5, D9, D42, §4.3 "Stabilita'"). Gira sul registro in
 * memoria e, da P2, su `FileWidgetLedger` (cartella temporanea, file troncati e rovinati, morte del processo fra scrittura e rinomina).
 */
abstract class WidgetLedgerContract {

  interface Rig {
    /** Il registro aperto adesso. */
    val ledger: WidgetLedger

    /** Riapre il registro dal disco, come dopo la morte del processo. */
    fun reopen(): WidgetLedger

    /** La prossima scrittura muore fra il temporaneo e la rinomina. */
    fun dieDuringNextWrite()

    /** Il file si rovina (byte cambiati, troncato, CRC sbagliato). */
    fun corrupt()
  }

  protected abstract fun rig(): Rig

  private fun id(n: Int) = MediaId(UUID(10L, n.toLong()).toString())

  private val memory = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 9)))

  private fun photos(slot: Int, epoch: Long, vararg media: Int) = WidgetPlan(
    WidgetSlot(slot), WidgetSize.LARGE, WidgetMode.Photos, media.mapIndexed { i, m -> WidgetCell(i, id(m), MediaKind.PHOTO, 20261031) }, epoch,
  )

  private fun memoryPlan(slot: Int, epoch: Long, vararg media: Int) = WidgetPlan(
    WidgetSlot(slot), WidgetSize.WIDE, WidgetMode.Memory(memory, WidgetTitle(1, listOf(3_100_000), null, null)),
    media.mapIndexed { i, m -> WidgetCell(i, id(m), MediaKind.PHOTO, 20251031) }, epoch,
  )

  @Test
  fun registraECercaLeCelle() = runTest {
    val rig = rig()
    rig.ledger.record(photos(1, 1, 10, 11, 12))
    rig.ledger.record(memoryPlan(2, 1, 20, 21))
    assertThat(rig.ledger.cell(WidgetSlot(1), 2)).isEqualTo(WidgetCellRef(id(12), null))
    assertThat(rig.ledger.cell(WidgetSlot(1), 3)).isNull()
    assertThat(rig.ledger.cell(WidgetSlot(9), 0)).isNull()
    assertThat(rig.ledger.cell(WidgetSlot(2), 0)).isEqualTo(WidgetCellRef(id(20), memory))
    assertThat(rig.ledger.cell(WidgetSlot(2), 1)).isEqualTo(WidgetCellRef(id(21), null))
    assertThat(rig.ledger.showing(setOf(id(11), id(99)))).containsExactly(WidgetSlot(1))
    assertThat(rig.ledger.showingMemory(memory)).containsExactly(WidgetSlot(2))
    assertThat(rig.ledger.slots()).containsExactly(WidgetSlot(1), WidgetSlot(2))
  }

  @Test
  fun unPianoNuovoSostituisceQuelloVecchioMaiIlContrario() = runTest {
    val rig = rig()
    rig.ledger.record(photos(1, 5, 10))
    rig.ledger.record(photos(1, 6, 11))
    assertThat(rig.ledger.cell(WidgetSlot(1), 0)?.media).isEqualTo(id(11))
    rig.ledger.record(photos(1, 4, 12))
    assertThat(rig.ledger.cell(WidgetSlot(1), 0)?.media).isEqualTo(id(11))
    rig.ledger.record(WidgetPlan(WidgetSlot(1), WidgetSize.SMALL, WidgetMode.Message(WidgetEmpty.NOT_CONFIGURED), emptyList(), 7))
    assertThat(rig.ledger.cell(WidgetSlot(1), 0)).isNull()
    assertThat(rig.ledger.showing(setOf(id(11)))).isEmpty()
  }

  @Test
  fun dimenticaEAzzera() = runTest {
    val rig = rig()
    rig.ledger.record(photos(1, 1, 10))
    rig.ledger.record(photos(2, 1, 11))
    rig.ledger.forget(WidgetSlot(1))
    assertThat(rig.ledger.slots()).containsExactly(WidgetSlot(2))
    rig.ledger.clear()
    assertThat(rig.ledger.slots()).isEmpty()
    assertThat(rig.reopen().slots()).isEmpty()
  }

  @Test
  fun sopravviveAllaMorteDelProcesso() = runTest {
    val rig = rig()
    rig.ledger.record(photos(1, 1, 10))
    assertThat(rig.reopen().cell(WidgetSlot(1), 0)?.media).isEqualTo(id(10))
    rig.dieDuringNextWrite()
    assertThat(runCatching { rig.ledger.record(photos(1, 2, 11)) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    val reopened = rig.reopen()
    assertThat(reopened.cell(WidgetSlot(1), 0)?.media).isEqualTo(id(10))
  }

  @Test
  fun unFileRovinatoEUnRegistroVuotoMaiUnCrollo() = runTest {
    val rig = rig()
    rig.ledger.record(photos(1, 1, 10))
    rig.corrupt()
    val reopened = rig.reopen()
    assertThat(reopened.slots()).isEmpty()
    reopened.record(photos(1, 1, 12))
    assertThat(reopened.cell(WidgetSlot(1), 0)?.media).isEqualTo(id(12))
  }
}

class InMemoryWidgetLedgerContractTest : WidgetLedgerContract() {
  override fun rig(): Rig = object : Rig {
    private val disk = SimulatedLedgerDisk()
    override val ledger: WidgetLedger = InMemoryWidgetLedger(disk)

    override fun reopen(): WidgetLedger = InMemoryWidgetLedger(disk)

    override fun dieDuringNextWrite() {
      disk.dieBeforeRename = true
    }

    override fun corrupt() = disk.corrupt()
  }
}
