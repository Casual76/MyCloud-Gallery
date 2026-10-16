package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.GuardSource
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * L'esecutore di riferimento del backup (§2.2) scenario per scenario: cio' che P6 usa per il banco prima di P2 e P3, e cio' che il
 * motore vero deve fare uguale (E-B1, E-B3, E-B4, E-B5, E-B15, E-B18, E-P12, E-P15, D12). Ogni scenario finisce con gli invarianti
 * del §5.4 veri.
 */
class ReferenceEngineTest {

  private val kit = BackupTestKit()
  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private suspend fun assertClean(before: BackupInvariants.Snapshot) {
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = true)).isEmpty()
  }

  @Test
  fun unaFotoNuovaArrivaAlSicuroNellaCartellaDelDispositivo() = runTest {
    val data = bytes(40_000, 1)
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    val before = kit.snapshot()
    kit.runAll()
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")!!.toList()).isEqualTo(data.toList())
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(kit.engine.status.value).isInstanceOf(BackupStatus.UpToDate::class.java)
    assertClean(before)
  }

  @Test
  fun conLeShareVereNienteSiScriveD12() = runTest {
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(10_000, 2)))
    kit.gate.default = BackupGateState.TEST_ONLY
    val before = kit.snapshot()
    val result = kit.runAll().first()
    assertThat(result.stoppedBy).isEqualTo(RunStop.GATE)
    assertThat(result.problem).isEqualTo(BackupProblemCode.TEST_ONLY)
    assertThat(kit.engine.status.value).isEqualTo(BackupStatus.Problem(BackupProblemCode.TEST_ONLY, 0))
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)
    assertThat(kit.store.all()).isEmpty()
  }

  /** E-B1, D5: la connessione cade a meta' di un file grande; si riprende dall'ultimo punto, al massimo un intervallo ricaricato. */
  @Test
  fun unFileGrandeRiprendeDallUltimoPuntoDiRipresa() = runTest {
    val data = bytes(200_000, 3)
    val media = kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    val before = kit.snapshot()
    kit.nas.opsFaults = FaultPlan().at(8, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.WRITE)
    val first = kit.runAll()
    assertThat(first.last().stoppedBy).isEqualTo(RunStop.NAS)
    kit.nas.opsFaults = null
    val point = kit.store.all().single().resumeAt
    assertThat(point).isGreaterThan(0L)
    kit.restart()
    kit.resumeUntilQuiet()
    assertThat(kit.nas.backupTruncations.get()).isEqualTo(1)
    // Byte scritti in tutto: il file piu' al massimo un intervallo fra due punti di ripresa (piu' il pezzo perso).
    assertThat(kit.nas.backupBytesWritten.get()).isAtMost(data.size + kit.checkpointBytes + kit.chunkBytes)
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertClean(before)
  }

  /** E-B3: il nome e' di un altro file → "nome (2)", il file che c'era resta byte per byte. */
  @Test
  fun nomeOccupatoDaUnAltroFile() = runTest {
    val data = bytes(30_000, 4)
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    kit.putNasFile(kit.share, "$dir/IMG_1.jpg", NasContent.of(bytes(31_000, 5)))
    val before = kit.snapshot()
    kit.runAll()
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1 (2).jpg")!!.toList()).isEqualTo(data.toList())
    assertThat(kit.store.all().single().nameAttempt).isEqualTo(1)
    assertClean(before)
  }

  /** E-B3, E-B16: il nome e' di un file identico (non ancora visto dalla scansione) → collegato, nessun file nuovo. */
  @Test
  fun nomeOccupatoDaUnFileIdenticoSiCollega() = runTest {
    val data = bytes(30_000, 6)
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    kit.putNasFile(kit.share, "$dir/IMG_1.jpg", NasContent.of(data), knownToCatalog = false)
    val before = kit.snapshot()
    kit.runAll()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.LINKED_EXISTING)
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertThat(kit.snapshot().files.keys.filter { !it.contains(".mycloudgallery") }).isEqualTo(before.files.keys.toList())
    assertClean(before)
  }

  /** D8, E-B5, §2.11 punto 2: una copia identica in un vecchio backup dell'area privata si collega dopo la rilettura, senza caricare. */
  @Test
  fun unaCopiaIdenticaInUnVecchioBackupSiCollegaSenzaCaricare() = runTest {
    val data = bytes(30_000, 7)
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    kit.putNasFile(kit.share, "Backup telefono 2023/IMG_1.jpg", NasContent.of(data))
    val before = kit.snapshot()
    kit.runAll()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.LINKED_EXISTING)
    assertThat(kit.nas.backupBytesWritten.get()).isEqualTo(0L)
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertClean(before)
  }

  /** E-B4: cancellata dal telefono prima del backup → `GONE`, "persa prima del backup", niente sul NAS. */
  @Test
  fun unaFotoCancellataPrimaDelBackupNonArrivaEDiceIlPerche() = runTest {
    val lost = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 8)))
    val kept = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(20_000, 9)))
    kit.runAll(budget = kotlin.time.Duration.ZERO) // pianifica e basta
    kit.phone.delete(kit.phoneIdOf(lost))
    val before = kit.snapshot()
    kit.resumeUntilQuiet()
    val items = kit.store.all().associateBy { it.mediaId }
    assertThat(items.getValue(lost).state).isEqualTo(UploadState.GONE)
    assertThat(kit.catalog.problemOf(lost)).isEqualTo(BackupProblemCode.LOST_BEFORE_BACKUP)
    assertThat(kit.catalog.isSafe(kept)).isTrue()
    assertClean(before)
  }

  /** E-B15: il file viene riscritto a meta' lettura → la versione non c'e' piu' (`GONE` senza problema), nessun byte misto pubblicato. */
  @Test
  fun unFileRiscrittoAMetaLetturaNonPubblicaByteMisti() = runTest {
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(60_000, 10)))
    kit.phone.duringRead(kit.phoneIdOf(media), afterBytes = 16_000, event = FakePhoneBytes.MidRead.Rewritten(NasContent.of(bytes(60_000, 11)), 9_000))
    val before = kit.snapshot()
    kit.resumeUntilQuiet()
    val item = kit.store.all().single()
    assertThat(item.state).isEqualTo(UploadState.GONE)
    assertThat(item.problem).isNull()
    assertThat(kit.catalog.problemOf(media)).isNull()
    assertClean(before)
  }

  /** E-P12: il MediaStore rinumera gli `_ID` a meta' lettura → si aspetta che il catalogo ritrovi la riga, poi si finisce. */
  @Test
  fun lIdCambiatoAMetaLetturaSiRitrovaDalCatalogo() = runTest {
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(50_000, 12)))
    kit.phone.duringRead(kit.phoneIdOf(media), afterBytes = 1, event = FakePhoneBytes.MidRead.Renumbered("v2", 777))
    val before = kit.snapshot()
    kit.runAll()
    // La lettura e' finita, ma il vecchio _ID non vale piu': niente hash registrato, l'elemento aspetta la riga nuova.
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.PLANNED)
    kit.catalog.relinkPhone(media, kit.phone.ref(777))
    kit.resumeUntilQuiet()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertClean(before)
  }

  /** E-B18, D4: il NAS restituisce byte diversi a ogni caricamento → dopo 3 verifiche `FAILED` con `VERIFY_FAILED`, mai al sicuro. */
  @Test
  fun treVerificheNonTornateFermanoLElementoSenzaPubblicare() = runTest {
    val reads = AtomicInteger(0)
    val unstable = object : NasContent {
      override val size: Long = 20_000

      override fun copy(offset: Long, dst: ByteArray, dstOffset: Int, len: Int) {
        val salt = reads.incrementAndGet()
        for (i in 0 until len) dst[dstOffset + i] = ((offset + i) * 31 + salt).toByte()
      }
    }
    val media = kit.addPhoneMedia("IMG_1.jpg", unstable)
    val before = BackupInvariants.Snapshot(kit.snapshot().files, emptySet())
    kit.resumeUntilQuiet()
    val item = kit.store.all().single()
    assertThat(item.state).isEqualTo(UploadState.FAILED)
    assertThat(item.verifyAttempts).isEqualTo(3)
    assertThat(kit.catalog.problemOf(media)).isEqualTo(BackupProblemCode.VERIFY_FAILED)
    assertThat(kit.catalog.isSafe(media)).isFalse()
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)
  }

  /**
   * D9 (il guardiano, sull'esecutore di riferimento): un media proprio con la sola copia nell'area comune riceve la copia privata con la
   * copia lato server, verificata contro l'hash della versione e pubblicata in `Backup/Area comune/AAAA/MM/`; la sorgente resta com'era.
   */
  @Test
  fun ilGuardianoCreaLaCopiaPrivataDallAreaComune() = runTest {
    val data = bytes(30_000, 14)
    val media = MediaId.random()
    val version = VersionId.random()
    kit.catalog.put(
      InMemoryBackupCatalog.Media(
        mediaId = media, versionId = version, kind = MediaKind.PHOTO, size = data.size.toLong(), quickHash = null, phone = null,
        phoneStamp = null, source = BackupSource.Camera, localDay = 20261012, fileName = "IMG_9.jpg", sortTime = 1, fullHash = Sha256.of(data),
      ),
    )
    val source = SharePath.parse("Foto/IMG_9.jpg")
    val fileId = kit.nas.putFile(kit.commonShare, source.value, NasContent.of(data), modifiedAt = 5_000)
    val stamp = NasStamp(data.size.toLong(), 5_000, fileId)
    kit.catalog.putCopy(media, version, kit.commonShare, source, stamp)
    val before = kit.snapshot()
    kit.store.create(
      listOf(
        NewBackupItem(
          kind = UploadKind.GUARD_COPY, mediaId = media, versionId = version, size = data.size.toLong(), phone = null, expected = null,
          share = kit.share, planned = SharePath.parse("Backup/Area comune/2026/10/IMG_9.jpg"), sha256 = Sha256.of(data),
          guardSource = GuardSource(kit.commonShare, source, stamp),
        ),
      ),
    )
    kit.engine.resumeAll(kotlin.time.Duration.parse("1h"))
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(kit.nas.bytes(kit.share, "Backup/Area comune/2026/10/IMG_9.jpg")!!.toList()).isEqualTo(data.toList())
    assertThat(kit.nas.bytes(kit.commonShare, source.value)!!.toList()).isEqualTo(data.toList())
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertClean(before)
  }

  /** E-P15: permessi ritirati → il giro si ferma con il codice, l'elemento aspetta. */
  @Test
  fun iPermessiRitiratiFermanoIlGiro() = runTest {
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(10_000, 13)))
    kit.phone.denied = PhoneDenied.MEDIA_PERMISSION
    val result = kit.runAll().first()
    assertThat(result.stoppedBy).isEqualTo(RunStop.PHONE)
    assertThat(result.problem).isEqualTo(BackupProblemCode.MEDIA_PERMISSION)
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.PLANNED)
    kit.phone.denied = null
    kit.resumeUntilQuiet()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
  }
}
