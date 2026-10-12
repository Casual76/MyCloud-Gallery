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
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
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
 * Oggi c'e' solo la v1: il test dimostra che lo schema esportato coincide con le entita' e che un
 * database v1 con dati si apre senza perdere nulla. Dalla v2 lo stesso test copre 1 -> 2 da solo.
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
        SchemaSeeds.verify(database)
      } finally {
        database.close()
      }
    }
  }

  /** Un database di una versione futura (app tornata indietro) non si apre e non viene cancellato. */
  @Test
  fun unDatabaseDiUnaVersioneFuturaNonVieneMaiCancellato() {
    val name = "dal-futuro.db"
    val file = createFromSchema(GalleryDatabase.VERSION, name) { SchemaSeeds.seed(1, it) }
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = GalleryDatabase.VERSION + 1 }

    val database = GalleryDatabase.build(context, name)
    val failure = runCatching { database.openHelper.writableDatabase }
    database.close()

    assertThat(failure.isFailure).isTrue()
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db.rawQuery("SELECT COUNT(*) FROM media", null).use { assertThat(it.moveToFirst() && it.getInt(0) == 1).isTrue() }
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

  fun seed(version: Int, db: SQLiteDatabase) = when (version) {
    1 -> seedV1(db)
    else -> error("Manca il seed della versione $version dello schema (vedi SchemaSeeds).")
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

  /** Cio' che deve essere vero dopo l'apertura (e le migrazioni) di un database con i seed. */
  suspend fun verify(database: GalleryDatabase) {
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
  }
}
