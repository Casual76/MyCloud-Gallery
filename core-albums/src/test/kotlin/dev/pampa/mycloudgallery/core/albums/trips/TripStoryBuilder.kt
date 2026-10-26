package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Le STORIE SINTETICHE di P7 (docs/fase7-progetto.md §5.3, riga P7 del §4.4): due anni di una vita fotografica con la casa, i traslochi, la casa
// al mare, i viaggi (anche in altri fusi, a cavallo d'anno, con giorni senza GPS), le feste, gli screenshot, le foto dei familiari e i GPS
// sbagliati. Luoghi pubblici, nessun dato personale. Ogni storia porta con se' la verita': i viaggi e gli eventi che la rilevazione deve
// trovare, e nessun altro (nessun falso viaggio a casa).

/** Un posto sul mondo (luogo pubblico) con il suo id GeoNames: [moved] ne ricava un altro a distanza esatta. */
internal class Spot(val name: String, val lat: Double, val lon: Double, val placeId: Int) {
  /** Un posto spostato di [northKm] verso nord e [eastKm] verso est. */
  fun moved(northKm: Double, eastKm: Double, id: Int = placeId, label: String = name): Spot =
    Spot(label, lat + northKm / KM_PER_DEGREE, lon + eastKm / (KM_PER_DEGREE * cos(Math.toRadians(lat))), id)

  override fun toString(): String = name

  companion object {
    const val KM_PER_DEGREE = 111.195
  }
}

/** I luoghi pubblici delle storie (gli id sono quelli del mini-dataset di `FakePlaceData`, gli altri sono di prova). */
internal object Places {
  val FIRENZE = Spot("Firenze", 43.7696, 11.2558, 3176959)
  val MILANO = Spot("Milano", 45.4643, 9.1895, 3173435)
  val ROMA = Spot("Roma", 41.8919, 12.5113, 3169070)
  val OSLO = Spot("Oslo", 59.9127, 10.7461, 3143244)
  val LONDRA = Spot("Londra", 51.5085, -0.1257, 2643743)
  val MONACO = Spot("Monaco di Baviera", 48.1374, 11.5755, 2867714)
  val LISBONA = Spot("Lisbona", 38.7167, -9.1333, 2267057)
  val PARIGI = Spot("Parigi", 48.8534, 2.3488, 2988507)
  val TOKYO = Spot("Tokyo", 35.6895, 139.6917, 1850147)
  val NEW_YORK = Spot("New York", 40.7143, -74.0060, 5128581)
  val LOS_ANGELES = Spot("Los Angeles", 34.0522, -118.2437, 5368361)
  val SYDNEY = Spot("Sydney", -33.8688, 151.2093, 2147714)
  val BERLINO = Spot("Berlino", 52.5200, 13.4050, 2950159)
  val TORINO = Spot("Torino", 45.0703, 7.6869, 3165524)
  val BOLOGNA = Spot("Bologna", 44.4949, 11.3426, 3181928)
}

/** Come vive chi abita a [home]: quanti giorni fotografa, dove la sera, quanto sbaglia il telefono. */
internal class Habits(
  val home: Spot,
  val dayProbability: Double = 0.55,
  val perDay: IntRange = 1..4,
  val outingShare: Double = 0.25,
  val outings: List<Spot> = emptyList(),
  val gpsMissing: Double = 0.03,
  val badGps: Double = 0.0,
  val screenshots: Double = 0.04,
  val backupOnly: Double = 0.2,
  val eveningShare: Double = 0.5,
  val tz: Int = 60,
)

/** La verita' di una storia. [home] e' dove si abita oggi (null: nessuna casa stimabile, E-AL16). */
internal class Expect {
  val trips = ArrayList<LocalDayRange>()
  val events = ArrayList<LocalDate>()
  var home: Spot? = null
}

internal class Story(val name: String, val today: LocalDate, val shots: List<AlbumCandidate>, val expect: Expect, val hidden: Set<MediaId>) {
  override fun toString(): String = name
}

internal class StoryBuilder(val name: String, seed: Long, val today: LocalDate = LocalDate.of(2026, 10, 22), spanDays: Int = 730) {
  val rnd = Random(seed)
  val first: LocalDate = today.minusDays(spanDays - 1L)
  val expect = Expect()
  private val shots = ArrayList<AlbumCandidate>()
  private val residences = ArrayList<Pair<LocalDayRange, Habits>>()
  private val busy = HashSet<LocalDate>()
  private val hidden = HashSet<MediaId>()
  private var count = 0

  // --- uno scatto --------------------------------------------------------------------------------------------------------------------

