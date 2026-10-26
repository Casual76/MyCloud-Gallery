package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.engine.MoveCompletions
import dev.pampa.mycloudgallery.core.nasops.engine.completionPortOf
import dev.pampa.mycloudgallery.core.nasops.noop.NoMoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 7 P4 (D29; riga P4 del §4.4: "MoveCompletionSink chiamata prima di SOURCE_RECORDED e idempotente: test sul motore"). La porta di
 * completamento nei due esecutori (quello vero di P12 e quello di riferimento: stesse chiamate, §5.2):
 *  - si chiama **una volta per file** spostato tra share, quando l'elemento e' `PUBLISHED`: la copia verificata e' a destinazione (con il nome
 *    alternativo, se quello scelto era occupato), la sorgente e' ancora al suo posto e nessuna voce del cestino e' ancora scritta;
 *  - il timbro e' quello della destinazione letto adesso, l'impronta quella verificata;
 *  - **vincolante**: finche' non risponde senza errori l'elemento resta in `PUBLISHED`, la sorgente non va nel cestino e l'operazione aspetta
 *    ("condizioni") senza fermare le altre; una ripresa la richiama con gli stessi argomenti (idempotenza di chi la lega);
 *  - con un guasto a **ogni** chiamata al NAS e con la morte del processo a **ogni** passo (diario, NAS, e la porta stessa, prima e dopo che il
 *    catalogo si sia aggiornato) la sorgente non e' **mai** nel cestino senza che la porta abbia risposto per quell'elemento.
 */
class CompletionSinkEngineTest {

  private fun content(n: Int) = ByteArray(7 + n) { (n * 37 + it).toByte() }

  /** Una chiamata della porta, con lo stato del mondo in quel momento. */
  private data class Call(
    val op: OpId,
    val index: Int,
    val source: NasFileRef,
    val dest: NasFileRef,
    val stamp: NasStamp,
    val sha: Sha256,
    val stateAtCall: OpItemState?,
    val sourceThere: Boolean,
    val destBytes: List<Byte>?,
    val trashEntryWritten: Boolean,
  )

  /** Il catalogo finto: ricorda le chiamate e quali elementi ha "collegato" (risposta senza errori). */
  private class Recorder(var failFor: (NasFileRef) -> Boolean = { false }) : MoveCompletionSink {
    lateinit var kit: NasOpsTestKit
    val calls = ArrayList<Call>()
    val acked = LinkedHashMap<Pair<OpId, Int>, NasFileRef>()

    override suspend fun published(op: OpId, index: Int, source: NasFileRef, dest: NasFileRef, stamp: NasStamp, sha256: Sha256) {
      val item = kit.store.items(op).firstOrNull { it.index == index }
      calls += Call(
        op, index, source, dest, stamp, sha256,
        stateAtCall = item?.state,
        sourceThere = kit.exists(source.share, source.path.value),
        destBytes = kit.bytes(dest.share, dest.path.value)?.toList(),
        trashEntryWritten = item?.trashId?.let { kit.store.trashEntry(it) } != null,
      )
      if (failFor(source)) error("catalogo chiuso")
      acked[op to index] = dest
    }
  }

  private suspend fun NasOpsTestKit.planOf(request: OpRequest): OpPlan =
    (operations.plan(request) as? PlanOutcome.Ready)?.plan ?: error("piano rifiutato: ${operations.plan(request)}")

  /** Lo scenario comune: un file con il nome occupato a destinazione e una cartella con due file, dall'area privata a quella comune. */
  private suspend fun NasOpsTestKit.scenario(): OpRequest {
    putFile("test", "V/0.jpg", content(1))
    putFile("test", "V/Gita/1.jpg", content(2))
    putFile("test", "V/Gita/Mare/2.jpg", content(3))
    putFile("test-comune", "F/0.jpg", content(4))
    return OpRequest.Move(listOf(itemOf("test", "V/0.jpg"), itemOf("test", "V/Gita")), "test-comune", SharePath.parse("F"))
  }

