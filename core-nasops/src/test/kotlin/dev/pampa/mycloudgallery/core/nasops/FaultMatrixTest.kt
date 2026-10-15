package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nasops.contract.LeaveBehind
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Un assaggio della prova delle 500 interruzioni (§5.5, P15): per ogni operazione, un guasto "prima" e "dopo" che il NAS abbia applicato
 * **ciascuna** primitiva (il numero si conta con un giro pulito), poi la morte del processo (`restart`: oggetti nuovi, stesso diario,
 * stesso NAS) e la ripresa dal diario. Gli invarianti: nessun file a meta' visibile, nessun doppione, nessuna perdita, nessun
 * temporaneo rimasto. P15 lo estende a migliaia di giri casuali con l'esecutore vero; qui prova che il fake e i contratti reggono
 * lo stesso schema.
 */
open class FaultMatrixTest {

  /** L'esecutore provato: quello di riferimento (P0-B) qui, quello vero (P12) in [ProductionFaultMatrixTest]. */
  protected open fun engine(): KitEngine = KitEngine.REFERENCE

  private val contents = listOf(byteArrayOf(1, 1, 1, 1), byteArrayOf(2, 2, 2), byteArrayOf(3, 3, 3, 3, 3))

  private fun NasOpsTestKit.visible(share: String) = filesIn(share).filterNot { it.startsWith(".mycloudgallery") }

  private fun NasOpsTestKit.appFiles(share: String) = filesIn(share).filter { it.startsWith(".mycloudgallery") }

  private fun NasOpsTestKit.trashedBytes(): List<List<Byte>> =
    store.allTrash().filter { it.state == TrashEntryState.TRASHED }.map { bytes("test", it.trashedPath.value)!!.toList() }

  /** Un caso: come preparare il NAS e cosa chiedere, e cosa deve essere vero alla fine (con o senza guasti). */
  private class Case(
    val name: String,
    val setup: suspend (NasOpsTestKit) -> OpRequest,
    val check: (NasOpsTestKit, String) -> Unit,
  )

  /** Pianifica con un NAS sano, installa [fault], avvia, "muore", riprende. */
  private suspend fun NasOpsTestKit.scenario(request: OpRequest, fault: FaultPlan) {
    val plan = (operations.plan(request) as PlanOutcome.Ready).plan
    nas.opsFaults = fault
    operations.start(plan, confirm(plan))
    nas.opsFaults = null
    restart()
    // Dopo la morte del processo si riprende dal diario; lo stato mostrato si ricostruisce da li'.
    operations.resumeAll(5.minutes)
    var rounds = 0
    while (operations.operations.value.any { it.active } && rounds++ < 5) operations.resumeAll(5.minutes)
  }

  /** Dopo la ripresa ogni operazione e ogni elemento sono finiti bene: un'operazione riuscita sul NAS non deve risultare fallita nel diario. */
  private suspend fun checkAllDone(kit: NasOpsTestKit, label: String) {
    val ops = kit.store.ops(OpState.entries.toSet())
    assertWithMessage(label).that(ops.map { it.state }.toSet()).containsExactly(OpState.DONE)
    for (op in ops) {
      assertWithMessage(label).that(kit.store.items(op.id).map { it.state }.toSet()).containsExactly(dev.pampa.mycloudgallery.core.nasops.contract.OpItemState.DONE)
    }
  }

