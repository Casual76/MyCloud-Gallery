package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentity
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonPair
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import org.junit.Test

/**
 * GLI EDGE CASE DEL RAGGRUPPAMENTO (docs/fase8-progetto.md §0.2, §2.6; riga P5 del §4.4; brief di P5): E-F1 (gemelli che si separano a
 * mano e non si riuniscono), E-F2 (la stessa persona da bambino e da adulto unita a mano), E-F3 (poster), E-F15 (la stessa persona due
 * volte in una foto), E-F17 (fotogrammi dei video), E-F27 (dimenticata che si riforma), E-F39 (contenuto sparito e tornato), alias con
 * catene e cicli. Solo identita' sintetiche.
 */
class GroupingEdgeCasesTest {
  private val engine = FastGrouping()
  private val ids = GroupingStories.ids(77)
  private val birthAll = GroupingParams.DEFAULT.copy(birthsEvery = 1)

  /** [count] foto di una sola persona [identity] a partire dalla foto [from], con un volto ciascuna. */
  private fun photos(gen: SyntheticIdentities, seed: Long, identity: Int, from: Int, count: Int, age: Float = 0f, noise: Float = SyntheticIdentities.CORE_NOISE, tier: FaceTier = FaceTier.CORE) =
    (from until from + count).map { GroupingStories.face(gen, seed, it, 0, identity, tier = tier, noise = noise, ageYears = age) }

  private fun group(faces: List<GroupFace>, corrections: GroupingCorrections = GroupingCorrections.NONE): GroupingState {
    val first = engine.incremental(GroupingInput(GroupingState.EMPTY, faces, corrections, birthAll), ids).state
    return engine.nightly(GroupingInput(first, faces, corrections), ids).state
  }

  private fun owner(state: GroupingState, face: GroupFace): PersonId? =
    state.assignments[face.ref]?.takeIf { it.origin != AssignOrigin.IGNORED }?.let { state.resolve(it.person) }

  @Test
  fun gemelliSeparatiAManoNonSiRiunisconoMai() {
    // E-F1: due gemelli quasi uguali (centri a coseno 0,97) finiscono nella stessa persona.
    val gen = SyntheticIdentities(91).define(SyntheticIdentity(id = 2, twinOf = 1, twinCosine = 0.97f))
    val twinA = photos(gen, 91, 1, 0, 20)
    val twinB = photos(gen, 91, 2, 20, 20)
    var state = group(twinA + twinB)
    val together = twinA.mapNotNull { owner(state, it) }.toSet() intersect twinB.mapNotNull { owner(state, it) }.toSet()
    assertWithMessage("i gemelli dovrebbero essere finiti insieme").that(together).isNotEmpty()
    val p = together.first()
    // "Dividi": i volti di B in una persona nuova, "separata" dalla prima.
    val x = PersonId.fromBytes(ByteArray(16) { 9 })
    val split = GroupingCorrections(fixed = twinB.filter { owner(state, it) == p }.associate { it.locator to x }, born = setOf(x), apart = setOf(PersonPair.of(p, x)))
    var faces = twinA + twinB
    for (night in 0 until 5) {
      faces = faces + photos(gen, 91, 1, 100 + night * 4, 2) + photos(gen, 91, 2, 102 + night * 4, 2)
      state = engine.incremental(GroupingInput(state, faces, split), ids).state
      val out = engine.nightly(GroupingInput(state, faces, split), ids)
      state = out.state
      assertWithMessage("notte $night").that(state.resolve(p)).isNotEqualTo(state.resolve(x))
      assertThat(out.suggestions.map { it.pair }).doesNotContain(PersonPair.of(p, x))
      assertThat(Invariants.violations(state, faces, split)).isEmpty()
    }
    // Anche dopo un ricalcolo completo con un modello nuovo.
    val (after, _) = Scenarios.recomputed(faces, faces.associate { it.ref to (if (it in twinA) 1 else 2) }, 91)
    val re = engine.nightly(GroupingInput(engine.recluster(GroupingInput(state, after, split), state, ids).state, after, split), ids).state
    assertThat(re.resolve(p)).isNotEqualTo(re.resolve(x))
    assertThat(Invariants.violations(re, after, split)).isEmpty()
  }

