package dev.pampa.mycloudgallery.core.map.testing

import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointCounts
import dev.pampa.mycloudgallery.core.map.contract.MapPointSource
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.MapTraffic
import dev.pampa.mycloudgallery.core.map.contract.PlaceCenters
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow

// I fake della mappa (Fase 9, P0, §5.2): rete, impostazioni, punti, traffico, centri dei luoghi. Per le suite di contratto di P0 e per P1, P3, P4,
// P5, P12, P13. Nessun dato vero: punti casuali deterministici dal seme (anche ai bordi: antimeridiano, poli, (0, 0)), luoghi inventati.

/** La rete finta: si cambia con [set] (Wi-Fi, dati mobili, roaming, nessuna). */
class FakeMapNetworkState(initial: MapNetwork = MapNetwork.WIFI) : MapNetworkState {
  private val state = MutableStateFlow(initial)
  override val current: StateFlow<MapNetwork> = state

  fun set(network: MapNetwork) {
    state.value = network
  }
}

/**
 * Le impostazioni della mappa in memoria (D24, **Q1 (c)**): con l'interruttore di build acceso ([buildFlag]) la mappa online e' accesa di serie
 * (`On(byDefault = true)`); [disable] la spegne; [enable] la riaccende solo con la scelta della pagina. "Solo sul Wi-Fi" di serie acceso; la
 * camera solo qui.
 */
class InMemoryMapSettings(private val buildFlag: Boolean = true) : MapSettings {
  private val onlineState = MutableStateFlow<MapOnline>(if (buildFlag) MapOnline.On(byDefault = true) else MapOnline.Unavailable)
  override val online: StateFlow<MapOnline> = onlineState

  private val wifi = MutableStateFlow(true)
  override val wifiOnly: StateFlow<Boolean> = wifi

  private val camera = MutableStateFlow<CameraSpot?>(null)
  override val lastCamera: StateFlow<CameraSpot?> = camera

  /** Le scelte ricevute (per le prove: la versione della nota). */
  val choices: MutableList<MapOnlineChoice> = mutableListOf()

  /** Vero dopo [clear]: da li' le scritture non fanno piu' niente (l'azzeramento). */
  var cleared: Boolean = false
    private set

  override suspend fun enable(choice: MapOnlineChoice) {
    if (cleared) return
    choices += choice
    if (buildFlag) onlineState.value = MapOnline.On(byDefault = false)
  }

  override suspend fun disable() {
    if (cleared) return
    if (buildFlag) onlineState.value = MapOnline.Off
  }

  override suspend fun setWifiOnly(on: Boolean) {
    if (!cleared) wifi.value = on
  }

  override suspend fun saveCamera(spot: CameraSpot?) {
    if (!cleared) camera.value = spot
  }

  /** Come l'azzeramento vero: tutto torna ai valori di serie (la mappa online "spenta") e da qui in poi le scritture non fanno niente. */
  override suspend fun clear() {
    cleared = true
    onlineState.value = if (buildFlag) MapOnline.Off else MapOnline.Unavailable
    wifi.value = true
    camera.value = null
  }
}

/** I punti finti, a pagine come la query vera (D27): solo quelli validi; i non validi si contano. */
class FakeMapPointSource(points: List<MapPoint>, private val withoutPosition: Int = 0) : MapPointSource {
  private val valid = points.filter { MapPointValidity.isValid(it.latE6, it.lonE6) }
  private val invalid = points.size - valid.size

  override fun pages(): Flow<List<MapPoint>> = valid.sortedBy { it.id.value }.chunked(MapPoints.PAGE_SIZE).asFlow()

  override suspend fun counts(): MapPointCounts = MapPointCounts(withoutPosition, invalid)
}

/** Il registro del traffico finto: somma i byte per rete. */
class RecordingMapTraffic : MapTraffic {
  val bytes: MutableMap<MapNetwork, Long> = HashMap()

  override fun record(bytes: Long, network: MapNetwork) {
    this.bytes[network] = (this.bytes[network] ?: 0L) + bytes
  }
}

class FakePlaceCenters(private val centers: Map<Int, GeoE6>, private val urbanIds: Set<Int> = emptySet(), private val names: Map<Int, String> = emptyMap()) : PlaceCenters {
  override fun center(placeId: Int): GeoE6? = centers[placeId]

  override fun urban(placeId: Int): Boolean = placeId in urbanIds

  override fun name(placeId: Int): String? = names[placeId]
}

/**
 * Punti sintetici (Fase 9, P0, §5.1): [count] punti deterministici dal seme, raggruppati attorno a pochi centri inventati (come le foto vere,
 * molte nello stesso posto: E-MAP9) piu' una parte sparsa sul mondo, con i casi ai bordi (antimeridiano, poli oltre +-85, (0, 0)) se
 * [withEdges]. Giorni, punteggi e luoghi casuali.
 */
object SyntheticMapPoints {
  fun generate(count: Int, seed: Long = 11, withEdges: Boolean = true): List<MapPoint> {
    val random = Random(seed)
    val centers = List(6) { GeoE6(random.nextInt(140_000_000) - 70_000_000, random.nextInt(340_000_000) - 170_000_000) }
    val out = ArrayList<MapPoint>(count + 16)
    repeat(count) {
      val (lat, lon) = if (random.nextInt(10) < 8) {
        val c = centers[random.nextInt(centers.size)]
        (c.latE6 + random.nextInt(200_000) - 100_000) to (c.lonE6 + random.nextInt(200_000) - 100_000)
      } else {
        (random.nextInt(2 * 84_000_000) - 84_000_000) to (random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6)
      }
      out += point(random, lat, lon)
    }
    if (withEdges) {
      for ((lat, lon) in listOf(
        0 to 0, 89_000_000 to 10_000_000, -86_000_000 to 0, 10_000_000 to 179_999_999, 10_000_000 to -179_999_999,
        84_999_999 to 180_000_000, -84_999_999 to -180_000_000, 1 to 0, 0 to 1,
      )) {
        out += point(random, lat, lon)
      }
    }
    return out
  }

  private fun point(random: Random, lat: Int, lon: Int): MapPoint = MapPoint(
    MediaId(UUID(random.nextLong(), random.nextLong()).toString()),
    lat,
    lon,
    20_100_101 + random.nextInt(16) * 10_000 + random.nextInt(12) * 100 + random.nextInt(28),
    (random.nextInt(80) - 30).toShort(),
    if (random.nextInt(3) == 0) 0 else 3_000_000 + random.nextInt(20),
  )
}
