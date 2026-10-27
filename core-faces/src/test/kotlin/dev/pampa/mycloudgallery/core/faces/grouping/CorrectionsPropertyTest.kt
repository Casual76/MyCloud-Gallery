package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingOutcome
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.random.Random
import org.junit.Test

/**
 * LE CORREZIONI DELL'UTENTE VALGONO PER SEMPRE (D23, E-F20, E-F21, E-F27; riga P5 del §4.4 "correzioni sempre rispettate (proprieta' su
 * 10.000 storie casuali)", "anche dopo un ricalcolo"). Ogni storia: una famiglia piccola che arriva a lotti; a ogni giro un passo a caso
 * (incrementale, provvisorio, notte, a volte con contenuti spariti, a volte un ricalcolo completo con i vettori di un "modello nuovo") e
 * correzioni a caso ("E' …" verso persone esistenti o nuove, "Non e' …", "Ignora", "Unisci", "Dimentica", "Separati", nomi, "Dividi",
 * lapidi di "E' …"). Dopo ogni passo valgono tutte le regole di [Invariants]; in piu' i volti di una persona dimenticata non tornano mai
 * automatici.
 */
class CorrectionsPropertyTest {
  private val engine = FastGrouping()

  @Test
  fun leCorrezioniSonoRispettateSu10000Storie() {
    var steps = 0
    val counts = IntArray(4)
    for (seed in 1L..10_000L) {
      val random = Random(seed * 13 + 5)
      val story = FamilyStories.story(
        Scenarios.config(seed).copy(photos = 8 + random.nextInt(24), identities = 3 + random.nextInt(4), siblings = 0, twins = random.nextInt(2), children = 0),
      )
      val arrivals = Scenarios.arrivals(story.faces, 1 + random.nextInt(3), random)
      val ids = GroupingStories.ids(seed)
      val correctionIds = GroupingStories.ids(seed + 50_000_000)
      var faces = arrivals.first()
      var truth = story.truth
      var state = GroupingState.EMPTY
      var corrections = GroupingCorrections.NONE
      var gone = emptySet<ContentKey>()
      var forgottenFaces = emptySet<FaceLocator>()
      var everGone = emptySet<ContentKey>()
      var everFixed = emptySet<FaceLocator>()
      var next = 1
      repeat(3 + random.nextInt(5)) { round ->
        if (next < arrivals.size && random.nextBoolean()) faces = arrivals[next++]
        val params = if (round == 0) GroupingParams.DEFAULT.copy(birthsEvery = 1) else GroupingParams.DEFAULT.copy(birthsEvery = 1 + random.nextInt(20))
        val previous = state
        val kind = random.nextInt(10)
        val outcome: GroupingOutcome = when {
          kind < 4 -> engine.incremental(GroupingInput(state, faces, corrections, params, gone), ids)
          kind < 5 -> engine.provisional(GroupingInput(state, faces, corrections, params, gone))
          kind < 9 -> {
            if (random.nextInt(5) == 0) gone = gone + Scenarios.gone(faces, random, 0.1f)
            engine.nightly(GroupingInput(state, faces, corrections, params, gone), ids)
          }
          else -> {
            val (after, afterTruth) = Scenarios.recomputed(faces, truth, seed + round)
            faces = after
            truth = truth + afterTruth
            engine.recluster(GroupingInput(state, faces, corrections, params, gone), state, ids)
          }
        }
        counts[minOf(3, kind / 3)]++
        state = outcome.state
        steps++
        val broken = Invariants.violations(state, faces, corrections, gone, previous, params)
        assertWithMessage("storia $seed, giro $round, passo $kind").that(broken).isEmpty()
        // "Dimentica": i volti che la persona aveva restano fuori dagli automatici (E-F27), anche dopo un ricalcolo (per posto). Un contenuto
        // sparito e tornato (E-F39) e un volto assegnato a mano e poi liberato (lapide di "E' …") sono fuori dalla regola: tornano come
        // volti nuovi (punto aperto in docs/decisioni.md).
        everGone = everGone + gone
        val back = state.assignments.values.filter { a ->
          a.origin == AssignOrigin.AUTO && a.face.contentKey !in everGone && forgottenFaces.any { it.matches(a.locator) } &&
            everFixed.none { it.matches(a.locator) }
        }
        assertWithMessage("storia $seed, giro $round: volti di una persona dimenticata di nuovo automatici").that(back).isEmpty()
        // Le correzioni nuove si scrivono sullo stato appena visto (come fa l'utente).
        if (random.nextInt(3) > 0) {
          val before = corrections.forgotten
          corrections = Scenarios.corrections(corrections, state, faces, random, correctionIds, 1 + random.nextInt(3))
          everFixed = everFixed + corrections.fixed.keys
          val newly: Set<PersonId> = corrections.forgotten - before
          if (newly.isNotEmpty()) {
            val refs = Invariants.liveFacesOf(state, newly)
            forgottenFaces = forgottenFaces + state.assignments.values.filter { it.face in refs }.map { it.locator }
          }
        }
      }
    }
    println("Correzioni: $steps passi su 10.000 storie (incrementali/provvisori/notti/ricalcoli per gruppi: ${counts.toList()})")
  }
}
