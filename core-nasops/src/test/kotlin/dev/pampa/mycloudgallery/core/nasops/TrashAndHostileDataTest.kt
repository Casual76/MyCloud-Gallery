package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.testing.RemoteTrashRecord
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.KeepReason
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La pulizia del cestino (E-D4, F20) e i dati remoti ostili (E-S11, C10): nessun record, vero o finto, letto dal NAS fa eliminare o
 * spostare niente. Le uniche cose che partono sono la richiesta confermata dall'utente e la scadenza dal diario **locale**.
 */
open class TrashAndHostileDataTest {

  /** L'esecutore provato: quello di riferimento (P0-B) qui, quello vero (P12) in [ProductionTrashAndHostileDataTest]. */
  protected open fun engine(): KitEngine = KitEngine.REFERENCE

  private val kit = NasOpsTestKit(engine = engine())
  private val a = byteArrayOf(1, 2, 3, 4)
  private val day = 24L * 60 * 60 * 1000

  private fun visible(share: String) = kit.filesIn(share).filterNot { it.startsWith(".mycloudgallery") }

  private suspend fun trashOne(path: String = "Foto/a.jpg", bytes: ByteArray = a) = run {
    kit.putFile("test", path, bytes)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", path))))
    kit.store.allTrash().last()
  }

  // --- Scadenza ------------------------------------------------------------------------------------------------------------------

  @Test
  fun nullaDiScadutoNullaDaEliminare() = runTest {
    val entry = trashOne()
    kit.advance(29 * day)
    val report = kit.trash.purgeExpired(1.minutes)
    assertThat(report.purged).isEqualTo(0)
    assertThat(kit.exists("test", entry.trashedPath.value)).isTrue()
  }

  @Test
  fun dopo30GiorniDiOraDelNasLaVoceProprieSiEliminaConUnSoloHandle() = runTest {
    val entry = trashOne()
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    val report = kit.trash.purgeExpired(1.minutes)
    assertThat(report.purged).isEqualTo(1)
    assertThat(report.bytes).isEqualTo(a.size.toLong())
    assertThat(report.stoppedBy).isEqualTo(PurgeStop.COMPLETE)
    assertThat(kit.exists("test", entry.trashedPath.value)).isFalse()
    assertThat(kit.store.trashEntry(entry.trashId)!!.state).isEqualTo(TrashEntryState.PURGED)
    // Una seconda passata non trova piu' niente.
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
  }

  @Test
  fun ilFileCambiatoNelCestinoNonSiElimina() = runTest {
    val entry = trashOne()
    // Qualcuno ha cambiato il file nel cestino (dimensione diversa): non e' piu' quello che l'utente ha cestinato.
    kit.modify("test", entry.trashedPath.value, byteArrayOf(7))
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    val report = kit.trash.purgeExpired(1.minutes)
    assertThat(report.purged).isEqualTo(0)
    assertThat(report.kept[KeepReason.CHANGED]).isEqualTo(1)
    assertThat(kit.exists("test", entry.trashedPath.value)).isTrue()
  }

  @Test
  fun unOrologioCheTornaIndietroNonEliminaNiente() = runTest {
    val entry = trashOne()
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    // L'ora del NAS salta all'indietro di un mese: la voce non e' (piu') scaduta.
    kit.clocks.nasNow = kit.clocks.nasNow!! - 31 * day
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
    assertThat(kit.exists("test", entry.trashedPath.value)).isTrue()
  }

  @Test
  fun congelamentoSaltoDellOraEOraNonAncorataFermanoLaPulizia() = runTest {
    val entry = trashOne()
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    for (status in listOf(GateStatus.FROZEN, GateStatus.CLOCK_JUMP, GateStatus.WRITES_STOPPED)) {
      kit.gate.byShare["test"] = status
      val report = kit.trash.purgeExpired(1.minutes)
      assertThat(report.purged).isEqualTo(0)
      assertThat(report.stoppedBy).isEqualTo(PurgeStop.BLOCKED)
    }
    kit.gate.byShare.clear()
    val savedNow = kit.clocks.nasNow
    kit.clocks.nasNow = null
    assertThat(kit.trash.purgeExpired(1.minutes).stoppedBy).isEqualTo(PurgeStop.NO_NAS_TIME)
    kit.clocks.nasNow = savedNow
    assertThat(kit.exists("test", entry.trashedPath.value)).isTrue()
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(1)
  }

  @Test
  fun ilTettoPerGiroEDi200ElementiEIlResto() = runTest {
    val total = TrustLimits.MAX_PURGE_ITEMS_PER_ROUND + 5
    repeat(total) { n ->
      kit.putFile("test", "F/$n.jpg", byteArrayOf(n.toByte()))
    }
    val items = (0 until total).map { kit.itemOf("test", "F/$it.jpg") }
    kit.run(OpRequest.Trash(items))
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    val first = kit.trash.purgeExpired(1.minutes)
    assertThat(first.purged).isEqualTo(TrustLimits.MAX_PURGE_ITEMS_PER_ROUND)
    assertThat(first.deferred).isAtLeast(1)
    val second = kit.trash.purgeExpired(1.minutes)
    assertThat(second.purged).isEqualTo(5)
  }

  @Test
  fun unaCartellaCestinataScadutaSiEliminaIntera() = runTest {
    kit.putFile("test", "Gita/1.jpg", a)
    kit.putFile("test", "Gita/Mare/2.jpg", a)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Gita"))))
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(1)
    assertThat(kit.filesIn("test")).isEmpty()
  }

