package com.mycloudgallery.worker

import android.content.Context
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
import com.mycloudgallery.core.database.dao.MediaFtsDao
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.database.entity.MediaFtsEntity
import com.mycloudgallery.core.database.entity.MediaItemEntity
import com.mycloudgallery.core.network.NetworkDetector
import com.mycloudgallery.core.network.WebDavClient
import com.mycloudgallery.domain.model.NetworkMode
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Worker che indicizza in background ogni MediaItem non ancora analizzato.
 * Per ogni foto: estrae EXIF, lancia ML Kit Image Labeling + OCR, calcola aHash.
 * Si ferma automaticamente se: batteria < 20% (non in carica) o modalità risparmio.
 */
@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaItemDao: MediaItemDao,
    private val mediaFtsDao: MediaFtsDao,
    private val webDavClient: WebDavClient,
    private val networkDetector: NetworkDetector,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "indexing_worker"
        const val KEY_TOTAL = "total"
        const val KEY_INDEXED = "indexed"
        const val KEY_CURRENT_FILE = "currentFile"
        const val KEY_ESTIMATED_MINUTES = "estimatedMinutes"

        private const val BATCH_SIZE = 10
        private const val THUMBNAIL_SIZE = 512
        private const val LABEL_CONFIDENCE_THRESHOLD = 0.70f
        private const val OCR_MAX_CHARS = 500
        private const val PHASH_DIM = 8 // 8×8 = 64-bit hash
    }

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(LABEL_CONFIDENCE_THRESHOLD)
            .build()
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (networkDetector.networkMode.value == NetworkMode.OFFLINE) {
            return@withContext Result.retry()
        }

        val total = mediaItemDao.getUnindexedCount()
        if (total == 0) return@withContext Result.success()

        var indexed = 0
        val startTime = System.currentTimeMillis()

        while (!isStopped) {
            if (shouldPause()) break

            val batch = mediaItemDao.getUnindexed(BATCH_SIZE)
            if (batch.isEmpty()) break

            for (item in batch) {
                if (isStopped) break
                processItem(item)
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

        labeler.close()
        textRecognizer.close()
        Result.success()
    }

    private suspend fun processItem(item: MediaItemEntity) {
        // Scarica file (range per EXIF, bitmap per AI)
        val fileBytes = downloadFileBytes(item.webDavPath) ?: return

        // --- EXIF (solo immagini) ---
        val exif = if (!item.isVideo) extractExif(fileBytes) else ExifData()

        // --- Bitmap per ML Kit ---
        val bitmap = decodeThumbnail(fileBytes) ?: run {
            // Anche senza bitmap segniamo come indicizzato (file corrotto o formato non supportato)
            mediaItemDao.updateIndexedFields(
                id = item.id,
                lat = exif.latitude, lng = exif.longitude,
                cameraModel = exif.cameraModel, iso = exif.iso, focalLength = exif.focalLength,
                labels = null, scenes = null, ocrText = null, pHash = null,
            )
            return
        }

        // --- ML Kit: Image Labels ---
        val allLabels = runImageLabeling(bitmap)
        val sceneKeywords = setOf("outdoor", "indoor", "sky", "nature", "landscape", "city", "street")
        val scenes = allLabels.filter { label -> sceneKeywords.any { label.contains(it) } }
        val objects = allLabels - scenes.toSet()

        // --- ML Kit: OCR ---
        val ocrText = runTextRecognition(bitmap)?.take(OCR_MAX_CHARS)

        // --- Average Hash (duplicati) ---
        val pHash = computeAverageHash(bitmap)
        bitmap.recycle()

        // --- Aggiorna Room ---
        mediaItemDao.updateIndexedFields(
            id = item.id,
            lat = exif.latitude, lng = exif.longitude,
            cameraModel = exif.cameraModel, iso = exif.iso, focalLength = exif.focalLength,
            labels = objects.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
            scenes = scenes.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
            ocrText = ocrText,
            pHash = pHash,
        )

        // --- Aggiorna FTS (spazio-separato per MATCH FTS4) ---
        mediaFtsDao.upsert(MediaFtsEntity(
            itemId = item.id,
            fileName = item.fileName,
            aiLabels = objects.joinToString(" "),
            aiOcrText = ocrText ?: "",
            aiScenes = scenes.joinToString(" "),
        ))

        // --- Raggruppa duplicati ---
        if (pHash != null) groupDuplicates(item.id, pHash)
    }

    /** Scarica i byte del file tramite WebDAV. Max 1 MB per non saturare la RAM. */
    private suspend fun downloadFileBytes(path: String): ByteArray? {
        return try {
            // Range request: primi 1 MB — sufficiente per EXIF (nei primi KB) e thumbnail
            webDavClient.getRange(path, 0L, 1_048_575L).use { it.readBytes() }
        } catch (e: Exception) {
            try {
                webDavClient.get(path).use { it.readBytes() }
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** Decodifica byte in Bitmap scalato a THUMBNAIL_SIZE. */
    private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0) return null

        val scale = maxOf(options.outWidth / THUMBNAIL_SIZE, options.outHeight / THUMBNAIL_SIZE, 1)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    /** Estrae metadati EXIF dai byte del file. */
    private fun extractExif(bytes: ByteArray): ExifData {
        return try {
            bytes.inputStream().use { stream ->
                val exif = ExifInterface(stream)
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
            }
        } catch (e: Exception) {
            ExifData()
        }
    }

    /** Converte stringa frazione EXIF (es. "50/1") in Float. */
    private fun parseFraction(value: String): Float? {
        return value.split("/").let { parts ->
            if (parts.size == 2) {
                val num = parts[0].toFloatOrNull() ?: return null
                val den = parts[1].toFloatOrNull() ?: return null
                if (den != 0f) num / den else null
            } else value.toFloatOrNull()
        }
    }

    /** ML Kit Image Labeling: Task → suspend. */
    private suspend fun runImageLabeling(bitmap: Bitmap): List<String> =
        suspendCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels -> cont.resume(labels.map { it.text.lowercase() }) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    /** ML Kit Text Recognition (OCR): Task → suspend. */
    private suspend fun runTextRecognition(bitmap: Bitmap): String? =
        suspendCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text.trim().takeIf { it.isNotBlank() })
                }
                .addOnFailureListener { cont.resume(null) }
        }

    /**
     * Calcola Average Hash (aHash) 64-bit:
     * 1. Scala a 8×8 in scala di grigi
     * 2. Media dei pixel → bit = (pixel >= media) ? 1 : 0
     * 3. Restituisce stringa hex a 16 caratteri
     */
    private fun computeAverageHash(bitmap: Bitmap): String {
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
        return hash.toString(16).padStart(16, '0')
    }

    /** Raggruppa questo item con altri aventi lo stesso pHash (exact match). */
    private suspend fun groupDuplicates(itemId: String, pHash: String) {
        val matches = mediaItemDao.getByPerceptualHash(pHash)
        if (matches.size > 1) {
            // Il gruppo prende l'ID del file più vecchio (più originale)
            val groupId = matches.minByOrNull { it.createdAt }!!.id
            matches.forEach { match ->
                if (match.duplicateGroupId != groupId) {
                    mediaItemDao.setDuplicateGroup(match.id, groupId)
                }
            }
        }
    }

    /** Sospende l'indicizzazione se batteria bassa o risparmio energetico attivo. */
    private fun shouldPause(): Boolean {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 1..19 && !bm.isCharging) return true

        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isPowerSaveMode) return true

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
