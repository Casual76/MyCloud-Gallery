package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// I VIAGGI SINTETICI DI P6 (docs/fase7-progetto.md §5.3 punto 1): storie di due anni con la verita' scritta insieme alle foto. Luoghi pubblici, nessun
// dato personale. Ogni storia ha una casa (e a volte un trasloco e una casa al mare), viaggi brevi e lunghi (anche a cavallo d'anno e in altri fusi),
// la gita fuori porta, i giorni senza GPS, un tablet, un familiare, gli screenshot, i video, le foto con il GPS sbagliato e quelle scattate "per
// strada". Il generatore di P12 (tools/fixtures/trips) e' un'altra cosa: questo serve alle prove delle regole.
//
// **La verita'** di un album e' l'insieme delle foto scattate dal viaggio (a destinazione, anche quelle senza GPS e quelle dei familiari, quelle con il
// GPS sbagliato e quelle di una tappa per strada) tranne gli screenshot e le foto di casa. Le foto di casa del giorno prima della partenza e del giorno
// dopo il ritorno non sono del viaggio, e qualcuna ha il GPS sbagliato proprio sulla destinazione: e' il rumore che la precisione deve reggere.

internal class City(val id: Int, val name: String, val lat: Double, val lon: Double, val zone: Int, val country: String) {
  val center: GeoPoint get() = GeoPoint(lat, lon)
}

internal object Cities {
  val HOMES = listOf(
    City(1001, "Milano", 45.4642, 9.1900, 60, "Italia"),
    City(1002, "Roma", 41.9028, 12.4964, 60, "Italia"),
    City(1003, "Torino", 45.0703, 7.6869, 60, "Italia"),
    City(1004, "Bologna", 44.4949, 11.3426, 60, "Italia"),
  )
  val HOMES_MOVE = listOf(
    City(1011, "Verona", 45.4384, 10.9916, 60, "Italia"),
    City(1012, "Padova", 45.4064, 11.8768, 60, "Italia"),
  )
  val SEASIDE = listOf(
    City(1021, "Rimini", 44.0678, 12.5695, 60, "Italia"),
    City(1022, "Sanremo", 43.8159, 7.7760, 60, "Italia"),
    City(1023, "Cervia", 44.2620, 12.3477, 60, "Italia"),
    City(1024, "Gallipoli", 40.0558, 17.9923, 60, "Italia"),
  )
  val DESTINATIONS = listOf(
    City(1101, "Oslo", 59.9139, 10.7522, 60, "Norvegia"),
    City(1102, "Lisbona", 38.7223, -9.1393, 0, "Portogallo"),
    City(1103, "New York", 40.7128, -74.0060, -300, "Stati Uniti"),
    City(1104, "Tokyo", 35.6762, 139.6503, 540, "Giappone"),
    City(1105, "Parigi", 48.8566, 2.3522, 60, "Francia"),
    City(1106, "Londra", 51.5074, -0.1278, 0, "Regno Unito"),
    City(1107, "Madrid", 40.4168, -3.7038, 60, "Spagna"),
    City(1108, "Berlino", 52.5200, 13.4050, 60, "Germania"),
    City(1109, "Vienna", 48.2082, 16.3738, 60, "Austria"),
    City(1110, "Praga", 50.0755, 14.4378, 60, "Cechia"),
    City(1111, "Budapest", 47.4979, 19.0402, 60, "Ungheria"),
    City(1112, "Atene", 37.9838, 23.7275, 120, "Grecia"),
    City(1113, "Reykjavik", 64.1466, -21.9426, 0, "Islanda"),
    City(1114, "Marrakech", 31.6295, -7.9811, 60, "Marocco"),
    City(1115, "Dubai", 25.2048, 55.2708, 240, "Emirati Arabi"),
    City(1116, "San Francisco", 37.7749, -122.4194, -480, "Stati Uniti"),
    City(1117, "Sydney", -33.8688, 151.2093, 660, "Australia"),
    City(1118, "Stoccolma", 59.3293, 18.0686, 60, "Svezia"),
    City(1119, "Copenaghen", 55.6761, 12.5683, 60, "Danimarca"),
    City(1120, "Helsinki", 60.1699, 24.9384, 120, "Finlandia"),
    City(1121, "Firenze", 43.7696, 11.2558, 60, "Italia"),
    City(1122, "Napoli", 40.8518, 14.2681, 60, "Italia"),
    City(1123, "Palermo", 38.1157, 13.3615, 60, "Italia"),
    City(1124, "Edimburgo", 55.9533, -3.1883, 0, "Regno Unito"),
  )
  val ALL: List<City> = HOMES + HOMES_MOVE + SEASIDE + DESTINATIONS