  @Test
  fun laPortaSiChiamaDopoLaPubblicazioneEPrimaDelCestinoSuEntrambiGliEsecutori() = runTest {
    val seen = HashMap<KitEngine, List<String>>()
    for (engine in KitEngine.entries) {
      val recorder = Recorder()
      val kit = NasOpsTestKit(engine = engine, completions = recorder).also { recorder.kit = it }
      val plan = kit.planOf(kit.scenario())
      kit.operations.start(plan, kit.confirm(plan))
      kit.resumeUntilQuiet()
      val label = engine.name
      // Una chiamata per file (non per le cartelle), con il nome alternativo se quello scelto era occupato.
      assertWithMessage(label).that(recorder.calls.map { it.source.path.value to it.dest.path.value }).containsExactly(
        "V/0.jpg" to "F/0 (2).jpg", "V/Gita/1.jpg" to "F/Gita/1.jpg", "V/Gita/Mare/2.jpg" to "F/Gita/Mare/2.jpg",
      )
      for (call in recorder.calls) {
        assertWithMessage("$label: passo al momento della chiamata").that(call.stateAtCall).isEqualTo(OpItemState.PUBLISHED)
        assertWithMessage("$label: sorgente ancora al suo posto").that(call.sourceThere).isTrue()
        assertWithMessage("$label: nessuna voce del cestino prima della porta").that(call.trashEntryWritten).isFalse()
        val bytes = call.destBytes
        assertWithMessage("$label: la copia e' a destinazione").that(bytes).isNotNull()
        assertWithMessage("$label: impronta verificata").that(call.sha).isEqualTo(Sha256.of(bytes!!.toByteArray()))
        assertWithMessage("$label: timbro della destinazione").that(call.stamp.size).isEqualTo(bytes.size.toLong())
        assertWithMessage(label).that(call.source.share).isEqualTo("test")
        assertWithMessage(label).that(call.dest.share).isEqualTo("test-comune")
      }
      // E poi lo spostamento finisce come sempre: le sorgenti nel cestino, l'operazione chiusa.
      assertWithMessage(label).that(kit.store.allTrash().filter { it.state == TrashEntryState.TRASHED }).hasSize(3)
      assertWithMessage(label).that(kit.store.ops(OpState.entries.toSet()).map { it.state }).containsExactly(OpState.DONE)
      seen[engine] = recorder.calls.map { "${it.index} ${it.source.path.value} ${it.dest.path.value} ${it.sha.hex}" }
    }
    // §5.2: le stesse chiamate dal riferimento e dal vero.
    assertThat(seen[KitEngine.REFERENCE]).containsExactlyElementsIn(seen[KitEngine.PRODUCTION])
  }

