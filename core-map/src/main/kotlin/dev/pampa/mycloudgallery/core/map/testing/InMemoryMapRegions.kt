package dev.pampa.mycloudgallery.core.map.testing

import dev.pampa.mycloudgallery.core.map.contract.DownloadStart
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.PlaceCenters
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.RegionEstimate
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionKind
import dev.pampa.mycloudgallery.core.map.contract.RegionLimits
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.contract.TileMath
import dev.pampa.mycloudgallery.core.map.noop.NoPlaceCenters
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Un catalogo finto per le prove (Fase 9, P0): zone inventate con riquadri arbitrari. Il catalogo vero (20 regioni, paesi vicini) e' di P5. */
object SampleRegions {
  val NORTH = RegionSpec.Named(RegionId("it-prova-nord"), RegionKind.ITALIAN_REGION, GeoBox(10_000_000, 44_000_000, 11_000_000, 45_000_000))
  val SOUTH = RegionSpec.Named(RegionId("it-prova-sud"), RegionKind.ITALIAN_REGION, GeoBox(15_000_000, 38_000_000, 16_000_000, 39_000_000))
  val COUNTRY = RegionSpec.Named(RegionId("country-prova"), RegionKind.COUNTRY, GeoBox(5_000_000, 45_000_000, 9_000_000, 48_000_000))
  val CATALOG: List<RegionSpec> = listOf(NORTH, SOUTH, COUNTRY)
}

/**
 * Le regioni offline di riferimento (Fase 9, P0, D26, §5.2): catalogo, stime (tessere da z0 a z14 per [bytesPerTile]), regole di rete, spazio e
 * tetti, stati (in corso, parziale, completa, da aggiornare), copertura delle **sole regioni complete**. Lo scaricamento si simula: [advance],
 * [complete], [interrupt] (la morte del processo). Passa `MapRegionsContract`; la regione vera di P5 (sopra `OfflineManager`) la stessa suite, e
 * la sua copertura coincide con le tessere elencate dal database di MapLibre su tre regioni (§5.2).
 */
class InMemoryMapRegions(
  private val network: MapNetworkState,
  private val wifiOnly: () -> Boolean = { true },
  private val nowMs: () -> Long = { 0L },
  private val today: () -> LocalDate = { LocalDate.of(2026, 10, 27) },
  private val centers: PlaceCenters = NoPlaceCenters,
  private val catalog: List<RegionSpec> = SampleRegions.CATALOG,
  private val bytesPerTile: Long = 2_000,
) : MapRegions {

  /** Lo spazio libero del dispositivo (si cambia nelle prove). */
  var freeBytes: Long = 64L shl 30

  private val state = MutableStateFlow<List<RegionState>>(emptyList())
  override val regions: StateFlow<List<RegionState>> = state

  override fun catalog(): List<RegionSpec> = catalog

  override fun around(placeId: Int, radius: RegionRadius): RegionSpec? = centers.center(placeId)?.let { RegionSpec.AroundPlace(placeId, radius, it) }

  override suspend fun estimate(spec: RegionSpec): RegionEstimate {
    val tiles = TileMath.count(spec.box, RegionLimits.MIN_ZOOM, RegionLimits.MAX_ZOOM)
    return RegionEstimate(tiles, tiles * bytesPerTile)
  }

  override suspend fun download(spec: RegionSpec, confirmation: RegionDownloadConfirmation): DownloadStart {
    if (confirmation.region != spec.id || !confirmation.validAt(nowMs())) return DownloadStart.Expired
    val estimate = estimate(spec)
    if (estimate.bytes > RegionLimits.PER_REGION_BYTES) return DownloadStart.TooLarge
    val others = state.value.filter { it.spec.id != spec.id }.sumOf { it.bytes }
    if (others + estimate.bytes > RegionLimits.TOTAL_BYTES) return DownloadStart.TooLarge
    val needed = estimate.bytes + RegionLimits.FREE_RESERVE_BYTES
    if (freeBytes < needed) return DownloadStart.NoSpace(needed)
    when (network.current.value) {
      MapNetwork.WIFI -> Unit
      MapNetwork.MOBILE -> {
        if (wifiOnly()) return DownloadStart.NeedsWifi
        if (!confirmation.mobile) return DownloadStart.NeedsMobileConfirmation(estimate.bytes)
      }
      MapNetwork.ROAMING, MapNetwork.NONE -> return DownloadStart.NeedsWifi
    }
    put(RegionState(spec, RegionStatus.DOWNLOADING, 0, estimate.tiles, 0, null))
    return DownloadStart.Started
  }

  override suspend fun resume(id: RegionId): DownloadStart {
    val region = state.value.firstOrNull { it.spec.id == id } ?: return DownloadStart.Expired
    if (region.status != RegionStatus.PARTIAL) return DownloadStart.Started
    if (network.current.value != MapNetwork.WIFI) return DownloadStart.NeedsWifi
    put(region.copy(status = RegionStatus.DOWNLOADING))
    return DownloadStart.Started
  }

  override suspend fun pauseAll() {
    state.value = state.value.map { if (it.status == RegionStatus.DOWNLOADING) it.copy(status = RegionStatus.PARTIAL) else it }
  }

  override suspend fun delete(id: RegionId) {
    state.value = state.value.filter { it.spec.id != id }
  }

  override fun covers(tile: TileKey): Boolean = state.value.any {
    (it.status == RegionStatus.READY || it.status == RegionStatus.NEEDS_UPDATE) && TileMath.touches(tile, it.spec.box)
  }

  /** Lo scaricamento avanza di [tiles] tessere (le tessere arrivate valgono anche se poi si interrompe, E-MAP11). */
  fun advance(id: RegionId, tiles: Long) = update(id) {
    val done = (it.completedTiles + tiles).coerceAtMost(it.totalTiles)
    it.copy(completedTiles = done, bytes = done * bytesPerTile)
  }

  /** Lo scaricamento finisce: la regione e' completa e le sue tessere non si chiedono piu' online. */
  fun complete(id: RegionId) = update(id) {
    it.copy(status = RegionStatus.READY, completedTiles = it.totalTiles, bytes = it.totalTiles * bytesPerTile, completedDay = dayOf(today()))
  }

  /** Il processo muore a meta' (E-MAP11): la regione resta parziale. */
  fun interrupt(id: RegionId) = update(id) { if (it.status == RegionStatus.DOWNLOADING) it.copy(status = RegionStatus.PARTIAL) else it }

  /** Le regioni complete da oltre 6 mesi diventano "da aggiornare" (solo a mano). */
  fun checkAges() {
    val now = today()
    state.value = state.value.map {
      val day = it.completedDay?.let { d -> LocalDate.of(d / 10_000, (d / 100) % 100, d % 100) }
      if (it.status == RegionStatus.READY && day != null && ChronoUnit.DAYS.between(day, now) > RegionLimits.UPDATE_AFTER_DAYS) {
        it.copy(status = RegionStatus.NEEDS_UPDATE)
      } else {
        it
      }
    }
  }

  private fun put(region: RegionState) {
    state.value = state.value.filter { it.spec.id != region.spec.id } + region
  }

  private fun update(id: RegionId, change: (RegionState) -> RegionState) {
    state.value = state.value.map { if (it.spec.id == id) change(it) else it }
  }

  private fun dayOf(date: LocalDate): Int = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
}