  private suspend fun verify(case: Case) {
    // Giro pulito: quante primitive di scrittura fa l'operazione, e il risultato atteso senza guasti.
    val clean = NasOpsTestKit(engine = engine())
    val counter = FaultPlan()
    clean.scenario(case.setup(clean), counter)
    val total = counter.operations
    assertWithMessage("${case.name}: primitive contate").that(total).isAtLeast(1)
    case.check(clean, "${case.name}, senza guasti")
    checkAllDone(clean, "${case.name}, senza guasti")

    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val kit = NasOpsTestKit(engine = engine())
        val request = case.setup(kit)
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        kit.scenario(request, fault)
        val label = "${case.name}, guasto $n/$total $phase"
        // Il guasto e' scattato davvero (altrimenti la prova non proverebbe niente).
        assertWithMessage(label).that(fault.fired).hasSize(1)
        case.check(kit, label)
        checkAllDone(kit, label)
      }
    }
  }

  @Test
  fun spostaTraShareRegge_unGuastoAOgniPrimitivaPrimaEDopo() = runTest {
    verify(
      Case(
        "sposta tra share",
        setup = { kit ->
          contents.take(2).forEachIndexed { i, bytes -> kit.putFile("test", "V/$i.jpg", bytes) }
          OpRequest.Move(listOf(kit.itemOf("test", "V/0.jpg"), kit.itemOf("test", "V/1.jpg")), "test-comune", SharePath.parse("F"))
        },
        check = { kit, label ->
          // Nessuna perdita e nessun doppione: ogni originale c'e' una volta fra i file visibili (destinazione) e una nel cestino.
          assertWithMessage(label).that(kit.visible("test-comune")).containsExactly("F/0.jpg", "F/1.jpg")
          assertWithMessage(label).that(kit.visible("test")).isEmpty()
          assertWithMessage(label).that(kit.bytes("test-comune", "F/0.jpg")!!.toList()).isEqualTo(contents[0].toList())
          assertWithMessage(label).that(kit.bytes("test-comune", "F/1.jpg")!!.toList()).isEqualTo(contents[1].toList())
          assertWithMessage(label).that(kit.trashedBytes()).containsExactly(contents[0].toList(), contents[1].toList())
          // Nessun temporaneo: tutto cio' che sta in tmp/ e' citato dal diario (qui: niente).
          assertWithMessage(label).that(kit.appFiles("test-comune")).isEmpty()
          assertWithMessage(label).that(kit.operations.operations.value.single()).isInstanceOf(NasOperationState.Done::class.java)
        },
      ),
    )
  }

  @Test
  fun spostaTraShareLasciandoINascostiRegge_unGuastoAOgniPrimitivaPrimaEDopo() = runTest {
    // R11 (scelta del 15/10): due file normali si spostano, due nascosti restano dov'erano; con un guasto a ogni primitiva, prima e dopo,
    // e la ripresa dal diario, i nascosti non arrivano mai nell'area comune e la cartella d'origine resta con loro.
    val hidden = listOf(byteArrayOf(9, 9, 9, 9, 9), byteArrayOf(8, 8, 8, 8, 8, 8))
    verify(
      Case(
        "sposta lasciando i nascosti",
        setup = { kit ->
          kit.putFile("test", "V/0.jpg", contents[0])
          kit.putFile("test", "V/Gita/1.jpg", contents[1])
          kit.putFile("test", "V/riservata.jpg", hidden[0])
          kit.putFile("test", "V/Gita/Segreta/s.jpg", hidden[1])
          OpRequest.Move(
            listOf(kit.itemOf("test", "V")),
            "test-comune",
            SharePath.parse("F"),
            setOf(LeaveBehind("test", SharePath.parse("V/riservata.jpg")), LeaveBehind("test", SharePath.parse("V/Gita/Segreta/s.jpg"))),
          )
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test-comune")).containsExactly("F/V/0.jpg", "F/V/Gita/1.jpg")
          for (path in kit.filesIn("test-comune")) {
            assertWithMessage("$label: $path").that(hidden.map { it.toList() }).doesNotContain(kit.bytes("test-comune", path)!!.toList())
          }
          assertWithMessage(label).that(kit.visible("test")).containsExactly("V/riservata.jpg", "V/Gita/Segreta/s.jpg")
          assertWithMessage(label).that(kit.bytes("test", "V/riservata.jpg")!!.toList()).isEqualTo(hidden[0].toList())
          assertWithMessage(label).that(kit.bytes("test", "V/Gita/Segreta/s.jpg")!!.toList()).isEqualTo(hidden[1].toList())
          assertWithMessage(label).that(kit.exists("test", "V/Gita/Segreta")).isTrue()
          assertWithMessage(label).that(kit.trashedBytes()).containsExactly(contents[0].toList(), contents[1].toList())
          assertWithMessage(label).that(kit.appFiles("test-comune")).isEmpty()
        },
      ),
    )
  }

  @Test
  fun eliminaRegge_unGuastoAOgniPrimitivaPrimaEDopo() = runTest {
    verify(
      Case(
        "elimina",
        setup = { kit ->
          kit.putFile("test", "Foto/a.jpg", contents[0])
          kit.putFile("test", "Foto/b.jpg", contents[1])
          OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"), kit.itemOf("test", "Foto/b.jpg")))
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test")).isEmpty()
          assertWithMessage(label).that(kit.trashedBytes()).containsExactly(contents[0].toList(), contents[1].toList())
          assertWithMessage(label).that(kit.store.allTrash()).hasSize(2)
        },
      ),
    )
  }

  @Test
  fun ripristinaRegge_unGuastoAOgniPrimitivaPrimaEDopo() = runTest {
    verify(
      Case(
        "ripristina",
        setup = { kit ->
          kit.putFile("test", "Foto/a.jpg", contents[0])
          kit.putFile("test", "Foto/b.jpg", contents[1])
          kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"), kit.itemOf("test", "Foto/b.jpg"))))
          OpRequest.Restore(
            kit.store.allTrash().map { TrashRestoreRef("test", kit.deviceId, it.trashId, it.originalPath, false, it.size) },
          )
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test")).containsExactly("Foto/a.jpg", "Foto/b.jpg")
          assertWithMessage(label).that(kit.bytes("test", "Foto/a.jpg")!!.toList()).isEqualTo(contents[0].toList())
          assertWithMessage(label).that(kit.bytes("test", "Foto/b.jpg")!!.toList()).isEqualTo(contents[1].toList())
          assertWithMessage(label).that(kit.appFiles("test")).isEmpty()
        },
      ),
    )
  }

  @Test
  fun rinominaRegge_unGuastoAOgniPrimitivaPrimaEDopo() = runTest {
    verify(
      Case(
        "rinomina",
        setup = { kit ->
          kit.putFile("test", "Foto/a.jpg", contents[0])
          kit.putFile("test", "Foto/b.jpg", contents[1])
          OpRequest.Rename(kit.itemOf("test", "Foto/a.jpg"), "c.jpg")
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test")).containsExactly("Foto/c.jpg", "Foto/b.jpg")
          assertWithMessage(label).that(kit.bytes("test", "Foto/c.jpg")!!.toList()).isEqualTo(contents[0].toList())
        },
      ),
    )
  }

  @Test
  fun rinominaSoloDiMaiuscoleRegge_unGuastoAOgniPrimitivaPrimaEDopo() = runTest {
    verify(
      Case(
        "solo maiuscole",
        setup = { kit ->
          kit.putFile("test", "Foto/b.jpg", contents[1])
          OpRequest.Rename(kit.itemOf("test", "Foto/b.jpg"), "B.JPG")
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test")).containsExactly("Foto/B.JPG")
          assertWithMessage(label).that(kit.bytes("test", "Foto/B.JPG")!!.toList()).isEqualTo(contents[1].toList())
        },
      ),
    )
  }

  @Test
  fun eliminaSubitoUnaCartellaRegge_unGuastoAOgniPrimitivaPrimaEDopo() = runTest {
    verify(
      Case(
        "elimina subito",
        setup = { kit ->
          kit.putFile("test", "Gita/1.jpg", contents[0])
          kit.putFile("test", "Gita/Mare/2.jpg", contents[1])
          kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Gita"))))
          OpRequest.PurgeNow(listOf(OwnTrashRef(kit.store.allTrash().single().trashId)))
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.filesIn("test")).isEmpty()
          assertWithMessage(label).that(kit.store.allTrash().single().state).isEqualTo(TrashEntryState.PURGED)
        },
      ),
    )
  }

  @Test
  fun dopoLaMorteDelProcessoLoStatoSiRicostruisceDalDiario() = runTest {
    val kit = NasOpsTestKit(engine = engine())
    kit.putFile("test", "x.jpg", contents[0])
    kit.run(OpRequest.Rename(kit.itemOf("test", "x.jpg"), "y.jpg"))
    kit.restart()
    assertThat(kit.operations.operations.value).isEmpty()
    kit.operations.resumeAll(1.minutes)
    assertThat(kit.operations.operations.value.single()).isInstanceOf(NasOperationState.Done::class.java)
    assertThat(kit.store.ops(setOf(OpState.DONE)).single().kind).isEqualTo(OpKind.RENAME)
  }
}
