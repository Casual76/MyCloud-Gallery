package dev.pampa.mycloudgallery.core.nasops.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.ServerCopyMode
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I difetti che il banco aveva trovato nello stack fake di P0-B (docs/prova-500.md §6), ciascuno con la sua riproduzione minima e
 * deterministica. P12 li ha corretti in `InMemoryNasOperations` e P16 ha tolto le esclusioni da `FakeChaosStackFactory` (`knownDefects`,
 * `unfaultable`, `cancelIsSafe`, `knownDefectHit`): da qui in poi questo test e' una **regressione**, fallisce se uno torna.
 */
class ChaosKnownDefectsTest {

  private val bytes = ByteArray(2_000) { (it * 7).toByte() }

  private fun kit(copyMode: ServerCopyMode = ServerCopyMode.OK): NasOpsTestKit = NasOpsTestKit().also {
    it.putFile("test", "a.jpg", bytes)
    it.nas.serverCopyMode = copyMode
  }

  private suspend fun NasOpsTestKit.moveRequest() =
    OpRequest.Move(listOf(itemOf("test", "a.jpg")), "test-comune", SharePath.parse("F"))

  /** D1: la lettura a flusso del ripiego cade a meta' e l'eccezione esce da start. */
  private fun d1(): Boolean = runBlocking {
    val k = kit(ServerCopyMode.NOT_SUPPORTED)
    k.nas.fault = FakeNasFault { op, _, _ -> if (op == FakeNasOp.READ) NasError.Timeout else null }
    val outcome = runCatching { k.run(k.moveRequest()) }
    outcome.exceptionOrNull() is NasIoException
  }

  /** D2: "Ferma" mentre il temporaneo non si riesce a eliminare lascia il temporaneo e segna l'elemento annullato. */
  private fun d2(): Boolean = runBlocking {
    val k = kit()
    k.nas.opsFaults = FaultPlan().at(1, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.COPY_CHUNK)
    k.run(k.moveRequest())
    k.nas.opsFaults = null
    k.restart()
    val id = k.store.ops(OpState.entries.toSet()).single().id
    check(k.store.items(id).single().state == OpItemState.COPYING) { "scenario di D2 non raggiunto" }
    k.nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.DELETE_ON_CLOSE)
    k.operations.cancel(id)
    k.nas.opsFaults = null
    val cancelled = k.store.items(id).single().state == OpItemState.CANCELLED
    val orphan = k.filesIn("test-comune").any { it.startsWith(".mycloudgallery/tmp/") }
    cancelled && orphan
  }

  /** D3: "Ferma" dopo una pubblicazione la cui risposta si e' persa: copia gia' visibile, sorgente ancora li', operazione annullata. */
  private fun d3(): Boolean = runBlocking {
    val k = kit()
    k.nas.opsFaults = FaultPlan().at(1, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.RENAME)
    k.run(k.moveRequest())
    k.nas.opsFaults = null
    k.restart()
    val id = k.store.ops(OpState.entries.toSet()).single().id
    check(k.store.items(id).single().state == OpItemState.VERIFIED) { "scenario di D3 non raggiunto" }
    k.operations.cancel(id)
    val twoCopies = k.exists("test", "a.jpg") && k.exists("test-comune", "F/a.jpg")
    k.store.op(id)!!.state == OpState.CANCELLED && twoCopies
  }

  /** D4: dopo la risposta persa della pubblicazione, una lettura che cade mentre si controlla la destinazione fa FALLIRE l'elemento. */
  private fun d4(): Boolean = runBlocking {
    val k = kit()
    k.nas.opsFaults = FaultPlan().at(1, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.RENAME)
    k.run(k.moveRequest())
    k.nas.opsFaults = null
    k.restart()
    val id = k.store.ops(OpState.entries.toSet()).single().id
    k.nas.fault = FakeNasFault { op, _, _ -> if (op == FakeNasOp.STAT) NasError.Timeout else null }
    k.operations.resumeAll(5.minutes)
    k.nas.fault = null
    val item = k.store.items(id).single()
    item.state == OpItemState.FAILED && item.errorCode == OpErrorCode.UNEXPECTED && k.exists("test", "a.jpg") && k.exists("test-comune", "F/a.jpg")
  }

  /**
   * O1 (osservazione, non un difetto del fake: e' il contratto): `start` e' idempotente sull'impronta del piano per SEMPRE, anche se l'operazione
   * e' finita. Rinomina a -> b, poi b -> a, poi di nuovo a -> b: il terzo piano e' identico al primo (la rinomina non cambia ne' dimensione ne' data
   * ne' `fileId`) e `start` risponde AlreadyStarted senza fare niente. Il banco evita di generare piani gia' avviati e conta i passi saltati.
   */
  private fun o1(): Boolean = runBlocking {
    val k = kit()
    k.run(OpRequest.Rename(k.itemOf("test", "a.jpg"), "b.jpg"))
    k.run(OpRequest.Rename(k.itemOf("test", "b.jpg"), "a.jpg"))
    val third = k.run(OpRequest.Rename(k.itemOf("test", "a.jpg"), "b.jpg"))
    third is dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome.AlreadyStarted && k.exists("test", "a.jpg") && !k.exists("test", "b.jpg")
  }

  @Test
  fun iDifettiTrovatiDalBancoNelFakeNonSiRiproduconoPiu() {
    val results = linkedMapOf("D1" to d1(), "D2" to d2(), "D3" to d3(), "D4" to d4())
    for ((id, reproduced) in results) assertWithMessage("difetto $id del fake tornato").that(reproduced).isFalse()
    println(if (o1()) "osservazione O1: la stessa rinomina ripetuta dopo un'altra non parte nel fake (start idempotente sull'impronta del piano)" else "osservazione O1: non si riproduce piu'")
    // Senza difetti noti, nessuna esclusione: il banco colpisce tutto anche con lo stack fake.
    assertThat(FakeChaosStackFactory.knownDefects).isEmpty()
    assertThat(FakeChaosStackFactory.unfaultable).isEmpty()
  }
}
