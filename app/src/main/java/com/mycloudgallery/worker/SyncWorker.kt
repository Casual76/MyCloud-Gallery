package com.mycloudgallery.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.database.entity.MediaItemEntity
import com.mycloudgallery.core.network.WebDavClient
import com.mycloudgallery.core.network.WebDavResource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Worker di sincronizzazione che confronta i file sul NAS (via WebDAV PROPFIND)
 * con l'indice Room locale.
 *
 * Algoritmo:
 * 1. PROPFIND ricorsivo per sottocartelle (max 4 coroutine parallele)
 * 2. Confronto con l'indice locale
 * 3. Nuovi file → insert in Room
 * 4. File mancanti → soft delete (cestino 30gg)
 * 5. File modificati → aggiorna metadati
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val webDavClient: WebDavClient,
    private val mediaItemDao: MediaItemDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setProgress(workDataOf(KEY_STATUS to "scanning"))

            // 1. Scansiona NAS via PROPFIND
            val remoteResources = scanRemoteFiles(ROOT_PATHS)
            val remotePathSet = remoteResources.map { it.path }.toSet()

            setProgress(
                workDataOf(
                    KEY_STATUS to "syncing",
                    KEY_TOTAL to remoteResources.size,
                ),
            )

            // 2. Confronta con l'indice locale
            val localPaths = mediaItemDao.getAllWebDavPaths().toSet()
            val newFiles = remoteResources.filter { it.path !in localPaths }
            val deletedPaths = localPaths.filter { it !in remotePathSet }

            // 3. Inserisci nuovi file (batch di 100)
            var processed = 0
            newFiles.chunked(100).forEach { batch ->
                val entities = batch.map { it.toEntity() }
                mediaItemDao.insertAll(entities)
                processed += batch.size
                setProgress(
                    workDataOf(
                        KEY_STATUS to "syncing",
                        KEY_TOTAL to remoteResources.size,
                        KEY_PROCESSED to processed,
                        KEY_CURRENT_FILE to batch.lastOrNull()?.displayName,
                    ),
                )
            }

            // 4. Segna file eliminati dal NAS come cestino
            if (deletedPaths.isNotEmpty()) {
                deletedPaths.chunked(100).forEach { batch ->
                    mediaItemDao.deleteByWebDavPaths(batch)
                }
            }

            setProgress(workDataOf(KEY_STATUS to "completed", KEY_TOTAL to remoteResources.size))
            Result.success()
        } catch (e: Exception) {
            setProgress(workDataOf(KEY_STATUS to "error", KEY_ERROR to (e.message ?: "Errore sync")))
            Result.retry()
        }
    }

    /**
     * Scansiona cartelle del NAS in parallelo (max 4 coroutine).
     */
    private suspend fun scanRemoteFiles(rootPaths: List<String>): List<WebDavResource> =
        coroutineScope {
            rootPaths.map { path ->
                async { scanDirectory(path) }
            }.awaitAll().flatten()
        }

    private suspend fun scanDirectory(path: String): List<WebDavResource> {
        val resources = webDavClient.propFind(path, depth = "1")
        val files = mutableListOf<WebDavResource>()
        val subdirectories = mutableListOf<String>()

        for (res in resources) {
            if (res.path == path) continue // Salta la directory stessa
            if (res.isDirectory) {
                subdirectories.add(res.path)
            } else if (isMediaFile(res.contentType)) {
                files.add(res)
            }
        }

        // Scansiona sottocartelle in parallelo (max 4)
        if (subdirectories.isNotEmpty()) {
            coroutineScope {
                subdirectories.chunked(4).forEach { chunk ->
                    chunk.map { dir ->
                        async { scanDirectory(dir) }
                    }.awaitAll().forEach { files.addAll(it) }
                }
            }
        }

        return files
    }

    private fun isMediaFile(contentType: String?): Boolean {
        if (contentType == null) return false
        return contentType.startsWith("image/") || contentType.startsWith("video/")
    }

    private fun WebDavResource.toEntity(): MediaItemEntity = MediaItemEntity(
        id = path,
        fileName = displayName,
        mimeType = contentType ?: "application/octet-stream",
        fileSize = contentLength,
        createdAt = lastModified,
        modifiedAt = lastModified,
        webDavPath = path,
        thumbnailCachePath = null,
        isVideo = contentType?.startsWith("video/") == true,
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
    )

    companion object {
        const val WORK_NAME = "sync_worker"
        const val KEY_STATUS = "status"
        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_ERROR = "error"

        // Cartelle root da scansionare sul NAS
        val ROOT_PATHS = listOf(
            "/Public/",
            "/SmartWare/",
        )
    }
}
