package dev.pampa.mycloudgallery.core.security.lock

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.AppLockMode
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLockSettingsStoreTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun realStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "lock.preferences_pb") }

  @Test
  fun difabbrica_ilBloccoESpento_conIlTimeoutDiDefault() {
    val store = AppLockSettingsStore(realStore())
    assertThat(runBlocking { store.settings.first() }).isEqualTo(AppLockSettings(AppLockMode.OFF, AppLockTimeout.DEFAULT))
  }

  @Test
  fun siSalvaESiRilegge() {
    val store = AppLockSettingsStore(realStore())
    runBlocking { store.update(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_5_MINUTES)) }
    assertThat(runBlocking { store.settings.first() })
      .isEqualTo(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_5_MINUTES))
  }

  @Test
  fun ogniTimeoutSopravviveAlSalvataggio() {
    val store = AppLockSettingsStore(realStore())
    for (timeout in AppLockTimeout.entries) {
      runBlocking { store.update(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, timeout)) }
      assertThat(runBlocking { store.settings.first() }.timeout).isEqualTo(timeout)
    }
  }

  @Test
  fun valoriNonRiconosciuti_sonoIlPiuSicuro() {
    val raw = realStore()
    runBlocking {
      raw.edit {
        it[stringPreferencesKey("mode")] = "PIN_MIO"
        it[stringPreferencesKey("timeout")] = "AFTER_100_YEARS"
      }
    }
    // un modo sconosciuto non spegne il blocco, un timeout sconosciuto vale "subito"
    assertThat(runBlocking { AppLockSettingsStore(raw).settings.first() })
      .isEqualTo(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.IMMEDIATELY))
  }

  @Test
  fun erroreDiLettura_failClosed() {
    val broken = object : DataStore<Preferences> {
      override val data: Flow<Preferences> = flow { throw IOException("archiviazione non pronta") }
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
    assertThat(runBlocking { AppLockSettingsStore(broken).settings.first() }).isEqualTo(AppLockSettings.FAIL_CLOSED)
  }

  /** OB-4 della review generale: con la memoria piena `DataStore.edit` lancia `IOException`; da un `viewModelScope` chiudeva l'app. Ora `update` dice "non salvato" e basta. */
  @Test
  fun unaScritturaCheNonRiesce_restituisceFalso_NonLanciaEIlBloccoResta() {
    val full = object : DataStore<Preferences> {
      override val data: Flow<Preferences> = flow { emit(emptyPreferences()) }
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("No space left on device")
    }
    val store = AppLockSettingsStore(full)

    val saved = runBlocking { store.update(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.IMMEDIATELY)) }

    assertThat(saved).isFalse()
    assertThat(runBlocking { store.settings.first() }.mode).isEqualTo(AppLockMode.OFF)
  }

  @Test
  fun unaScritturaCheRiesce_restituisceVero() {
    val store = AppLockSettingsStore(realStore())

    assertThat(runBlocking { store.update(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_5_MINUTES)) }).isTrue()
  }

  @Test
  fun unErroreDiProgrammazioneInScrittura_nonVieneNascosto() {
    val broken = object : DataStore<Preferences> {
      override val data: Flow<Preferences> = flow { emit(emptyPreferences()) }
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IllegalStateException("bug")
    }
    val result = runCatching { runBlocking { AppLockSettingsStore(broken).update(AppLockSettings()) } }
    assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun unErroreCheNonEDiI_O_nonVieneNascosto() {
    val broken = object : DataStore<Preferences> {
      override val data: Flow<Preferences> = flow { throw IllegalStateException("bug") }
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
    val result = runCatching { runBlocking { AppLockSettingsStore(broken).settings.first() } }
    assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
  }
}
