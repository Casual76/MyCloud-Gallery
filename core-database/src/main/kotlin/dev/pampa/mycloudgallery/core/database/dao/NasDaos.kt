package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.entity.IndexHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOutboxBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOutboxRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexPublishStateEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.NasOpEntity
import dev.pampa.mycloudgallery.core.database.entity.NasOpItemEntity
import dev.pampa.mycloudgallery.core.database.entity.NasAdoptedFingerprintEntity
import dev.pampa.mycloudgallery.core.database.entity.NasScanStateEntity
import dev.pampa.mycloudgallery.core.database.entity.NasTrashEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaDirtyEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaPackEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaProvisionalEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaSettingEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaShareAccessEntity
import kotlinx.coroutines.flow.Flow

// I DAO dello schema v3 (Fase 4). Semplici di proposito: righe dentro e fuori, nessuna regola. Le regole stanno nei
// pacchetti che li usano (P3 pubblicazione, P4 replica, P12 operazioni) e gli adattatori sopra questi DAO li scrive P11.
// Chi scrive in piu' tabelle insieme (un segmento applicato + il suo HeadMark, un passo del diario + il suo stato) lo fa
// dentro `GalleryDatabase.withTransaction { ... }`; le scritture del grafo dei media passano **sempre** da `MediaCatalog`.
//
// Ogni query che scorre tante righe ha il testo in una costante pubblica: `V3QueryPlanTest` ne controlla il piano
// (`EXPLAIN QUERY PLAN`: indice usato, nessun `USE TEMP B-TREE`).

/** Il cursore della scansione del NAS (`nas_scan_state`). Le scritture con osservazioni passano da `MediaCatalog`. */
@Dao
interface NasScanDao {

  @Query("SELECT * FROM nas_scan_state WHERE account_id = :account AND share_key = :shareKey")
  suspend fun get(account: String, shareKey: String): NasScanStateEntity?

  @Query("SELECT * FROM nas_scan_state ORDER BY account_id, share_key")
  suspend fun all(): List<NasScanStateEntity>

  /** Le share congelate (D10), per la Libreria, i lavori e la Diagnostica. */
  @Query("SELECT * FROM nas_scan_state WHERE freeze_reason IS NOT NULL ORDER BY account_id, share_key")
  fun observeFrozen(): Flow<List<NasScanStateEntity>>

  @Upsert
  suspend fun upsert(state: NasScanStateEntity)

  @Query("DELETE FROM nas_scan_state WHERE account_id = :account AND share_key = :shareKey")
  suspend fun delete(account: String, shareKey: String)

  // --- R6 (revisione 4A): impronte adottate dall'indice ---------------------------------------------------------------

  @Upsert
  suspend fun upsertAdopted(rows: List<NasAdoptedFingerprintEntity>)

  @Query("DELETE FROM nas_adopted_fingerprint WHERE location_id IN (:locationIds)")
  suspend fun deleteAdopted(locationIds: List<Long>)

  @Query("SELECT * FROM nas_adopted_fingerprint WHERE location_id IN (:locationIds)")
  suspend fun adoptedAmong(locationIds: List<Long>): List<NasAdoptedFingerprintEntity>
}

/**
 * La pubblicazione dell'indice di **questo** dispositivo (P3, §2.1): stato per share, outbox dei record e dei blob, stato
 * proprio pubblicato (segmenti, record e blob vivi). Righe dentro e fuori, nessuna regola: le regole (seq, M3, vivi per
 * `seq`) stanno nell'adattatore `RoomIndexPublishStore` di core-index, che fa una transazione per metodo della porta.
 *
 * Le liste di chiavi passate a `IN (...)` vanno spezzate da chi chiama in blocchi da [MAX_KEYS] (limite dei parametri di
 * SQLite su API 29).
 */
@Dao
interface IndexPublishDao {

  @Query("SELECT * FROM index_publish_state WHERE share_key = :shareKey")
  suspend fun publishState(shareKey: String): IndexPublishStateEntity?

  @Query("SELECT * FROM index_publish_state ORDER BY share_key")
  suspend fun publishStates(): List<IndexPublishStateEntity>

