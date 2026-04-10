package com.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mycloudgallery.core.database.entity.MediaFtsEntity

@Dao
interface MediaFtsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaFtsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaFtsEntity>)

    /**
     * Ricerca full-text — restituisce gli ID dei media che matchano la query.
     * Supporta operatori FTS: AND, OR, NOT, prefisso con *.
     */
    @Query("SELECT itemId FROM media_fts WHERE media_fts MATCH :query LIMIT :limit")
    suspend fun searchIds(query: String, limit: Int = 200): List<String>

    @Query("DELETE FROM media_fts WHERE itemId = :itemId")
    suspend fun deleteByItemId(itemId: String)

    @Query("DELETE FROM media_fts")
    suspend fun deleteAll()
}
