package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.entity.IndexHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOutboxBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOutboxRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexOwnSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexPublishStateEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexRecordEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexSyncStateEntity
import dev.pampa.mycloudgallery.core.database.entity.NasOpEntity
import dev.pampa.mycloudgallery.core.database.entity.NasOpItemEntity
import dev.pampa.mycloudgallery.core.database.entity.NasScanStateEntity
import dev.pampa.mycloudgallery.core.database.entity.NasTrashEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaBlobEntity
import dev.pampa.mycloudgallery.core.database.entity.ReplicaPackEntity
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
}

/**
 * La pubblicazione dell'indice di **questo** dispositivo (P3, §2.1): stato per share, outbox dei record e dei blob,
 * segmenti propri elencati dall'ultima testa.
 *
 * Firme per l'adattatore di `IndexPublisher` (P11):
 * - `stage`: in una transazione, per ogni record `takeSeq` + [upsertRecord]; per ogni blob [upsertBlob];
 * - `flush`: [pendingRecords] in ordine di `seq` e [pendingBlobs] per classe → segmenti → testa; poi, in una transazione,
 *   [insertOwnSegments], [savePublishState] con la versione nuova e [deleteRecordsUpTo] / [deleteBlobs] di cio' che e'
 *   uscito.
 */
@Dao
interface IndexPublishDao {

  @Query("SELECT * FROM index_publish_state WHERE share_key = :shareKey")
  suspend fun publishState(shareKey: String): IndexPublishStateEntity?

  @Upsert
  suspend fun savePublishState(state: IndexPublishStateEntity)

  /** Crea lo stato della share con `next_seq = 1` se manca (due istruzioni: niente `UPSERT` su API 29). */
  @Query("INSERT OR IGNORE INTO index_publish_state (share_key, next_seq, head_version) VALUES (:shareKey, 1, 0)")
  suspend fun createIfMissing(shareKey: String)

  @Query("UPDATE index_publish_state SET next_seq = next_seq + :count WHERE share_key = :shareKey")
  suspend fun advanceSeq(shareKey: String, count: Long)

  /**
   * Prende [count] numeri di sequenza consecutivi per la share e restituisce il primo (D5). Va chiamata **dentro** la
   * transazione che scrive i record: un crash non lascia numeri a meta' e due lavori non si contendono lo stesso.
   */
  suspend fun takeSeq(shareKey: String, count: Long = 1): Long {
    require(count > 0) { "Almeno un numero." }
    createIfMissing(shareKey)
    val first = checkNotNull(publishState(shareKey)).nextSeq
    advanceSeq(shareKey, count)
    return first
  }

  /** Un record nell'outbox: sostituisce quello in coda per la stessa chiave (vince il piu' nuovo, D5). */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertRecord(record: IndexOutboxRecordEntity)

  /** I record in coda per la share, in ordine di `seq`, a pagine (`afterSeq` = ultimo gia' letto, 0 all'inizio). */
  @Query(PENDING_RECORDS_SQL)
  suspend fun pendingRecords(shareKey: String, afterSeq: Long, limit: Int): List<IndexOutboxRecordEntity>

  /** Toglie i record pubblicati: quelli con `seq` fino a [maxSeq] compreso (un record ri-messo in coda ha un `seq` nuovo). */
  @Query("DELETE FROM index_outbox_record WHERE share_key = :shareKey AND seq <= :maxSeq")
  suspend fun deleteRecordsUpTo(shareKey: String, maxSeq: Long): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertBlob(blob: IndexOutboxBlobEntity)

  /** I blob in coda di una classe, in ordine di chiave di contenuto (l'ordine della tabella del segmento). */
  @Query(PENDING_BLOBS_SQL)
  suspend fun pendingBlobs(shareKey: String, cls: Int, afterKey: String, limit: Int): List<IndexOutboxBlobEntity>

  @Query("DELETE FROM index_outbox_blob WHERE share_key = :shareKey AND cls = :cls AND content_key IN (:keys)")
  suspend fun deleteBlobs(shareKey: String, cls: Int, keys: List<String>): Int

  /** I blob che stanno in un pacco temporaneo (per sapere se il file si puo' togliere). */
  @Query("SELECT COUNT(*) FROM index_outbox_blob WHERE pack = :pack")
  suspend fun blobsInPack(pack: String): Int

