package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.HomeClearance
import dev.pampa.mycloudgallery.core.albums.contract.NoHomeClearance
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.TripRules
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.contract.PlacePrecision
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.PlaceEntry
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import kotlin.math.ceil
import kotlin.math.roundToInt

// I LUOGHI DI UN TITOLO (D13, §2.2 passi 1 e 3). Il dizionario dei luoghi della Libreria (solo celle **visibili**: l'istantanea non ha i nascosti)
// e la regola di luogo che ne esce: centro dalle foto, raggio dalla precisione.

/** Il dizionario dei luoghi per l'analizzatore e quanti media visibili ha ogni interpretazione (per gli omonimi, E-AL2). */
internal class LibraryDictionary(val dictionary: PlaceDictionary) {
  private val counts: Map<PlaceOption, Int> = dictionary.entries.associate { it.option to it.mediaCount }

  fun countOf(option: PlaceOption): Int = counts[option] ?: 0
}

internal object SmartLibraryPlaces {

  /**
   * Il dizionario dei luoghi dell'istantanea [snapshot]: un'interpretazione per luogo, una per regione (`admin1`) e una per paese, con i media
   * visibili di ognuna. **Lo stesso** dizionario che la ricerca costruisce (`LibraryPlaces` di core-search: un test li confronta), cosi' "Oslo"
   * vale lo stesso in Cerca e nell'editor di un album.
   */
  fun build(snapshot: TimelineSnapshot, lookup: PlaceLookup): LibraryDictionary {
    val counts = HashMap<Int, Int>()
    for (i in 0 until snapshot.size) {
      val id = snapshot.placeAt(i)
      if (id != 0) counts[id] = (counts[id] ?: 0) + 1
    }
    val entries = ArrayList<PlaceEntry>()
    val regions = LinkedHashMap<String, Pair<String, MutableMap<Int, Int>>>()
    val countries = LinkedHashMap<String, Pair<String, MutableMap<Int, Int>>>()
    for ((id, count) in counts.entries.sortedBy { it.key }) {
      val name = lookup.place(id) ?: continue
      val key = SearchText.normalize(name.name)
      if (key.isNotEmpty()) entries += PlaceEntry(key, PlaceOption(setOf(id), name.name, PlaceLevel.PLACE), count)
      name.admin1?.let { a -> SearchText.normalize(a).takeIf { it.isNotEmpty() }?.let { regions.getOrPut(it) { a to HashMap() }.second[id] = count } }
      SearchText.normalize(name.country).takeIf { it.isNotEmpty() }?.let { countries.getOrPut(it) { name.country to HashMap() }.second[id] = count }
    }
    for ((key, v) in regions) entries += PlaceEntry(key, PlaceOption(v.second.keys.toSet(), v.first, PlaceLevel.REGION), v.second.values.sum())
    for ((key, v) in countries) entries += PlaceEntry(key, PlaceOption(v.second.keys.toSet(), v.first, PlaceLevel.COUNTRY), v.second.values.sum())
    return LibraryDictionary(PlaceDictionary(entries))
  }
}

/**
 * R16 della revisione di fine Fase 7: il centro di un luogo finisce nei dati personali (`smart:place`, area privata sul NAS, non cifrata nella share).
 * Per la citta' dove si abita e' la mediana delle foto proprie li', cioe' la casa a precisione piena. Qui il centro che si scrive:
 *  - **calcolato senza i punti troppo vicini a casa o a un luogo abituale** ([HomeClearance]; se la citta' e' tutta cosi', con tutti);
 *  - **su una griglia di 0,1 gradi** (~11 km): dice la citta', che il titolo gia' dice, non la casa;
 *  - e se il punto della griglia cade ancora troppo vicino a casa, quello accanto (fra i nove piu' vicini) che non ci cade.
 * Il raggio non cambia: a un bordo della citta' qualche foto puo' risultare "vicina" invece che "sicura" (L80).
 */
internal object PlaceCenters {

  suspend fun stored(points: List<GeoPoint>, home: HomeClearance): GeoPoint? {
    if (points.isEmpty()) return null
    val close = home.closeToHome(points)
    val away = points.filterIndexed { i, _ -> close.getOrNull(i) != true }
    val basis = SmartGeo.medianCenter(away.ifEmpty { points }) ?: return null
    return onGrid(basis, home)
  }

  /** Il punto della griglia piu' vicino a [basis] che non sia troppo vicino a casa. */
  suspend fun onGrid(basis: GeoPoint, home: HomeClearance): GeoPoint {
    val fine = (1.0 / TripRules.CENTER_GRID_DEGREES).roundToInt()
    return nearestClear(basis, home, fine) ?: nearestClear(basis, home, 1) ?: gridPoint(basis.latitude.roundToInt() , basis.longitude.roundToInt(), 1)
  }

