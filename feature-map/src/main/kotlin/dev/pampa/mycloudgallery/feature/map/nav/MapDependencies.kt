package dev.pampa.mycloudgallery.feature.map.nav

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.noop.NoVisibleMedia
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.NoPlaceSearch
import dev.pampa.mycloudgallery.core.map.contract.PlaceSearch
import dev.pampa.mycloudgallery.core.map.noop.NoMapNetworkState
import dev.pampa.mycloudgallery.core.map.noop.NoMapPoints
import dev.pampa.mycloudgallery.core.map.noop.NoMapRegions
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import dev.pampa.mycloudgallery.feature.map.contract.MapMediaPositions
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.contract.NoMapMediaPositions
import dev.pampa.mycloudgallery.feature.map.contract.NoMapPlaceNames
import dev.pampa.mycloudgallery.feature.map.contract.UnboundMapEngine
import dev.pampa.mycloudgallery.feature.map.map.MapLibreSurfaceContent
import dev.pampa.mycloudgallery.feature.map.map.MapSurfaceContent
import dev.pampa.mycloudgallery.feature.map.noop.NoMapSettings
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import java.util.Optional
import javax.inject.Inject

// LE PORTE DELLE SCHERMATE DELLA MAPPA PER DAGGER (Fase 9, P4; D7 della Fase 2: @HiltViewModel; docs/fase9-progetto.md §1.4 `MapGraph`). Tutte **opzionali**, tranne la maschera
// dei Nascosti (R3, 31/10: il filtro finale non puo' tornare muto in silenzio, quindi la chiede senza `Optional` e Hilt la deve avere): `:app` (P13, `MapGraph`) lega i contratti
// veri (i punti sopra `MapPointsQueries`, le impostazioni su DataStore, la rete in uso, le regioni, i nomi dei luoghi, la posizione di un media, i Nascosti, l'avvio di MapLibre); finche' non
// lo fa le schermate partono lo stesso con i null-object, che **non fanno niente di pericoloso** (nessun punto, mappa dettagliata non disponibile, nessuna rete, nessuna mappa vera) e il
// grafo di `:app` compila.

/** I contratti con cui parlano le schermate della mappa, gia' risolti sui null-object quando `:app` non li ha legati. */
class MapDependencies @Inject constructor(
  points: Optional<MapPoints>,
  settings: Optional<MapSettings>,
  regions: Optional<MapRegions>,
  network: Optional<MapNetworkState>,
  places: Optional<MapPlaceNames>,
  positions: Optional<MapMediaPositions>,
  visible: Optional<VisibleMedia>,
  /** I Nascosti: **non opzionale** (R3, 31/10). La maschera la lega sempre `:app` (`GalleryModule`); senza, `:app` non compila. */
  mask: HiddenMask,
  engine: Optional<MapEngineStarter>,
  /** Fase 9 (P13): una superficie gia' pronta al posto di quella vera (solo le prove di `:app` la legano, con `FakeMapSurface`: MapLibre non si disegna in Robolectric). */
  surface: Optional<MapSurfaceContent>,
  /** Fase 9 (P5): la ricerca di un luogo per nome (dataset GeoNames sul telefono) per "la zona attorno a un luogo"; senza, la pagina dice che l'elenco dei luoghi non c'e'. */
  placeSearch: Optional<PlaceSearch> = Optional.empty(),
) {
  val points: MapPoints = points.orElse(NoMapPoints)
  val settings: MapSettings = settings.orElse(NoMapSettings)
  val regions: MapRegions = regions.orElse(NoMapRegions)
  val network: MapNetworkState = network.orElse(NoMapNetworkState)
  val places: MapPlaceNames = places.orElse(NoMapPlaceNames)
  val positions: MapMediaPositions = positions.orElse(NoMapMediaPositions)
  val visible: VisibleMedia = visible.orElse(NoVisibleMedia)

  /** La ricerca dei luoghi per nome ([NoPlaceSearch] se non legata: nessun risultato). */
  val placeSearch: PlaceSearch = placeSearch.orElse(NoPlaceSearch)

  /** Vero se `:app` ha legato la ricerca dei luoghi (altrimenti la pagina dice che non c'e'). */
  val hasPlaceSearch: Boolean = placeSearch.isPresent

  /** Il filtro finale dei Nascosti: la maschera c'e' sempre (un legame che manca e' un errore di compilazione, non un filtro muto). */
  val filter: FinalFilter = FinalFilter(mask)

  /** La mappa vera con MapLibre, che si avvia pigra con [engine] (senza legame: nessuna `MapView`). */
  val content: MapSurfaceContent = surface.orElseGet { MapLibreSurfaceContent(engine.orElse(UnboundMapEngine)) }
}

/** Le porte della mappa, opzionali per Dagger: `:app` le lega ai contratti di core-map e di feature-map. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MapPortsModule {

  @BindsOptionalOf
  abstract fun mapPoints(): MapPoints

  @BindsOptionalOf
  abstract fun mapSettings(): MapSettings

  @BindsOptionalOf
  abstract fun mapRegions(): MapRegions

  @BindsOptionalOf
  abstract fun mapNetworkState(): MapNetworkState

  @BindsOptionalOf
  abstract fun mapPlaceNames(): MapPlaceNames

  @BindsOptionalOf
  abstract fun mapMediaPositions(): MapMediaPositions

  @BindsOptionalOf
  abstract fun visibleMedia(): VisibleMedia

  @BindsOptionalOf
  abstract fun mapEngineStarter(): MapEngineStarter

  @BindsOptionalOf
  abstract fun placeSearch(): PlaceSearch

  /** Mai legata in produzione: la mappa e' sempre quella vera di MapLibre ([MapLibreSurfaceContent]). */
  @BindsOptionalOf
  abstract fun mapSurfaceContent(): MapSurfaceContent
}
