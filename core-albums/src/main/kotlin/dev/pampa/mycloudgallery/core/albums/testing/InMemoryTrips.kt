package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.contract.TripRules
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.search.contract.Today
import java.time.temporal.ChronoUnit
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

/** Le proposte scartate dell'account (voce sincronizzata `trip_dismissed:<impronta>`): una sola per tutti i dispositivi finti. */
class TripDismissals {
  val dismissed: MutableSet<TripId> = mutableSetOf()
}

/**
 * L'ESECUTORE DI RIFERIMENTO di viaggi ed eventi (docs/fase7-progetto.md §2.3, D18-D20; regole in [TripRules]): forza bruta, leggibile,
 * sui candidati **visibili** (E-H7) e **miei** (D18). P7 scrive trips/ (con `home_estimate` locale) e lo fa passare dalla stessa suite
 * (`TripsContract`); P12 confronta i due sulle storie sintetiche. La casa e le coordinate restano in campi privati di questa classe: nessuna
 * API la restituisce.
 */
class InMemoryTrips(
  private val candidates: AlbumCandidates,
  private val today: Today,
  private val albums: Albums,
  private val dismissals: TripDismissals = TripDismissals(),
  private val label: (TripKind, LocalDayRange, List<Int>) -> String = { kind, days, _ -> if (kind == TripKind.TRIP) "Viaggio ${days.from}" else "Evento ${days.from}" },
) : Trips {

  private class Anchor(val center: GeoPoint, val radiusKm: Double)

  private class Found(val proposal: TripProposal, val rules: SmartRules)

  override val proposals: MutableStateFlow<TripProposals> = MutableStateFlow(TripProposals.EMPTY)

  private var found: List<Found> = emptyList()
  private var homeKnown = false
  private var searched = false
  private val accepted = HashMap<TripId, AlbumId>()

  private suspend fun mine(): List<AlbumCandidate> {
    val out = ArrayList<AlbumCandidate>()
    var cursor: CandidateCursor? = null
    do {
      val page = candidates.page(CandidateFilter(sources = setOf(SmartSource.MY_PHONE, SmartSource.MY_PRIVATE)), cursor, 2_000)
      out += page.items.filter { it.mine }
      cursor = page.next
    } while (cursor != null)
    return out
  }

  override suspend fun detect(budget: Duration): TripReport {
    val media = mine()
    // VG-1: una Libreria senza scatti propri non e' una ricerca (la prima scansione non e' arrivata).
    if (media.isEmpty() && !searched) return TripReport(0, 0, homeKnown = false, incomplete = true)
    val anchors = anchors(media)
    homeKnown = anchors.isNotEmpty()
    searched = true
    val trips = if (homeKnown) trips(media, anchors) else emptyList()
    val tripDays = trips.flatMap { t -> generateSequence(t.proposal.days.from) { it.plusDays(1) }.takeWhile { !it.isAfter(t.proposal.days.to) }.toList() }.toSet()
    val events = events(media.filter { it.day !in tripDays })
    found = (trips + events).sortedByDescending { it.proposal.days.to }
    publish()
    return TripReport(trips.size, events.size, homeKnown, incomplete = false)
  }

  private fun publish() {
    proposals.value = TripProposals(found.map { it.proposal }.filter { !isDismissed(it) && it.id !in accepted }, homeKnown, searched)
  }

  /** D18: casa (la prima) e luoghi abituali, dalle foto serali proprie con GPS degli ultimi 12 mesi, pesate per giorni distinti. */
  private fun anchors(media: List<AlbumCandidate>): List<Anchor> {
    val since = today.date().minusMonths(TripRules.HOME_WINDOW_MONTHS)
    val recent = media.filter { it.gps != null && !it.day.isBefore(since) }
    val gpsDays = recent.map { it.day }.toSet().size
    if (gpsDays == 0) return emptyList()
    val evening = recent.filter { c -> c.localTime?.hour?.let { it >= TripRules.EVENING_FROM_HOUR || it < TripRules.MORNING_UNTIL_HOUR } == true }
    val byCell = evening.groupBy { cellOf(it.gps!!) }
    fun block(c: Pair<Int, Int>): List<AlbumCandidate> = (-1..1).flatMap { dx -> (-1..1).flatMap { dy -> byCell[c.first + dx to c.second + dy].orEmpty() } }
    val blocks = byCell.keys.map { it to block(it) }.sortedWith(compareByDescending<Pair<Pair<Int, Int>, List<AlbumCandidate>>> { b -> b.second.map { it.day }.toSet().size }.thenBy { it.first.first }.thenBy { it.first.second })
    val chosen = ArrayList<Pair<Pair<Int, Int>, List<AlbumCandidate>>>()
    for (b in blocks) {
      val share = b.second.map { it.day }.toSet().size.toDouble() / gpsDays
      val min = if (chosen.isEmpty()) TripRules.HOME_MIN_SHARE else TripRules.HABITUAL_MIN_SHARE
      if (share < min) break
      if (chosen.any { kotlin.math.abs(it.first.first - b.first.first) <= 2 && kotlin.math.abs(it.first.second - b.first.second) <= 2 }) continue
      chosen += b
    }
    return chosen.map { (_, photos) ->
      val center = GeoPoint(photos.map { it.gps!!.latitude }.average(), photos.map { it.gps!!.longitude }.average())
      val distances = photos.map { km(center, it.gps!!) }.sorted()
      val p90 = distances[((distances.size - 1) * TripRules.HOME_RADIUS_PERCENTILE) / 100]
      Anchor(center, maxOf(TripRules.HOME_RADIUS_MIN_KM, p90))
    }
  }

  /** D19: serie di giorni lontani (mediana oltre max(50 km, 3 x raggio di casa)), buchi di al piu' 2 giorni senza foto geolocalizzate. */
  private fun trips(media: List<AlbumCandidate>, anchors: List<Anchor>): List<Found> {
    val homeRadius = anchors.first().radiusKm
    val farKm = maxOf(TripRules.FAR_MIN_KM, TripRules.FAR_HOME_RADIUS_FACTOR * homeRadius)
    val byDay = media.filter { it.gps != null }.groupBy { it.day }
    val distanceOf = { c: AlbumCandidate -> anchors.minOf { km(it.center, c.gps!!) } }
    val far = byDay.filter { (_, photos) -> median(photos.map(distanceOf)) > farKm }.keys.sorted()
    val near = byDay.keys - far.toSet()
    val series = ArrayList<MutableList<java.time.LocalDate>>()
    for (day in far) {
      val last = series.lastOrNull()?.last()
      val gap = last?.let { ChronoUnit.DAYS.between(it, day) - 1 }
      val broken = last == null || gap!! > TripRules.MAX_GAP_DAYS || near.any { it.isAfter(last) && it.isBefore(day) }
      if (broken) series += mutableListOf(day) else series.last() += day
    }
    return series.mapNotNull { days ->
      val farPhotos = days.flatMap { byDay.getValue(it) }
      val oneBigDay = days.size == 1 && farPhotos.count { distanceOf(it) > TripRules.SINGLE_DAY_MIN_KM } >= TripRules.SINGLE_DAY_MIN_MEDIA
      if (days.size < 2 && !oneBigDay) return@mapNotNull null
      val range = LocalDayRange(days.first(), days.last())
      val inRange = media.filter { it.day in range }
      val places = farPhotos.map { it.placeId }.filter { it != 0 }.distinct().sorted()
      val center = GeoPoint(farPhotos.map { it.gps!!.latitude }.average(), farPhotos.map { it.gps!!.longitude }.average())
      val radius = ceil(farPhotos.maxOf { km(center, it.gps!!) } + 1).toInt().coerceIn(SmartLimits.MIN_RADIUS_KM, SmartLimits.MAX_RADIUS_KM)
      val name = label(TripKind.TRIP, range, places)
      val place = if (places.isEmpty()) null else PlaceRule(places, center, radius, name, PlaceScope.AROUND)
      found(TripKind.TRIP, range, places, name, inRange, SmartRules(SmartPeriod.Ranges(listOf(range)), place, null))
    }
  }

  /** D19 (con l'interpretazione di [TripRules]): almeno max(30, 5 x media giornaliera) media in al piu' 4 ore, fuori dai viaggi. */
  private fun events(media: List<AlbumCandidate>): List<Found> {
    if (media.isEmpty()) return emptyList()
    val perDay = media.size.toDouble() / media.map { it.day }.toSet().size
    val threshold = maxOf(TripRules.EVENT_MIN_MEDIA.toDouble(), TripRules.EVENT_RATE_FACTOR * perDay)
    val sorted = media.sortedBy { it.takenAtUtcMs }
    val window = TripRules.EVENT_MAX_HOURS * 3_600_000L
    val out = ArrayList<Found>()
    var i = 0
    while (i < sorted.size) {
      var j = i
      while (j + 1 < sorted.size && sorted[j + 1].takenAtUtcMs - sorted[i].takenAtUtcMs <= window) j++
      if (j - i + 1 >= threshold) {
        val group = sorted.subList(i, j + 1)
        val range = LocalDayRange(group.minOf { it.day }, group.maxOf { it.day })
        val places = group.map { it.placeId }.filter { it != 0 }.distinct().sorted()
        val name = label(TripKind.EVENT, range, places)
        out += found(TripKind.EVENT, range, places, name, group, SmartRules(SmartPeriod.Ranges(listOf(range)), null, null))
        i = j + 1
      } else {
        i++
      }
    }
    return out
  }

  private fun found(kind: TripKind, range: LocalDayRange, places: List<Int>, name: String, media: List<AlbumCandidate>, rules: SmartRules): Found {
    val cover = media.maxWith(compareBy<AlbumCandidate> { it.pixels }.thenBy { it.media.value }).media
    return Found(TripProposal(TripId.of(kind, range, places), kind, range, name, media.size, cover, places), rules)
  }

  override suspend fun accept(id: TripId): TripAcceptOutcome {
    accepted[id]?.let { return TripAcceptOutcome.AlreadyAlbum(it) }
    val trip = found.firstOrNull { it.proposal.id == id } ?: return TripAcceptOutcome.NotFound
    val created = albums.create(trip.proposal.label, AlbumKind.SMART, trip.rules) as? CreateOutcome.Created ?: return TripAcceptOutcome.NotFound
    accepted[id] = created.id
    publish()
    return TripAcceptOutcome.Created(created.id)
  }

  override suspend fun dismiss(id: TripId) {
    dismissals.dismissed += id
    // VG-2: un viaggio scartato resta scartato anche se, essendo in corso, si allunga.
    found.firstOrNull { it.proposal.id == id }?.proposal?.takeIf { it.kind == TripKind.TRIP }?.let { dismissals.dismissed += TripId.startKey(it.kind, it.days.from) }
    publish()
  }

  private fun isDismissed(p: TripProposal): Boolean =
    p.id in dismissals.dismissed || (p.kind == TripKind.TRIP && TripId.startKey(p.kind, p.days.from) in dismissals.dismissed)

  private companion object {
    fun cellOf(p: GeoPoint): Pair<Int, Int> = floor(p.latitude / TripRules.HOME_CELL_DEGREES).toInt() to floor(p.longitude / TripRules.HOME_CELL_DEGREES).toInt()

    fun median(values: List<Double>): Double = values.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    /** La distanza sulla sfera (haversine), in km. */
    fun km(a: GeoPoint, b: GeoPoint): Double {
      val r = 6_371.0
      val dLat = Math.toRadians(b.latitude - a.latitude)
      val dLon = Math.toRadians(b.longitude - a.longitude)
      val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
      return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
  }
}
