package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.EmbeddingEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.database.entity.StatusCount
import dev.pampa.mycloudgallery.core.database.entity.TimelineItem
import dev.pampa.mycloudgallery.core.database.entity.WorkStateEntity
import dev.pampa.mycloudgallery.core.model.CellFlags
import kotlinx.coroutines.flow.Flow

/**
 * La timeline, pronta per la paginazione **a chiave** (keyset): ogni pagina riparte dall'ultima cella
 * vista (`sort_time`, `media_id`), quindi costa uguale alla prima pagina e alla millesima, e un media
 * che arriva nel frattempo non fa saltare o ripetere celle. E' la forma che un `PagingSource` (Paging 3,
 * quando entrera' nelle dipendenze) usera' cosi' com'e'.
 *
 * Visibile = in almeno un posto raggiungibile e non nascosto (E-H1) e, dalla v2, non dietro la copertina
 * del suo gruppo (D20), come nell'indice della Fase 3 ([TimelineIndexDao]). L'ordine e' dal piu' recente.
 */
@Dao
interface TimelineDao {

  @Query("$SELECT_ITEM WHERE $VISIBLE ORDER BY m.sort_time DESC, m.media_id DESC LIMIT :limit")
  fun observeFirstPage(limit: Int): Flow<List<TimelineItem>>

  /** La pagina dopo la cella (sortTime, mediaId), verso il passato. */
  @Query(PAGE_BEFORE)
  suspend fun pageBefore(sortTime: Long, mediaId: String, limit: Int): List<TimelineItem>

  /** La pagina prima della cella, verso il presente (scrubber, "torna qui"): in ordine crescente. */
  @Query(
    "$SELECT_ITEM WHERE $VISIBLE AND m.sort_time >= :sortTime AND (m.sort_time > :sortTime OR m.media_id > :mediaId) " +
      "ORDER BY m.sort_time ASC, m.media_id ASC LIMIT :limit",
  )
  suspend fun pageAfter(sortTime: Long, mediaId: String, limit: Int): List<TimelineItem>

  /** Filtro rapido (screenshot, WhatsApp: piano 3.3), con la stessa paginazione. */
  @Query(
    "$SELECT_ITEM WHERE $VISIBLE AND m.source = :source AND m.sort_time <= :sortTime " +
      "AND (m.sort_time < :sortTime OR m.media_id < :mediaId) ORDER BY m.sort_time DESC, m.media_id DESC LIMIT :limit",
  )
  suspend fun pageBeforeForSource(source: String, sortTime: Long, mediaId: String, limit: Int): List<TimelineItem>

  @Query("SELECT COUNT(*) FROM media m LEFT JOIN media_personal p ON p.media_id = m.media_id WHERE $VISIBLE")
  fun observeVisibleCount(): Flow<Int>

  companion object {
    /** La cursore iniziale: piu' recente di qualunque data plausibile. */
    const val START_SORT_TIME: Long = Long.MAX_VALUE
    const val START_MEDIA_ID: String = "~"

    private const val SELECT_ITEM: String =
      "SELECT m.media_id, m.sort_time, m.kind, m.source, m.presence, m.safety, m.group_id, " +
        "COALESCE(p.favorite, 0) AS favorite FROM media m LEFT JOIN media_personal p ON p.media_id = m.media_id"
    private const val VISIBLE: String =
      "m.presence != 'none' AND COALESCE(p.hidden, 0) = 0 AND (m.cell_flags & ${CellFlags.GROUP_MEMBER}) = 0"

    /** Testo della pagina verso il passato: pubblico per il test che ne controlla il piano di esecuzione. */
    internal const val PAGE_BEFORE: String =
      "$SELECT_ITEM WHERE $VISIBLE AND m.sort_time <= :sortTime AND (m.sort_time < :sortTime OR m.media_id < :mediaId) " +
        "ORDER BY m.sort_time DESC, m.media_id DESC LIMIT :limit"
  }
}

@Dao
interface WorkStateDao {

  @Upsert
  suspend fun upsert(states: List<WorkStateEntity>)

  @Query("SELECT * FROM work_state WHERE media_id = :mediaId ORDER BY stage")
  suspend fun forMedia(mediaId: String): List<WorkStateEntity>

  @Query("SELECT status FROM work_state WHERE media_id = :mediaId AND stage = :stage")
  suspend fun status(mediaId: String, stage: String): String?

  /** La coda di una fase: dal piu' recente al piu' vecchio (proposta 11). */
  @Query(
    "SELECT w.* FROM work_state w JOIN media m ON m.media_id = w.media_id " +
      "WHERE w.stage = :stage AND w.status = 'pending' ORDER BY m.sort_time DESC, m.media_id DESC LIMIT :limit",
  )
  suspend fun nextPending(stage: String, limit: Int): List<WorkStateEntity>

  /** I fallimenti da riprovare, i piu' vecchi prima. */
  @Query(
    "SELECT * FROM work_state WHERE stage = :stage AND status = 'failed' AND updated_at <= :retryBefore " +
      "ORDER BY updated_at LIMIT :limit",
  )
  suspend fun retryable(stage: String, retryBefore: Long, limit: Int): List<WorkStateEntity>

