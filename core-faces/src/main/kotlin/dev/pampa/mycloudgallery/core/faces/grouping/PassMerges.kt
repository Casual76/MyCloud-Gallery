package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.MergeSuggestion
import dev.pampa.mycloudgallery.core.faces.contract.PersonPair
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.contract.PhotoKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * Le unioni prudenti (§2.5 (a)): per le coppie di persone con i centroidi >= `tSuggest`, il legame medio fra gli esemplari; sopra `tMerge`
 * l'unione automatica (alias, D22) se le due persone non sono mai insieme in una foto, non hanno entrambe un nome e non sono separate;
 * altrimenti (fra `tSuggest` e `tMerge`, o con due nomi) una proposta. Due regole in piu' del riferimento di P0, portate anche li':
 * - **F3**: niente unione ne' proposta se l'utente ha escluso un volto di una delle due dall'altra ("Non e' …"): l'unione lo rimetterebbe
 *   nella persona da cui e' stato tolto per sempre;
 * - **F5**: "Separati" vale per le persone a cui portano gli alias: se A e B sono separate e A e' stata unita (a mano) in C, C e B non si
 *   uniscono da sole (E-F1, E-F20: "non si riuniscono mai da sole").
 */
internal fun GroupingPass.merges() {
  val active = activePersons().filter { it.exemplars.isNotEmpty() }
  val live = liveRowsByPerson()
  val ex = active.associate { it.id to exemplarRows(it) }
  val centers = active.associate { it.id to Exemplars.centroid(table.matrix, live[it.id] ?: IntArray(0)) }
  val photos = HashMap<PersonId, MutableSet<Int>>()
  val rows = HashMap<PersonId, MutableList<Int>>()
  for (p in active) {
    val mine = live[p.id] ?: IntArray(0)
    photos[p.id] = mine.mapTo(HashSet()) { table.photo[it] }
    rows[p.id] = mine.toMutableList()
  }
  val owned = HashMap<PersonId, MutableList<FaceRef>>()
  for (a in assign.values) owned.getOrPut(a.person) { ArrayList() } += a.face
  val pairs = ArrayList<Triple<PersonId, PersonId, Float>>()
  for (i in active.indices) {
    for (j in i + 1 until active.size) {
      val a = active[i].id
      val b = active[j].id
      val ca = centers[a] ?: continue
      val cb = centers[b] ?: continue
      if (Exemplars.dot(ca, cb) < params.tSuggest) continue
      val link = Exemplars.averageLink(table.matrix, ex.getValue(a), ex.getValue(b))
      if (link >= params.tSuggest) pairs += Triple(a, b, link)
    }
  }
  pairs.sortWith(compareByDescending<Triple<PersonId, PersonId, Float>> { it.third }.thenBy { it.first }.thenBy { it.second })
  for ((a, b, link) in pairs) {
    if (persons[a]?.state != PersonState.ACTIVE || persons[b]?.state != PersonState.ACTIVE) continue
    if (apart(a, b)) continue
    if (photos.getValue(a).any { it in photos.getValue(b) }) continue
    if (rejects(rows.getValue(a), b) || rejects(rows.getValue(b), a)) continue
    val namedA = a in corrections.named
    val namedB = b in corrections.named
    if (link >= params.tMerge && !(namedA && namedB)) {
      val survivor = when {
        namedA && !namedB -> a
        namedB && !namedA -> b
        else -> listOf(a, b).sortedWith(compareByDescending<PersonId> { rows.getValue(it).size }.thenBy { it }).first()
      }
      val loser = if (survivor == a) b else a
      update(persons.getValue(loser).copy(state = PersonState.MERGED, aliasOf = survivor, exemplars = emptyList()))
      for (ref in owned.remove(loser).orEmpty()) {
        val x = assign[ref] ?: continue
        put(x.copy(person = survivor))
        owned.getOrPut(survivor) { ArrayList() } += ref
      }
      photos.getValue(survivor).addAll(photos.getValue(loser))
      rows.getValue(survivor).addAll(rows.getValue(loser))
      merged++
    } else {
      suggestions += MergeSuggestion(PersonPair.of(a, b), link)
    }
  }
}

