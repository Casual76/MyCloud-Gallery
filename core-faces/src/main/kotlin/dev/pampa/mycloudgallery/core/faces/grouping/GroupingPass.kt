package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingOutcome
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingReport
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.MergeSuggestion
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * Lo stato mutabile di un passo del nucleo vero ([FastGrouping]): lo stesso di `GroupingWork` del riferimento (persone, assegnazioni con
 * i loro riferimenti, contatori, proposte) piu' gli indici che il riferimento non ha (la tabella dei volti, le correzioni per foto). I passi
 * stanno in `PassCorrections.kt` e `PassSteps.kt`; ognuno scorre gli insiemi nello stesso ordine del riferimento dove l'ordine conta.
 */
internal class GroupingPass(private val input: GroupingInput, val ids: PersonIdSource) {
  val params: GroupingParams = input.params
  val corrections = input.corrections
  val index = CorrectionIndex(corrections)

  /** I volti correnti (tolti i contenuti spariti), un volto per riferimento (l'ultimo, come `associateBy`), in ordine di riferimento. */
  val table = FaceTable(input.faces.filter { it.ref.contentKey !in input.gone }.associateBy { it.ref }.values.sortedBy { it.ref })
  val newSeq: Long = input.state.seq + 1

  val persons = LinkedHashMap<PersonId, GroupPerson>().apply {
    input.state.persons.keys.sorted().forEach { put(it, input.state.persons.getValue(it)) }
  }
  val assign = HashMap(input.state.assignments)

  var born = 0
  var merged = 0
  var ejected = 0
  var removed = 0
  val suggestions = ArrayList<MergeSuggestion>()
  var birthsMark: Int? = null

  /** La persona finale di [id] seguendo gli alias (anche dimenticata); un ciclo ha come radice l'id minore del ciclo. */
  fun root(id: PersonId): PersonId? {
    var current = id
    var seen: LinkedHashSet<PersonId>? = null
    while (true) {
      val person = persons[current] ?: return null
      if (person.state != PersonState.MERGED) return current
      if (seen == null) seen = LinkedHashSet()
      if (!seen.add(current)) return seen.toList().dropWhile { it != current }.min()
      current = person.aliasOf!!
    }
  }

  /** La persona attiva a cui porta [id], o null (sconosciuta o dimenticata). */
  fun resolve(id: PersonId): PersonId? = root(id)?.takeIf { persons[it]?.state == PersonState.ACTIVE }

  fun update(person: GroupPerson) {
    persons[person.id] = person.copy(changedSeq = newSeq)
  }

  fun put(assignment: GroupAssignment) {
    assign[assignment.face] = assignment
  }

  fun remove(ref: FaceRef) {
    assign.remove(ref)
  }

  /** Le persone attive, in ordine di id. */
  fun activePersons(): List<GroupPerson> = persons.values.filter { it.state == PersonState.ACTIVE }.sortedBy { it.id }

  /** Le righe degli esemplari di [person] che sono volti correnti, nell'ordine degli esemplari. */
  fun exemplarRows(person: GroupPerson): IntArray {
    val out = IntArrayBuilder(person.exemplars.size)
    for (ref in person.exemplars) {
      val row = table.indexOf(ref)
      if (row >= 0) out.add(row)
    }
    return out.toArray()
  }

  fun similarity(row: Int, person: GroupPerson): Float? = Exemplars.similarity(table.matrix, row, exemplarRows(person))

  /** Le persone (risolte negli alias) da cui il volto in [locator] e' escluso per sempre ("Non e' …"). */
  fun rejectedFor(locator: FaceLocator): Set<PersonId> {
    if (!index.mayBeRejected(locator)) return emptySet()
    return index.rejectedRaw(locator).mapNotNullTo(HashSet()) { resolve(it) }
  }

  /** Per ogni persona le righe dei suoi volti automatici e confermati, in ordine crescente (`facesOf` del riferimento). */
  fun liveRowsByPerson(): Map<PersonId, IntArray> {
    val buckets = HashMap<PersonId, IntArrayBuilder>()
    for (a in assign.values) {
      if (a.origin == AssignOrigin.IGNORED) continue
      val row = table.indexOf(a.face)
      if (row >= 0) buckets.getOrPut(a.person) { IntArrayBuilder() }.add(row)
    }
    return buckets.mapValues { (_, b) -> b.toArray().apply { sort() } }
  }

  /** Le righe dei volti CORE senza assegnazione, non ignorati e non fissati: il serbatoio delle nascite. */
  fun unassignedCoreRows(): IntArray {
    val out = IntArrayBuilder()
    for (row in 0 until table.size) if (isFreeCore(row)) out.add(row)
    return out.toArray()
  }

  fun unassignedCore(): Int {
    var n = 0
    for (row in 0 until table.size) if (isFreeCore(row)) n++
    return n
  }

  private fun isFreeCore(row: Int): Boolean {
    val face = table.faces[row]
    return table.core[row] && face.ref !in assign && !index.isIgnored(face.locator) && index.fixedFor(face.locator) == null
  }

  fun finish(publish: Boolean): GroupingOutcome {
    val start = input.state
    val changed = persons != start.persons || assign != start.assignments
    val report = GroupingReport(
      assigned = assign.values.count { it.origin != AssignOrigin.IGNORED },
      unassigned = table.faces.count { it.ref !in assign },
      born = born,
      merged = merged,
      ejected = ejected,
      removed = removed,
      persons = persons.values.count { it.state == PersonState.ACTIVE },
    )
    val mark = if (publish) birthsMark ?: start.unassignedAtBirths else start.unassignedAtBirths
    val state = if (!changed || !publish) {
      GroupingState(start.seq, start.base, persons.toMap(), assign.toMap(), mark)
    } else {
      GroupingState(newSeq, start.seq, persons.toMap(), assign.toMap(), mark)
    }
    return GroupingOutcome(state, suggestions.toList(), report)
  }
}
