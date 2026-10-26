package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * PRESTAZIONI (docs/fase7-progetto.md §2.3 "Quando", F91; riga P7 del §4.4): **meno di 5 secondi su 100 mila elementi, sulla JVM**. Dieci anni
 * di una Libreria da ~105.000 media propri (una cinquantina di scatti nei giorni con foto), 26 viaggi e 6 feste enormi; si misura l'intera
 * rilevazione di `CatalogTrips` (lettura a pagine, visibilita', casa di ogni mese, viaggi, eventi, titoli, memoria locale e ricontrollo di
 * ogni proposta), a freddo (prima rilevazione della JVM, JIT compreso) e a caldo. I numeri sono in `docs/decisioni.md`.
 */
class TripPerformanceTest {

  private fun big(): Story {
    val today = LocalDate.of(2026, 10, 22)
    val b = StoryBuilder("grande", 99, today, spanDays = 3_650)
    val home = Places.FIRENZE
    val dests = listOf(Places.OSLO, Places.ROMA, Places.PARIGI, Places.LONDRA, Places.MONACO, Places.LISBONA, Places.BERLINO, Places.TOKYO, Places.NEW_YORK)
    var start = b.first.plusDays(40)
    var n = 0
    val away = ArrayList<LocalDayRange>()
    while (start.plusDays(12).isBefore(today)) {
      val dest = dests[n++ % dests.size]
      away += LocalDayRange(start.minusDays(1), start.plusDays(5L + n % 5 + 1))
      b.trip(start, 5 + n % 5, dest, tz = if (dest === Places.TOKYO) 540 else if (dest === Places.NEW_YORK) -240 else 60, perDay = 20..40)
      start = start.plusDays(140)
    }
    for (year in 2018..2026) LocalDate.of(year, 6, 14).takeIf { day -> away.none { day in it } }?.let { b.event(it, 19, 0, 400, 200, home) }
    b.residence(b.first, today, Habits(home, dayProbability = 0.6, perDay = 30..60, outings = listOf(home.moved(4.0, 3.0, 7_100_001), home.moved(-9.0, 12.0, 7_100_002))))
    return b.build()
  }

  @Test
  fun centomilaElementiInMenoDiCinqueSecondi() = runBlocking<Unit> {
    val story = big()
    assertThat(story.shots.size).isAtLeast(100_000)
    val library = StoryLibrary(story.shots)
    TripsHarness(library, story.today).use { h ->
      val cold = timed { h.trips.detect(1.minutes) }
      val shown = h.trips.proposals.value
      val warm = timed { h.trips.detect(1.minutes) }
      println("PRESTAZIONI trips: ${story.shots.size} elementi, a freddo $cold ms, a caldo $warm ms, ${shown.proposals.count { it.kind == TripKind.TRIP }} viaggi, ${shown.proposals.count { it.kind == TripKind.EVENT }} eventi")
      assertThat(shown.homeKnown).isTrue()
      assertThat(shown.proposals.count { it.kind == TripKind.TRIP }).isAtLeast(25)
      assertThat(shown.proposals.count { it.kind == TripKind.EVENT }).isAtLeast(8)
      assertThat(cold).isLessThan(5_000)
      assertThat(warm).isLessThan(5_000)
      assertThat(h.trips.proposals.value).isEqualTo(shown)
    }
  }

  /** Il solo rilevatore (senza lettura, visibilita' e memoria): la parte che costa con il numero di elementi. */
  @Test
  fun ilRilevatorePuroEVeloceEdELineare() {
    val story = big()
    val shots = story.shots.filter { it.mine && !it.screen }
    val half = shots.filter { it.day.isBefore(LocalDate.of(2022, 1, 1)) }
    TripDetector.detect(shots, story.today)
    val full = timed { TripDetector.detect(shots, story.today) }
    val part = timed { TripDetector.detect(half, LocalDate.of(2021, 12, 31)) }
    println("PRESTAZIONI rilevatore: ${shots.size} elementi in $full ms, ${half.size} in $part ms")
    assertThat(full).isLessThan(3_000)
    // Raddoppiare gli elementi non quadruplica il tempo: niente di quadratico (tolleranza larga per il rumore di una JVM condivisa).
    assertThat(full).isAtMost(part * 8 + 200)
  }

  private inline fun <T> timed(block: () -> T): Long {
    val t0 = System.nanoTime()
    block()
    return (System.nanoTime() - t0) / 1_000_000
  }
}
