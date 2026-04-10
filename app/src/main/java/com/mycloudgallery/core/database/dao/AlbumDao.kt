package com.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mycloudgallery.core.database.entity.AlbumEntity
import com.mycloudgallery.core.database.entity.AlbumMediaCrossRef
import com.mycloudgallery.core.database.entity.MediaItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

    @Query("SELECT * FROM albums ORDER BY sortOrder ASC, name ASC")
    fun getAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: String): AlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: AlbumEntity)

    @Update
    suspend fun update(album: AlbumEntity)

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteById(id: String)

    // --- Media in album ---

    @Transaction
    @Query("""
        SELECT mi.* FROM media_items mi
        INNER JOIN album_media_items ami ON mi.id = ami.mediaItemId
        WHERE ami.albumId = :albumId
        ORDER BY mi.createdAt DESC
    """)
    fun getMediaForAlbum(albumId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT COUNT(*) FROM album_media_items WHERE albumId = :albumId")
    fun getMediaCountForAlbum(albumId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMediaToAlbum(crossRef: AlbumMediaCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMediaToAlbumBatch(crossRefs: List<AlbumMediaCrossRef>)

    @Query("DELETE FROM album_media_items WHERE albumId = :albumId AND mediaItemId = :mediaItemId")
    suspend fun removeMediaFromAlbum(albumId: String, mediaItemId: String)

    @Query("DELETE FROM album_media_items WHERE albumId = :albumId AND mediaItemId IN (:mediaItemIds)")
    suspend fun removeMediaFromAlbumBatch(albumId: String, mediaItemIds: List<String>)
}
