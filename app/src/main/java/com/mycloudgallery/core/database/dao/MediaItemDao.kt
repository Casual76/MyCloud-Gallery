package com.mycloudgallery.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mycloudgallery.core.database.entity.MediaItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {

    // --- Paginazione per galleria principale ---

    @Query("SELECT * FROM media_items WHERE isInTrash = 0 ORDER BY createdAt DESC")
    fun getAllPaged(): PagingSource<Int, MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE isInTrash = 0 AND isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavoritesPaged(): PagingSource<Int, MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE isInTrash = 1 ORDER BY trashedAt DESC")
    fun getTrashPaged(): PagingSource<Int, MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE isInTrash = 0 AND isVideo = :isVideo ORDER BY createdAt DESC")
    fun getByTypePaged(isVideo: Boolean): PagingSource<Int, MediaItemEntity>

    // --- Query per data (timeline) ---

    @Query("""
        SELECT * FROM media_items
        WHERE isInTrash = 0
        AND createdAt BETWEEN :startTime AND :endTime
        ORDER BY createdAt DESC
    """)
    fun getByDateRange(startTime: Long, endTime: Long): Flow<List<MediaItemEntity>>

    // --- Query per GPS (mappa) ---

    @Query("""
        SELECT * FROM media_items
        WHERE isInTrash = 0
        AND exifLatitude IS NOT NULL
        AND exifLongitude IS NOT NULL
        AND exifLatitude BETWEEN :minLat AND :maxLat
        AND exifLongitude BETWEEN :minLng AND :maxLng
        ORDER BY createdAt DESC
    """)
    fun getByGeoBox(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE isInTrash = 0 AND exifLatitude IS NOT NULL ORDER BY createdAt DESC")
    fun getAllWithGps(): Flow<List<MediaItemEntity>>

    // --- CRUD singolo ---

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun getById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE webDavPath = :path")
    suspend fun getByWebDavPath(path: String): MediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Update
    suspend fun update(item: MediaItemEntity)

    // --- Operazioni batch ---

    @Query("UPDATE media_items SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE media_items SET isInTrash = 1, trashedAt = :trashedAt WHERE id = :id")
    suspend fun moveToTrash(id: String, trashedAt: Long = System.currentTimeMillis())

    @Query("UPDATE media_items SET isInTrash = 1, trashedAt = :trashedAt WHERE id IN (:ids)")
    suspend fun moveToTrashBatch(ids: List<String>, trashedAt: Long = System.currentTimeMillis())

    @Query("UPDATE media_items SET isInTrash = 0, trashedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM media_items WHERE isInTrash = 1 AND trashedAt < :cutoffTime")
    suspend fun deleteExpiredTrash(cutoffTime: Long)

    @Query("SELECT COUNT(*) FROM media_items WHERE isInTrash = 1")
    fun getTrashCount(): Flow<Int>

    // --- Indicizzazione (Fase 2) ---

    @Query("SELECT * FROM media_items WHERE isIndexed = 0 AND isInTrash = 0 LIMIT :limit")
    suspend fun getUnindexed(limit: Int): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE isIndexed = 0 AND isInTrash = 0")
    suspend fun getUnindexedCount(): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE isInTrash = 0")
    fun getTotalCount(): Flow<Int>

    // --- Indicizzazione (aggiornamento campi AI/EXIF) ---

    @Query("""
        UPDATE media_items SET
            exifLatitude = :lat, exifLongitude = :lng,
            exifCameraModel = :cameraModel, exifIso = :iso,
            exifFocalLength = :focalLength,
            aiLabels = :labels, aiScenes = :scenes, aiOcrText = :ocrText,
            perceptualHash = :pHash, isIndexed = 1
        WHERE id = :id
    """)
    suspend fun updateIndexedFields(
        id: String,
        lat: Double?, lng: Double?,
        cameraModel: String?, iso: Int?, focalLength: Float?,
        labels: String?, scenes: String?, ocrText: String?,
        pHash: String?,
    )

    // --- Duplicati (Fase 2.4) ---

    @Query("SELECT * FROM media_items WHERE perceptualHash = :hash AND isInTrash = 0")
    suspend fun getByPerceptualHash(hash: String): List<MediaItemEntity>

    @Query("UPDATE media_items SET duplicateGroupId = :groupId WHERE id = :id")
    suspend fun setDuplicateGroup(id: String, groupId: String?)

    @Query("""
        SELECT * FROM media_items
        WHERE duplicateGroupId IS NOT NULL AND isInTrash = 0
        ORDER BY duplicateGroupId, createdAt DESC
    """)
    fun getAllDuplicates(): Flow<List<MediaItemEntity>>

    // --- Ricerca per ID (usata dalla SearchRepository dopo FTS) ---

    @Query("SELECT * FROM media_items WHERE id IN (:ids) AND isInTrash = 0 ORDER BY createdAt DESC")
    suspend fun getByIds(ids: List<String>): List<MediaItemEntity>

    // --- Lista semplice (usata da SearchRepository) ---

    @Query("SELECT * FROM media_items WHERE isInTrash = 0 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 500): List<MediaItemEntity>

    // --- Ricerca LIKE fallback ---

    @Query("""
        SELECT * FROM media_items
        WHERE isInTrash = 0
        AND (fileName LIKE '%' || :query || '%'
             OR aiLabels LIKE '%' || :query || '%'
             OR aiOcrText LIKE '%' || :query || '%')
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchLike(query: String, limit: Int = 200): List<MediaItemEntity>

    // --- Conteggi ---

    @Query("SELECT COUNT(*) FROM media_items WHERE isFavorite = 1 AND isInTrash = 0")
    fun getFavoritesCount(): Flow<Int>

    // --- Sync ---

    @Query("SELECT webDavPath FROM media_items")
    suspend fun getAllWebDavPaths(): List<String>

    @Query("DELETE FROM media_items WHERE webDavPath IN (:paths)")
    suspend fun deleteByWebDavPaths(paths: List<String>)
}
