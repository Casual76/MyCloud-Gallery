package dev.pampa.mycloudgallery.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Filo d'inciampo per le fasi future: ogni colonna dello schema che punta a `media` deve essere
 * gestita dall'unione di due media ([MediaCatalog.MEDIA_REFERENCES] e `MergeDao`). Le chiavi esterne
 * in cascata cancellerebbero in silenzio i riferimenti del media perdente: una tabella nuova (es. i
 * volti della Fase 8) che punta a `media` senza essere aggiunta all'unione farebbe fallire questo test.
 */
@RunWith(AndroidJUnit4::class)
class MergeReferencesTest {

  @Test
  fun ogniRiferimentoAMediaEGestitoDallUnione() {
    val database = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
    try {
      val sql = database.openHelper.readableDatabase
      val tables = sql.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
          "AND name NOT IN ('android_metadata', 'room_master_table')",
      ).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }
        // Le tabelle d'appoggio che SQLite crea per l'FTS4 (`ai_text_fts_content`, `_segments`, `_segdir`, `_docsize`, `_stat`) non sono
        // tabelle dello schema: le classifica la tabella virtuale `ai_text_fts` (v5).
        .filterNot { it.startsWith("ai_text_fts_") }

      val references = tables.flatMap { table ->
        sql.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
          buildList {
            while (cursor.moveToNext()) {
              if (cursor.getString(cursor.getColumnIndexOrThrow("table")) == "media") {
                add("$table." + cursor.getString(cursor.getColumnIndexOrThrow("from")))
              }
            }
          }
        }
      }

      assertWithMessage("Una tabella punta a `media`: aggiungila a MediaCatalog.MEDIA_REFERENCES e all'unione (MergeDao).")
        .that(references)
        .containsExactlyElementsIn(MediaCatalog.MEDIA_REFERENCES)

      // Ogni tabella e' classificata: punta a `media` (sopra), segue le versioni (`media_version`, che
      // l'unione sposta o traduce) oppure dichiara di non contenere identita' di media. Le tabelle della v2
      // (`media_store_cursor`, `catalog_meta`) stanno nell'ultimo gruppo: una tabella nuova non classificata
      // fa fallire il test.
      val parents = tables.associateWith { table ->
        sql.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
          buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("table"))) }
        }
      }
      val unclassified = tables.filter { table ->
        table != "media" && "media" !in parents.getValue(table) && "media_version" !in parents.getValue(table) &&
          table !in MediaCatalog.TABLES_WITHOUT_MEDIA_IDS && table !in MediaCatalog.TABLES_WITH_INDEX_IDS
      }
      assertWithMessage(
        "Tabelle non classificate per l'unione: MediaCatalog.TABLES_WITHOUT_MEDIA_IDS, TABLES_WITH_INDEX_IDS o un riferimento a media.",
      ).that(unclassified).isEmpty()
      assertThat(MediaCatalog.TABLES_WITHOUT_MEDIA_IDS).containsAtLeast("media_store_cursor", "catalog_meta", "nas_scan_state", "nas_op_item")
      // Schema v4 (Fase 5A): il diario, il problema, le firme e le tabelle della 5B che riguardano un media o una versione sono
      // riferimenti che l'unione e la rinomina spostano (`MergeV4Test` prova che non perdono righe); le altre non hanno identita'.
      assertThat(MediaCatalog.MEDIA_REFERENCES).containsAtLeast(
        "backup_item.media_id", "backup_problem.media_id", "pending_task.media_id", "edit_session.media_id", "free_space_item.media_id",
      )
      assertThat(MediaCatalog.VERSION_REFERENCES).containsAtLeast(
        "backup_item.version_id", "visual_signature.version_id", "pending_task.version_id", "edit_session.version_id",
        "free_space_item.version_id",
      )
      assertThat(MediaCatalog.TABLES_WITHOUT_MEDIA_IDS).containsAtLeast("free_space_run", "incoming_item", "nas_copy_mismatch")
      // Schema v5 (Fase 6, D37): nessuna tabella nuova punta a `media` (tutte per chiave di contenuto): stanno fra quelle senza id di
      // media, e nessuna colonna `ai_*`/`search_*` e' fra i riferimenti da unire o rinominare.
      val v5 = setOf(
        "ai_vector", "ai_text", "ai_text_fts", "ai_vocabulary", "search_history", "ai_pending_publish", "ai_own_head", "ai_own_segment",
        "ai_own_live", "ai_replica_head", "ai_replica_segment", "ai_replica_entry", "ai_replica_applied", "ai_replica_dirty",
      )
      assertThat(MediaCatalog.TABLES_WITHOUT_MEDIA_IDS).containsAtLeastElementsIn(v5)
      assertThat(tables).containsAtLeastElementsIn(v5)
      assertThat((MediaCatalog.MEDIA_REFERENCES + MediaCatalog.VERSION_REFERENCES).filter { it.substringBefore('.') in v5 }).isEmpty()
      assertThat(tables).containsAtLeastElementsIn(MediaCatalog.TABLES_WITHOUT_MEDIA_IDS)
      assertThat(tables).containsAtLeastElementsIn(MediaCatalog.TABLES_WITH_INDEX_IDS)
      assertThat(MediaCatalog.TABLES_WITHOUT_MEDIA_IDS.intersect(MediaCatalog.TABLES_WITH_INDEX_IDS)).isEmpty()
      // Le tabelle della v3 non puntano ne' a `media` ne' a `media_version`: le identita' dell'indice sono dati dei record.
      (MediaCatalog.TABLES_WITH_INDEX_IDS + MediaCatalog.TABLES_WITHOUT_MEDIA_IDS).forEach { table ->
        assertWithMessage(table).that(parents.getValue(table).intersect(setOf("media", "media_version"))).isEmpty()
      }

      // Una colonna che si chiama come un'identita' di media senza chiave esterna sfuggirebbe all'unione. Le uniche
      // ammesse sono le identita' **remote** dell'indice (`remote_*`) nelle tabelle di TABLES_WITH_INDEX_IDS.
      val columns = tables.associateWith { table ->
        sql.query("PRAGMA table_info(`$table`)").use { cursor ->
          buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
      }
      val unmanagedIds = columns.flatMap { (table, names) -> names.filter { it.endsWith("media_id") }.map { "$table.$it" } }
        .filter { it != "media.media_id" && it !in MediaCatalog.MEDIA_REFERENCES }
        .filterNot { ref -> ref.substringBefore('.') in MediaCatalog.TABLES_WITH_INDEX_IDS && ref.substringAfter('.').startsWith("remote_") }
      assertWithMessage("Colonne con identita' di media non gestite dall'unione").that(unmanagedIds).isEmpty()
      val remoteOutside = columns.flatMap { (table, names) -> names.filter { it.startsWith("remote_") }.map { "$table.$it" } }
        .filter { it.substringBefore('.') !in MediaCatalog.TABLES_WITH_INDEX_IDS }
      assertWithMessage("Identita' remote fuori dalle tabelle dell'indice").that(remoteOutside).isEmpty()

      // Ogni colonna che punta a una versione e' rinominata dall'adozione D7 (IdentityDao.renameVersion) e tradotta
      // dall'unione: una tabella nuova che punta a `media_version` senza essere elencata fa fallire il test.
      val versionReferences = tables.flatMap { table ->
        sql.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
          buildList {
            while (cursor.moveToNext()) {
              val parent = cursor.getString(cursor.getColumnIndexOrThrow("table"))
              val to = cursor.getString(cursor.getColumnIndexOrThrow("to"))
              if (parent == "media_version" && to == "version_id") add("$table." + cursor.getString(cursor.getColumnIndexOrThrow("from")))
            }
          }
        }
      }
      assertWithMessage("Una tabella punta a `media_version`: aggiungila a MediaCatalog.VERSION_REFERENCES e a IdentityDao.renameVersion.")
        .that(versionReferences)
        .containsExactlyElementsIn(MediaCatalog.VERSION_REFERENCES)
    } finally {
      database.close()
    }
  }
}