  /** Uno scatto all'ora locale [local] (fuso [tz] in minuti), a [spot] (con GPS, a meno di [jitterKm]) o senza GPS se [spot] e' null. */
  fun shot(
    local: LocalDateTime,
    tz: Int,
    spot: Spot?,
    jitterKm: Double = 0.5,
    source: SmartSource = SmartSource.MY_PHONE,
    kind: MediaKind = MediaKind.PHOTO,
    screen: Boolean = false,
    gps: Boolean = true,
    placeId: Int = spot?.placeId ?: 0,
    hide: Boolean = false,
  ): AlbumCandidate {
    val id = FakeAlbumLibrary.id(++count)
    val point = if (spot != null && gps) at(spot, jitterKm) else null
    val c = AlbumCandidate(
      media = id,
      day = local.toLocalDate(),
      takenAtUtcMs = local.toInstant(ZoneOffset.ofTotalSeconds(tz * 60)).toEpochMilli(),
      localTime = local.toLocalTime(),
      gps = point,
      placeId = if (point != null) placeId else 0,
      originDevice = "questo-telefono",
      source = source,
      kind = kind,
      screen = screen,
      pixels = 6_000_000L + rnd.nextInt(8_000_000),
    )
    shots += c
    if (hide) hidden += id
    return c
  }

  private fun at(spot: Spot, km: Double): GeoPoint {
    val r = rnd.nextDouble() * km
    val a = rnd.nextDouble() * 2 * PI
    return GeoPoint(
      spot.lat + r * cos(a) / Spot.KM_PER_DEGREE,
      spot.lon + r * sin(a) / (Spot.KM_PER_DEGREE * cos(Math.toRadians(spot.lat))),
    )
  }

  private fun time(day: LocalDate, hour: Int, minute: Int): LocalDateTime = day.atTime(LocalTime.of(hour, minute))

