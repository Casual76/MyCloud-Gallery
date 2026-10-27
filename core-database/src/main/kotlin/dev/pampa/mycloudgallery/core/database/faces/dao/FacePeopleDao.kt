package dev.pampa.mycloudgallery.core.database.faces.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesHeadMarkEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesMetaEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesOutboxEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesUnitDoneEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PeopleRecordEntity

// I DAO delle voci `people` e del lavoro di sincronizzazione della generazione (Fase 8, P6; `docs/modello-dati.md` §15). Mai valori nei log.

/** Le voci `people` (nomi e correzioni): una riga per chiave, l'ultima scrittura vince (la regola e' di `PeopleRecord`, in `store/`). */
@Dao
interface PeopleRecordDao {

  @Query("SELECT * FROM people_record ORDER BY subject, field")
  suspend fun all(): List<PeopleRecordEntity>

  @Query("SELECT * FROM people_record WHERE subject = :subject AND field = :field")
  suspend fun get(subject: String, field: String): PeopleRecordEntity?

  @Upsert
  suspend fun upsertAll(rows: List<PeopleRecordEntity>)

  @Query("SELECT COUNT(*) FROM people_record")
  suspend fun count(): Int
}

/** Gli anti-rollback delle teste dei dispositivi (`HeadMark`, §2.4): l'ultima testa accettata per dispositivo. */
@Dao
interface FacesHeadMarkDao {

  @Query("SELECT * FROM faces_head_mark WHERE device_id = :deviceId")
  suspend fun get(deviceId: String): FacesHeadMarkEntity?

  @Query("SELECT * FROM faces_head_mark ORDER BY device_id")
  suspend fun all(): List<FacesHeadMarkEntity>

  @Upsert
  suspend fun upsert(mark: FacesHeadMarkEntity)

  @Query("DELETE FROM faces_head_mark WHERE device_id = :deviceId")
  suspend fun remove(deviceId: String)
}

/** L'outbox della pubblicazione: una voce per ([FacesOutboxEntity.kind], [FacesOutboxEntity.ref]); rimettere in coda dà un id nuovo. */
@Dao
interface FacesOutboxDao {

  /** Mette in coda; una voce uguale gia' presente viene sostituita (id nuovo: chi aveva letto quella vecchia non la toglie). */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun stage(entry: FacesOutboxEntity): Long

  @Query("SELECT * FROM faces_outbox ORDER BY entry_id LIMIT :limit")
  suspend fun pending(limit: Int): List<FacesOutboxEntity>

  @Query("SELECT COUNT(*) FROM faces_outbox")
  suspend fun count(): Int

  /** Toglie le voci lette (al piu' [FaceBodyDao.IN_LIMIT] id per volta). */
  @Query("DELETE FROM faces_outbox WHERE entry_id IN (:ids)")
  suspend fun remove(ids: List<Long>)

  @Query("DELETE FROM faces_outbox")
  suspend fun clear()
}

/** Le unita' di lavoro finite. */
@Dao
interface FacesUnitDao {

  @Upsert
  suspend fun upsert(unit: FacesUnitDoneEntity)

  @Query("SELECT * FROM faces_unit_done WHERE area = :area AND month = :month AND part = :part")
  suspend fun get(area: String, month: String, part: Int): FacesUnitDoneEntity?

  /** Le unita' fatte con la ricetta [recipe] o piu' nuova, in ordine. */
  @Query("SELECT * FROM faces_unit_done WHERE recipe >= :recipe ORDER BY area, month, part")
  suspend fun doneWith(recipe: Int): List<FacesUnitDoneEntity>

  @Query("DELETE FROM faces_unit_done WHERE recipe < :recipe")
  suspend fun removeOlderThan(recipe: Int): Int
}

/** I valori di servizio del database (`faces_meta`). */
@Dao
interface FacesMetaDao {

  @Query("SELECT data FROM faces_meta WHERE name = :name")
  suspend fun get(name: String): ByteArray?

  @Upsert
  suspend fun put(row: FacesMetaEntity)
}
