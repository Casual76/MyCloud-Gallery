package dev.pampa.mycloudgallery.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup
import dev.pampa.mycloudgallery.core.backup.settings.BackupSourceCodec
import dev.pampa.mycloudgallery.core.backup.settings.DefaultBackupSettings
import dev.pampa.mycloudgallery.core.backup.settings.StoredBackupSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * OB-4 della review generale: con la memoria piena `DataStore.edit` lancia `IOException`, e l'interruttore di una sorgente del backup (dal `viewModelScope` delle Impostazioni)
 * chiudeva l'app. Ora `update` non salva, non lancia e restituisce il valore ancora salvato: lo stato non cambia e l'interruttore torna dov'era.
 */
class DataStoreBackupSettingsFullStorageTest {

  @get:Rule val tmp = TemporaryFolder()

  private class FullDataStore(private val stored: Preferences = emptyPreferences()) : DataStore<Preferences> {
    var failure: Throwable = IOException("No space left on device")
    override val data: Flow<Preferences> = flowOf(stored)

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
  }

  private val camera = BackupSources(setOf(BackupSource.Camera))

  @Test
  fun conLaMemoriaPienaUpdateNonLanciaERestituisceIlValoreDiPrima() = runTest {
    val storage = DataStoreBackupSettingsStorage(FullDataStore(), flowOf(camera))

    val result = storage.update { it.copy(sourceIds = BackupSourceCodec.encode(BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots)))) }

    // Mai scelte + passo 6 dell'onboarding: il valore di prima e' quello proposto (la sola fotocamera), non la scelta fallita.
    assertThat(result.sourceIds).isEqualTo(BackupSourceCodec.encode(camera))
  }

  @Test
  fun leImpostazioniDelBackupNonCambianoELInterruttoreTornaIndietro() = runTest(UnconfinedTestDispatcher()) {
    val storage = DataStoreBackupSettingsStorage(FullDataStore(), flowOf(camera))
    val settings = DefaultBackupSettings(storage, backgroundScope)
    settings.awaitLoaded()
    assertThat(settings.sources.value.sources).containsExactly(BackupSource.Camera)

    settings.setSources(BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots)))

    // La scelta non si e' salvata: lo stato e' quello di prima (e la pagina lo dice con la sua nota).
    assertThat(settings.sources.value.sources).containsExactly(BackupSource.Camera)
  }

  @Test
  fun laPropostaDeiRitagliNonSiDaPerFattaSeLaScritturaFallisce_NonLancia() = runTest {
    val storage = DataStoreBackupSettingsStorage(FullDataStore(), flowOf(camera))

    val added = storage.offerCutoutsOnce(CutoutBackup.source())

    assertThat(added).isFalse()
  }

  @Test
  fun unaLetturaCheNonRiesceValeNienteDiSalvato_NonFaCadereIlRacoglitore() = runTest {
    val unreadable = object : DataStore<Preferences> {
      override val data: Flow<Preferences> = flow { throw IOException("archiviazione non pronta") }
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
    val storage = DataStoreBackupSettingsStorage(unreadable, flowOf(null))

    assertThat(storage.data.first()).isEqualTo(StoredBackupSettings())
  }

  @Test
  fun conLaMemoriaPiena_UnErroreDiProgrammazione_NonVieneNascosto() = runTest {
    val storage = DataStoreBackupSettingsStorage(FullDataStore().also { it.failure = IllegalStateException("bug") }, flowOf(camera))

    val failure = runCatching { storage.update { it } }.exceptionOrNull()

    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun conUnArchivioVero_SiSalvaESiRilegge() = runTest {
    val real = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "backup.preferences_pb") }
    val storage = DataStoreBackupSettingsStorage(real, flowOf(camera))

    val result = storage.update { it.copy(deviceFolder = "Pixel") }

    assertThat(result.deviceFolder).isEqualTo("Pixel")
    assertThat(storage.data.first().deviceFolder).isEqualTo("Pixel")
  }
}
