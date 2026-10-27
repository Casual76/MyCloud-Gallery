package dev.pampa.mycloudgallery.core.faces.eval

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.grouping.FastGrouping
import dev.pampa.mycloudgallery.core.faces.grouping.Scenarios
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingMath
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.random.Random

/**
 * Il banco delle prove della valutazione (P11): storie sintetiche ([FamilyStories], vettori di identita' inventate, nessun dato personale)
 * raggruppate come sul telefono, un **utente ideale** che conosce la verita' e compila le etichette, e le osservazioni che la regola delle soglie
 * chiede. Cosi' le metriche si provano contro numeri che si conoscono (errori messi apposta) e la regola contro gruppi veri.
 * Non e' un file condiviso con lo strumento: sta solo qui (il suo nome finisce in `Kit`).
 */
internal object FacesEvalKit {
  private val engine = FastGrouping()

  /** La storia raggruppata come sul telefono: [parts] arrivi con l'incrementale e una notte dopo ognuno. */
  fun group(story: FamilyStories.Story, seed: Long, params: GroupingParams = GroupingParams.DEFAULT, parts: Int = 4): GroupingState {
    val random = Random(seed)
    val ids = GroupingStories.ids(seed)
    var state = GroupingState.EMPTY
    for ((k, faces) in Scenarios.arrivals(story.faces, parts, random).withIndex()) {
      val p = if (k == 0) params.copy(birthsEvery = 1) else params
      state = engine.incremental(GroupingInput(state, faces, params = p), ids).state
      state = engine.nightly(GroupingInput(state, faces, params = params), ids).state
    }
    return state
  }

  fun members(state: GroupingState): Map<PersonId, List<FaceRef>> = FamilyStories.groups(state)

  /** L'identita' piu' frequente fra i volti di un gruppo (a parita' la minore). */
  fun major(refs: List<FaceRef>, truth: Map<FaceRef, Int>): Int =
    refs.mapNotNull { truth[it] }.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first().key

  /** Somiglianza al gruppo e alla seconda persona, come le calcolerebbe l'ambiente sul telefono. */
  class Scored(val score: Float, val second: Float?)

  fun scored(state: GroupingState, story: FamilyStories.Story): Map<FaceRef, Scored> {
    val byRef = story.faces.associateBy { it.ref }
    val exemplars = state.persons.filterValues { it.state == PersonState.ACTIVE }.mapValues { (_, p) -> p.exemplars.mapNotNull { byRef[it] } }
    val out = HashMap<FaceRef, Scored>()
    for (a in state.assignments.values) {
      if (a.origin == AssignOrigin.IGNORED) continue
      val face = byRef[a.face] ?: continue
      val own = GroupingMath.similarity(face, exemplars[a.person].orEmpty()) ?: a.score
      val second = exemplars.filterKeys { it != a.person }.values.mapNotNull { GroupingMath.similarity(face, it) }.maxOrNull()
      out[a.face] = Scored(own, second)
    }
    return out
  }

  /** I gruppi che Persone mostrerebbe: le persone attive con almeno 3 volti. */
  fun candidates(state: GroupingState): List<FacesEvalSampling.Candidate> =
    members(state).filter { (p, refs) -> refs.size >= 3 && state.persons[p]?.state == PersonState.ACTIVE }
      .map { (p, refs) -> FacesEvalSampling.Candidate(p.value, refs.size, named = false) }

  fun totals(state: GroupingState, story: FamilyStories.Story): EvalTotals {
    val assigned = state.assignments.values.filter { it.origin != AssignOrigin.IGNORED }.map { it.face }.toSet()
    val core = story.faces.filter { it.tier == FaceTier.CORE }
    val weak = story.faces.filter { it.tier == FaceTier.WEAK }
    return EvalTotals(core.size, core.count { it.ref in assigned }, weak.size, weak.count { it.ref in assigned }, candidates(state).size)
  }

  /**
   * L'utente ideale: guarda i [selected] gruppi, tocca i volti che non sono dell'identita' piu' frequente del gruppo, dice "misto" se i sbagliati
   * sono almeno [mixedShare] dei volti, e dice "e' la stessa di" per i gruppi della stessa identita'. Le etichette sono quelle che lo strumento scrive.
   */
  fun oracle(
    kind: EvalSessionKind,
    state: GroupingState,
    story: FamilyStories.Story,
    selected: List<FacesEvalSampling.Candidate>,
    seed: Long,
    mixedShare: Double = 2.0,
  ): SessionLabels {
    val byRef = story.faces.associateBy { it.ref }
    val scores = scored(state, story)
    val majors = members(state).mapValues { (_, refs) -> major(refs, story.truth) }
    var session = SessionLabels(kind, seed, totals(state, story), emptyList())
    for (c in selected) {
      val person = PersonId(c.person)
      val refs = members(state).getValue(person)
      val candidates = refs.map { FacesEvalSampling.FaceCandidate(byRef.getValue(it).locator, scores[it]?.score ?: 0f) }
      val shown = FacesEvalSampling.faces(candidates, c.person, seed).all
      val locatorToRef = refs.associateBy { byRef.getValue(it).locator }
      val wrong = shown.filter { story.truth[locatorToRef.getValue(it.locator)] != majors.getValue(person) }.map { it.locator }.toSet()
      val mixed = wrong.size >= mixedShare * shown.size
      val same = majors.filter { (p, m) -> p != person && m == majors.getValue(person) }.keys.map { it.value }.toSet()
      session = session.withGroup(GroupLabel(c.person, c.size, c.named, shown.size, reviewed = true, mixed = mixed, wrong = if (mixed) emptySet() else wrong, sameAs = same))
    }
    return session
  }

