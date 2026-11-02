package dev.pampa.mycloudgallery.core.database.faces.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesMetaEntity
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

  /** Le voci "da datare" prendono l'ora del NAS [nasNow] (D36); quante. Idempotente: dopo non ce ne sono piu'. */
  @Query("UPDATE people_record SET at_nas = :nasNow WHERE at_nas IS NULL")
  suspend fun date(nasNow: Long): Int

  /** Toglie le voci scritte da [authors] (dispositivi revocati o persi, L99); quante. [authors] al piu' 500. */
  @Query("DELETE FROM people_record WHERE author IN (:authors)")
  suspend fun deleteByAuthors(authors: List<String>): Int

  /** R3: toglie le voci scritte da [devices] **o arrivate da loro** (la provenienza, `via`); quante. [devices] al piu' 500. */
  @Query("DELETE FROM people_record WHERE author IN (:devices) OR via IN (:devices)")
  suspend fun deleteByAuthorsOrVia(devices: List<String>): Int

  /** R3: quante voci sono arrivate da [via] (la provenienza; una riga di prima della v2 vale come arrivata dal suo autore). */
  @Query("SELECT COUNT(*) FROM people_record WHERE COALESCE(via, author) = :via")
  suspend fun countVia(via: String): Int

  /** R3: le voci arrivate dal loro autore (scelte fatte qui, o lette dalla testa di chi le ha scritte): le sole che si ripubblicano. */
  @Query("SELECT * FROM people_record WHERE via IS NULL OR via = author ORDER BY subject, field")
  suspend fun direct(): List<PeopleRecordEntity>
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
