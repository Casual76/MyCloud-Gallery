package com.mycloudgallery.worker

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mycloudgallery.core.network.WebDavClient
import com.mycloudgallery.core.security.TokenManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Worker che osserva MediaStore per nuove foto/video e li carica sul NAS.
 *
 * Upload path: /Users/{username}/Camera/YYYY/MM/filename
 */
@HiltWorker
class CameraUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val webDavClient: WebDavClient,
    private val tokenManager: TokenManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val lastUploadTime = inputData.getLong(KEY_LAST_UPLOAD_TIME, 0L)
            val username = tokenManager.username ?: return@withContext Result.failure()

            // Query MediaStore per nuove immagini
            val newMedia = queryNewMedia(lastUploadTime)
            if (newMedia.isEmpty()) return@withContext Result.success()

            setProgress(
                workDataOf(
                    KEY_STATUS to "uploading",
                    KEY_TOTAL to newMedia.size,
                    KEY_PROCESSED to 0,
                ),
            )

            var uploaded = 0
            for (media in newMedia) {
                try {
                    val remotePath = buildRemotePath(username, media)

                    // Crea cartelle se necessario
                    val parentDir = remotePath.substringBeforeLast("/") + "/"
                    ensureDirectoryExists(parentDir)

                    // Upload del file
                    val inputStream = applicationContext.contentResolver.openInputStream(media.uri)
                        ?: continue
                    inputStream.use { stream ->
                        webDavClient.put(remotePath, stream, media.mimeType, media.size)
                    }

                    uploaded++
                    setProgress(
                        workDataOf(
                            KEY_STATUS to "uploading",
                            KEY_TOTAL to newMedia.size,
                            KEY_PROCESSED to uploaded,
                            KEY_CURRENT_FILE to media.displayName,
                        ),
                    )
                } catch (_: Exception) {
                    // Salta file falliti, verranno ritentati
                }
            }

            Result.success(workDataOf(KEY_LAST_UPLOAD_TIME to System.currentTimeMillis()))
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun queryNewMedia(sinceTimestamp: Long): List<LocalMedia> {
        val result = mutableListOf<LocalMedia>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ?"
        val selectionArgs = arrayOf((sinceTimestamp / 1000).toString())
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} ASC"

        // Query immagini
        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                result.add(
                    LocalMedia(
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)),
                        mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)),
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                        dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) * 1000,
                    ),
                )
            }
        }

        // Query video
        applicationContext.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                result.add(
                    LocalMedia(
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)),
                        mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)),
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                        dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) * 1000,
                    ),
                )
            }
        }

        return result
    }

    private fun buildRemotePath(username: String, media: LocalMedia): String {
        val dateFormat = SimpleDateFormat("yyyy/MM", Locale.US)
        val datePath = dateFormat.format(Date(media.dateAdded))
        return "/Users/$username/Camera/$datePath/${media.displayName}"
    }

    private suspend fun ensureDirectoryExists(path: String) {
        val parts = path.trimEnd('/').split("/").filter { it.isNotEmpty() }
        var current = "/"
        for (part in parts) {
            current += "$part/"
            try {
                webDavClient.mkcol(current)
            } catch (_: Exception) {
                // La cartella esiste già — ok
            }
        }
    }

    private data class LocalMedia(
        val uri: android.net.Uri,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val dateAdded: Long,
    )

    companion object {
        const val WORK_NAME = "camera_upload_worker"
        const val KEY_STATUS = "status"
        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_LAST_UPLOAD_TIME = "last_upload_time"
    }
}
