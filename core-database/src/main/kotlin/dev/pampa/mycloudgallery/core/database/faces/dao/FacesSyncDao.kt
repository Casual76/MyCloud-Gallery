package dev.pampa.mycloudgallery.core.database.faces.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesChunkEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishDiaryEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishKeyEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishSegmentEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishStateEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaDeviceEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaRevokedEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaSegmentEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaSegmentStateEntity

// IL DAO DELLO STATO DI SINCRONIZZAZIONE (Fase 8, P3 + P6; `docs/modello-dati.md` §15.6): quello che la replica e il pubblicatore ricordano.
// Letture a tabelle intere (lo stato si carica intero, una volta per processo) e **una scrittura per stato**, in una transazione, con la sola
// differenza rispetto all'ultimo stato scritto (la fa `store/`). I valori non stanno nei log.

/** Un blocco di `faces_chunk` per ambito, proprietario e nome (per toglierlo). */
class ChunkRef(val scope: String, val owner: String, val name: String) {
  override fun toString(): String = "ChunkRef($scope)"
}

/**
 * Tutto cio' che cambia nello stato della replica, da scrivere in una volta sola. L'ordine e' fisso: tolti i dispositivi (con le loro righe e
 * i loro blocchi), riscritte le righe dei dispositivi cambiati, poi i blocchi, poi i revocati.
 */
class ReplicaWrite(
  val removedDevices: List<String> = emptyList(),
  val devices: List<ReplicaDeviceEntity> = emptyList(),
  /** I dispositivi di cui si riscrivono segmenti e segmenti letti (le righe vecchie si tolgono, quelle in [segments] e [states] si mettono). */
  val rewrittenDevices: List<String> = emptyList(),
  val segments: List<ReplicaSegmentEntity> = emptyList(),
  val states: List<ReplicaSegmentStateEntity> = emptyList(),
  val removedChunks: List<ChunkRef> = emptyList(),
  val chunks: List<FacesChunkEntity> = emptyList(),
  val addedRevoked: List<String> = emptyList(),
  val removedRevoked: List<String> = emptyList(),
) {
  override fun toString(): String = "ReplicaWrite"
}

/** Tutto cio' che cambia nello stato del pubblicatore, da scrivere in una volta sola. */
class PublishWrite(
  val state: PublishStateEntity? = null,
  val addedKeys: List<String> = emptyList(),
  val removedKeys: List<String> = emptyList(),
  /** Se vero, i segmenti dell'ultima testa si riscrivono (le righe vecchie si tolgono, quelle in [segments] si mettono). */
  val rewriteSegments: Boolean = false,
  val segments: List<String> = emptyList(),
  val addedDiary: List<PublishDiaryEntity> = emptyList(),
  val removedDiary: List<String> = emptyList(),
  val removedChunks: List<ChunkRef> = emptyList(),
  val chunks: List<FacesChunkEntity> = emptyList(),
) {
  override fun toString(): String = "PublishWrite"
}

@Dao
abstract class FacesSyncDao {

  // --- Letture -----------------------------------------------------------------------------------------------------------------

  @Query("SELECT * FROM replica_device")
  abstract suspend fun replicaDevices(): List<ReplicaDeviceEntity>

  @Query("SELECT * FROM replica_segment ORDER BY device_id, position")
  abstract suspend fun replicaSegments(): List<ReplicaSegmentEntity>

  @Query("SELECT * FROM replica_segment_state ORDER BY device_id, hash")
  abstract suspend fun replicaStates(): List<ReplicaSegmentStateEntity>

  @Query("SELECT device_id FROM replica_revoked ORDER BY device_id")
  abstract suspend fun replicaRevoked(): List<String>

  @Query("SELECT * FROM faces_chunk WHERE scope = :scope ORDER BY owner, name, part")
  abstract suspend fun chunks(scope: String): List<FacesChunkEntity>

  @Query("SELECT * FROM publish_state WHERE id = 1")
  abstract suspend fun publishState(): PublishStateEntity?

  @Query("SELECT content_key FROM publish_key ORDER BY content_key")
  abstract suspend fun publishKeys(): List<String>

  @Query("SELECT hash FROM publish_segment ORDER BY hash")
  abstract suspend fun publishSegments(): List<String>

  @Query("SELECT * FROM publish_diary ORDER BY ord")
  abstract suspend fun publishDiary(): List<PublishDiaryEntity>

  @Query("SELECT COUNT(*) FROM faces_chunk")
  abstract suspend fun chunkCount(): Int

