package com.mycloudgallery.core.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ModelInfo(
    val fileName: String,
    val downloadUrl: String,
    val sha256: String?,
)

enum class DownloadState { IDLE, DOWNLOADING, COMPLETED, FAILED }

data class ModelDownloadStatus(
    val state: DownloadState = DownloadState.IDLE,
    val progressPercent: Int = 0,
    val errorMessage: String? = null,
)

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        val MOBILE_FACENET = ModelInfo(
            fileName = "mobile_facenet.tflite",
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/mobile_facenet/mobile_facenet.tflite",
            sha256 = null,
        )
        val GEMMA_4 = ModelInfo(
            fileName = "gemma4.task",
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/gemma/gemma4.task",
            sha256 = null,
        )
    }

    private val modelsDir: File = File(context.filesDir, "models").also { it.mkdirs() }

    private val _statuses = MutableStateFlow<Map<String, ModelDownloadStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ModelDownloadStatus>> = _statuses.asStateFlow()

    fun modelFile(info: ModelInfo): File = File(modelsDir, info.fileName)

    fun isDownloaded(info: ModelInfo): Boolean = modelFile(info).exists()

    fun download(info: ModelInfo) {
        if (isDownloaded(info)) {
            updateStatus(info, ModelDownloadStatus(DownloadState.COMPLETED, 100))
            return
        }
        updateStatus(info, ModelDownloadStatus(DownloadState.DOWNLOADING))
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
                .setTitle("Download modello AI: ${info.fileName}")
                .setDescription("MyCloud Gallery — modello AI in download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(modelFile(info)))
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)
            dm.enqueue(request)
        } catch (e: Exception) {
            updateStatus(info, ModelDownloadStatus(DownloadState.FAILED, 0, e.message))
        }
    }

    fun verifyIntegrity(info: ModelInfo): Boolean {
        val expectedSha = info.sha256 ?: return true
        val file = modelFile(info)
        if (!file.exists()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        return actualSha.equals(expectedSha, ignoreCase = true)
    }

    private fun updateStatus(info: ModelInfo, status: ModelDownloadStatus) {
        _statuses.value = _statuses.value + (info.fileName to status)
    }
}
