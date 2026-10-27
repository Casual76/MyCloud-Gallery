package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import kotlin.random.Random
import org.junit.Test

/**
 * LA STABILITA' DEGLI ID (D21, D22; riga P5 del §4.4 "stabilita' degli id (proprieta')", "ricalcolo completo con abbinamento (>= 95% degli
 * id ereditati)", "deterministico a parita' d'ingresso"; brief di P5: fra ricalcoli, fra due dispositivi che partono dagli stessi dati).
 */
class GroupingStabilityTest {
  private val engine = FastGrouping()

  private fun config(seed: Long) = FamilyStories.Config(seed = seed, identities = 15 + (seed % 10).toInt(), photos = 300, siblings = 2, years = 10f)

  /** Le foto arrivano in [parts] lotti con l'incrementale e una notte dopo ognuno: gli stati dopo ogni passo. */
  private fun history(faces: List<GroupFace>, seed: Long, parts: Int): List<Pair<GroupingState, List<GroupFace>>> {
    val ids = GroupingStories.ids(seed)
    var state = GroupingState.EMPTY
    val out = ArrayList<Pair<GroupingState, List<GroupFace>>>()
    for ((k, part) in Scenarios.arrivals(faces, parts, Random(seed)).withIndex()) {
      val params = if (k == 0) GroupingParams.DEFAULT.copy(birthsEvery = 1) else GroupingParams.DEFAULT
      state = engine.incremental(GroupingInput(state, part, params = params), ids).state
      out += state to part
      state = engine.nightly(GroupingInput(state, part), ids).state
      out += state to part
    }
    return out
  }

  @Test
  fun aggiungereVoltiNonCambiaMaiGliIdDellePersone() {
    var moved = 0
    var kept = 0
    for (seed in 1L..30L) {
      val steps = history(FamilyStories.story(config(seed)).faces, seed, 6)
      for (i in 1 until steps.size) {
        val (before, _) = steps[i - 1]
        val (after, _) = steps[i]
        for ((id, p) in before.persons) {
          val now = after.persons[id]
          assertWithMessage("storia $seed: una persona e' sparita").that(now).isNotNull()
          // Senza correzioni una persona attiva resta attiva o diventa un alias (unione, D22): mai dimenticata, mai di un altro id.
          if (p.state == PersonState.ACTIVE) assertThat(now!!.state).isAnyOf(PersonState.ACTIVE, PersonState.MERGED)
          if (p.state == PersonState.MERGED) assertThat(now!!.state).isEqualTo(PersonState.MERGED)
        }
        for (a in before.assignments.values.filter { it.origin != AssignOrigin.IGNORED }) {
          val b = after.assignments[a.face] ?: continue
          if (after.resolve(b.person) == after.resolve(a.person)) kept++ else moved++
        }
      }
    }
    val share = moved.toDouble() / (moved + kept)
    println("Stabilita' fra i passi: %d volti restano con la loro persona, %d cambiano (%.4f)".format(kept, moved, share))
    assertWithMessage("volti che cambiano persona fra un passo e l'altro").that(share).isAtMost(0.005)
  }

  @Test
  fun altreNottiNonCambianoGliId() {
    for (seed in 1L..20L) {
      val story = FamilyStories.story(config(seed))
      val ids = GroupingStories.ids(seed + 1)
      var state = history(story.faces, seed, 3).last().first
      val before = state
      repeat(3) { state = engine.nightly(GroupingInput(state, story.faces), ids).state }
      val active = before.persons.values.filter { it.state == PersonState.ACTIVE }.map { it.id }
      for (id in active) assertWithMessage("storia $seed").that(state.resolve(id)).isNotNull()
      val moved = before.assignments.values.count { a -> state.assignments[a.face]?.let { state.resolve(it.person) != state.resolve(a.person) } ?: false }
      assertWithMessage("storia $seed: volti spostati da tre notti in piu'").that(moved.toDouble() / before.assignments.size).isAtMost(0.005)
    }
  }

  @Test
  fun ilRicalcoloCompletoEreditaAlmenoIl95PerCentoDegliId() {
    var persons = 0
    var inherited = 0
    var purity = 0.0
    for (seed in 1L..30L) {
      val story = FamilyStories.story(config(seed))
      val before = history(story.faces, seed, 3).last().first
      val (after, truth) = Scenarios.recomputed(story.faces, story.truth, seed)
      val out = engine.recluster(GroupingInput(before, after), before, GroupingStories.ids(seed + 2)).state
      val old = FamilyStories.groups(before).filter { it.value.size >= 3 }.keys
      persons += old.size
      inherited += old.count { id -> out.persons[id]?.state == PersonState.ACTIVE && out.assignments.values.any { it.person == id } }
      purity += FamilyStories.purity(out, truth)
    }
    println("Ricalcolo completo: %d persone su %d ereditano l'id (%.4f), purezza media %.4f".format(inherited, persons, inherited.toDouble() / persons, purity / 30))
    assertWithMessage("id ereditati").that(inherited.toDouble() / persons).isAtLeast(0.95)
    assertWithMessage("purezza dopo il ricalcolo").that(purity / 30).isAtLeast(0.99)
  }

  @Test
  fun dueDispositiviConGliStessiDatiDannoGliStessiGruppiEGliStessiId() {
    // Deterministico a parita' d'ingresso: due macchine (due nuclei) con gli stessi volti, le stesse correzioni e lo stesso generatore di
    // id danno lo stesso stato. Sul telefono gli id nuovi li genera solo il custode (D21): gli altri leggono la sua istantanea.
    for (seed in 1L..10L) {
      val faces = FamilyStories.story(config(seed)).faces
      val a = FastGrouping()
      val b = FastGrouping()
      var sa = GroupingState.EMPTY
      var sb = GroupingState.EMPTY
      val ia = GroupingStories.ids(seed)
      val ib = GroupingStories.ids(seed)
      for (part in Scenarios.arrivals(faces, 3, Random(seed))) {
        sa = a.nightly(GroupingInput(a.incremental(GroupingInput(sa, part, params = GroupingParams.DEFAULT.copy(birthsEvery = 1)), ia).state, part), ia).state
        sb = b.nightly(GroupingInput(b.incremental(GroupingInput(sb, part, params = GroupingParams.DEFAULT.copy(birthsEvery = 1)), ib).state, part), ib).state
        assertThat(sb.persons).isEqualTo(sa.persons)
        assertThat(sb.assignments).isEqualTo(sa.assignments)
        assertThat(sb.seq).isEqualTo(sa.seq)
      }
    }
  }
}
