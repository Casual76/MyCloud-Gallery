package dev.pampa.mycloudgallery.core.nasops.p12

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nasops.PlanFixtures
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptedPlan
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationGate
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpTemps
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.store.RoomOpsStore
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il diario su Room ([RoomOpsStore], schema v3) con le regole del fake di riferimento: una transazione per l'operazione e i suoi elementi,
 * le transizioni fuori dalla macchina a stati rifiutate, la voce del cestino solo da un'operazione e mai sovrascritta; e la morte del
 * processo **vera** (il database su file chiuso e riaperto) a ogni passo di uno spostamento tra share.
 */
@RunWith(RobolectricTestRunner::class)
class RoomOpsStoreTest {

  private val opened = ArrayList<GalleryDatabase>()
  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private fun database(name: String? = null): GalleryDatabase = GalleryDatabase.build(context, name).also { opened += it }

  @After
  fun close() {
    opened.forEach { it.close() }
    opened.clear()
  }

  private fun accepted(plan: OpPlan): AcceptedPlan =
    (ConfirmationGate.accept(plan, TestConfirmations.forPlan(plan, 1_000), 1_000) as AcceptOutcome.Accepted).accepted

  @Test
  fun lOperazioneEGliElementiFannoIlGiroInUnaTransazioneEIdempotenti() = runBlocking<Unit> {
    val store = RoomOpsStore(database())
    val plan = PlanFixtures.move("Foto/a.jpg", "Famiglia/a.jpg")
    val id = OpId(PlanFixtures.id(40))
    val op = store.create(accepted(plan), id, "test", 5_000)
    assertThat(op.state).isEqualTo(OpState.PLANNED)
    assertThat(op.planHash).isEqualTo(plan.hash)
    // Lo stesso piano: la stessa operazione, nessun doppione.
    assertThat(store.create(accepted(plan), OpId(PlanFixtures.id(41)), "test", 6_000).id).isEqualTo(id)
    assertThat(store.opByPlanHash(plan.hash)!!.id).isEqualTo(id)
    val item = store.items(id).single()
    assertThat(item.srcPath).isEqualTo(SharePath.parse("Foto/a.jpg"))
    assertThat(item.stamp).isEqualTo(NasStamp(10, 1_000, 5))
    assertThat(item.trashId).isEqualTo(PlanFixtures.id(9))

    // Ogni campo di un passo torna uguale (temporaneo, impronta, timbro verificato, attesa, errore).
    val temp = OpTemps.tempFor(PlanFixtures.me, id, 0)
    val copying = item.copy(state = OpItemState.COPYING, tempName = temp, updatedAtNas = 7_000)
    store.updateItem(copying)
    val copied = copying.copy(state = OpItemState.COPIED, attempts = 2, nextAttemptAtNas = 9_000, errorCode = OpErrorCode.LOCKED)
    store.updateItem(copied)
    val verified = copied.copy(state = OpItemState.VERIFIED, sha256 = Sha256("ab".repeat(32)), stamp = NasStamp(10, 1_000, 5), nextAttemptAtNas = null, errorCode = null)
    store.updateItem(verified)
    assertThat(RoomOpsStore(opened.single()).items(id).single()).isEqualTo(verified)
    assertThat(store.itemsToResume(10_000, 10).single()).isEqualTo(verified)
  }

