package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.ServerCopyMode
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nasops.contract.LeaveBehind
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef
import dev.pampa.mycloudgallery.core.nasops.engine.RecordWrite
import dev.pampa.mycloudgallery.core.nasops.engine.TrashRecordWriter
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpClocks
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.trash.SignedTrashRecords
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.data.TrashRecord
import dev.pampa.mycloudgallery.core.trust.data.TrashRecordReason
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La riga P12 del §4.4: **ogni macchina a stati** con un guasto (`FaultPlan`) a **ogni** chiamata al NAS — scritture e letture — prima e
 * dopo che il NAS l'abbia applicata, e con la **morte del processo a ogni passo** (ogni scrittura del diario e ogni chiamata al NAS, prima
 * e dopo l'effetto). Dopo la ripresa dal diario gli invarianti del §5.5 sono veri, a meta' e alla fine: nessun file a meta' visibile,
 * nessun doppione visibile, nessuna perdita, byte giusti, solo temporanei propri citati dal diario, cestino coerente (file nel cestino ⇒
 * voce del diario; record firmato valido), e ogni operazione arriva a `DONE`.
 *
 * Il numero di punti si conta con un giro pulito; ogni guasto deve scattare davvero.
 */
class EveryCallTest {

  private val family = SigningFamily(FakeOpClocks.DEFAULT_NAS_NOW)
  private val device = family.approveDevice()
  private val shares = listOf(NasOpsTestKit.PRIVATE_SHARE, NasOpsTestKit.COMMON_SHARE)

  private fun newKit() = NasOpsTestKit(deviceId = device.deviceId(), engine = KitEngine.PRODUCTION, signing = family.identity(device))

  private fun content(n: Int) = ByteArray(5 + n) { (n * 31 + it).toByte() }

