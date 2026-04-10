package com.mycloudgallery.domain.repository

import androidx.paging.PagingData
import com.mycloudgallery.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    fun getAllMedia(): Flow<PagingData<MediaItem>>
    fun getFavorites(): Flow<PagingData<MediaItem>>
    fun getTrash(): Flow<PagingData<MediaItem>>
    suspend fun getMediaById(id: String): MediaItem?
    suspend fun toggleFavorite(id: String)
    suspend fun moveToTrash(id: String)
    suspend fun moveToTrashBatch(ids: List<String>)
    suspend fun restoreFromTrash(id: String)
    suspend fun deletePermanently(id: String)
    suspend fun deletePermanentlyBatch(ids: List<String>)
    suspend fun deleteExpiredTrash()
    fun getTrashCount(): Flow<Int>
    fun getTotalCount(): Flow<Int>
}
