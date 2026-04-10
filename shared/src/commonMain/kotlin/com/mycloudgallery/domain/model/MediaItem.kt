package com.mycloudgallery.domain.model

/**
 * Modello di dominio per un elemento multimediale (foto o video).
 * Puro Kotlin — nessuna dipendenza Android o JVM specifica.
 */
data class MediaItem(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val webDavPath: String,
    val thumbnailCachePath: String?,
    val mediaType: MediaType,
    val videoDuration: Long?,
    val width: Int?,
    val height: Int?,
    val exifLatitude: Double?,
    val exifLongitude: Double?,
    val exifCameraModel: String?,
    val exifIso: Int?,
    val exifFocalLength: Float?,
    val aiLabels: List<String>,
    val aiScenes: List<String>,
    val aiOcrText: String?,
    val isIndexed: Boolean,
    val isInTrash: Boolean,
    val trashedAt: Long?,
    val isFavorite: Boolean,
) {
    val hasGps: Boolean get() = exifLatitude != null && exifLongitude != null
    val hasAiData: Boolean get() = aiLabels.isNotEmpty() || aiScenes.isNotEmpty() || !aiOcrText.isNullOrBlank()
}