  @Upsert
  suspend fun savePublishState(state: IndexPublishStateEntity)

  // --- Outbox -------------------------------------------------------------------------------------------------------

  @Insert
  suspend fun insertRecords(records: List<IndexOutboxRecordEntity>): List<Long>

  @Insert
  suspend fun insertBlobs(blobs: List<IndexOutboxBlobEntity>): List<Long>

  @Query("UPDATE index_outbox_record SET sunk = 1 WHERE share_key = :shareKey AND id IN (:ids)")
  suspend fun markRecordsSunk(shareKey: String, ids: List<Long>)

  @Query("UPDATE index_outbox_blob SET sunk = 1 WHERE share_key = :shareKey AND id IN (:ids)")
  suspend fun markBlobsSunk(shareKey: String, ids: List<Long>)

  @Query("SELECT * FROM index_outbox_record WHERE sunk = 0 ORDER BY id")
  suspend fun unsunkRecords(): List<IndexOutboxRecordEntity>

  @Query("SELECT * FROM index_outbox_blob WHERE sunk = 0 ORDER BY id")
  suspend fun unsunkBlobs(): List<IndexOutboxBlobEntity>

  /** L'outbox di una share in ordine di `seq` (lo stesso dell'id: il `seq` si assegna in ordine d'inserimento). */
  @Query(OUTBOX_RECORDS_SQL)
  suspend fun outboxRecords(shareKey: String): List<IndexOutboxRecordEntity>

  @Query(OUTBOX_BLOBS_SQL)
  suspend fun outboxBlobs(shareKey: String): List<IndexOutboxBlobEntity>

  @Query("DELETE FROM index_outbox_record WHERE share_key = :shareKey AND id IN (:ids)")
  suspend fun deleteRecords(shareKey: String, ids: List<Long>)

  @Query("DELETE FROM index_outbox_blob WHERE share_key = :shareKey AND id IN (:ids)")
  suspend fun deleteBlobs(shareKey: String, ids: List<Long>)

  @Query("SELECT COUNT(*) FROM index_outbox_record")
  suspend fun pendingRecordCount(): Int

  @Query("SELECT COUNT(*) AS items, COALESCE(SUM(byte_length), 0) AS bytes FROM index_outbox_blob")
  suspend fun pendingBlobs(): CountBytesRow

  @Query("SELECT path_key FROM index_outbox_record WHERE share_key = :shareKey AND path_key IN (:pathKeys)")
  suspend fun pendingPathKeys(shareKey: String, pathKeys: List<String>): List<String>

  @Query("SELECT content_key FROM index_outbox_record WHERE share_key = :shareKey AND content_key IN (:keys)")
  suspend fun pendingContentKeys(shareKey: String, keys: List<String>): List<String>

  /** R2 (revisione 4A): le share con righe nell'outbox. */
  @Query("SELECT DISTINCT share_key FROM index_outbox_record")
  suspend fun outboxRecordShares(): List<String>

  @Query("SELECT DISTINCT share_key FROM index_outbox_blob")
  suspend fun outboxBlobShares(): List<String>

  @Query("SELECT handle FROM index_outbox_blob")
  suspend fun referencedHandles(): List<String>

  // --- Stato proprio pubblicato -------------------------------------------------------------------------------------

  /** I segmenti elencati dalla testa corrente, in ordine d'impronta (come nella testa). */
  @Query("SELECT * FROM index_own_segment WHERE share_key = :shareKey AND listed = 1 ORDER BY segment_hash")
  suspend fun listedSegments(shareKey: String): List<IndexOwnSegmentEntity>

  /** Il diario: tutti i segmenti propri pubblicati nella share. */
  @Query("SELECT segment_hash FROM index_own_segment WHERE share_key = :shareKey")
  suspend fun publishedSegmentHashes(shareKey: String): List<String>

  @Query("UPDATE index_own_segment SET listed = 0 WHERE share_key = :shareKey")
  suspend fun unlistSegments(shareKey: String)

  @Upsert
  suspend fun saveOwnSegments(segments: List<IndexOwnSegmentEntity>)

