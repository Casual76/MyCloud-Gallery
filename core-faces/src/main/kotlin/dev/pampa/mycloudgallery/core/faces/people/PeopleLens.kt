package dev.pampa.mycloudgallery.core.faces.people

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * Le persone "come le vede l'utente adesso" (docs/fase8-progetto.md §2.6: ogni azione vale **subito** su questo dispositivo, anche prima che
 * il custode la metta nell'istantanea): l'istantanea del raggruppamento [state] con sopra le correzioni delle voci `people` ([people]).
 * Pura e immutabile, tutta in memoria. E' la lettura che fa `ArchivePeople` (e che l'esecutore di riferimento `testing/PeopleOverlay` fa
 * in piccolo): stesse regole, ma con gli indici per chiave di contenuto, perche' un archivio vero ha decine di migliaia di volti.
 *
 * Regole: gli alias sono quelli dell'istantanea piu' quelli delle voci (le voci vincono); un ciclo ha come radice l'id minore; una persona
 * dimenticata (nell'istantanea o nelle voci) non c'e'; un volto ignorato non e' di nessuno; un volto fissato ("E' …") e' della persona
 * scelta; un volto escluso ("Non e' …") lascia quella persona; **il volto fissato vince sull'esclusione** (per questo "Non e' …" su un volto
 * confermato scrive anche la lapide di `is`, "Fase 8 P5" in `docs/decisioni.md`). Un volto fissato che l'istantanea non conosce conta lo
 * stesso (per posto). Mai nomi o id nei log (D37).
 */
internal class PeopleLens(val state: GroupingState, val people: PeopleState) {

  private val aliases: Map<PersonId, PersonId> = buildMap {
    for (p in state.persons.values) if (p.state == PersonState.MERGED) put(p.id, p.aliasOf!!)
    putAll(people.aliases)
  }

  private val forgotten: Set<PersonId> =
    state.persons.values.filter { it.state == PersonState.FORGOTTEN }.map { it.id }.toSet() + people.forgotten

  private val fixedByKey: Map<ContentKey, List<Pair<FaceLocator, PersonId>>> =
    people.fixed.entries.groupBy({ it.key.contentKey }, { it.key to it.value })

  private val rejectedByKey: Map<ContentKey, List<Pair<FaceLocator, Set<PersonId>>>> =
    people.rejected.entries.groupBy({ it.key.contentKey }, { it.key to it.value })

  private val ignoredByKey: Map<ContentKey, List<FaceLocator>> = people.ignored.groupBy { it.contentKey }

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

  /** La persona fissata dall'utente per il volto in [locator] (abbinamento per posto), o null. */
  fun fixedFor(locator: FaceLocator): PersonId? = fixedByKey[locator.contentKey]?.firstOrNull { it.first.matches(locator) }?.second

  /** Le persone da cui il volto in [locator] e' escluso per sempre. */
  fun rejectedFor(locator: FaceLocator): Set<PersonId> = rejectedKeys(locator).flatMap { it.second }.toSet()

  fun isIgnored(locator: FaceLocator): Boolean = ignoredByKey[locator.contentKey].orEmpty().any { it.matches(locator) }

  // I posti **come sono scritti nelle voci**: una lapide deve avere la stessa chiave della voce che toglie (soggetto = il localizzatore
  // com'e'), anche se il volto di adesso ha un riquadro un po' diverso (stesso volto, IoU >= 0,5).

  /** Le voci `is` che toccano il volto in [locator], con il posto com'e' scritto. */
  fun fixedKeys(locator: FaceLocator): List<Pair<FaceLocator, PersonId>> = fixedByKey[locator.contentKey].orEmpty().filter { it.first.matches(locator) }

  /** Le voci `not` che toccano il volto in [locator], con il posto com'e' scritto. */
  fun rejectedKeys(locator: FaceLocator): List<Pair<FaceLocator, Set<PersonId>>> =
    rejectedByKey[locator.contentKey].orEmpty().filter { it.first.matches(locator) }

  /** Le voci `ignore` che toccano il volto in [locator], con il posto com'e' scritto. */
  fun ignoredKeys(locator: FaceLocator): List<FaceLocator> = ignoredByKey[locator.contentKey].orEmpty().filter { it.matches(locator) }

  /** Ogni volto (per posto) con la persona a cui appartiene adesso. */
  val faces: Map<FaceLocator, PersonId> by lazy {
    val out = LinkedHashMap<FaceLocator, PersonId>()
    val placed = HashMap<ContentKey, MutableList<FaceLocator>>()
    for (a in state.assignments.values.sortedBy { it.face }) {
      if (a.origin == AssignOrigin.IGNORED || isIgnored(a.locator)) continue
      val fixed = fixedFor(a.locator)
      val resolved = resolve(fixed ?: a.person) ?: continue
      if (fixed == null && resolved in rejectedFor(a.locator).mapNotNull(::resolve)) continue
      out[a.locator] = resolved
      placed.getOrPut(a.locator.contentKey) { ArrayList() } += a.locator
    }
    for ((locator, person) in people.fixed.entries.sortedBy { it.key.encode() }) {
      if (isIgnored(locator) || placed[locator.contentKey].orEmpty().any { it.matches(locator) }) continue
      val resolved = resolve(person) ?: continue
      out[locator] = resolved
      placed.getOrPut(locator.contentKey) { ArrayList() } += locator
    }
    out
  }

  private val byKey: Map<ContentKey, List<Pair<FaceLocator, PersonId>>> by lazy {
    faces.entries.groupBy({ it.key.contentKey }, { it.key to it.value })
  }

  private val byPerson: Map<PersonId, List<FaceLocator>> by lazy {
    faces.entries.groupBy({ it.value }, { it.key }).mapValues { (_, list) -> list.sortedBy { it.encode() } }
  }

  /** La persona a cui appartiene adesso il volto in [locator] (per posto), o null se di nessuno. */
  fun ownerOf(locator: FaceLocator): PersonId? = byKey[locator.contentKey].orEmpty().firstOrNull { it.first.matches(locator) }?.second

  /** I volti di [person] (una radice), in un ordine stabile. */
  fun facesOf(person: PersonId): List<FaceLocator> = byPerson[person].orEmpty()

  /** Le persone che esistono adesso (radici attive), anche senza volti (un nome che non si ritrova: "0 foto · ricontrolla"). */
  val persons: Set<PersonId> by lazy {
    val candidates = state.persons.keys + people.born.keys + people.names.keys + people.fixed.values
    candidates.mapNotNull { resolve(it) }.toSortedSet()
  }

  private val assignmentsByKey: Map<ContentKey, List<GroupAssignment>> by lazy { state.assignments.values.groupBy { it.locator.contentKey } }

  /**
   * Il volto era di una persona che ora **non c'e' piu'** (dimenticata, E-F27): i suoi volti diventano ignorati, non "senza nome". Un volto
   * che nessuna persona ha mai avuto, o che l'utente ha tolto da una persona, e' un'altra cosa: resta un volto senza nome.
   */
  fun belongsToForgotten(locator: FaceLocator): Boolean {
    fixedFor(locator)?.let { if (resolve(it) == null) return true }
    return assignmentsByKey[locator.contentKey].orEmpty().any { it.locator.matches(locator) && resolve(it.person) == null }
  }

  /** Il volto e' stato confermato dall'utente ("E' …"): non si sposta e, per la copertina, e' certo. */
  fun isConfirmed(locator: FaceLocator): Boolean = fixedFor(locator) != null

  override fun toString(): String = "PeopleLens(${state.persons.size} persone, $people)"
}
