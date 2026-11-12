package dev.pampa.mycloudgallery.core.backup.safety

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * PU-2 della revisione del passo unico (12/11/2026): prima che l'utente scelga il primo backup ("Inizia ora" o "Stanotte": la cartella del dispositivo
 * e' ancora null, la pagina dice "Primo backup pronto") il guardiano delle copie private non scrive niente, nemmeno con un candidato valido (un media
 * proprio con la sola copia nell'area comune, che il telefono non ha piu') e il cancello del backup aperto. Dopo la scelta lavora come prima.
 */
class FirstBackupGuardianTest {

  private val bench = SafetyBench()
  private val kit = bench.kit
  private var chosen = false

  private fun guardian(): BackupPrivateCopyGuardian = BackupPrivateCopyGuardian(
    deviceId = kit.deviceId,
    ops = kit.ops,
    reader = kit.nas.reader,
    store = kit.store,
    catalog = kit.catalog,
    phone = kit.phone.port,
    gate = FirstBackupGate(kit.gate) { chosen },
    clock = kit.clock,
    privateShare = { kit.share },
    commonShare = { kit.commonShare },
  )

  private fun writes(): List<FakeNasOp> = synchronized(bench.touched) { bench.touched.map { it.first }.filter { it in WRITE_OPS } }

  @Test
  fun primaDellaSceltaDelPrimoBackupNessunaPrimitivaDiScrittura() = runTest {
    val start = bench.commonOnly("IMG_1.jpg", SafetyBench.bytes(40_000, 1))
    val before = kit.snapshot()
    bench.recordShares()
    assertThat(kit.gate.stateOf(kit.share)).isEqualTo(BackupGateState.OPEN)

    // Zero tocchi: il giro di "tutti" e quello dei media chiesti ("Libera spazio", una richiesta dopo una condivisione) si fermano al cancello.
    for (scope in listOf(GuardScope.All, GuardScope.Media(setOf(start.mediaId)))) {
      val report = guardian().ensure(scope, 1.hours)
      assertWithMessage("$scope").that(report.stoppedBy).isEqualTo(RunStop.GATE)
      assertThat(report.created).isEqualTo(0)
      assertThat(report.linked).isEqualTo(0)
    }
    assertWithMessage("primitive di scrittura chiamate prima della scelta").that(writes()).isEmpty()
    assertThat(kit.nas.serverCopies.get()).isEqualTo(0)
    assertThat(kit.nas.backupBytesWritten.get()).isEqualTo(0L)
    assertThat(kit.store.all()).isEmpty()
    assertThat(kit.snapshot().files.mapValues { (_, f) -> f.sha to f.fileId }).isEqualTo(before.files.mapValues { (_, f) -> f.sha to f.fileId })
    assertThat(kit.catalog.isSafe(start.mediaId)).isFalse()

    // L'utente sceglie il primo backup: il guardiano lavora come prima (copia, verifica, pubblicazione in `Backup/Area comune/`).
    chosen = true
    val report = guardian().ensure(GuardScope.All, 1.hours)
    assertThat(report.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(report.created).isEqualTo(1)
    assertThat(writes()).isNotEmpty()
    assertThat(kit.nas.bytes(kit.share, "${bench.guardDir()}/IMG_1.jpg")!!.toList()).isEqualTo(start.data.toList())
    assertThat(bench.guardItems().single().state).isEqualTo(UploadState.DONE)
    assertThat(kit.catalog.isSafe(start.mediaId)).isTrue()
  }

  /** Dopo la scelta il cancello del primo backup non toglie niente alle ragioni di sempre: la pausa dell'utente, il congelamento, l'emergenza. */
  @Test
  fun dopoLaSceltaValgonoLeRagioniDiSempre() {
    val gate = FirstBackupGate(kit.gate) { chosen }
    chosen = false
    for (state in BackupGateState.entries) {
      kit.gate.default = state
      assertWithMessage("$state, primo backup non scelto").that(gate.stateOf(kit.share)).isEqualTo(BackupGateState.NOT_READY)
    }
    chosen = true
    for (state in BackupGateState.entries) {
      kit.gate.default = state
      assertWithMessage("$state, primo backup scelto").that(gate.stateOf(kit.share)).isEqualTo(state)
    }
  }

  private companion object {
    /** Le primitive che scrivono sul NAS (core-nas, `FakeNasOp`), compresa la copia lato server. */
    val WRITE_OPS: Set<FakeNasOp> = setOf(
      FakeNasOp.SERVER_COPY, FakeNasOp.MKDIRS, FakeNasOp.RENAME, FakeNasOp.COPY_TO_TEMP, FakeNasOp.OPEN_TEMP, FakeNasOp.WRITE_TEMP,
      FakeNasOp.PUBLISH_TEMP, FakeNasOp.TRASH_MOVE, FakeNasOp.DELETE, FakeNasOp.RMDIR, FakeNasOp.REOPEN_TEMP, FakeNasOp.ARCHIVE_VERSION,
      FakeNasOp.RESTORE_VERSION,
    )
  }
}
