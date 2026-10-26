package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.MergeSuggestion
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.contract.PersonPair
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId

/** I passi del riferimento (§2.5): ognuno legge e scrive lo stato mutabile di [GroupingWork]. Vedi [ReferenceGrouping] per l'ordine. */
internal class GroupingSteps(
  private val w: GroupingWork,
  private val params: GroupingParams,
  private val corrections: GroupingCorrections,
  private val ids: PersonIdSource,
  private val newSeq: Long,
  private val counts: GroupingWork.Counts,
) {
  val suggestions = ArrayList<MergeSuggestion>()
  var birthsMark: Int? = null

  private val fixedSorted: List<Pair<FaceLocator, PersonId>> = corrections.fixed.entries.map { it.key to it.value }.sortedBy { it.first.encode() }

  private fun fixedFor(locator: FaceLocator): PersonId? = fixedSorted.firstOrNull { it.first.matches(locator) }?.second

  private fun isIgnored(locator: FaceLocator): Boolean = corrections.ignored.any { it.matches(locator) }

  private fun rejectedFor(locator: FaceLocator): Set<PersonId> =
    corrections.rejected.entries.filter { it.key.matches(locator) }.flatMap { it.value }.mapNotNull { resolve(it) }.toSet()

  /** La persona finale di [id] seguendo gli alias (anche dimenticata); un ciclo ha come radice l'id minore del ciclo. */
  private fun root(id: PersonId): PersonId? {
    val seen = LinkedHashSet<PersonId>()
    var current = id
    while (true) {
      val person = w.persons[current] ?: return null
      if (person.state != PersonState.MERGED) return current
      if (!seen.add(current)) return seen.toList().dropWhile { it != current }.min()
      current = person.aliasOf!!
    }
  }

  /** La persona attiva a cui porta [id], o null (sconosciuta o dimenticata). */
  private fun resolve(id: PersonId): PersonId? = root(id)?.takeIf { w.persons[it]?.state == PersonState.ACTIVE }

  private fun update(person: GroupPerson) {
    w.persons[person.id] = person.copy(changedSeq = newSeq)
  }

  private fun exemplarFaces(person: GroupPerson): List<GroupFace> = person.exemplars.mapNotNull { w.faces[it] }

  private fun similarity(face: GroupFace, person: GroupPerson): Float? = GroupingMath.similarity(face, exemplarFaces(person))

  fun removeGone(gone: Set<ContentKey>) {
    val drop = w.assign.keys.filter { it.contentKey in gone }.sorted()
    drop.forEach { w.assign.remove(it) }
    counts.removed += drop.size
  }

  /** Le assegnazioni di volti che non sono piu' correnti escono; le ignorate passano al volto nello stesso posto (D23). */
  fun dropStale(current: List<GroupFace>) {
    for ((ref, a) in w.assign.entries.sortedBy { it.key }.map { it.key to it.value }) {
      if (ref in w.faces) continue
      w.assign.remove(ref)
      if (a.origin == AssignOrigin.IGNORED) {
        val match = current.firstOrNull { it.ref !in w.assign && a.locator.matches(it.locator) }
        if (match != null) w.put(GroupAssignment(match.ref, match.locator, a.person, 0f, AssignOrigin.IGNORED))
      }
    }
    for (person in w.persons.values.toList()) {
      if (person.exemplars.any { it !in w.faces }) update(person.copy(exemplars = person.exemplars.filter { it in w.faces }))
    }
  }

  /** Le correzioni come vincoli (D23), a ogni passo: nate, alias, dimenticate, ignorati, "Non e'", "E'". */
  fun applyCorrections(current: List<GroupFace>) {
    val needed = (corrections.born + corrections.fixed.values + corrections.aliases.keys + corrections.aliases.values).toSortedSet()
    for (id in needed) if (id !in w.persons) w.persons[id] = GroupPerson(id, PersonState.ACTIVE, null, newSeq, newSeq, emptyList())
    for ((from, into) in corrections.aliases.entries.sortedBy { it.key }) {
      val person = w.persons.getValue(from)
      if (person.state == PersonState.FORGOTTEN || (person.state == PersonState.MERGED && person.aliasOf == into)) continue
      update(person.copy(state = PersonState.MERGED, aliasOf = into, exemplars = emptyList()))
    }
    normalizeCycles()
    for (id in corrections.forgotten.sorted()) {
      val person = w.persons[id] ?: GroupPerson(id, PersonState.FORGOTTEN, null, newSeq, newSeq, emptyList())
      if (person.state != PersonState.FORGOTTEN || id !in w.persons) update(person.copy(state = PersonState.FORGOTTEN, aliasOf = null, exemplars = emptyList()))
    }
    for (a in w.assign.values.sortedBy { it.face }) {
      val target = w.persons[a.person]
      val rootId = root(a.person)
      when {
        target == null || rootId == null -> w.assign.remove(a.face)
        w.persons.getValue(rootId).state == PersonState.FORGOTTEN ->
          if (a.origin != AssignOrigin.IGNORED || a.person != rootId) w.put(a.copy(person = rootId, origin = AssignOrigin.IGNORED, score = 0f))
        rootId != a.person -> w.put(a.copy(person = rootId))
      }
    }
    for (a in w.assign.values.sortedBy { it.face }) {
      if (a.origin != AssignOrigin.IGNORED && isIgnored(a.locator)) w.assign.remove(a.face)
    }
    for (a in w.assign.values.sortedBy { it.face }) {
      if (a.origin == AssignOrigin.AUTO && a.person in rejectedFor(a.locator)) w.assign.remove(a.face)
    }
    for (face in current) {
      val fixedTo = fixedFor(face.locator) ?: continue
      if (isIgnored(face.locator)) continue
      val rootId = root(fixedTo) ?: continue
      val existing = w.assign[face.ref]
      if (w.persons.getValue(rootId).state == PersonState.FORGOTTEN) {
        w.put(GroupAssignment(face.ref, face.locator, rootId, 0f, AssignOrigin.IGNORED))
        continue
      }
      if (existing?.person == rootId && existing.origin == AssignOrigin.USER) continue
      val score = similarity(face, w.persons.getValue(rootId)) ?: 0f
      w.put(GroupAssignment(face.ref, face.locator, rootId, score, AssignOrigin.USER))
      // Due volti della stessa foto mai nella stessa persona: un automatico della stessa foto esce.
      for (other in w.assign.values.sortedBy { it.face }) {
        if (other.face != face.ref && other.person == rootId && other.origin == AssignOrigin.AUTO && w.faces[other.face]?.photo == face.photo) {
          w.assign.remove(other.face)
        }
      }
    }
  }

  /** Un ciclo di alias (due unioni contrarie da due dispositivi) ha come radice l'id minore: quella torna attiva (§2.5 "Alias"). */
  private fun normalizeCycles() {
    for (id in w.persons.keys.sorted()) {
      val person = w.persons.getValue(id)
      if (person.state != PersonState.MERGED) continue
      val r = root(id) ?: continue
      val rp = w.persons.getValue(r)
      if (rp.state == PersonState.MERGED) update(rp.copy(state = PersonState.ACTIVE, aliasOf = null))
    }
  }

  /** L'assegnazione (§2.5 "Assegnazione") dei volti non assegnati, in ordine di riferimento, con gli esemplari di inizio passo. */
  fun assignUnassigned(current: List<GroupFace>) {
    val candidates = w.activePersons().map { it to exemplarFaces(it) }.filter { it.second.isNotEmpty() }
    for (face in current) {
      if (face.ref in w.assign || isIgnored(face.locator) || fixedFor(face.locator) != null) continue
      val rejected = rejectedFor(face.locator)
      val scored = candidates.filter { it.first.id !in rejected }
        .mapNotNull { (p, ex) -> GroupingMath.similarity(face, ex)?.let { p to it } }
        .sortedWith(compareByDescending<Pair<GroupPerson, Float>> { it.second }.thenBy { it.first.id })
      val best = scored.firstOrNull() ?: continue
      val threshold = if (face.tier == FaceTier.CORE) params.tJoin else params.tJoinWeak
      if (best.second < threshold) continue
      val second = scored.getOrNull(1)
      if (second != null && best.second - second.second < params.margin) continue
      val samePhoto = w.assign.values.filter { it.person == best.first.id && it.origin != AssignOrigin.IGNORED && w.faces[it.face]?.photo == face.photo }
      if (samePhoto.any { it.origin == AssignOrigin.USER }) continue
      val bestExemplars = candidates.first { it.first.id == best.first.id }.second
      if (samePhoto.any { other -> (w.faces[other.face]?.let { GroupingMath.similarity(it, bestExemplars) } ?: 0f) >= best.second }) continue
      samePhoto.forEach { w.assign.remove(it.face) }
      w.put(GroupAssignment(face.ref, face.locator, best.first.id, best.second, AssignOrigin.AUTO))
    }
  }

  fun unassignedCore(current: List<GroupFace>): Int =
    current.count { it.tier == FaceTier.CORE && it.ref !in w.assign && !isIgnored(it.locator) && fixedFor(it.locator) == null }

  /** Le nascite (§2.5): legame medio sui non assegnati CORE; una persona nasce con almeno 3 volti di almeno 2 foto. */
  fun births(current: List<GroupFace>) {
    val pool = current.filter { it.tier == FaceTier.CORE && it.ref !in w.assign && !isIgnored(it.locator) && fixedFor(it.locator) == null }
    for (group in GroupingMath.averageLinkage(pool, params.tBirth, params.tEdge)) {
      if (group.size < params.minFaces || group.map { it.ref.contentKey }.distinct().size < params.minPhotos) continue
      val id = ids.next()
      bear(id, group)
      counts.born++
    }
    birthsMark = unassignedCore(current)
  }

  private fun bear(id: PersonId, group: List<GroupFace>) {
    val exemplars = GroupingMath.exemplars(group, params.maxExemplars)
    val existing = w.persons[id]
    w.persons[id] = (existing ?: GroupPerson(id, PersonState.ACTIVE, null, newSeq, newSeq, emptyList())).copy(exemplars = exemplars, changedSeq = newSeq)
    val ex = exemplars.mapNotNull { w.faces[it] }
    for (face in group) w.put(GroupAssignment(face.ref, face.locator, id, GroupingMath.similarity(face, ex) ?: 0f, AssignOrigin.AUTO))
  }

  /** Le espulsioni notturne (§2.5 (b)): un volto automatico sotto `tEject` verso la sua persona (esclusi i suoi stessi esemplari). */
  fun eject() {
    for (person in w.activePersons()) {
      val ex = exemplarFaces(person)
      for (a in w.assign.values.filter { it.person == person.id && it.origin == AssignOrigin.AUTO }.sortedBy { it.face }) {
        val face = w.faces[a.face] ?: continue
        val s = GroupingMath.similarity(face, ex) ?: continue
        if (s < params.tEject) {
          w.assign.remove(a.face)
          counts.ejected++
        }
      }
    }
  }

  /** Esemplari rinnovati (§2.5 (c)) per ogni persona attiva, dai suoi volti automatici e confermati. */
  fun refreshExemplars() {
    for (person in w.activePersons()) {
      val faces = w.facesOf(person.id)
      val exemplars = if (faces.isEmpty()) emptyList() else GroupingMath.exemplars(faces, params.maxExemplars)
      if (exemplars != person.exemplars) update(person.copy(exemplars = exemplars))
    }
  }

  /** Le unioni prudenti (§2.5 (a)): sopra `tMerge` da sole, fra `tSuggest` e `tMerge` (o con due nomi) una proposta. */
  fun merges() {
    val active = w.activePersons().filter { it.exemplars.isNotEmpty() }
    val ex = active.associate { it.id to exemplarFaces(it) }
    val centers = active.associate { it.id to GroupingMath.centroid(w.facesOf(it.id)) }
    val photos = HashMap(active.associate { p -> p.id to w.facesOf(p.id).map { it.photo }.toMutableSet() })
    val pairs = ArrayList<Triple<PersonId, PersonId, Float>>()
    for (i in active.indices) {
      for (j in i + 1 until active.size) {
        val a = active[i].id
        val b = active[j].id
        val ca = centers[a] ?: continue
        val cb = centers[b] ?: continue
        if (GroupingMath.dot(ca, cb) < params.tSuggest) continue
        val link = GroupingMath.averageLink(ex.getValue(a), ex.getValue(b))
        if (link >= params.tSuggest) pairs += Triple(a, b, link)
      }
    }
    pairs.sortWith(compareByDescending<Triple<PersonId, PersonId, Float>> { it.third }.thenBy { it.first }.thenBy { it.second })
    for ((a, b, link) in pairs) {
      if (w.persons[a]?.state != PersonState.ACTIVE || w.persons[b]?.state != PersonState.ACTIVE) continue
      if (PersonPair.of(a, b) in corrections.apart) continue
      if (photos.getValue(a).any { it in photos.getValue(b) }) continue
      val namedA = a in corrections.named
      val namedB = b in corrections.named
      if (link >= params.tMerge && !(namedA && namedB)) {
        val survivor = when {
          namedA && !namedB -> a
          namedB && !namedA -> b
          else -> listOf(a, b).sortedWith(compareByDescending<PersonId> { w.facesOf(it).size }.thenBy { it }).first()
        }
        val loser = if (survivor == a) b else a
        update(w.persons.getValue(loser).copy(state = PersonState.MERGED, aliasOf = survivor, exemplars = emptyList()))
        for (x in w.assign.values.filter { it.person == loser }.sortedBy { it.face }) w.put(x.copy(person = survivor))
        photos.getValue(survivor).addAll(photos.getValue(loser))
        counts.merged++
      } else {
        suggestions += MergeSuggestion(PersonPair.of(a, b), link)
      }
    }
  }

  /** Il ricalcolo completo (D22, E-F24): da capo sui volti CORE, poi l'abbinamento ottimo alle persone vecchie per volti in comune. */
  fun rebuildFromScratch(current: List<GroupFace>) {
    val old = w.assign.values.sortedBy { it.face }
    val oldActive = w.persons.values.filter { it.state == PersonState.ACTIVE }.sortedBy { it.id }
    w.assign.clear()
    for (person in w.persons.values.toList()) if (person.exemplars.isNotEmpty()) update(person.copy(exemplars = emptyList()))
    for (a in old.filter { it.origin == AssignOrigin.IGNORED }) {
      val match = current.firstOrNull { it.ref !in w.assign && a.locator.matches(it.locator) }
      if (match != null) w.put(GroupAssignment(match.ref, match.locator, a.person, 0f, AssignOrigin.IGNORED))
    }
    val pool = current.filter { it.tier == FaceTier.CORE && it.ref !in w.assign && !isIgnored(it.locator) && fixedFor(it.locator) == null }
    val groups = GroupingMath.averageLinkage(pool, params.tBirth, params.tEdge)
      .filter { g -> g.size >= params.minFaces && g.map { it.ref.contentKey }.distinct().size >= params.minPhotos }
    val oldFaces = oldActive.map { p -> old.filter { it.person == p.id && it.origin != AssignOrigin.IGNORED }.map { it.locator } }
    val weights = Array(groups.size) { g ->
      DoubleArray(oldActive.size) { p -> groups[g].count { face -> oldFaces[p].any { it.matches(face.locator) } }.toDouble() }
    }
    val match = GroupingMath.hungarian(weights)
    for ((g, group) in groups.withIndex()) {
      val p = match.getOrElse(g) { -1 }
      val inherited = p >= 0 && weights[g][p] > 0 && weights[g][p] >= GroupingParams.INHERIT_SHARE * group.size
      val id = if (inherited) oldActive[p].id else ids.next().also { counts.born++ }
      bear(id, group)
    }
    birthsMark = unassignedCore(current)
  }
}
