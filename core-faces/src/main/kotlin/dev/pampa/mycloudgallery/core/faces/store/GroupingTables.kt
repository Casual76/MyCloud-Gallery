package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.database.faces.dao.FaceGroupingDao
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupAssignmentEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupExemplarEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupPersonEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupStateEntity
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull

// L'ISTANTANEA DEL RAGGRUPPAMENTO NEL DATABASE (docs/fase8-progetto.md §2.5, D20-D23). `GroupingState` e' immutabile e il custode la rifa'
// a ogni unita': si scrive **intera** in una transazione (o c'e' la vecchia o la nuova) e si legge intera. Persone, esemplari e assegnazioni
// sono riferimenti e numeri, mai vettori ne' nomi (i nomi sono voci `people`, sigillate). Una riga che questa versione non capisce (un codice
// sconosciuto scritto da un'app piu' nuova, un valore che i contratti rifiutano) si salta; le assegnazioni delle persone saltate con lei.

internal class GroupingTables(private val dao: FaceGroupingDao) {

  suspend fun load(): GroupingState {
    val header = dao.state() ?: return GroupingState.EMPTY
    val persons = LinkedHashMap<PersonId, GroupPerson>()
    val exemplarsOf = dao.exemplars().groupBy { it.personId }
    for (row in dao.persons()) {
      val person = toPerson(row, exemplarsOf[row.personId].orEmpty()) ?: continue
      persons[person.id] = person
    }
    val assignments = LinkedHashMap<FaceRef, GroupAssignment>()
    for (row in dao.assignments()) {
      val assignment = toAssignment(row) ?: continue
      if (assignment.person in persons) assignments[assignment.face] = assignment
    }
    return try {
      GroupingState(header.seq, header.base, persons, assignments, header.unassignedAtBirths)
    } catch (_: IllegalArgumentException) {
      GroupingState.EMPTY
    }
  }

  suspend fun save(state: GroupingState) {
    val persons = state.persons.values.sortedBy { it.id }
    dao.replaceAll(
      state = GroupStateEntity(id = 1, seq = state.seq, base = state.base, unassignedAtBirths = state.unassignedAtBirths),
      persons = persons.map { GroupPersonEntity(it.id.value, it.state.code, it.aliasOf?.value, it.bornSeq, it.changedSeq) },
      exemplars = persons.flatMap { person ->
        person.exemplars.mapIndexed { position, ref -> GroupExemplarEntity(person.id.value, position, ref.contentKey.hex, ref.frame, ref.index, ref.bodyDigest) }
      },
      assignments = state.assignments.values.sortedBy { it.face }.map(::toRow),
    )
  }

  private fun toRow(a: GroupAssignment): GroupAssignmentEntity {
    val face = a.face
    val box = a.locator.box
    return GroupAssignmentEntity(
      contentKey = face.contentKey.hex,
      frame = face.frame,
      faceIndex = face.index,
      bodyDigest = face.bodyDigest,
      // Il posto e' quasi sempre dello stesso contenuto e fotogramma del riferimento: si scrive solo se diverge (resta esatto).
      locKey = a.locator.contentKey.hex.takeIf { it != face.contentKey.hex },
      locFrame = a.locator.frame.takeIf { it != face.frame },
      x0 = box.x0,
      y0 = box.y0,
      x1 = box.x1,
      y1 = box.y1,
      personId = a.person.value,
      score = a.score.toDouble(),
      origin = a.origin.code,
    )
  }

  private fun toAssignment(row: GroupAssignmentEntity): GroupAssignment? = try {
    val origin = enumByCodeOrNull<AssignOrigin>(row.origin) ?: return null
    val face = FaceRef(ContentKey(row.contentKey), row.frame, row.faceIndex, row.bodyDigest)
    val locator = FaceLocator(ContentKey(row.locKey ?: row.contentKey), row.locFrame ?: row.frame, FaceBox(row.x0, row.y0, row.x1, row.y1))
    GroupAssignment(face, locator, PersonId(row.personId), row.score.toFloat(), origin)
  } catch (_: IllegalArgumentException) {
    null
  }

  private fun toPerson(row: GroupPersonEntity, exemplars: List<GroupExemplarEntity>): GroupPerson? = try {
    val state = enumByCodeOrNull<PersonState>(row.state) ?: return null
    GroupPerson(
      id = PersonId(row.personId),
      state = state,
      aliasOf = row.aliasOf?.let(::PersonId),
      bornSeq = row.bornSeq,
      changedSeq = row.changedSeq,
      exemplars = exemplars.sortedBy { it.position }.map { FaceRef(ContentKey(it.contentKey), it.frame, it.faceIndex, it.bodyDigest) },
    )
  } catch (_: IllegalArgumentException) {
    null
  }
}
