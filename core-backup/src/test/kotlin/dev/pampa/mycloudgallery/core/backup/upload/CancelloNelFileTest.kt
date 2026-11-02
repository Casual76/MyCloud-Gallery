package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Revisione 5A, I4 (correzione D della Fase 10): "Ferma le scritture sul NAS" (e ogni cancello che vale per tutto: blocco del manifest, pausa,
 * famiglia non pronta) ferma anche il file **in corso**, al primo pezzo dopo, con il punto di ripresa dove si e' arrivati. Prima il cancello si
 * guardava solo fra un passo e l'altro: un video da molti GB continuava a scrivere il suo temporaneo fino alla fine.
 */
class CancelloNelFileTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 31 + seed * 17 + (it shr 9)).toByte() }

  /** Le primitive del banco con un gancio dopo ogni scrittura di un temporaneo (qui: arriva l'emergenza). */
  private class HookedOps(private val inner: NasBackupOps, private val afterWrite: () -> Unit) : NasBackupOps by inner {
    var writes = 0
      private set

    override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> =
      when (val opened = inner.openTemp(share, temp, purpose)) {
        is NasResult.Err -> opened
        is NasResult.Ok -> NasResult.Ok(Hooked(opened.value))
      }

    private inner class Hooked(private val writer: BackupTempWriter) : BackupTempWriter by writer {
      override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> {
        val result = writer.write(offset, buf, off, len)
        writes++
        afterWrite()
        return result
      }
    }
  }

  private fun engine(kit: BackupTestKit, ops: NasBackupOps) = SafeUploadEngine(
    deviceId = kit.deviceId,
    share = kit.share,
    ops = ops,
    reader = kit.nas.reader,
    store = kit.store,
    catalog = kit.catalog,
    phone = kit.phone.port,
    gate = kit.gate,
    clock = kit.clock,
    planner = kit.planner,
    folder = { kit.folder },
    existing = RereadingCopyFinder(kit.catalog, kit.nas.reader, kit.clock),
    facts = KitFacts(kit.catalog),
    config = UploadConfig(chunkBytes = kit.chunkBytes, checkpointBytes = kit.checkpointBytes, parallel = 1, lockedFirstWaitMs = 10, lockedRetries = 3),
    sleep = { ms -> kit.clock.advance(ms) },
  )

  @Test
  fun i4_FermaLeScrittureAMetaFileFermaIlFileAlPezzoDopoEPoiRiparte() = runTest {
    val kit = BackupTestKit()
    val content = bytes(10 * kit.chunkBytes, 1) // dieci pezzi
    kit.addPhoneMedia("VID_LUNGO.jpg", NasContent.of(content))
    var emergency = false
    val ops = HookedOps(kit.ops) {
      if (!emergency) {
        emergency = true
        kit.gate.default = BackupGateState.WRITES_STOPPED // l'utente tocca "Ferma le scritture sul NAS" mentre il primo pezzo sale
      }
    }

    val result = engine(kit, ops).run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)

    assertWithMessage("giro: $result").that(result.stoppedBy).isEqualTo(RunStop.GATE)
    assertThat(result.problem).isEqualTo(BackupProblemCode.WRITES_STOPPED)
    // Il file si e' fermato al pezzo dopo l'emergenza (prima: tutti e dieci i pezzi nel temporaneo, poi lo stop fra i passi).
    assertWithMessage("scritture nel temporaneo").that(ops.writes).isEqualTo(1)
    assertThat(kit.nas.filePaths(kit.share).filter { it.startsWith("Backup/") }).isEmpty()
    val item = kit.store.all().single()
    assertThat(item.state).isEqualTo(UploadState.UPLOADING)
    assertWithMessage("punto di ripresa dove si e' arrivati").that(item.resumeAt).isEqualTo(kit.chunkBytes.toLong())

    // Tolta l'emergenza il file riparte e arriva intero, una volta sola.
    kit.gate.default = BackupGateState.OPEN
    val again = engine(kit, kit.ops).resumeAll(1.hours)
    assertWithMessage("ripresa: $again").that(again.stoppedBy).isEqualTo(RunStop.COMPLETE)
    val published = kit.nas.filePaths(kit.share).filter { it.startsWith("Backup/") }
    assertThat(published).hasSize(1)
    assertThat(kit.nas.bytes(kit.share, published.single())?.toList()).isEqualTo(content.toList())
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
  }

  @Test
  fun i4_LaPausaAMetaFileFermaIlFileComeIlTastoFerma() = runTest {
    val kit = BackupTestKit()
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(6 * kit.chunkBytes, 2)))
    val ops = HookedOps(kit.ops) { kit.gate.default = BackupGateState.PAUSED }

    val result = engine(kit, ops).run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)

    assertWithMessage("giro: $result").that(result.stoppedBy).isEqualTo(RunStop.STOPPED)
    assertThat(ops.writes).isEqualTo(1)
    assertThat(kit.store.all().single().state.terminal).isFalse()
  }
}
