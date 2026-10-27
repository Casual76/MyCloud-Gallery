package dev.pampa.mycloudgallery.core.map.noop

import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.DownloadStart
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointCounts
import dev.pampa.mycloudgallery.core.map.contract.MapPointSource
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.MapRequestGate
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.MapTraffic
import dev.pampa.mycloudgallery.core.map.contract.MapVerdict
import dev.pampa.mycloudgallery.core.map.contract.PlaceCenters
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.RegionEstimate
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

// I null-object della mappa (Fase 9, P0), fuori da `testing` e quindi buoni anche per la release: :app li lega finche' P13 non integra i pezzi
// veri. **Falliscono chiusi**: il cancello rifiuta ogni richiesta, nessuna rete, nessuna regione, nessun punto, mappa online non disponibile.

/** Il cancello che rifiuta tutto (`OFF`): e' quello che c'e' finche' P1 non scrive `TilePolicy`. Nessuna richiesta esce mai. */
object RefuseAllRequests : MapRequestGate {
  override fun decide(req: MapRequestFacts, state: MapNetState): MapVerdict = MapVerdict.Refuse(RefuseReason.OFF)
}

object NoMapPointSource : MapPointSource {
  override fun pages(): Flow<List<MapPoint>> = emptyFlow()

  override suspend fun counts(): MapPointCounts = MapPointCounts(0, 0)
}

object NoMapPoints : MapPoints {
  override val index: StateFlow<GeoIndexState> = MutableStateFlow<GeoIndexState>(GeoIndexState.Empty(0)).asStateFlow()

  override suspend fun rebuild(reason: RebuildReason) = Unit
}

/** Nessuna rete per la mappa. */
object NoMapNetworkState : MapNetworkState {
  override val current: StateFlow<MapNetwork> = MutableStateFlow(MapNetwork.NONE).asStateFlow()
}

object NoMapTraffic : MapTraffic {
  override fun record(bytes: Long, network: MapNetwork) = Unit
}

object NoPlaceCenters : PlaceCenters {
  override fun center(placeId: Int): GeoE6? = null
}

/** Nessuna regione, nessuno scaricamento, nessuna tessera coperta. */
object NoMapRegions : MapRegions {
  override val regions: StateFlow<List<RegionState>> = MutableStateFlow(emptyList<RegionState>()).asStateFlow()

  override fun catalog(): List<RegionSpec> = emptyList()

  override fun around(placeId: Int, radius: RegionRadius): RegionSpec? = null

  override suspend fun estimate(spec: RegionSpec): RegionEstimate = RegionEstimate(0, 0)

  override suspend fun download(spec: RegionSpec, confirmation: RegionDownloadConfirmation): DownloadStart = DownloadStart.NeedsWifi

  override suspend fun resume(id: RegionId): DownloadStart = DownloadStart.NeedsWifi

  override suspend fun pauseAll() = Unit

  override suspend fun delete(id: RegionId) = Unit

  override fun covers(tile: TileKey): Boolean = false
}

/** La mappa online non c'e' (come con l'interruttore di build spento): nessuna richiesta; "Solo sul Wi-Fi" acceso; nessuna camera salvata. */
object UnavailableMapSettings : MapSettings {
  override val online: StateFlow<MapOnline> = MutableStateFlow<MapOnline>(MapOnline.Unavailable).asStateFlow()

  override suspend fun enable(choice: MapOnlineChoice) = Unit

  override suspend fun disable() = Unit

  override val wifiOnly: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

  override suspend fun setWifiOnly(on: Boolean) = Unit

  override val lastCamera: StateFlow<CameraSpot?> = MutableStateFlow<CameraSpot?>(null).asStateFlow()

  override suspend fun saveCamera(spot: CameraSpot?) = Unit
}