  private suspend fun nearestClear(basis: GeoPoint, home: HomeClearance, perDegree: Int): GeoPoint? {
    val lat = (basis.latitude * perDegree).roundToInt()
    val lon = (basis.longitude * perDegree).roundToInt()
    val around = (-1..1).flatMap { dy -> (-1..1).map { dx -> gridPoint(lat + dy, lon + dx, perDegree) } }.sortedBy { SmartGeo.distanceKm(it, basis) }
    val close = home.closeToHome(around)
    return around.filterIndexed { i, _ -> close.getOrNull(i) != true }.firstOrNull()
  }

  /** Il punto della griglia con [lat] e [lon] in multipli di 1/[perDegree] di grado (divisione esatta: "59.9", non "59.900000000000006"). */
  private fun gridPoint(lat: Int, lon: Int, perDegree: Int): GeoPoint {
    val la = (lat.toDouble() / perDegree).coerceIn(-MAX_LAT, MAX_LAT)
    var lo = lon.toDouble() / perDegree
    if (lo > MAX_LON) lo -= 2 * MAX_LON
    if (lo < -MAX_LON) lo += 2 * MAX_LON
    return GeoPoint(la, lo)
  }

  private const val MAX_LAT = 90.0
  private const val MAX_LON = 180.0
}

/**
 * Da un'interpretazione di luogo alla regola (§2.2 passo 3). Il **centro** e' la mediana delle coordinate delle foto visibili del luogo (il
 * dataset dei luoghi non ha coordinate: sono le foto a dire dov'e'), senza le foto di casa e arrotondato (R16, [PlaceCenters]); il **raggio** dalla
 * precisione: 25 km per una citta', 15 km per un luogo "vicino" (le foto stanno fra 15 e 50 km dal punto del dataset); una regione o un paese sono
 * il confine (il raggio non conta, ed e' il massimo).
 */
internal class PlaceResolver(
  private val candidates: VisibleCandidates,
  private val geocoder: ReverseGeocoder?,
  private val home: HomeClearance = NoHomeClearance,
) {

  /** La regola per [option] ([label] e' il nome da mostrare: per gli omonimi porta anche il paese o la regione), o null senza foto col GPS. */
  suspend fun rule(option: PlaceOption, label: String = option.label): PlaceRule? {
    val points = samplePoints(option.ids)
    val center = PlaceCenters.stored(points, home) ?: return null
    val scope = when (option.level) {
      PlaceLevel.PLACE -> PlaceScope.AROUND
      PlaceLevel.REGION -> PlaceScope.REGION
      PlaceLevel.COUNTRY -> PlaceScope.COUNTRY
    }
    val radius = if (scope == PlaceScope.AROUND) radiusFor(center, option.ids) else SmartLimits.MAX_RADIUS_KM
    return PlaceRule(option.ids.sorted(), center, radius, label, scope)
  }

  /**
   * La regola per piu' luoghi dello stesso titolo ("Roma e Firenze"): un cerchio che li contiene tutti (centro in mezzo, raggio fino al piu'
   * lontano piu' il suo raggio), se sta nei 300 km; altrimenti il primo. Regioni e paesi: il primo, e basta.
   */
  suspend fun rule(options: List<PlaceOption>, firstLabel: String = options.first().label): PlaceRule? {
    val first = rule(options.first(), firstLabel) ?: return null
    if (options.size == 1 || options.any { it.level != PlaceLevel.PLACE }) return first
    val rules = listOf(first) + options.drop(1).mapNotNull { rule(it) }
    if (rules.size < 2) return first
    // R16: anche il punto "in mezzo" e' un centro scritto nei dati personali (due mete ai lati opposti di casa hanno il mezzo proprio in casa).
    val middle = PlaceCenters.onGrid(SmartGeo.middle(rules.map { it.center }), home)
    val radius = ceil(rules.maxOf { SmartGeo.distanceKm(middle, it.center) + it.radiusKm }).toInt()
    if (radius > SmartLimits.MAX_RADIUS_KM) return first
    return PlaceRule(rules.flatMap { it.placeIds }.distinct().sorted(), middle, radius.coerceAtLeast(SmartLimits.MIN_RADIUS_KM), rules.joinToString(" e ") { it.label }, PlaceScope.AROUND)
  }

  private suspend fun samplePoints(ids: Set<Int>): List<GeoPoint> {
    val points = ArrayList<GeoPoint>()
    var after: CandidateCursor? = null
    while (points.size < SmartTuning.CENTER_SAMPLE) {
      val page = candidates.placePage(ids, after, SmartTuning.PAGE)
      for (c in page.items) c.c.gps?.let { points += it }
      after = page.next ?: break
    }
    return points.take(SmartTuning.CENTER_SAMPLE)
  }

  private fun radiusFor(center: GeoPoint, ids: Set<Int>): Int {
    val match = geocoder?.nearest(center)?.takeIf { it.place.id.value in ids }
    return if (match?.precision == PlacePrecision.NEAR) SmartLimits.NEAR_RADIUS_KM else SmartLimits.CITY_RADIUS_KM
  }
}