  val places: Map<Int, PlaceName> = ALL.associate { it.id to PlaceName(it.name, "Regione ${it.name}", it.country) }

  val lookup = PlaceLookup { places[it] }

  fun distanceKm(a: City, b: City): Double = SmartGeo.distanceKm(a.center, b.center)
}

/** Un album da creare dal titolo, con la verita' (le foto del viaggio) e quanti viaggi contiene. */
internal class AlbumCase(val title: String, val truth: Set<MediaId>, val trips: Int, val kind: String) {
  override fun toString(): String = "AlbumCase($kind, $title, ${truth.size} foto)"
}

internal class SyntheticStory(val seed: Long) {
  val world = MemoryWorld(emptyList(), Cities.places)
  val cases = ArrayList<AlbumCase>()

  /** Tutte le foto del viaggio sono qui, per chiave di viaggio (citta' e anno d'inizio). */
  private class Trip(val city: City, val first: LocalDate, val last: LocalDate, val truth: MutableSet<MediaId> = LinkedHashSet())

  private val rnd = Random(seed)
  private var n = 1
  private val trips = ArrayList<Trip>()
  private lateinit var home: City
  private val seaWeekends = ArrayList<LocalDate>()
  private val seaDays = HashSet<LocalDate>()
  private var seaTown: City? = null

  private fun roll(p: Double): Boolean = rnd.nextDouble() < p

  private fun offset(c: City, km: Double, bearing: Double): GeoPoint {
    val lat = (c.lat + km * cos(bearing) / 111.195).coerceIn(-89.0, 89.0)
    val lon = c.lon + km * sin(bearing) / (111.195 * cos(Math.toRadians(c.lat)))
    return GeoPoint(lat, ((lon + 540) % 360) - 180)
  }

  private fun around(c: City, spreadKm: Double): GeoPoint = offset(c, abs(rnd.nextDouble() + rnd.nextDouble() - 1.0) * spreadKm, rnd.nextDouble() * 2 * PI)

  private fun placeOf(c: City, gps: GeoPoint): Int = if (SmartGeo.distanceKm(c.center, gps) <= 50.0) c.id else 0

  private fun emit(
    day: LocalDate,
    minute: Int,
    zone: Int,
    gps: GeoPoint?,
    placeId: Int,
    device: String? = "phone",
    source: SmartSource = SmartSource.MY_PHONE,
    screen: Boolean = false,
    video: Boolean = false,
  ): MediaId {
    val media = cand(
      n, day, minute.coerceIn(0, 1439), gps = gps, placeId = if (gps == null) 0 else placeId, device = device, source = source, zone = zone, screen = screen,
      kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO,
    )
    world.put(media)
    n++
    return media.media
  }

  fun build(): SyntheticStory {
    home = Cities.HOMES[rnd.nextInt(Cities.HOMES.size)]
    val moveDay = if (roll(0.3)) LocalDate.of(2025, 1, 1).plusDays(rnd.nextLong(0, 300)) else null
    planTrips()
    planSea()
    homeLife(moveDay)
    for (trip in trips) tripLife(trip)
    seaHouse()
    albumsFromTrips()
    world.publish()
    return this
  }

  // --- I viaggi ------------------------------------------------------------------------------------------------------------------

  private fun planTrips() {
    val pool = Cities.DESTINATIONS.filter { Cities.distanceKm(it, home) > 120 }.shuffled(rnd).toMutableList()
    val count = 3 + rnd.nextInt(4)
    val first = LocalDate.of(2024, 1, 10)
    fun overlaps(from: LocalDate, to: LocalDate) = trips.any { !(to.plusDays(5).isBefore(it.first) || from.minusDays(5).isAfter(it.last)) }
    repeat(count) {
      val city = pool.removeAt(0)
      val length = when (rnd.nextInt(10)) {
        in 0..3 -> 2 + rnd.nextInt(3)
        in 4..7 -> 5 + rnd.nextInt(4)
        else -> 9 + rnd.nextInt(4)
      }
      for (attempt in 0 until 30) {
        val start = if (attempt == 0 && trips.isEmpty() && roll(0.45)) LocalDate.of(2024, 12, 26 + rnd.nextInt(4)) else first.plusDays(rnd.nextLong(0, 690))
        val end = start.plusDays(length.toLong() - 1)
        if (!overlaps(start, end) && end.isBefore(LocalDate.of(2025, 12, 20))) {
          trips += Trip(city, start, end)
          break
        }
      }
    }
    // E-AL12: nel 30% delle storie un viaggio torna nella stessa citta' lo stesso anno.
    if (roll(0.3)) {
      val base = trips.firstOrNull { it.first.year == it.last.year } ?: return
      repeat(30) {
        val start = LocalDate.of(base.first.year, 1, 15).plusDays(rnd.nextLong(0, 320))
        val end = start.plusDays(2L + rnd.nextInt(3))
        if (end.year == base.first.year && !overlaps(start, end)) {
          trips += Trip(base.city, start, end)
          return
        }
      }
    }
  }