  @Query("SELECT status, COUNT(*) AS count FROM work_state WHERE stage = :stage GROUP BY status")
  fun observeCounts(stage: String): Flow<List<StatusCount>>
}

@Dao
interface EmbeddingDao {

  @Upsert
  suspend fun upsert(embedding: EmbeddingEntity)

  @Query("SELECT * FROM embedding WHERE media_id = :mediaId ORDER BY space_id, dimension, precision, vector_index")
  suspend fun forMedia(mediaId: String): List<EmbeddingEntity>

  /**
   * Una pagina dei vettori di **uno** spazio (modello + dimensione + precisione: E-I4), a chiave
   * (`media_id`, `vector_index`). La ricerca della Fase 6 scorre cosi' tutto lo spazio.
   */
  @Query(
    "SELECT * FROM embedding WHERE space_id = :spaceId AND dimension = :dimension AND precision = :precision " +
      "AND (media_id > :afterMediaId OR (media_id = :afterMediaId AND vector_index > :afterIndex)) " +
      "ORDER BY media_id, vector_index LIMIT :limit",
  )
  suspend fun scan(spaceId: String, dimension: Int, precision: String, afterMediaId: String, afterIndex: Int, limit: Int): List<EmbeddingEntity>

  @Query("SELECT COUNT(*) FROM embedding WHERE space_id = :spaceId AND dimension = :dimension AND precision = :precision")
  suspend fun count(spaceId: String, dimension: Int, precision: String): Int
}

@Dao
interface PersonalDao {

  @Query("SELECT * FROM media_personal WHERE media_id = :mediaId")
  suspend fun get(mediaId: String): MediaPersonalEntity?

  @Upsert
  suspend fun upsert(flags: MediaPersonalEntity)

  @Query("SELECT media_id FROM media_personal WHERE favorite = 1 ORDER BY media_id")
  fun observeFavoriteIds(): Flow<List<String>>
}

@Dao
interface AlbumDao {

  @Insert
  suspend fun insert(album: AlbumEntity)

  @Query("SELECT * FROM album WHERE album_id = :albumId")
  suspend fun get(albumId: String): AlbumEntity?

  @Upsert
  suspend fun upsertItem(item: AlbumItemEntity)

  @Query("DELETE FROM album_item WHERE album_id = :albumId AND media_id = :mediaId")
  suspend fun removeItem(albumId: String, mediaId: String)

  @Query("SELECT * FROM album_item WHERE album_id = :albumId ORDER BY position, media_id")
  suspend fun items(albumId: String): List<AlbumItemEntity>

  @Query("SELECT * FROM album_item WHERE album_id = :albumId ORDER BY position, media_id")
  fun observeItems(albumId: String): Flow<List<AlbumItemEntity>>

  @Query("SELECT * FROM album_item WHERE media_id = :mediaId ORDER BY album_id")
  suspend fun itemsOfMedia(mediaId: String): List<AlbumItemEntity>
}

/**
 * Le istruzioni dell'unione di due media (`MediaCatalog.merge`): spostano ogni riferimento dal
 * perdente al sopravvissuto **prima** che il perdente venga cancellato, perche' le chiavi esterne
 * in cascata cancellerebbero in silenzio album, preferiti e vettori. Il test `MergeReferencesTest`
 * fallisce se una tabella nuova punta a `media` senza essere gestita qui.
 */
@Dao
interface MergeDao {

  @Query("UPDATE media_location SET version_id = :to WHERE version_id = :from")
  suspend fun repointLocations(from: String, to: String)

  @Query("UPDATE work_state SET version_id = :to WHERE version_id = :from")
  suspend fun repointWorkVersion(from: String, to: String)

  @Query("UPDATE embedding SET version_id = :to WHERE version_id = :from")
  suspend fun repointEmbeddingVersion(from: String, to: String)

  @Query("UPDATE media_version SET parent_version_id = :to WHERE parent_version_id = :from")
  suspend fun repointParents(from: String, to: String)

  @Query("UPDATE media_version SET media_id = :mediaId, parent_version_id = :parentId WHERE version_id = :versionId")
  suspend fun moveVersion(versionId: String, mediaId: String, parentId: String?)

  @Query(
    "INSERT OR IGNORE INTO album_item (album_id, media_id, position, added_at) " +
      "SELECT album_id, :survivor, position, added_at FROM album_item WHERE media_id = :loser",
  )
  suspend fun copyAlbumItems(loser: String, survivor: String)

  @Query("UPDATE album SET cover_media_id = :survivor WHERE cover_media_id = :loser")
  suspend fun repointAlbumCovers(loser: String, survivor: String)

  @Query("UPDATE media_group SET cover_media_id = :survivor WHERE cover_media_id = :loser")
  suspend fun repointGroupCovers(loser: String, survivor: String)

  @Query("UPDATE media_alias SET media_id = :survivor WHERE media_id = :loser")
  suspend fun repointAliases(loser: String, survivor: String)
}