  @Query("SELECT * FROM index_own_record WHERE share_key = :shareKey AND path_key IN (:pathKeys)")
  suspend fun ownRecords(shareKey: String, pathKeys: List<String>): List<IndexOwnRecordEntity>

  @Query("SELECT path_key FROM index_own_record WHERE share_key = :shareKey AND path_key IN (:pathKeys)")
  suspend fun livePathKeys(shareKey: String, pathKeys: List<String>): List<String>

  @Query("SELECT content_key FROM index_own_record WHERE share_key = :shareKey AND content_key IN (:keys)")
  suspend fun liveContentKeys(shareKey: String, keys: List<String>): List<String>

  @Query("SELECT * FROM index_own_record WHERE share_key = :shareKey AND segment_hash IN (:segments)")
  suspend fun ownRecordsIn(shareKey: String, segments: List<String>): List<IndexOwnRecordEntity>

  @Query("SELECT * FROM index_own_blob WHERE share_key = :shareKey AND segment_hash IN (:segments)")
  suspend fun ownBlobsIn(shareKey: String, segments: List<String>): List<IndexOwnBlobEntity>

  @Query("SELECT segment_hash AS segment, COUNT(*) AS items FROM index_own_record WHERE share_key = :shareKey GROUP BY segment_hash")
  suspend fun ownRecordCounts(shareKey: String): List<SegmentCountRow>

  @Query("SELECT segment_hash AS segment, COUNT(*) AS items FROM index_own_blob WHERE share_key = :shareKey GROUP BY segment_hash")
  suspend fun ownBlobCounts(shareKey: String): List<SegmentCountRow>

  @Query("SELECT COUNT(*) FROM index_own_record WHERE share_key = :shareKey")
  suspend fun ownRecordCount(shareKey: String): Int

  @Upsert
  suspend fun saveOwnRecords(records: List<IndexOwnRecordEntity>)

  @Query("DELETE FROM index_own_record WHERE share_key = :shareKey AND path_key IN (:pathKeys)")
  suspend fun deleteOwnRecords(shareKey: String, pathKeys: List<String>)

  @Upsert
  suspend fun saveOwnBlobs(blobs: List<IndexOwnBlobEntity>)

  companion object {
    /** Il massimo di chiavi per `IN (...)` (SQLite su API 29 ammette 999 parametri; si lascia margine agli altri). */
    const val MAX_KEYS: Int = 900

    const val OUTBOX_RECORDS_SQL: String = "SELECT * FROM index_outbox_record WHERE share_key = :shareKey ORDER BY seq"

    const val OUTBOX_BLOBS_SQL: String = "SELECT * FROM index_outbox_blob WHERE share_key = :shareKey ORDER BY id"
  }
}

/** Un conteggio e dei byte. */
data class CountBytesRow(val items: Int, val bytes: Long)

/** Quante voci vive per segmento. */
data class SegmentCountRow(val segment: String, val items: Int)

/**
 * La replica locale dell'indice (P4, §2.2): teste accettate, segmenti, record, percorsi da rifondere, pacchi e blob,
 * accesso alle share, impostazioni e miniature provvisorie. Righe dentro e fuori: le regole (D5, trattenuti, convergenza
 * della qualita') stanno nell'adattatore `RoomReplicaStore` di core-index, una transazione per metodo della porta.
 */
@Dao
interface ReplicaDao {

  // --- Teste e segmenti ---------------------------------------------------------------------------------------------

  @Query("SELECT * FROM index_head WHERE share_key = :shareKey ORDER BY device_id")
  suspend fun heads(shareKey: String): List<IndexHeadEntity>

  @Upsert
  suspend fun saveHead(head: IndexHeadEntity)

  @Query("DELETE FROM index_head WHERE share_key = :shareKey AND device_id = :deviceId")
  suspend fun deleteHead(shareKey: String, deviceId: String)

  @Query("DELETE FROM index_head WHERE share_key = :shareKey")
  suspend fun deleteHeads(shareKey: String)

  @Query("SELECT * FROM index_segment WHERE share_key = :shareKey AND device_id = :deviceId ORDER BY position, segment_hash")
  suspend fun segments(shareKey: String, deviceId: String): List<IndexSegmentEntity>

