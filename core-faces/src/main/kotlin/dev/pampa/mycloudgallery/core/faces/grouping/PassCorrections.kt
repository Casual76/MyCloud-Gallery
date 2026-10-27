package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.PersonId

// I passi del nucleo vero che tengono in ordine lo stato (§2.5 "Correzioni", D22, D23, E-F15, E-F16, E-F39): i contenuti spariti, i volti
// non piu' correnti e le correzioni dell'utente come vincoli. Stesse regole e stesso ordine di `GroupingSteps` del riferimento.

/** (e) della notte: i volti dei contenuti spariti o diventati documenti escono (E-F16, E-F39). */
internal fun GroupingPass.removeGone(gone: Set<ContentKey>) {
  val drop = assign.keys.filter { it.contentKey in gone }.sorted()
  drop.forEach { remove(it) }
  removed += drop.size
}

/**
 * Le assegnazioni di volti che non sono piu' correnti escono; le ignorate passano al volto nello stesso posto (D23), e con loro (F6) quelle
 * di una persona che una correzione "Dimentica" toglie (il corpo vincente di un contenuto e' cambiato proprio mentre arriva "Dimentica").
 */
internal fun GroupingPass.dropStale() {
  val stale = assign.entries.filter { it.key !in table }.sortedBy { it.key }.map { it.key to it.value }
  for ((ref, a) in stale) {
    remove(ref)
    val forgottenRoot = if (a.origin == AssignOrigin.IGNORED) a.person else root(a.person)?.takeIf { it in corrections.forgotten } ?: continue
    val row = table.facesAt(a.locator).firstOrNull { r -> table.faces[r].ref !in assign && a.locator.matches(table.faces[r].locator) }
    if (row != null) {
      val face = table.faces[row]
      put(GroupAssignment(face.ref, face.locator, forgottenRoot, 0f, AssignOrigin.IGNORED))
    }
  }
  for (person in persons.values.toList()) {
    if (person.exemplars.any { it !in table }) update(person.copy(exemplars = person.exemplars.filter { it in table }))
  }
}

/**
 * Le correzioni come vincoli (D23), a ogni passo: nate, alias, dimenticate, ignorati, "Non e'", "E'". Con due regole in piu' del
 * riferimento di P0, portate anche li' (Fase 8 P5, `docs/decisioni.md`):
 * - **F1**: un volto confermato ("E' …", origine dell'utente) la cui correzione non c'e' piu' (lapide di `is`) torna libero: un'origine
 *   dell'utente vale solo finche' c'e' la sua correzione; senza, un "Non e' …" scritto dopo non lo toglierebbe mai;
 * - **F2**: dopo gli alias dell'utente, una sola faccia automatica per persona e per foto (due persone unite a mano possono avere ciascuna
 *   un volto della stessa foto): resta quella dell'utente se c'e', altrimenti l'automatica piu' simile agli esemplari (a parita' il
 *   riferimento minore); le altre tornano fra i non assegnati (E-F15).
 */
