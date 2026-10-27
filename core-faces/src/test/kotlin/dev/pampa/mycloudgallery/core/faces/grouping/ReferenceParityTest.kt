package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingEngine
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingOutcome
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.faces.testing.ReferenceGrouping
import dev.pampa.mycloudgallery.core.model.ContentKey
import kotlin.random.Random
import org.junit.Test

/**
 * LA PARITA' COL RIFERIMENTO (docs/fase8-progetto.md §5.2 `ReferenceGrouping`): su **500 storie** sintetiche il nucleo vero da' gli stessi
 * gruppi del riferimento a forza bruta, salvo i casi a +-1e-4 dalle soglie, e gli stessi id.
 *
 * Ogni storia e' una famiglia a caso ([Scenarios.config]: fratelli, gemelli, eta', deboli, sconosciuti, video, specchi, poster) che arriva
 * in 1-3 lotti; per ogni lotto: incrementale, a volte provvisorio, correzioni a caso, notte (a volte con contenuti spariti); alla fine, a
 * volte, un ricalcolo completo con i vettori di un "modello nuovo". Ogni passo parte dallo **stesso** stato (quello del riferimento) sui due
 * nuclei con lo stesso generatore di id: gli esiti devono essere **uguali in tutto** (stato con numeri, persone, esemplari, assegnazioni e
 * punteggi; proposte; numeri del passo), perche' fuori dal legame medio delle nascite i conti sono gli stessi bit per bit. Se differiscono,
 * il caso e' ammesso solo se il riferimento con le soglie spostate di 1e-4 (in su o in giu') da' l'esito del nucleo vero; i casi ammessi si
 * contano e devono restare rari.
 */
class ReferenceParityTest {
  private val reference = ReferenceGrouping()
  private val fast = FastGrouping()

  private var steps = 0
  private var nearThreshold = 0
  private val failures = ArrayList<String>()

  private fun same(a: GroupingOutcome, b: GroupingOutcome): Boolean =
    a.state.seq == b.state.seq && a.state.base == b.state.base && a.state.unassignedAtBirths == b.state.unassignedAtBirths &&
      a.state.persons == b.state.persons && a.state.assignments == b.state.assignments && a.suggestions == b.suggestions &&
      a.report == b.report

  private fun shifted(params: GroupingParams, by: Float) = params.copy(
    tJoin = params.tJoin + by, tJoinWeak = params.tJoinWeak + by, margin = params.margin + by, tEdge = params.tEdge + by,
    tBirth = params.tBirth + by, tMerge = params.tMerge + by, tSuggest = params.tSuggest + by, tEject = params.tEject + by,
  )

  /** Esegue [step] sui due nuclei con lo stesso generatore di id e confronta; restituisce l'esito del riferimento. */
  private fun check(label: String, idsSeed: Long, input: GroupingInput, step: (GroupingEngine, GroupingInput, PersonIdSource) -> GroupingOutcome): GroupingOutcome {
    steps++
    val expected = step(reference, input, GroupingStories.ids(idsSeed))
    val actual = step(fast, input, GroupingStories.ids(idsSeed))
    if (same(expected, actual)) return expected
    val tolerated = listOf(1e-4f, -1e-4f).any { by ->
      same(step(reference, input.copy(params = shifted(input.params, by)), GroupingStories.ids(idsSeed)), actual)
    }
    if (tolerated) nearThreshold++ else failures += label
    return expected
  }

  @Test
  fun stessiGruppiEStessiIdDelRiferimentoSu500Storie() {
    for (seed in 1L..500L) runStory(seed)
    println("Parita' col riferimento: $steps passi, $nearThreshold casi a 1e-4 dalle soglie, ${failures.size} differenze")
    assertWithMessage("passi diversi dal riferimento (non vicini alle soglie): ${failures.take(20)}").that(failures).isEmpty()
    assertWithMessage("casi vicini alle soglie").that(nearThreshold.toDouble() / steps).isAtMost(0.01)
  }

  private fun runStory(seed: Long) {
    val story = FamilyStories.story(Scenarios.config(seed))
    val random = Random(seed * 7 + 3)
    val arrivals = Scenarios.arrivals(story.faces, 1 + random.nextInt(3), random)
    val correctionIds = GroupingStories.ids(seed + 9_000_000)
    var state = GroupingState.EMPTY
    var corrections = GroupingCorrections.NONE
    var gone = emptySet<ContentKey>()
    var step = 0
    fun label(name: String) = "storia $seed, $name ${step++}"
    for ((k, faces) in arrivals.withIndex()) {
      val first = if (k == 0) GroupingParams.DEFAULT.copy(birthsEvery = 1) else GroupingParams.DEFAULT
      state = check(label("incrementale"), seed * 100 + step, GroupingInput(state, faces, corrections, first, gone)) { e, i, ids -> e.incremental(i, ids) }.state
      if (random.nextInt(3) == 0) {
        val extra = (faces + story.faces.filter { random.nextInt(4) == 0 }).distinctBy { it.ref }.sortedBy { it.ref }
        check(label("provvisorio"), seed * 100 + step, GroupingInput(state, extra, corrections, GroupingParams.DEFAULT, gone)) { e, i, _ -> e.provisional(i) }
      }
      if (random.nextInt(2) == 0) corrections = Scenarios.corrections(corrections, state, faces, random, correctionIds, 1 + random.nextInt(4))
      if (random.nextInt(6) == 0) gone = gone + Scenarios.gone(faces, random, 0.05f)
      state = check(label("notte"), seed * 100 + step, GroupingInput(state, faces, corrections, GroupingParams.DEFAULT, gone)) { e, i, ids -> e.nightly(i, ids) }.state
    }
    if (random.nextInt(4) == 0) {
      val (after, _) = Scenarios.recomputed(story.faces, story.truth, seed)
      val previous = state
      state = check(label("ricalcolo"), seed * 100 + step, GroupingInput(previous, after, corrections, GroupingParams.DEFAULT, gone)) { e, i, ids ->
        e.recluster(i, previous, ids)
      }.state
      check(label("notte dopo il ricalcolo"), seed * 100 + step, GroupingInput(state, after, corrections, GroupingParams.DEFAULT, gone)) { e, i, ids ->
        e.nightly(i, ids)
      }
    }
  }

  /** Un controllo della prova stessa: sulle storie del contratto i due nuclei danno lo stesso esito anche partendo da zero in un passo. */
  @Test
  fun lePrimeNascitePartonoUguali() {
    for (seed in 1L..20L) {
      val story = GroupingStories.story(seed, identities = 8, photos = 80, maxFacesPerPhoto = 3, weakShare = 0.2f, twins = 1)
      val input = GroupingInput(GroupingState.EMPTY, story.faces, params = GroupingParams.DEFAULT.copy(birthsEvery = 1))
      val a = reference.incremental(input, GroupingStories.ids(seed))
      val b = fast.incremental(input, GroupingStories.ids(seed))
      assertThat(b.state.assignments).isEqualTo(a.state.assignments)
      assertThat(b.state.persons).isEqualTo(a.state.persons)
    }
  }
}
