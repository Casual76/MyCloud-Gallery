package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripRules
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.math.ceil

// VIAGGI ED EVENTI (docs/fase7-progetto.md §2.3, D19, E-AL10, E-AL11, E-AL16, E-H7): le regole, pure e deterministiche, sui candidati
// **gia' visibili e propri** (VisibleMedia, D22; niente screenshot, E-AL4). Nessuna porta, nessun Room, nessun orologio: si prova sulla JVM con
// storie sintetiche (TripStoriesTest) e sulla forza bruta di riferimento (`InMemoryTrips`). I giorni sono quelli **locali dello scatto**
// (`AlbumCandidate.day`, D36): un viaggio in un altro fuso resta nei suoi giorni (E-AL10); le finestre di ore degli eventi usano l'istante.

/** Quali luoghi (GeoNames) ha un viaggio o un evento, e come si dicono: i piu' frequenti per il titolo, tutti per l'impronta. */
internal class PlaceStats(
  /** I piu' frequenti, dal piu' frequente (parita': id minore). */
  val top: IntArray,
  /** Quanti luoghi diversi (anche oltre quelli in [ids]). */
  val distinct: Int,
  /** Gli id per `TripId` e per la regola del luogo: al piu' [MAX_IDS], i piu' frequenti, in ordine crescente. */
  val ids: List<Int>,
) {
  override fun toString(): String = "PlaceStats($distinct)"

  companion object {
    const val MAX_IDS: Int = 30
    private const val TOP: Int = 3
    val NONE: PlaceStats = PlaceStats(IntArray(0), 0, emptyList())

    fun of(shots: Collection<AlbumCandidate>): PlaceStats {
      val counts = HashMap<Int, Int>()
      for (c in shots) if (c.placeId != 0) counts.merge(c.placeId, 1, Int::plus)
      if (counts.isEmpty()) return NONE
      val ranked = counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).map { it.key }
      return PlaceStats(ranked.take(TOP).toIntArray(), ranked.size, ranked.take(MAX_IDS).sorted())
    }
  }
}

/** Un viaggio o un evento trovato: [members] tutti i media propri e visibili nei giorni, [coverPool] fra cui scegliere la copertina. */
internal class DetectedItem(
  val kind: TripKind,
  val range: LocalDayRange,
  val members: List<AlbumCandidate>,
  val coverPool: List<AlbumCandidate>,
  val places: PlaceStats,
) {
  val id: TripId get() = TripId.of(kind, range, places.ids)

  override fun toString(): String = "DetectedItem(${kind.code}, ${members.size})"
}

/** L'esito di una rilevazione. [current] e' la casa di oggi (null: nessuna casa stimabile, E-AL16). */
internal class Detection(val current: Anchors?, val items: List<DetectedItem>) {
  val trips: Int get() = items.count { it.kind == TripKind.TRIP }
  val events: Int get() = items.count { it.kind == TripKind.EVENT }

  override fun toString(): String = "Detection($trips viaggi, $events eventi)"
}

internal object CoverPicker {
  /** La foto piu' grande (non uno screenshot, meglio una foto che un video); a parita' l'id maggiore: sempre la stessa. */
  fun pick(pool: List<AlbumCandidate>): AlbumCandidate {
    val photos = pool.filter { !it.isVideo && !it.screen }
    val from = photos.ifEmpty { pool.filter { !it.screen }.ifEmpty { pool } }
    return from.maxWith(compareBy<AlbumCandidate> { it.pixels }.thenBy { it.media.value })
  }
}

/** Il cerchio dei luoghi di un viaggio (centro e raggio in km): per la regola del luogo dell'album che nasce dalla proposta. */
internal class TripArea(val lat: Double, val lon: Double, val radiusKm: Int) {
  override fun toString(): String = "TripArea($radiusKm km)"
}

internal object TripDetector {
  private const val MILLIS_PER_HOUR = 3_600_000L

