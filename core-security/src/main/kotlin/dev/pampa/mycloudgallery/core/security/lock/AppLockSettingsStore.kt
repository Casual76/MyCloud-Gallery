package dev.pampa.mycloudgallery.core.security.lock

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.security.AppLockMode
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Dove si ricordano le impostazioni del blocco (modo e timeout). Sono in `noBackupFilesDir`, come le
 * credenziali: non viaggiano in nessun backup.
 *
 * **Fail closed:** se il file non si legge (danneggiato, I/O) il blocco risulta ATTIVO e senza tolleranza
 * ([AppLockSettings.FAIL_CLOSED]); un valore salvato che non si riconosce vale "subito" per il timeout e
 * "credenziale di sistema" per il modo.
 */
class AppLockSettingsStore internal constructor(private val store: DataStore<Preferences>) {

  val settings: Flow<AppLockSettings> = store.data
    .map { decode(it) }
    .catch { error ->
      if (error is IOException) {
        GalleryLog.w(TAG) { "impostazioni del blocco non leggibili (${error.javaClass.simpleName}): blocco attivo" }
        emit(AppLockSettings.FAIL_CLOSED)
      } else {
        throw error
      }
    }

  /**
   * Salva [settings]. **Vero** se e' stato scritto; **falso** se non si e' riusciti (memoria piena, cartella non scrivibile: `IOException` di DataStore, OB-4 della review
   * generale): il blocco resta com'era, l'app non si chiude e chi chiama puo' dirlo all'utente (l'interruttore che torna indietro).
   */
  suspend fun update(settings: AppLockSettings): Boolean = QuietWrites.attempt("impostazioni del blocco") {
    store.edit {
      it[MODE] = settings.mode.name
      it[TIMEOUT] = settings.timeout.name
    }
  }

  companion object {
    private const val TAG = "AppLockSettingsStore"
    private val MODE = stringPreferencesKey("mode")
    private val TIMEOUT = stringPreferencesKey("timeout")

    /** Niente salvato = blocco spento (e' lo stato di fabbrica, non un errore). */
    internal fun decode(prefs: Preferences): AppLockSettings {
      val modeName = prefs[MODE]
      val timeoutName = prefs[TIMEOUT]
      val mode = if (modeName == null) {
        AppLockMode.OFF
      } else {
        AppLockMode.entries.firstOrNull { it.name == modeName } ?: AppLockMode.SYSTEM_CREDENTIAL
      }
      val timeout = if (timeoutName == null) {
        AppLockTimeout.DEFAULT
      } else {
        AppLockTimeout.entries.firstOrNull { it.name == timeoutName } ?: AppLockTimeout.IMMEDIATELY
      }
      return AppLockSettings(mode, timeout)
    }

    @Volatile
    private var instance: AppLockSettingsStore? = null

    /** L'unico archivio del processo (DataStore non ammette due istanze sullo stesso file). */
    fun getInstance(context: Context): AppLockSettingsStore {
      instance?.let { return it }
      return synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
      }
    }

    private fun create(context: Context): AppLockSettingsStore {
      val store = PreferenceDataStoreFactory.create(
        // File danneggiato: blocco attivo e immediato, che l'utente poi puo' spegnere.
        corruptionHandler = ReplaceFileCorruptionHandler {
          preferencesOf(
            MODE to AppLockSettings.FAIL_CLOSED.mode.name,
            TIMEOUT to AppLockSettings.FAIL_CLOSED.timeout.name,
          )
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { File(context.noBackupFilesDir, "datastore/app_lock.preferences_pb") },
      )
      return AppLockSettingsStore(store)
    }
  }
}
