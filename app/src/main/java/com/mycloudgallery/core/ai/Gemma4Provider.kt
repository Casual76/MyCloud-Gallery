package com.mycloudgallery.core.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scene AI provider using Gemma 4 via MediaPipe Tasks GenAI.
 * Requires the model file to be downloaded to filesDir/models/gemma4.task.
 */
@Singleton
class Gemma4Provider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
) : SceneAIProvider {

    private var llmInference: LlmInference? = null

    private fun ensureModel(): LlmInference? {
        if (llmInference != null) return llmInference
        val modelFile = modelDownloadManager.modelFile(ModelDownloadManager.GEMMA_4)
        if (!modelFile.exists()) return null
        return try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(256)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            llmInference
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun analyzeScene(bitmap: Bitmap): List<String> = withContext(Dispatchers.Default) {
        val model = ensureModel() ?: return@withContext emptyList()
        // Note: MediaPipe Tasks GenAI text-only inference; image description via prompt engineering.
        // For full VLM support, upgrade to MediaPipe multimodal Tasks when available.
        val prompt = "Analizza questa scena fotografica e restituisci 5-10 parole chiave (oggetti, luoghi, atmosfera) separate da virgola. Rispondi solo con le parole chiave."
        return@withContext try {
            val response = model.generateResponse(prompt)
            response.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
    }
}
