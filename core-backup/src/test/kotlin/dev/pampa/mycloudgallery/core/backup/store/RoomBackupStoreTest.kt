package dev.pampa.mycloudgallery.core.backup.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.GuardSource
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.upload.RealEngineKit
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaVersionEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il diario del backup su Room ([RoomBackupStore], schema v4 di P1) con le regole del fake (`InMemoryBackupStore`): ogni campo torna
 * uguale, la creazione e' idempotente per versione e tipo, le transizioni fuori dalla macchina e i cambi d'identita' sono rifiutati; e
 * il motore vero che riprende da un database chiuso e riaperto (la morte del processo vera).
 */
@RunWith(RobolectricTestRunner::class)
class RoomBackupStoreTest {

  private val opened = ArrayList<GalleryDatabase>()
  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val clock = FakeBackupClock()

  private fun database(name: String? = null): GalleryDatabase = GalleryDatabase.build(context, name).also { opened += it }

  @After
  fun close() {
    opened.forEach { it.close() }
    opened.clear()
  }

  /** Il media e la versione nel catalogo (le chiavi esterne di `backup_item`): come li avrebbe scritti la lettura del telefono. */
  private suspend fun GalleryDatabase.seed(mediaId: MediaId, versionId: VersionId, size: Long = 1_234) = transaction {
    mediaDao().insert(
      MediaEntity(
        mediaId = mediaId.value, kind = "photo", source = "camera", ownerAccount = null, groupId = null, currentVersionId = versionId.value,
        createdAt = 0, updatedAt = 0, sortTime = 0, presence = "phone", safety = "no_nas_copy",
      ),
    )
    versionDao().insert(
      MediaVersionEntity(
        versionId = versionId.value, mediaId = mediaId.value, kind = "original", parentVersionId = null, size = size,
        quickHash = "a".repeat(64), fullHash = null, corrections = "", createdAt = 0,
      ),
    )
  }

  private val seededMedia = HashMap<VersionId, MediaId>()

  private suspend fun GalleryDatabase.seeded(version: VersionId = VersionId.random()): VersionId {
    if (version !in seededMedia) {
      val media = MediaId.random()
      seed(media, version)
      seededMedia[version] = media
    }
    return version
  }

  private fun newItem(version: VersionId, kind: UploadKind = UploadKind.NEW_COPY) = NewBackupItem(
    kind = kind,
    mediaId = seededMedia.getValue(version),
    versionId = version,
    size = 1_234,
    phone = PhoneRef("external_primary", 42, "v1"),
    expected = FileStamp(1_234, 5_000, 7),
    share = "test",
    planned = SharePath.parse("Backup/S25/2026/10/IMG_1.jpg"),
    quickHash = QuickHash("a".repeat(64)),
  )

  @Test
  fun ogniCampoTornaUgualeEIlDiarioEIdempotente() = runBlocking<Unit> {
    val db = database()
    val store = RoomBackupStore(db, clock)
    val new = newItem(db.seeded())
    val item = store.create(listOf(new, new)).also { assertThat(it).hasSize(2) }.distinct().single()
    assertThat(item.state).isEqualTo(UploadState.PLANNED)
    assertThat(item.phone).isEqualTo(new.phone)
    assertThat(item.expected).isEqualTo(new.expected)
    // La stessa versione e lo stesso tipo: lo stesso elemento, nessun doppione; un altro tipo e' un altro elemento.
    assertThat(store.create(listOf(newItem(version = new.versionId))).single().id).isEqualTo(item.id)
    val guard = store.create(listOf(newItem(version = new.versionId, kind = UploadKind.GUARD_COPY).let {
      NewBackupItem(
        kind = it.kind, mediaId = it.mediaId, versionId = it.versionId, size = it.size, phone = null, expected = null, share = it.share,
        planned = it.planned, sha256 = Sha256("b".repeat(64)),
        guardSource = GuardSource("test-comune", SharePath.parse("Foto/IMG_1.jpg"), NasStamp(1_234, 9_000, 77)),
      )
    })).single()
    assertThat(guard.id).isNotEqualTo(item.id)
    assertThat(guard.guardSource).isEqualTo(GuardSource("test-comune", SharePath.parse("Foto/IMG_1.jpg"), NasStamp(1_234, 9_000, 77)))
    assertThrows(IllegalArgumentException::class.java) { runBlocking { store.create(listOf(newItem(db.seeded(), kind = UploadKind.VERSION))) } }

    // Ogni valore di un passo torna uguale.
    val temp = NasBackupPaths.tempFor(BackupTestKit.DEVICE, item.id.value)
    val hashed = item.copy(state = UploadState.HASHED, sha256 = Sha256("c".repeat(64)), quickHash = QuickHash("d".repeat(64)))
    store.update(hashed)
    val uploading = hashed.copy(state = UploadState.UPLOADING, temp = temp, resumeAt = 1_000)
    store.update(uploading)
    val verified = uploading.copy(state = UploadState.UPLOADED, resumeAt = 1_234)
      .also { store.update(it) }
      .copy(state = UploadState.VERIFIED, tempStamp = NasStamp(1_234, 6_000, 11), verifyAttempts = 1)
    store.update(verified)
    val publishing = verified.copy(state = UploadState.PUBLISHING, nameAttempt = 2, finalPath = SharePath.parse("Backup/S25/2026/10/IMG_1 (3).jpg"))
    store.update(publishing)
    val published = publishing.copy(state = UploadState.PUBLISHED, finalStamp = NasStamp(1_234, 6_000, 11))
    store.update(published)
    assertThat(store.item(item.id)).isEqualTo(published)
    assertThat(store.citedTemps()).containsExactly(temp)
    assertThat(store.openFor(new.versionId)!!.id).isEqualTo(item.id)
    assertThat(store.itemsToResume(10).map { it.id }).containsExactly(item.id, guard.id).inOrder()
    store.update(published.copy(state = UploadState.LINKED))
    store.update(published.copy(state = UploadState.DONE))
    assertThat(store.itemsToResume(10).map { it.id }).containsExactly(guard.id)
    assertThat(store.citedTemps()).isEmpty()
    val counts = store.counts()
    assertThat(counts.byState[UploadState.DONE]).isEqualTo(1)
    assertThat(counts.open).isEqualTo(1)
    // Un'uscita con il suo problema.
    store.update(guard.copy(state = UploadState.FAILED, problem = BackupProblemCode.VERIFY_FAILED))
    assertThat(store.item(guard.id)!!.problem).isEqualTo(BackupProblemCode.VERIFY_FAILED)
  }