  private fun days(from: LocalDate, to: LocalDate): Sequence<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }

  // --- cio' che succede ----------------------------------------------------------------------------------------------------------------

  /** Si abita a [habits].home dal [from] al [to]: le giornate di tutti i giorni, tranne quelle prese da altro (viaggi, soggiorni, feste). */
  fun residence(from: LocalDate, to: LocalDate, habits: Habits) {
    residences += LocalDayRange(from, to) to habits
  }

  /**
   * Un viaggio di [days] giorni da [start] a [dest] nel fuso [tz]: da [perDay] scatti al giorno, [gpsFree] sono i giorni (da 0) senza GPS,
   * [edges] aggiunge uno scatto a mezzanotte e venti del primo giorno e uno a mezzanotte meno dieci dell'ultimo (E-AL10: nell'ora UTC
   * cadono nel giorno prima e nel giorno dopo). [expected] dice se la rilevazione deve trovarlo; [hide] nasconde tutti gli scatti.
   */
  fun trip(
    start: LocalDate,
    days: Int,
    dest: Spot,
    tz: Int = 60,
    perDay: IntRange = 6..14,
    gpsFree: Set<Int> = emptySet(),
    edges: Boolean = false,
    expected: Boolean = true,
    hide: Boolean = false,
    videos: Boolean = false,
    screenshotsPerDay: Int = 0,
  ) {
    val end = start.plusDays(days - 1L)
    for (d in 0 until days) {
      val day = start.plusDays(d.toLong())
      busy += day
      val withGps = d !in gpsFree
      repeat(rnd.nextInt(perDay.first, perDay.last + 1)) {
        val kind = if (videos && rnd.nextInt(4) == 0) MediaKind.VIDEO else MediaKind.PHOTO
        shot(time(day, 9 + rnd.nextInt(13), rnd.nextInt(60)), tz, dest, 4.0, kind = kind, gps = withGps, hide = hide)
      }
      repeat(screenshotsPerDay) { shot(time(day, 10 + rnd.nextInt(10), rnd.nextInt(60)), tz, dest, screen = true) }
      if (edges && d == 0) shot(time(day, 0, 20), tz, dest, 4.0, gps = withGps, hide = hide)
      if (edges && d == days - 1) shot(time(day, 23, 50), tz, dest, 4.0, gps = withGps, hide = hide)
    }
    if (expected) expect.trips += LocalDayRange(start, end)
  }

  /** Un soggiorno abituale (casa al mare, suoceri): giorni lontani da casa che non sono un viaggio perche' tornano spesso. */
  fun stay(from: LocalDate, to: LocalDate, spot: Spot, tz: Int = 60, perDay: IntRange = 3..6) {
    for (day in days(from, to)) {
      busy += day
      repeat(rnd.nextInt(perDay.first, perDay.last + 1)) {
        val hour = if (rnd.nextInt(100) < 55) 19 + rnd.nextInt(4) else 9 + rnd.nextInt(10)
        shot(time(day, hour, rnd.nextInt(60)), tz, spot, 1.0)
      }
    }
  }

  /** Una giornata fuori ma vicina a casa (o lontana, ma con pochi scatti): [photos] scatti a [spot]. */
  fun dayOut(day: LocalDate, spot: Spot, photos: Int, tz: Int = 60) {
    busy += day
    repeat(photos) { shot(time(day, 9 + rnd.nextInt(10), rnd.nextInt(60)), tz, spot, 3.0) }
  }

  /** Giorni senza nessuna foto. */
  fun quiet(from: LocalDate, to: LocalDate) {
    days(from, to).forEach { busy += it }
  }

  /**
   * Una festa: [count] scatti a partire da [startHour]:[startMinute] per [spanMinutes] minuti a [spot] (anche oltre mezzanotte). Il giorno
   * (e quello dopo, se la festa lo attraversa) non ha altri scatti. [expected] dice se e' un evento da trovare.
   */
  fun event(day: LocalDate, startHour: Int, startMinute: Int, count: Int, spanMinutes: Int, spot: Spot?, tz: Int = 60, expected: Boolean = true, videos: Boolean = false) {
    val begin = time(day, startHour, startMinute)
    busy += day
    for (i in 0 until count) {
      val local = begin.plusMinutes(if (count == 1) 0 else (spanMinutes.toLong() * i / (count - 1)))
      busy += local.toLocalDate()
      shot(local, tz, spot, 0.3, kind = if (videos && i % 5 == 0) MediaKind.VIDEO else MediaKind.PHOTO, gps = spot != null)
    }
    if (expected) expect.events += day
  }

  /** Foto di un familiare (non sono mie): non fanno ne' casa ne' viaggi. */
  fun family(from: LocalDate, to: LocalDate, spot: Spot, perDay: Int = 4, tz: Int = 60) {
    for (day in days(from, to)) repeat(perDay) { shot(time(day, 10 + rnd.nextInt(12), rnd.nextInt(60)), tz, spot, 2.0, source = SmartSource.FAMILY) }
  }

  /** [count] screenshot (o registrazioni dello schermo) in [spanMinutes] minuti. */
  fun screenshots(day: LocalDate, startHour: Int, count: Int, spanMinutes: Int, spot: Spot? = null) {
    busy += day
    val begin = time(day, startHour, 0)
    for (i in 0 until count) shot(begin.plusMinutes(spanMinutes.toLong() * i / count), 60, spot, screen = true)
  }

  // --- la vita di tutti i giorni --------------------------------------------------------------------------------------------------------

  private fun routine(range: LocalDayRange, h: Habits) {
    for (day in days(range.from, range.to)) {
      if (day in busy || rnd.nextDouble() >= h.dayProbability) continue
      repeat(rnd.nextInt(h.perDay.first, h.perDay.last + 1)) {
        val evening = rnd.nextDouble() < h.eveningShare
        val hour = if (evening) 19 + rnd.nextInt(5) else 8 + rnd.nextInt(11)
        val local = time(day, hour, rnd.nextInt(60))
        val out = !evening && h.outings.isNotEmpty() && rnd.nextDouble() < h.outingShare * 2 || evening && h.outings.isNotEmpty() && rnd.nextDouble() < h.outingShare / 2
        val spot = if (out) h.outings[rnd.nextInt(h.outings.size)] else h.home
        val source = if (rnd.nextDouble() < h.backupOnly) SmartSource.MY_PRIVATE else SmartSource.MY_PHONE
        when {
          rnd.nextDouble() < h.screenshots -> shot(local, h.tz, spot, screen = true, source = source)
          rnd.nextDouble() < h.badGps -> shot(local, h.tz, spot, source = source).let { shots[shots.lastIndex] = withoutPlace(it) }
          rnd.nextDouble() < h.gpsMissing -> shot(local, h.tz, spot, source = source, gps = false)
          else -> shot(local, h.tz, spot, source = source)
        }
      }
    }
  }

  /** Un GPS sbagliato: il (0, 0) di una posizione mai letta. */
  private fun withoutPlace(c: AlbumCandidate): AlbumCandidate = c.copy(gps = GeoPoint(0.0, 0.0), placeId = 0)

  /** Una foto con un GPS lontano, preso da un altro posto (un errore del telefono); [day] sta in una residenza. */
  fun outlier(day: LocalDate, hour: Int, from: Spot, tz: Int = 60) {
    shot(time(day, hour, 5), tz, from, 1.0)
  }

  /** Quanti scatti su cento la Libreria nasconde (a caso): la rilevazione non li vede. */
  var hidePercent: Int = 0

  fun build(): Story {
    for ((range, habits) in residences) routine(range, habits)
    if (hidePercent > 0) shots.filter { rnd.nextInt(100) < hidePercent }.forEach { hidden += it.media }
    return Story(name, today, shots.sortedWith(compareBy<AlbumCandidate>({ it.day }, { it.takenAtUtcMs }, { it.media.value })), expect, hidden)
  }
}
