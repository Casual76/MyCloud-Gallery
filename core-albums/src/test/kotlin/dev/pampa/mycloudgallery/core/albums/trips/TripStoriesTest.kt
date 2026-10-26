package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * VIAGGI ED EVENTI SU 20 STORIE SINTETICHE DI DUE ANNI (docs/fase7-progetto.md §5.3, riga P7 del §4.4): **tutti i viaggi trovati e nessun
 * falso viaggio a casa**. Per ogni storia (`TripStories`): i viaggi e gli eventi che la rilevazione propone sono esattamente quelli della
 * verita' (stessi giorni, locali dello scatto: E-AL10), la casa e' dove si abita oggi (E-AL15) o non c'e' (E-AL16), i conteggi e le copertine
 * sono quelli dei media visibili (E-H7), i titoli sono titoli validi, e una seconda rilevazione da' lo stesso risultato.
 */
class TripStoriesTest {

  @Test
  fun leVentiStorieDannoEsattamenteIViaggiEGliEventiAttesi() = runBlocking<Unit> {
    val stories = TripStories.all()
    assertThat(stories).hasSize(20)
    assertThat(stories.map { it.name }).containsNoDuplicates()
    val problems = stories.flatMap { story -> check(story).map { "${story.name}: $it" } }
    assertWithMessage("storie con viaggi o eventi sbagliati").that(problems).isEmpty()
  }

  /** Le storie hanno di che provare: viaggi, eventi, traslochi, fusi, nascosti, familiari, screenshot e GPS sbagliati non sono parole. */
  @Test
  fun leStorieSonoInteressanti() {
    val stories = TripStories.all()
    println("STORIE: ${stories.size} storie, ${stories.sumOf { it.shots.size }} scatti, ${stories.sumOf { it.expect.trips.size }} viaggi attesi, ${stories.sumOf { it.expect.events.size }} eventi attesi")
    assertThat(stories.sumOf { it.expect.trips.size }).isAtLeast(70)
    assertThat(stories.sumOf { it.expect.events.size }).isAtLeast(15)
    assertThat(stories.count { it.expect.home == null }).isEqualTo(1)
    assertThat(stories.count { it.hidden.isNotEmpty() }).isAtLeast(2)
    assertThat(stories.count { s -> s.shots.any { it.screen } }).isAtLeast(5)
    assertThat(stories.count { s -> s.shots.any { it.source == dev.pampa.mycloudgallery.core.albums.contract.SmartSource.FAMILY } }).isAtLeast(2)
    assertThat(stories.count { s -> s.shots.any { it.gps != null && it.gps!!.latitude == 0.0 } }).isAtLeast(2)
    // Fusi orari (E-AL10): ci sono scatti il cui giorno locale non e' quello UTC.
    assertThat(stories.count { s -> s.shots.any { java.time.Instant.ofEpochMilli(it.takenAtUtcMs).atZone(java.time.ZoneOffset.UTC).toLocalDate() != it.day } }).isAtLeast(2)
    // Ripetibili: due costruzioni della stessa storia sono la stessa storia.
    assertThat(TripStories.all().map { it.shots.size }).isEqualTo(stories.map { it.shots.size })
  }

  private fun check(story: Story): List<String> = runBlocking {
    val problems = ArrayList<String>()
    val library = StoryLibrary(story.shots).also { it.hidden += story.hidden }
    TripsHarness(library, story.today).use { h ->
      val report = h.trips.detect(1.minutes)
      val shown = h.trips.proposals.value
      val trips = shown.proposals.filter { it.kind == TripKind.TRIP }
      val events = shown.proposals.filter { it.kind == TripKind.EVENT }

      val wantTrips = story.expect.trips.toSet()
      val foundTrips = trips.map { it.days }.toSet()
      if (foundTrips != wantTrips) {
        problems += "viaggi mancanti: ${(wantTrips - foundTrips).map(::text)}; viaggi di troppo (falsi): ${(foundTrips - wantTrips).map(::text)}"
      }
      if (trips.size != foundTrips.size) problems += "due viaggi con gli stessi giorni"
      val wantEvents = story.expect.events.toSet()
      val foundEvents = events.map { it.days.from }.toSet()
      if (foundEvents != wantEvents) {
        problems += "eventi mancanti: ${(wantEvents - foundEvents).sorted()}; eventi di troppo: ${(foundEvents - wantEvents).sorted()}"
      }
      if (events.size != foundEvents.size) problems += "due eventi con lo stesso inizio"
      if (report.trips != trips.size || report.events != events.size) problems += "il resoconto (${report.trips} viaggi, ${report.events} eventi) non e' quello delle proposte"

      val home = story.expect.home
      if (report.homeKnown != (home != null) || shown.homeKnown != (home != null)) problems += "la casa e' ${if (report.homeKnown) "stimata" else "non stimata"}, doveva essere ${if (home != null) "stimata" else "assente"}"
      val anchor = h.store.home?.home
      if (home != null && anchor != null) {
        val away = TripGeometry.km(home.lat, home.lon, anchor.lat, anchor.lon)
        if (away > 3.0) problems += "la casa stimata e' a ${away.toInt()} km da quella vera"
      }
      if (home == null && h.store.home != null) problems += "una casa conservata senza GPS"

      // Conteggi e copertine sono dei media visibili, propri, non screenshot; i titoli sono titoli validi.
      for (p in shown.proposals) {
        val members = story.shots.filter { it.mine && !it.screen && it.media !in story.hidden && it.day in p.days }
        if (p.count != members.size) problems += "una proposta conta ${p.count} media, ce ne sono ${members.size}"
        if (p.cover !in members.map { it.media }) problems += "la copertina di una proposta non e' un suo media visibile"
        if (AlbumTitles.problem(p.label) != null || p.label.isBlank()) problems += "titolo non valido: ${p.label}"
        if (p.id != dev.pampa.mycloudgallery.core.albums.contract.TripId.of(p.kind, p.days, p.placeIds)) problems += "l'impronta non e' quella di tipo, giorni e luoghi"
      }
      // Un viaggio ha il luogo della meta (quando il GPS c'era).
      for (p in trips) {
        val dest = story.shots.filter { it.day in p.days && it.gps != null && it.placeId != 0 && it.media !in story.hidden && !it.screen && it.mine }
        if (dest.isNotEmpty() && p.placeIds.isEmpty()) problems += "un viaggio con luoghi noti non li ha"
      }
      // Ripetibile e senza effetti: una seconda rilevazione da' le stesse proposte.
      h.trips.detect(1.minutes)
      if (h.trips.proposals.value != shown) problems += "due rilevazioni di fila danno proposte diverse"
    }
    problems
  }

  private fun text(range: LocalDayRange): String = "${range.from}..${range.to}"

}
