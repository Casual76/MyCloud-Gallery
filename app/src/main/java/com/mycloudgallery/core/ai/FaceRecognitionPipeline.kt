package com.mycloudgallery.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.mycloudgallery.core.database.dao.FaceEmbeddingDao
import com.mycloudgallery.core.database.entity.FaceEmbeddingEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceRecognitionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val faceEmbeddingDao: FaceEmbeddingDao,
) {
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f)
            .build()
    )

    private var tfliteInterpreter: Interpreter? = null

    private fun ensureInterpreter(): Interpreter? {
        if (tfliteInterpreter != null) return tfliteInterpreter
        val modelFile = modelDownloadManager.modelFile(ModelDownloadManager.MOBILE_FACENET)
        if (!modelFile.exists()) return null
        return try {
            val fis = FileInputStream(modelFile)
            val channel = fis.channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            tfliteInterpreter = Interpreter(buffer)
            tfliteInterpreter
        } catch (e: Exception) {
            null
        }
    }

    suspend fun processImage(mediaItemId: String, bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = try {
            faceDetector.process(image).await()
        } catch (e: Exception) {
            return
        }
        if (faces.isEmpty()) return

        val interpreter = ensureInterpreter() ?: return

        val embeddings = faces.mapNotNull { face ->
            val box = face.boundingBox
            val cropped = cropFace(bitmap, box) ?: return@mapNotNull null
            val embedding = extractEmbedding(interpreter, cropped)
            cropped.recycle()
            val boxJson = Json.encodeToString(listOf(box.left, box.top, box.right, box.bottom))
            val embeddingJson = Json.encodeToString(embedding.toList())
            FaceEmbeddingEntity(
                id = UUID.randomUUID().toString(),
                mediaItemId = mediaItemId,
                embeddingJson = embeddingJson,
                boundingBoxJson = boxJson,
                personId = null,
            )
        }
        if (embeddings.isNotEmpty()) {
            faceEmbeddingDao.insertAll(embeddings)
        }
    }

    private fun cropFace(bitmap: Bitmap, box: Rect): Bitmap? {
        val left = box.left.coerceAtLeast(0)
        val top = box.top.coerceAtLeast(0)
        val right = box.right.coerceAtMost(bitmap.width)
        val bottom = box.bottom.coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return null
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return Bitmap.createScaledBitmap(cropped, 112, 112, true).also { if (it != cropped) cropped.recycle() }
    }

    private fun extractEmbedding(interpreter: Interpreter, face112: Bitmap): FloatArray {
        val inputBuffer = ByteBuffer.allocateDirect(1 * 112 * 112 * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(112 * 112)
        face112.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        for (px in pixels) {
            inputBuffer.putFloat(((px shr 16 and 0xFF) - 127.5f) / 127.5f) // R
            inputBuffer.putFloat(((px shr 8 and 0xFF) - 127.5f) / 127.5f)  // G
            inputBuffer.putFloat(((px and 0xFF) - 127.5f) / 127.5f)         // B
        }
        val outputBuffer = Array(1) { FloatArray(192) } // MobileFaceNet outputs 192-dim
        interpreter.run(inputBuffer, outputBuffer)
        return outputBuffer[0]
    }

    fun close() {
        tfliteInterpreter?.close()
        tfliteInterpreter = null
        faceDetector.close()
    }
}