  // --- Dati remoti ostili ----------------------------------------------------------------------------------------------------------

  /** Un file nel cestino di un ALTRO dispositivo e un record (finto o vero) che lo cita: l'app lo mostra, non lo tocca. */
  private fun foreignTrashFile(owner: String = NasOpsTestKit.DEVICE_B, trashId: String = PlanFixtures.id(60)): SharePath {
    val path = NasLayout.trashItemFile(owner, trashId)
    kit.putFile("test", path.value, a)
    return path
  }

  @Test
  fun unFintoCestinoNonFaEliminareNullaNeLaPuliziaNeEliminaSubito() = runTest {
    val path = foreignTrashFile()
    kit.remoteTrash += RemoteTrashRecord(
      share = "test", ownerDeviceId = NasOpsTestKit.DEVICE_B, trashId = PlanFixtures.id(60), originalPath = SharePath.parse("Foto/finto.jpg"),
      size = a.size.toLong(), trashedAtNas = 0L, author = "Altro telefono",
    )
    kit.trash.refresh()
    val shown = (kit.trash.items.value as NasTrashState.Ready).items.single()
    // Si puo' ripristinare, mai eliminare subito: non e' un mio elemento.
    assertThat(shown.restorable).isTrue()
    assertThat(shown.deletableNow).isFalse()

    // Anche con un record "scaduto da secoli", la pulizia non lo tocca: legge solo il diario locale.
    kit.advance(400 * day)
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
    assertThat(kit.exists("test", path.value)).isTrue()

    // E "elimina subito" con quell'id non diventa un piano.
    val outcome = kit.operations.plan(OpRequest.PurgeNow(listOf(OwnTrashRef(PlanFixtures.id(60)))))
    assertThat((outcome as PlanOutcome.Rejected).reason).isEqualTo(PlanRejection.NOT_MINE)
  }

  @Test
  fun unRecordConUnPercorsoDentroLAreaDellAppNonDiventaUnRipristino() = runTest {
    foreignTrashFile()
    val hostile = TrashRestoreRef(
      "test", NasOpsTestKit.DEVICE_B, PlanFixtures.id(60), SharePath.parse(".mycloudgallery/devices/${PlanFixtures.me}/index/head/00000000000000000001"), false, 4,
    )
    val outcome = kit.operations.plan(OpRequest.Restore(listOf(hostile)))
    assertThat((outcome as PlanOutcome.Rejected).reason).isEqualTo(PlanRejection.PATH_NOT_ALLOWED)
    assertThat(kit.store.ops(dev.pampa.mycloudgallery.core.nasops.contract.OpState.entries.toSet())).isEmpty()
  }

  @Test
  fun ilRecordSenzaFileNonDiventaNulla() = runTest {
    val ghost = TrashRestoreRef("test", NasOpsTestKit.DEVICE_B, PlanFixtures.id(61), SharePath.parse("Foto/x.jpg"), false, 4)
    val outcome = kit.operations.plan(OpRequest.Restore(listOf(ghost)))
    assertThat((outcome as PlanOutcome.Rejected).reason).isEqualTo(PlanRejection.SOURCE_MISSING)
  }

  @Test
  fun ilRecordInvalidoComparePerMostrareSenzaAzioni() = runTest {
    foreignTrashFile()
    kit.remoteTrash += RemoteTrashRecord(
      "test", NasOpsTestKit.DEVICE_B, PlanFixtures.id(60), SharePath.parse("Foto/x.jpg"), 4, 0L, null, valid = false,
    )
    kit.trash.refresh()
    val shown = (kit.trash.items.value as NasTrashState.Ready).items.single()
    assertThat(shown.withoutValidRecord).isTrue()
    assertThat(shown.restorable).isFalse()
    assertThat(shown.name).isNull()
    assertThat(shown.originalPath).isNull()
  }

  @Test
  fun unaVoceDelCestinoDiUnAltroDispositivoSiRipristinaSenzaTocareIlSuoDiario() = runTest {
    val path = foreignTrashFile()
    kit.run(
      OpRequest.Restore(listOf(TrashRestoreRef("test", NasOpsTestKit.DEVICE_B, PlanFixtures.id(60), SharePath.parse("Foto/vista.jpg"), false, 4))),
    )
    assertThat(visible("test")).containsExactly("Foto/vista.jpg")
    assertThat(kit.exists("test", path.value)).isFalse()
    // Nel mio diario non c'era e non e' comparsa.
    assertThat(kit.store.allTrash()).isEmpty()
  }

  @Test
  fun ilCestinoMostraSoloIlCheCeDavvero() = runTest {
    trashOne()
    kit.remoteTrash += RemoteTrashRecord("test", NasOpsTestKit.DEVICE_B, PlanFixtures.id(70), SharePath.parse("Foto/sparito.jpg"), 4, 0L, null)
    kit.trash.refresh()
    val items = (kit.trash.items.value as NasTrashState.Ready).items
    // Il record senza file non compare (e' innocuo), la voce propria si'.
    assertThat(items).hasSize(1)
    assertThat(items.single().reason).isEqualTo(TrashReason.DELETE)
    assertThat(items.single().ownerDeviceId).isEqualTo(kit.deviceId)
  }
}
