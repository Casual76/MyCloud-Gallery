package dev.pampa.mycloudgallery.core.rules.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.traffic.DataStoreTrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * OB-4 della review generale: con la memoria piena `DataStore.edit` lancia `IOException`; da un `viewModelScope` (le Impostazioni delle regole) chiudeva l'app, da un
 * trasferimento (il registro dei consumi) lo faceva cadere. Ora la scrittura non riesce e basta.
 */
class RuleSettingsFullStorageTest {

  private class FullDataStore : DataStore<Preferences> {
    var failure: Throwable = IOException("No space left on device")
    override val data: Flow<Preferences> = flowOf(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
  }

  @Test
  fun conLaMemoriaPienaLeImpostazioniDelleRegoleNonSiSalvanoENonLanciano() = runTest {
    val store = DataStoreRuleSettingsStore(FullDataStore())

    store.save(RuleSettings(preset = Preset.MAXIMUM))
    store.update { it.copy(preset = Preset.MAXIMUM) }

    assertThat(store.current().preset).isEqualTo(RuleSettings().preset)
  }

  @Test
  fun conLaMemoriaPienaIlRegistroDeiConsumiNonFaCadereUnTrasferimento() = runTest {
    val ledger = DataStoreTrafficLedger(FullDataStore())

    ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.HOME_WIFI, 1_000L)

    assertThat(ledger.currentUsage().totalBytes).isEqualTo(0L)
  }

  @Test
  fun unErroreDiProgrammazioneInScritturaNonVieneNascosto() = runTest {
    val store = DataStoreRuleSettingsStore(FullDataStore().also { it.failure = IllegalStateException("bug") })

    val failure = runCatching { store.save(RuleSettings()) }.exceptionOrNull()

    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
  }
}
