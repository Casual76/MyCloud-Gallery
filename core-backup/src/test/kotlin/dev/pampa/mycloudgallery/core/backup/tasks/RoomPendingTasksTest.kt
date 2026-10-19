package dev.pampa.mycloudgallery.core.backup.tasks

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaVersionEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fase 5B, P8 (D20, E-E4, §2.6 "Da decidere"): le attivita' in sospeso su Room ([RoomPendingTasks], `pending_task` dello schema v4). Una
 * sola attivita' per originale, per sempre (nemmeno dopo "Tienilo"); la lista mostra solo le aperte, dalla piu' vecchia; "Tienilo" e "fatto"
 * non toccano il NAS; tutto sopravvive a un database chiuso e riaperto; gli istanti sono l'ora del NAS.
 */
@RunWith(RobolectricTestRunner::class)
class RoomPendingTasksTest {

  private val opened = ArrayList<GalleryDatabase>()
  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val clock = FakeBackupClock()

  private fun database(name: String? = null): GalleryDatabase = GalleryDatabase.build(context, name).also { opened += it }

  @After
  fun close() {
    opened.forEach { it.close() }
    opened.clear()
  }

  /** Il media video e la sua versione originale (le chiavi esterne di `pending_task`). */
  private suspend fun GalleryDatabase.video(): Pair<MediaId, VersionId> {
    val mediaId = MediaId.random()
    val versionId = VersionId.random()
    transaction {
      mediaDao().insert(
        MediaEntity(
          mediaId = mediaId.value, kind = "video", source = "camera", ownerAccount = null, groupId = null, currentVersionId = versionId.value,
          createdAt = 0, updatedAt = 0, sortTime = 0, presence = "phone", safety = "safe",
        ),
      )
      versionDao().insert(
        MediaVersionEntity(
          versionId = versionId.value, mediaId = mediaId.value, kind = "original", parentVersionId = null, size = 3_000_000_000,
          quickHash = "a".repeat(64), fullHash = null, corrections = "", createdAt = 0,
        ),
      )
    }
    return mediaId to versionId
  }

  @Test
  fun unaSolaAttivitaPerOriginaleELaListaMostraLeAperte() = runTest {
    val db = database()
    val tasks = RoomPendingTasks(db, clock)
    val (m1, v1) = db.video()
    val (m2, v2) = db.video()
    tasks.originalKept(m1, v1, 3_000_000_000)
    clock.advance(60_000)
    tasks.originalKept(m2, v2, 1_000)
    // Lo stesso originale una seconda volta (un giro ripreso, un'altra modifica): nessun doppione.
    tasks.originalKept(m1, v1, 3_000_000_000)
    val open = tasks.tasks.value
    assertThat(open.map { it.versionId }).containsExactly(v1, v2).inOrder()
    assertThat(open.first().kind).isEqualTo(PendingTaskKind.EDITED_VIDEO_ORIGINAL)
    assertThat(open.first().bytes).isEqualTo(3_000_000_000)
    assertThat(open.first().createdAtNasMillis).isEqualTo(FakeBackupClock.DEFAULT_NAS_NOW)

    // "Tienilo": esce dalla lista e non si propone piu', nemmeno se la stessa versione torna.
    tasks.dismiss(open.first().id)
    assertThat(tasks.tasks.value.map { it.versionId }).containsExactly(v2)
    tasks.originalKept(m1, v1, 3_000_000_000)
    assertThat(tasks.tasks.value.map { it.versionId }).containsExactly(v2)
    // La cancellazione della 4B e' partita (:app): fatta. Da tenuta si puo' passare a fatta (l'utente ha cambiato idea dal viewer).
    tasks.complete(open[1].id)
    tasks.complete(open[0].id)
    assertThat(tasks.tasks.value).isEmpty()
    assertThat(tasks.all().map { it.state }).containsExactly(PendingTaskState.DONE, PendingTaskState.DONE)
    // Fatta non torna aperta ne' tenuta.
    tasks.dismiss(open[1].id)
    assertThat(tasks.all().map { it.state }).containsExactly(PendingTaskState.DONE, PendingTaskState.DONE)
  }

  @Test
  fun lAttivitaSopravviveAlDatabaseChiusoERiaperto() = runTest {
    val name = "pending-${System.nanoTime()}.db"
    val first = database(name)
    val (m, v) = first.video()
    RoomPendingTasks(first, clock).originalKept(m, v, 42)
    first.close()
    opened.remove(first)
    val again = RoomPendingTasks(database(name), clock)
    // La lista parte vuota finche' :app non la rilegge all'avvio.
    assertThat(again.tasks.value).isEmpty()
    again.refresh()
    assertThat(again.tasks.value.single().versionId).isEqualTo(v)
    context.deleteDatabase(name)
  }

  @Test
  fun senzaOraDelNasSiUsaLUltimaNotaMaiLOrologioDelTelefono() = runTest {
    val db = database()
    val tasks = RoomPendingTasks(db, clock)
    val (m1, v1) = db.video()
    tasks.originalKept(m1, v1, 1)
    clock.nasNow = null
    val (m2, v2) = db.video()
    tasks.originalKept(m2, v2, 2)
    assertThat(tasks.all().single { it.versionId == v2 }.createdAtNasMillis).isEqualTo(FakeBackupClock.DEFAULT_NAS_NOW)
  }
}
