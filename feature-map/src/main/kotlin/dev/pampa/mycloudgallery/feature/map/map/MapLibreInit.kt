package dev.pampa.mycloudgallery.feature.map.map

import android.content.Context
import android.os.Looper
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
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
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource

/**
 * Le porte che decidono la rete della mappa (Fase 9, P1; D23, D24, D26), legate da :app (P13, `MapGraph`): la decisione pura ([gate] =
 * `TilePolicy`), l'interruttore di build ([buildFlag] = `FeatureFlags.mapOnline`), le scelte di questo dispositivo ([settings]: mappa online,
 * "Solo sul Wi-Fi"), la rete in uso ([network]), le regioni complete (`covers`), il registro del traffico ([traffic]) e lo scaricamento confermato in
 * corso ([downloadScope], di P5; null = nessuno).
 *
 * **Le regioni si leggono a ogni richiesta** (correzioni I3): [regionsNow] e' una funzione, non un oggetto: P5 puo' legare `MapGraph.regions` dopo che MapLibre e'
 * partita e la tessera di una zona scaricata e' `COVERED` dalla richiesta dopo (D26, C155). [cacheFailed] (R5): vero se MapLibre non ha potuto spostare il suo database
 * in `noBackupFilesDir/map/`: nessuna richiesta esce (la cache andrebbe fuori dalla cartella che l'azzeramento toglie).
 */
