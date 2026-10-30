package dev.pampa.mycloudgallery.feature.map.support

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoClusterIndex
import dev.pampa.mycloudgallery.core.map.contract.GeoCluster
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.testing.ReferenceClusterIndex
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I DATI DI PROVA DELLA MAPPA (Fase 9, P4): una Libreria sintetica (`SyntheticLibrary`) con punti **inventati** sulle coordinate di citta' pubbliche (mai un posto della famiglia, mai la casa), i nascosti finti
// (`FakeVisibility`), un servizio dei punti controllabile e i nomi dei luoghi. Nessun dato personale.

/** Citta' pubbliche, in microgradi. */
internal object Cities {
  val FIRENZE = GeoE6(43_769_600, 11_255_800)
  val ROMA = GeoE6(41_902_800, 12_496_400)
  val OSLO = GeoE6(59_913_900, 10_752_200)
  val LISBONA = GeoE6(38_722_300, -9_139_300)
  val AUCKLAND = GeoE6(-36_848_500, 174_763_300)

  /** Dall'altra parte dell'antimeridiano: Suva (Figi, 178 gradi est) e Taveuni (179 gradi ovest). */
  val SUVA = GeoE6(-18_141_600, 178_441_900)
  val TAVEUNI = GeoE6(-16_850_000, -179_950_000)

  val ALL = listOf(FIRENZE, ROMA, OSLO, LISBONA, AUCKLAND)
}

/** Un servizio dei punti finto, controllabile: l'indice si cambia a mano (come una ricostruzione) e le richieste si contano. */
internal class FakeMapPoints(initial: GeoIndexState = GeoIndexState.Building) : MapPoints {
  private val state = MutableStateFlow(initial)
  override val index: StateFlow<GeoIndexState> = state

  val rebuilds: MutableList<RebuildReason> = mutableListOf()

  override suspend fun rebuild(reason: RebuildReason) {
    rebuilds += reason
  }

  fun set(next: GeoIndexState) {
    state.value = next
  }
}

/** Un indice che conta quante volte lo si interroga (per provare che le bolle si ricalcolano solo a camera ferma o al cambio di livello). */
internal class CountingIndex(private val delegate: GeoClusterIndex) : GeoClusterIndex {
  var clusterCalls: Int = 0
    private set

  override val size: Int get() = delegate.size

  override fun clusters(view: GeoBox, zoom: Double, cellPx: Int): List<GeoCluster> {
    clusterCalls++
    return delegate.clusters(view, zoom, cellPx)
  }

  override fun members(key: CellKey, limit: Int): List<MediaId> = delegate.members(key, limit)

  override fun boundsOfAll(): GeoBox? = delegate.boundsOfAll()
}

/** I nomi dei luoghi di prova, dall'id: "Luogo 1", "Luogo 2"... (e, se serve, un nome speciale per un id). */
internal class TestPlaceNames(private val special: Map<Int, String> = emptyMap()) : MapPlaceNames {
  override fun label(placeId: Int): String? = special[placeId] ?: if (placeId == 0) null else "Luogo ${placeId - PLACE_BASE}"
}

internal const val PLACE_BASE = 3_000_000

/**
 * La Libreria di una prova: [count] celle dell'istantanea (0 e' la piu' recente), i luoghi `PLACE_BASE + 1..4`, un punto per ogni cella a partire da una citta' (le celle dalla stessa
 * citta' stanno a pochi metri l'una dall'altra, cosi' fanno una bolla) e la visibilita' finta.
 */
internal class MapRig(val count: Int = 80, cities: List<GeoE6> = Cities.ALL) {
  val library = SyntheticLibrary(seed = 3, count = count, placeIds = IntArray(4) { PLACE_BASE + 1 + it })
  val snapshot: TimelineSnapshot = library.snapshot
  val ids: List<MediaId> = (0 until snapshot.size).map { snapshot.idAt(it) }
  val visibility = FakeVisibility(snapshot, present = ids.toSet())

  /** Il punto di ogni cella: la citta' `i % n` e un piccolo scarto (circa 20 m per passo). */
  val points: List<MapPoint> = ids.mapIndexed { i, id ->
    val city = cities[i % cities.size]
    MapPoint(id, city.latE6 + (i / cities.size) * 150, city.lonE6 + (i / cities.size) * 150, snapshot.localDayAt(i), 0, snapshot.placeAt(i))
  }
  val index: ReferenceClusterIndex = ReferenceClusterIndex(points)
  val ready: GeoIndexState = GeoIndexState.Ready(index, withoutPosition = 5, invalid = 0)

  /** La cella a [level] che contiene il punto del media [i]. */
  fun cellOf(i: Int, level: Int): CellKey = GeoGrid.cellOf(points[i].latE6, points[i].lonE6, level)
}
