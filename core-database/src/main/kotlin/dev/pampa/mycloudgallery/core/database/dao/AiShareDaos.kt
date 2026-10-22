package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.entity.AiOwnHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.AiOwnLiveEntity
import dev.pampa.mycloudgallery.core.database.entity.AiOwnSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.AiPendingPublishEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaAppliedEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaDirtyEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaSegmentEntity

// I DAO delle tabelle `ai_*` dell'indice condiviso (schema v5, D7, D8, D37): le primitive per gli adattatori Room di `AiPublishStore` e
// `AiReplicaStore` di core-index (P11), come `IndexPublishDao` e `ReplicaDao` lo sono per la Fase 4. **Una transazione per metodo
// della porta** (`GalleryDatabase.transaction`); le liste di chiavi al massimo `AiIndexDao.MAX_KEYS` per chiamata. `share` e' gia'
// una chiave (minuscolo); `cls` e' il codice di `AiClass` (7 `vec`, 8 `ocr`).

/** Una coppia (classe, chiave di contenuto). */
data class AiKeyRow(
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
)

/** Una voce in fila senza il corpo (decidere senza caricare i byte: doppioni, rilascio, conteggi). */
data class AiPendingRefRow(
  @ColumnInfo(name = "id") val id: Long,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "digest") val digest: String,
)

/** Un candidato della fusione senza il corpo. */
data class AiCandidateRow(
  @ColumnInfo(name = "device") val device: String,
  @ColumnInfo(name = "segment") val segment: String,
  @ColumnInfo(name = "cls") val cls: Int,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "digest") val digest: String,
)

/** Le voci in fila di una share, in uno stato (`outbox` o `held`), e la testa `ai` propria: la pubblicazione di questo dispositivo. */
@Dao
interface AiPublishDao {

  // --- Outbox e voci in attesa (`ai_pending_publish`) ----------------------------------------------------------------

  /** Gli id nuovi, crescenti nell'ordine delle righe (`AUTOINCREMENT`: mai riusati). */
  @Insert
  suspend fun stage(rows: List<AiPendingPublishEntity>): List<Long>

  /** Le righe piu' vecchie di [share] in [state], con i corpi, in ordine di id (l'indice `(share, state, id)`). */
  @Query("SELECT * FROM ai_pending_publish WHERE share = :share AND state = :state ORDER BY id LIMIT :limit")
  suspend fun rows(share: String, state: String, limit: Int): List<AiPendingPublishEntity>

  @Query("SELECT id AS id, cls AS cls, content_key AS content_key, digest AS digest FROM ai_pending_publish WHERE share = :share AND state = :state ORDER BY id")
  suspend fun refs(share: String, state: String): List<AiPendingRefRow>

  @Query("UPDATE ai_pending_publish SET state = :to WHERE share = :share AND id IN (:ids)")
  suspend fun move(share: String, ids: List<Long>, to: String)

  @Query("DELETE FROM ai_pending_publish WHERE share = :share AND id IN (:ids)")
  suspend fun discard(share: String, ids: List<Long>)

  @Query("SELECT COUNT(*) FROM ai_pending_publish WHERE state = :state")
  suspend fun count(state: String): Int

  /** Le share con righe, in qualunque stato. */
  @Query("SELECT DISTINCT share FROM ai_pending_publish ORDER BY share")
  suspend fun shares(): List<String>

  // --- Testa propria --------------------------------------------------------------------------------------------------

  @Query("SELECT * FROM ai_own_head WHERE share = :share")
  suspend fun ownHead(share: String): AiOwnHeadEntity?

  @Upsert
  suspend fun saveOwnHead(head: AiOwnHeadEntity)

  /** I segmenti elencati dalla testa propria, in ordine di impronta (come nella testa). */
  @Query("SELECT * FROM ai_own_segment WHERE share = :share ORDER BY hash")
  suspend fun ownSegments(share: String): List<AiOwnSegmentEntity>

  @Upsert
  suspend fun saveOwnSegments(rows: List<AiOwnSegmentEntity>)

  /** Una testa nuova elenca i segmenti [keep] (i nuovi e i non compattati): gli altri escono. */
  @Query("DELETE FROM ai_own_segment WHERE share = :share AND hash NOT IN (:keep)")
  suspend fun deleteOwnSegmentsNotIn(share: String, keep: List<String>)

  @Query("SELECT * FROM ai_own_live WHERE share = :share AND cls = :cls AND content_key IN (:keys)")
  suspend fun ownLive(share: String, cls: Int, keys: List<String>): List<AiOwnLiveEntity>

  @Upsert
  suspend fun saveOwnLive(rows: List<AiOwnLiveEntity>)

  /** Revisione A (R14): tutte le voci vive di una share (la compattazione delle teste proprie). */
  @Query("SELECT * FROM ai_own_live WHERE share = :share")
  suspend fun ownLiveAll(share: String): List<AiOwnLiveEntity>

  /** Revisione A (R14): le voci vive tolte da una compattazione. */
  @Query("DELETE FROM ai_own_live WHERE share = :share AND cls = :cls AND content_key IN (:keys)")
  suspend fun deleteOwnLive(share: String, cls: Int, keys: List<String>)

  /** Le voci vive di una share che stanno in un segmento che la testa non elenca piu' (compattazione). */
  @Query("SELECT * FROM ai_own_live WHERE share = :share AND segment = :segment")
  suspend fun ownLiveInSegment(share: String, segment: String): List<AiOwnLiveEntity>
}

/** La replica dei dati AI degli altri dispositivi (e delle proprie teste rilette), con lo stato della consegna all'indice locale. */
@Dao
interface AiReplicaDao {

