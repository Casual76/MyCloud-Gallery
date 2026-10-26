package dev.pampa.mycloudgallery.core.faces.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import org.junit.Test

/**
 * La SUITE DI CONTRATTO del raggruppamento (docs/fase8-progetto.md §2.5 "Prove dell'algoritmo", §5.2, D20-D23). Gira sull'esecutore di
 * riferimento di P0 ([ReferenceGroupingContractTest]) e sul nucleo vero di P5, che la estende e aggiunge la parita' su 500 storie, le misure
 * e le 10.000 storie di correzioni. Solo identita' sintetiche (vettori), nessun volto vero.
 */
abstract class GroupingContract {

  protected abstract fun engine(): GroupingEngine

  private val params = GroupingParams.DEFAULT

  /** Una storia raggruppata da capo: l'incrementale (con le nascite) e una notte. */
  protected fun grouped(story: GroupingStories.Story, seed: Long, corrections: GroupingCorrections = GroupingCorrections.NONE): GroupingOutcome {
    val ids = GroupingStories.ids(seed)
    val first = engine().incremental(GroupingInput(GroupingState.EMPTY, story.faces, corrections, params.copy(birthsEvery = 1)), ids)
    return engine().nightly(GroupingInput(first.state, story.faces, corrections, params), ids)
  }

  private fun live(state: GroupingState) = state.assignments.values.filter { it.origin != AssignOrigin.IGNORED }

  /** §2.5: purezza >= 99% sul sintetico (identita' ben separate) e una copertura dichiarata. */
  @Test
  fun purezzaSulleStorieSintetiche() {
    for (seed in 1L..4L) {
      val story = GroupingStories.story(seed, identities = 8, photos = 80, maxFacesPerPhoto = 3)
      val out = grouped(story, seed)
      assertWithMessage("purezza, storia $seed").that(GroupingStories.purity(out.state, story.truth)).isAtLeast(0.99)
      assertWithMessage("copertura, storia $seed").that(GroupingStories.coverage(out.state, story)).isAtLeast(0.6)
      assertThat(out.state.persons.values.count { it.state == PersonState.ACTIVE }).isAtMost(16)
    }
  }

  /** Deterministico a parita' d'ingresso (stessi gruppi, stessi id con lo stesso generatore). */
  @Test
  fun deterministico() {
    val story = GroupingStories.story(7, identities = 6, photos = 40)
    assertThat(grouped(story, 7).state.assignments).isEqualTo(grouped(story, 7).state.assignments)
    assertThat(grouped(story, 7).state.persons).isEqualTo(grouped(story, 7).state.persons)
  }

  /** Due volti della stessa foto mai nella stessa persona (D20, E-F15), anche con la stessa identita' due volte (specchio). */
  @Test
  fun maiDueVoltiDellaStessaFotoNellaStessaPersona() {
    val identities = SyntheticIdentities(11)
    val faces = ArrayList<GroupFace>()
    for (photo in 0 until 12) faces += GroupingStories.face(identities, 11, photo, 0, identity = 1)
    // Lo specchio: due volti della stessa identita' nella stessa foto.
    faces += GroupingStories.face(identities, 11, 0, 1, identity = 1)
    faces += GroupingStories.face(identities, 11, 1, 1, identity = 1)
    val out = grouped(GroupingStories.Story(faces.sortedBy { it.ref }, faces.associate { it.ref to 1 }), 11)
    val byPhoto = live(out.state).groupBy { it.person to (it.face.contentKey to it.face.frame) }
    assertThat(byPhoto.values.all { it.size == 1 }).isTrue()
    assertThat(out.state.persons.values.count { it.state == PersonState.ACTIVE }).isAtLeast(1)
  }

