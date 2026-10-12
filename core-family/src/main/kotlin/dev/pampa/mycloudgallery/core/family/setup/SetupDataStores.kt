package dev.pampa.mycloudgallery.core.family.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * I DataStore dello stato locale di famiglia, in `noBackupFilesDir`: fuori da qualunque backup e dal
 * trasferimento da dispositivo a dispositivo (C16, E-O8), come le credenziali.
 *
 * **Uno per file e per processo**: DataStore non ammette due istanze sullo stesso file, quindi `:app` li crea
 * una volta sola (singleton) e li passa a [dev.pampa.mycloudgallery.core.family.onboarding.DataStoreOnboardingStateStore]
 * e a [DataStoreNasAccountStore]. Un file danneggiato diventa un file vuoto: l'onboarding riparte dal benvenuto e
 * l'account si reinserisce.
 */
object SetupDataStores {

  const val ONBOARDING_FILE: String = "onboarding"
  const val ACCOUNT_FILE: String = "nas-account"

  /** L'avanzamento dell'onboarding. */
  fun onboarding(context: Context, scope: CoroutineScope): DataStore<Preferences> = create(context, scope, ONBOARDING_FILE)

  /** L'account del NAS di questo dispositivo. */
  fun account(context: Context, scope: CoroutineScope): DataStore<Preferences> = create(context, scope, ACCOUNT_FILE)

  /** Dove sta il file di [name]: `noBackupFilesDir/datastore/<nome>.preferences_pb`. */
  fun fileOf(context: Context, name: String): File = File(File(context.noBackupFilesDir, "datastore"), "$name.preferences_pb")

  private fun create(context: Context, scope: CoroutineScope, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
      corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
      scope = scope,
      produceFile = { fileOf(context, name) },
    )
}
