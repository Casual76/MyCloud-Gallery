package com.mycloudgallery.core.ai

import android.graphics.Bitmap

interface SceneAIProvider {
    /** Returns a list of scene/label strings for the given bitmap. Empty if unavailable. */
    suspend fun analyzeScene(bitmap: Bitmap): List<String>
    fun close() {}
}