  @Test
  fun unaTransizioneFuoriDallaMacchinaAStatiERifiutataENonScrittaNiente() = runBlocking<Unit> {
    val store = RoomOpsStore(database())
    val plan = PlanFixtures.trash(2)
    val id = OpId(PlanFixtures.id(42))
    store.create(accepted(plan), id, "test", 5_000)
    val item = store.items(id).first()
    assertThrows(IllegalArgumentException::class.java) { runBlocking { store.updateItem(item.copy(state = OpItemState.TRASHED)) } }
    assertThrows(IllegalArgumentException::class.java) { runBlocking { store.updateItem(item.copy(state = OpItemState.COPYING)) } }
    // Un elemento non cambia identita' (percorso, share).
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { store.updateItem(item.copy(state = OpItemState.RECORDED, srcPath = SharePath.parse("Altro/x.jpg"))) }
    }
    assertThat(RoomOpsStore(opened.single()).items(id).first().state).isEqualTo(OpItemState.PLANNED)
    store.updateItem(item.copy(state = OpItemState.RECORDED))
    store.updateItem(item.copy(state = OpItemState.FAILED, errorCode = OpErrorCode.SOURCE_CHANGED))
    // Da uno stato terminale non si esce.
    assertThrows(IllegalArgumentException::class.java) { runBlocking { store.updateItem(item.copy(state = OpItemState.RECORDED)) } }
  }

  @Test
  fun ilDiarioDelCestinoNasceSoloDaUnOperazioneENonSiSovrascrive() = runBlocking<Unit> {
    val store = RoomOpsStore(database())
    val plan = PlanFixtures.trash(1)
    val id = OpId(PlanFixtures.id(43))
    store.create(accepted(plan), id, "test", 5_000)
    fun entry(trashId: String, opId: OpId?, at: Long) = LocalTrashRecord(
      trashId, "test", "test", "test", SharePath.parse("Foto/f0.jpg"), NasLayout.trashItemFile(PlanFixtures.me, trashId),
      TrashReason.DELETE, false, 10, null, at, TrashEntryState.TRASHED, opId, at,
    )
    assertThrows(IllegalArgumentException::class.java) { runBlocking { store.recordTrash(entry(PlanFixtures.id(1), null, 5_000)) } }
    assertThrows(IllegalArgumentException::class.java) { runBlocking { store.recordTrash(entry(PlanFixtures.id(1), OpId(PlanFixtures.id(99)), 5_000)) } }
    store.recordTrash(entry(PlanFixtures.id(1), id, 5_000))
    // Una ripresa riscrive la voce: vince la prima (l'ora del cestino non si sposta in avanti).
    store.recordTrash(entry(PlanFixtures.id(1), id, 8_000))
    assertThat(store.trashEntry(PlanFixtures.id(1))!!.trashedAtNas).isEqualTo(5_000)
    assertThat(store.expiredTrash(5_001, 10).map { it.trashId }).containsExactly(PlanFixtures.id(1))
    assertThat(store.expiredTrash(5_000, 10)).isEmpty()
    assertThat(store.observeTrash().first()).hasSize(1)
    store.markTrash(PlanFixtures.id(1), TrashEntryState.PURGED, 9_000)
    assertThat(store.expiredTrash(Long.MAX_VALUE, 10)).isEmpty()
    assertThat(store.observeTrash().first()).isEmpty()
    assertThat(store.trashEntry(PlanFixtures.id(1))!!.state).isEqualTo(TrashEntryState.PURGED)
  }

  @Test
  fun laMorteDelProcessoVeraAOgniPassoDiUnoSpostamentoTraShare() = runBlocking<Unit> {
    // Conteggio pulito (stesso scenario, diario Room in memoria).
    val counter = Death()
    run {
      val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
      val plan = setup(kit)
      engine(kit, RoomOpsStore(database()), counter).start(plan, kit.confirm(plan))
    }
    val steps = counter.steps
    assertThat(steps).isAtLeast(10)
    for (n in 1..steps) {
      for (after in listOf(false, true)) {
        val label = "Room, morte al passo $n/$steps ${if (after) "dopo" else "prima"}"
        val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
        val plan = setup(kit)
        val originals = Originals.of(kit, listOf("test", "test-comune"))
        val file = "nasops-$n-$after.db"
        context.deleteDatabase(file)
        val first = database(file)
        val death = Death(n, after)
        try {
          engine(kit, RoomOpsStore(first), death).start(plan, kit.confirm(plan))
        } catch (_: ProcessDeath) {
          // il processo muore: il database si chiude com'e'
        }
        assertWithMessage(label).that(death.died).isTrue()
        first.close()
        // Un processo nuovo: database riaperto dal file, oggetti nuovi.
        val second = RoomOpsStore(database(file))
        val fresh = engine(kit, second, Death())
        if (second.opByPlanHash(plan.hash) == null) fresh.start(plan, kit.confirm(plan))
        repeat(4) { fresh.resumeAll(5.minutes) }
        val ops = second.ops(OpState.entries.toSet())
        assertWithMessage(label).that(ops.map { it.state }).containsExactly(OpState.DONE)
        assertWithMessage(label).that(second.items(ops.single().id).map { it.state }.toSet()).containsExactly(OpItemState.DONE)
        assertWithMessage(label).that(kit.filesIn("test-comune").filterNot { it.startsWith(".mycloudgallery") }).containsExactly("F/a.jpg", "F/b.jpg")
        assertWithMessage(label).that(kit.filesIn("test-comune").filter { it.startsWith(".mycloudgallery/tmp") }).isEmpty()
        val trashed = second.expiredTrash(Long.MAX_VALUE, 10)
        assertWithMessage(label).that(trashed).hasSize(2)
        for (entry in trashed) assertWithMessage(label).that(kit.exists("test", entry.trashedPath.value)).isTrue()
        checkFinal(kit, originals, label)
        context.deleteDatabase(file)
      }
    }
    println("P12 diario Room: $steps passi x 2 con il database chiuso e riaperto")
  }

  private suspend fun setup(kit: NasOpsTestKit): OpPlan {
    kit.putFile("test", "V/a.jpg", byteArrayOf(1, 2, 3))
    kit.putFile("test", "V/b.jpg", byteArrayOf(4, 5))
    val request = OpRequest.Move(listOf(kit.itemOf("test", "V/a.jpg"), kit.itemOf("test", "V/b.jpg")), "test-comune", SharePath.parse("F"))
    return (kit.operations.plan(request) as PlanOutcome.Ready).plan
  }

  private fun engine(kit: NasOpsTestKit, store: RoomOpsStore, death: Death) = NasOpsEngine(
    deviceId = kit.deviceId,
    accountId = kit.accountId,
    fileOps = DyingFileOps(kit.fileOps, death),
    reader = DyingReader(kit.nas.reader, death),
    store = DyingStore(store, death),
    clocks = kit.clocks,
    gate = kit.gate,
    lock = NasOpsLock(),
  )

  private fun checkFinal(kit: NasOpsTestKit, originals: Originals, label: String) {
    val visible = (kit.filesIn("test") + kit.filesIn("test-comune")).filterNot { it.startsWith(".mycloudgallery") }
    assertWithMessage(label).that(visible).hasSize(originals.byContent.size)
  }
}
