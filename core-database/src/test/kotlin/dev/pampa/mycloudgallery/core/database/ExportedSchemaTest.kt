package dev.pampa.mycloudgallery.core.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.TimelineRules
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * L'infrastruttura delle migrazioni (piano 1.3: "migrazioni del database testate dalla prima versione").
 *
 * Usa gli schemi esportati da Room in `core-database/schemas/`, che stanno sotto controllo di
 * versione: per ogni versione esportata crea un database **esattamente** come lo avrebbe creato
 * quella versione dell'app (dal JSON, non dalle entita' di oggi), ci mette dei dati realistici
 * ([SchemaSeeds]), lo apre con il database di oggi (Room esegue le migrazioni e poi valida lo schema
 * risultante contro le entita') e verifica che i dati ci siano ancora, integri.
 *
 * Con la v2 (Fase 3) il test copre 1 -> 2 da solo: il database v1 con i dati di [SchemaSeeds] migra, i
 * dati restano e i campi nuovi (derivati dell'indice, istanti dei flag personali) sono quelli che il
 * catalogo stesso calcolerebbe. Con la v3 (Fase 4) copre 1 -> 3 e 2 -> 3: i bit di presenza calcolati dalla
 * migrazione sono quelli del catalogo, le tabelle nuove nascono vuote e una v3 con una riga per tabella si riapre.
 */
@RunWith(AndroidJUnit4::class)
class ExportedSchemaTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  // Gradle esegue i test con la cartella del modulo come cartella di lavoro.
  private val schemaDir = File("schemas/${GalleryDatabase::class.java.name}")

  private fun exportedVersions(): List<Int> =
    schemaDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.nameWithoutExtension.toInt() }.sorted()

  @Test
  fun ogniVersioneDalla1AllaCorrenteEEsportataSenzaBuchi() {
    assertThat(exportedVersions()).isEqualTo((1..GalleryDatabase.VERSION).toList())
  }

  /** Lo schema esportato della versione corrente e' quello che Room crea oggi (nessuna modifica senza versione). */
  @Test
  fun loSchemaEsportatoCoincideConQuelloCheRoomCreaOggi() {
    val fromJson = createFromSchema(GalleryDatabase.VERSION, "da-json.db")
    context.deleteDatabase("da-room.db")
    val fromRoom = GalleryDatabase.build(context, "da-room.db")
    try {
      val expected = SQLiteDatabase.openDatabase(fromJson.path, null, SQLiteDatabase.OPEN_READONLY).use { schemaOf(it.rawQuery(SCHEMA_SQL, null)) }
      val actual = schemaOf(fromRoom.openHelper.readableDatabase.query(SCHEMA_SQL))
      assertThat(actual).isNotEmpty()
      assertThat(actual).isEqualTo(expected)
    } finally {
      fromRoom.close()
    }
  }

  /** Ogni versione esportata, con dati, si apre (e migra) fino alla corrente senza perdere nulla. */
  @Test
  fun ogniVersioneEsportataConDatiSiApreEMigraSenzaPerdereNulla() = runBlocking<Unit> {
    for (version in exportedVersions()) {
      val name = "migrazione-da-v$version.db"
      createFromSchema(version, name) { SchemaSeeds.seed(version, it) }

      val database = GalleryDatabase.build(context, name)
      try {
        val sql = database.openHelper.writableDatabase
        sql.query("PRAGMA integrity_check").use { assertThat(it.moveToFirst() && it.getString(0) == "ok").isTrue() }
        sql.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
        SchemaSeeds.verify(database, fromVersion = version)
      } finally {
        database.close()
      }
    }
  }

  /** Un database di una versione futura (app tornata indietro) non si apre e non viene cancellato. */
  @Test
  fun unDatabaseDiUnaVersioneFuturaNonVieneMaiCancellato() {
    val name = "dal-futuro.db"
    val file = createFromSchema(GalleryDatabase.VERSION, name) { SchemaSeeds.seed(GalleryDatabase.VERSION, it) }
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = GalleryDatabase.VERSION + 1 }

    val database = GalleryDatabase.build(context, name)
    val failure = runCatching { database.openHelper.writableDatabase }
    database.close()

    assertThat(failure.isFailure).isTrue()
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db.rawQuery("SELECT COUNT(*) FROM media", null).use { assertThat(it.moveToFirst() && it.getInt(0) == 1).isTrue() }
    }
  }

  /**
   * 3 -> 4: `backup_todo` lo calcola la migrazione dai derivati gia' salvati, con la stessa regola del catalogo
   * (`TimelineRules.backupTodo`), per **ogni** combinazione di presenza e sicurezza; e le tabelle nuove nascono vuote.
   */
  @Test
  fun laMigrazioneDalla3CalcolaIlBackupTodoDiOgniCombinazione() = runBlocking<Unit> {
    val combos = Presence.entries.flatMap { presence -> SafetyStatus.entries.map { presence to it } }
    val name = "migrazione-backup-todo.db"
    createFromSchema(3, name) { db ->
      combos.forEachIndexed { i, (presence, safety) ->
        val media = "00000000-0000-4000-8000-%012d".format(i)
        val version = "10000000-0000-4000-8000-%012d".format(i)
        db.execSQL(
          "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, " +
            "sort_time, presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash) VALUES " +
            "('$media', 'photo', 'camera', 'alessio', NULL, '$version', 0, 0, ${1_760_000_000_000L - i}, '${presence.code}', " +
            "'${safety.code}', 20251009, 1000, NULL, NULL, 0, NULL)",
        )
        db.execSQL(
          "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, " +
            "created_at) VALUES ('$version', '$media', 'original', NULL, 1000, '%064x', NULL, '', 0)".format(i),
        )
      }
    }

    val database = GalleryDatabase.build(context, name)
    try {
      val sql = database.openHelper.writableDatabase
      sql.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
      val todo = sql.query("SELECT media_id, backup_todo FROM media").use { cursor ->
        buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1) == 1) }
      }
      assertThat(todo).hasSize(combos.size)
      combos.forEachIndexed { i, (presence, safety) ->
        assertThat(todo.getValue("00000000-0000-4000-8000-%012d".format(i))).isEqualTo(TimelineRules.backupTodo(presence, safety, false))
      }
      // Solo telefono e telefono+NAS, con due stati non al sicuro (mai la sola copia comune: la crea il guardiano).
      assertThat(todo.values.count { it }).isEqualTo(2 * 2)
      assertThat(database.backupDiaryDao().countsByState()).isEmpty()
    } finally {
      database.close()
    }
  }

  // --- Creazione di un database dallo schema esportato ----------------------------------------------

  private fun createFromSchema(version: Int, name: String, seed: (SQLiteDatabase) -> Unit = {}): File {
    val schema = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
    check(schema.getInt("version") == version) { "Il file $version.json non descrive la versione $version." }
    val file = context.getDatabasePath(name)
    file.parentFile?.mkdirs()
    SQLiteDatabase.deleteDatabase(file)
    SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
      db.beginTransaction()
      try {
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
          val entity = entities.getJSONObject(i)
          val table = entity.getString("tableName")
          db.execSQL(entity.getString("createSql").replace(TABLE_PLACEHOLDER, table))
          val indices = entity.optJSONArray("indices") ?: continue
          for (j in 0 until indices.length()) {
            db.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_PLACEHOLDER, table))
          }
        }
        schema.optJSONArray("views")?.let { views ->
          for (i in 0 until views.length()) {
            val view = views.getJSONObject(i)
            db.execSQL(view.getString("createSql").replace(VIEW_PLACEHOLDER, view.getString("viewName")))
          }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        seed(db)
        db.version = version
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
    return file
  }

  private fun schemaOf(cursor: Cursor): List<String> = cursor.use {
    buildList {
      while (it.moveToNext()) {
        add(listOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3)?.replace(Regex("\\s+"), " ")).joinToString(" | "))
      }
    }
  }

  private companion object {
    const val TABLE_PLACEHOLDER = "\${TABLE_NAME}"
    const val VIEW_PLACEHOLDER = "\${VIEW_NAME}"
    const val SCHEMA_SQL =
      "SELECT type, name, tbl_name, sql FROM sqlite_master " +
        "WHERE name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table') ORDER BY type, name"
  }
}