  @Test
  fun bambinoEAdultoUnitiAManoRestanoUniti() {
    // E-F2: la stessa identita' a 2 anni e a 27 (deriva forte): due persone, unite a mano; le foto nuove delle due eta' vanno nella stessa.
    val gen = SyntheticIdentities(92).define(SyntheticIdentity(id = 1, driftPerYear = 0.08f))
    val child = photos(gen, 92, 1, 0, 15, age = 2f)
    val adult = photos(gen, 92, 1, 15, 15, age = 27f)
    var state = group(child + adult)
    val c = child.mapNotNull { owner(state, it) }.groupingBy { it }.eachCount().maxBy { it.value }.key
    val a = adult.mapNotNull { owner(state, it) }.groupingBy { it }.eachCount().maxBy { it.value }.key
    assertWithMessage("bambino e adulto dovrebbero essere due persone").that(c).isNotEqualTo(a)
    val merge = GroupingCorrections(aliases = mapOf(c to a))
    var faces = child + adult
    for (night in 0 until 3) {
      faces = faces + photos(gen, 92, 1, 100 + night * 4, 2, age = 2f) + photos(gen, 92, 1, 102 + night * 4, 2, age = 27f)
      state = engine.nightly(GroupingInput(engine.incremental(GroupingInput(state, faces, merge), ids).state, faces, merge), ids).state
      assertThat(state.resolve(c)).isEqualTo(state.resolve(a))
    }
    val owners = faces.mapNotNull { owner(state, it) }.toSet()
    assertWithMessage("le due eta' in una persona sola").that(owners).containsExactly(state.resolve(a))
    val (after, _) = Scenarios.recomputed(faces, faces.associate { it.ref to 1 }, 92)
    val re = engine.recluster(GroupingInput(state, after, merge), state, ids).state
    assertThat(re.resolve(c)).isEqualTo(re.resolve(a))
  }

  @Test
  fun laPropostaDellaStessaPersonaSiVedeOvunqueEIlNoValePerSempre() {
    // E-F2, §2.6 "Proposte": a 0 e a 14 anni (deriva media) due persone con il legame fra 0,40 e 0,52: proposta, mai unione da sola.
    val gen = SyntheticIdentities(99).define(SyntheticIdentity(id = 1, driftPerYear = 0.08f))
    val small = photos(gen, 99, 1, 0, 14, age = 0f)
    val older = photos(gen, 99, 1, 14, 14, age = 14f)
    val faces = small + older
    val state = group(faces)
    val a = owner(state, small.first())!!
    val b = owner(state, older.first())!!
    assertWithMessage("due persone").that(a).isNotEqualTo(b)
    val pair = PersonPair.of(a, b)
    val night = engine.nightly(GroupingInput(state, faces), ids)
    assertThat(night.suggestions.map { it.pair }).contains(pair)
    // Le stesse proposte su qualunque dispositivo, senza la notte; lo stato non cambia.
    assertThat(GroupingProposals.of(GroupingInput(night.state, faces)).map { it.pair }).contains(pair)
    // "No": separati, la proposta non torna; "Si'": un alias dell'utente, una persona sola.
    val no = GroupingCorrections(apart = setOf(pair))
    assertThat(GroupingProposals.of(GroupingInput(night.state, faces, no)).map { it.pair }).doesNotContain(pair)
    assertThat(engine.nightly(GroupingInput(night.state, faces, no), ids).suggestions.map { it.pair }).doesNotContain(pair)
    val yes = GroupingCorrections(aliases = mapOf(b to a))
    val merged = engine.nightly(GroupingInput(night.state, faces, yes), ids).state
    assertThat(merged.resolve(b)).isEqualTo(merged.resolve(a))
  }

  @Test
  fun posterIgnoratoODimenticatoNonTornaInUnaPersona() {
    // E-F3: un poster (lo stesso volto quasi identico) in 12 foto fa una persona; "Ignora" su un volto, poi "Dimentica" la persona.
    val gen = SyntheticIdentities(93)
    val poster = photos(gen, 93, 5, 0, 12, noise = 0.35f)
    var state = group(poster)
    val p = owner(state, poster.first())!!
    val ignored = GroupingCorrections(ignored = setOf(poster[3].locator))
    state = engine.nightly(GroupingInput(state, poster, ignored), ids).state
    assertThat(state.assignments[poster[3].ref]).isNull()
    val forgotten = ignored.copy(forgotten = setOf(p))
    state = engine.nightly(GroupingInput(state, poster, forgotten), ids).state
    assertThat(state.resolve(p)).isNull()
    assertThat(poster.none { owner(state, it) != null }).isTrue()
    // Foto nuove dello stesso poster: possono formare una persona nuova senza nome (D24), mai quella dimenticata; i volti vecchi restano fuori.
    val more = poster + photos(gen, 93, 5, 50, 6, noise = 0.35f)
    state = engine.nightly(GroupingInput(engine.incremental(GroupingInput(state, more, forgotten, birthAll), ids).state, more, forgotten), ids).state
    assertThat(poster.none { owner(state, it) != null }).isTrue()
    assertThat(more.mapNotNull { owner(state, it) }).doesNotContain(p)
  }

