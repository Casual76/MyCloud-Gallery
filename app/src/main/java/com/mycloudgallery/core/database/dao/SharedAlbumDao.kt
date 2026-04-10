package com.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mycloudgallery.core.database.entity.SharedAlbumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedAlbumDao {

    @Query("SELECT * FROM shared_albums ORDER BY createdAt DESC")
    fun getAll(): Flow<List<SharedAlbumEntity>>

    @Query("SELECT * FROM shared_albums WHERE id = :id")
    suspend fun getById(id: String): SharedAlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: SharedAlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(albums: List<SharedAlbumEntity>)

    @Query("DELETE FROM shared_albums WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM shared_albums WHERE id NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<String>)
}
