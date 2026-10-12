package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.entity.MediaAliasEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaGraph
import dev.pampa.mycloudgallery.core.database.entity.MediaGroupEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaLocationEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaVersionEntity
import dev.pampa.mycloudgallery.core.database.entity.NasShareEntity
import dev.pampa.mycloudgallery.core.database.entity.StatusCount
import dev.pampa.mycloudgallery.core.database.entity.VersionMetadataEntity
import kotlinx.coroutines.flow.Flow

/**
 * I media e il loro grafo. Le scritture che toccano piu' tabelle passano da `MediaCatalog`, che le
 * fa in una transazione applicando le regole di dominio: chiamare questi metodi da soli puo' lasciare
 * i campi derivati (`presence`, `safety`, `sort_time`) indietro.
 */
@Dao
interface MediaDao {

  @Insert
  suspend fun insert(media: MediaEntity)

  @Update
  suspend fun update(media: MediaEntity)

  @Query("DELETE FROM media WHERE media_id = :mediaId")
  suspend fun delete(mediaId: String)

  @Query("SELECT * FROM media WHERE media_id = :mediaId")
  suspend fun get(mediaId: String): MediaEntity?

  @Transaction
  @Query("SELECT * FROM media WHERE media_id = :mediaId")
  suspend fun graph(mediaId: String): MediaGraph?

  /** Il grafo di un media che si aggiorna da solo (pannello info, viewer). */
  @Transaction
  @Query("SELECT * FROM media WHERE media_id = :mediaId")
  fun observeGraph(mediaId: String): Flow<MediaGraph?>

  @Query("SELECT media_id FROM media ORDER BY media_id")
  suspend fun allIds(): List<String>

  @Query("SELECT COUNT(*) FROM media")
  suspend fun count(): Int

  @Query("UPDATE media SET current_version_id = :versionId, updated_at = :now WHERE media_id = :mediaId")
  suspend fun setCurrentVersion(mediaId: String, versionId: String, now: Long)

  @Query(
    "UPDATE media SET presence = :presence, safety = :safety, sort_time = :sortTime, updated_at = :now " +
      "WHERE media_id = :mediaId",
  )
  suspend fun updateDerived(mediaId: String, presence: String, safety: String, sortTime: Long, now: Long)

  @Query("UPDATE media SET owner_account = :account, updated_at = :now WHERE media_id = :mediaId")
  suspend fun setOwner(mediaId: String, account: String?, now: Long)

  @Query("UPDATE media SET group_id = :groupId, updated_at = :now WHERE media_id = :mediaId")
  suspend fun setGroup(mediaId: String, groupId: String?, now: Long)

  @Insert
  suspend fun insertGroup(group: MediaGroupEntity)

  /** "Tutto al sicuro" / "312 in coda": i media di un account per stato, tra quelli visibili. */
  @Query(
    "SELECT safety AS status, COUNT(*) AS count FROM media " +
      "WHERE owner_account = :account AND presence != 'none' GROUP BY safety",
  )
  fun observeSafetyCounts(account: String): Flow<List<StatusCount>>

  // --- Alias (identita' assorbite da un'unione) ----------------------------------------------------

  @Query("SELECT media_id FROM media_alias WHERE alias_id = :aliasId")
  suspend fun aliasTarget(aliasId: String): String?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAlias(alias: MediaAliasEntity)
}

@Dao
interface VersionDao {

  @Insert
  suspend fun insert(version: MediaVersionEntity)

  @Update
  suspend fun update(version: MediaVersionEntity)

  @Query("SELECT * FROM media_version WHERE version_id = :versionId")
  suspend fun get(versionId: String): MediaVersionEntity?

  @Query("SELECT * FROM media_version WHERE media_id = :mediaId ORDER BY created_at")
  suspend fun forMedia(mediaId: String): List<MediaVersionEntity>

  /** Primo livello del collegamento per impronta: stessa dimensione e stesso hash rapido. */
  @Query("SELECT * FROM media_version WHERE size = :size AND quick_hash = :quickHash")
  suspend fun byQuickFingerprint(size: Long, quickHash: String): List<MediaVersionEntity>

  /** Le versioni con questi byte esatti (deduplica del backup, E-B5). */
  @Query("SELECT * FROM media_version WHERE full_hash = :fullHash")
  suspend fun byFullHash(fullHash: String): List<MediaVersionEntity>

  @Upsert
  suspend fun upsertMetadata(metadata: VersionMetadataEntity)

  @Query("SELECT * FROM version_metadata WHERE version_id = :versionId")
  suspend fun metadata(versionId: String): VersionMetadataEntity?
}

@Dao
interface LocationDao {

  @Insert
  suspend fun insert(location: MediaLocationEntity): Long

  @Update
  suspend fun update(location: MediaLocationEntity)

  @Query("DELETE FROM media_location WHERE location_id = :locationId")
  suspend fun delete(locationId: Long)

  @Query("SELECT * FROM media_location WHERE location_id = :locationId")
  suspend fun get(locationId: Long): MediaLocationEntity?

  /** Il posto (chiave normalizzata, E-N7). */
  @Query("SELECT * FROM media_location WHERE location_key = :locationKey")
  suspend fun byKey(locationKey: String): MediaLocationEntity?

  /** Cache MediaStore: le posizioni che hanno in cache questo `_ID` su questo volume. */
  @Query("SELECT * FROM media_location WHERE volume = :volume AND media_store_id = :mediaStoreId")
  suspend fun byMediaStoreId(volume: String, mediaStoreId: Long): List<MediaLocationEntity>

  /** Il contenuto conosciuto di una cartella, per confrontarlo con una scansione (E-N5). */
  @Query("SELECT * FROM media_location WHERE folder_key = :folderKey ORDER BY location_key")
  suspend fun inFolder(folderKey: String): List<MediaLocationEntity>

  /** Le posizioni sparite con questi byte probabili: candidate a uno spostamento (E-N1). */
  @Query(
    "SELECT l.* FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
      "WHERE l.state = 'absent' AND l.location_type = :type AND v.size = :size AND v.quick_hash = :quickHash",
  )
  suspend fun vanishedWithContent(type: String, size: Long, quickHash: String): List<MediaLocationEntity>

  @Query("SELECT * FROM media_location WHERE state = 'absent' AND absent_since <= :before")
  suspend fun absentSince(before: Long): List<MediaLocationEntity>

  @Query("SELECT * FROM media_location WHERE location_type = 'phone' AND volume = :volume AND state = 'present'")
  suspend fun presentOnVolume(volume: String): List<MediaLocationEntity>

  @Query("SELECT v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id WHERE l.location_id = :locationId")
  suspend fun mediaIdOf(locationId: Long): String?

  /** I media con almeno una copia in una share: sono gli unici toccati da un cambio della sua area. */
  @Query(
    "SELECT DISTINCT v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
      "WHERE l.location_type = 'nas' AND l.account_id = :account AND l.share_key = :shareKey",
  )
  suspend fun mediaIdsInShare(account: String, shareKey: String): List<String>
}

@Dao
interface NasShareDao {

  @Upsert
  suspend fun upsert(share: NasShareEntity)

  @Query("SELECT * FROM nas_share")
  suspend fun all(): List<NasShareEntity>
}
