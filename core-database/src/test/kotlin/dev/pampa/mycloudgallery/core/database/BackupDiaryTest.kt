package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.dao.BackupDiaryDao
import dev.pampa.mycloudgallery.core.database.entity.BackupItemEntity
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.random.Random

/**
 * Il diario del backup (schema v4, `backup_item`; Fase 5A, P1): righe dentro e fuori per il `RoomBackupStore` di P2 e le query che
 * sceglie con gli indici. L'elenco degli aperti non scorre i terminali (la tabella cresce di una riga per file).
 */
@RunWith(AndroidJUnit4::class)
class BackupDiaryTest : CatalogTestBase() {

  private suspend fun newMedia(n: Int): MediaId = catalog.record(phoneObservation("IMG_$n.jpg", 100L + n, smallFull(n), T0 - n)).mediaId!!

  /** Un elemento del media (di proposito con `is_open` sbagliato: lo corregge il DAO). */
  private suspend fun item(media: MediaId, state: String = "planned", kind: String = "new_copy", temp: String? = null) =
    backupItemEntity(media, catalog.versionOf(media), state, kind, temp)

  @Test
  fun ilDaoTieneCoerenteIsOpenConLoStatoEGliApertiSiTrovanoPerIdentitaCrescente() = runBlocking<Unit> {
    val dao = db.backupDiaryDao()
    val media = (1..4).map { newMedia(it) }
    val ids = media.mapIndexed { i, m -> dao.insert(item(m, temp = ".mycloudgallery/tmp/tablet-bk-$i")) }
    assertThat(ids).containsExactly(1L, 2L, 3L, 4L).inOrder()
    assertThat(dao.itemsToResume(10).map { it.itemId }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    assertThat(dao.get(1)!!.isOpen).isTrue()

    // Avanza: `is_open` segue lo stato (qualunque cosa porti l'entita').
    dao.update(dao.get(2)!!.copy(state = "done", isOpen = true, finalPath = "Backup/S25/2025/10/IMG.jpg", updatedAt = T0 + 1))
    dao.update(dao.get(3)!!.copy(state = "linked_existing"))
    dao.update(dao.get(4)!!.copy(state = "failed", problem = "verify_failed", verifyAttempts = 3))
    dao.update(dao.get(1)!!.copy(state = "uploading", resumeAt = 64L * 1024 * 1024))
    assertThat(listOf(2L, 3L, 4L).map { dao.get(it)!!.isOpen }).containsExactly(false, false, false)
    assertThat(dao.itemsToResume(10).map { it.itemId }).containsExactly(1L)
    assertThat(dao.get(1)!!.resumeAt).isEqualTo(64L * 1024 * 1024)
    assertThat(dao.get(4)!!.problem).isEqualTo("verify_failed")
    // `gone` e' terminale come gli altri: un elemento sparito dal telefono non si riprende.
    dao.update(dao.get(1)!!.copy(state = "gone"))
    assertThat(dao.itemsToResume(10)).isEmpty()

    // I terminali elencati nell'entita' sono quelli degli stati terminali della macchina (codici fissati).
    assertThat(BackupItemEntity.TERMINAL_STATES).containsExactly("done", "linked_existing", "gone", "failed")
    listOf("planned", "hashed", "uploading", "uploaded", "copying", "copied", "verified", "publishing", "published", "linked", "archiving", "archived", "restoring", "restored")
      .forEach { assertThat(BackupItemEntity.isOpenState(it)).isTrue() }
  }

  @Test
  fun ilDiarioCitaSoloITemporaneiDegliElementiNonFinitiEContaPerStato() = runBlocking<Unit> {
    val dao = db.backupDiaryDao()
    val media = (1..5).map { newMedia(it) }
    dao.insert(item(media[0], "uploading", temp = ".mycloudgallery/tmp/tablet-bk-1"))
    dao.insert(item(media[1], "verified", temp = ".mycloudgallery/tmp/tablet-bk-2"))
    dao.insert(item(media[2], "done", temp = ".mycloudgallery/tmp/tablet-bk-3")) // finito: la pulizia puo' toglierlo
    dao.insert(item(media[3], "planned", temp = null))
    dao.insert(item(media[4], "failed", temp = ".mycloudgallery/tmp/tablet-bk-5"))

    assertThat(dao.citedTemps()).containsExactly(".mycloudgallery/tmp/tablet-bk-1", ".mycloudgallery/tmp/tablet-bk-2")
    assertThat(dao.countsByState().associate { it.status to it.count })
      .containsExactly("uploading", 1, "verified", 1, "done", 1, "planned", 1, "failed", 1)
    assertThat(dao.openBytes()).isEqualTo(3 * 2_000L)
    // Gli elementi di un media (terminali compresi) e quelli aperti di una versione.
    assertThat(dao.forMedia(media[2].value).single().state).isEqualTo("done")
    assertThat(dao.openForVersion(catalog.versionOf(media[0]).value).single().state).isEqualTo("uploading")
    assertThat(dao.openForVersion(catalog.versionOf(media[2]).value)).isEmpty()
    // Due tipi diversi sulla stessa versione: il guardiano e la copia nuova non si pestano i piedi.
    dao.insert(item(media[0], "planned", kind = "guard_copy"))
    assertThat(dao.openForVersion(catalog.versionOf(media[0]).value).map { it.kind }).containsExactly("new_copy", "guard_copy").inOrder()
    // Il toString non porta percorsi.
    assertThat(dao.get(1)!!.toString()).doesNotContain("Backup/")
    assertThat(dao.get(1)!!.toString()).doesNotContain("tablet-bk")
  }

  @Test
  fun unElementoDelDiarioPuntaSempreAUnMediaEAUnaVersioneCheEsistono() = runBlocking<Unit> {
    // Chiavi esterne differite verso `media` e `media_version`, senza cascata: nessuna riga del diario si perde in silenzio
    // (l'unione e la rinomina le spostano: `MergeV4Test`). Il controllo di una violazione e' al commit.
    val foreign = db.openHelper.readableDatabase.query("PRAGMA foreign_key_list(`backup_item`)").use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          add(Triple(cursor.getString(cursor.getColumnIndexOrThrow("table")), cursor.getString(cursor.getColumnIndexOrThrow("from")), cursor.getString(cursor.getColumnIndexOrThrow("on_delete"))))
        }
      }
    }
    assertThat(foreign).containsExactly(Triple("media", "media_id", "NO ACTION"), Triple("media_version", "version_id", "NO ACTION"))
    // Con righe vere le chiavi tornano: nessuna violazione.
    val media = newMedia(1)
    db.backupDiaryDao().insert(item(media))
    db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
  }

  // --- Piani (EXPLAIN QUERY PLAN) -------------------------------------------------------------------------------------------------

  private fun planOf(query: String): String {
    val sql = query.replace(Regex(":[a-zA-Z]+"), "?")
    return db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")
  }

  /**
   * 100 mila elementi terminali e una dozzina aperti: il diario cresce di una riga per file, ma chi riprende e chi cita i
   * temporanei leggono solo gli aperti, dall'indice (`is_open`, `item_id`): niente scorrimento dei terminali, niente ordinamento.
   */
  @Test
  fun centoMilaElementiTerminaliNonRallentanoGliAperti() = runBlocking<Unit> {
    val media = newMedia(1)
    val version = catalog.versionOf(media).value
    val sql = db.openHelper.writableDatabase
    sql.beginTransaction()
    try {
      val insert = sql.compileStatement(
        "INSERT INTO backup_item (kind, state, is_open, media_id, version_id, size, share, planned_path, name_attempt, resume_at, " +
          "verify_attempts, created_at, updated_at, archive_attempt) VALUES ('new_copy', ?, ?, ?, ?, 2000, 'alessio', ?, 0, 0, 0, 0, 0, 0)",
      )
      val random = Random(5)
      for (i in 0 until 100_000) {
        val open = i % 8_000 == 7_999
        insert.clearBindings()
        insert.bindString(1, if (open) "uploading" else "done")
        insert.bindLong(2, if (open) 1 else 0)
        insert.bindString(3, media.value)
        insert.bindString(4, version)
        insert.bindString(5, "Backup/S25/${UUID(random.nextLong(), random.nextLong())}.jpg")
        insert.executeInsert()
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
    val dao = db.backupDiaryDao()

    val plans = mapOf(
      "itemsToResume" to (BackupDiaryDao.ITEMS_TO_RESUME_SQL to "index_backup_item_is_open_item_id"),
      "openForVersion" to (BackupDiaryDao.OPEN_FOR_VERSION_SQL to "index_backup_item_version_id_is_open"),
      "citedTemps" to (BackupDiaryDao.CITED_TEMPS_SQL to "index_backup_item_is_open_item_id"),
      "countsByState" to (BackupDiaryDao.COUNTS_BY_STATE_SQL to "index_backup_item_state"),
    )
    plans.forEach { (name, query) ->
      val plan = planOf(query.first)
      println("Piano di $name: $plan")
      assertWithMessage(name).that(plan).contains(query.second)
      // I conteggi per stato raggruppano su un indice che li da' gia' in ordine; gli altri non ordinano mai.
      if (name != "countsByState") assertWithMessage(name).that(plan).doesNotContain("TEMP B-TREE")
    }

    val started = System.nanoTime()
    val open = dao.itemsToResume(50)
    val cited = dao.citedTemps()
    val counts = dao.countsByState().associate { it.status to it.count }
    val millis = (System.nanoTime() - started) / 1_000_000
    println("Diario con 100.000 elementi: ${open.size} aperti, ${counts.getValue("done")} terminali, in $millis ms")
    assertThat(open).hasSize(12)
    assertThat(open.map { it.itemId }).isInOrder()
    assertThat(cited).isEmpty()
    assertThat(counts).containsExactly("done", 100_000 - 12, "uploading", 12)
    // Largo: su JVM l'atteso e' qualche decina di ms; il dispositivo si misura a M2.
    assertThat(millis).isLessThan(1_500)
  }
}
