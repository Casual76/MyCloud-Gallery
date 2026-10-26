package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.ColumnInfo
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

  /**
   * La coda di una fase **per il lavoro sul telefono**: dal piu' recente al piu' vecchio (proposta 11), solo media con una
   * copia presente sul telefono (`presence`). Dalla v3 (Fase 4) nella coda META ci sono anche i media solo-NAS, che il
   * telefono non sa leggere: senza il filtro 20 mila media del NAS piu' recenti fermavano la META del telefono dopo
   * `metaMaxSkips` salti (§1.3 di `docs/fase4-progetto.md`). La coda del NAS e' [nextPendingOnNas].
   *
   * Piano: si scorre `media` con l'indice (`sort_time`, `media_id`) dal piu' recente e si cerca la fase per chiave
   * primaria (`CROSS JOIN` fissa l'ordine): nessun ordinamento in memoria, e il `LIMIT` ferma la lettura appena basta.
   */
  @Query(NEXT_PENDING_ON_PHONE_SQL)
  suspend fun nextPending(stage: String, limit: Int): List<WorkStateEntity>

  /** La coda di una fase per il lavoro **sul NAS** (copie presenti sul NAS), come [nextPending]. */
  @Query(NEXT_PENDING_ON_NAS_SQL)
  suspend fun nextPendingOnNas(stage: String, limit: Int): List<WorkStateEntity>

  /** I fallimenti da riprovare **sul telefono**, i piu' vecchi prima (indice `stage, status, updated_at`). */
  @Query(RETRYABLE_ON_PHONE_SQL)
  suspend fun retryable(stage: String, retryBefore: Long, limit: Int): List<WorkStateEntity>

  /** I fallimenti da riprovare **sul NAS**, come [retryable]. */
  @Query(RETRYABLE_ON_NAS_SQL)
  suspend fun retryableOnNas(stage: String, retryBefore: Long, limit: Int): List<WorkStateEntity>

  @Query("SELECT status, COUNT(*) AS count FROM work_state WHERE stage = :stage GROUP BY status")
  fun observeCounts(stage: String): Flow<List<StatusCount>>

  companion object {
    private const val ON_PHONE: String = "m.presence IN ('phone_only', 'phone_and_nas')"
    private const val ON_NAS: String = "m.presence IN ('nas_only', 'phone_and_nas')"

    private const val NEXT_PENDING: String =
      "SELECT w.* FROM media m CROSS JOIN work_state w ON w.media_id = m.media_id AND w.stage = :stage " +
        "WHERE w.status = 'pending' AND "
    private const val NEXT_PENDING_ORDER: String = " ORDER BY m.sort_time DESC, m.media_id DESC LIMIT :limit"

    const val NEXT_PENDING_ON_PHONE_SQL: String = NEXT_PENDING + ON_PHONE + NEXT_PENDING_ORDER
    const val NEXT_PENDING_ON_NAS_SQL: String = NEXT_PENDING + ON_NAS + NEXT_PENDING_ORDER

    private const val RETRYABLE: String =
      "SELECT w.* FROM work_state w CROSS JOIN media m ON m.media_id = w.media_id " +
        "WHERE w.stage = :stage AND w.status = 'failed' AND w.updated_at <= :retryBefore AND "
    private const val RETRYABLE_ORDER: String = " ORDER BY w.updated_at LIMIT :limit"

    const val RETRYABLE_ON_PHONE_SQL: String = RETRYABLE + ON_PHONE + RETRYABLE_ORDER
    const val RETRYABLE_ON_NAS_SQL: String = RETRYABLE + ON_NAS + RETRYABLE_ORDER
  }
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

/**
 * Gli album e i loro elementi (v1, estesi dalla v6). Sono tabelle **derivate** (D5): le scrive la proiezione degli album (P3, per i
 * personali) e la replica degli album condivisi (P2), sempre in una transazione (`GalleryDatabase.transaction`); la lettura per le
 * schermate passa da [summaries] e dalle viste di `core-library`. I `MediaId` che contiene seguono le unioni d'identita'
 * ([MergeDao.copyAlbumItems]).
 */
@Dao
interface AlbumDao {

  @Insert
  suspend fun insert(album: AlbumEntity)

  /** Crea o riscrive la riga di un album (la proiezione la riscrive tutta a ogni giro). */
  @Upsert
  suspend fun upsert(album: AlbumEntity)

  @Query("SELECT * FROM album WHERE album_id = :albumId")
  suspend fun get(albumId: String): AlbumEntity?