  /**
   * Casa, viaggi ed eventi dai media propri [shots] (visibili, senza screenshot, in ordine di giorno e istante) visti da [today].
   * Un viaggio non esiste senza casa (E-AL16): senza GPS solo eventi, e per i luoghi degli eventi serve la casa (vedi [placesOf]).
   */
  fun detect(shots: List<AlbumCandidate>, today: LocalDate): Detection {
    val upToToday = shots.filter { !it.day.isAfter(today) }
    val ordered = if (upToToday.zipWithNext().all { (a, b) -> a.day <= b.day }) upToToday else upToToday.sortedBy { it.day }
    val gps = ordered.filter { c -> c.gps?.let { TripGeometry.usable(it.latitude, it.longitude) } == true }
    val g = GeoIndex(gps)
    val timeline = if (g.size > 0) HomeTimeline.build(g, today) else null
    val index = DayIndex(ordered)
    val trips = if (timeline != null && timeline.known) tripsOf(index, g, timeline) else emptyList()
    val tripDays = HashSet<Int>()
    for (t in trips) for (d in t.range.from.toEpochDay()..t.range.to.toEpochDay()) tripDays += d.toInt()
    val events = eventsOf(index, tripDays, timeline)
    val items = (trips + events).sortedWith(
      compareByDescending<DetectedItem> { it.range.to }.thenByDescending { it.range.from }.thenBy { it.id.value },
    )
    return Detection(timeline?.current, items)
  }

  /** Gli scatti in ordine di giorno, con la ricerca dei giorni (per leggere i media di un periodo senza scorrere tutto). */
  private class DayIndex(val shots: List<AlbumCandidate>) {
    private val days = IntArray(shots.size) { shots[it].day.toEpochDay().toInt() }

    private fun lowerBound(day: Int): Int {
      var lo = 0
      var hi = days.size
      while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (days[mid] < day) lo = mid + 1 else hi = mid
      }
      return lo
    }