  // --- La vita di casa -----------------------------------------------------------------------------------------------------------

  private fun homeLife(moveDay: LocalDate?) {
    var day = LocalDate.of(2024, 1, 1)
    val end = LocalDate.of(2025, 12, 31)
    var nowHome = home
    while (!day.isAfter(end)) {
      if (moveDay != null && day == moveDay) nowHome = Cities.HOMES_MOVE[rnd.nextInt(Cities.HOMES_MOVE.size)]
      val away = day in seaDays || trips.any { !day.isBefore(it.first) && !day.isAfter(it.last) }
      if (!away && roll(0.5)) {
        repeat(1 + rnd.nextInt(3)) { emitHome(day, nowHome) }
      }
      // Qualche festa: molte foto in poche ore, a casa.
      if (!away && roll(0.004)) repeat(80 + rnd.nextInt(120)) { emitHome(day, nowHome, hours = 19..23) }
      day = day.plusDays(1)
    }
    // Il rumore del giorno prima e del giorno dopo di ogni viaggio: foto di casa; a volte una con il GPS sbagliato proprio sulla destinazione.
    for (trip in trips) {
      for (edge in listOf(trip.first.minusDays(1), trip.last.plusDays(1))) {
        repeat(rnd.nextInt(4)) { emitHome(edge, home) }
        if (roll(0.04)) {
          val wrong = around(trip.city, 3.0)
          emit(edge, 20 * 60, 60, wrong, trip.city.id)
        }
        // Una foto senza GPS di casa, la sera prima di partire: mai "fra due sicuri" (le ancore sono a destinazione).
        if (roll(0.5)) emit(edge, 22 * 60, 60, null, 0)
      }
    }
  }

  private fun emitHome(day: LocalDate, city: City, hours: IntRange = 8..22) {
    val minute = hours.first * 60 + rnd.nextInt((hours.last - hours.first) * 60)
    val gps = if (roll(0.9)) around(city, 6.0) else null
    emit(day, minute, city.zone, gps, if (gps == null) 0 else placeOf(city, gps), video = roll(0.06))
  }

  // --- Un viaggio ----------------------------------------------------------------------------------------------------------------

