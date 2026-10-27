package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nasops.contract.CatalogHoldBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.ProtectedFiles
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 7, revisione B (`docs/review/7-fase7.md`), sui due esecutori (quello vero di P12 e quello di riferimento: stesse regole, §5.2):
 * - R8: un errore che non e' di rete alla prima occhiata alla destinazione appena pubblicata **non salta** la porta di completamento (prima: la
 *   sorgente andava nel cestino senza che il catalogo sapesse la destinazione, se la seconda occhiata riusciva);
 * - I2: una porta che non risponde mai tiene l'elemento con un motivo leggibile ([PauseReason.CATALOG]) e un tetto (24 ore), poi lo ferma con le
 *   due copie al loro posto (prima: "condizioni", senza fine);
 * - R3: i file protetti dagli album condivisi ([ProtectedFiles]) restano fuori dal piano con il loro motivo, su cestino, spostamento e rinomina.
 */
class RevisioneBNasOpsTest {

  private fun content(n: Int) = ByteArray(9 + n) { (n * 41 + it).toByte() }

  /** Il catalogo finto: le chiamate della porta e quelle che hanno risposto. */
  private class Recorder(var refuse: Boolean = false) : MoveCompletionSink {
    val calls = ArrayList<Pair<OpId, Int>>()
    val acked = HashSet<Pair<OpId, Int>>()

    override suspend fun published(op: OpId, index: Int, source: NasFileRef, dest: NasFileRef, stamp: NasStamp, sha256: Sha256) {
      calls += op to index
      if (refuse) error("destinazione di un altro media")
      acked += op to index
    }
  }

  private suspend fun NasOpsTestKit.planOf(request: OpRequest): OpPlan =
    (operations.plan(request) as? PlanOutcome.Ready)?.plan ?: error("piano rifiutato: ${operations.plan(request)}")

  /** Ogni sorgente nel cestino ha avuto la risposta della porta per il suo elemento. */
  private suspend fun NasOpsTestKit.noTrashWithoutAck(recorder: Recorder, label: String) {
    for (entry in store.allTrash().filter { it.state == TrashEntryState.TRASHED }) {
      val op = entry.opId ?: continue
      val item = store.items(op).first { it.trashId == entry.trashId }
      assertWithMessage("$label: sorgente nel cestino senza la porta").that(recorder.acked).contains(op to item.index)
    }
  }

