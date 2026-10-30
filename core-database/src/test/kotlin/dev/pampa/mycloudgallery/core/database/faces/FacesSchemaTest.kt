package dev.pampa.mycloudgallery.core.database.faces

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.SchemaFiles
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesMetaEntity
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Lo schema del database dei volti, v1 e v2 (Fase 8, P6, e le correzioni del pacchetto "formato e replica dei volti"; `docs/fase8-progetto.md` D15, riga P6 del §4.4; `docs/modello-dati.md` §15).
 *
 * - Lo schema **esportato** (`schemas/<FacesDatabase>/1.json`, sotto controllo di versione) e' quello che Room crea oggi, e un database creato
 *   **dal JSON** (come lo avrebbe creato quella versione dell'app) con dati si apre con il codice di oggi.
 * - **Nessuna tabella del database della galleria e' toccata**: `GalleryDatabase` resta v6, il suo schema esportato `6.json` ha l'impronta di
 *   quando e' stato scritto, non ne esiste una v7, e i due database non hanno nemmeno un nome di tabella in comune.
 * - Le chiavi esterne sono accese e in cascata (togliere un corpo toglie i suoi volti, togliere una persona toglie esemplari e assegnazioni),
 *   e nessuna colonna ha un nome che dica un valore sensibile in chiaro (vettori e nomi sono colonne sigillate).
 * - Il giornale e' WAL: accanto al file ci sono `-wal` e `-shm`, e `FacesDatabaseFiles` li conosce tutti.
 */
@RunWith(AndroidJUnit4::class)
class FacesSchemaTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val schemaDir = File("schemas/${FacesDatabase::class.java.name}")
  private val gallerySchemaDir = File("schemas/${GalleryDatabase::class.java.name}")

  private fun schemaOf(cursor: Cursor): List<String> = cursor.use {
    buildList {
      while (it.moveToNext()) {
        add(listOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3)?.replace(Regex("\\s+"), " ")).joinToString(" | "))
      }
    }
  }

  private fun newDatabase(name: String = "faces-prova.db"): FacesDatabase = FacesDatabase.build(context, File(folder.root, name)).also { it.openNow() }

  private fun exportedVersions(dir: File): List<Int> =
    dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.nameWithoutExtension.toInt() }.sorted()

  private fun tablesOf(json: File): Set<String> {
    val entities = JSONObject(json.readText()).getJSONObject("database").getJSONArray("entities")
    return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
  }

  @Test
  fun gliSchemiV1EV2SonoEsportatiENonCeNientAltro() {
    assertThat(FacesDatabase.VERSION).isEqualTo(2)
    assertThat(exportedVersions(schemaDir)).containsExactly(1, 2).inOrder()
    for (version in 1..2) {
      val schema = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
      assertThat(schema.getInt("version")).isEqualTo(version)
    }
    // La v2 aggiunge colonne e un indice, nessuna tabella.
    assertThat(tablesOf(File(schemaDir, "2.json"))).isEqualTo(tablesOf(File(schemaDir, "1.json")))
    assertThat(tablesOf(File(schemaDir, "1.json"))).containsExactly(
      "face_body", "face", "face_unsupported", "group_state", "group_person", "group_exemplar", "group_assignment", "people_record",
      "faces_head_mark", "faces_outbox", "faces_unit_done", "faces_meta",
      // La sincronizzazione (P3 e P6): lo stato della replica e quello del pubblicatore, e i blocchi sigillati delle voci `cluster`.
      "replica_device", "replica_segment", "replica_segment_state", "replica_revoked", "faces_chunk", "publish_state", "publish_key",
      "publish_segment", "publish_diary",
    )
  }

  @Test
  fun loSchemaEsportatoCoincideConQuelloCheRoomCreaOggi() {
    val fromJson = SchemaFiles.create(context, schemaDir, FacesDatabase.VERSION, "faces-da-json.db")
    val expected = SQLiteDatabase.openDatabase(fromJson.path, null, SQLiteDatabase.OPEN_READONLY).use { schemaOf(it.rawQuery(SCHEMA_SQL, null)) }
    val database = newDatabase("faces-da-room.db")
    try {
      val actual = schemaOf(database.openHelper.readableDatabase.query(SCHEMA_SQL))
      assertThat(actual).isNotEmpty()
      assertThat(actual).isEqualTo(expected)
    } finally {
      database.close()
    }
  }

  @Test
  fun unaV1CreataDalJsonConUnaRigaPerTabellaSiMigraSiRiapreESiLegge() = runBlocking<Unit> {
    val file = SchemaFiles.create(context, schemaDir, 1, "faces-v1-con-dati.db") { db ->
      val k = "a".repeat(64)
      val p = "11111111-1111-4111-8111-111111111111"
      db.execSQL("INSERT INTO face_body VALUES ('$k', 'dev-a', 1, 'sface_2021dec_v1', 'yunet_2023mar', 1, 'analysis', 7, 1, x'0102')")
      db.execSQL("INSERT INTO face VALUES ('$k', 'dev-a', 0, 0, 1, 2, 3, 4, 200, 'core', 0, 120, 0, 100)")
      db.execSQL("INSERT INTO face_unsupported VALUES ('$k', 2)")
      db.execSQL("INSERT INTO group_state VALUES (1, 5, 3, 10)")
      db.execSQL("INSERT INTO group_person VALUES ('$p', 'active', NULL, 1, 5)")
      db.execSQL("INSERT INTO group_exemplar VALUES ('$p', 0, '$k', 0, 0, 7)")
      db.execSQL("INSERT INTO group_assignment VALUES ('$k', 0, 0, 7, NULL, NULL, 1, 2, 3, 4, '$p', 0.5, 'auto')")
      db.execSQL("INSERT INTO people_record VALUES ('$p', 'hidden', 'true', NULL, 1, 'dev-a')")
      db.execSQL("INSERT INTO faces_head_mark VALUES ('dev-a', 3, 'h', 2, 9)")
      db.execSQL("INSERT INTO faces_outbox (kind, ref) VALUES ('faces', '$k')")
      db.execSQL("INSERT INTO faces_unit_done VALUES ('priv', '2025-03', 0, 1, NULL)")
      db.execSQL("INSERT INTO faces_meta VALUES ('key_check', x'00')")
      db.execSQL("INSERT INTO replica_device VALUES ('dev-a', 3, 'h3', 2, 1, 3, 'h3', 2, 1, 3, 'h3')")
      db.execSQL("INSERT INTO replica_segment VALUES ('dev-a', 0, 19, '${"c".repeat(64)}', 1234)")
      db.execSQL("INSERT INTO replica_segment_state VALUES ('dev-a', '${"c".repeat(64)}', 1, 77)")
      db.execSQL("INSERT INTO replica_revoked VALUES ('dev-x')")
      db.execSQL("INSERT INTO faces_chunk VALUES ('replica.cluster', 'dev-a', '${"c".repeat(64)}', 0, '${"d".repeat(64)}', 99, x'0a0b')")
      db.execSQL("INSERT INTO publish_state VALUES (1, 4, 3, 5, 5, 'h5', 1000, 4, 3, 10, 2, 1, 0)")
      db.execSQL("INSERT INTO publish_key VALUES ('$k')")
      db.execSQL("INSERT INTO publish_segment VALUES ('${"e".repeat(64)}')")
      db.execSQL("INSERT INTO publish_diary VALUES ('${"e".repeat(64)}', 7)")
    }
    val database = FacesDatabase.build(context, file)
    try {
      database.openNow()
      assertThat(database.bodyDao().bodyCount()).isEqualTo(1)
      assertThat(database.bodyDao().faceCount()).isEqualTo(1)
      assertThat(database.bodyDao().unsupportedRecipe("a".repeat(64))).isEqualTo(2)
      assertThat(database.groupingDao().state()!!.seq).isEqualTo(5)
      assertThat(database.groupingDao().persons()).hasSize(1)
      assertThat(database.groupingDao().exemplars()).hasSize(1)
      assertThat(database.groupingDao().assignments()).hasSize(1)
      assertThat(database.peopleDao().all()).hasSize(1)
      // v1 -> v2: la provenienza delle voci di prima e' il loro autore, i segmenti letti non hanno attese, l'indice per autore c'e'.
      assertThat(database.peopleDao().all().single().via).isEqualTo("dev-a")
      assertThat(database.peopleDao().countVia("dev-a")).isEqualTo(1)
      assertThat(database.peopleDao().direct()).hasSize(1)
      assertThat(database.syncDao().replicaStates().single().attempts).isEqualTo(0)
      assertThat(database.syncDao().replicaStates().single().retryAt).isNull()
      assertThat(database.bodyDao().totalsOf("dev-a").bodies).isEqualTo(1)
      database.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_face_body_author'").use {
        assertThat(it.count).isEqualTo(1)
      }
      assertThat(database.headMarkDao().get("dev-a")!!.version).isEqualTo(3)
      assertThat(database.outboxDao().count()).isEqualTo(1)
      assertThat(database.unitDao().get("priv", "2025-03", 0)!!.recipe).isEqualTo(1)
      assertThat(database.metaDao().get("key_check")).isNotNull()
      val sync = database.syncDao()
      assertThat(sync.replicaDevices().single().markVersion).isEqualTo(3)
      assertThat(sync.replicaSegments().single().cls).isEqualTo(19)
      assertThat(sync.replicaStates().single().entries).isEqualTo(77)
      assertThat(sync.replicaRevoked()).containsExactly("dev-x")
      assertThat(sync.chunks("replica.cluster").single().data).isEqualTo(byteArrayOf(10, 11))
      assertThat(sync.publishState()!!.pending).isEqualTo(2)
      assertThat(sync.publishKeys()).hasSize(1)
      assertThat(sync.publishSegments()).hasSize(1)
      assertThat(sync.publishDiary().single().ord).isEqualTo(7)
      database.openHelper.writableDatabase.query("PRAGMA integrity_check").use { assertThat(it.moveToFirst() && it.getString(0) == "ok").isTrue() }
    } finally {
      database.close()
    }
  }

  @Test
  fun unDatabaseDiUnaVersioneFuturaNonSiApreENonVieneMaiCancellato() {
    val file = SchemaFiles.create(context, schemaDir, 1, "faces-dal-futuro.db") { db ->
      db.execSQL("INSERT INTO face_unsupported VALUES ('${"b".repeat(64)}', 4)")
    }
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = FacesDatabase.VERSION + 1 }
    assertThat(FacesDatabaseFiles.versionOf(file)).isEqualTo(FacesDatabase.VERSION + 1)
    val database = FacesDatabase.build(context, file)
    val failure = runCatching { database.openNow() }
    database.close()
    assertThat(failure.isFailure).isTrue()
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db.rawQuery("SELECT COUNT(*) FROM face_unsupported", null).use { assertThat(it.moveToFirst() && it.getInt(0) == 1).isTrue() }
    }
    assertThat(FacesDatabaseFiles.versionOf(File(folder.root, "non-esiste.db"))).isNull()
    File(folder.root, "rotto.db").also { it.writeBytes(ByteArray(300) { i -> (i * 7).toByte() }) }.let { assertThat(FacesDatabaseFiles.versionOf(it)).isNull() }
  }

  // --- la galleria non e' toccata ----------------------------------------------------------------------------------------------

  @Test
  fun laV6DellaGalleriaRestaInvariataELaV7AggiungeSoloLeColonneDellaCronologia() {
    // Correzioni D (R2): la v7 e' la prima modifica della galleria dopo la Fase 7, e aggiunge una colonna sola (`search_history.persons`).
    assertThat(GalleryDatabase.VERSION).isEqualTo(7)
    assertThat(exportedVersions(gallerySchemaDir)).containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
    // L'impronta dello schema v6 com'era prima della Fase 8 (a capo normalizzati): un file di schema esportato non si modifica mai.
    val text = File(gallerySchemaDir, "6.json").readText().replace("\r\n", "\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    assertThat(digest).isEqualTo(GALLERY_V6_SHA256)
    // Le tabelle della v7 sono quelle della v6: la Fase 8 non ne ha messa nessuna nella galleria (i volti stanno nel loro database).
    assertThat(tablesOf(File(gallerySchemaDir, "7.json"))).isEqualTo(tablesOf(File(gallerySchemaDir, "6.json")))
    // Nessuna tabella in comune: il database dei volti e' un altro file con altre tabelle.
    val gallery = tablesOf(File(gallerySchemaDir, "7.json"))
    val faces = tablesOf(File(schemaDir, "1.json"))
    assertThat(gallery.intersect(faces)).isEmpty()
    assertThat(gallery.filter { it.startsWith("face") || it.startsWith("group_") || it.startsWith("people") }).isEmpty()
  }

  @Test
  fun ilDatabaseDeiVoltiEUnFileAParteConIlSuoGiornaleWalEITreCompagniSiConoscono() {
    val directory = File(folder.root, "volti")
    val g = FaceGeneration.fromBytes(ByteArray(16) { 4 })
    val main = FacesDatabaseFiles.fileOf(directory, g)
    assertThat(main.name).isEqualTo("faces-${g.base32}.db")
    assertThat(main.name).isNotEqualTo(GalleryDatabase.NAME)
    assertThat(FacesDatabaseFiles.allOf(main).map { it.name.removePrefix(main.name) }).containsExactly("", "-wal", "-shm", "-journal").inOrder()
    val database = FacesDatabase.build(context, main)
    try {
      database.openNow()
      database.openHelper.writableDatabase.execSQL("INSERT INTO faces_meta VALUES ('x', x'01')")
      // Il giornale e' WAL: finche' e' aperto ci sono il -wal e il -shm.
      database.openHelper.writableDatabase.query("PRAGMA journal_mode").use { it.moveToFirst(); assertThat(it.getString(0)).isEqualTo("wal") }
      assertThat(File(main.path + "-wal").exists()).isTrue()
      assertThat(File(main.path + "-shm").exists()).isTrue()
      assertThat(FacesDatabaseFiles.generationsIn(directory)).containsExactly(g)
      assertThat(FacesDatabaseFiles.generationOf("faces-${g.base32}.db-wal")).isEqualTo(g)
      assertThat(FacesDatabaseFiles.generationOf("faces-${g.base32}.db-shm")).isEqualTo(g)
      assertThat(FacesDatabaseFiles.generationOf("faces-nonvalida.db")).isNull()
      assertThat(FacesDatabaseFiles.generationOf(GalleryDatabase.NAME)).isNull()
    } finally {
      database.close()
    }
  }

  // --- chiavi esterne e cascate ------------------------------------------------------------------------------------------------

  @Test
  fun leChiaviEsterneSonoAcceseEInCascata() {
    val database = newDatabase()
    try {
      val sql = database.openHelper.writableDatabase
      sql.query("PRAGMA foreign_keys").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1) }
      val k = "c".repeat(64)
      val p = "22222222-2222-4222-8222-222222222222"
      sql.execSQL("INSERT INTO face_body VALUES ('$k', 'dev-a', 1, 'sface_2021dec_v1', 'yunet_2023mar', 1, 'analysis', 1, 2, x'00')")
      sql.execSQL("INSERT INTO face VALUES ('$k', 'dev-a', 0, 0, 1, 2, 3, 4, 200, 'core', 0, 120, 0, 100)")
      sql.execSQL("INSERT INTO face VALUES ('$k', 'dev-a', 1, 0, 1, 2, 3, 4, 200, 'none', 1, 20, 0, 100)")
      sql.execSQL("INSERT INTO group_person VALUES ('$p', 'active', NULL, 1, 1)")
      sql.execSQL("INSERT INTO group_exemplar VALUES ('$p', 0, '$k', 0, 0, 1)")
      sql.execSQL("INSERT INTO group_assignment VALUES ('$k', 0, 0, 1, NULL, NULL, 1, 2, 3, 4, '$p', 0.5, 'auto')")
      // Un volto di un corpo che non c'e', un'assegnazione a una persona che non c'e': rifiutati.
      assertThat(runCatching { sql.execSQL("INSERT INTO face VALUES ('$k', 'dev-b', 0, 0, 1, 2, 3, 4, 200, 'core', 0, 120, 0, 100)") }.isFailure).isTrue()
      assertThat(
        runCatching {
          sql.execSQL("INSERT INTO group_assignment VALUES ('$k', 0, 1, 1, NULL, NULL, 1, 2, 3, 4, '33333333-3333-4333-8333-333333333333', 0.5, 'auto')")
        }.isFailure,
      ).isTrue()
      sql.execSQL("DELETE FROM face_body WHERE content_key = '$k'")
      sql.execSQL("DELETE FROM group_person WHERE person_id = '$p'")
      for (table in listOf("face", "group_exemplar", "group_assignment")) {
        sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertWithMessage(table).that(it.getInt(0)).isEqualTo(0) }
      }
      sql.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
    } finally {
      database.close()
    }
  }

  @Test
  fun nessunaColonnaDiceUnVettoreOUnNomeInChiaro() {
    val database = newDatabase()
    try {
      val sql = database.openHelper.readableDatabase
      val columns = mutableMapOf<String, String>()
      sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table')").use { tables ->
        while (tables.moveToNext()) {
          val table = tables.getString(0)
          sql.query("PRAGMA table_info(`$table`)").use { info ->
            while (info.moveToNext()) columns["$table.${info.getString(info.getColumnIndexOrThrow("name"))}"] = info.getString(info.getColumnIndexOrThrow("type"))
          }
        }
      }
      // Le uniche colonne che portano dati sensibili sono BLOB sigillati, con questi nomi e nessun altro.
      assertThat(columns.filterValues { it == "BLOB" }.keys).containsExactly("face_body.secret", "people_record.sealed_value", "faces_meta.data", "faces_chunk.data")
      assertThat(columns.keys.filter { Regex("vector|embedding|centroid|name$|landmark", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        .containsExactly("faces_meta.name", "faces_chunk.name") // il nome di un valore di servizio o di un insieme di voci, non di una persona
    } finally {
      database.close()
    }
  }

  @Test
  fun ilValoreDiServizioSiScriveEsiLegge() = runBlocking<Unit> {
    val database = newDatabase()
    try {
      assertThat(database.metaDao().get("key_check")).isNull()
      database.metaDao().put(FacesMetaEntity("key_check", byteArrayOf(1, 2, 3)))
      database.metaDao().put(FacesMetaEntity("key_check", byteArrayOf(4)))
      assertThat(database.metaDao().get("key_check")).isEqualTo(byteArrayOf(4))
    } finally {
      database.close()
    }
  }

  private companion object {
    const val SCHEMA_SQL =
      "SELECT type, name, tbl_name, sql FROM sqlite_master " +
        "WHERE name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table') ORDER BY type, name"

    /** SHA-256 di `schemas/<GalleryDatabase>/6.json` (a capo normalizzati) al commit `c2ecc9e`, prima della Fase 8 P6. */
    const val GALLERY_V6_SHA256 = "4917ad7abdcdd1d198d8d8f9398e900ed22232df7fe3b8a2b4eb0cd5d7185d91"
  }
}
