package dev.pampa.mycloudgallery.core.database.faces.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupAssignmentEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupExemplarEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupPersonEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.GroupStateEntity

// Il DAO dell'istantanea del raggruppamento (Fase 8, P6; `docs/modello-dati.md` §15). L'istantanea si legge e si scrive **intera**
// (`GroupingState` e' immutabile e il custode la rifa' a ogni unita'): una lettura in tre scorrimenti, una scrittura che sostituisce tutto in
// una transazione. Mai valori nei log (D37).

@Dao
abstract class FaceGroupingDao {

  @Query("SELECT * FROM group_state WHERE id = 1")
  abstract suspend fun state(): GroupStateEntity?

  @Query("SELECT * FROM group_person ORDER BY person_id")
  abstract suspend fun persons(): List<GroupPersonEntity>

  @Query("SELECT * FROM group_exemplar ORDER BY person_id, position")
  abstract suspend fun exemplars(): List<GroupExemplarEntity>

  @Query("SELECT * FROM group_assignment ORDER BY content_key, frame, face_index, body_digest")
  abstract suspend fun assignments(): List<GroupAssignmentEntity>

  @Query("SELECT COUNT(*) FROM group_assignment WHERE person_id = :personId")
  abstract suspend fun assignmentCount(personId: String): Int

  @Query("DELETE FROM group_assignment")
  abstract suspend fun clearAssignments()

  @Query("DELETE FROM group_exemplar")
  abstract suspend fun clearExemplars()

  @Query("DELETE FROM group_person")
  abstract suspend fun clearPersons()

  @Query("DELETE FROM group_state")
  abstract suspend fun clearState()

  @Insert
  abstract suspend fun insertState(state: GroupStateEntity)

  @Insert
  abstract suspend fun insertPersons(persons: List<GroupPersonEntity>)

  @Insert
  abstract suspend fun insertExemplars(exemplars: List<GroupExemplarEntity>)

  @Insert
  abstract suspend fun insertAssignments(assignments: List<GroupAssignmentEntity>)

  /**
   * Sostituisce l'istantanea con questa, **in una transazione**: chi legge vede la vecchia intera o la nuova intera, mai una a meta'.
   * Si toglie dalle figlie alla madre e si scrive dalla madre alle figlie (le chiavi esterne non chiedono altro).
   */
  @Transaction
  open suspend fun replaceAll(
    state: GroupStateEntity,
    persons: List<GroupPersonEntity>,
    exemplars: List<GroupExemplarEntity>,
    assignments: List<GroupAssignmentEntity>,
  ) {
    clearAssignments()
    clearExemplars()
    clearPersons()
    clearState()
    insertState(state)
    if (persons.isNotEmpty()) insertPersons(persons)
    if (exemplars.isNotEmpty()) insertExemplars(exemplars)
    if (assignments.isNotEmpty()) insertAssignments(assignments)
  }
}
