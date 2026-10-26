package dev.pampa.mycloudgallery.core.albums.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.TripRules
import dev.pampa.mycloudgallery.core.albums.trips.Story
import dev.pampa.mycloudgallery.core.albums.trips.StoryLibrary
import dev.pampa.mycloudgallery.core.albums.trips.TripStories
import dev.pampa.mycloudgallery.core.albums.trips.TripsHarness
import dev.pampa.mycloudgallery.core.model.TripKind
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * F89 SUI VIAGGI SINTETICI (docs/fase7-progetto.md §5.3 punto 1 e §2.3, riga P12 del §4.4): le 20 storie di due anni di P7 con la verita' scritta dal
 * generatore (88 viaggi e 15 eventi). L'utente ideale scrive l'elenco dei viaggi principali **prima** di guardare (qui: la verita' della storia),
 * poi il confronto con le proposte di `CatalogTrips` -- la stessa strada dello strumento sul telefono -- dice quali sono stati trovati.
 */
class SyntheticTripsFoundTest {

  private class Found(val story: Story, val result: TripsFound, val listed: List<EvalTrip>)

  private fun compare(story: Story, drop: Int = -1): Found = runBlocking {
    TripsHarness(StoryLibrary(story.shots).also { it.hidden += story.hidden }, story.today).use { h ->
      val report = h.trips.detect(1.minutes)
      val proposed = h.trips.proposals.value.proposals.map { ProposedDays(it.days.from, it.days.to, it.kind == TripKind.TRIP) }
      val listed = story.expect.trips.mapIndexed { i, range -> EvalTrip("v%02d".format(i + 1), range.from, range.to, null) }
      val shown = if (drop >= 0) proposed.filterIndexed { i, _ -> i != drop } else proposed
      Found(story, TripsMatching.compare(listed, shown, TripRules.firstScreenSince(story.today), report.homeKnown), listed)
    }
  }

  @Test
  fun tuttiIViaggiDelleVentiStorieSonoFraLeProposteENessunViaggioInPiu() {
    val all = TripStories.all().map { compare(it) }
    val listed = all.sumOf { it.result.listed }
    val found = all.sumOf { it.result.found }
    val unlisted = all.sumOf { it.result.unlisted }
    println("VALUTAZIONE viaggi: storie=${all.size} nell-elenco=$listed trovati=$found toccati=${all.sumOf { it.result.touched }} mancanti=${all.sumOf { it.result.missing }} proposte-in-piu=$unlisted eventi=${all.sumOf { it.result.eventProposals }}")
    assertThat(all).hasSize(20)
    assertThat(all.count { !it.result.homeKnown }).isEqualTo(1)
    assertThat(listed).isEqualTo(88)
    assertWithMessage("viaggi dell'elenco non trovati").that(found).isEqualTo(listed)
    assertWithMessage("proposte di viaggio che non sono nell'elenco (falsi viaggi a casa)").that(unlisted).isEqualTo(0)
    for (f in all) {
      assertWithMessage("storia ${f.story.name}").that(f.result.verdicts.values.toSet()).containsNoneOf(TripVerdict.MISSING, TripVerdict.TOUCHED)
      // F89 vale per le storie con almeno un viaggio; una storia senza viaggi non ha proposte di viaggio (senza GPS nemmeno la casa, E-AL16).
      if (f.listed.isNotEmpty()) assertThat(f.result.meets).isTrue() else assertThat(f.result.tripProposals).isEqualTo(0)
    }
  }

  /** Il confronto si accorge di un viaggio che manca: con una proposta tolta, quel viaggio non e' piu' "trovato". */
  @Test
  fun unaPropostaCheMancaSiVede() {
    val story = TripStories.all().first { it.expect.trips.size >= 3 }
    val whole = compare(story)
    assertThat(whole.result.meets).isTrue()
    val shown = whole.result.found
    // Si toglie la prima proposta di viaggio (le proposte sono dal piu' recente: quella che corrisponde al viaggio piu' recente dell'elenco).
    val without = compare(story, drop = firstTripIndex(story))
    assertThat(without.result.found).isEqualTo(shown - 1)
    assertThat(without.result.missing + without.result.touched).isEqualTo(1)
    assertThat(without.result.meets).isFalse()
    assertThat(without.result.toNumbersText()).contains("NON raggiunto")
    assertThat(whole.result.toNumbersText()).contains("F89: RAGGIUNTO")
  }

  private fun firstTripIndex(story: Story): Int = runBlocking {
    TripsHarness(StoryLibrary(story.shots).also { it.hidden += story.hidden }, story.today).use { h ->
      h.trips.detect(1.minutes)
      h.trips.proposals.value.proposals.indexOfFirst { it.kind == TripKind.TRIP }
    }
  }
}
