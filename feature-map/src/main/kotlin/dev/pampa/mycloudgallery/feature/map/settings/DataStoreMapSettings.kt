package dev.pampa.mycloudgallery.feature.map.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.feature.map.map.SettingsLoaded
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Le impostazioni della mappa di QUESTO dispositivo su DataStore (Fase 9, P4; docs/fase9-progetto.md D24, D29, **risposta Q1 (c) del 27/10**; contratto `MapSettings` di core-map, suite
 * `MapSettingsContract`): **locali, mai sincronizzate** (nessuna voce nei dati personali: il tablet condiviso e ogni telefono decidono per se'), in `noBackupFilesDir/map/settings/`, cosi'
 * l'azzeramento di `map/` (D37, P13) toglie anche loro, camera compresa. "Svuota la cache della mappa" (P5) tocca solo le tessere, mai questa cartella.
 *
 *  - **Q1 (c)**: la mappa dettagliata e' **accesa di serie**, senza pagina di consenso (`MapOnline.On(byDefault = true)`). Si salva solo cio' che l'utente fa: spegnerla scrive `off`;
 *    riaccenderla (con la scelta costruita dalla pagina della mappa) scrive `on` e la mappa non e' piu' "di serie" (`byDefault = false`). Un file rovinato o con un valore sconosciuto
 *    (una versione piu' nuova) torna a "di serie". Con l'interruttore di build spento (`FeatureFlags.mapOnline`) e' [MapOnline.Unavailable], qualunque cosa dica il file.
 *  - **"Solo sul Wi-Fi"**: di serie acceso (D24); mai in roaming in ogni caso (il cancello).
 *  - **L'ultima camera** (D29): una stringa `lat,lon,zoom` in microgradi, **solo qui**; un valore illeggibile o fuori dai limiti e' nessuna camera. Mai nei log, mai nel `toString()`.
 *
 * Le `StateFlow` partono dal valore di serie e si aggiornano alla prima lettura del file (pochi millisecondi); chi vuole la camera giusta prima di aprire la mappa aspetta [loaded].
 */
class DataStoreMapSettings(
  private val store: DataStore<Preferences>,
  scope: CoroutineScope,
  private val buildFlag: Boolean,
) : MapSettings, SettingsLoaded {

  private val data: Flow<Preferences> = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

  override val online: StateFlow<MapOnline> = data.map { onlineOf(it[ONLINE]) }.stateIn(scope, SharingStarted.Eagerly, onlineOf(null))

  override val wifiOnly: StateFlow<Boolean> = data.map { it[WIFI_ONLY] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

  override val lastCamera: StateFlow<CameraSpot?> = data.map { cameraOf(it[CAMERA]) }.stateIn(scope, SharingStarted.Eagerly, null)

  private fun onlineOf(saved: String?): MapOnline = when {
    !buildFlag -> MapOnline.Unavailable
    saved == OFF -> MapOnline.Off
    saved == ON -> MapOnline.On(byDefault = false)
    else -> MapOnline.On(byDefault = true)
  }

  /** Aspetta la prima lettura del file. */
  override suspend fun loaded() {
    data.first()
  }

  override suspend fun enable(choice: MapOnlineChoice) {
    if (!buildFlag) return
    store.edit { it[ONLINE] = ON }
  }

  override suspend fun disable() {
    if (!buildFlag) return
    store.edit { it[ONLINE] = OFF }
  }

  override suspend fun setWifiOnly(on: Boolean) {
    store.edit { it[WIFI_ONLY] = on }
  }

  override suspend fun saveCamera(spot: CameraSpot?) {
    store.edit { prefs ->
      if (spot == null) prefs.remove(CAMERA) else prefs[CAMERA] = "${spot.center.latE6},${spot.center.lonE6},${spot.zoom}"
    }
  }

  override fun toString(): String = "DataStoreMapSettings"

  companion object {
    private val ONLINE = stringPreferencesKey("online")
    private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    private val CAMERA = stringPreferencesKey("camera")
    private const val ON = "on"
    private const val OFF = "off"

    /** Il file in `noBackupFilesDir/map/settings/` (D37: l'azzeramento di `map/` lo toglie). */
    fun file(context: Context): File = File(context.noBackupFilesDir, "map/settings/map.preferences_pb")

    /** La camera da una stringa salvata: null se non e' fatta come deve o se le coordinate non sono valide. */
    internal fun cameraOf(text: String?): CameraSpot? {
      val parts = text?.split(',') ?: return null
      if (parts.size != 3) return null
      val lat = parts[0].toIntOrNull() ?: return null
      val lon = parts[1].toIntOrNull() ?: return null
      val zoom = parts[2].toDoubleOrNull() ?: return null
      if (!MapPointValidity.isValid(lat, lon) || !zoom.isFinite() || zoom !in 0.0..CameraSpot.MAX_ZOOM) return null
      return CameraSpot(GeoE6(lat, lon), zoom)
    }

    /** Le impostazioni su [file] (un DataStore per file e per processo: `MapGraph` di `:app` ne crea una). [scope] tiene vive le `StateFlow`; [storeScope] e' quello del DataStore (I/O). */
    fun create(
      file: File,
      scope: CoroutineScope,
      buildFlag: Boolean,
      storeScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ): DataStoreMapSettings = DataStoreMapSettings(
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = storeScope,
        produceFile = { file },
      ),
      scope,
      buildFlag,
    )
  }
}
