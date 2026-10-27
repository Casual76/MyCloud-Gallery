package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import kotlin.random.Random
import org.junit.Test

/**
 * LA QUALITA' SULLE IDENTITA' SINTETICHE (riga P5 del §4.4: "purezza >= 99% sul sintetico", "frammentazione dichiarata"; §5.3; il brief di
 * P5: purezza >= 97% misurata sui gruppi, copertura come da progetto, nessun gruppo con due volti della stessa foto, nessuna persona sotto
 * 3 volti in 2 foto). Famiglie sintetiche ([FamilyStories]) raggruppate come sul telefono: le foto arrivano a lotti, a ogni lotto
 * l'incrementale, poi la notte. Le soglie sono quelle provvisorie del progetto ([GroupingParams.DEFAULT], Q4: la misura sui dati veri e'
 * dell'utente, F105).
 *
 * Due insiemi: le famiglie **tipiche** (fratelli a coseno 0,6, eta' fino a 20 anni, deboli, sconosciuti, video, specchi) e quelle
 * **difficili** (anche gemelli a 0,8, bambini che crescono, poster). Le misure si stampano e finiscono in `docs/decisioni.md`.
 */
class GroupingQualityTest {
  private val engine = FastGrouping()

  private class Measure(var groups: Int = 0, var pure: Int = 0, var faces: Int = 0, var wrongFaces: Int = 0, var coverable: Double = 0.0, var fragments: Double = 0.0, var stories: Int = 0) {
    val purity: Double get() = if (groups == 0) 1.0 else pure.toDouble() / groups
    val errors: Double get() = if (faces == 0) 0.0 else wrongFaces.toDouble() / faces
    override fun toString(): String =
      "purezza %.4f (%d/%d gruppi), errori per volto %.4f, copertura dei raggruppabili %.3f, frammentazione %.2f".format(
        purity, pure, groups, errors, coverable / stories, fragments / stories,
      )
  }

  /** La storia raggruppata come sul telefono: [parts] lotti con l'incrementale, una notte dopo ognuno. Controlla le regole a ogni passo. */
  private fun group(story: FamilyStories.Story, seed: Long, parts: Int): GroupingState {
    val random = Random(seed)
    val ids = GroupingStories.ids(seed)
    var state = GroupingState.EMPTY
    for ((k, faces) in Scenarios.arrivals(story.faces, parts, random).withIndex()) {
      val params = if (k == 0) GroupingParams.DEFAULT.copy(birthsEvery = 1) else GroupingParams.DEFAULT
      val before = state
      state = engine.incremental(GroupingInput(state, faces, params = params), ids).state
      check(state, faces, before, seed)
      val mid = state
      state = engine.nightly(GroupingInput(state, faces), ids).state
      check(state, faces, mid, seed)
    }
    return state
  }

  private fun check(state: GroupingState, faces: List<GroupFace>, previous: GroupingState, seed: Long) {
    val broken = Invariants.violations(state, faces, GroupingCorrections.NONE, previous = previous)
    assertWithMessage("storia $seed").that(broken).isEmpty()
  }

  private fun measure(configs: List<FamilyStories.Config>): Measure {
    val m = Measure()
    for (config in configs) {
      val story = FamilyStories.story(config)
      val state = group(story, config.seed, 4)
      val groups = FamilyStories.groups(state)
      m.groups += groups.size
      m.pure += (FamilyStories.purity(state, story.truth) * groups.size + 0.5).toInt()
      val assigned = groups.values.sumOf { it.size }
      m.faces += assigned
      m.wrongFaces += (FamilyStories.faceErrors(state, story.truth) * assigned + 0.5).toInt()
      m.coverable += FamilyStories.coverableCoverage(state, story)
      m.fragments += FamilyStories.fragmentation(state, story)
      m.stories++
    }
    return m
  }

  private fun typical(seed: Long): FamilyStories.Config {
    val r = Random(seed)
    return FamilyStories.Config(
      seed = seed, identities = 12 + r.nextInt(20), photos = 300 + r.nextInt(300), maxFacesPerPhoto = 3, groupShare = 0.15f,
      weakShare = 0.15f, siblings = 2 + r.nextInt(3), siblingCosine = 0.6f, years = r.nextFloat() * 20f, strangerShare = 0.15f,
      videoShare = 0.04f, mirrorShare = 0.01f,
    )
  }

  private fun hard(seed: Long): FamilyStories.Config = typical(seed).copy(twins = 1, twinCosine = 0.8f, children = 2, years = 15f, posterShare = 0.05f, weakShare = 0.25f)

  @Test
  fun leFamiglieTipicheHannoGruppiPuri() {
    val m = measure((1L..40L).map { typical(it) })
    println("Famiglie tipiche (40): $m")
    assertWithMessage("purezza dei gruppi, famiglie tipiche").that(m.purity).isAtLeast(0.99)
    assertWithMessage("copertura dei volti CORE raggruppabili").that(m.coverable / m.stories).isAtLeast(0.6)
  }

  @Test
  fun leFamiglieDifficiliRestanoSopraIl97() {
    val m = measure((101L..140L).map { hard(it) })
    println("Famiglie difficili (40): $m")
    assertWithMessage("purezza dei gruppi, famiglie difficili").that(m.purity).isAtLeast(0.97)
    assertWithMessage("copertura dei volti CORE raggruppabili").that(m.coverable / m.stories).isAtLeast(0.5)
  }

  /** Le storie di base della suite di P0, su piu' semi: purezza >= 99% (§2.5) e copertura >= 60%. */
  @Test
  fun leStorieDiBaseSuCentoSemi() {
    var groups = 0
    var pure = 0.0
    var coverage = 0.0
    for (seed in 1L..100L) {
      val story = GroupingStories.story(seed, identities = 8, photos = 80, maxFacesPerPhoto = 3, weakShare = 0.1f, twins = 1)
      val ids = GroupingStories.ids(seed)
      val first = engine.incremental(GroupingInput(GroupingState.EMPTY, story.faces, params = GroupingParams.DEFAULT.copy(birthsEvery = 1)), ids)
      val out = engine.nightly(GroupingInput(first.state, story.faces), ids)
      val n = FamilyStories.groups(out.state).size
      groups += n
      pure += GroupingStories.purity(out.state, story.truth) * n
      coverage += GroupingStories.coverage(out.state, story)
    }
    println("Storie di base (100): purezza %.4f su %d gruppi, copertura %.3f".format(pure / groups, groups, coverage / 100))
    assertWithMessage("purezza").that(pure / groups).isAtLeast(0.99)
    assertWithMessage("copertura").that(coverage / 100).isAtLeast(0.6)
  }
}
