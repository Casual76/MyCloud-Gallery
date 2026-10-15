package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.engine.ShareAccessView
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpClocks
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.trash.NasTrashService
import dev.pampa.mycloudgallery.core.nasops.trash.SignedTrashRecordSource
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.data.KeepReason
import dev.pampa.mycloudgallery.core.trust.data.TrashRecord
import dev.pampa.mycloudgallery.core.trust.data.TrashRecordReason
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La pulizia del cestino (E-D4, F20; formato §9 punto 3) sull'esecutore vero: **solo dal diario locale**. Un finto cestino, record falsi o
 * firmati da altri, l'ora che salta (D32) o torna indietro, un record di un altro dispositivo: nessuna eliminazione. Un guasto a ogni chiamata
 * e la morte del processo a ogni passo non lasciano mai una voce incoerente ne' eliminano qualcosa che non doveva.
 */
class TrashPurgeTest {

  private val family = SigningFamily(FakeOpClocks.DEFAULT_NAS_NOW)
  private val device = family.approveDevice()
  private val otherDevice = family.approveDevice(name = "Tablet")
  private val day = 24L * 60 * 60 * 1000

  private fun newKit() = NasOpsTestKit(deviceId = device.deviceId(), engine = KitEngine.PRODUCTION, signing = family.identity(device))

