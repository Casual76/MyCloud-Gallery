package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.random.Random

/**
 * L'indice della timeline (D2 di `docs/fase3-progetto.md`): ordine dall'indice senza ordinare in memoria,
 * 100 mila righe lette con un `Cursor` grezzo entro un budget largo, nascosti e membri dei gruppi fuori.
 */
@RunWith(AndroidJUnit4::class)
class TimelineIndexTest : CatalogTestBase() {

  /**
   * [count] media scritti in SQL grezzo in una transazione (il catalogo ne scriverebbe 100 mila in
   * minuti: qui si prova la lettura). Ogni 200 uno nascosto, ogni 100 uno dietro la copertina di un
   * gruppo, ogni 300 uno sparito. Restituisce le identita' attese nell'indice.
   */
  private fun bulkInsert(count: Int): Set<String> {
    val sql = db.openHelper.writableDatabase
    val random = Random(42)
    val expected = HashSet<String>()
    sql.beginTransaction()
    try {
      val version = sql.compileStatement(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES (?, ?, 'original', NULL, ?, ?, NULL, '', ?)",
      )
      val media = sql.compileStatement(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash) " +
          "VALUES (?, ?, 'camera', 'alessio', NULL, ?, 0, 0, ?, ?, 'no_nas_copy', ?, ?, ?, ?, ?, ?)",
      )
      val hidden = sql.compileStatement("INSERT INTO media_personal (media_id, favorite, hidden, updated_at) VALUES (?, 0, 1, 0)")
      val thumb = ByteArray(25) { it.toByte() }
      for (i in 0 until count) {
        val mediaId = UUID(random.nextLong(), random.nextLong()).toString()
        val versionId = UUID(random.nextLong(), random.nextLong()).toString()
        val sortTime = Db.T0 - random.nextLong(15L * 365 * Db.DAY)
        val gone = i % 300 == 211
        val member = i % 100 == 37
        val isHidden = i % 200 == 113
        version.clearBindings()
        version.bindString(1, versionId)
        version.bindString(2, mediaId)
        version.bindLong(3, 1_000L + i)
        version.bindString(4, "%064x".format(i))
        version.bindLong(5, sortTime)
        version.executeInsert()
        media.clearBindings()
        media.bindString(1, mediaId)
        media.bindString(2, if (i % 12 == 0) "video" else "photo")
        media.bindString(3, versionId)
        media.bindLong(4, sortTime)
        media.bindString(5, if (gone) "none" else "phone_only")
        media.bindLong(6, 20_200_101L)
        media.bindLong(7, 1_333)
        if (i % 12 == 0) media.bindLong(8, 12_000) else media.bindNull(8)
        if (i % 3 == 0) media.bindLong(9, 3_143_244) else media.bindNull(9)
        media.bindLong(10, if (member) CellFlags.GROUP_MEMBER.toLong() else 0L)
        media.bindBlob(11, thumb)
        media.executeInsert()
        if (isHidden) {
          hidden.bindString(1, mediaId)
          hidden.executeInsert()
        }
        if (!gone && !member && !isHidden) expected += mediaId
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
    return expected
  }

  private fun planOf(sql: String): String =
    db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")

  /**
   * La query dell'indice scorre l'indice (`sort_time`, `media_id`): nessun ordinamento in memoria
   * (`USE TEMP B-TREE FOR ORDER BY`). Vale senza statistiche (come sul telefono, dove nessuno esegue
   * `ANALYZE`) e con.
   */
  @Test
  fun laQueryDellIndiceNonOrdinaInMemoria() = runBlocking<Unit> {
    bulkInsert(2_000)

    val plain = planOf(TimelineIndexDao.INDEX_SQL)
    db.openHelper.writableDatabase.execSQL("ANALYZE")
    val analyzed = planOf(TimelineIndexDao.INDEX_SQL)

    println("Piano della query dell'indice: $plain / dopo ANALYZE: $analyzed")
    listOf(plain, analyzed).forEach { plan ->
      assertThat(plan).contains("index_media_sort_time_media_id")
      assertThat(plan).doesNotContain("TEMP B-TREE")
    }
  }

  /**
   * P11: i riferimenti ai byte del telefono partono dai media chiesti e scendono per `version_id`, **mai** dall'indice
   * dello stato delle posizioni (tutte le posizioni presenti a ogni blocco di 500: minuti su 100 mila media, visto
   * sull'emulatore con la Libreria sintetica). Senza statistiche e con.
   */
  @Test
  fun iRiferimentiAiByteNonScorronoTutteLePosizioni() = runBlocking<Unit> {
    bulkInsert(2_000)
    val sql = TimelineIndexDao.PHONE_REFS_SQL.replace("(:ids)", "('a', 'b', 'c')")

    val plain = planOf(sql)
    db.openHelper.writableDatabase.execSQL("ANALYZE")
    val analyzed = planOf(sql)

    println("Piano dei riferimenti ai byte: $plain / dopo ANALYZE: $analyzed")
    listOf(plain, analyzed).forEach { plan ->
      assertThat(plan).contains("index_media_location_version_id")
      assertThat(plan).doesNotContain("index_media_location_state_absent_since")
    }
  }

  /** 100 mila righe in un `Cursor` grezzo, nell'ordine della timeline, entro un budget largo. */
  @Test
  fun centoMilaRigheSiLegganoConUnCursorEntroIlBudget() = runBlocking<Unit> {
    val expected = bulkInsert(100_000)

    val started = System.nanoTime()
    val ids = ArrayList<String>(expected.size)
    var previous = Long.MAX_VALUE
    var ordered = true
    var thumbBytes = 0L
    withContext(Dispatchers.IO) {
      db.timelineIndexDao().indexCursor().use { cursor ->
        val id = cursor.getColumnIndexOrThrow("media_id")
        val sortTime = cursor.getColumnIndexOrThrow("sort_time")
        val localDay = cursor.getColumnIndexOrThrow("local_day")
        val kind = cursor.getColumnIndexOrThrow("kind")
        val flags = cursor.getColumnIndexOrThrow("cell_flags")
        val aspect = cursor.getColumnIndexOrThrow("aspect_milli")
        val duration = cursor.getColumnIndexOrThrow("duration_ms")
        val place = cursor.getColumnIndexOrThrow("place_id")
        val thumb = cursor.getColumnIndexOrThrow("thumb_hash")
        while (cursor.moveToNext()) {
          val time = cursor.getLong(sortTime)
          if (time > previous) ordered = false
          previous = time
          ids += cursor.getString(id)
          cursor.getInt(localDay)
          cursor.getString(kind)
          cursor.getInt(flags)
          cursor.getInt(aspect)
          if (!cursor.isNull(duration)) cursor.getLong(duration)
          if (!cursor.isNull(place)) cursor.getInt(place)
          thumbBytes += cursor.getBlob(thumb)?.size ?: 0
        }
      }
    }
    val millis = (System.nanoTime() - started) / 1_000_000
    println("Indice di ${ids.size} righe letto con un Cursor in $millis ms")

    assertThat(ids.size).isEqualTo(expected.size)
    assertThat(ids.toSet()).isEqualTo(expected)
    assertThat(ordered).isTrue()
    assertThat(thumbBytes).isEqualTo(25L * expected.size)
    // Budget largo: su JVM l'atteso e' sotto il secondo e mezzo; il dispositivo si misura in P9.
    assertThat(millis).isLessThan(8_000)
  }

  /**
   * Fase 5A (P1, D36): 100 mila media di cui **1.000 gruppi di copie simili** (coppie, una ogni cento celle, e un gruppo su
   * cinquanta nascosto per intero). L'indice della timeline resta senza `USE TEMP B-TREE`, legge solo le copertine dei gruppi
   * visibili (mai un altro membro) ed entra nello stesso budget di `centoMilaRigheSiLegganoConUnCursorEntroIlBudget`.
   */
  @Test
  fun centoMilaMediaConMilleGruppiDiCopieSimiliRestanoNelBudgetSenzaOrdinareInMemoria() = runBlocking<Unit> {
    val expected = HashSet(bulkInsert(100_000))
    val sql = db.openHelper.writableDatabase
    val candidates = expected.toList().sorted().take(2_000)
    val random = Random(99)
    var hiddenGroups = 0
    sql.beginTransaction()
    try {
      val group = sql.compileStatement("INSERT INTO media_group (group_id, kind, cover_media_id, created_at) VALUES (?, 'similar_copies', ?, 0)")
      val cover = sql.compileStatement("UPDATE media SET group_id = ?, cell_flags = cell_flags | ? WHERE media_id = ?")
      val hide = sql.compileStatement("INSERT OR REPLACE INTO media_personal (media_id, favorite, hidden, updated_at) VALUES (?, 0, 1, 0)")
      for (g in 0 until 1_000) {
        val a = candidates[2 * g]
        val b = candidates[2 * g + 1]
        val groupId = UUID(random.nextLong(), random.nextLong()).toString()
        group.clearBindings()
        group.bindString(1, groupId)
        group.bindString(2, a)
        group.executeInsert()
        cover.clearBindings()
        cover.bindString(1, groupId)
        cover.bindLong(2, (CellFlags.GROUP_COVER or CellFlags.SIMILAR_COPIES).toLong())
        cover.bindString(3, a)
        cover.executeUpdateDelete()
        cover.clearBindings()
        cover.bindString(1, groupId)
        cover.bindLong(2, CellFlags.GROUP_MEMBER.toLong())
        cover.bindString(3, b)
        cover.executeUpdateDelete()
        expected.remove(b)
        if (g % 50 == 7) {
          // Un gruppo nascosto per intero (E-H1): nessuna delle due copie compare, nemmeno quella dietro la copertina.
          hiddenGroups++
          hide.clearBindings()
          hide.bindString(1, a)
          hide.executeInsert()
          hide.clearBindings()
          hide.bindString(1, b)
          hide.executeInsert()
          expected.remove(a)
        }
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
    assertThat(hiddenGroups).isEqualTo(20)

    val plain = planOf(TimelineIndexDao.INDEX_SQL)
    sql.execSQL("ANALYZE")
    val analyzed = planOf(TimelineIndexDao.INDEX_SQL)
    println("Piano dell'indice con 1.000 gruppi di copie simili: $plain / dopo ANALYZE: $analyzed")
    listOf(plain, analyzed).forEach { plan ->
      assertThat(plan).contains("index_media_sort_time_media_id")
      assertThat(plan).doesNotContain("TEMP B-TREE")
    }

    val started = System.nanoTime()
    val ids = HashSet<String>(expected.size)
    var covers = 0
    withContext(Dispatchers.IO) {
      db.timelineIndexDao().indexCursor().use { cursor ->
        val id = cursor.getColumnIndexOrThrow("media_id")
        val flags = cursor.getColumnIndexOrThrow("cell_flags")
        while (cursor.moveToNext()) {
          ids += cursor.getString(id)
          if (cursor.getInt(flags) and CellFlags.SIMILAR_COPIES != 0) covers++
        }
      }
    }
    val millis = (System.nanoTime() - started) / 1_000_000
    println("Indice di ${ids.size} righe con 1.000 gruppi di copie simili letto con un Cursor in $millis ms ($covers copertine di gruppo)")
    assertThat(ids).isEqualTo(expected)
    assertThat(covers).isEqualTo(1_000 - hiddenGroups)
    assertThat(millis).isLessThan(8_000)
    // Il numero sulla copertina ("2 copie") legge solo i membri visibili; costa quanto il resto della manutenzione dell'indice.
    val sizesStarted = System.nanoTime()
    val sizes = db.timelineIndexDao().groupSizes()
    val sizesMillis = (System.nanoTime() - sizesStarted) / 1_000_000
    println("groupSizes con 1.000 gruppi su 100.000 media: ${sizes.size} gruppi in $sizesMillis ms")
    assertThat(sizes).hasSize(1_000 - hiddenGroups)
    assertThat(sizes.all { it.size == 2 }).isTrue()
  }

  /** Le righe dell'indice per identita' (patch dal flusso dei cambiamenti) oltre il limite delle variabili di SQLite. */
  @Test
  fun leRigheSiRileggonoPerIdentitaABlocchi() = runBlocking<Unit> {
    val expected = bulkInsert(3_000)
    val all = db.mediaDao().allIds().map(::MediaId)

    val rows = catalog.indexRows(all)

    assertThat(all.size).isGreaterThan(TimelineIndexDao.MAX_IDS * 2)
    assertThat(rows.map { it.mediaId }.toSet()).isEqualTo(expected)
    val hidden = db.timelineIndexDao().hiddenRows()
    assertThat(hidden).isNotEmpty()
    assertThat(hidden.map { it.mediaId }.toSet().intersect(expected)).isEmpty()
  }
}
