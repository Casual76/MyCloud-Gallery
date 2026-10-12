package dev.pampa.mycloudgallery.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    } finally {
      database.close()
    }
  }
}
