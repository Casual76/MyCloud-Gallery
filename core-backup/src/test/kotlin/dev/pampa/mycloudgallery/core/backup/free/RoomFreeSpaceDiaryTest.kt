package dev.pampa.mycloudgallery.core.backup.free

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.PhonePlanHash
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaVersionEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il diario di "Libera spazio" su Room ([RoomFreeSpaceDiary], tabelle `free_space_run` e `free_space_item` dello schema v4, nessuna colonna
 * nuova): le stesse regole del diario in memoria; il flusso intero nello stesso processo; e dopo la morte vera del processo (database chiuso
 * e riaperto) un giro a meta' che non va avanti e le righe consegnate ad Android che restano tracciate per posizione del telefono.
 */
@RunWith(RobolectricTestRunner::class)
class RoomFreeSpaceDiaryTest {

  private val opened = ArrayList<GalleryDatabase>()
  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private fun database(name: String? = null): GalleryDatabase = GalleryDatabase.build(context, name).also { opened += it }

  @After
  fun close() {
    opened.forEach { it.close() }
    opened.clear()
  }

  /** I media e le versioni del banco nel catalogo Room (le chiavi esterne di `free_space_item`). */
  private suspend fun GalleryDatabase.seed(bench: FreeSpaceBench) = transaction {
    for (fm in bench.catalog.all()) {
      mediaDao().insert(
        MediaEntity(
          mediaId = fm.id.value, kind = fm.media.kind.code, source = "camera", ownerAccount = null, groupId = null,
          currentVersionId = fm.media.currentVersionId.value, createdAt = 0, updatedAt = 0, sortTime = fm.sortTime, presence = "phone_and_nas",
          safety = "safe",
        ),
      )
      for (v in fm.media.versions) {
        versionDao().insert(
          MediaVersionEntity(
            versionId = v.id.value, mediaId = fm.id.value, kind = v.kind.code, parentVersionId = v.parentId?.value, size = v.fingerprint.size,
            quickHash = "a".repeat(64), fullHash = null, corrections = "", createdAt = 0,
          ),
        )
      }
    }
  }

  private fun runOf(record: FreeRunRecord): FreeSpaceRun = FreeSpaceRun(
    id = record.id, planHash = record.planHash, state = record.state, permanent = record.permanent,
    guardedDone = 0, guardedTotal = 0, verifiedDone = 0, verifiedTotal = 0, verifiedBytes = 0, readyItems = 0, readyBytes = 0,
    batchesTotal = 0, batchesDone = record.batchesDone, freedItems = 0, freedBytes = 0,
  )

  @Test
  fun ilFlussoInteroSuRoom_nelloStessoProcesso() = runBlocking<Unit> {
    val bench = FreeSpaceBench()
    val safe = listOf(bench.safe(), bench.safe(), bench.edited())
    val common = bench.safe(privateCopy = false, commonCopy = true)
    val db = database()
    db.seed(bench)
    val diary = RoomFreeSpaceDiary(db, bench.clock)
    val space = bench.engine(diary = diary)
    val plan = space.plan(FreeSpaceFilter())
    val run = space.verify(plan, TestConfirmations.confirm(plan, bench.clock.monotonicMs()), 1.hours)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(run.readyItems).isEqualTo(4)
    assertThat(run.guardedTotal).isEqualTo(1)
    // Cio' che le colonne tengono: la copia della versione del telefono riletta, con il suo timbro.
    val rows = db.freeSpaceDao().items(run.id.value)
    assertThat(rows.map { it.state }.toSet()).containsExactly(FreeItemState.VERIFIED.code)
    assertThat(rows.all { it.nasLocationId != null && it.verifiedSize != null && it.verifiedModifiedAt != null }).isTrue()
    // Cio' che non tengono torna null: la validita' non e' nel diario (il motore la ricorda finche' il processo vive).
    assertThat(diary.run(run.id)!!.validity).isNull()
    assertThat(diary.items(run.id).all { it.phone == null && it.checks == null }).isTrue()

    val batch = space.nextBatch(run)!!
    assertThat(bench.batchViolations(batch).filter { "dialogo" !in it }).isEmpty()
    assertThat(db.freeSpaceDao().batch(run.id.value, 1).map { it.state }.toSet()).containsExactly(FreeItemState.IN_DIALOG.code)
    val done = space.recordOutcome(batch, bench.dialog(batch))
    assertThat(done.state).isEqualTo(FreeSpaceRunState.DONE)
    assertThat(done.freedItems).isEqualTo(4)
    assertThat(done.guardedTotal).isEqualTo(1)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactlyElementsIn((safe + common).map { it.phoneId })
    // La lettura del telefono "lo sa prima": la riga di ogni copia liberata, per posizione del telefono.
    for (m in safe + common) {
      assertThat(db.freeSpaceDao().forPhoneLocation(m.phoneLocation!!.value).first().state).isEqualTo(FreeItemState.FREED.code)
    }
  }

