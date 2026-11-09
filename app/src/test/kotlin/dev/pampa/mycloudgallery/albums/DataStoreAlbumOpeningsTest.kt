package dev.pampa.mycloudgallery.albums

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** G2b: l'ultima apertura delle voci della scheda Album, ricordata nel DataStore del profilo. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAlbumOpeningsTest {

  @Test
  fun ilFormatoTornaUgualeERigheRovinateSiIgnorano() {
    val order = mapOf("album:00000000-0000-4000-8000-00000000000a" to 3L, "special:camera" to 7L)
    assertThat(DataStoreAlbumOpenings.decode(DataStoreAlbumOpenings.encode(order))).isEqualTo(order)
    assertThat(DataStoreAlbumOpenings.decode("special:camera\t7\nrotta\n\t3\nspecial:videos\tx")).containsExactly("special:camera", 7L)
    assertThat(DataStoreAlbumOpenings.decode(null)).isEmpty()
  }

  @Test
  fun unApertoSiRicordaSulFileESiRilegge() = runTest(UnconfinedTestDispatcher()) {
    val dir: File = Files.createTempDirectory("aperture").toFile()
    val file = File(dir, "album_openings.preferences_pb")
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
    val openings = DataStoreAlbumOpenings(store, backgroundScope)

    assertThat(openings.order.filterNotNull().first()).isEmpty()
    openings.opened("special:camera")
    openings.opened("album:00000000-0000-4000-8000-00000000000a")
    openings.opened("special:camera")
    // Una chiave che romperebbe il formato non si scrive.
    openings.opened("rotta\tchiave")
    val order = openings.order.filterNotNull().first { it.size == 2 }
    assertThat(order).containsExactly("special:camera", 3L, "album:00000000-0000-4000-8000-00000000000a", 2L)
    dir.deleteRecursively()
  }
}
