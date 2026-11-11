package dev.pampa.mycloudgallery.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room + KSP + Robolectric: un database vero in memoria, senza telefono. */
@RunWith(AndroidJUnit4::class)
class GalleryDatabaseTest {

  private lateinit var database: GalleryDatabase

  @Before
  fun setUp() {
    database = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun primoAvvioSuFileApreCronologiaEAlbumInParallelo() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val name = "hr-first-" + java.util.UUID.randomUUID() + ".db"
    val fresh = GalleryDatabase.build(context, name)
    try {
      kotlinx.coroutines.coroutineScope {
        val jobs = (0 until 12).map { i ->
          async(kotlinx.coroutines.Dispatchers.IO) {
            repeat(20) {
              if (i == 0) fresh.searchHistoryDao().deleteAll()
              val table = listOf("search_history", "album", "media", "media_location")[i % 4]
              fresh.openHelper.readableDatabase.query("SELECT COUNT(*) FROM " + table).use { c ->
                check(c.moveToFirst() && c.getInt(0) == 0)
              }
            }
          }
        }
        jobs.forEach { it.await() }
      }
    } finally {
      fresh.close()
      context.deleteDatabase(name)
    }
  }

  @Test
  fun leChiaviEsterneSonoAttive() {
    database.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
      assertThat(cursor.moveToFirst()).isTrue()
      assertThat(cursor.getInt(0)).isEqualTo(1)
    }
  }

  @Test
  fun unDatabaseNuovoEVuotoEIntegro() {
    val db = database.openHelper.writableDatabase
    db.query("PRAGMA integrity_check").use { assertThat(it.moveToFirst() && it.getString(0) == "ok").isTrue() }
    db.query("SELECT COUNT(*) FROM media").use { assertThat(it.moveToFirst() && it.getInt(0) == 0).isTrue() }
  }
}