  private fun id(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  private class Scenario(
    val name: String,
    val setup: suspend (NasOpsTestKit) -> OpRequest,
    val check: suspend (NasOpsTestKit, String) -> Unit,
    /** I contenuti che l'operazione deve eliminare per sempre ("elimina subito"). */
    val purged: (NasOpsTestKit) -> Set<List<Byte>> = { emptySet() },
  )

  private suspend fun NasOpsTestKit.planOf(request: OpRequest): OpPlan =
    (operations.plan(request) as? PlanOutcome.Ready)?.plan ?: error("piano rifiutato: ${operations.plan(request)}")

  private fun NasOpsTestKit.visible(share: String) = filesIn(share).filterNot { isAppArea(it) }

  private suspend fun NasOpsTestKit.allDone(label: String) {
    val ops = store.ops(OpState.entries.toSet())
    assertWithMessage("$label: operazioni").that(ops.map { it.state }.toSet()).containsExactly(OpState.DONE)
    for (op in ops) assertWithMessage("$label: elementi").that(store.items(op.id).map { it.state }.toSet()).containsExactly(OpItemState.DONE)
  }

  /** Ogni voce TRASHED del diario ha il suo file e il suo record firmato valido (con i campi nuovi M5). */
  private fun NasOpsTestKit.trashCoherent(label: String) {
    for (entry in store.allTrash().filter { it.state == TrashEntryState.TRASHED }) {
      assertWithMessage("$label: file della voce").that(exists(entry.shareName, entry.trashedPath.value)).isTrue()
      val read = nas.fileSystem(entry.shareName).read(NasLayout.trashRecordFile(deviceId, entry.trashId), DocType.TRASH_RECORD.maxFileBytes)
      val bytes = (read as? NasRead.Data)?.bytes
      assertWithMessage("$label: record firmato").that(bytes).isNotNull()
      val verdict = TrashRecord.verify(bytes!!, family.state, entry.shareName, deviceId, entry.trashId)
      assertWithMessage("$label: record valido").that(verdict).isInstanceOf(Verdict.Accepted::class.java)
      val record = (verdict as Verdict.Accepted).value
      assertWithMessage(label).that(record.originalPath).isEqualTo(entry.originalPath)
      assertWithMessage(label).that(record.folder).isEqualTo(entry.isFolder)
      assertWithMessage(label).that(record.reason).isEqualTo(if (entry.reason == TrashReason.MOVE) TrashRecordReason.MOVE else TrashRecordReason.DELETE)
    }
  }

  private suspend fun finalChecks(kit: NasOpsTestKit, s: Scenario, originals: Originals, label: String) {
    s.check(kit, label)
    kit.checkInvariants(originals, shares, label, final = true, purged = s.purged(kit))
    kit.allDone(label)
    kit.trashCoherent(label)
  }

  // --- Guasti a ogni chiamata --------------------------------------------------------------------------------------------------

  private suspend fun faultsAtEveryCall(s: Scenario): Int {
    val clean = newKit()
    val request = s.setup(clean)
    val cleanOriginals = Originals.of(clean, shares)
    val purgedContents = s.purged(clean)
    val plan = clean.planOf(request)
    val counter = FaultPlan()
    clean.nas.opsFaults = counter
    clean.nas.readFaults = counter
    clean.operations.start(plan, clean.confirm(plan))
    clean.nas.opsFaults = null
    clean.nas.readFaults = null
    clean.resumeUntilQuiet()
    finalChecks(clean, Scenario(s.name, s.setup, s.check) { purgedContents }, cleanOriginals, "${s.name}, pulito")
    val total = counter.operations
    assertWithMessage("${s.name}: chiamate contate").that(total).isAtLeast(1)
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val kit = newKit()
        val req = s.setup(kit)
        val originals = Originals.of(kit, shares)
        val purged = s.purged(kit)
        val p = kit.planOf(req)
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        kit.nas.opsFaults = fault
        kit.nas.readFaults = fault
        kit.operations.start(p, kit.confirm(p))
        kit.nas.opsFaults = null
        kit.nas.readFaults = null
        val label = "${s.name}, guasto alla chiamata $n/$total $phase"
        assertWithMessage("$label: il guasto e' scattato").that(fault.fired).hasSize(1)
        kit.checkInvariants(originals, shares, "$label (a meta')", final = false, purged = purged)
        kit.restart()
        kit.resumeUntilQuiet()
        finalChecks(kit, Scenario(s.name, s.setup, s.check) { purged }, originals, label)
      }
    }
    return total
  }

  // --- Morte del processo a ogni passo -------------------------------------------------------------------------------------------

  private fun NasOpsTestKit.dyingRecords(death: Death): TrashRecordWriter {
    val inner = SignedTrashRecords(deviceId, { family.identity(device) }, { nas.fileSystem(it) }, Dispatchers.Unconfined)
    return TrashRecordWriter { draft -> death.around<RecordWrite> { inner.publish(draft) } }
  }

  private suspend fun deathAtEveryStep(s: Scenario): Int {
    val clean = newKit()
    val request = s.setup(clean)
    val plan = clean.planOf(request)
    val counter = Death()
    clean.engineWith(counter, clean.dyingRecords(counter)).start(plan, clean.confirm(plan))
    val total = counter.steps
    assertWithMessage("${s.name}: passi contati").that(total).isAtLeast(2)
    for (n in 1..total) {
      for (after in listOf(false, true)) {
        val kit = newKit()
        val req = s.setup(kit)
        val originals = Originals.of(kit, shares)
        val purged = s.purged(kit)
        val p = kit.planOf(req)
        val death = Death(n, after)
        try {
          kit.engineWith(death, kit.dyingRecords(death)).start(p, kit.confirm(p))
        } catch (_: ProcessDeath) {
          // il processo e' morto: si butta tutto e si riparte dal diario
        }
        val label = "${s.name}, morte al passo $n/$total ${if (after) "dopo" else "prima"}"
        assertWithMessage("$label: la morte e' avvenuta").that(death.died).isTrue()
        kit.checkInvariants(originals, shares, "$label (a meta')", final = false, purged = purged)
        kit.restart()
        // Morto prima che il diario nascesse: nessuna operazione, l'utente conferma di nuovo (il piano e' lo stesso).
        if (kit.store.opByPlanHash(p.hash) == null) kit.operations.start(p, kit.confirm(p))
        kit.resumeUntilQuiet()
        finalChecks(kit, Scenario(s.name, s.setup, s.check) { purged }, originals, label)
      }
    }
    return total
  }

  private suspend fun both(s: Scenario) {
    val calls = faultsAtEveryCall(s)
    val steps = deathAtEveryStep(s)
    println("P12 ${s.name}: $calls chiamate al NAS x 2 fasi, $steps passi x 2 (prima/dopo)")
  }

  // --- Gli scenari --------------------------------------------------------------------------------------------------------------

  @Test
  fun creaCartella() = runTest {
    both(
      Scenario(
        "crea cartella",
        setup = { OpRequest.CreateFolder("test", SharePath.parse("Foto"), "Nuova") },
        check = { kit, label -> assertWithMessage(label).that(kit.exists("test", "Foto/Nuova")).isTrue() },
      ),
    )
  }

  @Test
  fun rinomina() = runTest {
    both(
      Scenario(
        "rinomina",
        setup = { kit ->
          kit.putFile("test", "Foto/a.jpg", content(1))
          kit.putFile("test", "Foto/b.jpg", content(2))
          OpRequest.Rename(kit.itemOf("test", "Foto/a.jpg"), "c.jpg")
        },
        check = { kit, label -> assertWithMessage(label).that(kit.visible("test")).containsExactly("Foto/c.jpg", "Foto/b.jpg") },
      ),
    )
  }

  @Test
  fun rinominaSoloDiMaiuscole() = runTest {
    both(
      Scenario(
        "solo maiuscole",
        setup = { kit ->
          kit.putFile("test", "Foto/b.jpg", content(3))
          OpRequest.Rename(kit.itemOf("test", "Foto/b.jpg"), "B.JPG")
        },
        check = { kit, label -> assertWithMessage(label).that(kit.visible("test")).containsExactly("Foto/B.JPG") },
      ),
    )
  }

  @Test
  fun spostaNellaStessaShareConNomeOccupato() = runTest {
    both(
      Scenario(
        "sposta nella share",
        setup = { kit ->
          kit.putFile("test", "A/x.jpg", content(4))
          kit.putFile("test", "B/x.jpg", content(5))
          OpRequest.Move(listOf(kit.itemOf("test", "A/x.jpg")), "test", SharePath.parse("B"))
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test")).containsExactly("B/x.jpg", "B/x (2).jpg")
          assertWithMessage(label).that(kit.bytes("test", "B/x (2).jpg")!!.toList()).isEqualTo(content(4).toList())
        },
      ),
    )
  }

  @Test
  fun spostaTraShareFileECartelleConNomeOccupato() = runTest {
    both(
      Scenario(
        "sposta tra share",
        setup = { kit ->
          kit.putFile("test", "V/0.jpg", content(6))
          kit.putFile("test", "V/Gita/1.jpg", content(7))
          kit.putFile("test", "V/Gita/Mare/2.jpg", content(8))
          kit.putDir("test", "V/Gita/Vuota")
          kit.putFile("test-comune", "F/0.jpg", content(9))
          OpRequest.Move(listOf(kit.itemOf("test", "V/0.jpg"), kit.itemOf("test", "V/Gita")), "test-comune", SharePath.parse("F"))
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test-comune")).containsExactly("F/0.jpg", "F/0 (2).jpg", "F/Gita/1.jpg", "F/Gita/Mare/2.jpg")
          assertWithMessage(label).that(kit.bytes("test-comune", "F/0 (2).jpg")!!.toList()).isEqualTo(content(6).toList())
          assertWithMessage(label).that(kit.exists("test-comune", "F/Gita/Vuota")).isTrue()
          assertWithMessage(label).that(kit.visible("test")).isEmpty()
          // Le cartelle d'origine, svuotate, non restano (Q3: i file sono nel cestino della loro share).
          assertWithMessage(label).that(kit.exists("test", "V/Gita")).isFalse()
          val moved = kit.store.allTrash()
          assertWithMessage(label).that(moved.map { it.reason }.toSet()).containsExactly(TrashReason.MOVE)
          assertWithMessage(label).that(moved.filter { it.state == TrashEntryState.TRASHED }).hasSize(3)
        },
      ),
    )
  }

  @Test
  fun spostaTraShareLasciandoINascosti() = runTest {
    // R11 (scelta dell'utente del 15/10): i nascosti restano nell'area privata, il resto si sposta. Con un guasto a ogni chiamata e la morte
    // del processo a ogni passo, nessun nascosto arriva nell'area comune, la cartella d'origine resta con loro e ogni operazione si chiude.
    val hidden = listOf(content(40), content(41))
    both(
      Scenario(
        "sposta lasciando i nascosti",
        setup = { kit ->
          kit.putFile("test", "V/0.jpg", content(20))
          kit.putFile("test", "V/Gita/1.jpg", content(21))
          kit.putFile("test", "V/riservata.jpg", hidden[0])
          kit.putFile("test", "V/Gita/Segreta/s.jpg", hidden[1])
          kit.putDir("test", "V/Gita/Vuota")
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
          // Una cartella davvero vuota si sposta come sempre; quella con un nascosto non arriva nell'area comune da sola.
          assertWithMessage(label).that(kit.exists("test-comune", "F/V/Gita/Vuota")).isTrue()
          assertWithMessage(label).that(kit.exists("test-comune", "F/V/Gita/Segreta")).isFalse()
          // I nascosti sono dove erano, intatti, e le cartelle che li contengono non si sono tolte.
          assertWithMessage(label).that(kit.visible("test")).containsExactly("V/riservata.jpg", "V/Gita/Segreta/s.jpg")
          assertWithMessage(label).that(kit.bytes("test", "V/riservata.jpg")!!.toList()).isEqualTo(hidden[0].toList())
          assertWithMessage(label).that(kit.bytes("test", "V/Gita/Segreta/s.jpg")!!.toList()).isEqualTo(hidden[1].toList())
          assertWithMessage(label).that(kit.exists("test", "V")).isTrue()
          assertWithMessage(label).that(kit.exists("test", "V/Gita")).isTrue()
          assertWithMessage(label).that(kit.exists("test", "V/Gita/Vuota")).isFalse()
          val moved = kit.store.allTrash()
          assertWithMessage(label).that(moved.map { it.reason }.toSet()).containsExactly(TrashReason.MOVE)
          assertWithMessage(label).that(moved.filter { it.state == TrashEntryState.TRASHED }).hasSize(2)
        },
      ),
    )
  }

  @Test
  fun spostaTraShareConIlRipiegoDalTelefono() = runTest {
    both(
      Scenario(
        "ripiego dal telefono",
        setup = { kit ->
          kit.nas.serverCopyMode = ServerCopyMode.NOT_SUPPORTED
          kit.putFile("test", "V/a.jpg", ByteArray(300_000) { (it % 251).toByte() })
          OpRequest.Move(listOf(kit.itemOf("test", "V/a.jpg")), "test-comune", null)
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test-comune")).containsExactly("a.jpg")
          assertWithMessage(label).that(kit.visible("test")).isEmpty()
        },
      ),
    )
  }

  @Test
  fun eliminaFileECartella() = runTest {
    both(
      Scenario(
        "elimina",
        setup = { kit ->
          kit.putFile("test", "Foto/a.jpg", content(10))
          kit.putFile("test", "Gita/1.jpg", content(11))
          kit.putFile("test", "Gita/Mare/2.jpg", content(12))
          OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"), kit.itemOf("test", "Gita")))
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test")).isEmpty()
          val folder = kit.store.allTrash().single { it.isFolder }
          assertWithMessage(label).that(folder.fileCount).isEqualTo(2)
          assertWithMessage(label).that(kit.store.allTrash().map { it.state }.toSet()).containsExactly(TrashEntryState.TRASHED)
        },
      ),
    )
  }

  @Test
  fun ripristinaConCartellaSparitaNomeOccupatoEVoceAltrui() = runTest {
    both(
      Scenario(
        "ripristina",
        setup = { kit ->
          kit.putFile("test", "Foto/2024/a.jpg", content(13))
          kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/2024/a.jpg"))))
          kit.deleteFromPc("test", "Foto")
          kit.putFile("test", "Foto/2024/a.jpg", content(14))
          kit.putFile("test", NasLayout.trashItemFile(NasOpsTestKit.DEVICE_B, id(60)).value, content(15))
          val own = kit.store.allTrash().single()
          OpRequest.Restore(
            listOf(
              TrashRestoreRef("test", kit.deviceId, own.trashId, own.originalPath, false, own.size),
              TrashRestoreRef("test", NasOpsTestKit.DEVICE_B, id(60), SharePath.parse("Altro/c.jpg"), false, content(15).size.toLong()),
            ),
          )
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.visible("test")).containsExactly("Foto/2024/a.jpg", "Foto/2024/a (2).jpg", "Altro/c.jpg")
          assertWithMessage(label).that(kit.bytes("test", "Foto/2024/a (2).jpg")!!.toList()).isEqualTo(content(13).toList())
          assertWithMessage(label).that(kit.store.allTrash().single().state).isEqualTo(TrashEntryState.RESTORED)
        },
      ),
    )
  }

  @Test
  fun eliminaSubitoFileECartella() = runTest {
    both(
      Scenario(
        "elimina subito",
        setup = { kit ->
          kit.putFile("test", "Foto/x.jpg", content(16))
          kit.putFile("test", "Gita/1.jpg", content(17))
          kit.putFile("test", "Gita/Mare/2.jpg", content(18))
          kit.putFile("test", "Resta.jpg", content(19))
          kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/x.jpg"), kit.itemOf("test", "Gita"))))
          OpRequest.PurgeNow(kit.store.allTrash().map { OwnTrashRef(it.trashId) })
        },
        check = { kit, label ->
          assertWithMessage(label).that(kit.filesIn("test").filterNot { it.startsWith(".mycloudgallery/trash/${kit.deviceId}/rec") }).containsExactly("Resta.jpg")
          assertWithMessage(label).that(kit.store.allTrash().map { it.state }.toSet()).containsExactly(TrashEntryState.PURGED)
        },
        purged = { setOf(content(16).toList(), content(17).toList(), content(18).toList()) },
      ),
    )
  }

  @Test
  fun iConteggiSonoQuelliAttesi() = runTest {
    // Il controllo dei controlli: un giro pulito tocca davvero lettura e scrittura (altrimenti "ogni chiamata" non proverebbe niente).
    val kit = newKit()
    kit.putFile("test", "V/a.jpg", content(1))
    val plan = kit.planOf(OpRequest.Move(listOf(kit.itemOf("test", "V/a.jpg")), "test-comune", null))
    val counter = FaultPlan()
    kit.nas.opsFaults = counter
    kit.nas.readFaults = counter
    kit.operations.start(plan, kit.confirm(plan))
    assertThat(counter.operations).isAtLeast(8)
  }
}
