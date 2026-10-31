package dev.pampa.mycloudgallery.hidden

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.database.dao.MapPointsQueries
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoClusterIndex
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.points.GeoMapPoints
import dev.pampa.mycloudgallery.core.map.points.MapPointPager
import dev.pampa.mycloudgallery.core.map.points.MapPointRaw
import dev.pampa.mycloudgallery.core.map.points.PagedMapPointSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.integration.Attese
import dev.pampa.mycloudgallery.feature.map.contract.MapMediaPositions
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.contract.PlacesSectionState
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridController
import dev.pampa.mycloudgallery.feature.map.grid.ZoneTarget
import dev.pampa.mycloudgallery.feature.map.map.BubbleEngine
import dev.pampa.mycloudgallery.feature.map.map.MapViewport
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapLook
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapRenderer
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapService
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapState
import dev.pampa.mycloudgallery.feature.map.places.PlacesStates
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

// Fase 9 (P4, D31, E-MAP5, E-MAP7, E-MAP15, E-MAP18, E-H9; 30/10): le SONDE della riga `MAP` del registro dei Nascosti. La **catena vera dei punti** -- la query di `core-database` (`MapPointsQueries`), la lettura a
// pagine (`PagedMapPointSource`) e il servizio (`GeoMapPoints`), cioe' quello che `:app` montera' in P13 -- sul **catalogo vero** del mondo casuale, e le **schermate vere** di feature-map sopra (bolle, griglia di una
// zona, sezione Luoghi, mini-mappa del viewer). Le coordinate e i luoghi sono inventati (citta' pubbliche, `Cities`), con qualche media senza posizione e qualche coordinata assurda ((0, 0), oltre 85 gradi: scartate e
// contate). La storia: l'indice dei punti e' costruito; **dopo**, il catalogo nasconde (con la sola scrittura in `media_personal`, senza che indice e istantanea lo sappiano) le copertine delle bolle piu' grandi e un
// elemento in mezzo; ogni superficie deve toglierli. L'oracolo viene dalla **descrizione** del mondo (`HiddenOracle` con gli ultimi nascosti in piu'), mai dal codice che si prova. Poi si segnala il cambio dei nascosti
// (`HIDDEN_CHANGED`) e l'indice rifatto dalla query deve avere **conteggi esatti**. La prova gira su una copia privata del mondo (come per i viaggi e i ricordi) e restituisce gli id del mondo originale.

internal class MapReport(val points: SurfaceReport, val zone: SurfaceReport, val places: SurfaceReport, val minimap: SurfaceReport)

/** Quello che la prova ha davvero guardato: una sonda che non vede mai una bolla o una griglia sarebbe un controllo che passa sempre. */
internal object MapProbeStats {
  var worlds = 0
  var bubbles = 0
  var victims = 0
  var zones = 0
  var zoneVictims = 0
  var minimaps = 0
  var invalid = 0
  var withoutPosition = 0
  var placeRows = 0

  fun reset() {
    worlds = 0
    bubbles = 0
    victims = 0
    zones = 0
    zoneVictims = 0
    minimaps = 0
    invalid = 0
    withoutPosition = 0
    placeRows = 0
  }
}

internal object RealMapProbes {

  private val cities = listOf(GeoE6(43_769_600, 11_255_800), GeoE6(41_902_800, 12_496_400), GeoE6(59_913_900, 10_752_200), GeoE6(38_722_300, -9_139_300), GeoE6(-36_848_500, 174_763_300))
  private const val PLACE_BASE = 3_100_000

  /** La posizione inventata del media [i]: null = senza posizione; poi due tipi di coordinata assurda (scartate e contate, E-MAP7); altrimenti vicino a una citta' pubblica (e a chi sta nella stessa). */
  private fun coordOf(i: Int): Pair<Double, Double>? = when {
    i % 7 == 3 -> null
    i % 13 == 5 -> 0.0 to 0.0
    i % 17 == 9 -> 89.0 to 10.0
    else -> cities[i % cities.size].let { (it.latE6 + (i / cities.size) * 300) / 1e6 to (it.lonE6 + (i / cities.size) * 300) / 1e6 }
  }

  private fun placeOf(i: Int): Int = PLACE_BASE + 1 + i % 3

  private fun isValid(i: Int): Boolean = coordOf(i)?.let { (lat, lon) -> dev.pampa.mycloudgallery.core.map.contract.MapPointValidity.toE6(lat, lon) != null } == true

  private val cache: MutableMap<BuiltWorld, MapReport> = Collections.synchronizedMap(WeakHashMap())

  private class Names : MapPlaceNames {
    override fun label(placeId: Int): String? = "Luogo ${placeId - PLACE_BASE}"
  }

