package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingEngine
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingOutcome
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingReport
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * L'ESECUTORE DI RIFERIMENTO DEL RAGGRUPPAMENTO (docs/fase8-progetto.md §5.2 `ReferenceGrouping`, §2.5, D20-D23): le regole del contratto
 * [GroupingEngine] scritte a forza bruta, senza indici ne' scorciatoie. Il raggruppamento vero di P5 deve dare gli stessi gruppi su 500
 * storie sintetiche, salvo i casi a +-1e-4 dalle soglie, e gli stessi id dove il riferimento li conserva (§5.2). In src/main/testing per i
 * test di P5, P9, P11, P12; mai nella release.
 *
 * L'ordine dei passi (fissato qui, P5 lo segue):
 * - [incremental]: volti non piu' correnti, correzioni, assegnazione dei volti non assegnati, nascite se i non assegnati CORE sono almeno
 *   `birthsEvery` in piu' dell'ultima volta;
 * - [provisional]: volti non piu' correnti, correzioni, assegnazione (mai nascite ne' unioni; lo stato non avanza di numero);
 * - [nightly]: contenuti spariti, volti non piu' correnti, correzioni, espulsioni sotto `tEject`, esemplari, unioni e proposte, esemplari,
 *   riassegnazione, nascite, esemplari;
 * - [recluster]: nascite da capo su tutti i volti CORE, abbinamento ottimo dei gruppi nuovi alle persone vecchie per volti in comune (almeno
 *   il 30%), correzioni, esemplari, assegnazione del resto, esemplari.
 * Ogni spareggio per riferimento o per id; ogni insieme si scorre in ordine. Lo stato nuovo ha `seq` + 1 e `base` = `seq` di partenza se e'
 * cambiato qualcosa, altrimenti e' quello di partenza.
 *
 * P5 ha aggiunto sei regole, qui e nel nucleo vero (`grouping/FastGrouping`), segnate F1-F6 in `GroupingSteps` e spiegate in
 * `docs/decisioni.md` ("Fase 8 P5"): conferma senza correzione che torna libera, una faccia per foto dopo gli alias, unioni bloccate dai
 * "Non e' …", abbinamento del ricalcolo per componenti, "Separati" attraverso gli alias, "Dimentica" rispettata anche nel ricalcolo.
 */
class ReferenceGrouping : GroupingEngine {

  override fun incremental(input: GroupingInput, ids: PersonIdSource): GroupingOutcome = GroupingWork(input, ids).run {
    dropStale()
    applyCorrections()
    assignUnassigned()
    if (unassignedCore() - input.state.unassignedAtBirths >= input.params.birthsEvery) births()
    finish(publish = true)
  }

  override fun provisional(input: GroupingInput): GroupingOutcome = GroupingWork(input, NO_IDS).run {
    dropStale()
    applyCorrections()
    assignUnassigned()
    finish(publish = false)
  }

  override fun nightly(input: GroupingInput, ids: PersonIdSource): GroupingOutcome = GroupingWork(input, ids).run {
    removeGone()
    dropStale()
    applyCorrections()
    eject()
    refreshExemplars()
    merges()
    refreshExemplars()
    assignUnassigned()
    births()
    refreshExemplars()
    finish(publish = true)
  }

  override fun recluster(input: GroupingInput, previous: GroupingState, ids: PersonIdSource): GroupingOutcome =
    GroupingWork(input.copy(state = previous), ids).run {
      rebuildFromScratch()
      applyCorrections()
      refreshExemplars()
      assignUnassigned()
      refreshExemplars()
      finish(publish = true)
    }

  private companion object {
    /** Il provvisorio non fa nascere persone: un id chiesto qui e' un errore del riferimento. */
    val NO_IDS = PersonIdSource { error("Il raggruppamento provvisorio non crea persone.") }
  }
}

/** Lo stato mutabile di un passo del riferimento. */
internal class GroupingWork(private val input: GroupingInput, private val ids: PersonIdSource) {
  private val params: GroupingParams = input.params
  private val corrections = input.corrections
  val faces: Map<FaceRef, GroupFace> =
    input.faces.filter { it.ref.contentKey !in input.gone }.associateBy { it.ref }
  private val sortedFaces: List<GroupFace> = faces.values.sortedBy { it.ref }
  private val newSeq = input.state.seq + 1

  val persons = LinkedHashMap<PersonId, GroupPerson>().apply {
    input.state.persons.keys.sorted().forEach { put(it, input.state.persons.getValue(it)) }
  }
  val assign = HashMap(input.state.assignments)

  private val counts = Counts()

  internal class Counts {
    var born = 0
    var merged = 0
    var ejected = 0
    var removed = 0
  }

  private val steps = GroupingSteps(this, params, corrections, ids, newSeq, counts)

  fun removeGone() = steps.removeGone(input.gone)

  fun dropStale() = steps.dropStale(sortedFaces)

  fun applyCorrections() = steps.applyCorrections(sortedFaces)

  fun assignUnassigned() = steps.assignUnassigned(sortedFaces)

  fun births() = steps.births(sortedFaces)

  fun eject() = steps.eject()

  fun refreshExemplars() = steps.refreshExemplars()

  fun merges() = steps.merges()

  fun rebuildFromScratch() = steps.rebuildFromScratch(sortedFaces)

  fun unassignedCore(): Int = steps.unassignedCore(sortedFaces)

  fun finish(publish: Boolean): GroupingOutcome {
    val start = input.state
    val changed = persons != start.persons || assign != start.assignments
    val unassigned = sortedFaces.count { it.ref !in assign }
    val report = GroupingReport(
      assigned = assign.values.count { it.origin != AssignOrigin.IGNORED },
      unassigned = unassigned,
      born = counts.born,
      merged = counts.merged,
      ejected = counts.ejected,
      removed = counts.removed,
      persons = persons.values.count { it.state == PersonState.ACTIVE },
    )
    val state = if (!changed || !publish) {
      GroupingState(start.seq, start.base, persons.toMap(), assign.toMap(), if (publish) steps.birthsMark ?: start.unassignedAtBirths else start.unassignedAtBirths)
    } else {
      GroupingState(newSeq, start.seq, persons.toMap(), assign.toMap(), steps.birthsMark ?: start.unassignedAtBirths)
    }
    return GroupingOutcome(state, steps.suggestions.toList(), report)
  }

  /** Le persone attive che contano per assegnare (risolti gli alias). */
  fun activePersons(): List<GroupPerson> = persons.values.filter { it.state == PersonState.ACTIVE }.sortedBy { it.id }

  fun facesOf(person: PersonId, origins: Set<AssignOrigin> = LIVE): List<GroupFace> =
    assign.values.filter { it.person == person && it.origin in origins }.mapNotNull { faces[it.face] }.sortedBy { it.ref }

  fun put(assignment: GroupAssignment) {
    assign[assignment.face] = assignment
  }

  companion object {
    val LIVE: Set<AssignOrigin> = setOf(AssignOrigin.AUTO, AssignOrigin.USER)
  }
}
