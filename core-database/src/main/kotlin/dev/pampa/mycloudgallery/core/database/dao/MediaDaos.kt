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

  /** Riscrive i campi derivati (v2 compresi): li calcola solo `MediaCatalog`, con le regole di dominio. */
  @Query(
    "UPDATE media SET presence = :presence, safety = :safety, sort_time = :sortTime, local_day = :localDay, " +
      "aspect_milli = :aspectMilli, duration_ms = :durationMs, place_id = :placeId, cell_flags = :cellFlags, " +
      "thumb_hash = :thumbHash, updated_at = :now WHERE media_id = :mediaId",
  )
  suspend fun updateDerived(
    mediaId: String,
    presence: String,
    safety: String,
    sortTime: Long,
    localDay: Int,
    aspectMilli: Int,
    durationMs: Long?,
    placeId: Int?,
    cellFlags: Int,
    thumbHash: ByteArray?,
    now: Long,
  )

  @Query("UPDATE media SET kind = :kind, updated_at = :now WHERE media_id = :mediaId")
  suspend fun setKind(mediaId: String, kind: String, now: Long)

  @Query("UPDATE media SET owner_account = :account, updated_at = :now WHERE media_id = :mediaId")
  suspend fun setOwner(mediaId: String, account: String?, now: Long)

  @Query("UPDATE media SET group_id = :groupId, updated_at = :now WHERE media_id = :mediaId")
  suspend fun setGroup(mediaId: String, groupId: String?, now: Long)

  @Insert
  suspend fun insertGroup(group: MediaGroupEntity)

  @Query("SELECT * FROM media_group WHERE group_id = :groupId")
  suspend fun group(groupId: String): MediaGroupEntity?

  @Query("DELETE FROM media_group WHERE group_id = :groupId")
  suspend fun deleteGroup(groupId: String)

  /** I membri di un gruppo, in ordine di identita'. */
  @Query("SELECT * FROM media WHERE group_id = :groupId ORDER BY media_id")
  suspend fun groupMembers(groupId: String): List<MediaEntity>

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

  /** Le posizioni assenti da prima di [before], le piu' vecchie prima. */
  @Query("SELECT * FROM media_location WHERE state = 'absent' AND absent_since <= :before ORDER BY absent_since, location_id")
  suspend fun absentSince(before: Long): List<MediaLocationEntity>

  /** I media con almeno una copia su un volume del telefono (cambia la versione del MediaStore: E-P22). */
  @Query(
    "SELECT DISTINCT v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
      "WHERE l.location_type = 'phone' AND l.volume = :volume",
  )
  suspend fun mediaIdsOnVolume(volume: String): List<String>

  @Query("SELECT * FROM media_location WHERE location_type = 'phone' AND volume = :volume AND state = 'present'")
  suspend fun presentOnVolume(volume: String): List<MediaLocationEntity>

  /**
   * Le posizioni di un volume che una scansione completa iniziata a [epoch] non ha visto: presenti (o non
   * raggiungibili) con `last_seen_at` precedente. Le piu' vecchie prima, al massimo [limit].
   */
  @Query(
    "SELECT * FROM media_location WHERE location_type = 'phone' AND volume = :volume " +
      "AND state IN ('present', 'unavailable') AND last_seen_at < :epoch ORDER BY last_seen_at, location_id LIMIT :limit",
  )
  suspend fun notSeenSince(volume: String, epoch: Long, limit: Int): List<MediaLocationEntity>

  @Query("SELECT v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id WHERE l.location_id = :locationId")
  suspend fun mediaIdOf(locationId: Long): String?

  /**
   * Gli **hash completi rimandati** (Fase 3, P11; docs/fase3-progetto.md §2.1, riga "Impronta"): le copie presenti sul
   * telefono, con un `_ID` in cache, di versioni **senza hash completo** piu' grandi di [minSize] che hanno la stessa
   * impronta rapida (dimensione e hash rapido) di una versione di un **altro** media. Sono i media nati separati
   * (`KEEP_SEPARATE`) perche' leggere il file intero durante la lettura del telefono costava troppo: la manutenzione
   * in carica calcola l'hash completo e il catalogo decide. Le piu' vecchie prima, al massimo [limit].
   *
   * Solo una query: lo schema non cambia (l'esistenza del gemello usa l'indice `size, quick_hash`).
   */
  @Query(
    "SELECT l.* FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
      "WHERE l.location_type = 'phone' AND l.state = 'present' AND l.media_store_id IS NOT NULL " +
      "AND v.full_hash IS NULL AND v.size > :minSize " +
      "AND EXISTS (SELECT 1 FROM media_version o WHERE o.size = v.size AND o.quick_hash = v.quick_hash " +
      "AND o.media_id != v.media_id) " +
      "ORDER BY l.location_id LIMIT :limit",
  )
  suspend fun deferredFullHashes(minSize: Long, limit: Int): List<MediaLocationEntity>

  /** I media con almeno una copia in una share: sono gli unici toccati da un cambio della sua area. */
  @Query(
    "SELECT DISTINCT v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
      "WHERE l.location_type = 'nas' AND l.account_id = :account AND l.share_key = :shareKey",
  )
  suspend fun mediaIdsInShare(account: String, shareKey: String): List<String>

  // --- v3: il NAS (docs/fase4-progetto.md §2.3) ------------------------------------------------------------------

  /**
   * I file noti (presenti o non raggiungibili, **non** gli assenti) che stanno direttamente in una cartella del NAS
   * ([folderKey] = `Location.Nas.folderKey`): la scansione li confronta con l'elenco della cartella (scorciatoia del
   * timbro, D9, E-N5). Nessun ordine: chi chiama li mette in una mappa per chiave di posto.
   */
  @Query(KNOWN_IN_FOLDER_SQL)
  suspend fun knownInFolder(folderKey: String): List<MediaLocationEntity>

  /** Quanti file presenti ha una share nel catalogo (le soglie del congelamento, D10). */
  @Query(COUNT_PRESENT_IN_SHARE_SQL)
  suspend fun countPresentInShare(account: String, shareKey: String): Int

  /**
   * Le posizioni di una share che un giro completo iniziato a [epoch] non ha visto: presenti o non raggiungibili con
   * `last_seen_at` precedente. Le viste meno di recente prima, al massimo [limit] (indice `account_id, share_key,
   * last_seen_at`: nessun ordinamento in memoria).
   */
  @Query(NOT_SEEN_IN_SHARE_SQL)
  suspend fun notSeenInShareSince(account: String, shareKey: String, epoch: Long, limit: Int): List<MediaLocationEntity>

  /** Le posizioni presenti di una share, al massimo [limit] (share non piu' accessibile, E-R10). */
  @Query(PRESENT_IN_SHARE_SQL)
  suspend fun presentInShare(account: String, shareKey: String, limit: Int): List<MediaLocationEntity>

  companion object {
    const val KNOWN_IN_FOLDER_SQL: String = "SELECT * FROM media_location WHERE folder_key = :folderKey AND state != 'absent'"

    const val COUNT_PRESENT_IN_SHARE_SQL: String =
      "SELECT COUNT(*) FROM media_location WHERE account_id = :account AND share_key = :shareKey AND state = 'present'"

    const val NOT_SEEN_IN_SHARE_SQL: String =
      "SELECT * FROM media_location WHERE account_id = :account AND share_key = :shareKey " +
        "AND state IN ('present', 'unavailable') AND last_seen_at < :epoch ORDER BY last_seen_at, location_id LIMIT :limit"

    const val PRESENT_IN_SHARE_SQL: String =
      "SELECT * FROM media_location WHERE account_id = :account AND share_key = :shareKey AND state = 'present' " +
        "ORDER BY last_seen_at, location_id LIMIT :limit"
  }
}

@Dao
interface NasShareDao {

  @Upsert
  suspend fun upsert(share: NasShareEntity)

  @Query("SELECT * FROM nas_share")
  suspend fun all(): List<NasShareEntity>
}
