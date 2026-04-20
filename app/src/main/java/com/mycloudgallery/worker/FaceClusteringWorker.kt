package com.mycloudgallery.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mycloudgallery.core.database.dao.FaceEmbeddingDao
import com.mycloudgallery.core.database.dao.PersonDao
import com.mycloudgallery.core.database.entity.PersonEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.sqrt

@HiltWorker
class FaceClusteringWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val faceEmbeddingDao: FaceEmbeddingDao,
    private val personDao: PersonDao,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "face_clustering_worker"
        private const val SIMILARITY_THRESHOLD = 0.75f
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val unclustered = faceEmbeddingDao.getUnclusteredFaces()
        if (unclustered.isEmpty()) return@withContext Result.success()

        val allPersons = mutableListOf<Pair<String, FloatArray>>() // personId to representative embedding

        for (face in unclustered) {
            val faceEmbedding = parseEmbedding(face.embeddingJson) ?: continue

            var bestPersonId: String? = null
            var bestSimilarity = -1f
            for ((personId, personEmbedding) in allPersons) {
                val sim = cosineSimilarity(faceEmbedding, personEmbedding)
                if (sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestPersonId = personId
                }
            }

            if (bestPersonId != null && bestSimilarity >= SIMILARITY_THRESHOLD) {
                faceEmbeddingDao.assignPerson(face.id, bestPersonId)
                personDao.incrementPhotoCount(bestPersonId)
            } else {
                val newPersonId = UUID.randomUUID().toString()
                personDao.insert(PersonEntity(
                    id = newPersonId,
                    name = null,
                    representativeFaceId = face.id,
                    createdAt = System.currentTimeMillis(),
                    photoCount = 1,
                ))
                faceEmbeddingDao.assignPerson(face.id, newPersonId)
                allPersons.add(newPersonId to faceEmbedding)
            }
        }

        Result.success()
    }

    private fun parseEmbedding(json: String): FloatArray? {
        return try {
            val list = Json.decodeFromString<List<Float>>(json)
            list.toFloatArray()
        } catch (e: Exception) { null }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