/**
 * Dati di prova scritti **come li avrebbe scritti ogni versione dell'app**, in SQL grezzo con le
 * colonne di quella versione. Le stringhe (chiavi di posto, codici) sono fissate qui di proposito: se
 * un giorno cambia il formato di una chiave o un codice, il seed della v1 smette di essere leggibile e
 * il test obbliga a scrivere la migrazione che lo converte.
 *
 * Quando arriva la versione N: aggiungere `N -> seedVN(db)`, lasciare intatti i precedenti, e
 * aggiornare [verify] se la v N aggiunge dati da controllare.
 */
object SchemaSeeds {

  const val MEDIA_ID = "11111111-1111-4111-8111-111111111111"
  private const val VERSION_ID = "22222222-2222-4222-8222-222222222222"
  private const val ALBUM_ID = "33333333-3333-4333-8333-333333333333"
  private const val T0 = 1_760_000_000_000L
  private const val QUICK = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  private const val FULL = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

  /** Il luogo e il segnaposto della v2: Oslo nel dataset 1, 25 byte di ThumbHash. */
  private const val OSLO = 3_143_244
  private const val THUMB_HEX = "0102030405060708090A0B0C0D0E0F10111213141516171819"
  private const val CHANGE_SEQ_V2 = 7L

  fun seed(version: Int, db: SQLiteDatabase) = when (version) {
    1 -> seedV1(db)
    2 -> seedV2(db)
    3 -> seedV3(db)
    4 -> seedV4(db)
    else -> error("Manca il seed della versione $version dello schema (vedi SchemaSeeds).")
  }

  // --- v4 (Fase 5) ---
  private const val BACKUP_ITEM_ID = 1L
  private const val SIGNATURE_HEX = "0102030405060708"

