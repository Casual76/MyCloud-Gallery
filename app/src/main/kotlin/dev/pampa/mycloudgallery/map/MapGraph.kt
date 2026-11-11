package dev.pampa.mycloudgallery.map

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.SystemClock
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.MapTraffic
import dev.pampa.mycloudgallery.core.map.points.GeoMapPoints
import dev.pampa.mycloudgallery.core.map.regions.FileRegionStateStore
import dev.pampa.mycloudgallery.core.map.regions.OfflineMapRegions
import dev.pampa.mycloudgallery.core.map.points.PagedMapPointSource
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.dataset.PlacesData
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import dev.pampa.mycloudgallery.feature.map.contract.MapMediaPositions
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import dev.pampa.mycloudgallery.feature.map.map.MapNetWiring
import dev.pampa.mycloudgallery.feature.map.mini.MapLibreMiniMap
import dev.pampa.mycloudgallery.feature.map.mini.MapLibrePlacesPreview
import dev.pampa.mycloudgallery.feature.map.places.PlacesPreviewService
import dev.pampa.mycloudgallery.feature.map.regions.MapLibreRegionEngine
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapLook
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapService
import dev.pampa.mycloudgallery.feature.map.settings.DataStoreMapSettings
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * LA MAPPA NELL'APP (Fase 9, P13; docs/fase9-progetto.md §1.4 `MapGraph`, §2.8, §2.10, D21-D31; "per P13" di P1, P3, P4). Nasce con il processo (Hilt) e
 * **non fa niente finche' qualcuno non guarda**: ogni pezzo e' pigro. Le impostazioni (DataStore) si aprono alla prima pagina che le legge, l'indice dei
 * punti alla prima apertura della mappa o della sezione Luoghi (`rebuild(OPEN)`), MapLibre alla prima mappa o mini-mappa ([starter], con il client delle
 * tessere installato prima). Nessun lavoro in background (E-P10): le regioni offline (P5, oggi [NoMapRegions]) scaricano nel processo.
 *
 * Il file nomina [VisibleMedia] e [HiddenMask] (la posizione di un media solo se visibile, il filtro finale della mini-mappa): sta nell'elenco `wiring` del
 * registro `HiddenSurfaces`. Le superfici della mappa (bolle, griglia, Luoghi, mini-mappa) sono la riga `MAP` di quel registro, provata sul catalogo vero.
 * Nei log solo nomi di classi e numeri: mai coordinate, tessere, luoghi.
 */