  /** Quanto c'e' in coda (`PendingCounts`): record e blob di tutte le share. */
  @Query("SELECT (SELECT COUNT(*) FROM index_outbox_record) AS records, (SELECT COUNT(*) FROM index_outbox_blob) AS blobs, " +
    "(SELECT COALESCE(SUM(byte_length), 0) FROM index_outbox_blob) AS bytes")
  fun observePending(): Flow<PendingRow>

  @Query("SELECT * FROM index_own_segment WHERE share_key = :shareKey ORDER BY segment_hash")
  suspend fun ownSegments(shareKey: String): List<IndexOwnSegmentEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOwnSegments(segments: List<IndexOwnSegmentEntity>)

  @Query("DELETE FROM index_own_segment WHERE share_key = :shareKey AND segment_hash IN (:hashes)")
  suspend fun deleteOwnSegments(shareKey: String, hashes: List<String>): Int

  companion object {
    const val PENDING_RECORDS_SQL: String =
      "SELECT * FROM index_outbox_record WHERE share_key = :shareKey AND seq > :afterSeq ORDER BY seq LIMIT :limit"

    const val PENDING_BLOBS_SQL: String =
      "SELECT * FROM index_outbox_blob WHERE share_key = :shareKey AND cls = :cls AND content_key > :afterKey " +
        "ORDER BY content_key LIMIT :limit"
  }
}

/** Quanto c'e' nell'outbox. */
data class PendingRow(val records: Int, val blobs: Int, val bytes: Long)

/**
 * La replica locale dell'indice (P4, §2.2): teste accettate, segmenti, record `meta`, pacchi e blob, stato per share.
 *
 * Firme per gli adattatori di `IndexSync`, `ReplicaBlobs`, `ReplicaRecords` (P11):
 * - testa nuova accettata: in **una** transazione [upsertSegments] dei segmenti elencati, [markSegment] di quelli tolti
 *   (o [deleteSegment] + [deleteRecordsOfSegment] + [deletePacksOfSegment]), per ogni segmento `meta` scaricato
 *   [upsertRecords] (tenendo il `seq` piu' alto, vedi [recordFor]) e [saveHead]; le osservazioni al catalogo passano da
 *   `MediaCatalog.recordAll` / `adoptIdentities` (anche nella stessa transazione, con `inSameTransaction`);
 * - pacco scaricato: [insertPack] + [insertBlobs], poi [markSegment] `applied` con il `pack_id`;
 * - `ReplicaRecords.matching`: [recordsAt] per chiave del percorso, scelta D6 fatta da chi chiama;
 * - `ReplicaBlobs.locate`: [blobsFor] (o pacco per pacco con [blobsOfPack] per l'indice in memoria).
 */
@Dao
interface ReplicaDao {

  @Query("SELECT * FROM index_head WHERE share_key = :shareKey ORDER BY device_id")
  suspend fun heads(shareKey: String): List<IndexHeadEntity>

  @Query("SELECT * FROM index_head WHERE share_key = :shareKey AND device_id = :deviceId")
  suspend fun head(shareKey: String, deviceId: String): IndexHeadEntity?

  @Upsert
  suspend fun saveHead(head: IndexHeadEntity)

  @Query("DELETE FROM index_head WHERE share_key = :shareKey AND device_id = :deviceId")
  suspend fun deleteHead(shareKey: String, deviceId: String)

  @Query("SELECT * FROM index_segment WHERE share_key = :shareKey AND device_id = :deviceId ORDER BY segment_hash")
  suspend fun segments(shareKey: String, deviceId: String): List<IndexSegmentEntity>

  /** I segmenti di una share in uno stato (es. `listed` = da scaricare). */
  @Query("SELECT * FROM index_segment WHERE share_key = :shareKey AND state = :state")
  suspend fun segmentsInState(shareKey: String, state: String): List<IndexSegmentEntity>

  @Upsert
  suspend fun upsertSegments(segments: List<IndexSegmentEntity>)

  @Query(
    "UPDATE index_segment SET state = :state, pack_id = :packId, updated_at = :now " +
      "WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segmentHash",
  )
  suspend fun markSegment(shareKey: String, deviceId: String, segmentHash: String, state: String, packId: Long?, now: Long)

  @Query("DELETE FROM index_segment WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segmentHash")
  suspend fun deleteSegment(shareKey: String, deviceId: String, segmentHash: String)