  /**
   * La v4: le righe della v3 piu' una riga in ogni tabella nuova (diario del backup con tutti i campi, problema, firma visiva,
   * e le cinque della 5B), con i codici scritti a mano. `backup_todo` della v4 (il media del seed e' al sicuro: spento) arriva dal
   * suo DEFAULT.
   */
  private fun seedV4(db: SQLiteDatabase) {
    seedV3(db)
    db.execSQL(
      "INSERT INTO backup_item (item_id, kind, state, is_open, media_id, version_id, size, phone_volume, phone_ms_id, " +
        "phone_ms_version, expected_size, expected_modified_at, expected_generation, share, planned_path, final_path, name_attempt, " +
        "temp_path, resume_at, resume_file_id, sha256, quick_hash, temp_size, temp_modified_at, temp_file_id, final_size, " +
        "final_modified_at, final_file_id, verify_attempts, guard_share, guard_path, guard_size, guard_modified_at, guard_file_id, " +
        "problem, created_at, updated_at) VALUES ($BACKUP_ITEM_ID, 'new_copy', 'uploading', 1, '$MEDIA_ID', '$VERSION_ID', 300000, " +
        "'external_primary', 42, 'store-v1', 300000, $T0, 7, 'alessio', 'Backup/S25/2025/10/IMG_0001.jpg', " +
        "'Backup/S25/2025/10/IMG_0001 (2).jpg', 1, '.mycloudgallery/tmp/tablet-bk-1', 134217728, $RESUME_FILE_ID, '$FULL', '$QUICK', " +
        "300000, $T0, $FILE_ID, NULL, NULL, NULL, 2, 'NAS', 'Famiglia/IMG_0001.jpg', 300000, $T0, $FILE_ID, NULL, $T0, $T0)",
    )
    db.execSQL("INSERT INTO backup_problem (media_id, code, marks_cell, updated_at) VALUES ('$MEDIA_ID', 'verify_failed', 1, $T0)")
    db.execSQL("INSERT INTO nas_copy_mismatch (location_id, size, modified_at, found_at) VALUES (2, 300000, $T0, ${T0 + 5})")
    db.execSQL("INSERT INTO visual_signature (version_id, algorithm, frames, data, signed_at) VALUES ('$VERSION_ID', 1, 1, X'$SIGNATURE_HEX', $T0)")
    db.execSQL(
      "INSERT INTO pending_task (task_id, kind, state, media_id, version_id, bytes, created_at, updated_at) VALUES " +
        "(1, 'delete_original_video', 'open', '$MEDIA_ID', '$VERSION_ID', 2500000000, $T0, $T0)",
    )
    db.execSQL(
      "INSERT INTO edit_session (session_id, media_id, version_id, target, state, phone_volume, phone_ms_id, phone_ms_version, " +
        "start_size, start_modified_at, start_generation, relative_path, taken_at_utc, cache_name, cache_sha256, started_at, " +
        "expires_at) VALUES (1, '$MEDIA_ID', '$VERSION_ID', 'phone_view', 'open', 'external_primary', 42, 'store-v1', 300000, $T0, 7, " +
        "'DCIM/Camera/', $T0, NULL, NULL, $T0, ${T0 + 30 * 60_000})",
    )
    db.execSQL(
      "INSERT INTO free_space_run (run_id, state, filter, exclude_recent_days, plan_hash, delete_mode, item_count, total_bytes, " +
        "batches_total, batches_done, created_at, updated_at) VALUES (1, 'ready', 'all', 30, '$SEGMENT', 'android_trash', 1, 300000, 1, 0, " +
        "$T0, $T0)",
    )
    db.execSQL(
      "INSERT INTO free_space_item (run_id, item_index, state, media_id, version_id, phone_location_id, nas_location_id, size, " +
        "verified_size, verified_modified_at, verified_file_id, batch_index, reason, updated_at) VALUES (1, 0, 'verified', '$MEDIA_ID', " +
        "'$VERSION_ID', 1, 2, 300000, 300000, $T0, $FILE_ID, 0, NULL, $T0)",
    )
    db.execSQL(
      "INSERT INTO incoming_item (incoming_id, stage_id, state, file_name, display_name, sha256, size, mime, dest_share, dest_folder, " +
        "backup_item_id, created_at, updated_at) VALUES (1, 'stage-1', 'queued', '$FULL', 'IMG-20251009-WA0001.jpg', '$FULL', 120000, " +
        "'image/jpeg', 'alessio', 'Backup/S25/Ricevute', $BACKUP_ITEM_ID, $T0, $T0)",
    )
  }

  // --- v3 (Fase 4) ---
  private const val FILE_ID = 987_654L

  /** v4, R3 della revisione 5A: l'identita' del temporaneo al punto di ripresa. */
  private const val RESUME_FILE_ID = 876_543L
  private const val SEGMENT = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  private const val RECORD_KEY = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
  private const val OP_ID = "44444444-4444-4444-8444-444444444444"

  /** I flag della cella della v2 con i bit di presenza della v3: sul telefono, sul NAS, al sicuro. */
  private const val PRESENCE_V3 = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE

