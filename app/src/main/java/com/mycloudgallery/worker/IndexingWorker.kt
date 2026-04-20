package com.mycloudgallery.worker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.PowerManager
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mycloudgallery.core.ai.FaceRecognitionPipeline
import com.mycloudgallery.core.ai.GeminiNanoProvider
import com.mycloudgallery.core.ai.Gemma4Provider
import com.mycloudgallery.core.database.dao.MediaFtsDao
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.database.entity.MediaFtsEntity
import com.mycloudgallery.core.database.entity.MediaItemEntity
import com.mycloudgallery.core.network.NetworkDetector
import com.mycloudgallery.core.network.WebDavClient
import com.mycloudgallery.domain.model.NetworkMode
import com.mycloudgallery.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Worker che indicizza in background ogni MediaItem non ancora analizzato.
 */
@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val mediaItemDao: MediaItemDao,
    private val mediaFtsDao: MediaFtsDao,
    private val webDavClient: WebDavClient,
    private val networkDetector: NetworkDetector,
    private val geminiNanoProvider: GeminiNanoProvider,
    private val gemma4Provider: Gemma4Provider,
    private val faceRecognitionPipeline: FaceRecognitionPipeline,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "indexing_worker"
        const val KEY_TOTAL = "total"
        const val KEY_INDEXED = "indexed"
        const val KEY_CURRENT_FILE = "currentFile"
        const val KEY_ESTIMATED_MINUTES = "estimatedMinutes"

        private const val BATCH_SIZE = 10
        private const val THUMBNAIL_SIZE = 512
        private const val OCR_MAX_CHARS = 500
        private const val PHASH_DIM = 8
    }

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    // Cache in-memory degli hash per velocizzare la detezione duplicati
    private val activeHashes = mutableListOf<Triple<String, ULong, Long>>() // id, hash, createdAt

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (networkDetector.networkMode.value == NetworkMode.OFFLINE) {
            return@withContext Result.retry()
        }

        val total = mediaItemDao.getUnindexedCount()
        if (total == 0) return@withContext Result.success()

        // Inizializza cache hash
        activeHashes.clear()
        activeHashes.addAll(mediaItemDao.getAllWithPHash().map { 
            Triple(it.id, it.perceptualHash!!.toULong(16), it.createdAt) 
        })

        var indexed = 0
        val startTime = System.currentTimeMillis()

        while (!isStopped) {
            if (shouldPause()) break

            val batch = mediaItemDao.getUnindexed(BATCH_SIZE)
            if (batch.isEmpty()) break

            for (item in batch) {
                if (isStopped) break
                try {
                    processItem(item)
                } catch (e: Exception) {
                    // Ignora errori sul singolo item e prosegui
                }
                indexed++

                val elapsed = System.currentTimeMillis() - startTime
                val rate = if (indexed > 0) elapsed / indexed else 0L
                val remaining = if (rate > 0) ((total - indexed) * rate / 60_000).toInt() else -1

                setProgress(workDataOf(
                    KEY_TOTAL to total,
                    KEY_INDEXED to indexed,
                    KEY_CURRENT_FILE to item.fileName,
                    KEY_ESTIMATED_MINUTES to remaining,
                ))
            }
        }

        textRecognizer.close()
        imageLabeler.close()
        faceRecognitionPipeline.close()
        Result.success()
    }

    private suspend fun processItem(item: MediaItemEntity) = coroutineScope {
        // Skip AI labeling for videos — they can be GBs in size and don't need visual AI analysis.
        // Mark them as indexed with null AI fields so they don't re-enter the unindexed queue.
        if (item.isVideo) {
            mediaItemDao.updateIndexedFields(
                id = item.id,
                lat = null, lng = null,
                cameraModel = null, iso = null, focalLength = null,
                labels = null, scenes = null, ocrText = null, pHash = null,
                thumbnailPath = null,
            )
            return@coroutineScope
        }

        val tempFile = downloadToTempFile(item.webDavPath) ?: return@coroutineScope
        try {
            // Estrazione EXIF e Decoding Thumbnail in parallelo per velocità
            val exifDeferred = async { extractExif(tempFile) }
            val bitmapDeferred = async { decodeThumbnail(tempFile) }
            
            val exif = exifDeferred.await()
            val bitmap = bitmapDeferred.await() ?: run {
                mediaItemDao.updateIndexedFields(
                    id = item.id,
                    lat = exif.latitude, lng = exif.longitude,
                    cameraModel = exif.cameraModel, iso = exif.iso, focalLength = exif.focalLength,
                    labels = null, scenes = null, ocrText = null, pHash = null,
                    thumbnailPath = null,
                )
                return@coroutineScope
            }

            val thumbnailPath = saveThumbnail(bitmap, item.id)
            
            // AI Analysis in parallel with supervisorScope to ensure one failure doesn't cancel others
            val (mlKitLabels, genAILabels, ocrText, pHash) = supervisorScope {
                val mlKitLabelsDeferred = async { runImageLabeling(bitmap) }
                val sceneAIDeferred = async { runSceneAI(bitmap) }
                val ocrTextDeferred = async { runTextRecognition(bitmap) }
                val pHashDeferred = async { computeAverageHash(bitmap) }
                
                val mlKit = try { mlKitLabelsDeferred.await() } catch (e: Exception) { emptyList() }
                val genAI = try { sceneAIDeferred.await() } catch (e: Exception) { emptyList() }
                val ocr = try { ocrTextDeferred.await()?.take(OCR_MAX_CHARS) } catch (e: Exception) { null }
                val hash = try { pHashDeferred.await() } catch (e: Exception) { null }
                
                Quad(mlKit, genAI, ocr, hash)
            }

            val combinedLabels = (mlKitLabels + genAILabels).map { it.lowercase() }.distinct()
            val sceneKeywords = setOf("outdoor", "indoor", "sky", "nature", "landscape", "city", "street", "mountain", "beach", "forest", "sunset", "night")
            val scenes = combinedLabels.filter { label -> sceneKeywords.any { label.contains(it) } }
            val objects = combinedLabels - scenes.toSet()

            mediaItemDao.updateIndexedFields(
                id = item.id,
                lat = exif.latitude, lng = exif.longitude,
                cameraModel = exif.cameraModel, iso = exif.iso, focalLength = exif.focalLength,
                labels = objects.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                scenes = scenes.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                ocrText = ocrText,
                pHash = pHash,
                thumbnailPath = thumbnailPath,
            )

            mediaFtsDao.upsert(MediaFtsEntity(
                itemId = item.id,
                fileName = item.fileName,
                aiLabels = objects.joinToString(" "),
                aiOcrText = ocrText ?: "",
                aiScenes = scenes.joinToString(" "),
            ))

            if (pHash != null) {
                try {
                    val pHashULong = pHash.toULong(16)
                    groupDuplicates(item.id, pHashULong, item.createdAt)
                    activeHashes.add(Triple(item.id, pHashULong, item.createdAt))
                } catch (e: Exception) { /* ignore invalid hash */ }
            }
            
            try {
                faceRecognitionPipeline.processImage(item.id, bitmap)
            } catch (e: Exception) { /* ignore face recognition errors */ }
            bitmap.recycle()
        } finally {
            tempFile.delete()
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun saveThumbnail(bitmap: Bitmap, id: String): String? {
        return try {
            val hash = MessageDigest.getInstance("MD5")
                .digest(id.toByteArray())
                .joinToString("") { "%02x".format(it) }

            val thumbFile = File(context.cacheDir, "thumb_$hash.jpg")
            FileOutputStream(thumbFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            thumbFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun downloadToTempFile(path: String): File? {
        val tempFile = File.createTempFile("indexing_", ".tmp", context.cacheDir)
        return try {
            val downloaded = try {
                // Try range request first for efficiency (1MB should be enough for thumbnail/EXIF)
                webDavClient.getRange(path, 0L, 1_048_575L).use { stream ->
                    FileOutputStream(tempFile).use { out -> stream.copyTo(out) }
                    true
                }
            } catch (e: Exception) {
                // Fallback to full download if range is not supported
                try {
                    webDavClient.get(path).use { stream ->
                        FileOutputStream(tempFile).use { out -> stream.copyTo(out) }
                        true
                    }
                } catch (e2: Exception) { false }
            }
            if (downloaded) tempFile else { tempFile.delete(); null }
        } catch (e: Exception) {
            tempFile.delete()
            null
        }
    }

    private fun decodeThumbnail(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0) return null

            val scale = maxOf(options.outWidth / THUMBNAIL_SIZE, options.outHeight / THUMBNAIL_SIZE, 1)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractExif(file: File): ExifData {
        return try {
            val exif = ExifInterface(file.absolutePath)
            ExifData(
                latitude = exif.latLong?.get(0),
                longitude = exif.latLong?.get(1),
                cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                    ?.takeIf { it.isNotBlank() },
                iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                    .takeIf { it > 0 },
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    ?.let { parseFraction(it) },
            )
        } catch (e: Exception) {
            ExifData()
        }
    }

    private fun parseFraction(value: String): Float? {
        return value.split("/").let { parts ->
            if (parts.size == 2) {
                val num = parts[0].toFloatOrNull() ?: return null
                val den = parts[1].toFloatOrNull() ?: return null
                if (den != 0f) num / den else null
            } else value.toFloatOrNull()
        }
    }

    private suspend fun runSceneAI(bitmap: Bitmap): List<String> {
        return try {
            val providerStr = withTimeoutOrNull(2000) {
                settingsRepository.aiEngineProvider.first()
            } ?: "none"

            val provider = when (providerStr) {
                "gemini_nano" -> geminiNanoProvider
                "gemma4" -> gemma4Provider
                else -> return emptyList()
            }
            withTimeoutOrNull(15000) {
                provider.analyzeScene(bitmap)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun runImageLabeling(bitmap: Bitmap): List<String> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            imageLabeler.process(image).await().map { it.text.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun runTextRecognition(bitmap: Bitmap): String? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image).await().text.trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun computeAverageHash(bitmap: Bitmap): String? {
        return try {
            val small = Bitmap.createScaledBitmap(bitmap, PHASH_DIM, PHASH_DIM, true)
            val pixels = IntArray(PHASH_DIM * PHASH_DIM)
            small.getPixels(pixels, 0, PHASH_DIM, 0, 0, PHASH_DIM, PHASH_DIM)
            small.recycle()

            val grays = pixels.map { px ->
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                (0.299 * r + 0.587 * g + 0.114 * b)
            }
            val mean = grays.average()
            var hash = 0UL
            grays.forEachIndexed { i, v -> if (v >= mean) hash = hash or (1UL shl i) }
            hash.toString(16).padStart(16, '0')
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun groupDuplicates(itemId: String, thisHash: ULong, createdAt: Long) {
        val matches = activeHashes.filter { (_, candidateHash, _) ->
            val xor = thisHash xor candidateHash
            java.lang.Long.bitCount(xor.toLong()) <= 5 // Hamming distance threshold
        }
        
        if (matches.isNotEmpty()) {
            // Uniamo i nuovi match con l'item corrente per trovare il capogruppo (il più vecchio)
            val allItemsInGroup = matches + Triple(itemId, thisHash, createdAt)
            val groupId = allItemsInGroup.minByOrNull { it.third }!!.first
            
            allItemsInGroup.forEach { (id, _, _) ->
                mediaItemDao.setDuplicateGroup(id, groupId)
            }
        }
    }

    private fun shouldPause(): Boolean {
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 1..19 && !batteryManager.isCharging) return true
        if (powerManager.isPowerSaveMode) return true
        return false
    }

    private data class ExifData(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val cameraModel: String? = null,
        val iso: Int? = null,
        val focalLength: Float? = null,
    )
}
