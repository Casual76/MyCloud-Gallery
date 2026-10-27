package dev.pampa.mycloudgallery.feature.map.testing

import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapPrivacyNote
import dev.pampa.mycloudgallery.core.map.contract.RegionEstimate
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.testing.SampleRegions
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapBubble
import dev.pampa.mycloudgallery.feature.map.contract.MapNotice
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.PlaceRow
import dev.pampa.mycloudgallery.feature.map.contract.PlacesSectionState
import dev.pampa.mycloudgallery.feature.map.contract.RegionsState
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState

/**
 * Stati di esempio delle schermate della mappa (Fase 9, P0): un esempio per ogni caso del §1.5, per gli screenshot e i test di P4 e P5 prima
 * che ci siano i ViewModel. **Nessun dato personale**: luoghi inventati, celle a coordinate di prova lontane da chiunque, id deterministici.
 */
object MapSamples {
  private val empty = IndexOrderedTimelineView(EmptyTimelineSnapshot, IntArray(0))

  fun media(n: Int): MediaId = MediaId("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")

  private val cell = GeoGrid.cellOf(-45_000_000, 170_000_000, 12)

  val screens: List<MapScreenState> = listOf(
    MapScreenState.Loading,
    MapScreenState.Ready(MapOnline.On(byDefault = true), null, 1_240),
    MapScreenState.Ready(MapOnline.Off, MapNotice.BASE_ONLY, 0),
    MapScreenState.Ready(MapOnline.On(byDefault = false), MapNotice.WIFI_ONLY, 12),
    MapScreenState.Ready(MapOnline.On(byDefault = true), MapNotice.OFFLINE, 12),
    MapScreenState.Empty(320),
  )

  val bubbles: List<MapBubble> = listOf(
    MapBubble.Cluster(cell, 128, media(1), "Luogo di prova"),
    MapBubble.Cluster(cell, 12, media(2), null),
    MapBubble.Single(cell, media(3)),
  )

  val grids: List<ZoneGridState> = listOf(ZoneGridState.Ready("Luogo di prova", empty), ZoneGridState.Ready(null, empty), ZoneGridState.Empty)

  val places: List<PlacesSectionState> = listOf(
    PlacesSectionState.Ready(9_000, List(8) { PlaceRow(3_000_000 + it, "Luogo di prova $it", 900 - it * 50) }, 1_240),
    PlacesSectionState.NoGps(320),
  )

  val privacy: List<MapPrivacyState> = listOf(
    MapPrivacyState.Shown(MapOnline.On(byDefault = true), wifiOnly = true, noteVersion = MapPrivacyNote.VERSION, regions = 0),
    MapPrivacyState.Shown(MapOnline.Off, wifiOnly = false, noteVersion = MapPrivacyNote.VERSION, regions = 2),
    MapPrivacyState.Unavailable,
  )

  val regions: List<RegionsState> = listOf(
    RegionsState.Catalog(SampleRegions.CATALOG, listOf(RegionState(SampleRegions.NORTH, RegionStatus.READY, 100, 100, 200_000, 20260901))),
    RegionsState.Estimate(SampleRegions.SOUTH, RegionEstimate(12_000, 250_000_000), 40L shl 30),
    RegionsState.Downloading(SampleRegions.SOUTH.id, 40),
    RegionsState.Partial(SampleRegions.SOUTH.id, 40),
    RegionsState.Ready(SampleRegions.NORTH.id, 200_000, 56),
    RegionsState.NeedsUpdate(SampleRegions.NORTH.id),
  )
}
