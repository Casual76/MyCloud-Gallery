package dev.pampa.mycloudgallery.feature.map.map

import android.content.Context
import android.os.Looper
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapRequestGate
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.MapTraffic
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadScope
import dev.pampa.mycloudgallery.feature.map.net.MapTileHttp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.storage.FileSource

/**
 * Le porte che decidono la rete della mappa (Fase 9, P1; D23, D24, D26), legate da :app (P13, `MapGraph`): la decisione pura ([gate] =
 * `TilePolicy`), l'interruttore di build ([buildFlag] = `FeatureFlags.mapOnline`), le scelte di questo dispositivo ([settings]: mappa online,
 * "Solo sul Wi-Fi"), la rete in uso ([network]), le regioni complete ([regions], `covers`), il registro del traffico ([traffic]) e lo
 * scaricamento confermato in corso ([downloadScope], di P5; null = nessuno).
 */
class MapNetWiring(
  val gate: MapRequestGate,
  val buildFlag: Boolean,
  val settings: MapSettings,
  val network: MapNetworkState,
  val regions: MapRegions,
  val traffic: MapTraffic,
  val downloadScope: () -> RegionDownloadScope? = { null },
) {
  /** Lo stato per il cancello, letto adesso (ogni richiesta lo rilegge: spegnere la mappa vale dalla richiesta dopo). */
  fun state(currentVersion: String?): MapNetState = MapNetState(
    online = buildFlag && settings.online.value is MapOnline.On,
    network = network.current.value,
    mobileAllowed = !settings.wifiOnly.value,
    covered = regions::covers,
    currentVersion = currentVersion,
    download = downloadScope(),
  )

  override fun toString(): String = "MapNetWiring(buildFlag=$buildFlag)"
}

/** I numeri del client delle tessere per la Diagnostica (P13, `MapDiag`): solo numeri, mai tessere, mai `x` e `y`. */
data class MapNetCounts(
  val allowed: Long,
  val allowedByZoom: List<Long>,
  val bytes: Long,
  val refused: Map<RefuseReason, Long>,
  val errors: Long,
  val paused: Long,
  val withoutVersion: Long,
  val tileJsonFetches: Long,
)

/** Quando MapLibre deve credersi "in rete" (D23, verifica (7) del §2.7): con la mappa online permessa adesso, o con uno scaricamento confermato. */
object MapConnectivity {
  fun connected(online: Boolean, wifiOnly: Boolean, network: MapNetwork, download: RegionDownloadScope?): Boolean = when (network) {
    MapNetwork.ROAMING, MapNetwork.NONE -> false
    MapNetwork.WIFI -> online || download != null
    MapNetwork.MOBILE -> (online && !wifiOnly) || download?.mobileConfirmed == true
  }
}

/**
 * L'INIZIALIZZAZIONE DI MAPLIBRE (Fase 9, P1; docs/fase9-progetto.md D21, D23, §1.4 `MapGraph`, §2.7 verifiche (3), (4), (6), (7), (8)).
 * **Pigra**: la chiama la prima schermata che mostra una mappa (o la mini-mappa), mai l'avvio dell'app (il principio "Avvio e pagine
 * istantanei": MapLibre non sta sul percorso dell'avvio). In quest'ordine, sul thread principale, una volta per processo:
 *  1. i log di MapLibre spenti del tutto (`Logger.NONE`: anche quelli del codice nativo passano di li', e direbbero quali tessere mancano);
 *  2. `MapLibre.getInstance` **senza chiave** (nessun account, nessuna telemetria, nessuna richiesta: verifica (4) di P1). Deve venire prima del
 *     client: la classe delle richieste di MapLibre si inizializza leggendo il contesto di MapLibre (provato sull'emulatore il 30/10);
 *  3. il **nostro client** ([MapTileHttp], con il cancello) dentro MapLibre e i log di rete di MapLibre spenti: da qui ogni richiesta passa dal
 *     cancello. Nessuna mappa e' ancora nata ([mapOptions] la vieta prima di [isReady]), quindi nessuna richiesta e' passata da altri client;
 *  4. `MapLibre.setConnected`: falso se la mappa online e' spenta, in roaming, senza rete o sui dati mobili con "Solo sul Wi-Fi" (verifica (7)),
 *     tenuto aggiornato finche' il processo vive; il cancello resta comunque il controllo che conta, richiesta per richiesta;
 *  5. il database di MapLibre (cache e regioni) in `noBackupFilesDir/map/` (verifica (6)).
 * Le opzioni di ogni mappa ([mapOptions], [configure]) spengono il precaricamento dei livelli piu' bassi (`prefetchZoomDelta = 0`, che in
 * MapLibre 13 sostituisce `prefetchesTiles`) e usano la `SurfaceView` (D21).
 */