  @Test
  fun unaPortaCheNonRispondeFermaLElementoMaNonLeAltreOperazioni() = runTest {
    for (engine in KitEngine.entries) {
      val label = engine.name
      val recorder = Recorder(failFor = { it.path.value == "A/a.jpg" })
      val kit = NasOpsTestKit(engine = engine, completions = recorder).also { recorder.kit = it }
      kit.putFile("test", "A/a.jpg", content(10))
      kit.putFile("test", "B/b.jpg", content(11))
      val first = kit.planOf(OpRequest.Move(listOf(kit.itemOf("test", "A/a.jpg")), "test-comune", SharePath.parse("F")))
      kit.operations.start(first, kit.confirm(first))
      // L'elemento resta PUBLISHED: copia a destinazione, sorgente al suo posto, nessuna voce del cestino, operazione in attesa.
      val held = kit.store.opByPlanHash(first.hash)!!
      assertWithMessage(label).that(kit.store.items(held.id).single().state).isEqualTo(OpItemState.PUBLISHED)
      assertWithMessage(label).that(kit.exists("test", "A/a.jpg")).isTrue()
      assertWithMessage(label).that(kit.exists("test-comune", "F/a.jpg")).isTrue()
      assertWithMessage(label).that(kit.store.allTrash()).isEmpty()
      assertWithMessage(label).that(kit.store.op(held.id)!!.state).isEqualTo(OpState.PAUSED)
      assertWithMessage(label).that(kit.store.op(held.id)!!.pauseReason).isEqualTo(PauseReason.CONDITIONS)
      val shown = kit.operations.operations.value.single { it.id == held.id }
      assertWithMessage(label).that((shown as NasOperationState.Paused).reason).isEqualTo(PauseReason.CONDITIONS)

      // Un'altra operazione ferma per la rete: la ripresa la porta in fondo anche se la prima aspetta ancora il catalogo.
      val second = kit.planOf(OpRequest.Move(listOf(kit.itemOf("test", "B/b.jpg")), "test-comune", SharePath.parse("G")))
      val fault = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection)
      kit.nas.opsFaults = fault
      kit.nas.readFaults = fault
      kit.operations.start(second, kit.confirm(second))
      kit.nas.opsFaults = null
      kit.nas.readFaults = null
      assertWithMessage("$label: il guasto e' scattato").that(fault.fired).hasSize(1)
      val run = kit.operations.resumeAll(1.hours)
      assertWithMessage(label).that(run.stoppedBy).isEqualTo(RunStop.BLOCKED)
      assertWithMessage(label).that(kit.store.op(kit.store.opByPlanHash(second.hash)!!.id)!!.state).isEqualTo(OpState.DONE)
      assertWithMessage(label).that(kit.exists("test", "A/a.jpg")).isTrue()

      // Il catalogo risponde: la ripresa richiama la porta con gli stessi argomenti e lo spostamento finisce.
      recorder.failFor = { false }
      kit.restart()
      kit.resumeUntilQuiet()
      assertWithMessage(label).that(kit.exists("test", "A/a.jpg")).isFalse()
      assertWithMessage(label).that(kit.store.op(held.id)!!.state).isEqualTo(OpState.DONE)
      val forA = recorder.calls.filter { it.source.path.value == "A/a.jpg" }
      assertWithMessage(label).that(forA.size).isAtLeast(3)
      assertWithMessage("$label: idempotente, stessi argomenti").that(forA.map { Triple(it.dest, it.sha, it.stamp) }.toSet()).hasSize(1)
    }
  }

  @Test
  fun unaSolaPortaDiCompletamento() {
    val sink = MoveCompletionSink { _, _, _, _, _, _ -> }
    val moves = MoveCompletions { _, _, _, _, _ -> }
    assertThat(completionPortOf(sink, MoveCompletions.NONE)).isSameInstanceAs(sink)
    assertThat(completionPortOf(NoMoveCompletionSink, MoveCompletions.NONE)).isSameInstanceAs(NoMoveCompletionSink)
    assertThat(completionPortOf(NoMoveCompletionSink, moves)).isNotSameInstanceAs(NoMoveCompletionSink)
    assertThrows(IllegalArgumentException::class.java) { completionPortOf(sink, moves) }
  }

  // --- Guasti a ogni chiamata e morte a ogni passo -----------------------------------------------------------------------------------

  /** La sorgente e' nel cestino solo per gli elementi che la porta ha gia' collegato; ogni chiamata e' avvenuta con la sorgente al suo posto. */
  private suspend fun NasOpsTestKit.neverTrashedBeforeAck(recorder: Recorder, label: String) {
    for (entry in store.allTrash().filter { it.state == TrashEntryState.TRASHED }) {
      val op = entry.opId ?: continue
      val item = store.items(op).first { it.trashId == entry.trashId }
      assertWithMessage("$label: sorgente nel cestino senza la porta (#${item.index})").that(recorder.acked.keys).contains(op to item.index)
    }
    for (call in recorder.calls) {
      assertWithMessage("$label: porta chiamata senza la sorgente").that(call.sourceThere).isTrue()
      assertWithMessage("$label: porta chiamata fuori da PUBLISHED").that(call.stateAtCall).isEqualTo(OpItemState.PUBLISHED)
    }
    // Idempotenza: ogni elemento richiamato (ripresa) ha sempre gli stessi argomenti.
    for ((_, calls) in recorder.calls.groupBy { it.op to it.index }) {
      assertWithMessage("$label: argomenti diversi a una ripresa").that(calls.map { Triple(it.source, it.dest, it.sha) }.toSet()).hasSize(1)
    }
  }

  private suspend fun NasOpsTestKit.allAcked(recorder: Recorder, label: String) {
    val ops = store.ops(OpState.entries.toSet())
    assertWithMessage("$label: operazioni").that(ops.map { it.state }.toSet()).containsExactly(OpState.DONE)
    for (op in ops) for (item in store.items(op.id).filter { !it.isFolder }) {
      assertWithMessage("$label: elemento senza porta").that(recorder.acked[op.id to item.index]).isEqualTo(NasFileRef(item.dstShare!!, item.dstPath!!))
    }
  }

  @Test
  fun conUnGuastoAOgniChiamataLaSorgenteNonVaMaiNelCestinoPrimaDellaPorta() = runTest {
    val shares = listOf(NasOpsTestKit.PRIVATE_SHARE, NasOpsTestKit.COMMON_SHARE)
    val counterKit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
    val counterPlan = counterKit.planOf(counterKit.scenario())
    val counter = FaultPlan()
    counterKit.nas.opsFaults = counter
    counterKit.nas.readFaults = counter
    counterKit.operations.start(counterPlan, counterKit.confirm(counterPlan))
    val total = counter.operations
    assertThat(total).isAtLeast(10)
    for (n in 1..total) for (phase in FaultPhase.entries) {
      val recorder = Recorder()
      val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION, completions = recorder).also { recorder.kit = it }
      val plan = kit.planOf(kit.scenario())
      val originals = Originals.of(kit, shares)
      val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
      kit.nas.opsFaults = fault
      kit.nas.readFaults = fault
      kit.operations.start(plan, kit.confirm(plan))
      kit.nas.opsFaults = null
      kit.nas.readFaults = null
      val label = "guasto alla chiamata $n/$total $phase"
      assertWithMessage("$label: scattato").that(fault.fired).hasSize(1)
      kit.neverTrashedBeforeAck(recorder, "$label (a meta')")
      kit.checkInvariants(originals, shares, "$label (a meta')", final = false)
      kit.restart()
      kit.resumeUntilQuiet()
      kit.neverTrashedBeforeAck(recorder, label)
      kit.checkInvariants(originals, shares, label, final = true)
      kit.allAcked(recorder, label)
    }
  }

  @Test
  fun conLaMorteAOgniPassoComprendendoLaPortaNienteSiPerdeENienteVaNelCestinoPrima() = runTest {
    val shares = listOf(NasOpsTestKit.PRIVATE_SHARE, NasOpsTestKit.COMMON_SHARE)
    val countKit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
    val countPlan = countKit.planOf(countKit.scenario())
    val counter = Death()
    val countRecorder = Recorder().also { it.kit = countKit }
    countKit.engineWith(counter, completions = MoveCompletionSink { op, i, s, d, st, sha -> counter.around { countRecorder.published(op, i, s, d, st, sha) } })
      .start(countPlan, countKit.confirm(countPlan))
    val total = counter.steps
    assertThat(countRecorder.calls).hasSize(3)
    for (n in 1..total) for (after in listOf(false, true)) {
      val recorder = Recorder()
      val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION, completions = recorder).also { recorder.kit = it }
      val plan = kit.planOf(kit.scenario())
      val originals = Originals.of(kit, shares)
      val death = Death(n, after)
      val dyingSink = MoveCompletionSink { op, i, s, d, st, sha -> death.around { recorder.published(op, i, s, d, st, sha) } }
      try {
        kit.engineWith(death, completions = dyingSink).start(plan, kit.confirm(plan))
      } catch (_: ProcessDeath) {
        // il processo e' morto: si riparte dal diario
      }
      val label = "morte al passo $n/$total ${if (after) "dopo" else "prima"}"
      assertWithMessage("$label: morte avvenuta").that(death.died).isTrue()
      kit.neverTrashedBeforeAck(recorder, "$label (a meta')")
      kit.checkInvariants(originals, shares, "$label (a meta')", final = false)
      kit.restart()
      if (kit.store.opByPlanHash(plan.hash) == null) kit.operations.start(plan, kit.confirm(plan))
      kit.resumeUntilQuiet()
      kit.neverTrashedBeforeAck(recorder, label)
      kit.checkInvariants(originals, shares, label, final = true)
      kit.allAcked(recorder, label)
    }
  }
}