  @Test
  fun rifiutaLeTransizioniFuoriDallaMacchinaEICambiDIdentita() = runBlocking<Unit> {
    val db = database()
    val store = RoomBackupStore(db, clock)
    val item = store.create(listOf(newItem(db.seeded()))).single()
    assertThrows(IllegalStateException::class.java) { runBlocking { store.update(item.copy(state = UploadState.UPLOADED)) } }
    assertThrows(IllegalArgumentException::class.java) { runBlocking { store.update(item.copy(share = "altra")) } }
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { store.update(item.copy(planned = SharePath.parse("Backup/S25/2026/10/altro.jpg"))) }
    }
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { store.update(item.copy(id = dev.pampa.mycloudgallery.core.backup.contract.BackupItemId(999))) }
    }
    val temp = NasBackupPaths.tempFor(BackupTestKit.DEVICE, item.id.value)
    val hashed = item.copy(state = UploadState.HASHED, sha256 = Sha256("c".repeat(64)))
    store.update(hashed)
    store.update(hashed.copy(state = UploadState.UPLOADING, temp = temp))
    // Il temporaneo scritto non cambia piu'.
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { store.update(hashed.copy(state = UploadState.UPLOADING, temp = NasBackupPaths.tempFor(BackupTestKit.DEVICE, 999))) }
    }
    // Uno stato terminale non si lascia.
    store.update(hashed.copy(state = UploadState.GONE, temp = temp))
    assertThrows(IllegalStateException::class.java) { runBlocking { store.update(hashed.copy(state = UploadState.UPLOADING, temp = temp)) } }
    // La transizione rifiutata non ha cambiato niente.
    assertThat(store.item(item.id)!!.state).isEqualTo(UploadState.GONE)
  }

  /** Il motore vero sopra il diario su Room: il processo muore a meta' caricamento (database chiuso e riaperto) e riprende. */
  @Test
  fun ilMotoreRiprendeDaUnDatabaseRiaperto() = runBlocking<Unit> {
    val name = "backup-${System.nanoTime()}.db"
    val h = RealEngineKit()
    val data = ByteArray(120_000) { (it * 31 + 5).toByte() }
    val media = h.kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    val first = database(name)
    val m = h.kit.catalog.media(media)!!
    first.seed(m.mediaId, m.versionId, m.size)
    h.kit.nas.opsFaults = FaultPlan().at(5, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.WRITE)
    h.newEngine(store = RoomBackupStore(first, h.kit.clock)).run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED)
    h.kit.nas.opsFaults = null
    first.close()
    val second = database(name)
    val store = RoomBackupStore(second, h.kit.clock)
    val stopped = store.itemsToResume(10).single()
    assertThat(stopped.state).isEqualTo(UploadState.UPLOADING)
    assertThat(stopped.resumeAt).isGreaterThan(0L)
    val result = h.newEngine(store = store).run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED)
    assertThat(result.done).isEqualTo(1)
    assertThat(h.kit.nas.backupTruncations.get()).isEqualTo(1)
    assertThat(h.kit.catalog.isSafe(media)).isTrue()
    assertThat(h.kit.nas.bytes(h.kit.share, "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10/VID_1.mp4")!!.toList()).isEqualTo(data.toList())
    assertThat(store.counts().byState[UploadState.DONE]).isEqualTo(1)
  }
}