  @Query("SELECT * FROM index_segment WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :hash")
  suspend fun segment(shareKey: String, deviceId: String, hash: String): IndexSegmentEntity?

  @Query("SELECT * FROM index_segment WHERE state IN (:states) AND cls IN (:classes) ORDER BY share_key, device_id, position")
  suspend fun segmentsWhere(states: List<String>, classes: List<Int>): List<IndexSegmentEntity>

  @Upsert
  suspend fun saveSegments(segments: List<IndexSegmentEntity>)

  @Query("UPDATE index_segment SET state = :state WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :hash")
  suspend fun setSegmentState(shareKey: String, deviceId: String, hash: String, state: String)

  @Query(
    "UPDATE index_segment SET state = :state, pack_id = :packId " +
      "WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :hash",
  )
  suspend fun setSegmentPack(shareKey: String, deviceId: String, hash: String, state: String, packId: Long?)

  @Query("DELETE FROM index_segment WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :hash")
  suspend fun deleteSegment(shareKey: String, deviceId: String, hash: String)

  @Query("DELETE FROM index_segment WHERE share_key = :shareKey AND device_id = :deviceId")
  suspend fun deleteSegments(shareKey: String, deviceId: String)

  @Query("DELETE FROM index_segment WHERE share_key = :shareKey")
  suspend fun deleteSegmentsOfShare(shareKey: String)

  // --- Record -------------------------------------------------------------------------------------------------------

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveRecords(records: List<IndexRecordEntity>)

  @Query(RECORDS_AT_SQL)
  suspend fun records(shareKey: String, pathKeys: List<String>): List<IndexRecordEntity>

  @Query(
    "SELECT * FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segment " +
      "AND record_key IN (:pathKeys)",
  )
  suspend fun recordsOf(shareKey: String, deviceId: String, segment: String, pathKeys: List<String>): List<IndexRecordEntity>

  @Query("SELECT DISTINCT record_key FROM index_record WHERE share_key = :shareKey ORDER BY record_key")
  suspend fun pathKeys(shareKey: String): List<String>

  @Query("SELECT record_key FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segment")
  suspend fun keysOfSegment(shareKey: String, deviceId: String, segment: String): List<String>

  @Query("SELECT DISTINCT record_key FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId")
  suspend fun keysOfDevice(shareKey: String, deviceId: String): List<String>

  @Query("DELETE FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segment")
  suspend fun deleteRecordsOfSegment(shareKey: String, deviceId: String, segment: String)

  @Query("DELETE FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId")
  suspend fun deleteRecordsOfDevice(shareKey: String, deviceId: String)

  @Query("DELETE FROM index_record WHERE share_key = :shareKey")
  suspend fun deleteRecordsOfShare(shareKey: String)

  @Query(
    "SELECT record_key FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segment " +
      "AND seq <= :upToSeq",
  )
  suspend fun keysUpToSeq(shareKey: String, deviceId: String, segment: String, upToSeq: Long): List<String>

  @Query(
    "DELETE FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segment " +
      "AND seq <= :upToSeq",
  )
  suspend fun deleteUpToSeq(shareKey: String, deviceId: String, segment: String, upToSeq: Long)

  /** Le chiavi di contenuto distinte con almeno una delle classi di [mask] preparate (la stima di un livello, E-L6). */
  /** R1 (revisione 4A): le chiavi di contenuto dei record presenti di un dispositivo in una share. */
  @Query("SELECT DISTINCT content_key FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId AND gone = 0 AND content_key IS NOT NULL")
  suspend fun presentContentKeys(shareKey: String, deviceId: String): List<String>

  @Query("SELECT COUNT(DISTINCT content_key) FROM index_record WHERE gone = 0 AND (prep & :mask) != 0")
  suspend fun preparedContentCount(mask: Int): Int

  // --- Percorsi da rifondere ----------------------------------------------------------------------------------------

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun markDirty(rows: List<ReplicaDirtyEntity>)

  @Query("SELECT record_key FROM replica_dirty WHERE share_key = :shareKey ORDER BY record_key LIMIT :limit")
  suspend fun dirty(shareKey: String, limit: Int): List<String>

