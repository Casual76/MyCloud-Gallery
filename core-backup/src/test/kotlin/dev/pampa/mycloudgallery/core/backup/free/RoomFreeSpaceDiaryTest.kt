package dev.pampa.mycloudgallery.core.backup.free

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
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
import dev.pampa.mycloudgallery.core.database.GalleryMigrations
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
    // P14 (colonne facoltative di P9): il diario e' completo. La validita' (inizio monotono e riavvii), la riga del telefono e le copie rilette.
    assertThat(diary.run(run.id)!!.validity?.bootCount).isEqualTo(7L)
    assertThat(diary.run(run.id)!!.validity?.startMonotonicMs).isNotNull()
    assertThat(diary.items(run.id).all { it.phone != null && it.phoneStamp != null && !it.checks.isNullOrEmpty() }).isTrue()
    assertThat(diary.items(run.id).count { it.needsGuardian }).isEqualTo(1)

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
   * Dopo la morte vera del processo (database chiuso e riaperto) le righe nel dialogo restano tracciate e, con il diario completo del P14, la
   * riga del telefono le risolve; un giro a meta' verifica **riprende nello stesso avvio** e scade dopo un riavvio. Nome corto di proposito:
   * Robolectric lo mette nel percorso del database, e su Windows un percorso troppo lungo non si apre.
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
    // P14: la riga del telefono e' nel diario, quindi la prova del telefono si fa subito: la riga tolta da Android e' liberata, le altre due
    // sono rimaste (mai liberate, mai date per perse). Nessuna riga resta "nel dialogo".
    assertThat(resumed.freedItems).isEqualTo(1)
    val states = second.freeSpaceDao().items(run.id.value).map { it.state }
    assertThat(states.count { it == FreeItemState.FREED.code }).isEqualTo(1)
    assertThat(states).doesNotContain(FreeItemState.IN_DIALOG.code)
    assertThat(states.filter { it != FreeItemState.FREED.code }.toSet()).containsAnyOf(FreeItemState.KEPT.code, FreeItemState.IN_DOUBT.code)
    // La lettura del telefono sa che la riga liberata e' liberata, non persa.
    assertThat(diary.freedOrHandedPhoneLocations()).isNotEmpty()

    // Un giro a meta' verifica, dopo la morte del processo, nello stesso avvio: riprende (la validita' e' nel diario) e arriva ai lotti.
    val other = FreeSpaceBench()
    repeat(2) { other.safe() }
    val third = database("free-space-b-${System.nanoTime()}.db")
    third.seed(other)
    val a = other.engine(diary = RoomFreeSpaceDiary(third, other.clock))
    val p = a.plan(FreeSpaceFilter())
    val half = a.verify(p, TestConfirmations.confirm(p, other.clock.monotonicMs()), Duration.ZERO)
    assertThat(half.state).isEqualTo(FreeSpaceRunState.VERIFYING)
    val b = other.engine(diary = RoomFreeSpaceDiary(third, other.clock))
    val ready = b.resume(half, 1.hours)
    assertThat(ready.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(b.nextBatch(ready)).isNotNull()

    // Dopo un riavvio del telefono (conteggio diverso) un giro a meta' e' scaduto: niente lotti.
    val after = FreeSpaceBench()
    repeat(2) { after.safe() }
    val fourth = database("free-space-c-${System.nanoTime()}.db")
    fourth.seed(after)
    val c = after.engine(diary = RoomFreeSpaceDiary(fourth, after.clock))
    val q = c.plan(FreeSpaceFilter())
    val stopped = c.verify(q, TestConfirmations.confirm(q, after.clock.monotonicMs()), Duration.ZERO)
    after.bootCount = 8
    val d = after.engine(diary = RoomFreeSpaceDiary(fourth, after.clock))
    assertThat(d.resume(stopped, 1.hours).state).isEqualTo(FreeSpaceRunState.EXPIRED)
    assertThat(d.nextBatch(stopped)).isNull()
  }

  /**
   * LS-2 e LS-15 della verifica funzionale 5B: su Room i giri aperti (da riprendere) e quelli con righe consegnate ad Android e non risolte (da
   * risolvere dopo la lettura del telefono) sono due query sullo stato, non tutta la storia dei giri a ogni lettura del telefono.
   */
  @Test
  fun ls15_giriApertiEGiriDaRisolvere_sonoQuerySulloStato() = runBlocking<Unit> {
    val bench = FreeSpaceBench()
    val m = bench.safe()
    val db = database()
    db.seed(bench)
    val diary = RoomFreeSpaceDiary(db, bench.clock)
    val item = FreeItemRecord(
      index = 0, state = FreeItemState.PLANNED, mediaId = m.id, versionId = m.current, phoneLocation = m.phoneLocation!!, phone = m.phoneRef,
      phoneStamp = null, mime = null, size = 4_096, needsGuardian = false, plannedCopy = null,
    )
    fun run(state: FreeSpaceRunState, items: List<FreeItemRecord>) =
      runBlocking { diary.create(NewFreeRun(state, FreeSpaceKind.ALL, 30, false, PhonePlanHash.builder("prova").build(), FreeValidity(1, 7)), items) }
    // Un giro finito senza niente in sospeso, uno finito con una riga ancora nel dialogo, uno a meta' verifica.
    run(FreeSpaceRunState.DONE, emptyList())
    val handed = run(FreeSpaceRunState.READY, listOf(item))
    diary.update(handed, listOf(item.copy(state = FreeItemState.VERIFIED)))
    diary.update(handed, listOf(item.copy(state = FreeItemState.IN_DIALOG, batchIndex = 1)))
    diary.update(handed.copy(state = FreeSpaceRunState.EXPIRED))
    val open = run(FreeSpaceRunState.VERIFYING, listOf(item))
    assertThat(diary.runs()).hasSize(3)
    assertThat(diary.openRuns().map { it.id }).containsExactly(open.id)
    assertThat(diary.runsToSettle().map { it.id }).containsExactly(handed.id)
  }

  /**
   * LS-15 della verifica funzionale 5B: `update` rileggeva **tutti** gli elementi del giro a ogni scrittura (un giro da 5.000 media = 5.000 righe
   * lette per cambiarne una, a ogni tappa). Adesso legge solo gli indici che la chiamata cambia (`FreeSpaceDao.itemsAt`, dalla chiave
   * primaria), a blocchi da 500 (SQLite su Android 10 ammette 999 variabili per istruzione). Il registro delle istruzioni di Room lo prova:
   * il comportamento (le regole di sopra) non cambia, lo prova il resto di questa classe.
   */
  @Test
  fun ls15_unaScritturaLeggeSoloGliElementiCheCambia() = runBlocking<Unit> {
    val bench = FreeSpaceBench()
    val m = bench.safe()
    val statements = java.util.concurrent.CopyOnWriteArrayList<Pair<String, List<Any?>>>()
    val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
      .addMigrations(*GalleryMigrations.ALL)
      .setQueryCallback({ sql, args -> statements += sql to args }, Runnable::run)
      .build().also { opened += it }
    db.seed(bench)
    val diary = RoomFreeSpaceDiary(db, bench.clock)
    fun item(index: Int, state: FreeItemState = FreeItemState.PLANNED) = FreeItemRecord(
      index = index, state = state, mediaId = m.id, versionId = m.current, phoneLocation = m.phoneLocation!!, phone = m.phoneRef,
      phoneStamp = null, mime = null, size = 4_096, needsGuardian = false, plannedCopy = null,
    )
    val all = (0 until 1_200).map { item(it) }
    val run = diary.create(NewFreeRun(FreeSpaceRunState.VERIFYING, FreeSpaceKind.ALL, 30, false, PhonePlanHash.builder("prova").build(), FreeValidity(1, 7)), all)

    fun itemReads(): List<Pair<String, List<Any?>>> =
      statements.filter { (sql, _) -> sql.trimStart().startsWith("SELECT", ignoreCase = true) && sql.contains("free_space_item") }

    // Un elemento su 1.200: una sola lettura degli elementi, per quell'indice (e il giro che la chiama).
    statements.clear()
    diary.update(run, listOf(item(700, FreeItemState.VERIFIED)))
    val one = itemReads()
    assertThat(one).hasSize(1)
    assertThat(one.single().first).contains("item_index IN")
    assertThat(one.single().second).containsExactly(run.id.value, 700L)

    // Nessun elemento (il giro cambia da solo): nessuna lettura degli elementi.
    statements.clear()
    diary.update(run.copy(state = FreeSpaceRunState.READY))
    assertThat(itemReads()).isEmpty()

    // Tutti e 1.200 insieme: a blocchi (500 + 500 + 200), mai piu' di 501 variabili in un'istruzione, e nessuna lettura di tutto il giro.
    statements.clear()
    diary.update(run.copy(state = FreeSpaceRunState.READY), all.map { item(it.index, FreeItemState.VERIFIED) })
    val chunks = itemReads()
    assertThat(chunks.map { it.second.size - 1 }).containsExactly(500, 500, 200).inOrder()
    assertThat(chunks.all { (sql, _) -> sql.contains("item_index IN") }).isTrue()
    assertThat(diary.items(run.id).map { it.state }.toSet()).containsExactly(FreeItemState.VERIFIED)
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
