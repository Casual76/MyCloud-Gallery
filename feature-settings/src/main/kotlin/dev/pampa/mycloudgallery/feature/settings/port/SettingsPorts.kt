package dev.pampa.mycloudgallery.feature.settings.port

import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// Le "porte" delle Impostazioni: cio' che le pagine chiedono all'esterno e che nessun contratto di
// :core-* offre ancora. Stanno qui (nel perimetro di :feature-settings) e non nei moduli core perche' P7 non
// li tocca; `docs/fase2-progetto.md` §4.2 chiede di segnalare all'orchestratore cio' che manca, e l'elenco e'
// nel report di P7.

/**
 * L'account NAS di questo dispositivo (o profilo): chi e', dove sta, dove e' la password. **La porta che P9
 * deve legare in :app** (con `CredentialVault`, l'impronta del NAS e il registro dei dispositivi): finche'
 * non c'e', le pagine "Account del NAS" e "Cambia indirizzo" dicono che la funzione non e' ancora collegata.
 *
 * E' opzionale per Dagger (`@BindsOptionalOf`, vedi `SettingsOptionalBindingsModule`): l'app compila anche
 * senza, e un solo `@Provides` in :app la accende.
 */
interface NasAccountSettings {

  /** L'account configurato, o null se questo dispositivo non ne ha ancora uno. */
  val account: StateFlow<NasAccount?>

  /**
   * Salva la password nuova nel Keystore (la voce [NasAccount.credentialSlot]). **Non** chiude
   * l'interruttore di sicurezza ne' tocca le sessioni: lo fa chi chiama, nell'ordine giusto.
   */
  suspend fun savePassword(credentials: NasCredentials): SaveResult

  /**
   * Applica un indirizzo nuovo del NAS, solo dopo che l'utente l'ha confermato e il GUID e' risultato uguale
   * a quello memorizzato (E-R7, C4: l'indirizzo non cambia mai da solo).
   */
  suspend fun updateAddress(address: NasAddress)
}

/** L'ora del dispositivo, dietro un'interfaccia: cosi' i test non dipendono dall'orologio. */
fun interface SettingsClock {
  fun nowMillis(): Long
}

/** Le impostazioni del blocco app (`AppLockSettingsStore` di :core-security), dietro un'interfaccia per i test. */
interface AppLockPreferences {
  val settings: Flow<AppLockSettings>

  suspend fun update(settings: AppLockSettings)
}

/** Il telefono ha un blocco schermo vero? (`ScreenLockCheck`), dietro un'interfaccia per i test. */
fun interface ScreenLockProbe {
  fun status(): ScreenLockStatus
}
