package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.publish.IndexProvenanceException
import dev.pampa.mycloudgallery.core.index.testing.FakeIndexPublisher
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.testing.FakePreparer
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StagedRun
import dev.pampa.mycloudgallery.core.rules.run.testing.InMemoryCheckpointStore
import dev.pampa.mycloudgallery.core.rules.run.testing.InMemoryTrafficLedger
import dev.pampa.mycloudgallery.core.rules.run.testing.ScriptedStageRules
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Cio' che lega il motore al resto (§1.4, D10, D11): la preparazione dai byte del telefono e il record all'indice (D10), le tappe di
 * `StagedRun` con il registro del traffico prima dei punti di ripresa (F4-D22, §2.2 "Traffico"), il punto d'ingresso per `:app`.
 */
class UploadWiringTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"

  /** Il record di base dal catalogo finto (la porta che P7 lega a `MediaCatalog`). */
  private class KitRecords(private val catalog: InMemoryBackupCatalog) : UploadRecords {
    override suspend fun record(
      mediaId: dev.pampa.mycloudgallery.core.model.MediaId,
      versionId: dev.pampa.mycloudgallery.core.model.VersionId,
      stamp: dev.pampa.mycloudgallery.core.model.NasStamp,
      quickHash: dev.pampa.mycloudgallery.core.model.QuickHash,
      fullHash: dev.pampa.mycloudgallery.core.model.Sha256,
    ): MetaFile? {
      val m = catalog.media(mediaId)?.takeIf { it.versionId == versionId } ?: return null
      return MetaFile(stamp = stamp, quickHash = quickHash, mediaId = mediaId, versionId = versionId, kind = m.kind, source = MediaSource.OTHER)
    }
  }

  @Test
  fun d10_chiCaricaPreparaDagliStessiByteEPubblicaIlRecordConIlTimbroDopoLaPubblicazione() = runTest {
    val h = RealEngineKit()
    val preparer = FakePreparer()
    val publisher = FakeIndexPublisher()
    h.indexing = PreparingUploadIndexing(preparer, publisher, KitRecords(h.kit.catalog))
    h.restart()
    val photo = bytes(50_000, 1)
    val video = bytes(150_000, 2)
    h.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(photo))
    h.kit.addPhoneMedia("VID_2.mp4", NasContent.of(video), kind = MediaKind.VIDEO)
    h.resumeUntilQuiet()
    val items = h.kit.store.all()
    assertThat(items.map { it.state }.toSet()).containsExactly(UploadState.DONE)
    // La preparazione ha lavorato sui byte letti per l'hash (in memoria, sotto i 64 MiB): nessuna lettura in piu' del telefono.
    assertThat(preparer.prepared.map { it.kind }).containsExactly(MediaKind.PHOTO, MediaKind.VIDEO)
    assertThat(preparer.prepared.all { it is PrepareSource.Bytes }).isTrue()
    // Due passaggi del telefono per file: la lettura (hash e preparazione) e il caricamento.
    assertThat(h.kit.phone.bytesRead).isEqualTo(2L * (photo.size + video.size))
    // Un record per file, al percorso finale, con il timbro letto dopo la pubblicazione e l'hash completo calcolato qui.
    val staged = publisher.flush(h.kit.share, dev.pampa.mycloudgallery.core.index.contract.FlushReason.PERIODIC)
    assertThat(staged).isInstanceOf(dev.pampa.mycloudgallery.core.index.contract.PublishOutcome.Published::class.java)
    val published = publisher.published.single()
    assertThat(published.records).hasSize(2)
    for (item in items) {
      val record = published.records.single { it.path == item.finalPath } as dev.pampa.mycloudgallery.core.trust.data.MetaRecord.Present
      assertThat(record.file.stamp).isEqualTo(item.finalStamp)
      assertThat(record.file.fullHash).isEqualTo(item.sha256)
      assertThat(record.file.quickHash).isEqualTo(item.quickHash)
      val key = ContentKey.of(item.quickHash!!, item.size)
      assertThat(published.blobs.filter { it.contentKey == key }).isNotEmpty()
    }
    assertThat(published.blobs.filterIsInstance<BlobPut.Frames>()).hasSize(1)
  }

  /** Dopo un riavvio fra la lettura e il collegamento le uscite non ci sono piu': si rileggono dal telefono, se la riga e' la stessa. */
  @Test
  fun d10_dopoUnRiavvioLaPreparazioneSiRifaDalTelefono() = runTest {
    val h = RealEngineKit(parallel = 1)
    val preparer = FakePreparer()
    val publisher = FakeIndexPublisher()
    h.indexing = PreparingUploadIndexing(preparer, publisher, KitRecords(h.kit.catalog))
    h.restart()
    h.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(30_000, 3)))
    // Il primo giro si ferma al temporaneo (guasto), poi il processo muore: le uscite in memoria si perdono.
    h.kit.nas.opsFaults = dev.pampa.mycloudgallery.core.nas.testing.FaultPlan()
      .at(1, dev.pampa.mycloudgallery.core.nas.testing.FaultPhase.BEFORE, dev.pampa.mycloudgallery.core.nas.testing.SimFault.DropConnection)
    h.runAll()
    h.kit.nas.opsFaults = null
    h.restart()
    h.resumeUntilQuiet()
    assertThat(preparer.prepared).hasSize(2)
    publisher.flush(h.kit.share, dev.pampa.mycloudgallery.core.index.contract.FlushReason.PERIODIC)
    assertThat(publisher.published.single().blobs).isNotEmpty()
  }

  /** M3: se il catalogo dell'indice non conosce ancora il file, il record non va (lo adottera' la scansione); il caricamento finisce. */
  @Test
  fun unIndiceCheRifiutaIlRecordNonFermaIlCaricamento() = runTest {
    val h = RealEngineKit()
    val refusing = object : dev.pampa.mycloudgallery.core.index.contract.IndexPublisher by FakeIndexPublisher() {
      override suspend fun stage(share: String, changes: List<MetaChange>, blobs: List<BlobPut>) =
        throw IndexProvenanceException("M3: un percorso che non e' un file di questa share.")
    }
    h.indexing = PreparingUploadIndexing(FakePreparer(), refusing, KitRecords(h.kit.catalog))
    h.restart()
    val media = h.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 4)))
    h.resumeUntilQuiet()
    assertThat(h.kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(h.kit.catalog.isSafe(media)).isTrue()
  }

  /** F4-D22, §2.2 "Traffico": il motore in una tappa di `StagedRun`; il traffico (caricamento e rilettura) nel registro, tutto. */
  @Test
  fun dentroUnaTappaIlTrafficoVaNelRegistro() = runTest {
    val h = RealEngineKit()
    val data = bytes(100_000, 5)
    h.kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    val ledger = InMemoryTrafficLedger()
    val rules = ScriptedStageRules { RuleDecision.Run(network = NetworkClass.HOME_WIFI) }
    val staged = StagedRun(WorkRequest.Background(BackgroundActivity.VIDEO_BACKUP, writesToNas = true), rules, ledger, InMemoryCheckpointStore<Unit>())
    val outcome = UploadStages.stage(staged, h.engine, BackupKind.VIDEOS, BackupTrigger.SCHEDULED)
    assertThat(outcome.stage).isInstanceOf(StageOutcome.Finished::class.java)
    assertThat(outcome.last!!.done).isEqualTo(1)
    assertThat(ledger.totalBytes).isEqualTo(outcome.last!!.bytesUploaded + outcome.last!!.bytesReread)
    assertThat(ledger.totalBytes).isEqualTo(2L * data.size)
  }

  /** Le regole dicono Stop a meta' tappa: il motore si ferma al blocco successivo con il punto di ripresa salvato. */
  @Test
  fun leRegoleCheFermanoLaTappaLascianoIlPuntoDiRipresa() = runTest {
    val h = RealEngineKit()
    h.kit.addPhoneMedia("VID_1.mp4", NasContent.of(bytes(200_000, 6)), kind = MediaKind.VIDEO)
    val ledger = InMemoryTrafficLedger()
    var calls = 0
    val rules = ScriptedStageRules { call ->
      calls++
      if (call.running && ledger.totalBytes > 60_000) RuleDecision.Stop(WaitReason.NeedsPower) else RuleDecision.Run(network = NetworkClass.HOME_WIFI)
    }
    val staged = StagedRun(
      WorkRequest.Background(BackgroundActivity.VIDEO_BACKUP, writesToNas = true), rules, ledger, InMemoryCheckpointStore<Unit>(),
      config = dev.pampa.mycloudgallery.core.rules.run.StageConfig(recheckEvery = kotlin.time.Duration.ZERO),
    )
    val outcome = UploadStages.stage(staged, h.engine, BackupKind.VIDEOS, BackupTrigger.SCHEDULED)
    assertThat(outcome.stage).isInstanceOf(StageOutcome.Stopped::class.java)
    val item = h.kit.store.all().single()
    assertThat(item.state).isEqualTo(UploadState.UPLOADING)
    assertThat(item.resumeAt).isGreaterThan(0L)
    // Il traffico registrato copre almeno i byte del punto di ripresa (prima il registro, poi il punto).
    assertThat(ledger.totalBytes).isAtLeast(item.resumeAt)
    h.resumeUntilQuiet()
    assertThat(h.kit.store.all().single().state).isEqualTo(UploadState.DONE)
  }

  /** D34: il piano riceve il trigger del giro (con "Proteggi subito le foto nuove" solo le foto nuove della fotocamera). */
  @Test
  fun ilPianoRiceveIlTriggerDelGiro() = runTest {
    val h = RealEngineKit()
    val seen = ArrayList<BackupTrigger>()
    val inner = h.kit.planner
    h.planner = object : dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner by inner {
      override suspend fun next(kind: BackupKind, limit: Int, trigger: BackupTrigger) = inner.next(kind, limit, trigger).also { seen += trigger }
    }
    h.restart()
    h.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(10_000, 9)))
    val result = h.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.QUICK_NEW_PHOTOS)
    assertThat(seen).containsExactly(BackupTrigger.QUICK_NEW_PHOTOS)
    assertThat(result.done).isEqualTo(1)
  }

  /** Il punto d'ingresso per `:app`: senza un NAS SMB (sessione finta) il motore non fa niente e non scrive niente. */
  @Test
  fun ilPuntoDIngressoSenzaNasNonScrive() = runTest {
    val kit = BackupTestKit()
    kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(10_000, 7)))
    val engine = BackupEngineFactory.create(
      session = FakeNasSessionManager(),
      deviceId = kit.deviceId,
      privateShare = kit.share,
      ports = BackupEnginePorts(
        store = kit.store, catalog = kit.catalog, phone = kit.phone.port, gate = kit.gate, clock = kit.clock, planner = kit.planner,
        folder = { kit.folder }, existing = RereadingCopyFinder(kit.catalog, kit.nas.reader, kit.clock),
      ),
    )
    val result = engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    assertThat(result.done).isEqualTo(0)
    assertThat(kit.store.all()).isEmpty()
    assertThat(engine.status.value).isNotInstanceOf(BackupStatus.Running::class.java)
    assertThat(result.stoppedBy).isNotEqualTo(RunStop.BUDGET)
    assertThat(kit.nas.filePaths(kit.share)).isEmpty()
    assertThat(SharePath.of("Backup")).isNotNull()
  }

  /** Sopra una sessione SMB (il simulatore) il punto d'ingresso da' il motore vero con le primitive in sola aggiunta. */
  @Test
  fun ilPuntoDIngressoSopraSmbDaIlMotoreVero() = runTest {
    val w = SimWorld(backgroundScope)
    val data = bytes(30_000, 8)
    w.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    val engine = BackupEngineFactory.create(
      session = w.manager,
      deviceId = w.kit.deviceId,
      privateShare = w.kit.share,
      ports = BackupEnginePorts(
        store = w.kit.store, catalog = w.kit.catalog, phone = w.kit.phone.port, gate = w.kit.gate, clock = w.kit.clock,
        planner = w.kit.planner, folder = { w.kit.folder }, existing = RereadingCopyFinder(w.kit.catalog, w.reader, w.kit.clock),
        facts = KitFacts(w.kit.catalog),
      ),
    )
    assertThat(engine).isInstanceOf(SafeUploadEngine::class.java)
    val result = engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    assertThat(result.done).isEqualTo(1)
    assertThat(w.backend.bytes(w.kit.share, "$dir/IMG_1.jpg")!!.toList()).isEqualTo(data.toList())
  }
}