  /**
   * La v3: le righe della v2 con il `FileId` della copia sul NAS e i bit di presenza, piu' una riga in ogni tabella nuova
   * (scansione congelata, outbox, replica, diario e cestino della 4B), con i codici scritti a mano.
   */
  private fun seedV3(db: SQLiteDatabase) {
    db.execSQL(
      "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, " +
        "sort_time, presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash) VALUES " +
        "('$MEDIA_ID', 'photo', 'camera', 'alessio', NULL, '$VERSION_ID', $T0, $T0, $T0, 'phone_and_nas', 'safe', " +
        "20251009, 750, NULL, $OSLO, ${136 or PRESENCE_V3}, X'$THUMB_HEX')",
    )
    db.execSQL(
      "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, " +
        "created_at) VALUES ('$VERSION_ID', '$MEDIA_ID', 'original', NULL, 300000, '$QUICK', '$FULL', '', $T0)",
    )
    db.execSQL(
      "INSERT INTO version_metadata (version_id, mime_type, width, height, orientation, duration_ms, taken_at_utc, " +
        "taken_offset_min, taken_confidence, taken_source, latitude, longitude, altitude, updated_at, mirrored, traits, " +
        "place_id, place_dataset, thumb_hash) VALUES ('$VERSION_ID', 'image/jpeg', 4000, 3000, 90, NULL, $T0, 120, 'exact', " +
        "'exif_offset', 59.91, 10.75, NULL, $T0, 0, 0, $OSLO, 1, X'$THUMB_HEX')",
    )
    db.execSQL(
      "INSERT INTO media_location (location_id, version_id, location_type, location_key, folder_key, state, volume, " +
        "relative_path, display_name, media_store_id, media_store_version, generation, account_id, share_name, share_key, " +
        "nas_path, size, modified_at, absent_since, verified_at, last_seen_at, file_id) VALUES " +
        "(1, '$VERSION_ID', 'phone', 'phone:external_primary:dcim/camera/img_0001.jpg', 'phone:external_primary:dcim/camera/', " +
        "'present', 'external_primary', 'DCIM/Camera/', 'IMG_0001.jpg', 42, 'store-v1', 7, NULL, NULL, NULL, NULL, 300000, $T0, " +
        "NULL, NULL, $T0, NULL), " +
        "(2, '$VERSION_ID', 'nas', 'nas:alessio:alessio:backup/s25/img_0001.jpg', 'nas:alessio:alessio:backup/s25', 'present', " +
        "NULL, NULL, NULL, NULL, NULL, NULL, 'alessio', 'alessio', 'alessio', 'Backup/S25/IMG_0001.jpg', 300000, $T0, NULL, $T0, " +
        "$T0, $FILE_ID)",
    )
    db.execSQL("INSERT INTO nas_share (account_id, share_key, share_name, area) VALUES ('alessio', 'alessio', 'alessio', 'private')")
    db.execSQL(
      "INSERT INTO media_personal (media_id, favorite, hidden, updated_at, favorite_changed_at, hidden_changed_at) VALUES " +
        "('$MEDIA_ID', 1, 0, $T0, ${T0 - 5}, ${T0 - 6})",
    )
    db.execSQL("INSERT INTO album (album_id, title, cover_media_id, created_at, updated_at) VALUES ('$ALBUM_ID', 'Oslo 2026', '$MEDIA_ID', $T0, $T0)")
    db.execSQL("INSERT INTO album_item (album_id, media_id, position, added_at) VALUES ('$ALBUM_ID', '$MEDIA_ID', 1, $T0)")
    db.execSQL(
      "INSERT INTO work_state (media_id, stage, status, version_id, attempts, last_error, updated_at) VALUES " +
        "('$MEDIA_ID', 'embed', 'done', '$VERSION_ID', 0, NULL, $T0), ('$MEDIA_ID', 'ocr', 'failed', '$VERSION_ID', 2, 'DECODE_FAILED', $T0), " +
        "('$MEDIA_ID', 'full_hash', 'done', '$VERSION_ID', 0, NULL, $T0)",
    )
    db.execSQL(
      "INSERT INTO embedding (media_id, space_id, dimension, precision, vector_index, vector, version_id, created_at) VALUES " +
        "('$MEDIA_ID', 'embeddinggemma2-float-v1', 2, 'f32', 0, X'0000803F00000040', '$VERSION_ID', $T0)",
    )
    db.execSQL(
      "INSERT INTO media_store_cursor (volume, ms_version, generation, deleted_generation, scan_epoch, scan_resume_after_id, " +
        "last_full_scan_at, last_reconcile_at) VALUES ('external_primary', 'store-v1', 7, 6, $T0, 41, NULL, ${T0 - 1})",
    )
    db.execSQL("INSERT INTO catalog_meta (`key`, value) VALUES ('change_seq', $CHANGE_SEQ_V2), ('places_dataset', 1)")
    // Tabelle nuove della v3.
    db.execSQL(
      "INSERT INTO nas_scan_state (account_id, share_key, share_name, epoch, resume_after, last_full_scan_at, freeze_reason, " +
        "freeze_since, freeze_known_present, freeze_vanished, freeze_changed, freeze_not_media) VALUES " +
        "('alessio', 'alessio', 'alessio', $T0, 'Backup/S25', ${T0 - 10}, 'vanished', ${T0 - 5}, 1000, 60, 0, 0)",
    )
    db.execSQL("INSERT INTO index_publish_state (share_key, share_name, next_seq, head_version, head_hash, head_published_at) VALUES ('alessio', 'alessio', 12, 3, '$SEGMENT', $T0)")
    db.execSQL("INSERT INTO index_own_segment (share_key, segment_hash, cls, size, entries, listed) VALUES ('alessio', '$SEGMENT', 1, 512, 1, 1)")
    db.execSQL(
      "INSERT INTO index_outbox_record (id, share_key, seq, path_key, content_key, body, sunk) VALUES " +
        "(1, 'alessio', 11, '$RECORD_KEY', '$QUICK', X'7B7D', 0)",
    )
    db.execSQL(
      "INSERT INTO index_outbox_blob (id, share_key, cls, content_key, byte_length, handle, sunk) VALUES " +
        "(1, 'alessio', 2, '$QUICK', 6000, 'h-1', 1)",
    )
    db.execSQL(
      "INSERT INTO index_own_record (share_key, path_key, path, seq, gone, content_key, segment_hash) VALUES " +
        "('alessio', '$RECORD_KEY', 'Backup/S25/IMG_0001.jpg', 10, 0, '$QUICK', '$SEGMENT')",
    )
    db.execSQL("INSERT INTO index_own_blob (share_key, cls, content_key, segment_hash) VALUES ('alessio', 2, '$QUICK', '$SEGMENT')")
    db.execSQL("INSERT INTO index_head (share_key, device_id, version, content_hash, registry_version, created_at) VALUES ('alessio', 'tablet', 4, '$SEGMENT', 9, $T0)")
    db.execSQL(
      "INSERT INTO index_segment (share_key, device_id, segment_hash, cls, size, state, listed, pack_id, position) VALUES " +
        "('alessio', 'tablet', '$SEGMENT', 2, 6000, 'applied', 1, 1, 0)",
    )
    db.execSQL(
      "INSERT INTO index_record (share_key, device_id, segment_hash, record_key, seq, gone, content_key, prep, body) VALUES " +
        "('alessio', 'tablet', '$SEGMENT', '$RECORD_KEY', 5, 0, '$QUICK', 6, X'7B7D')",
    )
    db.execSQL("INSERT INTO replica_dirty (share_key, record_key) VALUES ('alessio', '$RECORD_KEY')")
    db.execSQL(
      "INSERT INTO replica_pack (pack_id, file_name, kind, cls, bytes, share_key, device_id, segment_hash) VALUES " +
        "(1, 'p-cccccccc', 'segment', 2, 6000, 'alessio', 'tablet', '$SEGMENT')",
    )
    db.execSQL(
      "INSERT INTO replica_blob (content_key, cls, pack_id, content_key_short, byte_offset, byte_length, device_id, segment_hash) VALUES " +
        "('$QUICK', 2, 1, -42, 44, 5956, 'tablet', '$SEGMENT')",
    )
    db.execSQL("INSERT INTO replica_share_access (share_key, failure, first_failure_day, dropped) VALUES ('alessio', 'ACCESS_DENIED', 20000, 0)")
    db.execSQL("INSERT INTO replica_setting (`key`, value) VALUES ('quality', 'high')")
    db.execSQL("INSERT INTO replica_provisional (content_key_short, content_key, jpeg) VALUES (-42, '$QUICK', X'FFD8FF')")
    db.execSQL(
      "INSERT INTO nas_op (op_id, kind, state, plan_hash, item_count, total_bytes, error_code, created_at, updated_at) VALUES " +
        "('$OP_ID', 'move', 'running', '$SEGMENT', 1, 300000, NULL, $T0, $T0)",
    )
    db.execSQL(
      "INSERT INTO nas_op_item (op_id, item_index, state, is_folder, account_id, src_share, src_path, dst_share, dst_path, " +
        "temp_name, size, modified_at, file_id, sha256, trash_id, attempts, next_attempt_at, error_code, updated_at) VALUES " +
        "('$OP_ID', 0, 'copied', 0, 'alessio', 'alessio', 'Backup/S25/IMG_0001.jpg', 'NAS', 'Famiglia/IMG_0001.jpg', " +
        "'tablet-op-1', 300000, $T0, $FILE_ID, NULL, NULL, 1, NULL, NULL, $T0)",
    )
    db.execSQL(
      "INSERT INTO nas_trash_entry (trash_id, account_id, share_key, share_name, original_path, trashed_path, reason, is_folder, " +
        "size, file_count, trashed_at, state, op_id, updated_at) VALUES ('t-1', 'alessio', 'alessio', 'alessio', 'Vecchie/a.jpg', " +
        "'.mycloudgallery/trash/tablet/files/t-1', 'delete', 0, 1000, NULL, $T0, 'trashed', NULL, $T0)",
    )
  }