  // --- Teste e segmenti -----------------------------------------------------------------------------------------------

  @Query("SELECT * FROM ai_replica_head WHERE share = :share ORDER BY device")
  suspend fun heads(share: String): List<AiReplicaHeadEntity>

  @Upsert
  suspend fun saveHead(head: AiReplicaHeadEntity)

  @Query("DELETE FROM ai_replica_head WHERE share = :share AND device = :device")
  suspend fun deleteHead(share: String, device: String)

  @Query("DELETE FROM ai_replica_head WHERE share = :share")
  suspend fun deleteHeads(share: String)

  /** In ordine di impronta, come la testa che li elenca. */
  @Query("SELECT * FROM ai_replica_segment WHERE share = :share AND device = :device ORDER BY hash")
  suspend fun segments(share: String, device: String): List<AiReplicaSegmentEntity>

  @Upsert
  suspend fun saveSegments(rows: List<AiReplicaSegmentEntity>)

  @Query("DELETE FROM ai_replica_segment WHERE share = :share AND device = :device AND hash = :hash")
  suspend fun deleteSegment(share: String, device: String, hash: String)

  @Query("DELETE FROM ai_replica_segment WHERE share = :share AND device = :device")
  suspend fun deleteSegments(share: String, device: String)

  @Query("DELETE FROM ai_replica_segment WHERE share = :share")
  suspend fun deleteSegmentsOfShare(share: String)

  // --- Voci (i candidati della fusione) -------------------------------------------------------------------------------

  /** Un segmento riscaricato sostituisce le voci di prima (stessa chiave primaria): non le raddoppia. */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun putEntries(rows: List<AiReplicaEntryEntity>)

  /** Le chiavi toccate da un segmento (per segnarle da rifondere prima di toglierlo). */
  @Query("SELECT cls AS cls, content_key AS content_key FROM ai_replica_entry WHERE share = :share AND device = :device AND segment = :segment")
  suspend fun keysOfSegment(share: String, device: String, segment: String): List<AiKeyRow>

  @Query("SELECT cls AS cls, content_key AS content_key FROM ai_replica_entry WHERE share = :share AND device = :device")
  suspend fun keysOfDevice(share: String, device: String): List<AiKeyRow>

  @Query("DELETE FROM ai_replica_entry WHERE share = :share AND device = :device AND segment = :segment")
  suspend fun deleteEntriesOfSegment(share: String, device: String, segment: String)

  @Query("DELETE FROM ai_replica_entry WHERE share = :share AND device = :device")
  suspend fun deleteEntriesOfDevice(share: String, device: String)

  @Query("DELETE FROM ai_replica_entry WHERE share = :share")
  suspend fun deleteEntriesOfShare(share: String)

  /** I candidati di [keys] della classe [cls], di ogni dispositivo e segmento. */
  @Query(
    "SELECT device AS device, segment AS segment, cls AS cls, content_key AS content_key, recipe AS recipe, digest AS digest " +
      "FROM ai_replica_entry WHERE share = :share AND cls = :cls AND content_key IN (:keys)",
  )
  suspend fun candidates(share: String, cls: Int, keys: List<String>): List<AiCandidateRow>

  /** Il corpo di un candidato, o null se non c'e' piu'. */
  @Query(
    "SELECT body FROM ai_replica_entry WHERE share = :share AND device = :device AND segment = :segment AND cls = :cls " +
      "AND content_key = :key",
  )
  suspend fun body(share: String, device: String, segment: String, cls: Int, key: String): ByteArray?

  /** Tutte le (classe, chiave) con almeno un candidato in [share]. */
  @Query("SELECT DISTINCT cls AS cls, content_key AS content_key FROM ai_replica_entry WHERE share = :share")
  suspend fun keys(share: String): List<AiKeyRow>

  // --- Consegna all'indice locale e chiavi da rifondere ---------------------------------------------------------------

  @Query("SELECT * FROM ai_replica_applied WHERE share = :share AND cls = :cls AND content_key IN (:keys)")
  suspend fun applied(share: String, cls: Int, keys: List<String>): List<AiReplicaAppliedEntity>

  @Query("SELECT * FROM ai_replica_applied WHERE share = :share ORDER BY cls, content_key")
  suspend fun allApplied(share: String): List<AiReplicaAppliedEntity>

  @Upsert
  suspend fun saveApplied(rows: List<AiReplicaAppliedEntity>)

  @Query("DELETE FROM ai_replica_applied WHERE share = :share AND cls = :cls AND content_key IN (:keys)")
  suspend fun deleteApplied(share: String, cls: Int, keys: List<String>)

  @Query("DELETE FROM ai_replica_applied WHERE share = :share")
  suspend fun deleteAppliedOfShare(share: String)

  @Query("SELECT cls AS cls, content_key AS content_key FROM ai_replica_dirty WHERE share = :share ORDER BY cls, content_key")
  suspend fun dirty(share: String): List<AiKeyRow>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun markDirty(rows: List<AiReplicaDirtyEntity>)

  @Query("DELETE FROM ai_replica_dirty WHERE share = :share AND cls = :cls AND content_key IN (:keys)")
  suspend fun clearDirty(share: String, cls: Int, keys: List<String>)

  @Query("DELETE FROM ai_replica_dirty WHERE share = :share")
  suspend fun deleteDirtyOfShare(share: String)

  /** Le share con dati (una testa, una consegna o una chiave da rifondere). */
  @Query(
    "SELECT share FROM ai_replica_head UNION SELECT share FROM ai_replica_applied UNION SELECT share FROM ai_replica_dirty " +
      "UNION SELECT share FROM ai_replica_segment ORDER BY share",
  )
  suspend fun shares(): List<String>
}