  private fun id(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  /** Il cestino vero con la lettura vera dei record firmati (registro accettato) e la morte del processo programmata. */
  private fun NasOpsTestKit.trashWith(death: Death = Death()) = NasTrashService(
    deviceId = deviceId,
    accountId = accountId,
    shares = { listOf("test", "test-comune") },
    store = DyingStore(store, death),
    fileOps = DyingFileOps(fileOps, death),
    reader = DyingReader(nas.reader, death),
    clocks = clocks,
    gate = gate,
    access = ShareAccessView.ALL_WRITABLE,
    remote = SignedTrashRecordSource({ nas.fileSystem(it) }, { family.state }, Dispatchers.Unconfined),
    lock = NasOpsLock(),
  )

  /** Un mondo con il proprio cestino scaduto (un file e una cartella) e tanta roba ostile o altrui che non si deve toccare. */
  private suspend fun hostileWorld(kit: NasOpsTestKit) {
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    kit.putFile("test", "Gita/1.jpg", byteArrayOf(4))
    kit.putFile("test", "Gita/Mare/2.jpg", byteArrayOf(5, 5))
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"), kit.itemOf("test", "Gita"))))
    // Il cestino di un altro dispositivo, con un record firmato valido "scaduto da secoli".
    val foreign = NasLayout.trashItemFile(otherDevice.deviceId(), id(60))
    kit.putFile("test", foreign.value, byteArrayOf(9, 9, 9))
    val record = TrashRecord(family.familyId, otherDevice.deviceId(), "test", id(60), SharePath.parse("Foto/altrui.jpg"), 3, kit.clocks.nasNow!! - 400 * day)
    kit.nas.fileSystem("test").publishExclusive(NasLayout.trashRecordFile(otherDevice.deviceId(), id(60)), record.sign(otherDevice))
    // Un record FIRMATO DA UN ALTRO ma messo nel MIO cestino, che punta a un file nel mio cestino (finto cestino).
    val planted = NasLayout.trashItemFile(kit.deviceId, id(61))
    kit.putFile("test", planted.value, byteArrayOf(7, 7, 7, 7))
    val fake = TrashRecord(family.familyId, otherDevice.deviceId(), "test", id(61), SharePath.parse("Foto/finto.jpg"), 4, kit.clocks.nasNow!! - 400 * day)
    kit.putFile("test", NasLayout.trashRecordFile(kit.deviceId, id(61)).value, fake.sign(otherDevice))
    // Spazzatura: un record illeggibile e un file con un nome non valido nel cestino.
    kit.putFile("test", NasLayout.trashRecordFile(kit.deviceId, id(62)).value, byteArrayOf(0x4d, 0x43, 0x47, 0x53, 1, 5))
    kit.putFile("test", NasLayout.ROOT.child("trash").child(kit.deviceId).child("files").child("non-un-id").value, byteArrayOf(8))
  }

  private fun NasOpsTestKit.untouchable(): Map<String, List<Byte>> =
    filesIn("test").filter { p -> store.allTrash().none { p.startsWith(it.trashedPath.value) } }.associateWith { bytes("test", it)!!.toList() }

  @Test
  fun soloIlDiarioLocaleFaEliminareIlRestoNonSiTocca() = runTest {
    val kit = newKit()
    hostileWorld(kit)
    val keep = kit.untouchable()
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    val report = kit.trashWith().purgeExpired(1.minutes)
    assertThat(report.purged).isEqualTo(2)
    assertThat(kit.store.allTrash().map { it.state }.toSet()).containsExactly(TrashEntryState.PURGED)
    // Tutto il resto (file altrui, finto cestino, record falsi, spazzatura, media) e' com'era, byte per byte.
    assertThat(kit.untouchable()).isEqualTo(keep)
    assertThat(kit.exists("test", NasLayout.trashItemFile(kit.deviceId, id(61)).value)).isTrue()
  }

  @Test
  fun eliminatiDiRecenteMostraIRecordValidiEQuelliSenzaRecordMaNonFaEliminareGliAltrui() = runTest {
    val kit = newKit()
    hostileWorld(kit)
    val trash = kit.trashWith()
    trash.refresh()
    val items = (trash.items.value as NasTrashState.Ready).items
    val mine = items.filter { it.deletableNow }
    assertThat(mine.map { it.reason }).containsExactly(TrashReason.DELETE, TrashReason.DELETE)
    assertThat(mine.single { it.isFolder }.fileCount).isEqualTo(2)
    val foreign = items.single { it.ownerDeviceId == otherDevice.deviceId() }
    assertThat(foreign.withoutValidRecord).isFalse()
    assertThat(foreign.restorable).isTrue()
    assertThat(foreign.deletableNow).isFalse()
    // Il file piantato nel mio cestino con un record firmato da un altro: senza record valido (il record non sta al suo posto), mai mio.
    val planted = items.single { it.trashId == id(61) }
    assertThat(planted.withoutValidRecord).isTrue()
    assertThat(planted.restorable).isFalse()
    assertThat(planted.deletableNow).isFalse()
  }

  @Test
  fun lOraCheSaltaOTornaIndietroNonFaEliminareNiente() = runTest {
    val kit = newKit()
    hostileWorld(kit)
    val keepAll = kit.filesIn("test").associateWith { kit.bytes("test", it)!!.toList() }
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    // D32: salto dell'ora oltre 5 minuti → sospesa.
    kit.gate.byShare["test"] = GateStatus.CLOCK_JUMP
    assertThat(kit.trashWith().purgeExpired(1.minutes).stoppedBy).isEqualTo(PurgeStop.BLOCKED)
    kit.gate.byShare.clear()
    // L'ora torna indietro di due mesi: niente e' scaduto.
    kit.clocks.nasNow = kit.clocks.nasNow!! - 60 * day
    assertThat(kit.trashWith().purgeExpired(1.minutes).purged).isEqualTo(0)
    // Ora del NAS non ancorata: nessuna decisione.
    kit.clocks.nasNow = null
    assertThat(kit.trashWith().purgeExpired(1.minutes).stoppedBy).isEqualTo(PurgeStop.NO_NAS_TIME)
    assertThat(kit.filesIn("test").associateWith { kit.bytes("test", it)!!.toList() }).isEqualTo(keepAll)
  }

  @Test
  fun unaVoceDelDiarioConUnOraNelFuturoNonSiElimina() = runTest {
    val kit = newKit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    // Cestinata con l'ora del NAS sbagliata in avanti di un anno (NTP rotto), poi l'ora si corregge.
    kit.clocks.nasNow = kit.clocks.nasNow!! + 365 * day
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    kit.clocks.nasNow = kit.clocks.nasNow!! - 365 * day + TrustLimits.TRASH_RETENTION_MILLIS
    val report = kit.trashWith().purgeExpired(1.minutes)
    assertThat(report.purged).isEqualTo(0)
    assertThat(kit.store.allTrash().single().state).isEqualTo(TrashEntryState.TRASHED)
  }

  @Test
  fun unaVoceCitataDaUnRipristinoInCorsoNonSiElimina() = runTest {
    val kit = newKit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    val entry = kit.store.allTrash().single()
    // Un ripristino avviato e fermo (il file e' aperto dal PC): la voce e' sua finche' non finisce.
    kit.lock("test", entry.trashedPath.value)
    kit.run(OpRequest.Restore(listOf(dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef("test", kit.deviceId, entry.trashId, entry.originalPath, false, entry.size))))
    kit.unlock("test", entry.trashedPath.value)
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    val report = kit.trashWith().purgeExpired(1.minutes)
    assertThat(report.purged).isEqualTo(0)
    assertThat(report.kept[KeepReason.NO_LOCAL_ACTION]).isEqualTo(1)
    kit.operations.resumeAll(1.minutes)
    assertThat(kit.exists("test", "Foto/a.jpg")).isTrue()
  }

  @Test
  fun unGuastoAOgniChiamataELaMorteAOgniPassoNonEliminanoNullaDiTroppo() = runTest {
    // Il conteggio pulito.
    val clean = newKit()
    hostileWorld(clean)
    clean.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    val counter = FaultPlan()
    clean.nas.opsFaults = counter
    clean.nas.readFaults = counter
    val deathCounter = Death()
    assertThat(clean.trashWith(deathCounter).purgeExpired(1.minutes).purged).isEqualTo(2)
    val calls = counter.operations
    val steps = deathCounter.steps
    assertThat(calls).isAtLeast(5)
    for (n in 1..calls) {
      for (phase in FaultPhase.entries) {
        val kit = newKit()
        hostileWorld(kit)
        val keep = kit.untouchable()
        kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        kit.nas.opsFaults = fault
        kit.nas.readFaults = fault
        kit.trashWith().purgeExpired(1.minutes)
        kit.nas.opsFaults = null
        kit.nas.readFaults = null
        val label = "pulizia, guasto $n/$calls $phase"
        assertWithMessage(label).that(fault.fired).hasSize(1)
        kit.checkCoherent(label)
        repeat(3) { kit.trashWith().purgeExpired(1.minutes) }
        assertWithMessage(label).that(kit.store.allTrash().map { it.state }.toSet()).containsExactly(TrashEntryState.PURGED)
        assertWithMessage(label).that(kit.untouchable()).isEqualTo(keep)
      }
    }
    for (n in 1..steps) {
      for (after in listOf(false, true)) {
        val kit = newKit()
        hostileWorld(kit)
        val keep = kit.untouchable()
        kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
        val death = Death(n, after)
        try {
          kit.trashWith(death).purgeExpired(1.minutes)
        } catch (_: ProcessDeath) {
          // si riparte dal diario
        }
        val label = "pulizia, morte al passo $n/$steps ${if (after) "dopo" else "prima"}"
        assertWithMessage(label).that(death.died).isTrue()
        kit.checkCoherent(label)
        repeat(3) { kit.trashWith().purgeExpired(1.minutes) }
        assertWithMessage(label).that(kit.store.allTrash().map { it.state }.toSet()).containsExactly(TrashEntryState.PURGED)
        assertWithMessage(label).that(kit.untouchable()).isEqualTo(keep)
      }
    }
    println("P12 pulizia del cestino: $calls chiamate x 2 fasi, $steps passi x 2")
  }

  /** Un file nel proprio cestino ha sempre la sua voce TRASHED (anche a meta' di un'eliminazione di cartella). */
  private fun NasOpsTestKit.checkCoherent(label: String) {
    val own = ".mycloudgallery/trash/$deviceId/files/"
    for (path in filesIn("test").filter { it.startsWith(own) }) {
      val trashId = path.removePrefix(own).substringBefore('/')
      val entry = store.allTrash().firstOrNull { it.trashId == trashId } ?: continue
      assertWithMessage("$label: $path").that(entry.state).isEqualTo(TrashEntryState.TRASHED)
    }
  }

  @Test
  fun iCampiNuoviDelRecordArrivanoAEliminatiDiRecente() = runTest {
    val kit = newKit()
    kit.putFile("test", "V/a.jpg", byteArrayOf(1, 2))
    kit.run(OpRequest.Move(listOf(kit.itemOf("test", "V/a.jpg")), "test-comune", null))
    // Un altro dispositivo della famiglia legge il cestino: il record dice "move".
    val other = NasOpsTestKit(deviceId = otherDevice.deviceId(), engine = KitEngine.PRODUCTION)
    val view = NasTrashService(
      otherDevice.deviceId(), "test", { listOf("test") }, other.store, kit.nas.fileOps(otherDevice.deviceId()), kit.nas.reader, kit.clocks, kit.gate,
      remote = SignedTrashRecordSource({ kit.nas.fileSystem(it) }, { family.state }, Dispatchers.Unconfined),
    )
    view.refresh()
    val item = (view.items.value as NasTrashState.Ready).items.single()
    assertThat(item.reason).isEqualTo(TrashReason.MOVE)
    assertThat(item.originalPath).isEqualTo(SharePath.parse("V/a.jpg"))
    assertThat(item.deletableNow).isFalse()
    assertThat(item.restorable).isTrue()
    assertThat(item.daysLeft).isEqualTo(30)
    assertThat(TrashRecordReason.MOVE.wire).isEqualTo("move")
  }
}
