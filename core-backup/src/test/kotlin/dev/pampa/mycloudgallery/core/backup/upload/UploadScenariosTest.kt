package dev.pampa.mycloudgallery.core.backup.upload

import android.media.MediaDataSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneFile
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il motore vero (§2.2) scenario per scenario, con gli edge case della riga P2 del §4.4: E-B2, E-B3, E-B5 (tramite la porta), E-B15,
 * E-B18, E-R9, E-N13, E-P12, i temporanei solo `<io>-bk-`, i due file in volo e la lettura a priorita' bassa con l'app in primo piano.
 * Ogni scenario finisce con gli invarianti del §5.4 veri.
 */
class UploadScenariosTest {

  private val h = RealEngineKit()
  private val kit get() = h.kit
  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private suspend fun assertClean(before: BackupInvariants.Snapshot) {
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = true)).isEmpty()
  }

  @Test
  fun unaFotoArrivaVerificataEAlSicuro() = runTest {
    val data = bytes(40_000, 1)
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    val before = kit.snapshot()
    val result = h.runAll().first()
    assertThat(result.done).isEqualTo(1)
    assertThat(result.bytesUploaded).isEqualTo(data.size.toLong())
    assertThat(result.bytesReread).isEqualTo(data.size.toLong())
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")!!.toList()).isEqualTo(data.toList())
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(h.engine.status.value).isInstanceOf(BackupStatus.UpToDate::class.java)
    assertClean(before)
  }

  /** E-B2: il processo muore a meta' caricamento (dopo un punto di ripresa): dal PC nessun file a meta', solo il temporaneo nascosto. */
  @Test
  fun eB2_unaMorteAMetaLasciaSoloIlTemporaneoNascosto() = runTest {
    val data = bytes(100_000, 2)
    kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    val before = kit.snapshot()
    // Le scritture: creazione, hash nel catalogo, HASHED, UPLOADING, primo punto di ripresa (32 KiB): il processo muore subito dopo.
    val death = dev.pampa.mycloudgallery.core.backup.testing.BackupDeath(dieAt = 5, afterEffect = true)
    try {
      h.runAll(h.dyingEngine(death))
    } catch (_: dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath) {
    }
    assertThat(death.died).isTrue()
    val item = kit.store.all().single()
    assertThat(item.state).isEqualTo(UploadState.UPLOADING)
    assertThat(item.resumeAt).isGreaterThan(0L)
    assertThat(kit.nas.filePaths(kit.share).filter { !it.startsWith(".mycloudgallery/") }).isEmpty()
    assertThat(kit.nas.filePaths(kit.share)).containsExactly(NasBackupPaths.tempFor(kit.deviceId, item.id.value).value)
    assertThat(kit.violations(before, final = false)).isEmpty()
    h.restart()
    h.resumeUntilQuiet()
    assertThat(kit.nas.backupTruncations.get()).isEqualTo(1)
    assertThat(kit.nas.bytes(kit.share, "$dir/VID_1.mp4")!!.toList()).isEqualTo(data.toList())
    assertClean(before)
  }

  /** E-B3: un file diverso e uno identico agli stessi nomi; "nome (2)" scritto nel diario prima di riprovare, mai una sostituzione. */
  @Test
  fun eB3_unFileDiversoEUnoIdenticoAgliStessiNomi() = runTest {
    val diverso = bytes(30_000, 3)
    val identico = bytes(25_000, 4)
    val a = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(diverso))
    val b = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(identico))
    kit.putNasFile(kit.share, "$dir/IMG_1.jpg", NasContent.of(bytes(31_000, 5)), knownToCatalog = false)
    kit.putNasFile(kit.share, "$dir/IMG_1 (2).jpg", NasContent.of(bytes(32_000, 6)), knownToCatalog = false)
    kit.putNasFile(kit.share, "$dir/IMG_2.jpg", NasContent.of(identico), knownToCatalog = false)
    val before = kit.snapshot()
    h.resumeUntilQuiet()
    val items = kit.store.all().associateBy { it.mediaId }
    assertThat(items.getValue(a).state).isEqualTo(UploadState.DONE)
    assertThat(items.getValue(a).finalPath!!.value).isEqualTo("$dir/IMG_1 (3).jpg")
    assertThat(items.getValue(a).nameAttempt).isEqualTo(2)
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1 (3).jpg")!!.toList()).isEqualTo(diverso.toList())
    assertThat(items.getValue(b).state).isEqualTo(UploadState.LINKED_EXISTING)
    assertThat(kit.catalog.isSafe(a) && kit.catalog.isSafe(b)).isTrue()
    // I nomi "(n)" si scrivono nel diario prima della pubblicazione che li usa.
    val transitions = kit.store.transitions.filter { it.item == items.getValue(a).id }.map { it.to }
    assertThat(transitions.count { it == UploadState.PUBLISHING }).isAtLeast(3)
    assertClean(before)
  }

  /** E-B5 tramite la porta (D8): la deduplica di P3 trova la copia identica; niente si scrive sul NAS. */
  @Test
  fun eB5_laCopiaIdenticaSiCollegaTramiteLaPortaSenzaScrivere() = runTest {
    val data = bytes(30_000, 7)
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    kit.putNasFile(kit.share, "Vecchi/IMG_1.jpg", NasContent.of(data))
    val finder = RereadingCopyFinder(kit.catalog, kit.nas.reader, kit.clock)
    h.finder = { finder }
    h.restart()
    val before = kit.snapshot()
    h.runAll()
    assertThat(finder.calls.get()).isEqualTo(1)
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.LINKED_EXISTING)
    assertThat(kit.nas.opCount(FakeNasOp.OPEN_TEMP)).isEqualTo(0)
    assertThat(kit.nas.backupBytesWritten.get()).isEqualTo(0L)
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertClean(before)
  }

  /** Una porta della deduplica che propone una copia di un altro media o di un'altra share non fa collegare niente: si carica. */
  @Test
  fun unaCopiaPropostaChenonTornaNonSiCollega() = runTest {
    val data = bytes(30_000, 8)
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    h.finder = { _ ->
      dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyFinder { c, _, _ ->
        dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyResult.Found(
          dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy(
            dev.pampa.mycloudgallery.core.model.MediaId.random(), c.versionId, kit.share, SharePath.parse("Altro/IMG_1.jpg"),
            dev.pampa.mycloudgallery.core.model.NasStamp(c.size, 1, 1), 1,
          ),
        )
      }
    }
    h.restart()
    val before = kit.snapshot()
    h.runAll()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")!!.toList()).isEqualTo(data.toList())
    assertClean(before)
  }

  /** E-B15: il file del telefono cambia dopo la lettura (prima del caricamento) e a meta' caricamento: si scarta, il temporaneo si toglie. */
  @Test
  fun eB15_unFileCambiatoPrimaODuranteIlCaricamentoSiScarta() = runTest {
    val first = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(40_000, 9)))
    val before = kit.snapshot()
    // 1) Il temporaneo della foto non si crea (guasto prima): l'elemento resta in UPLOADING; Samsung "Salva" la riscrive.
    kit.nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.CREATE)
    h.runAll()
    kit.nas.opsFaults = null
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.UPLOADING)
    kit.phone.rewrite(kit.phoneIdOf(first), NasContent.of(bytes(40_000, 11)), modifiedMs = 9_000)
    // 2) Un video si ferma dopo un punto di ripresa (guasto alla quarta scrittura), poi viene riscritto.
    val second = kit.addPhoneMedia("VID_2.mp4", NasContent.of(bytes(120_000, 10)), kind = MediaKind.VIDEO)
    kit.nas.opsFaults = FaultPlan().at(4, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.WRITE)
    h.restart()
    h.runAll()
    kit.nas.opsFaults = null
    val video = kit.store.all().first { it.mediaId == second }
    assertThat(video.state).isEqualTo(UploadState.UPLOADING)
    assertThat(video.resumeAt).isGreaterThan(0L)
    assertThat(kit.nas.exists(kit.share, video.temp!!.value)).isTrue()
    kit.phone.rewrite(kit.phoneIdOf(second), NasContent.of(bytes(120_000, 12)), modifiedMs = 9_500)
    h.restart()
    h.resumeUntilQuiet()
    for (item in kit.store.all()) {
      assertThat(item.state).isEqualTo(UploadState.GONE)
      assertThat(item.problem).isNull()
    }
    // Nessun byte misto pubblicato e nessun temporaneo rimasto (gli invarianti lo dicono anche per i file visibili).
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    assertClean(BackupInvariants.Snapshot(before.files, before.phoneShas + kit.phone.allSha256()))
  }

  /** E-B18: il NAS restituisce byte diversi a ogni caricamento: tre verifiche, poi `FAILED` con `VERIFY_FAILED`, mai pubblicato. */
  @Test
  fun eB18_treVerificheNonTornateFermanoLElemento() = runTest {
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
    h.resumeUntilQuiet()
    val item = kit.store.all().single()
    assertThat(item.state).isEqualTo(UploadState.FAILED)
    assertThat(item.problem).isEqualTo(BackupProblemCode.VERIFY_FAILED)
    assertThat(item.verifyAttempts).isEqualTo(3)
    assertThat(kit.catalog.problemOf(media)).isEqualTo(BackupProblemCode.VERIFY_FAILED)
    assertThat(kit.catalog.isSafe(media)).isFalse()
    assertThat(kit.snapshot().files.keys).isEqualTo(before.files.keys)
  }

  /** E-R9: `DISK_FULL` a meta': il temporaneo si toglie, il giro si ferma con `NAS_FULL`; con lo spazio di nuovo, si riprende. */
  @Test
  fun eR9_ilDiscoPienoAMetaTogliIlTemporaneoEFermaIlGiro() = runTest {
    val data = bytes(100_000, 13)
    val media = kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    val before = kit.snapshot()
    kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 40, freeBytes = 50_000, callerFreeBytes = 50_000) }
    val result = h.runAll().last()
    assertThat(result.stoppedBy).isEqualTo(RunStop.NAS)
    assertThat(result.problem).isEqualTo(BackupProblemCode.NAS_FULL)
    assertThat(h.engine.status.value).isEqualTo(BackupStatus.Problem(BackupProblemCode.NAS_FULL, 0))
    val item = kit.store.all().single()
    assertThat(item.state).isEqualTo(UploadState.UPLOADING)
    assertThat(item.resumeAt).isEqualTo(0L)
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    assertThat(kit.violations(before, final = false)).isEmpty()
    kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 40, freeBytes = 1L shl 39, callerFreeBytes = 1L shl 39) }
    h.resumeUntilQuiet()
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertClean(before)
  }

  /** E-N13 (§2.6): la quota dell'account esaurita a meta' (il NAS non e' pieno): `QUOTA`, il temporaneo si toglie. */
  @Test
  fun eN13_laQuotaEsauritaAMetaDaQuota() = runTest {
    kit.addPhoneMedia("VID_1.mp4", NasContent.of(bytes(100_000, 14)), kind = MediaKind.VIDEO)
    val before = kit.snapshot()
    kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 40, freeBytes = 1L shl 39, callerFreeBytes = 40_000) }
    val result = h.runAll().last()
    assertThat(result.problem).isEqualTo(BackupProblemCode.QUOTA)
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 40, freeBytes = 1L shl 39, callerFreeBytes = 1L shl 39) }
    h.resumeUntilQuiet()
    assertClean(before)
  }

  /** §2.2 "Spazio": prima di un file "grande" si guarda lo spazio (con il margine) e senza spazio niente si scrive. */
  @Test
  fun unFileGrandeSenzaSpazioNonComincia() = runTest {
    val small = RealEngineKit(
      config = UploadConfig(chunkBytes = 16 * 1024, checkpointBytes = 32L * 1024, spaceCheckBytes = 50_000, spaceMarginBytes = 10_000),
    )
    small.kit.addPhoneMedia("VID_1.mp4", NasContent.of(bytes(100_000, 21)), kind = MediaKind.VIDEO)
    small.kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 40, freeBytes = 1L shl 39, callerFreeBytes = 105_000) }
    val result = small.runAll().last()
    assertThat(result.problem).isEqualTo(BackupProblemCode.QUOTA)
    assertThat(small.kit.store.all().single().state).isEqualTo(UploadState.HASHED)
    assertThat(small.kit.nas.opCount(FakeNasOp.OPEN_TEMP)).isEqualTo(0)
    small.kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 40, freeBytes = 1L shl 39, callerFreeBytes = 110_000) }
    small.resumeUntilQuiet()
    assertThat(small.kit.store.all().single().state).isEqualTo(UploadState.DONE)
  }

  /** E-N13: la pubblicazione trova il file aperto da un altro programma: attese crescenti nello stesso giro, poi il prossimo giro. */
  @Test
  fun eN13_unFileApertoDalPcSiAspettaConAtteseCrescenti() = runTest {
    val data = bytes(20_000, 15)
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    val before = kit.snapshot()
    // Il temporaneo si blocca appena prima della prima pubblicazione (come un file tenuto aperto dal PC).
    var locked: String? = null
    kit.nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, share, path ->
      if (op == FakeNasOp.PUBLISH_TEMP && locked == null && path != null) {
        locked = path.value
        kit.nas.lock(share, path.value)
      }
      null
    }
    val result = h.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    // Tre attese crescenti nello stesso giro (10, 20, 40 ms nel banco), poi l'elemento aspetta il prossimo giro: niente di pubblicato.
    assertThat(result.done).isEqualTo(0)
    assertThat(result.waiting).isEqualTo(1)
    assertThat(h.sleeps).containsExactly(10L, 20L, 40L).inOrder()
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.PUBLISHING)
    assertThat(kit.nas.filePaths(kit.share).filter { !it.startsWith(".mycloudgallery/") }).isEmpty()
    kit.nas.unlock(kit.share, locked!!)
    kit.nas.fault = null
    h.resumeUntilQuiet()
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")!!.toList()).isEqualTo(data.toList())
    assertClean(before)
  }

  /** E-N13: il PC chiude il file durante le attese: si pubblica nello stesso giro. */
  @Test
  fun eN13_ilFileSiSbloccaDuranteLeAttese() = runTest {
    val data = bytes(20_000, 22)
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    var locked: String? = null
    kit.nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, share, path ->
      if (op == FakeNasOp.PUBLISH_TEMP && locked == null && path != null) {
        locked = path.value
        kit.nas.lock(share, path.value)
      }
      null
    }
    h.sleepHook = { if (h.sleeps.size == 2) kit.nas.unlock(kit.share, locked!!) }
    val result = h.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    kit.nas.fault = null
    assertThat(result.done).isEqualTo(1)
    assertThat(h.sleeps).containsExactly(10L, 20L).inOrder()
    assertThat(kit.nas.bytes(kit.share, "$dir/IMG_1.jpg")!!.toList()).isEqualTo(data.toList())
  }

  /** E-P12: il MediaStore rinumera gli `_ID` a meta' lettura e a meta' caricamento: si aspetta la riga nuova dal catalogo, poi si finisce. */
  @Test
  fun eP12_lIdCambiatoSiRitrovaDalCatalogo() = runTest {
    val data = bytes(80_000, 16)
    val media = kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    kit.phone.duringRead(kit.phoneIdOf(media), afterBytes = 1, event = FakePhoneBytes.MidRead.Renumbered("v2", 777))
    val before = kit.snapshot()
    h.runAll()
    // La lettura e' finita, ma il vecchio _ID non vale piu': niente hash registrato, l'elemento aspetta la riga nuova.
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.PLANNED)
    kit.catalog.relinkPhone(media, kit.phone.ref(777))
    // Il giro dopo si ferma prima del temporaneo (guasto), poi la riga si rinumera a meta' caricamento: i byte sono gli stessi.
    kit.nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.CREATE)
    h.runAll()
    kit.nas.opsFaults = null
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.UPLOADING)
    kit.phone.duringRead(777, afterBytes = 30_000, event = FakePhoneBytes.MidRead.Renumbered("v3", 888))
    h.restart()
    h.resumeUntilQuiet()
    assertThat(kit.phone.firedEvents()).isEqualTo(2)
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(kit.nas.bytes(kit.share, "$dir/VID_1.mp4")!!.toList()).isEqualTo(data.toList())
    assertClean(before)
  }

  /** E-B4: cancellata prima del backup: `GONE` con "persa prima del backup"; il giro continua con le altre. */
  @Test
  fun eB4_unaFotoCancellataDiceIlPerche() = runTest {
    val lost = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 17)))
    val kept = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(20_000, 18)))
    h.runAll(budget = kotlin.time.Duration.ZERO)
    kit.phone.delete(kit.phoneIdOf(lost))
    val before = kit.snapshot()
    h.resumeUntilQuiet()
    val items = kit.store.all().associateBy { it.mediaId }
    assertThat(items.getValue(lost).state).isEqualTo(UploadState.GONE)
    assertThat(kit.catalog.problemOf(lost)).isEqualTo(BackupProblemCode.LOST_BEFORE_BACKUP)
    assertThat(kit.catalog.isSafe(kept)).isTrue()
    assertClean(before)
  }

  /** D15, E-M12: un file che non si legge (errori di lettura con la riga invariata) o vuoto: `UNREADABLE`, mai al sicuro. */
  @Test
  fun d15_unFileIllegibileEUnoVuotoHannoIlLoroProblema() = runTest {
    val broken = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(30_000, 19)))
    val empty = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(ByteArray(0)))
    val ok = kit.addPhoneMedia("IMG_3.jpg", NasContent.of(bytes(10_000, 20)))
    val brokenId = kit.phoneIdOf(broken)
    h.phone = { inner -> FailingReads(inner, brokenId) }
    h.restart()
    val before = kit.snapshot()
    h.resumeUntilQuiet()
    val items = kit.store.all().associateBy { it.mediaId }
    assertThat(items.getValue(broken).state).isEqualTo(UploadState.FAILED)
    assertThat(items.getValue(broken).problem).isEqualTo(BackupProblemCode.UNREADABLE)
    assertThat(items.getValue(empty).problem).isEqualTo(BackupProblemCode.UNREADABLE)
    assertThat(kit.catalog.problemOf(broken)).isEqualTo(BackupProblemCode.UNREADABLE)
    assertThat(kit.catalog.isSafe(ok)).isTrue()
    assertThat(kit.catalog.isSafe(broken)).isFalse()
    // Gli elementi FAILED (voluti, chiedono l'utente) a parte, tutti gli invarianti valgono; nessun temporaneo resta.
    assertThat(kit.violations(before, final = false)).isEmpty()
    assertThat(kit.nas.filePaths(kit.share).filter { it.startsWith(".mycloudgallery/") }).isEmpty()
  }

  /** D33: solo temporanei `<io>-bk-` di questo dispositivo; quelli della 4B (`-op-`) e di un altro dispositivo restano intatti. */
  @Test
  fun d33_soloTemporaneiDelBackupDiQuestoDispositivo() = runTest {
    val other = dev.pampa.mycloudgallery.core.trust.codec.Base32.encode(ByteArray(16) { 0x33 })
    val opTemp = ".mycloudgallery/tmp/${kit.deviceId}-op-1-0"
    val otherTemp = NasBackupPaths.tempFor(other, 1).value
    kit.nas.putFile(kit.share, opTemp, NasContent.of(bytes(10, 1)))
    kit.nas.putFile(kit.share, otherTemp, NasContent.of(bytes(10, 2)))
    for (i in 1..4) kit.addPhoneMedia("IMG_$i.jpg", NasContent.of(bytes(30_000 + i, 20 + i)))
    val seen = ArrayList<String>()
    kit.nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, _, path ->
      if (op in setOf(FakeNasOp.OPEN_TEMP, FakeNasOp.REOPEN_TEMP, FakeNasOp.PUBLISH_TEMP, FakeNasOp.DELETE) && path != null) seen += path.value
      null
    }
    kit.nas.opsFaults = FaultPlan().random(seed = 3, probability = 0.15, phase = FaultPhase.AFTER, fault = SimFault.DropConnection)
    repeat(6) { h.runAll() }
    kit.nas.opsFaults = null
    h.resumeUntilQuiet()
    assertThat(seen).isNotEmpty()
    for (path in seen) assertThat(NasBackupPaths.isBackupTempOf(SharePath.parse(path), kit.deviceId)).isTrue()
    assertThat(kit.nas.bytes(kit.share, opTemp)!!.toList()).isEqualTo(bytes(10, 1).toList())
    assertThat(kit.nas.bytes(kit.share, otherTemp)!!.toList()).isEqualTo(bytes(10, 2).toList())
  }

  /** D11: due file in volo (uno si carica mentre l'altro si legge); con un solo file in volo mai due insieme. */
  @Test
  fun d11_dueFileInVoloEUnoSolo() = runTest {
    for (parallel in listOf(2, 1)) {
      val k = RealEngineKit(parallel = parallel)
      val counter = OpenFiles()
      k.phone = { inner -> SlowPhone(inner, counter) }
      k.restart()
      for (i in 1..4) k.kit.addPhoneMedia("IMG_$i.jpg", NasContent.of(bytes(60_000, 30 + i)))
      val before = k.kit.snapshot()
      k.resumeUntilQuiet()
      assertWithMessage("$parallel in volo").that(counter.max.get()).isEqualTo(parallel)
      assertWithMessage("$parallel in volo").that(k.kit.violations(before, final = true)).isEmpty()
    }
  }

  /** §4.4, riga P2: con l'app in primo piano lettura e preparazione girano nel contesto a priorita' bassa; in secondo piano no. */
  @Test
  fun conLAppInPrimoPianoLaLetturaVaAPrioritaBassa() = runTest {
    val contexts = ArrayList<String?>()
    h.phone = { inner -> ContextRecordingPhone(inner, contexts) }
    h.lowPriority = { CoroutineName(LOW) }
    h.foreground = { true }
    h.restart()
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 40)))
    h.runAll()
    // La prima apertura (lettura, hash) a priorita' bassa; la seconda (il caricamento) no.
    assertThat(contexts.first()).isEqualTo(LOW)
    h.foreground = { false }
    kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(20_000, 41)))
    contexts.clear()
    h.runAll()
    assertThat(contexts).doesNotContain(LOW)
  }

  /** "Ferma" a meta' caricamento: il punto di ripresa si salva dove si e' arrivati, il giro dice STOPPED. */
  @Test
  fun fermaAMetaSalvaIlPuntoDiRipresa() = runTest {
    val data = bytes(200_000, 42)
    kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    val writes = AtomicInteger()
    kit.nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.WRITE_TEMP && writes.incrementAndGet() == 5) kotlinx.coroutines.runBlocking { h.engine.stop(StopBy.USER) }
      null
    }
    val result = h.engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.USER_NOW)
    kit.nas.fault = null
    assertThat(result.stoppedBy).isEqualTo(RunStop.STOPPED)
    assertThat(h.engine.status.value).isEqualTo(BackupStatus.Stopped(StopBy.USER))
    assertThat(kit.store.all().single().resumeAt).isGreaterThan(0L)
    h.resumeUntilQuiet()
    assertThat(kit.nas.bytes(kit.share, "$dir/VID_1.mp4")!!.toList()).isEqualTo(data.toList())
  }

  /** D12, D32: con il cancello chiuso niente si pianifica ne' si scrive; chiuso a meta' giro, si ferma al passo successivo. */
  @Test
  fun ilCancelloChiusoFermaPrimaDelPassoSuccessivo() = runTest {
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 43)))
    kit.gate.default = dev.pampa.mycloudgallery.core.backup.contract.BackupGateState.TEST_ONLY
    val first = h.runAll().first()
    assertThat(first.stoppedBy).isEqualTo(RunStop.GATE)
    assertThat(first.problem).isEqualTo(BackupProblemCode.TEST_ONLY)
    assertThat(kit.store.all()).isEmpty()
    kit.gate.default = dev.pampa.mycloudgallery.core.backup.contract.BackupGateState.OPEN
    kit.nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.OPEN_TEMP) kit.gate.default = dev.pampa.mycloudgallery.core.backup.contract.BackupGateState.FROZEN
      null
    }
    val before = kit.snapshot()
    val second = h.runAll().first()
    kit.nas.fault = null
    assertThat(second.problem).isEqualTo(BackupProblemCode.FROZEN)
    // Il passo in corso (la scrittura) finisce, il successivo non parte: niente di visibile.
    assertThat(kit.nas.filePaths(kit.share).filter { !it.startsWith(".mycloudgallery/") }).isEmpty()
    assertThat(kit.violations(before, final = false)).isEmpty()
    kit.gate.default = dev.pampa.mycloudgallery.core.backup.contract.BackupGateState.OPEN
    h.resumeUntilQuiet()
    assertClean(before)
  }

  /**
   * Il catalogo vero rifiuta (e annulla) un lotto con una copia di dimensione sbagliata o senza l'hash completo: se succedesse, il giro non
   * si rompe, il diario resta al passo prima e l'elemento finisce al giro dopo; nessun "al sicuro" nel frattempo.
   */
  @Test
  fun unCatalogoCheRifiutaLaCopiaNonRompeIlGiroNeIlDiario() = runTest {
    val data = bytes(20_000, 46)
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    val other = kit.addPhoneMedia("IMG_2.jpg", NasContent.of(bytes(15_000, 47)))
    var refuse = true
    val refusing = object : dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog by kit.catalog {
      override suspend fun attachVerifiedCopies(copies: List<dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy>): Int {
        if (refuse && copies.any { it.mediaId == media }) throw IllegalArgumentException("La copia ha una dimensione diversa dalla versione.")
        return kit.catalog.attachVerifiedCopies(copies)
      }
    }
    val before = kit.snapshot()
    val result = h.newEngine(catalog = refusing).run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    assertThat(result.done).isEqualTo(1)
    assertThat(result.waiting).isEqualTo(1)
    assertThat(kit.store.all().first { it.mediaId == media }.state).isEqualTo(UploadState.PUBLISHED)
    assertThat(kit.catalog.isSafe(media)).isFalse()
    assertThat(kit.catalog.isSafe(other)).isTrue()
    refuse = false
    h.resumeUntilQuiet()
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertClean(before)
  }

  /** D11: il giro delle foto non riprende un video lasciato a meta' (le regole dei video sono diverse), quello dei video si'. */
  @Test
  fun ilGiroDelleFotoNonRiprendeUnVideo() = runTest {
    kit.addPhoneMedia("VID_1.mp4", NasContent.of(bytes(100_000, 44)), kind = MediaKind.VIDEO)
    h.engine.run(BackupKind.VIDEOS, kotlin.time.Duration.ZERO, BackupTrigger.SCHEDULED)
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.PLANNED)
    val photos = h.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    assertThat(photos.done).isEqualTo(0)
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.PLANNED)
    val videos = h.engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED)
    assertThat(videos.done).isEqualTo(1)
  }

  /** Un elemento del guardiano nel diario non lo tocca il motore del caricamento (D9 e' di P3), salvo se glielo si chiede. */
  @Test
  fun gliElementiDelGuardianoRestanoAlGuardiano() = runTest {
    val data = bytes(10_000, 45)
    val mediaId = dev.pampa.mycloudgallery.core.model.MediaId.random()
    val version = dev.pampa.mycloudgallery.core.model.VersionId.random()
    kit.store.create(
      listOf(
        NewBackupItem(
          kind = UploadKind.GUARD_COPY, mediaId = mediaId, versionId = version, size = data.size.toLong(), phone = null, expected = null,
          share = kit.share, planned = SharePath.parse("Backup/Area comune/2026/10/IMG_9.jpg"),
          sha256 = dev.pampa.mycloudgallery.core.model.Sha256.of(data),
        ),
      ),
    )
    h.engine.resumeAll(1.hours)
    assertThat(kit.store.all().single().state).isEqualTo(UploadState.PLANNED)
  }

  // --- Telefoni di prova -----------------------------------------------------------------------------------------------------------

  /** Le letture della riga [failing] falliscono sempre con un errore di I/O (la riga resta, il timbro e' lo stesso). */
  private class FailingReads(private val inner: PhoneBytes, private val failing: Long) : PhoneBytes by inner {
    override suspend fun open(ref: PhoneRef): PhoneOpen {
      val opened = inner.open(ref)
      if (ref.mediaStoreId != failing || opened !is PhoneOpen.Opened) return opened
      val file = opened.file
      return PhoneOpen.Opened(PhoneFile(object : MediaBytes by file.bytes {
        override suspend fun read(range: LongRange): ByteArray = throw java.io.IOException("lettura fallita")
      }, file.stamp) { file.close() })
    }
  }

  private class OpenFiles {
    val now = AtomicInteger()
    val max = AtomicInteger()
  }

  /** Un telefono le cui letture si sospendono: due file in volo si alternano davvero; conta le righe aperte insieme. */
  private class SlowPhone(private val inner: PhoneBytes, private val counter: OpenFiles) : PhoneBytes by inner {
    override suspend fun open(ref: PhoneRef): PhoneOpen {
      val opened = inner.open(ref)
      if (opened !is PhoneOpen.Opened) return opened
      val file = opened.file
      counter.max.accumulateAndGet(counter.now.incrementAndGet(), ::maxOf)
      return PhoneOpen.Opened(
        PhoneFile(object : MediaBytes by file.bytes {
          override suspend fun read(range: LongRange): ByteArray {
            delay(1)
            return file.bytes.read(range)
          }
        }, file.stamp) {
          counter.now.decrementAndGet()
          file.close()
        },
      )
    }
  }

  /** Registra il nome del contesto di ogni lettura (la priorita' bassa e' un contesto riconoscibile nei test). */
  private class ContextRecordingPhone(private val inner: PhoneBytes, private val names: MutableList<String?>) : PhoneBytes by inner {
    override suspend fun open(ref: PhoneRef): PhoneOpen {
      val opened = inner.open(ref)
      if (opened !is PhoneOpen.Opened) return opened
      val file = opened.file
      var first = true
      return PhoneOpen.Opened(
        PhoneFile(object : MediaBytes by file.bytes {
          override suspend fun read(range: LongRange): ByteArray {
            if (first) {
              names += currentCoroutineContext()[CoroutineName]?.name
              first = false
            }
            return file.bytes.read(range)
          }
        }, file.stamp) { file.close() },
      )
    }
  }

  private companion object {
    const val LOW = "bassa"
  }
}
