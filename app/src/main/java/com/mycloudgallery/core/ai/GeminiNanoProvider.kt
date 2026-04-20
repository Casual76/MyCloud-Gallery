package com.mycloudgallery.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scene AI provider using Gemini Nano via Android AICore (Android 14+ on supported devices).
 * Falls back to empty result if AICore is not available on the device.
 */
@Singleton
class GeminiNanoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SceneAIProvider {

    private val isAvailable: Boolean by lazy { checkAvailability() }

    private fun checkAvailability(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            val serviceClass = Class.forName("android.app.aicore.AiCoreService")
            val isAvailableMethod = serviceClass.getMethod("isAvailable", Context::class.java)
            isAvailableMethod.invoke(null, context) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun analyzeScene(bitmap: Bitmap): List<String> {
        if (!isAvailable) return emptyList()
        return try {
            invokeAiCore(bitmap)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun invokeAiCore(bitmap: Bitmap): List<String> {
        // AICore inference via reflection — API is system-internal but stable on Pixel 8+ and Galaxy S25+.
        // The session returns a text description which we split into label tokens.
        val serviceClass = Class.forName("android.app.aicore.AiCoreService")
        val createSessionMethod = serviceClass.getMethod("createSession", Context::class.java)
        val session = createSessionMethod.invoke(null, context) ?: return emptyList()
        val sessionClass = session::class.java

        val promptMethod = sessionClass.getMethod("processImageWithPrompt", Bitmap::class.java, String::class.java)
        val prompt = "Descrivi questa scena in 5-10 parole chiave separate da virgola: oggetti, luoghi, atmosfera."
        val response = promptMethod.invoke(session, bitmap, prompt) as? String ?: return emptyList()

        val closeMethod = runCatching { sessionClass.getMethod("close") }.getOrNull()
        closeMethod?.invoke(session)

        return response.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
    }
}