class MapNetWiring(
  val gate: MapRequestGate,
  val buildFlag: Boolean,
  val settings: MapSettings,
  val network: MapNetworkState,
  private val regionsNow: () -> MapRegions,
  val traffic: MapTraffic,
  private val cacheFailed: () -> Boolean = { false },
  val downloadScope: () -> RegionDownloadScope? = { null },
) {
  /** Come sopra con delle regioni fisse (le prove, e chi non ha regioni che cambiano). L'ultima funzione e' lo scaricamento confermato in corso (null = nessuno). */
  constructor(
    gate: MapRequestGate,
    buildFlag: Boolean,
    settings: MapSettings,
    network: MapNetworkState,
    regions: MapRegions,
    traffic: MapTraffic,
    cacheFailed: () -> Boolean = { false },
    downloadScope: () -> RegionDownloadScope? = { null },
  ) : this(gate, buildFlag, settings, network, { regions }, traffic, cacheFailed, downloadScope)

  /** La stessa cosa con in piu' un guasto da controllare a ogni richiesta (la cache fuori posto, l'azzeramento): lo mette `MapLibreInit.ensure`. */
  internal fun withCacheGuard(failed: () -> Boolean): MapNetWiring =
    MapNetWiring(gate, buildFlag, settings, network, regionsNow, traffic, { cacheFailed() || failed() }, downloadScope)

  /** Le regioni di adesso (mai tenute: P5 le lega dopo l'avvio). */
  fun regions(): MapRegions = regionsNow()

  /** La mappa online permessa adesso: interruttore di build, scelta dell'utente (che e' "spenta" finche' il file non si e' letto bene) e cache al suo posto. */
  fun onlineNow(): Boolean = buildFlag && settings.online.value is MapOnline.On && !cacheFailed()

  /** Lo stato per il cancello, letto adesso (ogni richiesta lo rilegge: spegnere la mappa vale dalla richiesta dopo). */
  fun state(currentVersion: String?): MapNetState = MapNetState(
    online = onlineNow(),
    network = network.current.value,
    mobileAllowed = !settings.wifiOnly.value,
    covered = { tile -> regionsNow().covers(tile) },
    currentVersion = currentVersion,
    download = activeDownload(),
  )

  /**
   * Lo scaricamento confermato in corso, ma solo con l'interruttore di build acceso: spento, il cancello rifiuta **ogni** richiesta, anche quelle
   * di una regione (`FeatureFlags.mapOnline`, D24). Con la cache fuori posto ([cacheFailed]) nemmeno quello.
   */
  fun activeDownload(): RegionDownloadScope? = if (buildFlag && !cacheFailed()) downloadScope() else null

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

  /** Il nome (e il prefisso dei suoi `-journal`, `-wal`, `-shm`) del database di MapLibre: l'azzeramento lo toglie anche dalle cartelle di serie della libreria (R5, seconda difesa). */
  const val DATABASE_PREFIX: String = "mbgl-offline.db"

  /** Al piu' quanto si aspetta che MapLibre svuoti il suo database prima che l'azzeramento tolga i file (poi ci pensano i file). */
  const val ERASE_WAIT_MS: Long = 5_000

  @Volatile
  private var http: MapTileHttp? = null

  @Volatile
  private var wiring: MapNetWiring? = null

  @Volatile
  private var appContext: Context? = null

  /** Vero dopo l'azzeramento: da li' nessuna richiesta esce piu' (il database e' stato svuotato e la cartella tolta). */
  @Volatile
  private var erased = false

  private val cacheFailedState = MutableStateFlow(false)

  /**
   * Vero se MapLibre **non ha potuto** spostare il suo database in `noBackupFilesDir/map/` (R5): la cache delle tessere starebbe fuori dalla cartella che l'azzeramento toglie. Fallisce
   * chiusa: nessuna richiesta esce ([MapNetWiring.onlineNow]), `setConnected(false)`, e la mappa grande resta sullo stile offline (il controllore lo legge). Un codice in Diagnostica
   * (`MAP_CACHE_UNMOVED`).
   */
  val cacheFailed: StateFlow<Boolean> = cacheFailedState.asStateFlow()

  /** Vero dopo [ensure]: nessuna `MapView` va creata prima (il client non sarebbe il nostro). */
  val isReady: Boolean get() = http != null

  /** Il client installato (per i test strumentati, che ne derivano uno con DNS e socket che contano). */
  internal val tileHttp: MapTileHttp? get() = http

  /** Il guasto della cache e l'azzeramento, per la rete: la stessa regola per il cancello e per `setConnected`. */
  private fun closed(): Boolean = cacheFailedState.value || erased

  /**
   * Prepara MapLibre con il nostro client (idempotente: la seconda chiamata non fa niente). Sul thread principale (lo vuole MapLibre). [scope]
   * vive quanto il processo (quello dell'app): tiene `setConnected` allineato a scelte e rete.
   */
  fun ensure(context: Context, wiring: MapNetWiring, scope: CoroutineScope) {
    check(Looper.myLooper() == Looper.getMainLooper()) { "MapLibreInit.ensure va chiamato sul thread principale." }
    if (http != null) return
    val app = context.applicationContext
    val mapDir = File(app.noBackupFilesDir, MAP_DIR)
    // La cartella della mappa si crea prima di tutto: se non si puo', il database di MapLibre resterebbe fuori e la mappa non esce in rete (fallisce chiusa).
    val dirOk = mapDir.isDirectory || mapDir.mkdirs()
    if (!dirOk) cacheFailedState.value = true
    val guarded = wiring.withCacheGuard(::closed)
    val tiles = MapTileHttp(
      gate = guarded.gate,
      conditions = guarded::state,
      traffic = guarded.traffic,
      network = { guarded.network.current.value },
      stateFile = File(mapDir, MapTileHttp.STATE_FILE),
      clock = MapTileHttp.deviceClock(app.contentResolver),
    )
    Logger.setVerbosity(Logger.NONE)
    MapLibre.getInstance(app)
    tiles.install()
    appContext = app
    this.wiring = guarded
    http = tiles
    refreshConnectivity()
    if (dirOk) moveCache(mapDir, scope)
    scope.launch(Dispatchers.Main.immediate) {
      combine(guarded.settings.online, guarded.settings.wifiOnly, guarded.network.current, cacheFailedState) { _, _, _, _ -> Unit }.collect { refreshConnectivity() }
    }
  }

  /** `setResourcesCachePath` con un callback vero: un errore chiude la rete e resta in Diagnostica (R5). */
  private fun moveCache(mapDir: File, scope: CoroutineScope) {
    val fail = {
      // Del guasto niente (il messaggio puo' nominare un percorso): solo che il database non si e' spostato.
      GalleryLog.w(TAG) { "database della mappa non spostato in map/: la mappa resta sullo stile offline" }
      cacheFailedState.value = true
      scope.launch(Dispatchers.Main.immediate) { refreshConnectivity() }
      Unit
    }
    try {
      FileSource.setResourcesCachePath(
        mapDir.absolutePath,
        object : FileSource.ResourcesCachePathChangeCallback {
          override fun onSuccess(path: String) = Unit

          override fun onError(message: String) = fail()
        },
      )
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "database della mappa non spostato (${e.javaClass.simpleName})" }
      fail()
    }
  }

  /** Riallinea `MapLibre.setConnected` (P5 la chiama quando uno scaricamento confermato comincia o finisce). Sul thread principale. */
  fun refreshConnectivity() {
    val w = wiring ?: return
    val online = w.onlineNow()
    MapLibre.setConnected(MapConnectivity.connected(online, w.settings.wifiOnly.value, w.network.current.value, w.activeDownload()))
  }

  /**
   * L'azzeramento (D37, R5): se MapLibre e' partita in questo processo, **da qui nessuna richiesta esce piu'** e il suo database (le tessere viste, le regioni) si svuota **con l'API della
   * libreria** (`OfflineManager.resetDatabase`), prima che i file si tolgano da sotto. Vero se non c'era niente da fare o se e' riuscito; falso (e basta: ci pensano i file, anche quelli di serie
   * di MapLibre, [DATABASE_PREFIX]) se MapLibre non risponde entro [ERASE_WAIT_MS] o dice di no.
   */
  suspend fun erase(): Boolean {
    // Da qui nessuna richiesta esce piu' (il cancello e `setConnected` leggono [closed]), prima ancora che il database si svuoti.
    if (appContext != null) erased = true
    return eraseWith(appContext, ::resetNativeDatabase)
  }

  /** [erase] con la porta verso la libreria nativa sostituibile (le prove JVM non hanno MapLibre). [context] null = MapLibre non e' partita. */
  internal suspend fun eraseWith(context: Context?, reset: suspend (Context) -> Boolean): Boolean {
    if (context == null) return true
    return try {
      withTimeoutOrNull(ERASE_WAIT_MS) { reset(context) } ?: false
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "database della mappa non svuotato (${e.javaClass.simpleName})" }
      false
    }
  }

  private suspend fun resetNativeDatabase(context: Context): Boolean = withContext(Dispatchers.Main.immediate) {
    refreshConnectivity()
    suspendCancellableCoroutine { done ->
      OfflineManager.getInstance(context).resetDatabase(
        object : OfflineManager.FileSourceCallback {
          override fun onSuccess() {
            if (done.isActive) done.resume(true)
          }

          override fun onError(message: String) {
            if (done.isActive) done.resume(false)
          }
        },
      )
    }
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

  private const val TAG = "MapLibreInit"
}