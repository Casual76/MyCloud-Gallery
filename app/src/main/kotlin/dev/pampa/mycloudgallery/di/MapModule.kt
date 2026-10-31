package dev.pampa.mycloudgallery.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.PlaceSearch
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsSectionProvider
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import dev.pampa.mycloudgallery.feature.map.contract.MapMediaPositions
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.settings.map.MapEntryStatus
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.GalleryStopper
import dev.pampa.mycloudgallery.map.AppMapEntryStatus
import dev.pampa.mycloudgallery.map.AppViewerPlaceMap
import dev.pampa.mycloudgallery.map.MapDiagnostics
import dev.pampa.mycloudgallery.map.MapGraph
import dev.pampa.mycloudgallery.map.placesSectionProvider
import javax.inject.Singleton

/**
 * La mappa della **Fase 9** (docs/fase9-progetto.md §1.4 `MapGraph`, P13): un solo [MapGraph] per processo, **pigro in ogni pezzo** (impostazioni, indice dei
 * punti, MapLibre, mini-mappa nascono al primo uso, mai all'avvio). I `@HiltViewModel` di feature-map e feature-settings vedono solo i contratti (porte
 * opzionali di `MapPortsModule` e `MapEntryOptionalModule`, legate qui).
 */
@Module
@InstallIn(SingletonComponent::class)
object MapModule {

  @Provides
  @Singleton
  fun mapGraph(@ApplicationContext context: Context, graph: AppGraph, gallery: GalleryGraph): MapGraph = MapGraph(
    context = context.applicationContext,
    parent = graph.scope,
    database = gallery.database,
    visible = gallery.visibleMedia,
    mask = gallery.hiddenMask,
    places = MapPlaceNames { id -> gallery.places.labelOfId(id) },
    catalogVersion = { id -> gallery.coreIfReady()?.catalog?.media(id)?.currentVersion?.id?.value },
    network = { graph.environment.snapshot },
    ledger = { graph.trafficLedger },
    buildFlag = graph.flags.mapOnline,
    placesData = { gallery.places.opened },
  ).also { map ->
    // L'azzeramento ferma la mappa per prima (§1.4, D37): niente si ricostruisce piu', la mini-mappa dimentica; poi si toglie `map/`.
    graph.mapStopper = GalleryStopper { map.stop() }
    // P3, P4: un "Nascondi" fatto qui rifa' subito l'indice dei punti (se la mappa e' stata aperta) e svuota la mini-mappa.
    gallery.hiddenListeners += { map.onHiddenChanged() }
    // P4: la mini-mappa e "Mostra sulla mappa" nelle info del viewer (MapLibre parte alla prima mini-mappa, mai qui).
    gallery.viewerPlaceMap = AppViewerPlaceMap { map.miniMap }
  }

  @Provides fun viewerPlaceMap(gallery: GalleryGraph, map: MapGraph): AppViewerPlaceMap =
    (gallery.viewerPlaceMap as? AppViewerPlaceMap) ?: AppViewerPlaceMap { map.miniMap }

  @Provides fun mapPoints(map: MapGraph): MapPoints = map.points

  @Provides fun mapSettings(map: MapGraph): MapSettings = map.settings

  /** P5: le regioni offline vere (`OfflineMapRegions` sopra `OfflineManager` di MapLibre), nate alla prima richiesta. */
  @Provides fun mapRegions(map: MapGraph): MapRegions = map.regions

  /** P5: la ricerca di un luogo per nome (GeoNames sul telefono) per "la zona attorno a un luogo". */
  @Provides fun placeSearch(map: MapGraph): PlaceSearch = map.placeFinder

  @Provides fun mapNetworkState(map: MapGraph): MapNetworkState = map.networkState

  @Provides fun mapPlaceNames(map: MapGraph): MapPlaceNames = map.places

  @Provides fun mapMediaPositions(map: MapGraph): MapMediaPositions = map.positions

  @Provides fun mapEngineStarter(map: MapGraph): MapEngineStarter = map.starter

  @Provides
  @Singleton
  fun mapEntryStatus(map: MapGraph, graph: AppGraph): MapEntryStatus = AppMapEntryStatus(map.settings, graph.scope)

  /** La sezione Luoghi delle Raccolte (codice `places`): la riga "Mappa" subito, i conti al primo sguardo (l'indice si fa allora, mai all'avvio). */
  @Provides
  @IntoSet
  @Singleton
  fun placesSection(@ApplicationContext context: Context, map: MapGraph): CollectionsSectionProvider =
    placesSectionProvider(context.applicationContext.resources, map)

  /** La sezione "Mappa" della Diagnostica (§2.12): solo numeri, flag e motivi. */
  @Provides
  @IntoSet
  fun mapDiagnostics(map: MapGraph): DiagnosticsContributor = MapDiagnostics(map)
}