  @Query("SELECT DISTINCT share_key FROM replica_dirty")
  suspend fun dirtyShares(): List<String>

  @Query("DELETE FROM replica_dirty WHERE share_key = :shareKey AND record_key IN (:pathKeys)")
  suspend fun clearDirty(shareKey: String, pathKeys: List<String>)

  @Query("DELETE FROM replica_dirty WHERE share_key = :shareKey")
  suspend fun clearDirtyOfShare(shareKey: String)

  // --- Pacchi e blob ------------------------------------------------------------------------------------------------

  @Query("SELECT * FROM replica_pack ORDER BY pack_id")
  suspend fun packs(): List<ReplicaPackEntity>

  @Query("SELECT * FROM replica_pack WHERE pack_id = :packId")
  suspend fun pack(packId: Long): ReplicaPackEntity?

  @Query("SELECT * FROM replica_pack WHERE file_name = :fileName")
  suspend fun packByName(fileName: String): ReplicaPackEntity?

  @Query("SELECT * FROM replica_pack WHERE share_key = :shareKey ORDER BY pack_id")
  suspend fun packsOfShare(shareKey: String): List<ReplicaPackEntity>

  /** I pacchi delle classi fuori da [keep], i piu' grandi prima (convergenza della qualita', E-L6). */
  @Query("SELECT * FROM replica_pack WHERE cls NOT IN (:keep) ORDER BY bytes DESC, pack_id")
  suspend fun packsOutside(keep: List<Int>): List<ReplicaPackEntity>

  @Insert
  suspend fun insertPack(pack: ReplicaPackEntity): Long

  @Query("UPDATE replica_pack SET bytes = :bytes WHERE pack_id = :packId")
  suspend fun setPackBytes(packId: Long, bytes: Long)

  @Query("DELETE FROM replica_pack WHERE pack_id = :packId")
  suspend fun deletePack(packId: Long)

  @Query("SELECT * FROM replica_blob ORDER BY pack_id, content_key, cls")
  suspend fun blobs(): List<ReplicaBlobEntity>

  @Query("SELECT * FROM replica_blob WHERE pack_id = :packId ORDER BY content_key, cls")
  suspend fun blobsOfPack(packId: Long): List<ReplicaBlobEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveBlobs(blobs: List<ReplicaBlobEntity>)

  @Query("DELETE FROM replica_blob WHERE pack_id = :packId")
  suspend fun deleteBlobsOfPack(packId: Long)

  // --- Accesso alle share e impostazioni ----------------------------------------------------------------------------

  @Query("SELECT * FROM replica_share_access WHERE share_key = :shareKey")
  suspend fun shareAccess(shareKey: String): ReplicaShareAccessEntity?

  @Upsert
  suspend fun saveShareAccess(row: ReplicaShareAccessEntity)

  @Query("DELETE FROM replica_share_access WHERE share_key = :shareKey")
  suspend fun deleteShareAccess(shareKey: String)

  @Query("SELECT value FROM replica_setting WHERE `key` = :key")
  suspend fun setting(key: String): String?

  @Upsert
  suspend fun saveSetting(row: ReplicaSettingEntity)

  // --- Miniature provvisorie (D17) ----------------------------------------------------------------------------------

  /** Bloccante, per chiave primaria: la chiama il dispatcher delle miniature. */
  @Query("SELECT jpeg FROM replica_provisional WHERE content_key_short = :contentKeyShort")
  fun provisional(contentKeyShort: Long): ByteArray?

  @Upsert
  suspend fun saveProvisional(row: ReplicaProvisionalEntity)

  @Query("DELETE FROM replica_provisional WHERE content_key_short = :contentKeyShort")
  suspend fun deleteProvisional(contentKeyShort: Long)

  @Query("SELECT COUNT(*) FROM replica_provisional")
  suspend fun provisionalCount(): Int