  /** Il record di un dispositivo per una chiave (per tenere il `seq` piu' alto, D5). */
  @Query("SELECT * FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId AND record_key = :recordKey")
  suspend fun recordFor(shareKey: String, deviceId: String, recordKey: String): IndexRecordEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertRecords(records: List<IndexRecordEntity>)

  /** Tutti i record di un percorso, di tutti i dispositivi (D6). Al massimo 500 chiavi per chiamata. */
  @Query(RECORDS_AT_SQL)
  suspend fun recordsAt(shareKey: String, recordKeys: List<String>): List<IndexRecordEntity>

  /** I record con una chiave di contenuto (forma ridotta): chi ha preparato cosa, fallimenti D14. */
  @Query(RECORDS_WITH_CONTENT_SQL)
  suspend fun recordsWithContent(contentKey: Long): List<IndexRecordEntity>

  /** I record di una share a pagine, in ordine di chiave (ricostruzioni, unita' di lavoro). */
  @Query(
    "SELECT * FROM index_record WHERE share_key = :shareKey AND record_key > :afterKey ORDER BY record_key LIMIT :limit",
  )
  suspend fun recordsInShare(shareKey: String, afterKey: String, limit: Int): List<IndexRecordEntity>

  @Query("DELETE FROM index_record WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segmentHash")
  suspend fun deleteRecordsOfSegment(shareKey: String, deviceId: String, segmentHash: String): Int

  /** Dimentica una share intera (E-I8: accesso negato in due giorni diversi). I pacchi li toglie chi chiama dal disco. */
  @Query("DELETE FROM index_record WHERE share_key = :shareKey")
  suspend fun deleteRecordsOfShare(shareKey: String): Int

  @Insert
  suspend fun insertPack(pack: ReplicaPackEntity): Long

  @Query("SELECT * FROM replica_pack WHERE pack_id = :packId")
  suspend fun pack(packId: Long): ReplicaPackEntity?

  @Query("SELECT * FROM replica_pack ORDER BY pack_id")
  suspend fun allPacks(): List<ReplicaPackEntity>

  @Query("SELECT * FROM replica_pack WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segmentHash")
  suspend fun packsOfSegment(shareKey: String, deviceId: String, segmentHash: String): List<ReplicaPackEntity>

  /** Toglie un pacco (e in cascata i suoi blob). Il file lo cancella chi chiama, dopo il commit. */
  @Query("DELETE FROM replica_pack WHERE pack_id = :packId")
  suspend fun deletePack(packId: Long)

  @Query("DELETE FROM replica_pack WHERE share_key = :shareKey AND device_id = :deviceId AND segment_hash = :segmentHash")
  suspend fun deletePacksOfSegment(shareKey: String, deviceId: String, segmentHash: String): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertBlobs(blobs: List<ReplicaBlobEntity>)

  /** I blob di una chiave ridotta e di una classe (piu' righe se piu' dispositivi l'hanno pubblicata). */
  @Query(BLOBS_FOR_SQL)
  suspend fun blobsFor(contentKeyShort: Long, cls: Int): List<ReplicaBlobEntity>

  /** I blob di un pacco, in ordine di chiave: per costruire l'indice in memoria di `ReplicaBlobs` pacco per pacco. */
  @Query("SELECT * FROM replica_blob WHERE pack_id = :packId ORDER BY content_key")
  suspend fun blobsOfPack(packId: Long): List<ReplicaBlobEntity>

  /** Byte dei blob per classe (stima dello spazio, `ReplicaQualityState.currentBytes`). */
  @Query("SELECT cls, COALESCE(SUM(byte_length), 0) AS bytes, COUNT(*) AS items FROM replica_blob GROUP BY cls")
  suspend fun bytesByClass(): List<ClassBytesRow>

  @Query("SELECT * FROM index_sync_state WHERE share_key = :shareKey")
  suspend fun syncState(shareKey: String): IndexSyncStateEntity?

  @Upsert
  suspend fun saveSyncState(state: IndexSyncStateEntity)

  companion object {
    const val RECORDS_AT_SQL: String = "SELECT * FROM index_record WHERE share_key = :shareKey AND record_key IN (:recordKeys)"

    const val RECORDS_WITH_CONTENT_SQL: String = "SELECT * FROM index_record WHERE content_key = :contentKey"

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
