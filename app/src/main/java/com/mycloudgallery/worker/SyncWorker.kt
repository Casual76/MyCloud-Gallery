package com.mycloudgallery.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.database.entity.MediaItemEntity
import com.mycloudgallery.core.network.SmbClientImpl
import com.mycloudgallery.core.network.WebDavResource
import com.mycloudgallery.core.security.TokenManager
import com.mycloudgallery.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Worker di sincronizzazione che confronta i file sul NAS (via SMB)
 * con l'indice Room locale.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val smbClient: SmbClientImpl,
    private val webDavClient: com.mycloudgallery.core.network.WebDavClient,
    private val networkDetector: com.mycloudgallery.core.network.NetworkDetector,
    private val mediaItemDao: MediaItemDao,
    private val tokenManager: TokenManager,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mode = networkDetector.networkMode.value
        if (mode == com.mycloudgallery.domain.model.NetworkMode.OFFLINE) {
            return@withContext Result.retry()
        }

        try {
            setProgress(workDataOf(KEY_STATUS to "scanning"))

            val rootPaths = mutableListOf("/Public/")
            tokenManager.username?.let { rootPaths.add("/$it/") }

            // 1. Carica metadati locali per confronto differenziale
            val localMetadata = mediaItemDao.getAllSyncMetadata().associateBy { it.webDavPath }
            val localPaths = localMetadata.keys.toSet()
            val remotePathSet = mutableSetOf<String>()

            val newEntities = mutableListOf<MediaItemEntity>()
            val updatedEntities = mutableListOf<MediaItemEntity>()
            var scannedCount = 0

            // 2. Scansione streaming via Channel
            val resourceChannel = Channel<WebDavResource>(capacity = 256)
            
            coroutineScope {
                // Produttore: scansiona NAS (SMB se locale, WebDAV se remoto)
                val scanJob = launch {
                    try {
                        if (mode == com.mycloudgallery.domain.model.NetworkMode.LOCAL) {
                            smbClient.withPersistentSession { session ->
                                scanRemoteFilesStreaming(session, rootPaths, resourceChannel)
                            }
                        } else {
                            scanRemoteFilesStreamingWebDav(rootPaths, resourceChannel)
                        }
                    } finally {
                        resourceChannel.close()
                    }
                }

                // Consumatore: confronta e prepara batch Room
                for (res in resourceChannel) {
                    remotePathSet.add(res.path)
                    scannedCount++

                    val local = localMetadata[res.path]
                    if (local == null) {
                        newEntities.add(res.toEntity())
                    } else if (local.modifiedAt != res.lastModified || local.fileSize != res.contentLength) {
                        // File modificato: aggiorna e resetta l'indicizzazione
                        updatedEntities.add(res.toEntity(local))
                    }

                    // Flush batch ogni 100 per risparmiare memoria e dare feedback
                    if (newEntities.size >= 100 || updatedEntities.size >= 100) {
                        flushBatches(newEntities, updatedEntities)
                        setProgress(workDataOf(
                            KEY_STATUS to "syncing",
                            KEY_TOTAL to scannedCount,
                            KEY_PROCESSED to scannedCount,
                            KEY_CURRENT_FILE to res.displayName,
                        ))
                    }
                }
                scanJob.join()
            }

            // Flush finale
            flushBatches(newEntities, updatedEntities)

            // 3. Gestisci file eliminati (sposta in cestino)
            val deletedPaths = localPaths.filter { it !in remotePathSet }
            if (deletedPaths.isNotEmpty()) {
                val trashedAt = System.currentTimeMillis()
                deletedPaths.chunked(100).forEach { batch ->
                    mediaItemDao.moveToTrashByWebDavPaths(batch, trashedAt)
                }
            }

            settingsRepository.setSyncResult(
                System.currentTimeMillis(),
                "Completata: $scannedCount file trovati"
            )
            setProgress(workDataOf(KEY_STATUS to "completed", KEY_TOTAL to scannedCount))
            Result.success()
        } catch (e: Exception) {
            settingsRepository.setSyncResult(
                System.currentTimeMillis(),
                "Errore: ${e.message ?: "Errore sconosciuto"}"
            )
            setProgress(workDataOf(KEY_STATUS to "error", KEY_ERROR to (e.message ?: "Errore sync")))
            Result.retry()
        }
    }

    private suspend fun flushBatches(
        newEntities: MutableList<MediaItemEntity>,
        updatedEntities: MutableList<MediaItemEntity>
    ) {
        if (newEntities.isNotEmpty()) {
            mediaItemDao.insertAll(newEntities.toList())
            newEntities.clear()
        }
        if (updatedEntities.isNotEmpty()) {
            mediaItemDao.insertAll(updatedEntities.toList()) // insertAll usa REPLACE, ok anche per update
            updatedEntities.clear()
        }
    }

    private suspend fun scanRemoteFilesStreaming(
        session: SmbClientImpl.PersistentSmbSession,
        rootPaths: List<String>,
        channel: Channel<WebDavResource>
    ) = coroutineScope {
        rootPaths.forEach { path ->
            launch { scanDirectoryStreaming(session, path, channel) }
        }
    }

    private suspend fun scanRemoteFilesStreamingWebDav(
        rootPaths: List<String>,
        channel: Channel<WebDavResource>
    ) = coroutineScope {
        rootPaths.forEach { path ->
            launch { scanDirectoryStreamingWebDav(path, channel) }
        }
    }

    private suspend fun scanDirectoryStreaming(
        session: SmbClientImpl.PersistentSmbSession,
        path: String,
        channel: Channel<WebDavResource>
    ) {
        val resources = try {
            session.list(path)
        } catch (e: Exception) {
            emptyList()
        }
        
        val subdirectories = mutableListOf<String>()

        val normalizedCurrentPath = path.trimEnd('/')
        for (res in resources) {
            val normalizedResPath = res.path.trimEnd('/')
            if (normalizedResPath == normalizedCurrentPath) continue
            
            if (res.displayName.startsWith(".")) continue
            
            if (res.isDirectory) {
                subdirectories.add(res.path)
            } else if (isMediaFile(res.contentType, res.displayName)) {
                channel.send(res)
            }
        }

        // Parallelizza sottocartelle
        if (subdirectories.isNotEmpty()) {
            coroutineScope {
                subdirectories.forEach { dir ->
                    launch { scanDirectoryStreaming(session, dir, channel) }
                }
            }
        }
    }

    private suspend fun scanDirectoryStreamingWebDav(
        path: String,
        channel: Channel<WebDavResource>
    ) {
        val resources = try {
            webDavClient.propFind(path, "1")
        } catch (e: Exception) {
            emptyList()
        }
        
        val subdirectories = mutableListOf<String>()

        val normalizedCurrentPath = path.trimEnd('/')
        for (res in resources) {
            val normalizedResPath = res.path.trimEnd('/')
            if (normalizedResPath == normalizedCurrentPath) continue
            
            if (res.displayName.startsWith(".")) continue
            
            if (res.isDirectory) {
                subdirectories.add(res.path)
            } else if (isMediaFile(res.contentType, res.displayName)) {
                channel.send(res)
            }
        }

        // Parallelizza sottocartelle
        if (subdirectories.isNotEmpty()) {
            coroutineScope {
                subdirectories.forEach { dir ->
                    launch { scanDirectoryStreamingWebDav(dir, channel) }
                }
            }
        }
    }

    private fun isMediaFile(contentType: String?, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mediaExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "mp4", "mkv", "mov", "avi")
        if (mediaExtensions.contains(extension)) return true
        
        val mime = contentType?.lowercase() ?: return false
        return mime.startsWith("image/") || mime.startsWith("video/") || mime == "application/octet-stream"
    }

    private fun WebDavResource.toEntity(local: MediaItemDao.SyncMetadata? = null): MediaItemEntity {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val isVideoFile = contentType?.startsWith("video/") == true || 
                setOf("mp4", "mkv", "mov", "avi").contains(extension)
        
        return MediaItemEntity(
            id = path,
            fileName = displayName,
            mimeType = contentType ?: "application/octet-stream",
            fileSize = contentLength,
            createdAt = local?.createdAt ?: lastModified,
            modifiedAt = lastModified,
            webDavPath = path,
            thumbnailCachePath = null, // Reset thumbnails on change or new file
            isVideo = isVideoFile,
            videoDuration = null,
            width = null,
            height = null,
            exifLatitude = null,
            exifLongitude = null,
            exifCameraModel = null,
            exifIso = null,
            exifFocalLength = null,
            aiLabels = null,
            aiScenes = null,
            aiOcrText = null,
            isIndexed = false, // Always (re)index new or modified files
            isInTrash = false,
            trashedAt = null,
            isFavorite = local?.isFavorite ?: false,
            perceptualHash = null,
            duplicateGroupId = null,
        )
    }

    companion object {
        const val WORK_NAME = "sync_worker"
        const val KEY_STATUS = "status"
        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_ERROR = "error"
    }
}