  /**
   * Dopo la morte vera del processo (database chiuso e riaperto) il giro a meta' non va avanti e le righe nel dialogo restano tracciate. Nome
   * corto di proposito: Robolectric lo mette nel percorso del database, e su Windows un percorso troppo lungo non si apre.
   */
  @Test
  fun laMorteVeraDelProcesso() = runBlocking<Unit> {
    val name = "free-space-${System.nanoTime()}.db"
    val bench = FreeSpaceBench()
    repeat(3) { bench.safe() }
    val first = database(name)
    first.seed(bench)
    val space = bench.engine(diary = RoomFreeSpaceDiary(first, bench.clock))
    val plan = space.plan(FreeSpaceFilter())
    val run = space.verify(plan, TestConfirmations.confirm(plan, bench.clock.monotonicMs()), 1.hours)
    val batch = space.nextBatch(run)!!
    // L'utente conferma nel dialogo di sistema; il processo muore prima della risposta (il database si chiude).
    bench.dialog(batch, only = setOf(batch.items.first().phone))
    first.close()

    val second = database(name)
    val diary = RoomFreeSpaceDiary(second, bench.clock)
    val resumed = bench.engine(diary = diary).resume(run, 1.hours)
    // Nessuna prova possibile senza la riga del telefono nel diario: le tre righe sono "in dubbio" (mai liberate, mai riproposte), per
    // posizione del telefono, finche' la lettura del telefono non le risolve.
    assertThat(resumed.isActive).isFalse()
    assertThat(resumed.freedItems).isEqualTo(0)
    assertThat(second.freeSpaceDao().items(run.id.value).map { it.state }).containsExactly(
      FreeItemState.IN_DOUBT.code, FreeItemState.IN_DOUBT.code, FreeItemState.IN_DOUBT.code,
    )
    assertThat(diary.handedPhoneLocations()).hasSize(3)
    // E un piano nuovo non le ripropone.
    val again = bench.engine(diary = diary).plan(FreeSpaceFilter())
    assertThat(again.isEmpty).isTrue()
    assertThat(again.excluded).containsExactly(FreeSpaceExclusion.IN_PROGRESS_OR_ERROR, 3)

    // Un giro a meta' verifica, dopo la morte del processo: scaduto (la validita' non e' nel diario), niente lotti.
    val other = FreeSpaceBench()
    repeat(2) { other.safe() }
    val third = database("free-space-b-${System.nanoTime()}.db")
    third.seed(other)
    val a = other.engine(diary = RoomFreeSpaceDiary(third, other.clock))
    val p = a.plan(FreeSpaceFilter())
    val half = a.verify(p, TestConfirmations.confirm(p, other.clock.monotonicMs()), Duration.ZERO)
    assertThat(half.state).isEqualTo(FreeSpaceRunState.VERIFYING)
    val b = other.engine(diary = RoomFreeSpaceDiary(third, other.clock))
    assertThat(b.resume(half, 1.hours).state).isEqualTo(FreeSpaceRunState.EXPIRED)
    assertThat(b.nextBatch(half)).isNull()
  }

  @Test
  fun leRegoleDelDiarioValgonoAncheSuRoom() = runBlocking<Unit> {
    val bench = FreeSpaceBench()
    val m = bench.safe()
    val db = database()
    db.seed(bench)
    val diary = RoomFreeSpaceDiary(db, bench.clock)
    val item = FreeItemRecord(
      index = 0, state = FreeItemState.PLANNED, mediaId = m.id, versionId = m.current, phoneLocation = m.phoneLocation!!, phone = m.phoneRef,
      phoneStamp = null, mime = null, size = 4_096, needsGuardian = false, plannedCopy = null,
    )
    val hash = PhonePlanHash.builder("prova").build()
    val run = diary.create(NewFreeRun(FreeSpaceRunState.VERIFYING, FreeSpaceKind.ALL, 30, false, hash, FreeValidity(1, 7)), listOf(item))
    assertThat(diary.run(run.id)!!.kind).isEqualTo(FreeSpaceKind.ALL)
    // Un elemento non salta la verifica, e uno liberato non torna indietro.
    assertThrows(IllegalStateException::class.java) {
      runBlocking { diary.update(run, listOf(item.copy(state = FreeItemState.IN_DIALOG, batchIndex = 1))) }
    }
    diary.update(run, listOf(item.copy(state = FreeItemState.VERIFIED)))
    diary.update(run, listOf(item.copy(state = FreeItemState.IN_DIALOG, batchIndex = 1)))
    diary.update(run.copy(batchesDone = 1), listOf(item.copy(state = FreeItemState.FREED, batchIndex = 1)))
    assertThrows(IllegalStateException::class.java) {
      runBlocking { diary.update(run.copy(batchesDone = 1), listOf(item.copy(state = FreeItemState.VERIFIED, batchIndex = null))) }
    }
    // L'identita' non cambia, i lotti non tornano indietro, un giro finito non riparte.
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { diary.update(run.copy(batchesDone = 1), listOf(item.copy(state = FreeItemState.FREED, batchIndex = 1, versionId = VersionId.random()))) }
    }
    assertThrows(IllegalStateException::class.java) { runBlocking { diary.update(run.copy(batchesDone = 0)) } }
    diary.update(run.copy(state = FreeSpaceRunState.DONE, batchesDone = 1))
    assertThrows(IllegalStateException::class.java) { runBlocking { diary.update(run.copy(state = FreeSpaceRunState.READY, batchesDone = 1)) } }
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { diary.update(run.copy(state = FreeSpaceRunState.DONE, batchesDone = 1, planHash = PhonePlanHash.builder("altro").build())) }
    }
    assertThat(diary.items(run.id).single().state).isEqualTo(FreeItemState.FREED)
    assertThat(db.freeSpaceDao().forPhoneLocation(m.phoneLocation!!.value).single().mediaId).isEqualTo(m.id.value)
    assertThat(MediaId(diary.items(run.id).single().mediaId.value)).isEqualTo(m.id)
  }
}
