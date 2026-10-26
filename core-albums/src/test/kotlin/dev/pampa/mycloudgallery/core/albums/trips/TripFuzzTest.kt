package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import java.time.LocalDate
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 200 VITE CASUALI (docs/fase7-progetto.md §5.3, riga P7 del §4.4): oltre alle venti storie scritte a mano, vite generate dal seme con la casa in
 * una citta' a caso, da tre a otto viaggi lontani da 120 a 1.500 km in una direzione a caso (da 2 a 12 giorni), qualche fine settimana in un
 * luogo abituale, qualche festa, foto senza GPS, screenshot, GPS sbagliati, familiari e nascosti. Viaggi, soggiorni e feste non si toccano (restano
 * almeno quattro giorni liberi fra uno e l'altro): la verita' e' sempre una sola. La rilevazione deve trovare **tutti** i viaggi e **tutte**
 * le feste e **nient'altro**.
 */
class TripFuzzTest {

  private val today = LocalDate.of(2026, 10, 22)

  private val cities = listOf(
    Places.FIRENZE, Places.MILANO, Places.ROMA, Places.TORINO, Places.BOLOGNA, Places.FIRENZE.moved(-250.0, 150.0, 7_000_001, "Napoli"),
    Places.FIRENZE.moved(-500.0, 300.0, 7_000_002, "Bari"), Places.MILANO.moved(40.0, 120.0, 7_000_003, "Bergamo"),
  )

  /** Una vita casuale con la sua verita'. */
  private fun life(seed: Long): Story {
    val random = Random(seed)
    val b = StoryBuilder("vita-$seed", seed, today)
    val home = cities[random.nextInt(cities.size)]
    val busy = ArrayList<LocalDayRange>()

    /** Un periodo libero di [days] giorni (con quattro giorni liberi attorno), o null se non si trova. */
    fun slot(days: Int): LocalDate? {
      repeat(40) {
        val start = b.first.plusDays(10L + random.nextInt(690 - days))
        val range = LocalDayRange(start.minusDays(4), start.plusDays(days + 3L))
        if (busy.none { other -> other.from <= range.to && range.from <= other.to }) {
          busy += range
          return start
        }
      }
      return null
    }

    // Il luogo abituale (a 60-90 km), se c'e', si sceglie prima: una meta a meno di 50 km da lui non e' lontana e non sarebbe un viaggio.
    val habitual = if (random.nextBoolean()) home.moved(65.0, 40.0, 8_900_000 + seed.toInt(), "Abituale") else null
    var places = 0

    /** Una meta a 120-1.500 km in una direzione a caso, mai a meno di 110 km dal luogo abituale. */
    fun destination(): Spot {
      var dest: Spot
      do {
        val distance = 120.0 + random.nextInt(1_380)
        val bearing = random.nextDouble() * 2 * Math.PI
        dest = home.moved(distance * Math.cos(bearing), distance * Math.sin(bearing), 8_000_000 + seed.toInt() * 10 + places++, "Meta $places")
      } while (habitual != null && TripGeometry.km(dest.lat, dest.lon, habitual.lat, habitual.lon) < 110.0)
      return dest
    }
    repeat(3 + random.nextInt(6)) {
      val days = 2 + random.nextInt(11)
      val start = slot(days) ?: return@repeat
      b.trip(start, days, destination(), perDay = 5..12)
    }
    // La vacanza di un mese, in meta' delle vite: un solo soggiorno lungo, che da solo pesa piu' del 10% dei giorni, e' un viaggio.
    if (random.nextBoolean()) {
      val days = 25 + random.nextInt(16)
      slot(days)?.let { b.trip(it, days, destination(), perDay = 3..6) }
    }
    repeat(random.nextInt(4)) {
      val start = slot(1) ?: return@repeat
      b.event(start, 18 + random.nextInt(2), random.nextInt(60), 60 + random.nextInt(80), 120 + random.nextInt(100), home)
    }
    if (habitual != null) {
      // I fine settimana di una parte dell'anno (non dei giorni gia' occupati).
      val spot = habitual
      var saturday = b.first.plusDays(((6 - b.first.dayOfWeek.value + 7) % 7).toLong())
      val odds = listOf(80, 90, 100)[random.nextInt(3)]
      while (saturday.plusDays(1).isBefore(today)) {
        val range = LocalDayRange(saturday, saturday.plusDays(1))
        if (random.nextInt(100) < odds && busy.none { it.from <= range.to && range.from <= it.to }) b.stay(saturday, saturday.plusDays(1), spot)
        saturday = saturday.plusDays(7)
      }
    }
    b.residence(
      b.first, today,
      Habits(
        home, dayProbability = 0.45 + random.nextDouble() * 0.2, perDay = 1..4, outings = listOf(home.moved(4.0, 3.0, 7_100_001), home.moved(-20.0, 15.0, 7_100_002)),
        gpsMissing = random.nextDouble() * 0.06, badGps = random.nextDouble() * 0.03, screenshots = random.nextDouble() * 0.06,
      ),
    )
    if (random.nextBoolean()) b.family(b.first, today, home.moved(1.0, 1.0), perDay = 1)
    b.hidePercent = random.nextInt(12)
    b.expect.home = home
    return b.build()
  }

  @Test
  fun dugentoVitePerCasoTuttiIViaggiENessunFalsoViaggio() = runBlocking<Unit> {
    val problems = ArrayList<String>()
    var trips = 0
    var events = 0
    for (seed in 1L..200L) {
      val story = life(seed)
      val library = StoryLibrary(story.shots).also { it.hidden += story.hidden }
      TripsHarness(library, story.today).use { h ->
        h.trips.detect(1.minutes)
        val found = h.trips.proposals.value.proposals
        val foundTrips = found.filter { it.kind == dev.pampa.mycloudgallery.core.model.TripKind.TRIP }.map { it.days }.toSet()
        val foundEvents = found.filter { it.kind == dev.pampa.mycloudgallery.core.model.TripKind.EVENT }.map { it.days.from }.toSet()
        // Un viaggio i cui giorni sono quasi tutti nascosti puo' non esserci (il test non lo pretende): la verita' e' cio' che resta con i visibili.
        val visible = story.shots.filter { it.mine && !it.screen && it.media !in story.hidden }
        val wantTrips = story.expect.trips.filter { visibleDays(visible, it) >= 2 }.toSet()
        val wantEvents = story.expect.events.toSet()
        trips += wantTrips.size
        events += wantEvents.size
        val missing = wantTrips - foundTrips
        val extra = foundTrips - story.expect.trips.toSet()
        if (missing.isNotEmpty() || extra.isNotEmpty()) problems += "[$seed] viaggi mancanti ${missing.map { "${it.from}..${it.to}" }}, di troppo ${extra.map { "${it.from}..${it.to}" }}"
        // Una festa di almeno 60 scatti con al piu' l'11% nascosto ne ha sempre piu' di 50: sopra qualunque soglia di ritmo di queste vite.
        val missingEvents = wantEvents.filter { day -> visible.count { it.day == day || it.day == day.plusDays(1) } >= 50 } - foundEvents
        val extraEvents = foundEvents - wantEvents
        if (missingEvents.isNotEmpty() || extraEvents.isNotEmpty()) problems += "[$seed] eventi mancanti $missingEvents, di troppo $extraEvents"
      }
    }
    println("FUZZ viaggi attesi $trips, eventi attesi $events")
    assertThat(trips).isGreaterThan(500)
    assertWithMessage("vite con viaggi o eventi sbagliati").that(problems).isEmpty()
  }

  /** In quanti giorni distinti di [range] ci sono scatti propri geolocalizzati visibili. */
  private fun visibleDays(visible: List<dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate>, range: LocalDayRange): Int =
    visible.filter { it.gps != null && it.day in range }.map { it.day }.toSet().size
}
