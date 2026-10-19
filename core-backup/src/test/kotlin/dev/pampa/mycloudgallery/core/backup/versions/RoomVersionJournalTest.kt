package dev.pampa.mycloudgallery.core.backup.versions

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.store.RoomBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaVersionEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fase 5B, P8: il diario delle versioni su Room ([RoomVersionJournal]) nella stessa tabella del diario del backup (`backup_item`, schema
 * v4). Ogni campo di un lavoro torna uguale (anche cio' che lo schema non ha, nella nota); le transizioni fuori dalla macchina, i cambi
 * d'identita' e di cammino sono rifiutati; la creazione e' idempotente. **Insieme al diario della 5A** (`RoomBackupStore`): la 5A vede i
 * lavori delle versioni come elementi aperti della versione (nessuna copia nuova in parallelo), ne cita i temporanei (D33) e li salta senza
 * errori; le versioni vedono i caricamenti della 5A. E il motore vero che riprende da un database chiuso e riaperto.
 */
@RunWith(RobolectricTestRunner::class)
class RoomVersionJournalTest {

  @get:Rule val folder = TemporaryFolder()

  private val opened = ArrayList<GalleryDatabase>()
  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val clock = FakeBackupClock()

  private fun database(name: String? = null): GalleryDatabase = GalleryDatabase.build(context, name).also { opened += it }

  @After
  fun close() {
    opened.forEach { it.close() }
    opened.clear()
  }

  /** Il media e le versioni nel catalogo (le chiavi esterne di `backup_item`). */
  private suspend fun GalleryDatabase.seed(mediaId: MediaId, versions: List<VersionId>) = transaction {
    if (mediaDao().get(mediaId.value) == null) {
      mediaDao().insert(
        MediaEntity(
          mediaId = mediaId.value, kind = "photo", source = "camera", ownerAccount = null, groupId = null, currentVersionId = versions.first().value,
          createdAt = 0, updatedAt = 0, sortTime = 0, presence = "phone", safety = "safe",
        ),
      )
    }
    for (v in versions) {
      if (versionDao().get(v.value) != null) continue
      versionDao().insert(
        MediaVersionEntity(
          versionId = v.value, mediaId = mediaId.value, kind = "edited", parentVersionId = null, size = 10,
          quickHash = "b".repeat(64), fullHash = null, corrections = "", createdAt = 0,
        ),
      )
    }
  }

  private val usual = SharePath.parse("Backup/S25/2026/10/IMG_1.jpg")
  private val ref = PhoneRef("external_primary", 42, "v1")

  @Test
  fun ogniCampoTornaUgualeEleRegoleSonoQuelleDelDiario() = runTest {
    val db = database()
    val journal = RoomVersionJournal(db, clock)
    val media = MediaId.random()
    val v = VersionId.random()
    val parent = VersionId.random()
    db.seed(media, listOf(v, parent))
    val current = CurrentFile(parent, LocationId(7), NasStamp(10, 1_000, 99), original = true)
    val created = journal.create(
      NewVersionJob(UploadKind.VERSION, media, v, 10, "test", usual, VersionSource.Phone(ref, FileStamp(10, 5, 3)), VersionTarget.USUAL_PATH, current),
    )
    assertThat(created.state).isEqualTo(UploadState.PLANNED)
    assertThat(created.current).isEqualTo(current)
    assertThat(created.flow).isEqualTo(dev.pampa.mycloudgallery.core.backup.contract.UploadFlow.VERSION)
    // Idempotente per versione e tipo.
    assertThat(journal.create(NewVersionJob(UploadKind.VERSION, media, v, 10, "test", usual, created.source, VersionTarget.USUAL_PATH, current)).id)
      .isEqualTo(created.id)
    // Tutto il cammino con ogni campo valorizzato, rileggendo ogni volta.
    val temp = NasBackupPaths.tempFor(VersionWorld.DEVICE, created.id.value)
    val sha = Sha256("c".repeat(64))
    var job = created
    val steps = listOf<(VersionJob) -> VersionJob>(
      { it.copy(state = UploadState.HASHED, sha256 = sha, quickHash = QuickHash("d".repeat(64))) },
      { it.copy(state = UploadState.UPLOADING, temp = temp, resumeAt = 0) },
      { it.copy(resumeAt = 5, resumeId = 1234) },
      { it.copy(state = UploadState.UPLOADED, resumeAt = 10) },
      { it.copy(state = UploadState.VERIFIED, tempStamp = NasStamp(10, 2_000, 555)) },
      { it.copy(state = UploadState.ARCHIVING, archivePath = SharePath.parse("Versioni/Backup/S25/2026/10/IMG_1 (originale;=%).jpg"), archiveAttempt = 2) },
      { it.copy(hold = VersionHold.SOURCE_CHANGED) },
      { it.copy(hold = null, state = UploadState.ARCHIVED, archivedStamp = NasStamp(10, 1_000, 99)) },
      { it.copy(state = UploadState.PUBLISHING, finalPath = usual, nameAttempt = 1) },
      { it.copy(state = UploadState.PUBLISHED, finalStamp = NasStamp(10, 2_000, 555)) },
      { it.copy(state = UploadState.LINKED) },
      { it.copy(state = UploadState.DONE, problem = BackupProblemCode.UNEXPECTED) },
    )
    for (step in steps) {
      job = step(job)
      journal.update(job)
      assertThat(journal.job(job.id)).isEqualTo(job)
    }
    assertThat(journal.inShare("TEST").single()).isEqualTo(job)
    assertThat(journal.inShare("altra")).isEmpty()
    assertThat(journal.open(10)).isEmpty()
  }

  @Test
  fun transizioniIdentitaECamminoFuoriRegolaSonoRifiutati() = runTest {
    val db = database()
    val journal = RoomVersionJournal(db, clock)
    val media = MediaId.random()
    val v = VersionId.random()
    db.seed(media, listOf(v))
    val job = journal.create(NewVersionJob(UploadKind.VERSION, media, v, 10, "test", usual, VersionSource.Staged(Sha256("e".repeat(64))), VersionTarget.USUAL_PATH, null))
    assertThat(job.source).isEqualTo(VersionSource.Staged(Sha256("e".repeat(64))))
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { journal.update(job.copy(state = UploadState.PUBLISHED)) } }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { journal.update(job.copy(usualPath = SharePath.parse("Backup/altro.jpg"))) } }
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { journal.update(job.copy(current = CurrentFile(v, null, NasStamp(1, 1), false))) }
    }
    assertThat(journal.job(job.id)).isEqualTo(job)
  }

  @Test
  fun insiemeAlDiarioDellaCinqueALeDuePartiSiVedono() = runTest {
    val db = database()
    val journal = RoomVersionJournal(db, clock)
    val store = RoomBackupStore(db, clock)
    val media = MediaId.random()
    val v = VersionId.random()
    val other = VersionId.random()
    val o = VersionId.random()
    db.seed(media, listOf(v, other, o))
    val swap = journal.create(
      NewVersionJob(
        UploadKind.SWAP, media, o, 10, "test", usual,
        VersionSource.InVersions(SharePath.parse("Versioni/Backup/S25/2026/10/IMG_1 (originale).jpg"), NasStamp(10, 1, 2), LocationId(3)),
        VersionTarget.USUAL_PATH, CurrentFile(v, LocationId(4), NasStamp(10, 5, 6), original = false),
      ),
    )
    assertThat(journal.job(swap.id)).isEqualTo(swap)
    val version = journal.create(NewVersionJob(UploadKind.VERSION, media, v, 10, "test", usual, VersionSource.Phone(ref, FileStamp(10, 5, 3)), VersionTarget.USUAL_PATH, null))
    val temp = NasBackupPaths.tempFor(VersionWorld.DEVICE, version.id.value)
    journal.update(version.copy(state = UploadState.HASHED, sha256 = Sha256("c".repeat(64))))
    journal.update(journal.job(version.id)!!.copy(state = UploadState.UPLOADING, temp = temp))
    // La 5A: un elemento aperto per la versione (non crea una copia nuova), il temporaneo citato (D33), la ripresa che li legge senza errori.
    assertThat(store.openFor(v)).isNotNull()
    assertThat(store.citedTemps()).contains(temp)
    val resumed = store.itemsToResume(100)
    assertThat(resumed.map { it.kind }).containsExactly(UploadKind.SWAP, UploadKind.VERSION)
    assertThat(resumed.all { it.problem == null }).isTrue()
    // Le versioni: un caricamento della 5A aperto rende la versione occupata; i suoi elementi non sono lavori delle versioni.
    store.create(listOf(NewBackupItem(UploadKind.NEW_COPY, media, other, 10, ref, FileStamp(10, 5, 3), "test", SharePath.parse("Backup/S25/2026/10/X.jpg"))))
    assertThat(journal.busy(other)).isTrue()
    assertThat(journal.open(100).map { it.kind }).containsExactly(UploadKind.SWAP, UploadKind.VERSION)
    assertThat(journal.forMedia(media)).hasSize(2)
    assertThat(journal.citedTemps()).containsExactly(temp)
  }

  /** Il motore vero sul diario Room: un database chiuso e riaperto a meta' (la morte vera del processo) e il lavoro arriva in fondo. */
  @Test
  fun ilMotoreRiprendeDaUnDatabaseChiusoERiaperto() = runTest {
    val name = "versions-${System.nanoTime()}.db"
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe()
    val v1 = w.editOnPhone(seed)
    var db = database(name)
    db.seed(seed.mediaId, listOf(v1))
    var journal = RoomVersionJournal(db, clock)
    var engine = w.newEngine(journal = journal)
    engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    // Un giro che le regole fermano a meta' (dopo qualche passo), poi il processo muore: resta solo il database.
    var allowed = 6
    engine.run(kotlin.time.Duration.parse("1h"), object : dev.pampa.mycloudgallery.core.backup.upload.UploadControl {
      override suspend fun proceed(): Boolean = --allowed > 0
    })
    val midway = journal.forMedia(seed.mediaId).single()
    assertThat(midway.state).isNotEqualTo(UploadState.PLANNED)
    assertThat(midway.state.terminal).isFalse()
    db.close()
    opened.remove(db)
    db = database(name)
    journal = RoomVersionJournal(db, clock)
    engine = w.newEngine(journal = journal)
    repeat(5) { engine.run(kotlin.time.Duration.parse("1h")) }
    assertThat(journal.forMedia(seed.mediaId).single().state).isEqualTo(UploadState.DONE)
    assertThat(w.sha(VersionWorld.PRIVATE_SHARE, seed.usualPath.value)).isEqualTo(w.phone.sha256(seed.phoneId))
    assertThat(w.sha(VersionWorld.PRIVATE_SHARE, "Versioni/Backup/S25 di prova/2026/10/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(seed.content))
    context.deleteDatabase(name)
  }
}