    fun within(range: LocalDayRange): List<AlbumCandidate> =
      shots.subList(lowerBound(range.from.toEpochDay().toInt()), lowerBound(range.to.toEpochDay().toInt() + 1))
  }

  // --- viaggi (D19) -------------------------------------------------------------------------------------------------------------------

  private fun tripsOf(index: DayIndex, g: GeoIndex, timeline: HomeTimeline): List<DetectedItem> {
    val n = g.days.size
    val far = BooleanArray(n)
    val over100 = IntArray(n)
    for (d in 0 until n) {
      val anchors = timeline.at(g.days[d]) ?: continue
      val from = g.dayStart[d]
      val dist = DoubleArray(g.dayStart[d + 1] - from) { anchors.nearestKm(g.lat[from + it], g.lon[from + it]) }
      over100[d] = dist.count { it > TripRules.SINGLE_DAY_MIN_KM }
      dist.sort()
      far[d] = TripGeometry.median(dist) > anchors.farKm
    }
    // Le serie: giorni lontani con al piu' MAX_GAP_DAYS giorni di calendario in mezzo, e nessun giorno geolocalizzato vicino a casa fra due.
    val series = ArrayList<MutableList<Int>>()
    var last = -1
    for (d in 0 until n) {
      if (!far[d]) continue
      val current = series.lastOrNull()
      val gap = if (last >= 0) g.days[d] - g.days[last] - 1 else Int.MAX_VALUE
      val joins = current != null && gap <= TripRules.MAX_GAP_DAYS && (last + 1 until d).none { timeline.at(g.days[it]) != null && !far[it] }
      if (joins) current!! += d else series += mutableListOf(d)
      last = d
    }
    return series.mapNotNull { days ->
      val oneBigDay = days.size == 1 && over100[days[0]] >= TripRules.SINGLE_DAY_MIN_MEDIA
      if (days.size < 2 && !oneBigDay) return@mapNotNull null
      val range = LocalDayRange(LocalDate.ofEpochDay(g.days[days.first()].toLong()), LocalDate.ofEpochDay(g.days[days.last()].toLong()))
      val away = ArrayList<AlbumCandidate>()
      val gpsShots = index.within(range).filter { c -> c.gps?.let { TripGeometry.usable(it.latitude, it.longitude) } == true }
      for (c in gpsShots) {
        val anchors = timeline.at(c.day.toEpochDay().toInt()) ?: continue
        if (!anchors.isNear(c.gps!!.latitude, c.gps.longitude)) away += c
      }
      val members = index.within(range)
      DetectedItem(TripKind.TRIP, range, members, away.ifEmpty { members }, PlaceStats.of(away))
    }
  }

  // --- eventi (D19, interpretazione del ritmo in TripRules) ---------------------------------------------------------------------------

  private fun eventsOf(index: DayIndex, tripDays: Set<Int>, timeline: HomeTimeline?): List<DetectedItem> {
    val pool = index.shots.filter { it.day.toEpochDay().toInt() !in tripDays }
    if (pool.isEmpty()) return emptyList()
    val perDay = pool.size.toDouble() / pool.map { it.day }.toSet().size
    val threshold = maxOf(TripRules.EVENT_MIN_MEDIA, ceil(TripRules.EVENT_RATE_FACTOR * perDay).toInt())
    val byTime = pool.sortedWith(compareBy<AlbumCandidate> { it.takenAtUtcMs }.thenBy { it.media.value })
    val t = LongArray(byTime.size) { byTime[it].takenAtUtcMs }
    val window = TripRules.EVENT_MAX_HOURS * MILLIS_PER_HOUR
    // Ogni finestra di al piu' 4 ore con abbastanza media e' un evento; finestre che si sovrappongono sono lo stesso evento (una festa di sei ore).
    val clusters = ArrayList<IntRange>()
    var start = -1
    var end = -1
    var j = 0
    for (i in byTime.indices) {
      if (j < i) j = i
      while (j + 1 < byTime.size && t[j + 1] - t[i] <= window) j++
      if (j - i + 1 < threshold) continue
      if (start >= 0 && i <= end) {
        end = maxOf(end, j)
      } else {
        if (start >= 0) clusters += start..end
        start = i
        end = j
      }
    }
    if (start >= 0) clusters += start..end

    val merged = LinkedHashMap<TripId, DetectedItem>()
    for (cluster in clusters) {
      val group = byTime.subList(cluster.first, cluster.last + 1)
      val range = LocalDayRange(group.minOf { it.day }, group.maxOf { it.day })
      val places = placesOf(group, timeline)
      val item = DetectedItem(TripKind.EVENT, range, index.within(range), group, places)
      val before = merged[item.id]
      merged[item.id] = if (before == null) item else DetectedItem(TripKind.EVENT, range, item.members, before.coverPool + item.coverPool, places)
    }
    return merged.values.toList()
  }

  /**
   * I luoghi di un evento: solo quelli **lontani da casa e dai luoghi abituali**. Una festa a casa non si intitola con la citta' dove
   * si abita (il titolo finisce nei dati personali): per questo, senza una casa stimata, un evento non ha luoghi.
   */
  private fun placesOf(group: List<AlbumCandidate>, timeline: HomeTimeline?): PlaceStats {
    if (timeline == null) return PlaceStats.NONE
    val away = group.filter { c ->
      val gps = c.gps ?: return@filter false
      val anchors = timeline.at(c.day.toEpochDay().toInt()) ?: return@filter false
      TripGeometry.usable(gps.latitude, gps.longitude) && !anchors.isNear(gps.latitude, gps.longitude)
    }
    return PlaceStats.of(away)
  }

  // --- il cerchio di un viaggio -------------------------------------------------------------------------------------------------------

  /**
   * Il cerchio che copre le foto di [pool] lontane da [anchors] (centro e raggio in km, fra [SmartLimits.MIN_RADIUS_KM] e
   * [SmartLimits.MAX_RADIUS_KM]); null senza foto geolocalizzate lontane da casa. Un punto per cella di ~1 km: il conto non cresce con le foto.
   */
  fun areaOf(pool: List<AlbumCandidate>, anchors: Anchors?): TripArea? {
    val points = LinkedHashMap<Long, DoubleArray>()
    for (c in pool) {
      val gps = c.gps ?: continue
      if (!TripGeometry.usable(gps.latitude, gps.longitude)) continue
      if (anchors != null && anchors.isNear(gps.latitude, gps.longitude)) continue
      points.getOrPut(TripGeometry.cellOf(gps.latitude, gps.longitude)) { doubleArrayOf(gps.latitude, gps.longitude) }
    }
    if (points.isEmpty()) return null
    val (lat, lon, radius) = TripGeometry.enclosing(points.values.toList())
    return TripArea(lat, lon, ceil(radius + 1).toInt().coerceIn(SmartLimits.MIN_RADIUS_KM, SmartLimits.MAX_RADIUS_KM))
  }
}