  companion object {
    /** Come [IndexPublishDao.MAX_KEYS]. */
    const val MAX_KEYS: Int = IndexPublishDao.MAX_KEYS

    /** Tutti i dispositivi per un percorso (D6). */
    const val RECORDS_AT_SQL: String = "SELECT * FROM index_record WHERE share_key = :shareKey AND record_key IN (:pathKeys)"

    /** La query del lato caldo delle miniature (indice su `content_key_short`, `cls`): `V3QueryPlanTest` ne guarda il piano. */
    const val BLOBS_FOR_SQL: String = "SELECT * FROM replica_blob WHERE content_key_short = :contentKeyShort AND cls = :cls"
  }
}

/** Byte e numero di blob di una classe nella replica. */
data class ClassBytesRow(val cls: Int, val bytes: Long, val items: Int)

/**
 * Il diario delle operazioni sul NAS e quello locale del cestino (4B, P12, §2.7, D24, D25). **Ogni passo si scrive prima
 * di farlo**: chi esegue un passo aggiorna l'elemento ([updateItem]) e solo dopo chiama il NAS.
 *
 * Firme per `NasOperations` e `NasTrash` (P12, P16): [insertOp] + [insertItems] alla conferma (una transazione);
 * [itemsToResume] alla ripresa; [updateItem] / [setOpState] a ogni passo; [insertTrashEntry] prima della rinomina nel
 * cestino; [expiredTrash] per la pulizia (solo voci proprie, ora del NAS).
 */
@Dao
interface NasOpsDao {

  @Insert
  suspend fun insertOp(op: NasOpEntity)

  @Insert
  suspend fun insertItems(items: List<NasOpItemEntity>)

  @Query("SELECT * FROM nas_op WHERE op_id = :opId")
  suspend fun op(opId: String): NasOpEntity?

  @Query("SELECT * FROM nas_op WHERE state IN (:states) ORDER BY created_at, op_id")
  suspend fun opsInState(states: List<String>): List<NasOpEntity>

  @Query("SELECT * FROM nas_op ORDER BY created_at, op_id")
  fun observeOps(): Flow<List<NasOpEntity>>

  @Query("UPDATE nas_op SET state = :state, error_code = :errorCode, updated_at = :now WHERE op_id = :opId")
  suspend fun setOpState(opId: String, state: String, errorCode: String?, now: Long)

  @Query("SELECT * FROM nas_op_item WHERE op_id = :opId ORDER BY item_index")
  suspend fun items(opId: String): List<NasOpItemEntity>

  /** Gli elementi da riprendere: in uno degli stati [states] e pronti (nessuna attesa o attesa scaduta a [now]). */
  @Query(ITEMS_TO_RESUME_SQL)
  suspend fun itemsToResume(states: List<String>, now: Long, limit: Int): List<NasOpItemEntity>

  @androidx.room.Update
  suspend fun updateItem(item: NasOpItemEntity)

  @Query("DELETE FROM nas_op WHERE op_id = :opId")
  suspend fun deleteOp(opId: String)

  @Insert
  suspend fun insertTrashEntry(entry: NasTrashEntryEntity)

  @androidx.room.Update
  suspend fun updateTrashEntry(entry: NasTrashEntryEntity)

  @Query("SELECT * FROM nas_trash_entry WHERE trash_id = :trashId")
  suspend fun trashEntry(trashId: String): NasTrashEntryEntity?

  @Query("SELECT * FROM nas_trash_entry WHERE state = 'trashed' ORDER BY trashed_at, trash_id")
  fun observeTrash(): Flow<List<NasTrashEntryEntity>>

  /** Le voci proprie nel cestino da prima di [before] (ora del NAS), le piu' vecchie prima, al massimo [limit] (E-D4). */
  @Query(EXPIRED_TRASH_SQL)
  suspend fun expiredTrash(before: Long, limit: Int): List<NasTrashEntryEntity>

  companion object {
    const val ITEMS_TO_RESUME_SQL: String =
      "SELECT * FROM nas_op_item WHERE state IN (:states) AND (next_attempt_at IS NULL OR next_attempt_at <= :now) LIMIT :limit"

    const val EXPIRED_TRASH_SQL: String =
      "SELECT * FROM nas_trash_entry WHERE state = 'trashed' AND trashed_at < :before ORDER BY trashed_at LIMIT :limit"
  }
}

