package dev.pampa.mycloudgallery.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * La migrazione 5 -> 6 su una Libreria da **100 mila media** (Fase 7, P1), in un file vero (non in memoria), come sul telefono. Classe a
 * parte: non apre il database in memoria dei test del catalogo.
 */
@RunWith(AndroidJUnit4::class)
class Migration5To6ScaleTest {

  /**
   * La migrazione 5 -> 6 di una Libreria da 100 mila media: i derivati nuovi (`origin_device`, `copy_areas`) di ogni media sono quelli
   * attesi dalle sue posizioni, nessuna chiave esterna e' rotta, e il tempo (scrittura di due colonne e costruzione dei quattro indici)
   * resta dentro un budget largo: e' una volta sola, all'avvio dopo l'aggiornamento.
   */
  @Test
  fun laMigrazioneDiCentoMilaMediaRiempieIDerivatiComeIlCatalogoELasciaIlResto() {
    val lib = BigLibrary(100_000)
    val context = ApplicationProvider.getApplicationContext<Context>()
    val name = "migrazione-5-6-centomila.db"
    val file = SchemaFiles.create(context, File("schemas/${GalleryDatabase::class.java.name}"), 5, name) { sql ->
      sql.execSQL("INSERT INTO nas_share (account_id, share_key, share_name, area) VALUES ('alessio', 'alessio', 'alessio', 'private'), ('alessio', 'nas', 'NAS', 'common')")
      lib.insertIntoV5(sql)
    }
    // Il file della v5 (e il suo log, se non e' stato ancora riportato nel file) prima di aprirlo con Room.
    val sizeBefore = file.length() + File(file.path + "-wal").let { if (it.exists()) it.length() else 0L }

    val started = System.nanoTime()
    val migrated = GalleryDatabase.build(context, name)
    try {
      val sql = migrated.openHelper.writableDatabase
      val millis = (System.nanoTime() - started) / 1_000_000
      val sizeAfter = sql.query("SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size()").use { c -> c.moveToFirst(); c.getLong(0) }
      println("Migrazione 5 -> 6 di 100.000 media (e relative posizioni): $millis ms; il database passa da ${sizeBefore / 1_000_000} a ${sizeAfter / 1_000_000} MB")
      val byId = HashMap<String, Pair<String?, Int>>()
      sql.query("SELECT media_id, origin_device, copy_areas FROM media").use { c ->
        while (c.moveToNext()) byId[c.getString(0)] = (if (c.isNull(1)) null else c.getString(1)) to c.getInt(2)
      }
      assertThat(byId).hasSize(lib.count)
      var wrong = 0
      for (i in 0 until lib.count) if (byId[lib.ids[i]] != (lib.device[i] to lib.copyAreas[i])) wrong++
      assertWithMessage("media con i derivati sbagliati").that(wrong).isEqualTo(0)
      sql.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
      assertWithMessage("Migrazione: $millis ms").that(millis).isLessThan(30_000)
    } finally {
      migrated.close()
    }
  }
}
