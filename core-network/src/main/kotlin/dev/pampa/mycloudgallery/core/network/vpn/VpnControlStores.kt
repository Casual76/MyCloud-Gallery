package dev.pampa.mycloudgallery.core.network.vpn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.network.contract.WireGuardTunnelNames
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * La configurazione del controllo di WireGuard e la sospensione, in un DataStore dentro `noBackupFilesDir`.
 * Ogni profilo Android ha il suo (E-P20): e' il suo spazio privato.
 *
 * Il valore in memoria segue subito le scritture; prima che il file sia letto vale "controllo spento", cioe' il
 * lato prudente (l'automazione non comanda niente).
 */
class DataStoreVpnControlConfigStore(
  private val dataStore: DataStore<Preferences>,
  scope: CoroutineScope,
) : VpnControlConfigStore {

  private val configState = MutableStateFlow(VpnControlConfig.OFF)
  private val suspensionState = MutableStateFlow<VpnSuspendReason?>(null)

  override val config: StateFlow<VpnControlConfig> = configState
  override val suspension: StateFlow<VpnSuspendReason?> = suspensionState

  init {
    scope.launch {
      dataStore.data.catch { emit(emptyPreferences()) }.map(::decode).collect { (config, suspension) ->
        configState.value = config
        suspensionState.value = suspension
      }
    }
  }

  override suspend fun save(config: VpnControlConfig) {
    val clean = clean(config)
    dataStore.edit { prefs ->
      prefs[ENABLED] = clean.enabled
      clean.tunnelName?.let { prefs[TUNNEL] = it } ?: prefs.remove(TUNNEL)
      prefs.remove(SUSPENSION)
    }
    configState.value = clean
    suspensionState.value = null
  }

  override suspend fun suspendBecause(reason: VpnSuspendReason) {
    dataStore.edit { it[SUSPENSION] = reason.name }
    suspensionState.value = reason
  }

  override suspend fun clearSuspension() {
    dataStore.edit { it.remove(SUSPENSION) }
    suspensionState.value = null
  }

  private fun decode(prefs: Preferences): Pair<VpnControlConfig, VpnSuspendReason?> {
    val config = clean(VpnControlConfig(prefs[ENABLED] ?: false, prefs[TUNNEL]))
    val suspension = prefs[SUSPENSION]?.let { name -> VpnSuspendReason.entries.firstOrNull { it.name == name } }
    return config to suspension
  }

  companion object {
    private val ENABLED = booleanPreferencesKey("vpn_control_enabled")
    private val TUNNEL = stringPreferencesKey("vpn_tunnel_name")
    private val SUSPENSION = stringPreferencesKey("vpn_suspension")

    /** Un nome non valido non si usa mai in un intent: diventa "nessun nome". */
    internal fun clean(config: VpnControlConfig): VpnControlConfig =
      VpnControlConfig(config.enabled, config.tunnelName?.trim()?.takeIf(WireGuardTunnelNames::isValid))

    /** Il file dell'app: `noBackupFilesDir/datastore/network_vpn.preferences_pb`. */
    fun create(context: Context, scope: CoroutineScope): DataStoreVpnControlConfigStore =
      DataStoreVpnControlConfigStore(
        dataStore = PreferenceDataStoreFactory.create(
          corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
          scope = scope,
          produceFile = { File(context.noBackupFilesDir, "datastore/network_vpn.preferences_pb") },
        ),
        scope = scope,
      )
  }
}