  // --- Scritture di basso livello (chiamate solo dalle due transazioni) --------------------------------------------------------

  @Query("DELETE FROM replica_device WHERE device_id = :deviceId")
  abstract suspend fun deleteReplicaDevice(deviceId: String)

  @Query("DELETE FROM replica_segment WHERE device_id = :deviceId")
  abstract suspend fun deleteReplicaSegments(deviceId: String)

  @Query("DELETE FROM replica_segment_state WHERE device_id = :deviceId")
  abstract suspend fun deleteReplicaStates(deviceId: String)

  @Query("DELETE FROM faces_chunk WHERE scope = :scope AND owner = :owner AND name = :name")
  abstract suspend fun deleteChunk(scope: String, owner: String, name: String)

  @Query("DELETE FROM faces_chunk WHERE owner = :owner AND scope LIKE 'replica.%'")
  abstract suspend fun deleteReplicaChunksOf(owner: String)

  @Upsert
  abstract suspend fun upsertReplicaDevices(rows: List<ReplicaDeviceEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertReplicaSegments(rows: List<ReplicaSegmentEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertReplicaStates(rows: List<ReplicaSegmentStateEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertChunks(rows: List<FacesChunkEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertRevoked(rows: List<ReplicaRevokedEntity>)

  @Query("DELETE FROM replica_revoked WHERE device_id IN (:ids)")
  abstract suspend fun deleteRevoked(ids: List<String>)

  @Upsert
  abstract suspend fun upsertPublishState(row: PublishStateEntity)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract suspend fun insertPublishKeys(rows: List<PublishKeyEntity>)

  @Query("DELETE FROM publish_key WHERE content_key IN (:keys)")
  abstract suspend fun deletePublishKeys(keys: List<String>)

  @Query("DELETE FROM publish_segment")
  abstract suspend fun deletePublishSegments()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertPublishSegments(rows: List<PublishSegmentEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertPublishDiary(rows: List<PublishDiaryEntity>)

  @Query("DELETE FROM publish_diary WHERE hash IN (:hashes)")
  abstract suspend fun deletePublishDiary(hashes: List<String>)

  // --- Le due transazioni ------------------------------------------------------------------------------------------------------

  /** Scrive tutto [write] o niente. Le liste lunghe si spezzano in gruppi da [FaceBodyDao.IN_LIMIT] (999 variabili per istruzione). */
  @Transaction
  open suspend fun writeReplica(write: ReplicaWrite) {
    for (device in write.removedDevices) {
      deleteReplicaChunksOf(device)
      deleteReplicaDevice(device)
      deleteReplicaSegments(device)
      deleteReplicaStates(device)
    }
    if (write.devices.isNotEmpty()) upsertReplicaDevices(write.devices)
    for (device in write.rewrittenDevices) {
      deleteReplicaSegments(device)
      deleteReplicaStates(device)
    }
    if (write.segments.isNotEmpty()) insertReplicaSegments(write.segments)
    if (write.states.isNotEmpty()) insertReplicaStates(write.states)
    for (ref in write.removedChunks) deleteChunk(ref.scope, ref.owner, ref.name)
    if (write.chunks.isNotEmpty()) insertChunks(write.chunks)
    write.removedRevoked.chunked(FaceBodyDao.IN_LIMIT).forEach { deleteRevoked(it) }
    if (write.addedRevoked.isNotEmpty()) insertRevoked(write.addedRevoked.map(::ReplicaRevokedEntity))
  }

  @Transaction
  open suspend fun writePublish(write: PublishWrite) {
    write.state?.let { upsertPublishState(it) }
    write.removedKeys.chunked(FaceBodyDao.IN_LIMIT).forEach { deletePublishKeys(it) }
    write.addedKeys.chunked(INSERT_BATCH).forEach { batch -> insertPublishKeys(batch.map(::PublishKeyEntity)) }
    if (write.rewriteSegments) {
      deletePublishSegments()
      if (write.segments.isNotEmpty()) insertPublishSegments(write.segments.map(::PublishSegmentEntity))
    }
    write.removedDiary.chunked(FaceBodyDao.IN_LIMIT).forEach { deletePublishDiary(it) }
    write.addedDiary.chunked(INSERT_BATCH).forEach { insertPublishDiary(it) }
    for (ref in write.removedChunks) deleteChunk(ref.scope, ref.owner, ref.name)
    if (write.chunks.isNotEmpty()) insertChunks(write.chunks)
  }

  companion object {
    private const val INSERT_BATCH = 2_000
  }
}