  @Query("SELECT * FROM album ORDER BY album_id")
  suspend fun all(): List<AlbumEntity>

  /** Tutti gli album (personali e condivisi) che si aggiornano da soli: l'elenco delle Raccolte. */
  @Query("SELECT * FROM album ORDER BY album_id")
  fun observeAll(): Flow<List<AlbumEntity>>

  /** Toglie l'album con tutto cio' che e' suo (elementi, regole, esclusioni, suggeriti: in cascata). */
  @Query("DELETE FROM album WHERE album_id = :albumId")
  suspend fun delete(albumId: String)

  @Upsert
  suspend fun upsertItem(item: AlbumItemEntity)

  @Upsert
  suspend fun upsertItems(items: List<AlbumItemEntity>)

  @Query("DELETE FROM album_item WHERE album_id = :albumId AND media_id = :mediaId")
  suspend fun removeItem(albumId: String, mediaId: String)

  @Query("DELETE FROM album_item WHERE album_id = :albumId")
  suspend fun clearItems(albumId: String)

  /** Toglie gli elementi di un album entrati in un certo modo (`auto`: il ricalcolo di uno smart riparte dai manuali e accettati). */
  @Query("DELETE FROM album_item WHERE album_id = :albumId AND origin = :origin")
  suspend fun clearItemsByOrigin(albumId: String, origin: String)

  @Query("SELECT * FROM album_item WHERE album_id = :albumId ORDER BY position, media_id")
  suspend fun items(albumId: String): List<AlbumItemEntity>

  @Query("SELECT * FROM album_item WHERE album_id = :albumId ORDER BY position, media_id")
  fun observeItems(albumId: String): Flow<List<AlbumItemEntity>>

  @Query("SELECT * FROM album_item WHERE media_id = :mediaId ORDER BY album_id")
  suspend fun itemsOfMedia(mediaId: String): List<AlbumItemEntity>

  /** Gli album personali legati a un album condiviso (voce `shared`, D4): di norma uno. */
  @Query("SELECT * FROM album WHERE shared_id = :sharedId ORDER BY album_id")
  suspend fun linkedTo(sharedId: String): List<AlbumEntity>

  /**
   * Per ogni album: quanti elementi **mostrabili** ha (media visibile, non nascosto, una cella per gruppo: D22, E-H5) e il giorno
   * locale del piu' recente ("Data dei media"). Un album senza elementi mostrabili non compare. Un solo passaggio sulle righe di
   * `album_item`; i nascosti si leggono da `media_personal`, mai da un derivato (D22).
   */
  @Query(SUMMARIES_SQL)
  suspend fun summaries(): List<AlbumSummaryRow>

  companion object {
    const val SUMMARIES_SQL: String =
      "SELECT ai.album_id AS album_id, COUNT(*) AS visible_count, MAX(m.local_day) AS newest_day FROM album_item ai " +
        "JOIN media m ON m.media_id = ai.media_id LEFT JOIN media_personal p ON p.media_id = m.media_id " +
        "WHERE m.presence != 'none' AND COALESCE(p.hidden, 0) = 0 AND (m.cell_flags & ${CellFlags.GROUP_MEMBER}) = 0 AND ${AlbumQueries.NOT_IN_HIDDEN_GROUP} " +
        "GROUP BY ai.album_id"
  }
}