  /** La query vera dietro la porta di lettura, come la scrivera' `:app` (senza logica: i codici di sorgente e di tipo tornano enumerazioni). */
  private class QueryPager(private val queries: MapPointsQueries) : MapPointPager {
    override suspend fun page(after: MediaId?, limit: Int): List<MapPointRaw> = queries.page(after, limit).map {
      MapPointRaw(
        MediaId(it.mediaId), it.latitude, it.longitude, it.localDay, it.cellFlags, enumByCode<MediaSource>(it.source), enumByCode<MediaKind>(it.kind), it.durationMs, it.placeId,
      )
    }

    override suspend fun countWithoutPosition(): Int = queries.countWithoutPosition()

    private inline fun <reified E : Enum<E>> enumByCode(code: String): E = enumValues<E>().first { (it as? dev.pampa.mycloudgallery.core.model.StableCode)?.code == code }
  }

  /** Un servizio dei punti fermo su un indice: l'indice "di prima" (quello che il catalogo ha gia' cambiato sotto). */
  private class Frozen(index: GeoClusterIndex, without: Int) : MapPoints {
    override val index: StateFlow<GeoIndexState> = MutableStateFlow(GeoIndexState.Ready(index, without, 0))

    override suspend fun rebuild(reason: RebuildReason) = Unit
  }

