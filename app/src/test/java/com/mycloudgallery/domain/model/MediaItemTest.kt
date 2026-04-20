package com.mycloudgallery.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaItemTest {

    private fun buildItem(
        lat: Double? = null,
        lng: Double? = null,
        aiLabels: List<String> = emptyList(),
        aiOcrText: String? = null,
    ) = MediaItem(
        id = "test_id",
        fileName = "test.jpg",
        mimeType = "image/jpeg",
        fileSize = 1024L,
        createdAt = System.currentTimeMillis(),
        modifiedAt = System.currentTimeMillis(),
        webDavPath = "/Public/test.jpg",
        thumbnailCachePath = null,
        mediaType = MediaType.IMAGE,
        videoDuration = null,
        width = 1920,
        height = 1080,
        exifLatitude = lat,
        exifLongitude = lng,
        exifCameraModel = null,
        exifIso = null,
        exifFocalLength = null,
        aiLabels = aiLabels,
        aiScenes = emptyList(),
        aiOcrText = aiOcrText,
        isIndexed = false,
        isInTrash = false,
        trashedAt = null,
        isFavorite = false,
    )

    @Test
    fun `hasGps è true solo se lat e lng sono entrambi presenti`() {
        assertTrue(buildItem(lat = 45.0, lng = 9.0).hasGps)
        assertFalse(buildItem(lat = 45.0, lng = null).hasGps)
        assertFalse(buildItem(lat = null, lng = 9.0).hasGps)
        assertFalse(buildItem().hasGps)
    }

    @Test
    fun `hasAiData è true se ci sono label, scene o testo OCR`() {
        assertTrue(buildItem(aiLabels = listOf("dog")).hasAiData)
        assertTrue(buildItem(aiOcrText = "Testo scansionato").hasAiData)
        assertFalse(buildItem().hasAiData)
    }

    @Test
    fun `MediaType fromMimeType classifica correttamente`() {
        assertEquals(MediaType.VIDEO, MediaType.fromMimeType("video/mp4"))
        assertEquals(MediaType.IMAGE, MediaType.fromMimeType("image/jpeg"))
        assertEquals(MediaType.IMAGE, MediaType.fromMimeType("image/heic"))
        assertEquals(MediaType.RAW, MediaType.fromMimeType("image/x-adobe-dng"))
    }
}