/**
 * Le istruzioni della **rinomina** di un'identita' (adozione D7, `MediaCatalog.adoptIdentities`): ogni colonna che punta a
 * un media ([dev.pampa.mycloudgallery.core.database.MediaCatalog.MEDIA_REFERENCES]) o a una versione
 * ([dev.pampa.mycloudgallery.core.database.MediaCatalog.VERSION_REFERENCES]) passa all'id nuovo nella stessa transazione.
 * Le chiavi esterne sono differite: il controllo e' al commit, quando tutto e' coerente. `MergeReferencesTest` fallisce se
 * una tabella nuova punta a `media` o a `media_version` senza essere elencata (e quindi qui).
 */
@Dao
interface IdentityDao {

  @Query("UPDATE media SET media_id = :to WHERE media_id = :from")
  suspend fun renameMediaRow(from: String, to: String)

  @Query("UPDATE media_version SET media_id = :to WHERE media_id = :from")
  suspend fun renameMediaInVersions(from: String, to: String)

  @Query("UPDATE media_personal SET media_id = :to WHERE media_id = :from")
  suspend fun renameMediaInPersonal(from: String, to: String)

  @Query("UPDATE work_state SET media_id = :to WHERE media_id = :from")
  suspend fun renameMediaInWork(from: String, to: String)

  @Query("UPDATE embedding SET media_id = :to WHERE media_id = :from")
  suspend fun renameMediaInEmbeddings(from: String, to: String)

  @Query("UPDATE media_alias SET media_id = :to WHERE media_id = :from")
  suspend fun renameMediaInAliases(from: String, to: String)

  @Query("UPDATE media_group SET cover_media_id = :to WHERE cover_media_id = :from")
  suspend fun renameMediaInGroupCovers(from: String, to: String)

  @Query("UPDATE album SET cover_media_id = :to WHERE cover_media_id = :from")
  suspend fun renameMediaInAlbumCovers(from: String, to: String)

  @Query("UPDATE album_item SET media_id = :to WHERE media_id = :from")
  suspend fun renameMediaInAlbumItems(from: String, to: String)

  @Query("UPDATE media_version SET version_id = :to WHERE version_id = :from")
  suspend fun renameVersionRow(from: String, to: String)

  @Query("UPDATE media SET current_version_id = :to WHERE current_version_id = :from")
  suspend fun renameVersionInMedia(from: String, to: String)

  @Query("UPDATE media_version SET parent_version_id = :to WHERE parent_version_id = :from")
  suspend fun renameVersionInParents(from: String, to: String)

  @Query("UPDATE media_location SET version_id = :to WHERE version_id = :from")
  suspend fun renameVersionInLocations(from: String, to: String)

  @Query("UPDATE version_metadata SET version_id = :to WHERE version_id = :from")
  suspend fun renameVersionInMetadata(from: String, to: String)

  @Query("UPDATE work_state SET version_id = :to WHERE version_id = :from")
  suspend fun renameVersionInWork(from: String, to: String)

  @Query("UPDATE embedding SET version_id = :to WHERE version_id = :from")
  suspend fun renameVersionInEmbeddings(from: String, to: String)

  /** Tutte le colonne che puntano al media [from] passano a [to] (stesso ordine di `MEDIA_REFERENCES`). */
  suspend fun renameMedia(from: String, to: String) {
    renameMediaRow(from, to)
    renameMediaInVersions(from, to)
    renameMediaInPersonal(from, to)
    renameMediaInWork(from, to)
    renameMediaInEmbeddings(from, to)
    renameMediaInAliases(from, to)
    renameMediaInGroupCovers(from, to)
    renameMediaInAlbumCovers(from, to)
    renameMediaInAlbumItems(from, to)
  }

  /** Tutte le colonne che puntano alla versione [from] passano a [to] (`VERSION_REFERENCES`). */
  suspend fun renameVersion(from: String, to: String) {
    renameVersionRow(from, to)
    renameVersionInMedia(from, to)
    renameVersionInParents(from, to)
    renameVersionInLocations(from, to)
    renameVersionInMetadata(from, to)
    renameVersionInWork(from, to)
    renameVersionInEmbeddings(from, to)
  }
}
