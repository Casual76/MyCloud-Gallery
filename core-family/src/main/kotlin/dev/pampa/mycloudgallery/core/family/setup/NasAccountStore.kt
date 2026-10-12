package dev.pampa.mycloudgallery.core.family.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** La voce del `CredentialVault` dove sta la password dell'account NAS di questo dispositivo. */
object NasCredentialSlots {
  const val PRIMARY: String = "nas-primary"
}

/**
 * L'account del NAS di questo dispositivo: indirizzo, utente, voce delle credenziali e GUID memorizzato. E'
 * cio' che l'onboarding scrive quando la password e' stata accettata (passo 3 o ingresso con invito) e che chi
 * apre le sessioni SMB legge per sapere a chi collegarsi (`NasAccount`, `docs/fase2-progetto.md` §1.2).
 *
 * Il contratto di `core-nas` non dice da dove la sessione prenda l'account: questo e' il punto in cui lo
 * scrive l'onboarding, e `:app` (P9) lo collega alla sessione. Mai la password qui: solo il nome della voce.
 */
interface NasAccountStore {

  fun observe(): Flow<NasAccount?>

  suspend fun current(): NasAccount?

  suspend fun save(account: NasAccount)

  suspend fun clear()
}

/** [NasAccountStore] su DataStore Preferences (in `noBackupFilesDir`, come il resto dello stato locale). */
class DataStoreNasAccountStore(private val dataStore: DataStore<Preferences>) : NasAccountStore {

  override fun observe(): Flow<NasAccount?> = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map(::read)

  override suspend fun current(): NasAccount? = read(dataStore.data.first())

  override suspend fun save(account: NasAccount) {
    dataStore.edit { prefs ->
      prefs[IP] = account.address.ip
      val name = account.address.name
      if (name != null) prefs[NAME] = name else prefs.remove(NAME)
      prefs[PORT] = account.address.port
      prefs[USER] = account.user
      prefs[SLOT] = account.credentialSlot
      val guid = account.pinnedGuid
      if (guid != null) prefs[GUID] = guid else prefs.remove(GUID)
    }
  }

  override suspend fun clear() {
    dataStore.edit { prefs ->
      listOf(IP, NAME, USER, SLOT, GUID).forEach { prefs.remove(it) }
      prefs.remove(PORT)
    }
  }

  private fun read(prefs: Preferences): NasAccount? {
    val ip = prefs[IP] ?: return null
    val address = NasAddress.parseOrNull(ip, prefs[NAME], prefs[PORT] ?: NasAddress.DEFAULT_PORT) ?: return null
    return NasAccount(address, prefs[USER] ?: return null, prefs[SLOT] ?: return null, prefs[GUID])
  }

  private companion object {
    val IP = stringPreferencesKey("nas.ip")
    val NAME = stringPreferencesKey("nas.name")
    val PORT = intPreferencesKey("nas.port")
    val USER = stringPreferencesKey("nas.user")
    val SLOT = stringPreferencesKey("nas.slot")
    val GUID = stringPreferencesKey("nas.guid")
  }
}