  @Test
  fun laStessaPersonaDueVolteInUnaFotoNeEntraUnaSola() {
    // E-F15: uno specchio in 6 foto su 12: in ogni foto un solo volto entra nella persona; l'altro resta fuori da lei (fra i non assegnati,
    // o, se gli specchi sono almeno 3 in 2 foto, in una persona a se' come i gemelli sempre insieme di E-F1: mai nella stessa).
    val gen = SyntheticIdentities(94)
    val faces = ArrayList<GroupFace>()
    for (photo in 0 until 12) {
      faces += GroupingStories.face(gen, 94, photo, 0, identity = 1)
      if (photo % 2 == 0) faces += GroupingStories.face(gen, 94, photo, 1, identity = 1)
    }
    val state = group(faces)
    for (photo in faces.groupBy { it.ref.contentKey }.values) {
      val owners = photo.mapNotNull { owner(state, it) }
      assertThat(owners.distinct()).hasSize(owners.size)
    }
    val main = faces.mapNotNull { owner(state, it) }.groupingBy { it }.eachCount().maxBy { it.value }
    assertThat(main.value).isAtLeast(10)
  }

  @Test
  fun iFotogrammiDeiVideoEntranoMaNonFannoNascere() {
    // E-F17: i volti dei fotogrammi sono deboli: non fanno nascere persone, ma entrano in una persona che c'e' (somiglianza piu' alta).
    val gen = SyntheticIdentities(95)
    val frames = (0 until 4).map { GroupingStories.face(gen, 95, 500, 0, identity = 1, tier = FaceTier.WEAK, noise = 0.7f, frame = it * 2) }
    assertThat(group(frames).persons).isEmpty()
    val photos = photos(gen, 95, 1, 0, 12)
    val state = group(photos + frames)
    val p = owner(state, photos.first())
    assertThat(frames.count { owner(state, it) == p }).isAtLeast(2)
  }

  @Test
  fun unaPersonaDimenticataPuoRiformarsiConUnIdNuovo() {
    // E-F27: con foto nuove la persona puo' riformarsi come gruppo senza nome, con un id nuovo; i vecchi volti restano ignorati.
    val gen = SyntheticIdentities(96)
    val old = photos(gen, 96, 1, 0, 10)
    var state = group(old)
    val p = owner(state, old.first())!!
    val forget = GroupingCorrections(forgotten = setOf(p))
    state = engine.nightly(GroupingInput(state, old, forget), ids).state
    val fresh = photos(gen, 96, 1, 100, 8)
    state = engine.nightly(GroupingInput(engine.incremental(GroupingInput(state, old + fresh, forget, birthAll), ids).state, old + fresh, forget), ids).state
    val reborn = fresh.mapNotNull { owner(state, it) }.toSet()
    assertThat(reborn).isNotEmpty()
    assertThat(reborn).doesNotContain(p)
    assertThat(old.all { state.assignments[it.ref]?.origin == AssignOrigin.IGNORED }).isTrue()
  }

  @Test
  fun unContenutoSparitoEsceETornaConLaStessaPersona() {
    // E-F39: i volti di un media sparito escono dai conteggi; se torna (stessa chiave), tornano nella stessa persona.
    val gen = SyntheticIdentities(97)
    val faces = photos(gen, 97, 1, 0, 12)
    var state = group(faces)
    val p = owner(state, faces.first())!!
    val gone = setOf(faces[4].ref.contentKey)
    val out = engine.nightly(GroupingInput(state, faces, gone = gone), ids)
    assertThat(out.report.removed).isEqualTo(1)
    assertThat(out.state.assignments[faces[4].ref]).isNull()
    state = engine.incremental(GroupingInput(out.state, faces), ids).state
    assertThat(owner(state, faces[4])).isEqualTo(p)
  }

  @Test
  fun aliasConCateneECicliSiRisolvonoSempreAllaStessaRadice() {
    val gen = SyntheticIdentities(98)
    val faces = (1..4).flatMap { id -> photos(gen, 98, id, id * 20, 8) }
    var state = group(faces)
    val persons = (1..4).map { id -> owner(state, faces.first { f -> f.ref.contentKey == GroupingStories.contentKey(98, id * 20) })!! }
    // Una catena A → B → C e un ciclo C → D → A (due unioni contrarie da due dispositivi): radice = l'id minore del ciclo.
    val (a, b, c, d) = persons
    val aliases = mapOf(a to b, b to c, c to d, d to a)
    state = engine.nightly(GroupingInput(state, faces, GroupingCorrections(aliases = aliases)), ids).state
    val root = persons.min()
    for (p in persons) assertThat(state.resolve(p)).isEqualTo(root)
    assertThat(state.persons.getValue(root).state).isEqualTo(PersonState.ACTIVE)
    assertThat(faces.mapNotNull { owner(state, it) }.toSet()).containsExactly(root)
  }
}