/** Una riga di [AlbumDao.summaries]. */
data class AlbumSummaryRow(
  @ColumnInfo(name = "album_id") val albumId: String,
  @ColumnInfo(name = "visible_count") val visibleCount: Int,
  @ColumnInfo(name = "newest_day") val newestDay: Int?,
)

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

  /** v6: l'elemento passa al sopravvissuto con **tutte** le sue colonne (come e' entrato, l'impronta, chi l'ha aggiunto). */
  @Query(
    "INSERT OR IGNORE INTO album_item (album_id, media_id, position, added_at, origin, fp, added_by) " +
      "SELECT album_id, :survivor, position, added_at, origin, fp, added_by FROM album_item WHERE media_id = :loser",
  )
  suspend fun copyAlbumItems(loser: String, survivor: String)

  @Query("UPDATE album SET cover_media_id = :survivor WHERE cover_media_id = :loser")
  suspend fun repointAlbumCovers(loser: String, survivor: String)

  @Query("UPDATE media_group SET cover_media_id = :survivor WHERE cover_media_id = :loser")
  suspend fun repointGroupCovers(loser: String, survivor: String)

  @Query("UPDATE media_alias SET media_id = :survivor WHERE media_id = :loser")
  suspend fun repointAliases(loser: String, survivor: String)

  // --- v4 (Fase 5): diario del backup, problema, firme e tabelle della 5B ---------------------------------------------------

  @Query("UPDATE backup_item SET media_id = :survivor WHERE media_id = :loser")
  suspend fun repointBackupItemMedia(loser: String, survivor: String)

  @Query("UPDATE backup_item SET version_id = :to WHERE version_id = :from")
  suspend fun repointBackupItemVersion(from: String, to: String)

  /** Un solo problema per media: se il sopravvissuto ne ha gia' uno resta il suo (la riga del perdente se ne va con lui). */
  @Query("UPDATE OR IGNORE backup_problem SET media_id = :survivor WHERE media_id = :loser")
  suspend fun repointBackupProblem(loser: String, survivor: String)

  /** Una sola firma per versione: se la versione di destinazione ha gia' la sua, resta quella (l'altra se ne va con la versione). */
  @Query("UPDATE OR IGNORE visual_signature SET version_id = :to WHERE version_id = :from")
  suspend fun repointSignature(from: String, to: String)

  @Query("UPDATE pending_task SET media_id = :survivor WHERE media_id = :loser")
  suspend fun repointPendingTaskMedia(loser: String, survivor: String)

  @Query("UPDATE pending_task SET version_id = :to WHERE version_id = :from")
  suspend fun repointPendingTaskVersion(from: String, to: String)

  @Query("UPDATE edit_session SET media_id = :survivor WHERE media_id = :loser")
  suspend fun repointEditSessionMedia(loser: String, survivor: String)

  @Query("UPDATE edit_session SET version_id = :to WHERE version_id = :from")
  suspend fun repointEditSessionVersion(from: String, to: String)

  @Query("UPDATE free_space_item SET media_id = :survivor WHERE media_id = :loser")
  suspend fun repointFreeSpaceItemMedia(loser: String, survivor: String)

  @Query("UPDATE free_space_item SET version_id = :to WHERE version_id = :from")
  suspend fun repointFreeSpaceItemVersion(from: String, to: String)

  /** P14: l'attuale da archiviare di un lavoro delle versioni segue la versione (colonna senza chiave esterna). */
  @Query("UPDATE backup_item SET current_version_id = :to WHERE current_version_id = :from")
  suspend fun repointBackupItemCurrent(from: String, to: String)

  /** P14: una sola correzione solo nell'app per versione: se la versione di destinazione ne ha gia' una resta quella. */
  @Query("UPDATE OR IGNORE metadata_correction SET version_id = :to WHERE version_id = :from")
  suspend fun repointMetadataCorrection(from: String, to: String)

  // --- v6 (Fase 7): regole, esclusioni, suggeriti, diario della condivisione e proposte di viaggio --------------------------------

  /** Una foto d'esempio ha una riga per posizione (`album_rule`: la chiave comprende `position`), quindi non c'e' nulla da fondere. */
  @Query("UPDATE album_rule SET media_id = :survivor WHERE media_id = :loser")
  suspend fun repointAlbumRules(loser: String, survivor: String)

  /** Una sola esclusione per (album, media): se il sopravvissuto ce l'ha gia' resta la sua. */
  @Query(
    "INSERT OR IGNORE INTO album_exclusion (album_id, media_id, fp) " +
      "SELECT album_id, :survivor, fp FROM album_exclusion WHERE media_id = :loser",
  )
  suspend fun copyAlbumExclusions(loser: String, survivor: String)

  @Query(
    "INSERT OR IGNORE INTO album_suggestion (album_id, media_id, reason, added_at) " +
      "SELECT album_id, :survivor, reason, added_at FROM album_suggestion WHERE media_id = :loser",
  )
  suspend fun copyAlbumSuggestions(loser: String, survivor: String)

  /** Il diario della condivisione non perde nessun elemento: la chiave comprende l'indice, non il media. */
  @Query("UPDATE album_share_item SET media_id = :survivor WHERE media_id = :loser")
  suspend fun repointAlbumShareItems(loser: String, survivor: String)

  @Query("UPDATE trip_proposal SET cover_media_id = :survivor WHERE cover_media_id = :loser")
  suspend fun repointTripCovers(loser: String, survivor: String)

  /** P14 (E-X3): cambia il tipo e il genitore di una versione (la copia di un editor diventa una versione `edited`). */
  @Query("UPDATE media_version SET kind = :kind, parent_version_id = :parentId WHERE version_id = :versionId")
  suspend fun setVersionKind(versionId: String, kind: String, parentId: String?)
}
