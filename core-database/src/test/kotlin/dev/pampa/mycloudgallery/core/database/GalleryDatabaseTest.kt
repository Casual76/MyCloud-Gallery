package dev.pampa.mycloudgallery.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun inserisceELeggeUnaRiga() = runBlocking {
    val dao = database.segnapostoDao()
    assertThat(dao.conta()).isEqualTo(0)

    dao.inserisci(SegnapostoEntity("uno"))

    assertThat(dao.conta()).isEqualTo(1)
  }
}
