package dev.pampa.mycloudgallery.core.database.faces.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Database dei volti, schema v1: l'istantanea del raggruppamento (§2.5, D20-D23). E' lo stato che il custode scrive e che gli altri
// dispositivi leggono dalla replica; qui sta la copia locale, intera. Le tabelle riflettono `GroupingState` di core-faces: persone,
// esemplari e assegnazioni. Niente di cio' che qui sta e' un vettore o un nome (i centroidi, quando P5 ne avra' bisogno, sono una colonna
// sigillata di `group_person`: richiede la v2). Regole comuni: vedi `FaceBodyEntities.kt`.

/**
 * I numeri dell'istantanea: una sola riga (`id` = 1). [seq] e [base] (la `seq` da cui e' partita, D21) e quanti volti CORE erano non
 * assegnati all'ultima nascita ([unassignedAtBirths], la regola "50 in piu'").
 */
@Entity(tableName = "group_state")
class GroupStateEntity(
  @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
  @ColumnInfo(name = "seq") val seq: Long,
  @ColumnInfo(name = "base") val base: Long,
  @ColumnInfo(name = "unassigned_at_births") val unassignedAtBirths: Int,
) {
  override fun toString(): String = "GroupStateEntity"
}

/**
 * Una persona dell'istantanea (§2.4 "Persona"): [personId] nella forma canonica (UUID minuscolo), [state] = `active`, `merged` o
 * `forgotten`, [aliasOf] solo per `merged`. Nessun nome qui: i nomi sono voci `people` (sigillate).
 */
@Entity(tableName = "group_person", primaryKeys = ["person_id"])
class GroupPersonEntity(
  @ColumnInfo(name = "person_id") val personId: String,
  @ColumnInfo(name = "state") val state: String,
  @ColumnInfo(name = "alias_of") val aliasOf: String?,
  @ColumnInfo(name = "born_seq") val bornSeq: Long,
  @ColumnInfo(name = "changed_seq") val changedSeq: Long,
) {
  override fun toString(): String = "GroupPersonEntity($state)"
}

/** Gli esemplari di una persona (al piu' 16), in ordine di [position]: riferimenti a volti, non vettori. */
@Entity(
  tableName = "group_exemplar",
  primaryKeys = ["person_id", "position"],
  foreignKeys = [
    ForeignKey(
      entity = GroupPersonEntity::class,
      parentColumns = ["person_id"],
      childColumns = ["person_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
class GroupExemplarEntity(
  @ColumnInfo(name = "person_id") val personId: String,
  @ColumnInfo(name = "position") val position: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "frame") val frame: Int,
  @ColumnInfo(name = "face_index") val faceIndex: Int,
  @ColumnInfo(name = "body_digest") val bodyDigest: Long,
) {
  override fun toString(): String = "GroupExemplarEntity"
}

/**
 * Un'assegnazione: il volto per **riferimento** (chiave, fotogramma, indice, impronta del corpo: la chiave primaria), il suo posto
 * (riquadro; [locKey] e [locFrame] solo se il posto e' di un altro contenuto o fotogramma del riferimento: null = lo stesso, il caso di
 * sempre), la persona, il punteggio (coseno) e l'origine (`auto`, `user`, `ignored`). In cascata sulla persona.
 */
@Entity(
  tableName = "group_assignment",
  primaryKeys = ["content_key", "frame", "face_index", "body_digest"],
  foreignKeys = [
    ForeignKey(
      entity = GroupPersonEntity::class,
      parentColumns = ["person_id"],
      childColumns = ["person_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["person_id"])],
)
class GroupAssignmentEntity(
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "frame") val frame: Int,
  @ColumnInfo(name = "face_index") val faceIndex: Int,
  @ColumnInfo(name = "body_digest") val bodyDigest: Long,
  @ColumnInfo(name = "loc_key") val locKey: String?,
  @ColumnInfo(name = "loc_frame") val locFrame: Int?,
  @ColumnInfo(name = "x0") val x0: Int,
  @ColumnInfo(name = "y0") val y0: Int,
  @ColumnInfo(name = "x1") val x1: Int,
  @ColumnInfo(name = "y1") val y1: Int,
  @ColumnInfo(name = "person_id") val personId: String,
  @ColumnInfo(name = "score") val score: Double,
  @ColumnInfo(name = "origin") val origin: String,
) {
  override fun toString(): String = "GroupAssignmentEntity($origin)"
}