  /** D20: una persona nasce con almeno 3 volti di almeno 2 foto, solo da volti CORE. */
  @Test
  fun laNascitaVuoleTreVoltiCoreDiDueFoto() {
    // Volti puliti (rumore basso): qui si provano i conteggi della regola, non la soglia (la provano le storie).
    val identities = SyntheticIdentities(12)
    val clean = 0.5f
    val two = (0 until 2).map { GroupingStories.face(identities, 12, it, 0, identity = 1, noise = clean) }
    assertThat(grouped(GroupingStories.Story(two, two.associate { it.ref to 1 }), 12).state.persons).isEmpty()
    val weak = (0 until 6).map { GroupingStories.face(identities, 12, it, 0, identity = 2, tier = FaceTier.WEAK) }
    assertThat(grouped(GroupingStories.Story(weak, weak.associate { it.ref to 2 }), 12).state.persons).isEmpty()
    val three = (0 until 3).map { GroupingStories.face(identities, 12, it, 0, identity = 3, noise = clean) }
    // Tre volti CORE della stessa foto (fotogrammi diversi di un video) non bastano: servono due foto.
    val onePhoto = (0 until 3).map { GroupingStories.face(identities, 12, 0, 0, identity = 4, noise = clean, frame = it) }
    assertThat(grouped(GroupingStories.Story(onePhoto, onePhoto.associate { it.ref to 4 }), 12).state.persons).isEmpty()
    assertThat(grouped(GroupingStories.Story(three, three.associate { it.ref to 3 }), 12).state.persons).hasSize(1)
  }

  /** §2.5 "stabilita'": aggiungere volti non cambia mai l'id di una persona (salvo un'unione, che e' un alias). */
  @Test
  fun aggiungereVoltiNonCambiaGliId() {
    val ids = GroupingStories.ids(21)
    val story = GroupingStories.story(21, identities = 6, photos = 60)
    val half = story.faces.filter { it.ref.contentKey in story.faces.map { f -> f.ref.contentKey }.distinct().take(30) }
    val first = engine().nightly(GroupingInput(GroupingState.EMPTY, half, params = params.copy(birthsEvery = 1)), ids)
    val second = engine().incremental(GroupingInput(first.state, story.faces, params = params), ids)
    val third = engine().nightly(GroupingInput(second.state, story.faces, params = params), ids)
    for (a in live(first.state)) {
      val now = third.state.assignments[a.face] ?: continue
      assertWithMessage("un volto ha cambiato persona senza un alias").that(third.state.resolve(a.person)).isEqualTo(now.person)
    }
    for (p in first.state.persons.values.filter { it.state == PersonState.ACTIVE }) {
      assertWithMessage("una persona e' sparita senza un alias").that(third.state.persons).containsKey(p.id)
    }
  }

  /** D23: "E' …", "Non e' …", "Ignora", "Dimentica" valgono a ogni passo. */
  @Test
  fun leCorrezioniSonoRispettateAOgniPasso() {
    val story = GroupingStories.story(31, identities = 5, photos = 50)
    val base = grouped(story, 31)
    val anyPerson = base.state.persons.values.first { it.state == PersonState.ACTIVE }.id
    val faces = story.faces
    val fixedFace = faces.first { base.state.assignments[it.ref]?.person != anyPerson }
    val rejectedFace = faces.first { base.state.assignments[it.ref]?.person == anyPerson && it != fixedFace }
    val ignoredFace = faces.last { base.state.assignments[it.ref] != null && it != fixedFace && it != rejectedFace }
    val corrections = GroupingCorrections(
      fixed = mapOf(fixedFace.locator to anyPerson),
      rejected = mapOf(rejectedFace.locator to setOf(anyPerson)),
      ignored = setOf(ignoredFace.locator),
    )
    val ids = GroupingStories.ids(32)
    var state = base.state
    repeat(3) {
      state = engine().nightly(GroupingInput(state, faces, corrections, params), ids).state
      state = engine().incremental(GroupingInput(state, faces, corrections, params), ids).state
      assertThat(state.assignments[fixedFace.ref]?.person).isEqualTo(anyPerson)
      assertThat(state.assignments[fixedFace.ref]?.origin).isEqualTo(AssignOrigin.USER)
      assertThat(state.assignments[rejectedFace.ref]?.person).isNotEqualTo(anyPerson)
      assertThat(state.assignments[ignoredFace.ref]).isNull()
    }
    // "Dimentica": la persona sparisce, i suoi volti restano ignorati e non tornano in una persona.
    val forgotten = engine().nightly(GroupingInput(state, faces, corrections.copy(forgotten = setOf(anyPerson)), params), ids).state
    assertThat(forgotten.resolve(anyPerson)).isNull()
    val again = engine().nightly(GroupingInput(forgotten, faces, corrections.copy(forgotten = setOf(anyPerson)), params), ids).state
    val wasHis = live(state).filter { it.person == anyPerson }.map { it.face }
    assertThat(wasHis.mapNotNull { again.assignments[it] }.all { it.origin == AssignOrigin.IGNORED }).isTrue()
  }

