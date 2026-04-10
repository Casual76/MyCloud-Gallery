package com.mycloudgallery.desktop.data.repository

import com.mycloudgallery.desktop.data.network.DesktopWebDavClient
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.MediaType
import com.mycloudgallery.domain.model.SearchFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository media per la versione Desktop.
 * Carica i media via WebDAV e li tiene in cache in-memory durante la sessione.
 * Per la persistenza tra sessioni è sufficiente un SQLite locale (TODO Fase 5+).
 */
class DesktopMediaRepository(
    private val webDavClient: DesktopWebDavClient,
) {
    // Cache in-memory per la sessione corrente
    private var _cache: List<MediaItem> = emptyList()

    /** Carica i media recenti dal NAS (o dalla cache se già caricati). */
    suspend fun getRecentMedia(limit: Int = 500): List<MediaItem> {
        if (_cache.isNotEmpty()) return _cache.take(limit)
        _cache = fetchFromNas()
        return _cache.take(limit)
    }

    suspend fun getById(id: String): MediaItem? =
        _cache.find { it.id == id } ?: getRecentMedia().find { it.id == id }

    /** Ricerca full-text in-memory sui campi indicizzati. */
    suspend fun search(query: String, filter: SearchFilter): List<MediaItem> {
        val all = getRecentMedia()
        if (query.isBlank()) return applyFilter(all, filter)
        val q = query.lowercase()
        return applyFilter(
            all.filter { item ->
                item.fileName.lowercase().contains(q) ||
                    item.aiLabels.any { it.lowercase().contains(q) } ||
                    item.aiScenes.any { it.lowercase().contains(q) } ||
                    item.aiOcrText?.lowercase()?.contains(q) == true ||
                    item.exifCameraModel?.lowercase()?.contains(q) == true
            },
            filter
        )
    }

    private fun applyFilter(items: List<MediaItem>, filter: SearchFilter): List<MediaItem> =
        items.filter { item ->
            (filter.mediaType == null || item.mediaType == filter.mediaType) &&
                (filter.fromTimestamp == null || item.createdAt >= filter.fromTimestamp) &&
                (filter.toTimestamp == null || item.createdAt <= filter.toTimestamp) &&
                (filter.hasGps == null || item.hasGps == filter.hasGps) &&
                (!filter.favoritesOnly || item.isFavorite)
        }

    private suspend fun fetchFromNas(): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            // Esegui PROPFIND ricorsivo sulla cartella /Public
            val xml = webDavClient.propFind("/Public", depth = "infinity")
            parseWebDavResponse(xml)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Parsing minimale della risposta PROPFIND multistatus XML. */
    private fun parseWebDavResponse(xml: String): List<MediaItem> {
        val mediaExtensions = setOf("jpg", "jpeg", "png", "heic", "webp", "mp4", "mov", "gif", "raw", "dng")
        val hrefRegex = Regex("<D:href>([^<]+)</D:href>")
        val contentLengthRegex = Regex("<D:getcontentlength>([^<]+)</D:getcontentlength>")
        val lastModifiedRegex = Regex("<D:getlastmodified>([^<]+)</D:getlastmodified>")

        val hrefs = hrefRegex.findAll(xml).map { it.groupValues[1].trim() }.toList()
        val sizes = contentLengthRegex.findAll(xml).map { it.groupValues[1].toLongOrNull() ?: 0L }.toList()

        return hrefs.mapIndexedNotNull { index, href ->
            val ext = href.substringAfterLast('.', "").lowercase()
            if (ext !in mediaExtensions) return@mapIndexedNotNull null

            val fileName = href.substringAfterLast('/')
            val isVideo = ext in setOf("mp4", "mov")
            MediaItem(
                id = href,
                fileName = fileName,
                mimeType = if (isVideo) "video/$ext" else "image/$ext",
                fileSize = sizes.getOrElse(index) { 0L },
                createdAt = System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis(),
                webDavPath = href,
                thumbnailCachePath = null,
                mediaType = if (isVideo) MediaType.VIDEO else MediaType.fromMimeType("image/$ext"),
                videoDuration = null,
                width = null,
                height = null,
                exifLatitude = null,
                exifLongitude = null,
                exifCameraModel = null,
                exifIso = null,
                exifFocalLength = null,
                aiLabels = emptyList(),
                aiScenes = emptyList(),
                aiOcrText = null,
                isIndexed = false,
                isInTrash = false,
                trashedAt = null,
                isFavorite = false,
            )
        }
    }
}
