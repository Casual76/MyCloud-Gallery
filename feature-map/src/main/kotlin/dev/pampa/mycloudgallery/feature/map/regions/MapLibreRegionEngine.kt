package dev.pampa.mycloudgallery.feature.map.regions

import android.content.Context
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.RegionEngine
import dev.pampa.mycloudgallery.core.map.contract.RegionEngineFailure
import dev.pampa.mycloudgallery.core.map.contract.RegionEngineListener
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * IL MOTORE DEGLI SCARICAMENTI CON `OfflineManager` DI MAPLIBRE (Fase 9, P5; docs/fase9-progetto.md §2.9, D26, E-MAP11; decisione dell'utente del 31/10: dentro i limiti di OpenFreeMap). L'unico
 * codice che nomina le regioni offline di MapLibre: decide **cosa** scaricare (il riquadro, da z[minZoom] a z[maxZoom], le tessere dell'URL stabile), mai **come** uscire in rete: ogni
 * richiesta passa dal client delle tessere dietro il cancello (`feature-map/.../net/`), con lo scaricamento confermato in corso (`RegionDownloadScope` e il suo conto di 3.000 tessere e
 * 50 MB), al piu' 4 insieme, con lo User-Agent dello scaricamento.
 *
 *  - La regione si scarica con lo stile **`asset://map/style-regione.json`**: solo la sorgente delle tessere (lo stesso modello di URL degli stili veri, quindi le tessere nel database sono quelle
 *    che la mappa poi cerca), senza glifi, sprite ne' carta di base (arrivano dall'APK: niente da scaricare). Il gestore riconosce una regione dai suoi metadati (l'id, mai una zona).
 *  - Prima di partire: MapLibre pronta con il nostro client ([MapEngineStarter], sul thread principale) e il database gia' spostato in `noBackupFilesDir/map/` ([MapLibreInit.awaitCacheReady]);
 *    senza, [RegionEngineFailure.NOT_READY] (si riprova all'apertura). A ogni cambio di stato si riallinea `MapLibre.setConnected` ([MapLibreInit.refreshConnectivity]).
 *  - Gli errori di rete di una tessera non sono guasti: MapLibre li riprova da se' (e il cancello tiene ferme le richieste quando lo scaricamento non e' piu' ammesso); solo una lunga serie
 *    di errori senza un solo progresso e' un [RegionEngineFailure.SERVER].
 * Tutto sul thread principale (lo vuole MapLibre). Nei log solo tipi e numeri.
 */
class MapLibreRegionEngine(
  private val context: Context,
  private val starter: MapEngineStarter,
) : RegionEngine {

  private class Active(val region: OfflineRegion)

  private val active = HashMap<String, Active>()

  override suspend fun start(key: String, box: GeoBox, minZoom: Int, maxZoom: Int, listener: RegionEngineListener) {
    withContext(Dispatchers.Main.immediate) {
      if (!starter.ensure(context)) throw NotReady()
      if (!MapLibreInit.awaitCacheReady()) throw NotReady()
      val manager = OfflineManager.getInstance(context)
      val region = findOrCreate(manager, key, definition(box, minZoom, maxZoom))
      active[key] = Active(region)
      region.setObserver(observerOf(listener))
      region.setDownloadState(OfflineRegion.STATE_ACTIVE)
      MapLibreInit.refreshConnectivity()
    }
  }

  override suspend fun stop(key: String) {
    withContext(Dispatchers.Main.immediate) {
      val region = active.remove(key)?.region ?: return@withContext
      region.setDownloadState(OfflineRegion.STATE_INACTIVE)
      region.setObserver(null)
      MapLibreInit.refreshConnectivity()
    }
  }

  override suspend fun delete(key: String) {
    withContext(Dispatchers.Main.immediate) {
      if (!MapLibreInit.isReady) return@withContext
      val known = active.remove(key)?.region
      known?.setDownloadState(OfflineRegion.STATE_INACTIVE)
      known?.setObserver(null)
      val manager = OfflineManager.getInstance(context)
      val region = known ?: list(manager).firstOrNull { keyOf(it) == key }
      if (region != null) deleteRegion(region)
      MapLibreInit.refreshConnectivity()
    }
  }

  override suspend fun deleteAll() {
    withContext(Dispatchers.Main.immediate) {
      // Se MapLibre non e' partita in questo processo non c'e' niente da togliere: i file li toglie l'azzeramento.
      if (!MapLibreInit.isReady) return@withContext
      for (entry in active.values) {
        entry.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        entry.region.setObserver(null)
      }
      active.clear()
      val manager = OfflineManager.getInstance(context)
      for (region in list(manager)) if (keyOf(region) != null) deleteRegion(region)
      MapLibreInit.refreshConnectivity()
    }
  }

  // --- MapLibre ---------------------------------------------------------------------------------------------------------------

  private fun definition(box: GeoBox, minZoom: Int, maxZoom: Int) = OfflineTilePyramidRegionDefinition(
    STYLE_URL,
    LatLngBounds.from(box.northE6 / 1e6, box.eastE6 / 1e6, box.southE6 / 1e6, box.westE6 / 1e6),
    minZoom.toDouble(),
    maxZoom.toDouble(),
    1f,
    false,
  )

  private fun keyOf(region: OfflineRegion): String? = region.metadata?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)

  private suspend fun list(manager: OfflineManager): List<OfflineRegion> = suspendCancellableCoroutine { done ->
    manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
      override fun onList(offlineRegions: Array<OfflineRegion>?) {
        if (done.isActive) done.resume(offlineRegions?.toList().orEmpty())
      }

      override fun onError(error: String) {
        if (done.isActive) done.resumeWithException(IllegalStateException("regioni non elencate"))
      }
    })
  }

  private suspend fun findOrCreate(manager: OfflineManager, key: String, definition: OfflineTilePyramidRegionDefinition): OfflineRegion {
    list(manager).firstOrNull { keyOf(it) == key }?.let { return it }
    return suspendCancellableCoroutine { done ->
      manager.createOfflineRegion(
        definition,
        key.toByteArray(Charsets.UTF_8),
        object : OfflineManager.CreateOfflineRegionCallback {
          override fun onCreate(offlineRegion: OfflineRegion) {
            if (done.isActive) done.resume(offlineRegion)
          }

          override fun onError(error: String) {
            if (done.isActive) done.resumeWithException(IllegalStateException("regione non creata"))
          }
        },
      )
    }
  }

  private suspend fun deleteRegion(region: OfflineRegion) = suspendCancellableCoroutine<Unit> { done ->
    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
      override fun onDelete() {
        if (done.isActive) done.resume(Unit)
      }

      override fun onError(error: String) {
        // Un errore nel togliere una regione non ferma chi chiama (l'azzeramento toglie comunque i file).
        GalleryLog.w(TAG) { "regione non tolta dal database" }
        if (done.isActive) done.resume(Unit)
      }
    })
  }

  private fun observerOf(listener: RegionEngineListener) = object : OfflineRegion.OfflineRegionObserver {
    private var errorsSinceProgress = 0
    private var lastTiles = -1L
    private var completed = false

    override fun onStatusChanged(status: OfflineRegionStatus) {
      if (status.completedTileCount != lastTiles) errorsSinceProgress = 0
      lastTiles = status.completedTileCount
      listener.onProgress(status.completedTileCount, 0)
      if (status.isComplete && !completed) {
        completed = true
        listener.onComplete()
        MapLibreInit.refreshConnectivity()
      }
    }

    override fun onError(error: OfflineRegionError) {
      // Gli errori di una tessera (rete, cancello che tiene ferma la richiesta) li riprova MapLibre; una lunga serie senza progresso e' un guasto.
      if (++errorsSinceProgress == MAX_ERRORS_WITHOUT_PROGRESS) listener.onFailure(RegionEngineFailure.SERVER)
    }

    override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
  }

  /** Il motore non e' pronto (MapLibre non parte o il suo database non e' in `map/`): il gestore riprova all'apertura. */
  class NotReady : IllegalStateException("scaricamento delle regioni: MapLibre non e' pronta")

  companion object {
    /** Lo stile dello scaricamento: solo la sorgente delle tessere dell'URL stabile (`src/main/assets/map/style-regione.json`). */
    const val STYLE_URL: String = "asset://map/style-regione.json"
    private const val MAX_ERRORS_WITHOUT_PROGRESS = 400
    private const val TAG = "RegionEngine"
  }
}