  private val WORLD = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)

  suspend fun report(original: BuiltWorld, finalFilter: Boolean = true, remember: Boolean = true): MapReport {
    if (remember) cache[original]?.let { return it }
    val context = ApplicationProvider.getApplicationContext<Context>()
    val own = HiddenWorldBuilder.build(context, original.spec)
    val merged = MapReport(SurfaceReport(), SurfaceReport(), SurfaceReport(), SurfaceReport())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      write(own)
      val one = evaluate(own, finalFilter, scope)
      for ((from, into) in listOf(one.points to merged.points, one.zone to merged.zone, one.places to merged.places, one.minimap to merged.minimap)) {
        into.shown += from.shown.map { original.ids[own.indexOf(it)] }
        into.problems += from.problems
      }
    } finally {
      scope.cancel()
      own.close()
    }
    if (remember) cache[original] = merged
    return merged
  }

  /** Le coordinate e i luoghi nel catalogo vero della copia (la riga `version_metadata` della versione corrente e `media.place_id`: da dove li legge la query). */
  private suspend fun write(world: BuiltWorld) {
    val db = world.db.openHelper.writableDatabase
    for ((i, id) in world.ids.withIndex()) {
      val version = checkNotNull(world.catalog.media(id)).currentVersionId.value
      coordOf(i)?.let { (lat, lon) -> db.execSQL("UPDATE version_metadata SET latitude = ?, longitude = ? WHERE version_id = ?", arrayOf<Any?>(lat, lon, version)) }
      db.execSQL("UPDATE media SET place_id = ? WHERE media_id = ?", arrayOf<Any?>(placeOf(i), id.value))
    }
  }

  private suspend fun evaluate(world: BuiltWorld, finalFilter: Boolean, scope: CoroutineScope): MapReport {
    val report = MapReport(SurfaceReport(), SurfaceReport(), SurfaceReport(), SurfaceReport())
    val service = GeoMapPoints(PagedMapPointSource(QueryPager(MapPointsQueries(world.db))), Dispatchers.Unconfined)
    service.rebuild(RebuildReason.OPEN)
    val stale = service.index.value as? GeoIndexState.Ready
    // La Libreria con pochi punti validi puo' non avere nessuna bolla: le sonde dicono comunque la verita' (niente da mostrare = niente di nascosto).
    val mask: HiddenMask = CatalogHiddenMask(world.db)
    // Senza il filtro finale (la mutazione che la prova deve vedere): la maschera che non nasconde niente, perche' `FinalFilter` non ne ammette l'assenza (R3).
    val filter = FinalFilter(if (finalFilter) mask else HiddenMask { emptySet() })
    val victims = if (stale == null) emptySet() else hideSome(world, stale.index)
    MapProbeStats.worlds++
    MapProbeStats.victims += victims.size
    val oracle = HiddenOracle(
      WorldSpec(world.spec.seed, world.spec.media, world.spec.groups, world.spec.hiddenByUser, world.spec.hiddenPartial, world.spec.favorites, world.spec.lateHidden + victims, world.spec.sameSideCopies),
    )
    val forbidden = world.idsOf(oracle.liveMustNeverShow)
    if (stale != null) {
      bubbles(world, stale.index, filter, forbidden, report.points)
      zones(world, stale, filter, forbidden, scope, report.zone)
    }
    // Il cambio dei nascosti: l'indice rifatto dalla query ha i conteggi esatti, e i luoghi pure.
    service.rebuild(RebuildReason.HIDDEN_CHANGED)
    exactCounts(world, service.index.value, oracle, report)
    minimaps(world, filter, forbidden, oracle, report.minimap)
    return report
  }

  /** Dopo l'indice, il catalogo nasconde la copertina delle bolle piu' grandi e un elemento in mezzo (senza che l'indice lo sappia): le vittime, in indici dello spec. */
  private suspend fun hideSome(world: BuiltWorld, index: GeoClusterIndex): Set<Int> {
    val clusters = index.clusters(WORLD, FINEST_ZOOM_FOR_VICTIMS, BubbleEngine.CELL_DP).sortedByDescending { it.count }
    val ids = LinkedHashSet<MediaId>()
    for (cluster in clusters.take(4)) ids += cluster.cover
    clusters.firstOrNull { it.count > 2 }?.let { big -> index.members(big.key, 16).let { if (it.size > 2) ids += it[it.size / 2] } }
    var at = 0L
    for (id in ids) world.db.personalDao().upsert(MediaPersonalEntity(id.value, favorite = false, hidden = true, updatedAt = 9_000_000_000L + at++))
    return ids.map { world.indexOf(it) }.toSet()
  }

  private const val FINEST_ZOOM_FOR_VICTIMS = 9.0

  /** Le bolle di piu' viste (il mondo e quattro citta', a quattro zoom): nessuna copertina nascosta. */
  private suspend fun bubbles(world: BuiltWorld, index: GeoClusterIndex, filter: FinalFilter, forbidden: Set<MediaId>, into: SurfaceReport) {
    val engine = BubbleEngine(Names(), filter)
    val views = buildList {
      for (zoom in listOf(2.0, 5.0)) add(MapViewport(GeoE6(30_000_000, 10_000_000), zoom, WORLD, 412, 892))
      for (city in cities.take(3)) for (zoom in listOf(8.0, 11.0)) {
        add(MapViewport(city, zoom, GeoBox(city.lonE6 - 1_000_000, city.latE6 - 1_000_000, city.lonE6 + 1_000_000, city.latE6 + 1_000_000), 412, 892))
      }
    }
    for (view in views) {
      for (bubble in engine.compute(index, view) { world.snapshot.indexOf(it) >= 0 }) {
        MapProbeStats.bubbles++
        into.shown += bubble.bubble.cover
        if (bubble.bubble.cover in forbidden) into.problems += "una bolla ha per copertina un media nascosto o di un gruppo nascosto (zoom ${view.zoom})"
        if (bubble.label?.lowercase()?.split(Regex("\\W+"))?.any { it == "casa" || it == "home" } == true) into.problems += "una bolla ha l'etichetta Casa"
      }
    }
  }

  /** La griglia di ogni zona delle bolle piu' grandi: gli elementi della cella nell'istantanea, senza i nascosti. Prima e dopo il cambio dell'indice non ci sono nascosti dentro. */
  private suspend fun zones(world: BuiltWorld, stale: GeoIndexState.Ready, filter: FinalFilter, forbidden: Set<MediaId>, scope: CoroutineScope, into: SurfaceReport) {
    val visible = CatalogVisibleMedia(world.db, MutableStateFlow(world.snapshot))
    val frozen = Frozen(stale.index, stale.withoutPosition)
    val clusters = stale.index.clusters(WORLD, 9.0, BubbleEngine.CELL_DP).sortedByDescending { it.count }.take(6)
    for (cluster in clusters) {
      val expected = stale.index.members(cluster.key, ZoneGridController.MEMBER_LIMIT).filter { world.snapshot.indexOf(it) >= 0 && it !in forbidden }.toSet()
      val controller = ZoneGridController(ZoneTarget.Cell(cluster.key), frozen, visible, Names(), filter, scope, Dispatchers.Unconfined)
      val state = withTimeout(Attese.TETTO) { controller.state.first { it != null } }
      MapProbeStats.zones++
      val shown: Set<MediaId> = (state as? ZoneGridState.Ready)?.view?.let { view -> (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }.toSet() }.orEmpty()
      into.shown += shown
      val leaked = shown.count { it in forbidden }
      if (leaked > 0) into.problems += "la griglia di una zona mostra $leaked media nascosti"
      if (leaked > 0) MapProbeStats.zoneVictims += leaked
      if (shown != expected && leaked == 0) into.problems += "la griglia di una zona non ha esattamente gli elementi della cella (${shown.size} contro ${expected.size})"
    }
  }

  /** L'indice rifatto: conteggi, senza posizione, scartati e luoghi esatti, e nessun punto fuori dalle celle visibili. */
  private fun exactCounts(world: BuiltWorld, state: GeoIndexState, oracle: HiddenOracle, report: MapReport) {
    val cells = oracle.liveLibraryCells.toList().sorted()
    val valid = cells.filter { isValid(it) }
    val without = cells.count { coordOf(it) == null }
    val invalid = cells.count { coordOf(it) != null && !isValid(it) }
    MapProbeStats.invalid += invalid
    MapProbeStats.withoutPosition += without
    val allowed = world.idsOf(cells)
    when (state) {
      is GeoIndexState.Ready -> {
        if (state.index.size != valid.size) report.points.problems += "l'indice ricostruito ha ${state.index.size} punti, dovrebbe averne ${valid.size}"
        if (state.withoutPosition != without) report.places.problems += "le foto senza posizione sono ${state.withoutPosition}, dovrebbero essere $without"
        if (state.invalid != invalid) report.points.problems += "le coordinate scartate sono ${state.invalid}, dovrebbero essere $invalid"
        val all = state.index.clusters(WORLD, 23.0, 1)
        for (cluster in all) {
          report.points.shown += cluster.cover
          if (cluster.cover !in allowed) report.points.problems += "l'indice ricostruito ha una copertina che non e' una cella visibile"
        }
        val section = PlacesStates.of(state, Names()) as? PlacesSectionState.Ready
        val expected = valid.groupingBy { placeOf(it) }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(PlacesSectionState.Ready.MAX_PLACES)
        if (section == null) {
          report.places.problems += "la sezione Luoghi non e' pronta con punti validi"
        } else {
          MapProbeStats.placeRows += section.places.size
          if (section.withPosition != valid.size) report.places.problems += "la sezione Luoghi dice ${section.withPosition} foto con la posizione, dovrebbero essere ${valid.size}"
          if (section.places.map { it.placeId to it.count } != expected.map { it.key to it.value }) report.places.problems += "i luoghi della sezione non sono quelli giusti"
          if (section.places.any { it.label.lowercase().contains("casa") }) report.places.problems += "un luogo si chiama Casa"
        }
      }
      is GeoIndexState.Empty -> {
        if (valid.isNotEmpty()) report.points.problems += "l'indice dice che non ci sono punti, dovrebbero essere ${valid.size}"
        if (state.withoutPosition != without) report.places.problems += "le foto senza posizione sono ${state.withoutPosition}, dovrebbero essere $without"
      }
      GeoIndexState.Building -> report.points.problems += "l'indice e' ancora in costruzione dopo la ricostruzione"
    }
  }

  /** La mini-mappa di ogni media del mondo: mai per un nascosto, e per tutti gli altri con una posizione valida. */
  private suspend fun minimaps(world: BuiltWorld, filter: FinalFilter, forbidden: Set<MediaId>, oracle: HiddenOracle, into: SurfaceReport) {
    val positions = MapMediaPositions { id -> world.indexOf(id).let { i -> if (isValid(i)) coordOf(i)?.let { (lat, lon) -> GeoE6(StrictMath.round(lat * 1e6).toInt(), StrictMath.round(lon * 1e6).toInt()) } else null } }
    val renderer = MiniMapRenderer { _, _, _, _ -> null }
    val service = MiniMapService(positions, filter, renderer) { MiniMapLook(false, 1, 2, 2f) }
    val expected = world.idsOf(oracle.spec.media.indices.filter { it !in oracle.liveMustNeverShow && isValid(it) })
    for (id in world.ids) {
      val last = service.place(id, 640).toList().last()
      if (last is MiniMapState.Ready) {
        MapProbeStats.minimaps++
        into.shown += id
        if (id in forbidden) into.problems += "una mini-mappa per un media nascosto o di un gruppo nascosto"
      }
    }
    val missing = expected - into.shown
    if (missing.isNotEmpty()) into.problems += "${missing.size} media visibili con una posizione non hanno la mini-mappa"
  }

  private fun probed(report: SurfaceReport, world: BuiltWorld) = Probed(shown = report.shown, within = world.liveShowable, problems = report.problems)

  /** I punti, le bolle, le copertine e le etichette: nessun nascosto, conteggi esatti dopo il cambio dei nascosti. */
  val points = SurfaceProbe { world -> probed(report(world).points, world) }

  /** La griglia di una zona: gli elementi della cella, senza nascosti. */
  val zone = SurfaceProbe { world -> probed(report(world).zone, world) }

  /** La sezione Luoghi: conteggi, luoghi e senza posizione esatti. */
  val places = SurfaceProbe { world -> probed(report(world).places, world) }

  /** La mini-mappa del viewer: mai per un nascosto. */
  val minimap = SurfaceProbe { world -> probed(report(world).minimap, world) }
}