  private fun seedV1(db: SQLiteDatabase) {
    db.execSQL(
      "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, " +
        "sort_time, presence, safety) VALUES ('$MEDIA_ID', 'photo', 'camera', 'alessio', NULL, '$VERSION_ID', $T0, $T0, " +
        "$T0, 'phone_and_nas', 'safe')",
    )
    db.execSQL(
      "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, " +
        "created_at) VALUES ('$VERSION_ID', '$MEDIA_ID', 'original', NULL, 300000, '$QUICK', '$FULL', '', $T0)",
    )
    db.execSQL(
      "INSERT INTO version_metadata (version_id, mime_type, width, height, orientation, duration_ms, taken_at_utc, " +
        "taken_offset_min, taken_confidence, taken_source, latitude, longitude, altitude, updated_at) VALUES " +
        "('$VERSION_ID', 'image/jpeg', 4000, 3000, 90, NULL, $T0, 120, 'exact', 'exif_offset', 59.91, 10.75, NULL, $T0)",
    )
    db.execSQL(
      "INSERT INTO media_location (location_id, version_id, location_type, location_key, folder_key, state, volume, " +
        "relative_path, display_name, media_store_id, media_store_version, generation, account_id, share_name, share_key, " +
        "nas_path, size, modified_at, absent_since, verified_at, last_seen_at) VALUES " +
        "(1, '$VERSION_ID', 'phone', 'phone:external_primary:dcim/camera/img_0001.jpg', 'phone:external_primary:dcim/camera/', " +
        "'present', 'external_primary', 'DCIM/Camera/', 'IMG_0001.jpg', 42, 'store-v1', 7, NULL, NULL, NULL, NULL, 300000, $T0, " +
        "NULL, NULL, $T0), " +
        "(2, '$VERSION_ID', 'nas', 'nas:alessio:alessio:backup/s25/img_0001.jpg', 'nas:alessio:alessio:backup/s25', 'present', " +
        "NULL, NULL, NULL, NULL, NULL, NULL, 'alessio', 'alessio', 'alessio', 'Backup/S25/IMG_0001.jpg', 300000, $T0, NULL, $T0, $T0)",
    )
    db.execSQL("INSERT INTO nas_share (account_id, share_key, share_name, area) VALUES ('alessio', 'alessio', 'alessio', 'private')")
    db.execSQL("INSERT INTO media_personal (media_id, favorite, hidden, updated_at) VALUES ('$MEDIA_ID', 1, 0, $T0)")
    db.execSQL("INSERT INTO album (album_id, title, cover_media_id, created_at, updated_at) VALUES ('$ALBUM_ID', 'Oslo 2026', '$MEDIA_ID', $T0, $T0)")
    db.execSQL("INSERT INTO album_item (album_id, media_id, position, added_at) VALUES ('$ALBUM_ID', '$MEDIA_ID', 1, $T0)")
    db.execSQL(
      "INSERT INTO work_state (media_id, stage, status, version_id, attempts, last_error, updated_at) VALUES " +
        "('$MEDIA_ID', 'embed', 'done', '$VERSION_ID', 0, NULL, $T0), ('$MEDIA_ID', 'ocr', 'failed', '$VERSION_ID', 2, 'DECODE_FAILED', $T0)",
    )
    db.execSQL(
      "INSERT INTO embedding (media_id, space_id, dimension, precision, vector_index, vector, version_id, created_at) VALUES " +
        "('$MEDIA_ID', 'embeddinggemma2-float-v1', 2, 'f32', 0, X'0000803F00000040', '$VERSION_ID', $T0)",
    )
  }

