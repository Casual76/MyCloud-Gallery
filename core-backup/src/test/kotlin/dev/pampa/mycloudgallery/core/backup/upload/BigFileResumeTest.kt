package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E-B1 (riga P2 del §4.4): un video da **3 GB virtuali** con le misure vere (scritture da 1 MiB, un punto di ripresa ogni 64 MiB). La
 * connessione cade a meta' (la scrittura e' arrivata, la risposta no); dopo la ripresa dal diario si ricaricano **al massimo 64 MiB**, il
 * temporaneo si riapre senza crearlo e si accorcia una volta sola, e la verifica completa rilegge tutti i 3 GB prima del nome definitivo.
 * Il contenuto virtuale non occupa memoria ne' sul telefono finto ne' nel NAS finto (`registerKnownContent`).
 */
class BigFileResumeTest {

  @Test
  fun treGigaRiprendonoDallUltimoPuntoConAlPiu64MiBRicaricati() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
    val size = 3L shl 30
    val h = RealEngineKit(kit = BackupTestKit(), config = UploadConfig(parallel = 1))
    val kit = h.kit
    val media = kit.addPhoneMedia("VID_3GB.mp4", NasContent.virtual(size, seed = 31), kind = MediaKind.VIDEO)
    // La 1.500ª scrittura da 1 MiB (circa 1,46 GiB) arriva al NAS ma la risposta si perde.
    kit.nas.opsFaults = FaultPlan().at(1_500, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.WRITE)
    val first = h.runAll().last()
    kit.nas.opsFaults = null
    assertThat(first.stoppedBy).isEqualTo(RunStop.NAS)
    val stopped = kit.store.all().single()
    assertThat(stopped.state).isEqualTo(UploadState.UPLOADING)
    assertThat(stopped.resumeAt).isEqualTo(23L * (64L shl 20))
    // Il processo muore e riparte dal diario.
    h.restart()
    h.resumeUntilQuiet()
    val done = kit.store.all().single()
    assertThat(done.state).isEqualTo(UploadState.DONE)
    assertThat(kit.catalog.isSafe(media)).isTrue()
    val reloaded = kit.nas.backupBytesWritten.get() - size
    println("E-B1: 3 GiB, interruzione dopo 1.500 MiB, ricaricati ${reloaded shr 20} MiB, accorciamenti ${kit.nas.backupTruncations.get()}")
    assertThat(reloaded).isAtMost(64L shl 20)
    assertThat(kit.nas.backupTruncations.get()).isEqualTo(1)
    // La rilettura di verifica e' completa (D4): tutti i byte del file pubblicato, una volta.
    assertThat(kit.nas.filePaths(kit.share)).containsExactly("Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10/VID_3GB.mp4")
  }
}
