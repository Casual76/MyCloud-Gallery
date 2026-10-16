package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.dao.SimilarDao
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.random.Random

/**
 * I candidati alle copie simili su 100 mila media (Fase 5A, P1, D36): per `sort_time` dall'indice (`sort_time`, `media_id`), mai
 * ordinando in memoria, con una scansione per intervallo per cercare i compagni dello stesso secondo. I tempi sono su JVM.
 */
@RunWith(AndroidJUnit4::class)
class SimilarQueriesTest : CatalogTestBase() {

  /**
   * [count] media con la data di scatto letta dai metadati (EXIF), un secondo ogni 7 (sparsi come una libreria vera) e, ogni 50,
   * un compagno nello stesso secondo: [count]/50 coppie di candidati. Ogni 1.000 un media in una raffica (non e' candidato).
   */
  private fun bulk(count: Int): Int {
    val sql = db.openHelper.writableDatabase
    val random = Random(21)
    var pairs = 0
    sql.beginTransaction()
    try {
      val version = sql.compileStatement(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES (?, ?, 'original', NULL, ?, ?, NULL, '', 0)",
      )
      val media = sql.compileStatement(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash, backup_todo) " +
          "VALUES (?, ?, 'camera', 'alessio', NULL, ?, 0, 0, ?, 'phone_only', 'no_nas_copy', 20251009, 1333, NULL, NULL, 0, NULL, 0)",
      )
      val metadata = sql.compileStatement(
        "INSERT INTO version_metadata (version_id, mime_type, width, height, orientation, duration_ms, taken_at_utc, taken_offset_min, " +
          "taken_confidence, taken_source, latitude, longitude, altitude, updated_at, mirrored, traits, place_id, place_dataset, thumb_hash) " +
          "VALUES (?, 'image/jpeg', 4000, 3000, 0, NULL, ?, 120, 'exact', 'exif_offset', NULL, NULL, NULL, 0, 0, 0, NULL, NULL, NULL)",
      )
      var second = (T0 / 1_000) * 1_000
      for (i in 0 until count) {
        val mediaId = UUID(random.nextLong(), random.nextLong()).toString()
        val versionId = UUID(random.nextLong(), random.nextLong()).toString()
        // Ogni 50 media il successivo ha lo stesso secondo (300 ms dopo): una coppia.
        val peer = i % 50 == 1
        if (!peer) second -= 7_000L
        val takenAt = if (peer) second + 300 else second
        if (peer) pairs++
        version.clearBindings()
        version.bindString(1, versionId)
        version.bindString(2, mediaId)
        version.bindLong(3, 1_000L + i)
        version.bindString(4, "%064x".format(i))
        version.executeInsert()
        media.clearBindings()
        media.bindString(1, mediaId)
        media.bindString(2, if (i % 12 == 0) "video" else "photo")
        media.bindString(3, versionId)
        media.bindLong(4, takenAt)
        media.executeInsert()
        metadata.clearBindings()
        metadata.bindString(1, versionId)
        metadata.bindLong(2, takenAt)
        metadata.executeInsert()
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
    return pairs
  }

  private fun planOf(query: String, vararg args: Any?): String =
    db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $query", arrayOf(*args)).use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")

  @Test
  fun centoMilaMediaSiPercorronoAPagineDalIndiceDiSortTimeSenzaOrdinare() = runBlocking<Unit> {
    val pairs = bulk(100_000)
    val plans = mapOf(
      "runRows" to planOf(SimilarDao.RUNS_SQL, 0L, 500),
      "secondRows" to planOf(SimilarDao.SECOND_SQL, 0L, 1_000L),
      "unsignedCount" to planOf(SimilarDao.UNSIGNED_COUNT_SQL, 1),
    )
    plans.forEach { (name, plan) ->
      println("Piano di $name: $plan")
      assertWithMessage(name).that(plan).contains("index_media_sort_time_media_id")
      assertWithMessage(name).that(plan).doesNotContain("TEMP B-TREE")
    }

    // Tutta la libreria, a pagine da 500 righe: nessun secondo spezzato, nessuna coppia persa.
    var from = 0L
    var pages = 0
    var runs = 0
    var slowest = 0L
    val started = System.nanoTime()
    while (true) {
      val pageStart = System.nanoTime()
      val page = catalog.similarRuns(from, 500)
      slowest = maxOf(slowest, (System.nanoTime() - pageStart) / 1_000_000)
      pages++
      runs += page.runs.size
      page.runs.forEach { run ->
        assertThat(run.candidates.map { it.second }.toSet()).containsExactly(run.second)
        assertThat(run.candidates.map { it.isVideo }.toSet()).hasSize(1)
      }
      from = page.nextSecond ?: break
    }
    val total = (System.nanoTime() - started) / 1_000_000
    println("similarRuns su 100.000 media: $runs gruppi in $pages pagine, $total ms in tutto, pagina piu' lenta $slowest ms")
    // Le coppie di un video e un foto nello stesso secondo non sono un gruppo (tipi diversi): al piu' le coppie costruite.
    assertThat(runs).isAtMost(pairs)
    assertThat(runs).isGreaterThan(pairs * 8 / 10)
    assertThat(slowest).isLessThan(1_000)

    // Il conteggio per la manutenzione (cosa aspetta ancora una firma) costa quanto un passaggio.
    val countStarted = System.nanoTime()
    val unsigned = catalog.similarUnsigned(1)
    val countMillis = (System.nanoTime() - countStarted) / 1_000_000
    println("similarUnsigned su 100.000 media: $unsigned in $countMillis ms")
    assertThat(unsigned).isEqualTo(runs * 2)
    assertThat(countMillis).isLessThan(3_000)
  }
}