  /**
   * La v2: le stesse righe della v1 con le colonne nuove, piu' un cursore del MediaStore e i valori del
   * catalogo. I derivati sono quelli che il catalogo calcolerebbe (giorno di Oslo, 3:4 perche' ruotata di
   * 90 gradi, preferito con luogo).
   */
  private fun seedV2(db: SQLiteDatabase) {
    db.execSQL(
      "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, " +
        "sort_time, presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash) VALUES " +
        "('$MEDIA_ID', 'photo', 'camera', 'alessio', NULL, '$VERSION_ID', $T0, $T0, $T0, 'phone_and_nas', 'safe', " +
        "20251009, 750, NULL, $OSLO, 136, X'$THUMB_HEX')",
    )
    db.execSQL(
      "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, " +
        "created_at) VALUES ('$VERSION_ID', '$MEDIA_ID', 'original', NULL, 300000, '$QUICK', '$FULL', '', $T0)",
    )
    db.execSQL(
      "INSERT INTO version_metadata (version_id, mime_type, width, height, orientation, duration_ms, taken_at_utc, " +
        "taken_offset_min, taken_confidence, taken_source, latitude, longitude, altitude, updated_at, mirrored, traits, " +
        "place_id, place_dataset, thumb_hash) VALUES ('$VERSION_ID', 'image/jpeg', 4000, 3000, 90, NULL, $T0, 120, 'exact', " +
        "'exif_offset', 59.91, 10.75, NULL, $T0, 0, 0, $OSLO, 1, X'$THUMB_HEX')",
    )
    db.execSQL(
      "INSERT INTO media_location (location_id, version_id, location_type, location_key, folder_key, state, volume, " +
        "relative_path, display_name, media_store_id, media_store_version, generation, account_id, share_name, share_key, " +
        "nas_path, size, modified_at, absent_since, verified_at, last_seen_at) VALUES " +
        "(1, '$VERSION_ID', 'phone', 'phone:external_primary:dcim/camera/img_0001.jpg', 'phone:external_primary:dcim/camera/', " +
        "'present', 'external_primary', 'DCIM/Camera/', 'IMG_0001.jpg', 42, 'store-v1', 7, NULL, NULL, NULL, NULL, 300000, $T0, " +
        "NULL, NULL, $T0), " +
        "(2, '$VERSION_ID', 'nas', 'nas:alessio:alessio:backup/s25/img_0001.jpg', 'nas:alessio:alessio:backup/s25', 'present', " +
        "NULL, NULL, NULL, NULL, NULL, NULL, 'alessio', 'alessio', 'alessio', 'Backup/S25/IMG_0001.jpg', 300000, $T0, NULL, $T0, $T0)",
    )
    db.execSQL("INSERT INTO nas_share (account_id, share_key, share_name, area) VALUES ('alessio', 'alessio', 'alessio', 'private')")
    db.execSQL(
      "INSERT INTO media_personal (media_id, favorite, hidden, updated_at, favorite_changed_at, hidden_changed_at) VALUES " +
        "('$MEDIA_ID', 1, 0, $T0, ${T0 - 5}, ${T0 - 6})",
    )
    db.execSQL("INSERT INTO album (album_id, title, cover_media_id, created_at, updated_at) VALUES ('$ALBUM_ID', 'Oslo 2026', '$MEDIA_ID', $T0, $T0)")
    db.execSQL("INSERT INTO album_item (album_id, media_id, position, added_at) VALUES ('$ALBUM_ID', '$MEDIA_ID', 1, $T0)")
    db.execSQL(
      "INSERT INTO work_state (media_id, stage, status, version_id, attempts, last_error, updated_at) VALUES " +
        "('$MEDIA_ID', 'embed', 'done', '$VERSION_ID', 0, NULL, $T0), ('$MEDIA_ID', 'ocr', 'failed', '$VERSION_ID', 2, 'DECODE_FAILED', $T0)",
    )
    db.execSQL(
      "INSERT INTO embedding (media_id, space_id, dimension, precision, vector_index, vector, version_id, created_at) VALUES " +
        "('$MEDIA_ID', 'embeddinggemma2-float-v1', 2, 'f32', 0, X'0000803F00000040', '$VERSION_ID', $T0)",
    )
    db.execSQL(
      "INSERT INTO media_store_cursor (volume, ms_version, generation, deleted_generation, scan_epoch, scan_resume_after_id, " +
        "last_full_scan_at, last_reconcile_at) VALUES ('external_primary', 'store-v1', 7, 6, $T0, 41, NULL, ${T0 - 1})",
    )
    db.execSQL("INSERT INTO catalog_meta (`key`, value) VALUES ('change_seq', $CHANGE_SEQ_V2), ('places_dataset', 1)")
  }

