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
          table !in MediaCatalog.TABLES_WITHOUT_MEDIA_IDS
      }
      assertWithMessage("Tabelle non classificate per l'unione: MediaCatalog.TABLES_WITHOUT_MEDIA_IDS o un riferimento a media.")
        .that(unclassified)
        .isEmpty()
      assertThat(MediaCatalog.TABLES_WITHOUT_MEDIA_IDS).containsAtLeast("media_store_cursor", "catalog_meta")
      assertThat(tables).containsAtLeastElementsIn(MediaCatalog.TABLES_WITHOUT_MEDIA_IDS)

      // Una colonna che si chiama come un'identita' di media senza chiave esterna sfuggirebbe all'unione.
      val unmanagedIds = tables.flatMap { table ->
        sql.query("PRAGMA table_info(`$table`)").use { cursor ->
          buildList {
            while (cursor.moveToNext()) {
              val column = cursor.getString(cursor.getColumnIndexOrThrow("name"))
              if (column.endsWith("media_id")) add("$table.$column")
            }
          }
        }
      }.filter { it != "media.media_id" && it !in MediaCatalog.MEDIA_REFERENCES }
      assertWithMessage("Colonne con identita' di media non gestite dall'unione").that(unmanagedIds).isEmpty()
    } finally {
      database.close()
    }
  }
}
