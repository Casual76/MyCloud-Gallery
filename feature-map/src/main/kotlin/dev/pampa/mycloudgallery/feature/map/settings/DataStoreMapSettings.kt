package dev.pampa.mycloudgallery.feature.map.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.MapSettingsProblem
import dev.pampa.mycloudgallery.feature.map.map.SettingsLoaded
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Le impostazioni della mappa di QUESTO dispositivo su DataStore (Fase 9, P4; docs/fase9-progetto.md D24, D29, **risposta Q1 (c) del 27/10**; contratto `MapSettings` di core-map, suite
 * `MapSettingsContract`): **locali, mai sincronizzate** (nessuna voce nei dati personali: il tablet condiviso e ogni telefono decidono per se'), in `noBackupFilesDir/map/settings/`, cosi'
 * l'azzeramento di `map/` (D37, P13) toglie anche loro, camera compresa. "Svuota la cache della mappa" (P5) tocca solo le tessere, mai questa cartella.
 *
 *  - **Q1 (c)**: la mappa dettagliata e' **accesa di serie**, senza pagina di consenso (`MapOnline.On(byDefault = true)`). Si salva solo cio' che l'utente fa: spegnerla scrive `off`;
 *    riaccenderla (con la scelta costruita dalla pagina della mappa) scrive `on` e la mappa non e' piu' "di serie" (`byDefault = false`). Un valore sconosciuto (una versione piu'
 *    nuova) torna a "di serie". Con l'interruttore di build spento (`FeatureFlags.mapOnline`) e' [MapOnline.Unavailable], qualunque cosa dica il file.
 *  - **Fallisce chiusa** (correzioni R2, 31/10): la scelta "spenta" e' la sola che tiene la zona guardata lontana da un terzo, quindi nessun guasto la riaccende da solo.
 *      1. **Prima della prima lettura riuscita** [online] vale [MapOnline.Off] (il cancello e `MapLibre.setConnected` lo leggono): la carta online nasce solo dopo, e [loaded] torna solo
 *         quando [online] e le altre `StateFlow` sono gia' al valore del file (un solo lettore le aggiorna, tutte insieme).
 *      2. **Errore di lettura** (I/O): [MapSettingsProblem.UNREADABLE]. Spenta **per il processo** (anche se un nuovo tentativo riesce: non ci si fida di una lettura che e' fallita);
 *         il flusso non finisce, la lettura si riprova ogni [RETRY_MS] per sapere "Solo sul Wi-Fi" e la camera; solo un tocco dell'utente ([enable]) la riaccende.
 *      3. **File rovinato**: il gestore della corruzione lo rifa con `online = off` **e** un segno `damaged` ([MapSettingsProblem.DAMAGED]): spenta finche' l'utente non la riaccende,
 *         anche dopo un riavvio, e la pagina delle impostazioni lo dice.
 *  - **"Solo sul Wi-Fi"**: di serie acceso (D24); mai in roaming in ogni caso (il cancello).
 *  - **L'ultima camera** (D29): una stringa `lat,lon,zoom` in microgradi, **solo qui**; un valore illeggibile o fuori dai limiti e' nessuna camera. Mai nei log, mai nel `toString()`.
 *  - **L'azzeramento** (R5): [clear] svuota il file attraverso DataStore e da li' in poi nessuna scrittura (controllo anche dentro la trasformazione, in fila con le altre).
 */
class DataStoreMapSettings(
  private val store: DataStore<Preferences>,
  scope: CoroutineScope,
  private val buildFlag: Boolean,
  private val retryMs: Long = RETRY_MS,
) : MapSettings, SettingsLoaded {

  private val onlineState = MutableStateFlow<MapOnline>(closedOnline())
  private val wifiState = MutableStateFlow(true)
  private val cameraState = MutableStateFlow<CameraSpot?>(null)
  private val problemState = MutableStateFlow(MapSettingsProblem.NONE)
  private val loadedState = MutableStateFlow(false)

  private val stopped = AtomicBoolean(false)

  /** Un errore di lettura e' capitato in questo processo: la mappa dettagliata resta spenta finche' l'utente non la riaccende. */
  @Volatile
  private var unreadable = false

  override val online: StateFlow<MapOnline> = onlineState.asStateFlow()

  override val problem: StateFlow<MapSettingsProblem> = problemState.asStateFlow()

  override val wifiOnly: StateFlow<Boolean> = wifiState.asStateFlow()

  override val lastCamera: StateFlow<CameraSpot?> = cameraState.asStateFlow()

  init {
    scope.launch { watch() }
  }

  private fun closedOnline(): MapOnline = if (buildFlag) MapOnline.Off else MapOnline.Unavailable

  /** L'unico lettore: ogni valore del file aggiorna tutte le `StateFlow` insieme; un errore non fa finire il flusso (si riprova). */
  private suspend fun watch() {
    while (true) {
      try {
        store.data.collect { prefs ->
          apply(prefs)
          loadedState.value = true
        }
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Del guasto solo il tipo: il messaggio di un errore di I/O potrebbe nominare un percorso.
        GalleryLog.w(TAG) { "impostazioni della mappa non lette (${e.javaClass.simpleName}): la mappa dettagliata vale spenta" }
        failRead()
        delay(retryMs)
      }
    }
  }

  @Synchronized
  private fun failRead() {
    unreadable = true
    if (stopped.get()) return
    onlineState.value = closedOnline()
    if (buildFlag && problemState.value == MapSettingsProblem.NONE) problemState.value = MapSettingsProblem.UNREADABLE
    // Il flusso e' "pronto" lo stesso: la mappa si apre con la carta di base, senza aspettare per sempre un file che non si legge.
    loadedState.value = true
  }

  @Synchronized
  private fun apply(prefs: Preferences) {
    if (stopped.get()) return
    val damaged = buildFlag && prefs[DAMAGED] == true
    problemState.value = when {
      damaged -> MapSettingsProblem.DAMAGED
      buildFlag && unreadable -> MapSettingsProblem.UNREADABLE
      else -> MapSettingsProblem.NONE
    }
    onlineState.value = onlineOf(prefs[ONLINE], closed = damaged || unreadable)
    wifiState.value = prefs[WIFI_ONLY] ?: true
    cameraState.value = cameraOf(prefs[CAMERA])
  }

  private fun onlineOf(saved: String?, closed: Boolean): MapOnline = when {
    !buildFlag -> MapOnline.Unavailable
    closed || saved == OFF -> MapOnline.Off
    saved == ON -> MapOnline.On(byDefault = false)
    else -> MapOnline.On(byDefault = true)
  }

  /** Aspetta la prima lettura del file (riuscita, o fallita: allora la mappa dettagliata vale spenta). Al ritorno [online], [wifiOnly] e [lastCamera] sono al valore giusto. */
  override suspend fun loaded() {
    loadedState.first { it }
  }

  override suspend fun enable(choice: MapOnlineChoice) {
    if (!buildFlag || stopped.get()) return
    val saved = quietEdit("mappa online") {
      if (stopped.get()) return@quietEdit
      it[ONLINE] = ON
      it.remove(DAMAGED)
    } ?: return
    // Un tocco dell'utente: da qui la scelta e' sua, il guasto di prima non conta piu'.
    unreadable = false
    apply(saved)
  }

  override suspend fun disable() {
    if (!buildFlag || stopped.get()) return
    val saved = quietEdit("mappa online") {
      if (stopped.get()) return@quietEdit
      it[ONLINE] = OFF
      it.remove(DAMAGED)
    } ?: return
    unreadable = false
    apply(saved)
  }

  override suspend fun setWifiOnly(on: Boolean) {
    if (stopped.get()) return
    quietEdit("mappa solo con il Wi-Fi") {
      if (stopped.get()) return@quietEdit
      it[WIFI_ONLY] = on
    }
  }

  override suspend fun saveCamera(spot: CameraSpot?) {
    if (stopped.get()) return
    quietEdit("ultima vista della mappa") { prefs ->
      if (stopped.get()) return@quietEdit
      if (spot == null) prefs.remove(CAMERA) else prefs[CAMERA] = "${spot.center.latE6},${spot.center.lonE6},${spot.zoom}"
    }
  }

  /**
   * OB-4 (review generale): con la memoria piena `DataStore.edit` lancia `IOException`, e chiamata da un tocco dell'utente (un interruttore della mappa) chiudeva l'app. Qui la scrittura
   * che non riesce non salva e non lancia: restituisce null e lo stato in memoria non cambia (l'interruttore resta dov'era). Il risultato e' lo stato salvato quando e' riuscita.
   */
  private suspend fun quietEdit(label: String, transform: (MutablePreferences) -> Unit): Preferences? {
    var saved: Preferences? = null
    QuietWrites.attempt(label) { saved = store.edit { transform(it) } }
    return saved
  }

  /**
   * L'azzeramento (D37, R5): da subito nessun'altra scrittura (anche una gia' in fila trova `stopped` e non fa niente), poi il file si svuota **attraverso DataStore** (che non
   * tollera che gli si tolga il file da sotto: la sua copia in memoria resterebbe, con la vecchia camera, e la prossima scrittura la rimetterebbe sul disco).
   */
  override suspend fun clear() {
    stopped.set(true)
    try {
      store.edit { it.clear() }
    } finally {
      markStopped()
    }
  }

  /** Come [clear] senza toccare il file: per chi nasce dopo l'azzeramento (nessuna scrittura da qui in poi, valori di serie e mappa dettagliata spenta). */
  @Synchronized
  fun markStopped() {
    stopped.set(true)
    onlineState.value = closedOnline()
    wifiState.value = true
    cameraState.value = null
    problemState.value = MapSettingsProblem.NONE
    loadedState.value = true
  }

  override fun toString(): String = "DataStoreMapSettings"

  companion object {
    private const val TAG = "MapSettings"

    /** Ogni quanto si riprova a leggere il file dopo un errore. */
    const val RETRY_MS: Long = 30_000

    private val ONLINE = stringPreferencesKey("online")
    private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    private val CAMERA = stringPreferencesKey("camera")

    /** Il segno "il file si e' rovinato ed e' stato rifatto": la mappa dettagliata resta spenta finche' l'utente non la riaccende. */
    private val DAMAGED = booleanPreferencesKey("damaged")
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

    /** Le preferenze con cui si rifa un file rovinato: **spenta** e il segno del danno (R2: non "di serie", che la riaccenderebbe). */
    internal fun rebuilt(): Preferences = mutablePreferencesOf().also {
      it[ONLINE] = OFF
      it[DAMAGED] = true
    }

    /** Le impostazioni su [file] (un DataStore per file e per processo: `MapGraph` di `:app` ne crea una). [scope] tiene vivo il lettore; [storeScope] e' quello del DataStore (I/O). */
    fun create(
      file: File,
      scope: CoroutineScope,
      buildFlag: Boolean,
      storeScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ): DataStoreMapSettings = DataStoreMapSettings(
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { rebuilt() },
        scope = storeScope,
        produceFile = { file },
      ),
      scope,
      buildFlag,
    )
  }
}