package com.mycloudgallery.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.network.WebDavClient
import com.mycloudgallery.core.util.toDomain
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val webDavClient: WebDavClient,
) : MediaRepository {

    override fun getAllMedia(): Flow<PagingData<MediaItem>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            enablePlaceholders = false,
        ),
    ) { mediaItemDao.getAllPaged() }
        .flow
        .map { pagingData -> pagingData.map { it.toDomain() } }

    override fun getFavorites(): Flow<PagingData<MediaItem>> = Pager(
        config = PagingConfig(pageSize = 60, prefetchDistance = 30),
    ) { mediaItemDao.getFavoritesPaged() }
        .flow
        .map { pagingData -> pagingData.map { it.toDomain() } }

    override fun getTrash(): Flow<PagingData<MediaItem>> = Pager(
        config = PagingConfig(pageSize = 60, prefetchDistance = 30),
    ) { mediaItemDao.getTrashPaged() }
        .flow
        .map { pagingData -> pagingData.map { it.toDomain() } }

    override suspend fun getMediaById(id: String): MediaItem? =
        mediaItemDao.getById(id)?.toDomain()

    override suspend fun toggleFavorite(id: String) {
        val item = mediaItemDao.getById(id) ?: return
        mediaItemDao.setFavorite(id, !item.isFavorite)
    }

    override suspend fun moveToTrash(id: String) {
        mediaItemDao.moveToTrash(id)
        // TODO: WebDAV MOVE verso cartella /Trash/ del NAS
    }

    override suspend fun moveToTrashBatch(ids: List<String>) {
        mediaItemDao.moveToTrashBatch(ids)
    }

    override suspend fun restoreFromTrash(id: String) {
        mediaItemDao.restoreFromTrash(id)
        // TODO: WebDAV COPY da /Trash/ alla cartella originale
    }

    override suspend fun deletePermanently(id: String) {
        val item = mediaItemDao.getById(id) ?: return
        try {
            webDavClient.delete(item.webDavPath)
        } catch (_: Exception) {
            // Offline — eliminazione WebDAV verrà ritentata alla prossima sync
        }
        mediaItemDao.deleteById(id)
    }

    override suspend fun deletePermanentlyBatch(ids: List<String>) {
        mediaItemDao.deleteByIds(ids)
    }

    override suspend fun deleteExpiredTrash() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        mediaItemDao.deleteExpiredTrash(thirtyDaysAgo)
    }

    override fun getTrashCount(): Flow<Int> = mediaItemDao.getTrashCount()

    override fun getTotalCount(): Flow<Int> = mediaItemDao.getTotalCount()
}
