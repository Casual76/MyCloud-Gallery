package com.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mycloudgallery.core.database.entity.FaceEmbeddingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(face: FaceEmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(faces: List<FaceEmbeddingEntity>)

    @Query("SELECT * FROM face_embeddings WHERE mediaItemId = :mediaId")
    suspend fun getByMediaItem(mediaId: String): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM face_embeddings WHERE personId IS NULL")
    suspend fun getUnclusteredFaces(): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM face_embeddings WHERE personId = :personId")
    fun getFacesByPerson(personId: String): Flow<List<FaceEmbeddingEntity>>

    @Query("UPDATE face_embeddings SET personId = :personId WHERE id = :faceId")
    suspend fun assignPerson(faceId: String, personId: String)

    @Query("DELETE FROM face_embeddings WHERE mediaItemId = :mediaId")
    suspend fun deleteByMediaItem(mediaId: String)
}
