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
            pageSize = 80,
            prefetchDistance = 40,
            enablePlaceholders = true, // Placeholders help with fast scrolling
            initialLoadSize = 100,
        ),
    ) { mediaItemDao.getAllPaged() }
        .flow
        .map { pagingData -> pagingData.map { it.toDomain() } }

    override fun getFavorites(): Flow<PagingData<MediaItem>> = Pager(
        config = PagingConfig(pageSize = 80, prefetchDistance = 40),
    ) { mediaItemDao.getFavoritesPaged() }
        .flow
        .map { pagingData -> pagingData.map { it.toDomain() } }

    override fun getFavoritesList(): Flow<List<MediaItem>> =
        mediaItemDao.getFavoritesList().map { list -> list.map { it.toDomain() } }

    override fun getTrash(): Flow<PagingData<MediaItem>> = Pager(
        config = PagingConfig(pageSize = 80, prefetchDistance = 40),
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
        val item = mediaItemDao.getById(id) ?: return
        mediaItemDao.moveToTrash(id)
        
        // Tentativo di spostamento sul NAS in una cartella nascosta .trash
        try {
            val fileName = item.webDavPath.substringAfterLast('/')
            val parentDir = item.webDavPath.substringBeforeLast('/', "")
            val trashDir = if (parentDir.isEmpty()) "/.trash" else "$parentDir/.trash"
            
            try { webDavClient.mkcol(trashDir) } catch (_: Exception) {}
            webDavClient.move(item.webDavPath, "$trashDir/$fileName")
        } catch (_: Exception) {
            // Se fallisce (es. offline), l'item rimane segnato come isInTrash localmente
            // e verrà riconciliato alla prossima sync o rimosso se non più presente.
        }
    }

    override suspend fun moveToTrashBatch(ids: List<String>) {
        mediaItemDao.moveToTrashBatch(ids)
        // Batch move sul NAS non è banale via WebDAV standard (richiede loop)
        // Lo lasciamo gestire alla sync di riconciliazione se necessario, 
        // o implementiamo un loop qui per piccoli batch.
        ids.forEach { id ->
            val item = mediaItemDao.getById(id) ?: return@forEach
            try {
                val fileName = item.webDavPath.substringAfterLast('/')
                val parentDir = item.webDavPath.substringBeforeLast('/', "")
                val trashDir = if (parentDir.isEmpty()) "/.trash" else "$parentDir/.trash"
                try { webDavClient.mkcol(trashDir) } catch (_: Exception) {}
                webDavClient.move(item.webDavPath, "$trashDir/$fileName")
            } catch (_: Exception) {}
        }
    }

    override suspend fun restoreFromTrash(id: String) {
        val item = mediaItemDao.getById(id) ?: return
        mediaItemDao.restoreFromTrash(id)
        
        // Tenta di riportare fuori dal .trash sul NAS
        try {
            if (item.webDavPath.contains("/.trash/")) {
                val restoredPath = item.webDavPath.replace("/.trash/", "/")
                webDavClient.move(item.webDavPath, restoredPath)
            }
        } catch (_: Exception) {}
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
