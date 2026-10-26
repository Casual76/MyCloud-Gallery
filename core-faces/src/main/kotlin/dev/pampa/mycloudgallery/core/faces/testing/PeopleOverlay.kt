package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * La lettura delle persone "come le vede l'utente adesso" (docs/fase8-progetto.md §2.6: ogni azione vale **subito** su questo dispositivo,
 * anche prima che il custode la metta nell'istantanea): l'istantanea del raggruppamento [state] con sopra le correzioni delle voci `people`
 * ([people]). Pura, per l'esecutore di riferimento [InMemoryPeople]; P9 fa lo stesso sopra l'archivio.
 *
 * Regole: gli alias sono quelli dell'istantanea piu' quelli delle voci (le voci vincono); un ciclo ha come radice l'id minore; una persona
 * dimenticata (nell'istantanea o nelle voci) non c'e'; un volto ignorato non e' di nessuno; un volto fissato ("E' …") e' della persona
 * scelta; un volto escluso ("Non e' …") lascia quella persona. Un volto fissato che l'istantanea non conosce conta lo stesso (per posto).
 */
internal class PeopleOverlay(private val state: GroupingState, val people: PeopleState) {

  private val aliases: Map<PersonId, PersonId> = buildMap {
    for (p in state.persons.values) if (p.state == PersonState.MERGED) put(p.id, p.aliasOf!!)
    putAll(people.aliases)
  }

  private val forgotten: Set<PersonId> =
    state.persons.values.filter { it.state == PersonState.FORGOTTEN }.map { it.id }.toSet() + people.forgotten

  /** La persona a cui porta [id] (alias, cicli: l'id minore), o null se dimenticata o sconosciuta. */
  fun resolve(id: PersonId): PersonId? {
    val seen = LinkedHashSet<PersonId>()
    var current = id
    while (true) {
      if (current in forgotten) return null
      val next = aliases[current] ?: return current.takeIf { known(it) }
      if (!seen.add(current)) return seen.toList().dropWhile { it != current }.min().takeIf { it !in forgotten }
      current = next
    }
  }

  private fun known(id: PersonId): Boolean =
    id in state.persons || id in people.born || id in people.names || id in people.fixed.values || id in people.aliases.values

  /** Ogni volto (per posto) con la persona a cui appartiene adesso. */
  val faces: Map<FaceLocator, PersonId> by lazy {
    val out = LinkedHashMap<FaceLocator, PersonId>()
    for (a in state.assignments.values.sortedBy { it.face }) {
      if (a.origin == AssignOrigin.IGNORED || people.isIgnored(a.locator)) continue
      val fixed = people.fixedFor(a.locator)
      val resolved = resolve(fixed ?: a.person) ?: continue
      if (fixed == null && resolved in people.rejectedFor(a.locator).mapNotNull(::resolve)) continue
      out[a.locator] = resolved
    }
    for ((locator, person) in people.fixed.entries.sortedBy { it.key.encode() }) {
      if (people.isIgnored(locator) || out.keys.any { it.matches(locator) }) continue
      val resolved = resolve(person) ?: continue
      out[locator] = resolved
    }
    out
  }

  /** Le persone che esistono adesso (radici attive), anche senza volti (un nome che non si ritrova: "0 foto · ricontrolla"). */
  val persons: Set<PersonId> by lazy {
    val candidates = state.persons.keys + people.born.keys + people.names.keys + people.fixed.values
    candidates.mapNotNull { resolve(it) }.toSortedSet()
  }

  fun facesOf(person: PersonId): List<FaceLocator> = faces.filter { it.value == person }.keys.sortedBy { it.encode() }
}