object MapLibreInit {

  /** La cartella della mappa in `noBackupFilesDir` (D26, D37: la toglie l'azzeramento). */
  const val MAP_DIR: String = "map"

  @Volatile
  private var http: MapTileHttp? = null

  @Volatile
  private var wiring: MapNetWiring? = null

  /** Vero dopo [ensure]: nessuna `MapView` va creata prima (il client non sarebbe il nostro). */
  val isReady: Boolean get() = http != null

  /** Il client installato (per i test strumentati, che ne derivano uno con DNS e socket che contano). */
  internal val tileHttp: MapTileHttp? get() = http

  /**
   * Prepara MapLibre con il nostro client (idempotente: la seconda chiamata non fa niente). Sul thread principale (lo vuole MapLibre). [scope]
   * vive quanto il processo (quello dell'app): tiene `setConnected` allineato a scelte e rete.
   */
  fun ensure(context: Context, wiring: MapNetWiring, scope: CoroutineScope) {
    check(Looper.myLooper() == Looper.getMainLooper()) { "MapLibreInit.ensure va chiamato sul thread principale." }
    if (http != null) return
    val app = context.applicationContext
    val mapDir = File(app.noBackupFilesDir, MAP_DIR)
    val tiles = MapTileHttp(
      gate = wiring.gate,
      conditions = wiring::state,
      traffic = wiring.traffic,
      network = { wiring.network.current.value },
      stateFile = File(mapDir, MapTileHttp.STATE_FILE),
      clock = MapTileHttp.deviceClock(app.contentResolver),
    )
    Logger.setVerbosity(Logger.NONE)
    MapLibre.getInstance(app)
    tiles.install()
    this.wiring = wiring
    http = tiles
    refreshConnectivity()
    if (mapDir.isDirectory || mapDir.mkdirs()) FileSource.setResourcesCachePath(mapDir.absolutePath, NoCallback)
    scope.launch(Dispatchers.Main.immediate) {
      combine(wiring.settings.online, wiring.settings.wifiOnly, wiring.network.current) { _, _, _ -> Unit }.collect { refreshConnectivity() }
    }
  }

  /** Riallinea `MapLibre.setConnected` (P5 la chiama quando uno scaricamento confermato comincia o finisce). Sul thread principale. */
  fun refreshConnectivity() {
    val w = wiring ?: return
    val online = w.buildFlag && w.settings.online.value is MapOnline.On
    MapLibre.setConnected(MapConnectivity.connected(online, w.settings.wifiOnly.value, w.network.current.value, w.downloadScope()))
  }

  /** Le opzioni di ogni mappa: `SurfaceView` (non `TextureView`), niente precaricamento dei livelli piu' bassi. Dopo [ensure]. */
  fun mapOptions(context: Context): MapLibreMapOptions {
    check(isReady) { "MapLibreInit.ensure prima di ogni mappa." }
    return MapLibreMapOptions.createFromAttributes(context)
      .textureMode(false)
      .setPrefetchZoomDelta(0)
  }

  /** Da chiamare su ogni `MapLibreMap` appena pronta: il precaricamento resta spento anche se le opzioni non sono passate di qui. */
  fun configure(map: MapLibreMap) {
    map.prefetchZoomDelta = 0
  }

  /** I numeri del client (zero prima di [ensure]). */
  fun counts(): MapNetCounts {
    val snapshot = http?.snapshot() ?: return MapNetCounts(0, List(MapHost.MAX_ZOOM + 1) { 0L }, 0, RefuseReason.entries.associateWith { 0L }, 0, 0, 0, 0)
    return MapNetCounts(
      allowed = snapshot.allowed,
      allowedByZoom = snapshot.byZoom,
      bytes = snapshot.bytes,
      refused = snapshot.refused,
      errors = snapshot.errors,
      paused = snapshot.paused,
      withoutVersion = snapshot.noVersion,
      tileJsonFetches = snapshot.tileJsonFetches,
    )
  }

  private object NoCallback : FileSource.ResourcesCachePathChangeCallback {
    override fun onSuccess(path: String) = Unit

    override fun onError(message: String) = Unit
  }
}