internal fun GroupingPass.applyCorrections() {
  val needed = (corrections.born + corrections.fixed.values + corrections.aliases.keys + corrections.aliases.values).toSortedSet()
  for (id in needed) if (id !in persons) persons[id] = GroupPerson(id, PersonState.ACTIVE, null, newSeq, newSeq, emptyList())
  for ((from, into) in corrections.aliases.entries.sortedBy { it.key }) {
    val person = persons.getValue(from)
    if (person.state == PersonState.FORGOTTEN || (person.state == PersonState.MERGED && person.aliasOf == into)) continue
    update(person.copy(state = PersonState.MERGED, aliasOf = into, exemplars = emptyList()))
  }
  normalizeCycles()
  for (id in corrections.forgotten.sorted()) {
    val person = persons[id] ?: GroupPerson(id, PersonState.FORGOTTEN, null, newSeq, newSeq, emptyList())
    if (person.state != PersonState.FORGOTTEN || id !in persons) {
      update(person.copy(state = PersonState.FORGOTTEN, aliasOf = null, exemplars = emptyList()))
    }
  }
  for (a in assign.values.toList()) {
    val rootId = root(a.person)
    when {
      persons[a.person] == null || rootId == null -> remove(a.face)
      persons.getValue(rootId).state == PersonState.FORGOTTEN ->
        if (a.origin != AssignOrigin.IGNORED || a.person != rootId) put(a.copy(person = rootId, origin = AssignOrigin.IGNORED, score = 0f))
      rootId != a.person -> put(a.copy(person = rootId))
    }
  }
  for (a in assign.values.toList()) {
    when {
      a.origin != AssignOrigin.IGNORED && index.isIgnored(a.locator) -> remove(a.face)
      a.origin == AssignOrigin.AUTO && a.person in rejectedFor(a.locator) -> remove(a.face)
      a.origin == AssignOrigin.USER && index.fixedFor(a.locator) == null -> remove(a.face)
    }
  }
  for (row in 0 until table.size) {
    val face = table.faces[row]
    val fixedTo = index.fixedFor(face.locator) ?: continue
    if (index.isIgnored(face.locator)) continue
    val rootId = root(fixedTo) ?: continue
    val existing = assign[face.ref]
    if (persons.getValue(rootId).state == PersonState.FORGOTTEN) {
      put(GroupAssignment(face.ref, face.locator, rootId, 0f, AssignOrigin.IGNORED))
      continue
    }
    if (existing?.person == rootId && existing.origin == AssignOrigin.USER) continue
    val score = similarity(row, persons.getValue(rootId)) ?: 0f
    put(GroupAssignment(face.ref, face.locator, rootId, score, AssignOrigin.USER))
    // Due volti della stessa foto mai nella stessa persona: un automatico della stessa foto esce.
    for (other in table.facesOfPhoto(table.photo[row])) {
      if (other == row) continue
      val a = assign[table.faces[other].ref] ?: continue
      if (a.person == rootId && a.origin == AssignOrigin.AUTO) remove(a.face)
    }
  }
  onePhotoPerPerson()
}

/** F2: una sola faccia automatica per persona e per foto (vedi [applyCorrections]). */
private fun GroupingPass.onePhotoPerPerson() {
  val groups = HashMap<Pair<PersonId, Int>, IntArrayBuilder>()
  for (a in assign.values) {
    if (a.origin == AssignOrigin.IGNORED) continue
    val row = table.indexOf(a.face)
    if (row >= 0) groups.getOrPut(a.person to table.photo[row]) { IntArrayBuilder(2) }.add(row)
  }
  for ((key, builder) in groups) {
    if (builder.size < 2) continue
    val rows = builder.toArray().apply { sort() }
    val live = rows.map { assign.getValue(table.faces[it].ref) }
    if (live.any { it.origin == AssignOrigin.USER }) {
      live.filter { it.origin == AssignOrigin.AUTO }.forEach { remove(it.face) }
      continue
    }
    val person = persons.getValue(key.first)
    var keep = rows[0]
    var keepScore = similarity(keep, person) ?: 0f
    for (i in 1 until rows.size) {
      val s = similarity(rows[i], person) ?: 0f
      if (s.compareTo(keepScore) > 0) {
        keep = rows[i]
        keepScore = s
      }
    }
    for (r in rows) if (r != keep) remove(table.faces[r].ref)
  }
}

/** Un ciclo di alias (due unioni contrarie da due dispositivi) ha come radice l'id minore: quella torna attiva (§2.5 "Alias"). */
private fun GroupingPass.normalizeCycles() {
  for (id in persons.keys.sorted()) {
    val person = persons.getValue(id)
    if (person.state != PersonState.MERGED) continue
    val r = root(id) ?: continue
    val rp = persons.getValue(r)
    if (rp.state == PersonState.MERGED) update(rp.copy(state = PersonState.ACTIVE, aliasOf = null))
  }
}
