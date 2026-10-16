package dev.pampa.mycloudgallery.core.database

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.Db.DAY
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.dao.BackupQueries
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.random.Random

/**
 * Le query del backup su numeri veri (Fase 5A, P1, riga "Fatto quando" di `docs/fase5-progetto.md` §4.4): **100 mila media**,
 * una pagina di candidati sotto i 100 ms senza `USE TEMP B-TREE` (`EXPLAIN QUERY PLAN` sullo stesso testo che il catalogo esegue),
 * la rotazione dall'indice di `verified_at`, la deduplica dall'indice di `media_version`. I tempi sono su JVM (Robolectric): il
 * dispositivo si misura a M2.
 */
@RunWith(AndroidJUnit4::class)
class BackupQueriesTest : CatalogTestBase() {

  /** Cosa scrivere per un media delle prove in massa. */
  private class Spec(
    val video: Boolean = false,
    val source: String = "camera",
    val presence: String,
    val safety: String,
    val todo: Boolean,
    /** Una posizione del telefono (riga del MediaStore) di cartella [folder]. */
    val phone: Boolean,
    /** Una copia nell'area privata: l'istante della verifica, o `null` = non verificata; `NONE` = nessuna copia privata. */
    val privateCopy: Boolean,
    val verifiedAt: Long?,
  )

  private val folders = listOf("DCIM/Camera/", "Pictures/Screenshots/", "Pictures/Telegram/")

  /**
   * [count] media in SQL grezzo (il catalogo ne scriverebbe 100 mila in minuti), con le righe dei derivati che il catalogo
   * calcolerebbe. Restituisce le identita' in ordine di inserimento.
   */
  private fun bulk(count: Int, spec: (Int) -> Spec): List<String> {
    val sql = db.openHelper.writableDatabase
    val random = Random(11)
    val ids = ArrayList<String>(count)
    sql.beginTransaction()
    try {
      sql.execSQL("INSERT OR REPLACE INTO media_store_cursor (volume, ms_version) VALUES ('external_primary', '${Db.STORE_V1}')")
      val version = sql.compileStatement(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES (?, ?, 'original', NULL, ?, ?, ?, '', 0)",
      )
      val media = sql.compileStatement(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash, backup_todo) " +
          "VALUES (?, ?, ?, 'alessio', NULL, ?, 0, 0, ?, ?, ?, 20251009, 1333, NULL, NULL, ?, NULL, ?)",
      )
      val phone = sql.compileStatement(
        "INSERT INTO media_location (version_id, location_type, location_key, folder_key, state, volume, relative_path, display_name, " +
          "media_store_id, media_store_version, generation, account_id, share_name, share_key, nas_path, size, modified_at, absent_since, " +
          "verified_at, last_seen_at, file_id) VALUES (?, 'phone', ?, ?, 'present', 'external_primary', ?, ?, ?, ?, 7, NULL, NULL, NULL, " +
          "NULL, ?, 1000, NULL, NULL, 1000, NULL)",
      )
      val nas = sql.compileStatement(
        "INSERT INTO media_location (version_id, location_type, location_key, folder_key, state, volume, relative_path, display_name, " +
          "media_store_id, media_store_version, generation, account_id, share_name, share_key, nas_path, size, modified_at, absent_since, " +
          "verified_at, last_seen_at, file_id) VALUES (?, 'nas', ?, ?, 'present', NULL, NULL, NULL, NULL, NULL, NULL, 'alessio', 'alessio', " +
          "'alessio', ?, ?, 1000, NULL, ?, 1000, NULL)",
      )
      for (i in 0 until count) {
        val s = spec(i)
        val mediaId = UUID(random.nextLong(), random.nextLong()).toString()
        val versionId = UUID(random.nextLong(), random.nextLong()).toString()
        val size = 2_000_000L + i
        version.clearBindings()
        version.bindString(1, versionId)
        version.bindString(2, mediaId)
        version.bindLong(3, size)
        version.bindString(4, "%064x".format(i))
        version.bindString(5, "%064x".format(i + 1_000_000))
        version.executeInsert()
        var flags = 0L
        if (s.phone) flags = flags or CellFlags.ON_PHONE.toLong()
        if (s.privateCopy || s.presence == "nas_only") flags = flags or CellFlags.ON_NAS.toLong()
        if (s.safety == "safe") flags = flags or CellFlags.SAFE.toLong()
        media.clearBindings()
        media.bindString(1, mediaId)
        media.bindString(2, if (s.video) "video" else "photo")
        media.bindString(3, s.source)
        media.bindString(4, versionId)
        media.bindLong(5, T0 - i * 60_000L)
        media.bindString(6, s.presence)
        media.bindString(7, s.safety)
        media.bindLong(8, flags)
        media.bindLong(9, if (s.todo) 1 else 0)
        media.executeInsert()
        if (s.phone) {
          val folder = folders[i % folders.size]
          phone.clearBindings()
          phone.bindString(1, versionId)
          phone.bindString(2, "phone:external_primary:${folder.lowercase()}img_$i.jpg")
          phone.bindString(3, "phone:external_primary:${folder.lowercase()}")
          phone.bindString(4, folder)
          phone.bindString(5, "IMG_$i.jpg")
          phone.bindLong(6, 10_000L + i)
          phone.bindString(7, Db.STORE_V1)
          phone.bindLong(8, size)
          phone.executeInsert()
        }
        if (s.privateCopy) {
          nas.clearBindings()
          nas.bindString(1, versionId)
          nas.bindString(2, "nas:alessio:alessio:backup/s25/${i / 500}/img_$i.jpg")
          nas.bindString(3, "nas:alessio:alessio:backup/s25/${i / 500}")
          nas.bindString(4, "Backup/S25/${i / 500}/IMG_$i.jpg")
          nas.bindLong(5, size)
          if (s.verifiedAt != null) nas.bindLong(6, s.verifiedAt) else nas.bindNull(6)
          nas.executeInsert()
        }
        ids += mediaId
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
    return ids
  }

  /** Il piano di una query dinamica con i suoi parametri veri. */
  private fun planOf(query: SupportSQLiteQuery): String {
    val args = ArrayList<Any?>()
    query.bindTo(object : SupportSQLiteProgram {
      override fun bindNull(index: Int) = set(index, null)

      override fun bindLong(index: Int, value: Long) = set(index, value)

      override fun bindDouble(index: Int, value: Double) = set(index, value)

      override fun bindString(index: Int, value: String) = set(index, value)

      override fun bindBlob(index: Int, value: ByteArray) = set(index, value)

      override fun clearBindings() = args.clear()

      override fun close() = Unit

      private fun set(index: Int, value: Any?) {
        while (args.size < index) args += null
        args[index - 1] = value
      }
    })
    return db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN ${query.sql}", args.toTypedArray()).use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")
  }

