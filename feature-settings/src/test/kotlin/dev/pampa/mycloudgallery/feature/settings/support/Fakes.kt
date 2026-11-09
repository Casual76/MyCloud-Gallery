package dev.pampa.mycloudgallery.feature.settings.support

import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.security.credentials.KeyProtectionLevel
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.settings.port.AppLockPreferences
import dev.pampa.mycloudgallery.feature.settings.port.NasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.port.ScreenLockProbe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * L'account NAS finto. Ricorda **che** una password e' stata salvata e per quale utente, mai la password: un
 * test che la cerca nei log non la trova qui.
 */
internal class FakeNasAccountSettings(initial: NasAccount? = Samples.account()) : NasAccountSettings {
  override val account: MutableStateFlow<NasAccount?> = MutableStateFlow(initial)

  var saveResult: SaveResult = SaveResult.Saved(KeyProtectionLevel.TRUSTED_ENVIRONMENT)
  val savedFor: MutableList<String> = mutableListOf()
  val addressUpdates: MutableList<NasAddress> = mutableListOf()

  /** Registra l'ordine delle operazioni insieme ad altri fake (`OperationLog`-like). */
  var onSave: () -> Unit = {}

  override suspend fun savePassword(credentials: NasCredentials): SaveResult {
    onSave()
    if (saveResult is SaveResult.Saved) savedFor += credentials.username
    return saveResult
  }

  override suspend fun updateAddress(address: NasAddress) {
    addressUpdates += address
  }
}

/** Chi chiede lo sblocco del telefono: risponde [result] e conta le richieste. */
internal class FakeAuthenticator(var result: AuthResult = AuthResult.Success) : SystemCredentialAuthenticator {
  var prompts: Int = 0
    private set

  override fun availability(): AuthAvailability = AuthAvailability.AVAILABLE

  override suspend fun authenticate(text: AuthPromptText): AuthResult {
    prompts++
    return result
  }
}

internal class FakeAppLockPreferences(initial: AppLockSettings = AppLockSettings()) : AppLockPreferences {
  private val flow = MutableStateFlow(initial)
  override val settings: Flow<AppLockSettings> = flow

  val updates: MutableList<AppLockSettings> = mutableListOf()

  /** Vero: la scrittura non riesce (memoria piena), come `DataStore.edit` che lancia `IOException`: `update` dice falso e niente cambia. */
  var failWrites: Boolean = false

  override suspend fun update(settings: AppLockSettings): Boolean {
    if (failWrites) return false
    updates += settings
    flow.value = settings
    return true
  }
}

internal class FakeScreenLockProbe(var status: ScreenLockStatus = ScreenLockStatus.SECURE) : ScreenLockProbe {
  override fun status(): ScreenLockStatus = status
}

/** Le impostazioni delle regole in memoria: il motore le rilegge da questo flusso, come dal DataStore vero. */
internal class InMemoryRuleStore(initial: dev.pampa.mycloudgallery.core.rules.settings.RuleSettings = dev.pampa.mycloudgallery.core.rules.settings.RuleSettings()) :
  dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore {
  private val flow = MutableStateFlow(initial)

  override fun observe(): Flow<dev.pampa.mycloudgallery.core.rules.settings.RuleSettings> = flow

  override suspend fun current(): dev.pampa.mycloudgallery.core.rules.settings.RuleSettings = flow.value

  override suspend fun save(settings: dev.pampa.mycloudgallery.core.rules.settings.RuleSettings) {
    flow.value = settings
  }

  override suspend fun update(transform: (dev.pampa.mycloudgallery.core.rules.settings.RuleSettings) -> dev.pampa.mycloudgallery.core.rules.settings.RuleSettings) {
    flow.value = transform(flow.value)
  }
}