class MapGraph(
  private val context: Context,
  parent: CoroutineScope,
  private val database: GalleryDatabase,
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
  val places: MapPlaceNames,
  private val catalogVersion: suspend (MediaId) -> String?,
  private val network: () -> StateFlow<NetworkSnapshot>,
  private val ledger: () -> TrafficLedger,
  private val buildFlag: Boolean,
  /** R5: svuota il database di MapLibre con l'API della libreria (se e' partita), prima che i file si tolgano. Le prove la sostituiscono (niente libreria nativa). */
  private val eraseEngine: suspend () -> Boolean = { MapLibreInit.erase() },
  /** P5: i dati dei luoghi (GeoNames sul telefono) per "la zona attorno a un luogo", solo per nome; null = non pronti o non nell'APK. */
  private val placesData: () -> PlacesData? = { null },
  /** P5: lo spazio libero dove stanno i dati (E-MAP12: mai sotto 1 GiB libero) e il giorno (`aaaammgg`) per "completata il" e i 6 mesi. */
  private val freeBytes: () -> Long = { RegionsSystem.freeBytes(context.noBackupFilesDir) },
  private val today: () -> Int = RegionsSystem::today,
  /** P5: quanto aspettare, dopo l'apertura dell'app, prima di riprendere le zone interrotte (dopo il primo disegno: l'avvio resta istantaneo). */
  private val resumeDelayMs: Long = RESUME_DELAY_MS,
) {
  private val job = SupervisorJob(parent.coroutineContext[Job])
  val scope: CoroutineScope = CoroutineScope(parent.coroutineContext + job)
  private val stopped = AtomicBoolean(false)

  /** P5: la ricerca dei luoghi per nome e il loro centro (mai la casa, mai i punti delle foto: C156), sopra i dati dei luoghi che l'app ha gia'. */
  val placeFinder: AppPlaceFinder = AppPlaceFinder(placesData)

  /**
   * Le regioni offline (P5; D26): nascono **alla prima richiesta** (il cancello le legge a ogni richiesta, I3; la pagina "Zone senza Internet"; la ripresa all'apertura), mai prima: leggono il piccolo file di stato in
   * `noBackupFilesDir/map/` e basta, MapLibre parte solo quando uno scaricamento parte davvero. Le prove possono assegnarne altre (`regions = ...`).
   */
  private val regionsHolder = lazy { buildRegions() }

  @Volatile
  private var regionsOverride: MapRegions? = null

  var regions: MapRegions
    get() = regionsOverride ?: regionsHolder.value
    set(value) {
      regionsOverride = value
    }

  private fun buildRegions(): MapRegions = OfflineMapRegions(
    engine = MapLibreRegionEngine(context, starter),
    store = FileRegionStateStore(File(File(context.noBackupFilesDir, MapLibreInit.MAP_DIR), FileRegionStateStore.FILE_NAME)),
    network = networkState,
    settings = settings,
    places = placeFinder,
    freeBytes = freeBytes,
    nowMs = { SystemClock.elapsedRealtime() },
    today = today,
    scope = scope,
  )

  /** Lo spazio libero dove stanno i dati, per la Diagnostica (solo un numero). */
  fun freeSpaceBytes(): Long = freeBytes()

  /** All'apertura dell'app (E-MAP11, nessun lavoro in background): le zone interrotte riprendono da sole sul Wi-Fi, dopo un attimo dal primo disegno. Non fa niente senza zone interrotte. */
  fun resumeRegions() {
    if (stopped.get()) return
    scope.launch {
      delay(resumeDelayMs)
      if (!stopped.get()) regions.resumeInterrupted()
    }
  }

  private val settingsHolder = lazy {
    DataStoreMapSettings.create(DataStoreMapSettings.file(context), scope, buildFlag).also {
      // Nata dopo l'azzeramento (R5): nessuna scrittura, e la mappa dettagliata vale spenta.
      if (stopped.get()) it.markStopped()
    }
  }

  /** Impostazioni -> Mappa (locali, mai sincronizzate): `noBackupFilesDir/map/settings/`, cosi' l'azzeramento di `map/` le toglie con la camera. */
  val settings: MapSettings get() = settingsHolder.value

  val networkState: MapNetworkState by lazy { AppMapNetworkState(network(), scope) }

  val traffic: MapTraffic by lazy { LedgerMapTraffic(ledger, { network().value }, scope) }

  /** Il cancello di P1 con lo stato di adesso (mappa accesa, rete, regioni). Le regioni si rileggono a ogni richiesta (I3): P5 puo' legare [regions] dopo l'avvio di MapLibre. */
  fun wiring(): MapNetWiring = MapNetWiring(TilePolicy, buildFlag, settings, networkState, { regions }, traffic) { regions.downloadScope() }

  /** L'avvio pigro di MapLibre (mai all'avvio dell'app). */
  val starter: MapEngineStarter by lazy { AppMapEngineStarter(::wiring, scope) }

  private val pointsHolder = lazy { WatchedMapPoints(GeoMapPoints(PagedMapPointSource(CatalogMapPointPager(database)), Dispatchers.Default), visible.snapshot, scope) }

  /** I punti: l'indice si fa alla prima apertura; dopo, si rifa' a ogni istantanea nuova e subito a ogni cambio dei nascosti. */
  val points: WatchedMapPoints get() = pointsHolder.value

  /** La posizione **effettiva** di un media **visibile** (la riga `version_metadata`, la stessa del pannello info: E-MAP8), mai di un nascosto. */
  val positions: MapMediaPositions = MapMediaPositions { media -> positionOf(media) }

  /** Il filtro finale dei Nascosti per le copertine e la mini-mappa (D31). */
  val filter: FinalFilter = FinalFilter(mask)

  /** Il colore del segno e il tema della mini-mappa: li aggiorna la radice della UI dal tema (mai colori scritti a mano). */
  @Volatile
  var look: MiniMapLook = MiniMapLook(dark = false, marker = 0, ring = 0, density = context.resources.displayMetrics.density)

  private val miniHolder = lazy { MiniMapService(positions, filter, MapLibreMiniMap(context, starter)) { look } }

  /** La mini-mappa delle info del viewer (stile solo offline: nessuna richiesta, D29, E-MAP16). */
  val miniMap: MiniMapService get() = miniHolder.value

  private val miniBuilt: MiniMapService? get() = if (miniHolder.isInitialized()) miniHolder.value else null

  private val previewHolder = lazy { PlacesPreviewService(points, MapLibrePlacesPreview(context, starter)) }

  /**
   * I1 (fase I): la mappa in anteprima dei Luoghi delle Raccolte (lo stesso stile solo offline della mini-mappa: nessuna richiesta). Nasce alla prima
   * richiesta, cioe' quando la scheda della mappa e' sullo schermo, mai all'avvio.
   */
  val placesPreview: PlacesPreviewService get() = previewHolder.value

  private val previewBuilt: PlacesPreviewService? get() = if (previewHolder.isInitialized()) previewHolder.value else null

  private suspend fun positionOf(media: MediaId): GeoE6? {
    if (stopped.get()) return null
    return try {
      if (media !in visible.visibleOrThrow(listOf(media))) return null
      val version = catalogVersion(media) ?: return null
      val meta = database.versionDao().metadata(version) ?: return null
      val lat = meta.latitude ?: return null
      val lon = meta.longitude ?: return null
      MapPointValidity.toE6(lat, lon)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Un guasto della lettura: nessuna posizione (la riga "Mostra sulla mappa" non c'e'), mai un crollo.
      GalleryLog.w(TAG) { "posizione non letta: ${e.javaClass.simpleName}" }
      null
    }
  }

  /** "Riprova" della sezione Luoghi (MA-1): rifa la lettura dei punti (`rebuild(OPEN)`), fuori dal thread principale. */
  fun retryPoints() {
    if (stopped.get()) return
    scope.launch(Dispatchers.Default) { points.rebuild(RebuildReason.OPEN) }
  }

  /** Un "Nascondi" o "Mostra" fatto qui: l'indice si rifa' subito e la mini-mappa dimentica le immagini. */
  fun onHiddenChanged() {
    if (stopped.get()) return
    pointsIfBuilt()?.hiddenChanged()
    miniBuilt?.clear()
    previewBuilt?.clear()
  }

  /** Il blocco dell'app: nessuna mini-mappa resta in memoria. */
  fun onLocked() {
    miniBuilt?.clear()
    previewBuilt?.clear()
  }

  /** `onTrimMemory` (§1.4 "Memoria"): le mini-mappe in memoria si buttano; MapLibre si rilascia con la sua pagina (le `MapView` seguono il ciclo di vita). */
  fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
      miniBuilt?.clear()
      previewBuilt?.clear()
    }
  }

  /** Per la Diagnostica: lo stato dell'indice se la mappa e' stata aperta in questo processo, altrimenti null (nessuna lettura per la Diagnostica). */
  fun indexIfOpened(): GeoIndexState? = pointsIfBuilt()?.takeIf { it.opened }?.index?.value

  private fun pointsIfBuilt(): WatchedMapPoints? = if (pointsHolder.isInitialized()) pointsHolder.value else null

  /**
   * L'azzeramento (D37), **per primo**: niente si ricostruisce piu', la mini-mappa dimentica, i collettori si fermano e si aspettano. Poi (R5), prima che l'eraser tolga
   * la cartella: il database di MapLibre si svuota con l'API della libreria (se e' partita) e le impostazioni si svuotano **attraverso DataStore** (che non tollera che gli si
   * tolga il file da sotto: la sua copia in memoria, con la vecchia camera, tornerebbe sul disco alla prima scrittura); da qui nessuna scrittura. Un guasto in uno di questi
   * passi non ferma l'azzeramento: i file si tolgono comunque (anche quelli di serie di MapLibre) e nei log resta solo il tipo.
   */
  suspend fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    miniBuilt?.clear()
    previewBuilt?.clear()
    // P5: le regioni per prime (se sono nate in questo processo): lo scaricamento si ferma, le regioni si tolgono dal database e dallo stato, da qui nessuna scrittura. Poi il database intero e le impostazioni.
    if (regionsHolder.isInitialized() || regionsOverride != null) guarded("regioni offline") { regions.clear() }
    job.cancelAndJoin()
    guarded("database della mappa") { if (!eraseEngine()) GalleryLog.w(TAG) { "database della mappa non svuotato dalla libreria: lo tolgono i file" } }
    if (settingsHolder.isInitialized()) guarded("impostazioni della mappa") { (settingsHolder.value as DataStoreMapSettings).clear() }
  }

  private suspend fun guarded(what: String, step: suspend () -> Unit) {
    try {
      step()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "azzeramento: $what non svuotato (${e.javaClass.simpleName})" }
    }
  }

  companion object {
    private const val TAG = "MapGraph"

    /** Dopo l'apertura dell'app, prima di riprendere le zone interrotte: l'avvio e le prime pagine restano istantanei (principio "Avvio e pagine istantanei"). */
    const val RESUME_DELAY_MS: Long = 4_000
  }
}
