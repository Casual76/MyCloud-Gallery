package dev.pampa.mycloudgallery.core.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.data.DataStoreLibraryPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * OB-4 della review generale: con la memoria piena `DataStore.edit` lancia `IOException`, e chiamata da un `viewModelScope` o da un `rememberCoroutineScope` (la Libreria
 * salva filtro, livello e stile cosi') chiudeva l'app. Ora la preferenza non si salva e basta.
 */
class LibraryPreferencesFullStorageTest {

  /** Un archivio dove ogni scrittura fallisce come con la memoria piena. */
  private class FullDataStore : DataStore<Preferences> {
    var failure: Throwable = IOException("No space left on device")
    override val data: Flow<Preferences> = flowOf(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
  }

  @Test
  fun conLaMemoriaPienaNessunaScritturaFaCadereNiente_EIValoriRestanoQuelliDiPrima() = runTest(UnconfinedTestDispatcher()) {
    val prefs = DataStoreLibraryPreferences(FullDataStore(), backgroundScope)

    prefs.setFilter(LibraryFilter.SCREENSHOTS)
    prefs.setLevel(GridLevel.MONTHS)
    prefs.setStyle(GridStyle.JUSTIFIED)
    prefs.setViewerDocked(true)

    assertThat(prefs.filter.value).isEqualTo(LibraryFilter.ALL)
    assertThat(prefs.level.value).isEqualTo(GridLevel.DAYS)
    assertThat(prefs.style.value).isEqualTo(GridStyle.SQUARE)
    assertThat(prefs.viewerDocked.value).isFalse()
  }

  @Test
  fun unErroreDiProgrammazioneNonVieneNascosto() = runTest(UnconfinedTestDispatcher()) {
    val store = FullDataStore().also { it.failure = IllegalStateException("bug") }
    val prefs = DataStoreLibraryPreferences(store, backgroundScope)

    val failure = runCatching { prefs.setFilter(LibraryFilter.SCREENSHOTS) }.exceptionOrNull()

    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
  }
}
