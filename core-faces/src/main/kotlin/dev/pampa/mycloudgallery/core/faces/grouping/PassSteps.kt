package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId

// I passi del nucleo vero che decidono chi e' chi (§2.5): assegnazione, nascite, espulsioni, esemplari. Le unioni e il ricalcolo completo
// stanno in `PassMerges.kt`. Stesse regole, stessi conti e stessi spareggi di `GroupingSteps` del riferimento.

/**
 * L'assegnazione (§2.5) dei volti non assegnati, in ordine di riferimento, con gli esemplari di inizio passo: la persona migliore se supera
 * `tJoin` (WEAK `tJoinWeak`) e la seconda di almeno `margin`, senza vincoli contrari ("Non e' …", ignorato, fissato, la stessa foto ha
 * gia' un volto confermato o piu' simile nella persona).
 */
internal fun GroupingPass.assignUnassigned() {
  val candidates = activePersons().map { it to exemplarRows(it) }.filter { it.second.isNotEmpty() }
  if (candidates.isEmpty()) return
  val scorer = ExemplarScorer(table.matrix, candidates.map { it.second })
  val scores = FloatArray(candidates.size)
  for (row in 0 until table.size) {
    val face = table.faces[row]
    if (face.ref in assign || index.isIgnored(face.locator) || index.fixedFor(face.locator) != null) continue
    val rejected = rejectedFor(face.locator)
    scorer.score(row, scores)
    // Il primo e il secondo nell'ordine del riferimento: somiglianza decrescente, poi id crescente (i candidati sono in ordine di id).
    var best = -1
    var second = -1
    for (s in candidates.indices) {
      if (scores[s].isNaN() || candidates[s].first.id in rejected) continue
      if (best < 0 || scores[s].compareTo(scores[best]) > 0) {
        second = best
        best = s
      } else if (second < 0 || scores[s].compareTo(scores[second]) > 0) {
        second = s
      }
    }
    if (best < 0) continue
    val bestScore = scores[best]
    val threshold = if (face.tier == FaceTier.CORE) params.tJoin else params.tJoinWeak
    if (bestScore < threshold) continue
    if (second >= 0 && bestScore - scores[second] < params.margin) continue
    val bestId = candidates[best].first.id
    val samePhoto = ArrayList<GroupAssignment>(2)
    for (other in table.facesOfPhoto(table.photo[row])) {
      if (other == row) continue
      val a = assign[table.faces[other].ref] ?: continue
      if (a.person == bestId && a.origin != AssignOrigin.IGNORED) samePhoto += a
    }
    if (samePhoto.any { it.origin == AssignOrigin.USER }) continue
    val bestRows = candidates[best].second
    if (samePhoto.any { (Exemplars.similarity(table.matrix, table.indexOf(it.face), bestRows) ?: 0f) >= bestScore }) continue
    samePhoto.forEach { remove(it.face) }
    put(GroupAssignment(face.ref, face.locator, bestId, bestScore, AssignOrigin.AUTO))
  }
}

/**
 * Le nascite (§2.5): legame medio sui non assegnati CORE ([BirthGroups]); una persona nasce con almeno 3 volti di almeno 2 foto, con un id
 * nuovo dal [GroupingPass.ids] (in ordine di gruppo).
 */
internal fun GroupingPass.births() {
  val pool = unassignedCoreRows()
  val existing = activePersons().map { exemplarRows(it) }.filter { it.isNotEmpty() }
  for (group in BirthGroups.form(table, pool, params, existing)) {
    bear(ids.next(), group.rows())
    born++
  }
  birthsMark = unassignedCore()
}

/** Una persona [id] dai volti [rows] (crescenti): esemplari dai suoi volti, ogni volto automatico con la sua somiglianza agli esemplari. */
internal fun GroupingPass.bear(id: PersonId, rows: IntArray) {
  val exemplars = Exemplars.choose(table.matrix, rows, table.core, params.maxExemplars)
  val existing = persons[id]
  val refs = exemplars.map { table.faces[it].ref }
  persons[id] = (existing ?: GroupPerson(id, PersonState.ACTIVE, null, newSeq, newSeq, emptyList())).copy(exemplars = refs, changedSeq = newSeq)
  for (row in rows) {
    val face = table.faces[row]
    put(GroupAssignment(face.ref, face.locator, id, Exemplars.similarity(table.matrix, row, exemplars) ?: 0f, AssignOrigin.AUTO))
  }
}

/** Le espulsioni notturne (§2.5 (b)): un volto automatico sotto `tEject` verso la sua persona (esclusi i suoi stessi esemplari). */
internal fun GroupingPass.eject() {
  val auto = HashMap<PersonId, MutableList<GroupAssignment>>()
  for (a in assign.values) if (a.origin == AssignOrigin.AUTO) auto.getOrPut(a.person) { ArrayList() } += a
  for (person in activePersons()) {
    val list = auto[person.id] ?: continue
    val ex = exemplarRows(person)
    for (a in list.sortedBy { it.face }) {
      val row = table.indexOf(a.face)
      if (row < 0) continue
      val s = Exemplars.similarity(table.matrix, row, ex) ?: continue
      if (s < params.tEject) {
        remove(a.face)
        ejected++
      }
    }
  }
}

/** Esemplari rinnovati (§2.5 (c)) per ogni persona attiva, dai suoi volti automatici e confermati. */
internal fun GroupingPass.refreshExemplars() {
  val rows = liveRowsByPerson()
  for (person in activePersons()) {
    val mine = rows[person.id]
    val exemplars = if (mine == null || mine.isEmpty()) {
      emptyList()
    } else {
      Exemplars.choose(table.matrix, mine, table.core, params.maxExemplars).map { table.faces[it].ref }
    }
    if (exemplars != person.exemplars) update(person.copy(exemplars = exemplars))
  }
}
