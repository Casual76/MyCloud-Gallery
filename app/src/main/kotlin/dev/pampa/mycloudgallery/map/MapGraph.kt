package dev.pampa.mycloudgallery.map

import android.content.ComponentCallbacks2
import android.content.Context
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
import dev.pampa.mycloudgallery.core.map.noop.NoMapRegions
import dev.pampa.mycloudgallery.core.map.points.GeoMapPoints
import dev.pampa.mycloudgallery.core.map.points.PagedMapPointSource
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import dev.pampa.mycloudgallery.feature.map.contract.MapMediaPositions
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.map.MapNetWiring
import dev.pampa.mycloudgallery.feature.map.mini.MapLibreMiniMap
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapLook
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapService
import dev.pampa.mycloudgallery.feature.map.settings.DataStoreMapSettings
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow

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
) {
  private val job = SupervisorJob(parent.coroutineContext[Job])
  val scope: CoroutineScope = CoroutineScope(parent.coroutineContext + job)
  private val stopped = AtomicBoolean(false)

  /** P5 (regioni offline, in attesa della decisione dell'utente): il punto d'aggancio. Oggi il null-object (nessuna regione, nessuno scaricamento). */
  @Volatile
  var regions: MapRegions = NoMapRegions

  /** Impostazioni -> Mappa (locali, mai sincronizzate): `noBackupFilesDir/map/settings/`, cosi' l'azzeramento di `map/` le toglie con la camera. */
  val settings: MapSettings by lazy { DataStoreMapSettings.create(DataStoreMapSettings.file(context), scope, buildFlag) }

  val networkState: MapNetworkState by lazy { AppMapNetworkState(network(), scope) }

  val traffic: MapTraffic by lazy { LedgerMapTraffic(ledger, { network().value }, scope) }

  /** Il cancello di P1 con lo stato di adesso (mappa accesa, rete, regioni). */
  fun wiring(): MapNetWiring = MapNetWiring(TilePolicy, buildFlag, settings, networkState, regions, traffic) { null }

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

  /** Un "Nascondi" o "Mostra" fatto qui: l'indice si rifa' subito e la mini-mappa dimentica le immagini. */
  fun onHiddenChanged() {
    if (stopped.get()) return
    pointsIfBuilt()?.hiddenChanged()
    miniBuilt?.clear()
  }

  /** Il blocco dell'app: nessuna mini-mappa resta in memoria. */
  fun onLocked() {
    miniBuilt?.clear()
  }

  /** `onTrimMemory` (§1.4 "Memoria"): le mini-mappe in memoria si buttano; MapLibre si rilascia con la sua pagina (le `MapView` seguono il ciclo di vita). */
  fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) miniBuilt?.clear()
  }

  /** Per la Diagnostica: lo stato dell'indice se la mappa e' stata aperta in questo processo, altrimenti null (nessuna lettura per la Diagnostica). */
  fun indexIfOpened(): GeoIndexState? = pointsIfBuilt()?.takeIf { it.opened }?.index?.value

  private fun pointsIfBuilt(): WatchedMapPoints? = if (pointsHolder.isInitialized()) pointsHolder.value else null

  /** L'azzeramento (D37), **per primo**: niente si ricostruisce piu', la mini-mappa dimentica, i collettori si fermano e si aspettano. */
  suspend fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    miniBuilt?.clear()
    job.cancelAndJoin()
  }

  companion object {
    private const val TAG = "MapGraph"
  }
}
