package com.mycloudgallery.domain.repository

import com.mycloudgallery.domain.model.Album
import com.mycloudgallery.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

interface AlbumRepository {
    fun getAll(): Flow<List<Album>>
    fun getMediaForAlbum(albumId: String): Flow<List<MediaItem>>
    fun getFavoritesCount(): Flow<Int>
    suspend fun createAlbum(name: String): Album
    suspend fun renameAlbum(id: String, newName: String)
    suspend fun deleteAlbum(id: String)
    suspend fun addMediaToAlbum(albumId: String, mediaIds: List<String>)
    suspend fun removeMediaFromAlbum(albumId: String, mediaIds: List<String>)
    suspend fun updateSortOrder(id: String, newSortOrder: Int)
    suspend fun updateCover(albumId: String, mediaId: String)
}
