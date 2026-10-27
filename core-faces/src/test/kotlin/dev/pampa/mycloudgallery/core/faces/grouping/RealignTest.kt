package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshot
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import kotlin.random.Random
import org.junit.Test

/**
 * IL RIALLINEAMENTO (D21, E-F26): due custodi partono dalla stessa istantanea, raggruppano volti nuovi (in parte diversi) con id diversi e
 * pubblicano; chi perde riparte dalla vincente portandoci le sue persone. Proprieta' su 300 divergenze a caso e casi scritti a mano.
 */
class RealignTest {
  private val engine = FastGrouping()

  private class Divergence(val base: GroupingState, val winner: GroupingState, val loser: GroupingState, val faces: List<dev.pampa.mycloudgallery.core.faces.contract.GroupFace>)

  /** Una storia raggruppata fino a meta', poi due rami con id diversi su arrivi diversi (in parte comuni). */
  private fun diverge(seed: Long): Divergence {
    val story = FamilyStories.story(Scenarios.config(seed).copy(photos = 120))
    val random = Random(seed)
    val keys = story.faces.map { it.ref.contentKey }.distinct().shuffled(random)
    val part = keys.withIndex().associate { (i, k) -> k to (i * 4 / keys.size) }
    val common = story.faces.filter { part.getValue(it.ref.contentKey) <= 1 }
    val first = engine.incremental(GroupingInput(GroupingState.EMPTY, common, params = GroupingParams.DEFAULT.copy(birthsEvery = 1)), GroupingStories.ids(seed)).state
    val base = engine.nightly(GroupingInput(first, common), GroupingStories.ids(seed + 1)).state
    val facesA = story.faces.filter { part.getValue(it.ref.contentKey) != 3 }
    val facesB = story.faces.filter { part.getValue(it.ref.contentKey) != 2 || random.nextBoolean() }
    val many = GroupingParams.DEFAULT.copy(birthsEvery = 1)
    val a = engine.nightly(GroupingInput(engine.incremental(GroupingInput(base, facesA, params = many), GroupingStories.ids(seed + 2)).state, facesA), GroupingStories.ids(seed + 3)).state
    val b = engine.nightly(GroupingInput(engine.incremental(GroupingInput(base, facesB, params = many), GroupingStories.ids(seed + 4)).state, facesB), GroupingStories.ids(seed + 5)).state
    return Divergence(base, a, b, story.faces)
  }

  @Test
  fun nessunaPersonaSiPerdeEIlVincitoreResta() {
    var aliased = 0
    var reborn = 0
    for (seed in 1L..300L) {
      val d = diverge(seed)
      val named = d.loser.persons.keys.filter { (it.hashCode() and 3) == 0 }.toSet()
      val corrections = GroupingCorrections(named = named)
      val out = Realign.onto(d.winner, d.loser, d.faces, corrections, GroupingParams.DEFAULT)
      // Ogni persona dei due rami c'e' ancora, e ogni persona attiva porta a una persona attiva (gli id restano, D22).
      for (id in d.winner.persons.keys + d.loser.persons.keys) assertWithMessage("storia $seed: persona persa").that(out.persons).containsKey(id)
      for ((id, p) in d.winner.persons + d.loser.persons) {
        if (p.state == PersonState.ACTIVE) assertWithMessage("storia $seed: persona senza radice").that(out.resolve(id)).isNotNull()
      }
      // Le assegnazioni del vincitore restano (a meno di un alias rovesciato verso una persona con un nome).
      for ((face, a) in d.winner.assignments) {
        val now = out.assignments[face]
        assertWithMessage("storia $seed: assegnazione del vincitore persa").that(now).isNotNull()
        assertThat(out.resolve(now!!.person)).isEqualTo(out.resolve(a.person))
      }
      // Gia' assorbita: riallinearsi di nuovo non cambia niente.
      assertThat(Realign.contains(out, d.loser)).isTrue()
      assertThat(Realign.onto(out, d.loser, d.faces, corrections, GroupingParams.DEFAULT)).isSameInstanceAs(out)
      if (out !== d.winner) {
        assertThat(out.seq).isEqualTo(maxOf(d.winner.seq, d.loser.seq) + 1)
        assertThat(out.base).isEqualTo(d.winner.seq)
      }
      // Il passo dopo funziona sul risultato (stato valido, correzioni riapplicate).
      engine.incremental(GroupingInput(out, d.faces, corrections), GroupingStories.ids(seed + 9))
      val orphans = d.loser.persons.values.filter { it.id !in d.winner.persons && it.state == PersonState.ACTIVE }
      aliased += orphans.count { out.persons.getValue(it.id).state == PersonState.MERGED }
      reborn += orphans.count { out.persons.getValue(it.id).state == PersonState.ACTIVE }
    }
    println("Riallineamento su 300 divergenze: $aliased persone diventate alias, $reborn rinate con lo stesso id")
    assertThat(aliased).isGreaterThan(0)
    assertThat(reborn).isGreaterThan(0)
  }

  @Test
  fun conUnNomeLAliasSiRovesciaEConDueNomiNessunaUnione() {
    for (seed in 1L..40L) {
      val d = diverge(seed)
      val plain = Realign.onto(d.winner, d.loser, d.faces, GroupingCorrections.NONE, GroupingParams.DEFAULT)
      val aliases = d.loser.persons.values.filter { it.state == PersonState.ACTIVE && it.id !in d.winner.persons }
        .map { it.id }.filter { plain.persons[it]?.state == PersonState.MERGED }
      if (aliases.isEmpty()) continue
      val p = aliases.first()
      val q = plain.persons.getValue(p).aliasOf!!
      // Solo la persona del perdente ha un nome: resta lei la radice (il nome si vede), quella del vincitore diventa il suo alias.
      val flipped = Realign.onto(d.winner, d.loser, d.faces, GroupingCorrections(named = setOf(p)), GroupingParams.DEFAULT)
      assertThat(flipped.persons.getValue(p).state).isEqualTo(PersonState.ACTIVE)
      assertThat(flipped.persons.getValue(q).aliasOf).isEqualTo(p)
      assertThat(flipped.assignments.values.none { it.person == q && it.origin != AssignOrigin.IGNORED }).isTrue()
      // Tutte e due con un nome: niente unione automatica (decide l'utente).
      val both = Realign.onto(d.winner, d.loser, d.faces, GroupingCorrections(named = setOf(p, q)), GroupingParams.DEFAULT)
      assertThat(both.persons.getValue(p).state).isEqualTo(PersonState.ACTIVE)
      assertThat(both.persons.getValue(q).state).isEqualTo(PersonState.ACTIVE)
      return
    }
    error("Nessuna divergenza con un alias nelle prime 40 storie")
  }

  @Test
  fun laVincenteEQuellaConLaSeqPiuAltaPoiIlDispositivoMinore() {
    val s1 = GroupingState(3, 2, emptyMap(), emptyMap())
    val s2 = GroupingState(4, 3, emptyMap(), emptyMap())
    assertThat(ClusterSnapshot.winner(listOf(ClusterSnapshot("b", s1), ClusterSnapshot("c", s2)))!!.author).isEqualTo("c")
    assertThat(ClusterSnapshot.winner(listOf(ClusterSnapshot("c", s2), ClusterSnapshot("b", s2)))!!.author).isEqualTo("b")
    assertThat(ClusterSnapshot.winner(emptyList())).isNull()
  }
}
