package com.mycloudgallery.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mycloudgallery.core.database.entity.PersonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonEntity)

    @Update
    suspend fun update(person: PersonEntity)

    @Query("SELECT * FROM persons ORDER BY photoCount DESC")
    fun getAll(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun getById(id: String): PersonEntity?

    @Query("UPDATE persons SET photoCount = photoCount + 1 WHERE id = :id")
    suspend fun incrementPhotoCount(id: String)

    @Query("UPDATE persons SET name = :name WHERE id = :id")
    suspend fun setName(id: String, name: String)

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun deleteById(id: String)
}