  /** Cio' che deve essere vero dopo l'apertura (e le migrazioni) di un database con i seed della versione [fromVersion]. */
  suspend fun verify(database: GalleryDatabase, fromVersion: Int) {
    val catalog = MediaCatalog(database, AccountId.of("alessio")) { T0 }
    val id = MediaId(MEDIA_ID)

    val media = checkNotNull(catalog.media(id)) { "Il media del seed non c'e' piu'." }
    assertThat(media.currentVersion.kind).isEqualTo(VersionKind.ORIGINAL)
    assertThat(media.currentVersion.fingerprint.fullHash?.hex).isEqualTo(FULL)
    assertThat(media.locations).hasSize(2)
    assertThat(media.locations.all { it.state == LocationState.PRESENT }).isTrue()
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.SAFE)

    val metadata = database.versionDao().metadata(VERSION_ID)!!.toDomain()
    assertThat(metadata.captureTime!!.source).isEqualTo(CaptureTimeSource.EXIF_WITH_OFFSET)
    assertThat(metadata.location!!.latitude).isEqualTo(59.91)

    assertThat(database.personalDao().get(MEDIA_ID)!!.favorite).isTrue()
    assertThat(database.albumDao().items(AlbumId(ALBUM_ID).value).map { it.mediaId }).containsExactly(MEDIA_ID)
    val states = catalog.workStates(id).associateBy { it.stage }
    assertThat(states.getValue(WorkStage.EMBED).status).isEqualTo(WorkStatus.DONE)
    assertThat(states.getValue(WorkStage.OCR).lastError!!.value).isEqualTo("DECODE_FAILED")
    val vector = database.embeddingDao().forMedia(MEDIA_ID).single().toDomain()
    assertThat(vector.toFloats().toList()).containsExactly(1f, 2f).inOrder()

    // --- v2 ---
    val row = database.mediaDao().get(MEDIA_ID)!!
    // Scattata alle 10:53 di Oslo (UTC+2) del 9 ottobre 2025; 4000x3000 ruotata di 90 gradi = 3:4.
    assertThat(row.localDay).isEqualTo(20251009)
    assertThat(row.aspectMilli).isEqualTo(750)
    assertThat(row.durationMs).isNull()
    val personal = database.personalDao().get(MEDIA_ID)!!
    if (fromVersion == 1) {
      // La v1 non aveva luogo ne' segnaposto; gli istanti dei flag partono da updated_at. I bit di presenza li calcola la
      // migrazione 2 -> 3 (sul telefono, sul NAS, al sicuro).
      assertThat(row.cellFlags).isEqualTo(CellFlags.FAVORITE or PRESENCE_V3)
      assertThat(row.placeId).isNull()
      assertThat(personal.favoriteChangedAt).isEqualTo(T0)
      assertThat(personal.hiddenChangedAt).isEqualTo(T0)
      assertThat(catalog.changeSeq()).isEqualTo(0)
      assertThat(catalog.mediaStoreCursors()).isEmpty()
      assertThat(catalog.placesDataset()).isNull()
    } else {
      assertThat(row.cellFlags).isEqualTo(CellFlags.FAVORITE or CellFlags.HAS_PLACE or PRESENCE_V3)
      assertThat(row.placeId).isEqualTo(OSLO)
      assertThat(metadata.place).isEqualTo(PlaceRef(OSLO, 1))
      assertThat(metadata.thumbHash!!.bytes().size).isEqualTo(25)
      assertThat(row.thumbHash!!.toList()).isEqualTo(metadata.thumbHash!!.bytes().toList())
      assertThat(personal.favoriteChangedAt).isEqualTo(T0 - 5)
      assertThat(personal.hiddenChangedAt).isEqualTo(T0 - 6)
      assertThat(catalog.changeSeq()).isEqualTo(CHANGE_SEQ_V2)
      assertThat(catalog.mediaStoreCursor("external_primary"))
        .isEqualTo(MediaStoreCursor("external_primary", "store-v1", 7, 6, T0, 41, null, T0 - 1))
      assertThat(catalog.placesDataset()).isEqualTo(1)
    }
    assertThat(catalog.indexRows(listOf(id)).map { it.mediaId }).containsExactly(MEDIA_ID)