  /** I volti che la griglia ha mostrato per un gruppo (gli stessi che sceglie lo strumento, con lo stesso seme). */
  private fun shown(
    g: GroupLabel,
    members: Map<PersonId, List<FaceRef>>,
    byRef: Map<FaceRef, GroupFace>,
    scores: Map<FaceRef, Scored>,
    seed: Long,
  ): List<GroupFace> {
    val refs = members.getValue(PersonId(g.person))
    val candidates = refs.map { FacesEvalSampling.FaceCandidate(byRef.getValue(it).locator, scores[it]?.score ?: 0f) }
    val locators = FacesEvalSampling.faces(candidates, g.person, seed).all.map { it.locator }.toSet()
    return refs.map { byRef.getValue(it) }.filter { it.locator in locators }
  }

  /** Le osservazioni della regola per i gruppi guardati e non misti: un'osservazione per ogni volto mostrato. */
  fun joinObservations(session: SessionLabels, state: GroupingState, story: FamilyStories.Story): List<JoinObservation> {
    val byRef = story.faces.associateBy { it.ref }
    val scores = scored(state, story)
    val members = members(state)
    val out = ArrayList<JoinObservation>()
    for ((index, g) in session.reviewedGroups.filter { !it.mixed }.withIndex()) {
      for (face in shown(g, members, byRef, scores, session.seed)) {
        val s = scores[face.ref] ?: continue
        out += JoinObservation(index, s.score, s.second, face.tier == FaceTier.WEAK, face.locator !in g.wrong)
      }
    }
    return out
  }

  /** I volti CORE guardati di ogni gruppo (anche misto), con i coseni a due a due, per la regola delle nascite. */
  fun birthGroups(session: SessionLabels, state: GroupingState, story: FamilyStories.Story): List<BirthGroup> {
    val byRef = story.faces.associateBy { it.ref }
    val scores = scored(state, story)
    val members = members(state)
    val out = ArrayList<BirthGroup>()
    for (g in session.reviewedGroups) {
      val faces = shown(g, members, byRef, scores, session.seed).filter { it.tier == FaceTier.CORE }
      val n = faces.size
      val cosine = FloatArray(n * n) { faces[it / n].cosine(faces[it % n]) }
      val photoIds = faces.map { it.photo }.distinct()
      // Un gruppo dato per misto non ha volti giusti e sbagliati noti: entra come "misto" (right = null).
      out += BirthGroup(if (g.mixed) null else BooleanArray(n) { faces[it].locator !in g.wrong }, IntArray(n) { photoIds.indexOf(faces[it].photo) }, cosine)
    }
    return out
  }

  // --- Errori messi apposta ------------------------------------------------------------------------------------------------------

  /** Lo stato con i volti [moves] spostati nelle persone indicate (assegnazioni automatiche): sono errori che si conoscono. */
  fun withMoves(state: GroupingState, moves: Map<FaceRef, PersonId>, story: FamilyStories.Story): GroupingState {
    val byRef = story.faces.associateBy { it.ref }
    val assignments = HashMap(state.assignments)
    for ((ref, to) in moves) assignments[ref] = GroupAssignment(ref, byRef.getValue(ref).locator, to, 0.5f, AssignOrigin.AUTO)
    return GroupingState(state.seq, state.base, state.persons, assignments, state.unassignedAtBirths)
  }

  /** Lo stato con una persona divisa in due: i volti [moved] passano a una persona nuova (stessa identita' in due gruppi). */
  fun withSplit(state: GroupingState, moved: List<FaceRef>, story: FamilyStories.Story, id: PersonId): GroupingState {
    val byRef = story.faces.associateBy { it.ref }
    val assignments = HashMap(state.assignments)
    for (ref in moved) assignments[ref] = GroupAssignment(ref, byRef.getValue(ref).locator, id, 0.9f, AssignOrigin.AUTO)
    val persons = HashMap(state.persons)
    persons[id] = GroupPerson(id, PersonState.ACTIVE, null, state.seq, state.seq, moved.take(3))
    return GroupingState(state.seq, state.base, persons, assignments, state.unassignedAtBirths)
  }
}
