package dev.pampa.mycloudgallery.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
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