  private fun tripLife(trip: Trip) {
    val city = trip.city
    val days = ChronoUnit.DAYS.between(trip.first, trip.last).toInt() + 1
    val phoneGps = if (roll(0.08)) 0.55 else 0.94
    val noGpsDay = if (days >= 4 && roll(0.3)) 1 + rnd.nextInt(days - 2) else -1
    val dayTrip = if (days >= 5 && roll(0.5)) 1 + rnd.nextInt(days - 2) else -1
    val withTablet = roll(0.25)
    val withFamily = roll(0.4)
    for (k in 0 until days) {
      val day = trip.first.plusDays(k.toLong())
      val heavy = roll(0.1)
      val count = if (k == 0 || k == days - 1) 4 + rnd.nextInt(12) else if (heavy) 40 + rnd.nextInt(25) else 8 + rnd.nextInt(28)
      val minutes = List(count) { 8 * 60 + rnd.nextInt(14 * 60 + 30) }.sorted()
      for (minute in minutes) {
        val device = if (withTablet && roll(0.15)) "tablet" else "phone"
        val gpsProb = if (k == noGpsDay) 0.0 else if (device == "tablet") 0.5 else phoneGps
        val video = roll(0.07)
        var gps: GeoPoint? = null
        var place = 0
        if (roll(gpsProb)) {
          val spot = when {
            roll(0.01) -> offset(city, 300.0 + rnd.nextDouble() * 2_700.0, rnd.nextDouble() * 2 * PI)
            (k == 0 || k == days - 1) && roll(0.03) -> alongWay(city)
            k == dayTrip && roll(0.4) -> offset(city, 28.0 + rnd.nextDouble() * 20.0, rnd.nextDouble() * 2 * PI)
            else -> around(city, 9.0)
          }
          gps = spot
          place = placeOf(city, spot)
        }
        val id = if (device == "tablet") {
          emit(day, minute, city.zone, gps, place, "backup:tablet-di-casa", SmartSource.MY_PRIVATE, video = video)
        } else {
          emit(day, minute, city.zone, gps, place, video = video)
        }
        trip.truth += id
      }
      // Gli screenshot (biglietti, mappe): rumore, mai del viaggio.
      repeat(if (roll(0.5)) 1 + rnd.nextInt(3) else 0) { emit(day, 9 * 60 + rnd.nextInt(600), city.zone, null, 0, screen = true) }
      if (withFamily && roll(0.6)) {
        repeat(2 + rnd.nextInt(8)) {
          val gps = if (roll(0.8)) around(city, 6.0) else null
          trip.truth += emit(day, 9 * 60 + rnd.nextInt(660), city.zone, gps, if (gps == null) 0 else placeOf(city, gps), null, SmartSource.FAMILY)
        }
      }
    }
  }

  /** Una tappa per strada: un punto fra casa e la destinazione (l'aeroporto, l'autogrill). */
  private fun alongWay(city: City): GeoPoint {
    val f = 0.3 + rnd.nextDouble() * 0.4
    return GeoPoint(home.lat + (city.lat - home.lat) * f, home.lon + (city.lon - home.lon) * f)
  }

  // --- La casa al mare ------------------------------------------------------------------------------------------------------------

  /** I fine settimana al mare: mai sopra un viaggio (nessuno e' in due posti), e niente vita di casa quei giorni. */
  private fun planSea() {
    if (!roll(0.25)) return
    val town = Cities.SEASIDE[rnd.nextInt(Cities.SEASIDE.size)]
    if (Cities.distanceKm(town, home) < 60) return
    var day = LocalDate.of(2025, 3, 1)
    while (day.year == 2025 && seaWeekends.size < 24) {
      val busy = trips.any { !day.plusDays(2).plusDays(1).isBefore(it.first) && !day.minusDays(1).isAfter(it.last) }
      if (day.dayOfWeek == java.time.DayOfWeek.FRIDAY && !busy && roll(0.6)) {
        seaWeekends += day
        for (k in 0..2) seaDays += day.plusDays(k.toLong())
      }
      day = day.plusDays(1)
    }
    seaTown = town
  }

  private fun seaHouse() {
    val town = seaTown ?: return
    val truth = LinkedHashSet<MediaId>()
    for (friday in seaWeekends) {
      for (k in 0..2) {
        val d = friday.plusDays(k.toLong())
        repeat(3 + rnd.nextInt(14)) {
          val gps = if (roll(0.92)) around(town, 5.0) else null
          truth += emit(d, 9 * 60 + rnd.nextInt(12 * 60), town.zone, gps, if (gps == null) 0 else placeOf(town, gps))
        }
      }
    }
    if (truth.isNotEmpty()) cases += AlbumCase("${town.name} 2025", truth, seaWeekends.size, "mare")
  }

  // --- Gli album -----------------------------------------------------------------------------------------------------------------

  private fun albumsFromTrips() {
    for (group in trips.groupBy { it.city }.values) {
      val crossing = group.firstOrNull { it.first.year != it.last.year }
      if (crossing != null) {
        val winter = if (crossing.first.month.value == 12) crossing.first.year else crossing.first.year - 1
        cases += AlbumCase("${crossing.city.name} inverno $winter", crossing.truth, 1, "cavallo-danno")
        continue
      }
      for ((year, byYear) in group.groupBy { it.first.year }) {
        val kind = if (byYear.size > 1) "due-viaggi" else "viaggio"
        cases += AlbumCase("${group.first().city.name} $year", byYear.flatMapTo(LinkedHashSet()) { it.truth }, byYear.size, kind)
      }
    }
  }

  companion object {
    fun stories(count: Int = 20, firstSeed: Long = 100): List<SyntheticStory> = (0 until count).map { SyntheticStory(firstSeed + it).build() }
  }
}
