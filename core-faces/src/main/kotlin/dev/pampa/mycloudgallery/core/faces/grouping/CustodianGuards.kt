package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshot
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.TrustLimits

// LE GUARDIE DEL CUSTODE (correzioni della Fase 8, pacchetto "formato e replica dei volti": R3, R4). Tre passi puri e deterministici attorno al
// passo del nucleo, fuori dai nuclei (il nucleo vero e il riferimento restano uguali, `ReferenceParityTest`):
// - [BoundedCorrections]: le voci `people` non creano persone dal nulla (R3);
// - [Unforget]: una persona dimenticata la cui voce `forget` e' sparita (revocata con la sua provenienza) torna com'era (R3);
// - [Renumber]: un'istantanea con la `seq` oltre il tetto del formato (adottata da un dispositivo poi revocato) riparte da una `seq` ammessa (R4).

/**
 * R3: le correzioni che il passo vede creano persone **solo** per id che un'istantanea (locale o letta) nomina o che un volto di adesso nomina
 * (un "E' …" su un volto che c'e': "Dividi" fa nascere cosi' la sua persona). Una voce `born`, `alias` o `forget` di un id che nessuno nomina, o un
 * "E' …" di un volto che qui non c'e', aspetta (le correzioni si rileggono dalle voci a ogni passo: niente si perde). Cosi' migliaia di voci
 * firmate verso id casuali non gonfiano le persone fino al tetto dell'istantanea, che fermerebbe il custode.
 */
internal object BoundedCorrections {
  fun within(corrections: GroupingCorrections, known: Set<PersonId>, faces: List<GroupFace>): GroupingCorrections {
    val index = CorrectionIndex(corrections)
    val byFace = HashSet<PersonId>()
    for (face in faces) index.fixedFor(face.locator)?.let { byFace += it }
    val allowed = known + byFace
    return corrections.copy(
      fixed = corrections.fixed.filterValues { it in allowed },
      forgotten = corrections.forgotten.filterTo(LinkedHashSet()) { it in allowed },
      aliases = corrections.aliases.filter { (from, into) -> from in allowed && into in allowed },
      born = corrections.born.filterTo(LinkedHashSet()) { it in allowed },
    )
  }
}

/**
 * R3: una persona **dimenticata** nell'istantanea la cui voce `forget` c'era al passo prima e adesso non c'e' piu' torna attiva, con i suoi volti
 * (le assegnazioni ignorate tornano automatiche) e gli esemplari scelti fra loro. Una voce `forget` non ha lapide ("Dimentica" non si annulla
 * dall'app): sparisce solo con la revoca del suo autore o di chi l'ha portata. Chi non aveva mai visto la voce non fa niente (fallisce chiuso:
 * la persona resta dimenticata).
 */
internal object Unforget {
  fun restore(state: GroupingState, ids: Set<PersonId>, faces: List<GroupFace>, params: GroupingParams): GroupingState {
    val back = ids.filter { state.persons[it]?.state == PersonState.FORGOTTEN }.sorted()
    if (back.isEmpty()) return state
    val current = faces.associateBy { it.ref }
    val persons = LinkedHashMap(state.persons)
    val assign = HashMap(state.assignments)
    for (id in back) {
      val own = state.assignments.values.filter { it.person == id && it.origin == AssignOrigin.IGNORED && it.face in current }.sortedBy { it.face }
      val rows = own.map { current.getValue(it.face) }
      val table = FaceTable(rows)
      val chosen = if (rows.isEmpty()) IntArray(0) else Exemplars.choose(table.matrix, IntArray(rows.size) { it }, table.core, params.maxExemplars)
      for ((i, a) in own.withIndex()) {
        val score = Exemplars.similarity(table.matrix, i, chosen) ?: 0f
        assign[a.face] = GroupAssignment(a.face, a.locator, id, score, AssignOrigin.AUTO)
      }
      val person = state.persons.getValue(id)
      persons[id] = GroupPerson(id, PersonState.ACTIVE, null, person.bornSeq, person.changedSeq, chosen.map { rows[it].ref })
    }
    return GroupingState(state.seq, state.base, persons, assign, state.unassignedAtBirths)
  }
}

/**
 * R4: l'istantanea [state] del custode con una `seq` oltre [TrustLimits.MAX_SNAPSHOT_SEQ] (adottata da un dispositivo che l'aveva pubblicata
 * vicino al tetto, o salvata prima della correzione) non si potrebbe pubblicare ne' superare mai: riparte dalla `seq` piu' alta ammessa fra le
 * istantanee lette ([remote]) + 1. Finche' il dispositivo ostile non e' revocato la sua istantanea resta la vincente per gli altri; dopo la
 * revoca, quella del custode torna a valere. Le altre istantanee restano com'erano.
 */
internal object Renumber {
  fun within(state: GroupingState, remote: List<ClusterSnapshot>): GroupingState {
    if (state.seq <= TrustLimits.MAX_SNAPSHOT_SEQ) return state
    val base = remote.map { it.state.seq }.filter { it < TrustLimits.MAX_SNAPSHOT_SEQ }.maxOrNull() ?: 0L
    return GroupingState(base + 1, base, state.persons.mapValues { (_, p) -> p.copy(changedSeq = minOf(p.changedSeq, base + 1), bornSeq = minOf(p.bornSeq, base + 1)) }, state.assignments, state.unassignedAtBirths)
  }
}