  private fun planOf(sql: String, vararg args: Any?): String =
    db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", arrayOf(*args)).use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")

  private val everything = setOf<CatalogSource>(
    CatalogSource.Camera,
    CatalogSource.Screenshots,
    CatalogSource.Folder("external_primary", "Pictures/Telegram/"),
  )

  /** Una pagina di candidati, con il tempo, e il cursore per la successiva. */
  private suspend fun page(after: CatalogCandidateCursor?, kind: CatalogBackupKind, label: String, sources: Set<CatalogSource> = everything): List<CatalogBackupCandidate> {
    val started = System.nanoTime()
    val rows = catalog.backupCandidates(sources, kind, after, 500)
    val millis = (System.nanoTime() - started) / 1_000_000
    println("backupCandidates $label: ${rows.size} candidati in $millis ms")
    assertWithMessage(label).that(millis).isLessThan(100)
    return rows
  }

  // --- 100 mila media, quasi tutti candidati --------------------------------------------------------------------------------------

  @Test
  fun centoMilaCandidatiSiLeggonoAPagineSotto100MsSenzaOrdinareInMemoria() = runBlocking<Unit> {
    val ids = bulk(100_000) { i ->
      Spec(
        video = i % 10 == 0,
        source = if (i % 5 == 4) "screenshot" else "camera",
        presence = "phone_only",
        safety = "no_nas_copy",
        todo = true,
        phone = true,
        privateCopy = false,
        verifiedAt = null,
      )
    }
    val query = BackupQueries.candidates("alessio", everything, CatalogBackupKind.PHOTOS, CatalogCandidateCursor(T0 - 500 * 60_000L, MediaId(ids[0])), 500)!!
    val plan = planOf(query)
    println("Piano di backupCandidates (100.000 candidati): $plan")
    assertThat(plan).contains("index_media_backup_todo_sort_time_media_id")
    assertThat(plan).doesNotContain("TEMP B-TREE")

    // Tutte le pagine di foto, dal piu' recente: ordine, nessun doppione, ogni pagina entro il tetto.
    val seen = HashSet<MediaId>()
    var cursor: CatalogCandidateCursor? = null
    var pages = 0
    var previous = Long.MAX_VALUE
    while (true) {
      val rows = page(cursor, CatalogBackupKind.PHOTOS, "foto, pagina ${pages + 1}")
      if (rows.isEmpty()) break
      pages++
      rows.forEach {
        assertThat(it.sortTime).isAtMost(previous)
        previous = it.sortTime
        assertThat(seen.add(it.mediaId)).isTrue()
        assertThat(it.kind.isVideo).isFalse()
      }
      cursor = rows.last().let { CatalogCandidateCursor(it.sortTime, it.mediaId) }
    }
    // Foto: 90.000; tre sorgenti ma una (Telegram) senza media nel volume di prova: camera e screenshot.
    assertThat(seen).hasSize(90_000)
    assertThat(pages).isEqualTo(180)
    // I video: un giro a parte.
    val videos = page(null, CatalogBackupKind.VIDEOS, "video, prima pagina")
    assertThat(videos).hasSize(500)
    videos.forEach { assertThat(it.kind.isVideo).isTrue() }
    // Una pagina nel mezzo della libreria costa come la prima.
    page(CatalogCandidateCursor(T0 - 50_000 * 60_000L, MediaId(ids[50_000])), CatalogBackupKind.PHOTOS, "foto, a meta'")
  }

  // --- 100 mila media, pochi candidati (il caso della fine del primo backup) ----------------------------------------------------------

  @Test
  fun conPochiCandidatiNellaLibreriaUnaPaginaNonScorreTuttoLIndice() = runBlocking<Unit> {
    // 7.000 sul telefono (350 da caricare, 6.650 gia' al sicuro), 93.000 solo sul NAS: la libreria dopo il primo backup.
    bulk(100_000) { i ->
      when {
        i % 100 == 3 && (i / 100) % 20 < 7 -> Spec(presence = "phone_only", safety = "no_nas_copy", todo = true, phone = true, privateCopy = false, verifiedAt = null)
        i % 100 < 7 -> Spec(presence = "phone_and_nas", safety = "safe", todo = false, phone = true, privateCopy = true, verifiedAt = T0)
        else -> Spec(presence = "nas_only", safety = "safe", todo = false, phone = false, privateCopy = true, verifiedAt = T0)
      }
    }
    val todo = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media WHERE backup_todo = 1").use { it.moveToFirst(); it.getInt(0) }
    assertThat(todo).isEqualTo(350)

    val first = page(null, CatalogBackupKind.PHOTOS, "pochi candidati, prima pagina")
    assertThat(first).hasSize(350)
    // Esaurito: la pagina dopo l'ultimo e' vuota, e costa quanto le altre (non scorre i 100.000).
    val last = page(first.last().let { CatalogCandidateCursor(it.sortTime, it.mediaId) }, CatalogBackupKind.PHOTOS, "pochi candidati, pagina vuota")
    assertThat(last).isEmpty()
    // I conteggi per la pagina del backup, sulle stesse 100.000 righe.
    val started = System.nanoTime()
    val counts = catalog.safetyCounts(everything)
    val millis = (System.nanoTime() - started) / 1_000_000
    println("safetyCounts su 100.000 media: $counts in $millis ms")
    assertThat(counts.noNasCopy).isEqualTo(350)
    assertThat(counts.safe).isEqualTo(99_650)
    assertThat(millis).isLessThan(1_500)
  }

  // --- Le verifiche e la deduplica ----------------------------------------------------------------------------------------------------

  @Test
  fun laRotazioneEIlNonVerificatoUsanoLIndiceDiVerifiedAtSenzaOrdinare() = runBlocking<Unit> {
    // 100.000 copie nell'area privata: 99.000 verificate in tempi diversi, 1.000 mai rilette.
    bulk(100_000) { i ->
      Spec(
        presence = "phone_and_nas",
        safety = if (i % 100 == 0) "unverified" else "safe",
        todo = i % 100 == 0,
        phone = i % 10 == 0,
        privateCopy = true,
        verifiedAt = if (i % 100 == 0) null else T0 - (i % 3_650) * DAY - i,
      )
    }
    val plans = mapOf(
      "Unverified" to (planOf(BackupQueries.UNVERIFIED_SQL, "alessio", "alessio", 500)),
      "Rotation" to (planOf(BackupQueries.ROTATION_SQL, "alessio", "alessio", 500)),
    )
    plans.forEach { (name, plan) ->
      println("Piano di $name: $plan")
      assertWithMessage(name).that(plan).contains("index_media_location_account_id_share_key_verified_at")
      assertWithMessage(name).that(plan).doesNotContain("TEMP B-TREE")
    }
    // La coda di `FULL_HASH` parte dalle fasi in coda (indice `stage`, `status`, `updated_at`) e scende per chiave.
    val fullHashPlan = planOf(BackupQueries.FULL_HASH_SQL, "alessio", 500)
    println("Piano di FullHash: $fullHashPlan")
    assertThat(fullHashPlan).contains("index_work_state_stage_status_updated_at")
    assertThat(fullHashPlan).doesNotContain("TEMP B-TREE")

    var started = System.nanoTime()
    val rotation = catalog.verifyCandidates(CatalogVerifyScope.Rotation, 500)
    var millis = (System.nanoTime() - started) / 1_000_000
    println("verifyCandidates(Rotation) su 100.000 copie: ${rotation.size} in $millis ms")
    assertThat(rotation).hasSize(500)
    assertThat(rotation.map { it.verifiedAtMillis }).isInOrder()
    val oldest = db.openHelper.readableDatabase.query("SELECT MIN(verified_at) FROM media_location").use { it.moveToFirst(); it.getLong(0) }
    assertThat(rotation.first().verifiedAtMillis).isEqualTo(oldest) // la piu' vecchia
    assertThat(millis).isLessThan(100)

    started = System.nanoTime()
    val unverified = catalog.verifyCandidates(CatalogVerifyScope.Unverified, 500)
    millis = (System.nanoTime() - started) / 1_000_000
    println("verifyCandidates(Unverified) su 100.000 copie: ${unverified.size} in $millis ms")
    assertThat(unverified).hasSize(500)
    assertThat(unverified.map { it.copy.locationId.value }).isInOrder()
    assertThat(millis).isLessThan(100)
  }

  @Test
  fun leCopiePrivateCandidateDellaDeduplicaSiTrovanoPerDimensioneEImpronta() = runBlocking<Unit> {
    bulk(100_000) { Spec(presence = "nas_only", safety = "safe", todo = false, phone = false, privateCopy = true, verifiedAt = T0) }
    val quick = "%064x".format(77_777)
    val sql = BackupQueries.privateCopies("alessio", "alessio", 2_000_000L + 77_777, quick, 10)
    val plan = planOf(sql)
    println("Piano di privateCopies: $plan")
    assertThat(plan).contains("index_media_version_size_quick_hash")
    assertThat(plan).doesNotContain("TEMP B-TREE")

    val started = System.nanoTime()
    val found = catalog.privateCopies("alessio", 2_000_000L + 77_777, dev.pampa.mycloudgallery.core.model.QuickHash(quick), 10)
    val none = catalog.privateCopies("alessio", 1_999_999L, null, 10)
    val millis = (System.nanoTime() - started) / 1_000_000
    println("privateCopies su 100.000 media: ${found.size} + ${none.size} in $millis ms")
    assertThat(found).hasSize(1)
    assertThat(none).isEmpty()
    assertThat(millis).isLessThan(100)
  }

  @Test
  fun iMediaProprioConLaSolaCopiaComuneEIlProblemaNonScorronoLaLibreria() = runBlocking<Unit> {
    bulk(100_000) { Spec(presence = "nas_only", safety = "safe", todo = false, phone = false, privateCopy = true, verifiedAt = T0) }
    val started = System.nanoTime()
    val guard = catalog.onlyCommonCopies(null, 500)
    val millis = (System.nanoTime() - started) / 1_000_000
    println("onlyCommonCopies su 100.000 media: ${guard.size} in $millis ms")
    assertThat(guard).isEmpty()
    assertThat(millis).isLessThan(100)
    val plan = planOf(BackupQueries.onlyCommonCopies("alessio", null, 500))
    println("Piano di onlyCommonCopies: $plan")
    assertThat(plan).contains("index_media_safety")
  }
}