  // --- R8 ------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun r8UnErroreNonDiReteAllaPrimaOcchiataNonSaltaLaPorta() = runTest {
    for (engine in KitEngine.entries) {
      val label = engine.name
      val recorder = Recorder()
      val kit = NasOpsTestKit(engine = engine, completions = recorder)
      kit.putFile("test", "A/a.jpg", content(1))
      // La copia appena pubblicata e' aperta in esclusiva da un altro client SMB: la prima occhiata risponde "file in uso" (non e' la rete).
      val fired = AtomicBoolean(false)
      kit.nas.fault = FakeNasFault { op, share, path ->
        val published = op == FakeNasOp.STAT && share.equals("test-comune", ignoreCase = true) && path?.value == "F/a.jpg" && kit.exists("test-comune", "F/a.jpg")
        if (published && fired.compareAndSet(false, true)) NasError.FileBusy else null
      }
      val plan = kit.planOf(OpRequest.Move(listOf(kit.itemOf("test", "A/a.jpg")), "test-comune", SharePath.parse("F")))
      kit.operations.start(plan, kit.confirm(plan))
      assertWithMessage("$label: il guasto e' scattato").that(fired.get()).isTrue()
      kit.noTrashWithoutAck(recorder, "$label (subito)")
      kit.resumeUntilQuiet()
      kit.noTrashWithoutAck(recorder, label)
      val op = kit.store.opByPlanHash(plan.hash)!!
      assertWithMessage("$label: lo spostamento finisce dopo la porta").that(kit.store.op(op.id)!!.state).isEqualTo(OpState.DONE)
      assertWithMessage(label).that(recorder.acked).containsExactly(op.id to 0)
      assertWithMessage(label).that(kit.exists("test", "A/a.jpg")).isFalse()
    }
  }

  // --- I2 ------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun i2UnaPortaCheNonRispondeMaiHaUnMotivoEUnTetto() = runTest {
    for (engine in KitEngine.entries) {
      val label = engine.name
      val recorder = Recorder(refuse = true)
      val kit = NasOpsTestKit(engine = engine, completions = recorder)
      kit.putFile("test", "A/a.jpg", content(2))
      val plan = kit.planOf(OpRequest.Move(listOf(kit.itemOf("test", "A/a.jpg")), "test-comune", SharePath.parse("F")))
      kit.operations.start(plan, kit.confirm(plan))
      val op = kit.store.opByPlanHash(plan.hash)!!.id
      // Il motivo leggibile: "catalogo", non "condizioni"; l'elemento aspetta in PUBLISHED con il prossimo tentativo scritto.
      assertWithMessage(label).that(kit.store.op(op)!!.pauseReason).isEqualTo(PauseReason.CATALOG)
      val held = kit.store.items(op).single()
      assertWithMessage(label).that(held.state).isEqualTo(OpItemState.PUBLISHED)
      assertWithMessage(label).that(held.waitingCatalog).isTrue()
      // Le riprese passano (il tempo con loro): prima qualche tentativo subito, poi intervalli crescenti; oltre le 24 ore l'elemento si ferma.
      var rounds = 0
      while (kit.store.op(op)!!.state != OpState.FAILED && rounds++ < 200) {
        kit.operations.resumeAll(1.hours)
        kit.advance(30L * 60 * 1000)
      }
      val item = kit.store.items(op).single()
      assertWithMessage("$label: l'elemento si ferma dopo il tetto").that(item.state).isEqualTo(OpItemState.FAILED)
      assertWithMessage(label).that(item.errorCode).isEqualTo(OpErrorCode.CATALOG_MISMATCH)
      assertWithMessage("$label: tentativi limitati").that(recorder.calls.size).isAtMost(CatalogHoldBackoff.QUICK_RETRIES + 12)
      // Nessuna perdita: la sorgente al suo posto, la copia verificata nella destinazione, niente nel cestino.
      assertWithMessage(label).that(kit.bytes("test", "A/a.jpg")!!.toList()).isEqualTo(content(2).toList())
      assertWithMessage(label).that(kit.bytes("test-comune", "F/a.jpg")!!.toList()).isEqualTo(content(2).toList())
      assertWithMessage(label).that(kit.store.allTrash().filter { it.state == TrashEntryState.TRASHED }).isEmpty()
    }
  }

  @Test
  fun i2UnCatalogoCheRispondeDopoUnPoFinisceLoSpostamento() = runTest {
    for (engine in KitEngine.entries) {
      val label = engine.name
      val recorder = Recorder(refuse = true)
      val kit = NasOpsTestKit(engine = engine, completions = recorder)
      kit.putFile("test", "A/a.jpg", content(3))
      val plan = kit.planOf(OpRequest.Move(listOf(kit.itemOf("test", "A/a.jpg")), "test-comune", SharePath.parse("F")))
      kit.operations.start(plan, kit.confirm(plan))
      repeat(CatalogHoldBackoff.QUICK_RETRIES + 2) {
        kit.operations.resumeAll(1.hours)
        kit.advance(5L * 60 * 1000)
      }
      recorder.refuse = false
      kit.restart()
      kit.resumeUntilQuiet(rounds = 40)
      val op = kit.store.opByPlanHash(plan.hash)!!.id
      assertWithMessage(label).that(kit.store.op(op)!!.state).isEqualTo(OpState.DONE)
      val item = kit.store.items(op).single()
      assertWithMessage("$label: l'attesa si e' azzerata").that(item.errorCode).isNull()
      kit.noTrashWithoutAck(recorder, label)
    }
  }

  // --- R3 ------------------------------------------------------------------------------------------------------------------------------

  /** Protegge i file (e le cartelle che li contengono) indicati, nell'area comune. */
  private class Shield(private val files: Set<String>, var broken: Boolean = false) : ProtectedFiles {
    override suspend fun protectedAmong(share: String, items: List<NasItem>): Set<SharePath> {
      if (broken) error("album non leggibili")
      if (!share.equals("test-comune", ignoreCase = true)) return emptySet()
      return items.filter { item -> files.any { it == item.path.value || (item.isFolder && it.startsWith(item.path.value + "/")) } }.map { it.path }.toSet()
    }
  }

  @Test
  fun r3IFileProtettiRestanoFuoriDaCestinoSpostamentoERinomina() = runTest {
    for (engine in KitEngine.entries) {
      val label = engine.name
      val shield = Shield(setOf("Album condivisi/Oslo/b.jpg"))
      val kit = NasOpsTestKit(engine = engine, protectedFiles = shield)
      kit.putFile("test-comune", "Album condivisi/Oslo/a.jpg", content(4))
      kit.putFile("test-comune", "Album condivisi/Oslo/b.jpg", content(5))
      val a = kit.itemOf("test-comune", "Album condivisi/Oslo/a.jpg")
      val b = kit.itemOf("test-comune", "Album condivisi/Oslo/b.jpg")
      // Cestino: il protetto resta fuori con il suo motivo; l'altro si elimina.
      val trash = kit.planOf(OpRequest.Trash(listOf(a, b)))
      assertWithMessage(label).that(trash.items.map { it.srcPath.value }).containsExactly("Album condivisi/Oslo/a.jpg")
      assertWithMessage(label).that(trash.skipped.map { it.path.value to it.reason }).containsExactly("Album condivisi/Oslo/b.jpg" to SkipReason.SHARED_ALBUM_ITEM)
      // Solo il protetto: il piano si rifiuta con il motivo.
      assertWithMessage(label).that(kit.operations.plan(OpRequest.Trash(listOf(b)))).isEqualTo(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
      // Spostamento (anche "Riporta" fatto a mano dal viewer) e rinomina.
      assertWithMessage(label).that(kit.operations.plan(OpRequest.Move(listOf(b), "test", SharePath.parse("Foto")))).isEqualTo(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
      assertWithMessage(label).that(kit.operations.plan(OpRequest.Rename(b, "altro.jpg"))).isEqualTo(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
      // Una cartella che contiene un protetto resta dov'e'.
      val folder = kit.itemOf("test-comune", "Album condivisi/Oslo")
      assertWithMessage(label).that(kit.operations.plan(OpRequest.Trash(listOf(folder)))).isEqualTo(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
      // Un errore di chi risponde vale "protetto": niente parte.
      shield.broken = true
      assertWithMessage(label).that(kit.operations.plan(OpRequest.Trash(listOf(a)))).isEqualTo(PlanOutcome.Rejected(PlanRejection.SHARED_ALBUM_ITEM))
    }
  }
}