  /** D20 (a), E-F1, E-F23: due gruppi della stessa persona si uniscono da soli (alias); con due nomi o "Separati" no. */
  @Test
  fun leUnioniPrudentiRispettanoNomiESeparazioni() {
    val identities = SyntheticIdentities(41)
    val faces = (0 until 8).map { GroupingStories.face(identities, 41, it, 0, identity = 1) }
    val p = PersonId.fromBytes(ByteArray(16) { 1 })
    val q = PersonId.fromBytes(ByteArray(16) { 2 })
    val split = GroupingCorrections(fixed = faces.take(4).associate { it.locator to p } + faces.drop(4).associate { it.locator to q }, born = setOf(p, q))
    val ids = GroupingStories.ids(41)
    val first = engine().incremental(GroupingInput(GroupingState.EMPTY, faces, split, params), ids)
    val merged = engine().nightly(GroupingInput(first.state, faces, split, params), ids)
    assertThat(merged.state.resolve(p)).isEqualTo(merged.state.resolve(q))
    val named = engine().nightly(GroupingInput(first.state, faces, split.copy(named = setOf(p, q)), params), ids)
    assertThat(named.state.resolve(p)).isNotEqualTo(named.state.resolve(q))
    assertThat(named.suggestions.map { it.pair }).contains(PersonPair.of(p, q))
    val apart = engine().nightly(GroupingInput(first.state, faces, split.copy(apart = setOf(PersonPair.of(p, q))), params), ids)
    assertThat(apart.state.resolve(p)).isNotEqualTo(apart.state.resolve(q))
    assertThat(apart.suggestions).isEmpty()
  }

  /** D21: il provvisorio (gli altri dispositivi) assegna con gli esemplari dell'istantanea, mai nascite, e non avanza di numero. */
  @Test
  fun ilProvvisorioNonFaNascereENonPubblica() {
    val story = GroupingStories.story(51, identities = 4, photos = 40)
    val provisional = engine().provisional(GroupingInput(GroupingState.EMPTY, story.faces, params = params))
    assertThat(provisional.state.persons).isEmpty()
    assertThat(provisional.state.seq).isEqualTo(0)
    val base = grouped(story, 51)
    val more = GroupingStories.story(52, identities = 4, photos = 10)
    val local = engine().provisional(GroupingInput(base.state, story.faces + more.faces, params = params))
    assertThat(local.state.seq).isEqualTo(base.state.seq)
    assertThat(local.state.persons.keys).isEqualTo(base.state.persons.keys)
  }

  /** D22, E-F24: un ricalcolo completo eredita gli id per volti in comune (stesso posto). */
  @Test
  fun ilRicalcoloCompletoEreditaGliId() {
    val story = GroupingStories.story(61, identities = 6, photos = 60)
    val before = grouped(story, 61)
    val identities = SyntheticIdentities(61)
    // Lo stesso archivio ricalcolato: stessi posti, riferimenti nuovi (corpo diverso), vettori un po' diversi.
    val after = story.faces.map { f ->
      val ref = FaceRef(f.ref.contentKey, f.ref.frame, f.ref.index, f.ref.bodyDigest + 1)
      GroupFace(ref, f.locator, f.tier, identities.vector(story.truth.getValue(f.ref), sample = 9_000 + f.ref.index + f.ref.contentKey.short, noise = 0.9f))
    }
    val out = engine().recluster(GroupingInput(before.state, after, params = params), before.state, GroupingStories.ids(62))
    val oldPeople = before.state.persons.values.filter { it.state == PersonState.ACTIVE }.map { it.id }
    val kept = oldPeople.count { id -> out.state.assignments.values.any { it.person == id } }
    assertWithMessage("id ereditati").that(kept.toDouble() / oldPeople.size).isAtLeast(0.95)
    assertThat(GroupingStories.purity(out.state, after.associate { it.ref to story.truth.getValue(FaceRef(it.ref.contentKey, it.ref.frame, it.ref.index, it.ref.bodyDigest - 1)) })).isAtLeast(0.99)
  }
}
