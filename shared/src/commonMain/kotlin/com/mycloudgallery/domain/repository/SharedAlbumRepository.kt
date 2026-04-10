package com.mycloudgallery.domain.repository

import com.mycloudgallery.domain.model.SharedAlbum
import kotlinx.coroutines.flow.Flow

interface SharedAlbumRepository {
    fun getAll(): Flow<List<SharedAlbum>>
    suspend fun getById(id: String): SharedAlbum?
    fun getPendingRequestsCount(): Flow<Int>
    suspend fun syncWithNas()
    suspend fun createSharedAlbum(name: String, memberUserIds: List<String>): SharedAlbum
    suspend fun approveRequest(albumId: String, requestId: String)
    suspend fun rejectRequest(albumId: String, requestId: String)
    suspend fun proposeMediaAddition(albumId: String, mediaPaths: List<String>)
}