    // --- v3 ---
    val nasCopy = media.locations.single { it.isNas }.location as Location.Nas
    val account = AccountId.of("alessio")
    assertThat(catalog.nasRefs(listOf(id)).getValue(id).locationId).isEqualTo(2L)
    if (fromVersion < 3) {
      // Le tabelle nuove nascono vuote; il FileId non era noto.
      assertThat(nasCopy.fileId).isNull()
      assertThat(catalog.nasScanStates()).isEmpty()
      assertThat(database.indexPublishDao().publishState("alessio")).isNull()
      assertThat(database.replicaDao().heads("alessio")).isEmpty()
      assertThat(database.nasOpsDao().opsInState(listOf("running"))).isEmpty()
      assertThat(catalog.workStates(id).map { it.stage }).doesNotContain(WorkStage.FULL_HASH)
    } else {
      assertThat(nasCopy.fileId).isEqualTo(FILE_ID)
      assertThat(catalog.knownNasFiles(account, "alessio", NasPath.of("Backup/S25")).values.single().stamp)
        .isEqualTo(NasStamp(300_000, T0, FILE_ID))
      val scan = checkNotNull(catalog.nasScanState(account, "ALESSIO"))
      assertThat(scan.resumeAfter).isEqualTo(NasPath.of("Backup/S25"))
      assertThat(scan.freeze).isEqualTo(NasFreeze(FreezeReason.VANISHED, T0 - 5, 1000, 60, 0, 0))
      val publish = database.indexPublishDao()
      assertThat(publish.publishState("alessio")!!.headVersion).isEqualTo(3)
      assertThat(publish.publishState("alessio")!!.nextSeq).isEqualTo(12)
      assertThat(publish.outboxRecords("alessio").single().seq).isEqualTo(11)
      assertThat(publish.outboxBlobs("alessio").single().length).isEqualTo(6000)
      assertThat(publish.listedSegments("alessio").single().entries).isEqualTo(1)
      assertThat(publish.ownRecords("alessio", listOf(RECORD_KEY)).single().seq).isEqualTo(10)
      val replica = database.replicaDao()
      assertThat(replica.heads("alessio").single().version).isEqualTo(4)
      assertThat(replica.records("alessio", listOf(RECORD_KEY)).single().prep).isEqualTo(6)
      assertThat(replica.blobsOfPack(1).single().offset).isEqualTo(44)
      assertThat(replica.segments("alessio", "tablet").single().packId).isEqualTo(1)
      assertThat(replica.dirty("alessio", 10)).containsExactly(RECORD_KEY)
      assertThat(replica.shareAccess("alessio")!!.firstFailureDay).isEqualTo(20000)
      assertThat(replica.setting("quality")).isEqualTo("high")
      // Lettura bloccante (il dispatcher delle miniature): mai sul thread principale.
      assertThat(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { replica.provisional(-42) }!!.size).isEqualTo(3)
      val ops = database.nasOpsDao()
      assertThat(ops.items(OP_ID).single().state).isEqualTo("copied")
      assertThat(ops.expiredTrash(T0 + 1, 10).single().trashId).isEqualTo("t-1")
      assertThat(catalog.workStates(id).first { it.stage == WorkStage.FULL_HASH }.status).isEqualTo(WorkStatus.DONE)
    }

    // --- v4 (Fase 5) ---
    // Il media del seed e' al sicuro e proprio: non e' un candidato del backup, in nessuna versione di partenza.
    assertThat(row.backupTodo).isFalse()
    if (fromVersion < 4) {
      // Le tabelle nuove nascono vuote.
      assertThat(database.backupDiaryDao().countsByState()).isEmpty()
      assertThat(database.backupProblemDao().count()).isEqualTo(0)
      assertThat(database.nasCopyMismatchDao().count()).isEqualTo(0)
      assertThat(database.visualSignatureDao().count()).isEqualTo(0)
      assertThat(database.pendingTaskDao().inState("open")).isEmpty()
      assertThat(database.editSessionDao().inState("open")).isEmpty()
      assertThat(database.freeSpaceDao().runsInState(listOf("ready"))).isEmpty()
      assertThat(database.incomingDao().inState("queued")).isEmpty()
      assertThat(catalog.mediaSafety(id)).isEqualTo(CatalogMediaSafety(SafetyStatus.SAFE, T0, null))
    } else {
      val item = checkNotNull(database.backupDiaryDao().get(BACKUP_ITEM_ID))
      assertThat(item.state).isEqualTo("uploading")
      assertThat(item.isOpen).isTrue()
      assertThat(item.finalPath).isEqualTo("Backup/S25/2025/10/IMG_0001 (2).jpg")
      assertThat(item.nameAttempt).isEqualTo(1)
      assertThat(item.resumeAt).isEqualTo(134_217_728L)
      assertThat(item.resumeFileId).isEqualTo(RESUME_FILE_ID)
      assertThat(item.tempFileId).isEqualTo(FILE_ID)
      assertThat(item.guardPath).isEqualTo("Famiglia/IMG_0001.jpg")
      assertThat(database.backupDiaryDao().itemsToResume(10).map { it.itemId }).containsExactly(BACKUP_ITEM_ID)
      assertThat(database.backupDiaryDao().citedTemps()).containsExactly(".mycloudgallery/tmp/tablet-bk-1")
      assertThat(database.backupProblemDao().get(MEDIA_ID)!!.code).isEqualTo("verify_failed")
      assertThat(database.nasCopyMismatchDao().get(2)!!.foundAt).isEqualTo(T0 + 5)
      assertThat(database.visualSignatureDao().get(VERSION_ID)!!.data.toList()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).toList())
      assertThat(catalog.signatureOf(dev.pampa.mycloudgallery.core.model.VersionId(VERSION_ID))!!.algorithm).isEqualTo(1)
      assertThat(database.pendingTaskDao().inState("open").single().bytes).isEqualTo(2_500_000_000L)
      assertThat(database.editSessionDao().inState("open").single().startGeneration).isEqualTo(7)
      assertThat(database.freeSpaceDao().items(1).single().verifiedFileId).isEqualTo(FILE_ID)
      assertThat(database.freeSpaceDao().forPhoneLocation(1).map { it.runId }).containsExactly(1L)
      assertThat(database.incomingDao().get(1)!!.backupItemId).isEqualTo(BACKUP_ITEM_ID)
      // Un problema che marca la cella su un media al sicuro non vale: lo toglie il primo ricalcolo (sotto).
      assertThat(CellFlags.has(row.cellFlags, CellFlags.BACKUP_PROBLEM)).isFalse()
    }

    // I derivati scritti dalla migrazione (o dal seed) sono quelli che il catalogo calcola: ricalcolarli
    // (registrare di nuovo la share tocca il media) non cambia nulla e non annuncia nulla.
    val seq = catalog.changeSeq()
    catalog.registerShare(NasShare(AccountId.of("alessio"), "alessio", NasArea.PRIVATE))
    assertThat(database.mediaDao().get(MEDIA_ID)).isEqualTo(row)
    assertThat(catalog.changeSeq()).isEqualTo(seq)
  }
}