/** F5: due persone attive separate dall'utente, direttamente o attraverso gli alias. */
private fun GroupingPass.apart(a: PersonId, b: PersonId): Boolean = corrections.apart.any { pair ->
  val x = root(pair.first)
  val y = root(pair.second)
  (x == a && y == b) || (x == b && y == a)
}

/** F3: qualcuno dei volti [rows] e' escluso dall'utente dalla persona [other]. */
private fun GroupingPass.rejects(rows: List<Int>, other: PersonId): Boolean =
  rows.any { row -> other in rejectedFor(table.faces[row].locator) }

/**
 * Il ricalcolo completo (D22, E-F24): da capo sui volti CORE con le nascite ([BirthGroups]), poi l'abbinamento ottimo dei gruppi nuovi
 * alle persone vecchie per volti in comune (stesso posto, IoU >= 0,5) **per componenti** ([OptimalMatching], F4): un gruppo eredita l'id
 * se condivide almeno il 30% dei suoi volti con la persona abbinata, altrimenti ha un id nuovo. I volti ignorati passano per posto, e con
 * loro (F6) quelli di una persona che una correzione "Dimentica" arrivata adesso toglie (le correzioni si applicano dopo).
 */
internal fun GroupingPass.rebuildFromScratch() {
  val old = assign.values.sortedBy { it.face }
  val oldActive = persons.values.filter { it.state == PersonState.ACTIVE }.sortedBy { it.id }
  assign.clear()
  for (person in persons.values.toList()) if (person.exemplars.isNotEmpty()) update(person.copy(exemplars = emptyList()))
  // F6: anche i volti di una persona che una correzione "Dimentica" arrivata adesso toglie passano come ignorati.
  for (a in old) {
    val forgottenRoot = if (a.origin == AssignOrigin.IGNORED) a.person else root(a.person)?.takeIf { it in corrections.forgotten } ?: continue
    val row = table.facesAt(a.locator).firstOrNull { r -> table.faces[r].ref !in assign && a.locator.matches(table.faces[r].locator) }
    if (row != null) {
      val face = table.faces[row]
      put(GroupAssignment(face.ref, face.locator, forgottenRoot, 0f, AssignOrigin.IGNORED))
    }
  }
  val groups = BirthGroups.form(table, unassignedCoreRows(), params, emptyList()).map { it.rows() }
  // I posti dei volti vecchi di ogni persona attiva, per foto: chi ha un volto nello stesso posto.
  val column = oldActive.withIndex().associate { (i, p) -> p.id to i }
  val places = HashMap<PhotoKey, MutableList<Pair<FaceLocator, Int>>>()
  for (a in old) {
    if (a.origin == AssignOrigin.IGNORED) continue
    val p = column[a.person] ?: continue
    places.getOrPut(PhotoKey(a.locator.contentKey, a.locator.frame)) { ArrayList() } += a.locator to p
  }
  val weights = HashMap<Pair<Int, Int>, Double>()
  for ((g, group) in groups.withIndex()) {
    for (row in group) {
      val face = table.faces[row]
      val here = places[face.photo] ?: continue
      for (p in here.filter { it.first.matches(face.locator) }.map { it.second }.distinct()) {
        weights[g to p] = (weights[g to p] ?: 0.0) + 1.0
      }
    }
  }
  val match = OptimalMatching.match(groups.size, oldActive.size, weights)
  for ((g, group) in groups.withIndex()) {
    val p = match[g]
    val w = if (p >= 0) weights[g to p] ?: 0.0 else 0.0
    val inherited = p >= 0 && w > 0 && w >= GroupingParams.INHERIT_SHARE * group.size
    val id = if (inherited) oldActive[p].id else ids.next().also { born++ }
    bear(id, group)
  }
  birthsMark = unassignedCore()
}
