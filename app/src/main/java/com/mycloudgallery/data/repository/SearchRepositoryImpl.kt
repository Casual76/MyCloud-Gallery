package com.mycloudgallery.data.repository

import com.mycloudgallery.core.database.dao.MediaFtsDao
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.database.entity.MediaItemEntity
import com.mycloudgallery.core.util.toDomain
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.MediaType
import com.mycloudgallery.domain.model.SearchFilter
import com.mycloudgallery.domain.repository.SearchRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val mediaFtsDao: MediaFtsDao,
) : SearchRepository {

    override suspend fun search(query: String, filter: SearchFilter): List<MediaItem> {
        val rawItems: List<MediaItemEntity> = if (query.isBlank()) {
            mediaItemDao.searchFiltered(
                isVideo = when (filter.mediaType) {
                    MediaType.VIDEO -> true
                    MediaType.IMAGE -> false
                    else -> null
                },
                isFavorite = if (filter.favoritesOnly) true else null,
                hasGps = filter.hasGps,
                hasOcr = filter.hasOcr,
                isDuplicate = if (filter.duplicatesOnly) true else null,
                from = filter.fromTimestamp ?: 0L,
                to = filter.toTimestamp ?: Long.MAX_VALUE
            )
        } else {
            val ftsQuery = buildFtsQuery(query)
            val ftsIds: List<String> = try {
                mediaFtsDao.searchIds(ftsQuery)
            } catch (_: Exception) {
                emptyList()
            }

            if (ftsIds.isNotEmpty()) {
                mediaItemDao.getByIds(ftsIds)
            } else {
                mediaItemDao.searchLike(query)
            }
        }

        // Se query è non-null, dobbiamo comunque applicare il filtro residuo (RAW non è gestito da SQL)
        return rawItems
            .filter { applyFilter(it, filter) }
            .map { it.toDomain() }
            .sortedByDescending { it.createdAt }
    }

    override suspend fun searchByDateRange(from: Long, to: Long, filter: SearchFilter): List<MediaItem> {
        return mediaItemDao.searchFiltered(
            isVideo = when (filter.mediaType) {
                MediaType.VIDEO -> true
                MediaType.IMAGE -> false
                else -> null
            },
            isFavorite = if (filter.favoritesOnly) true else null,
            hasGps = filter.hasGps,
            hasOcr = filter.hasOcr,
            isDuplicate = if (filter.duplicatesOnly) true else null,
            from = from,
            to = to
        ).map { it.toDomain() }
    }

    /**
     * Converte la query in sintassi FTS4.
     * Parole multiple → ogni parola come prefix search, implicitamente in AND.
     * Virgolette preservate per phrase search.
     */
    private fun buildFtsQuery(input: String): String {
        if (input.contains('"')) return input
        return input.trim()
            .split("\\s+".toRegex())
            .filter { it.length >= 2 }
            .joinToString(" ") { "$it*" }
    }

    private fun applyFilter(item: MediaItemEntity, filter: SearchFilter): Boolean {
        if (item.isInTrash) return false

        filter.mediaType?.let { type ->
            val matchesType = when (type) {
                MediaType.VIDEO -> item.isVideo
                MediaType.RAW -> item.mimeType.contains("raw", ignoreCase = true) ||
                        item.mimeType.contains("dng", ignoreCase = true)
                MediaType.IMAGE -> !item.isVideo
            }
            if (!matchesType) return false
        }

        filter.fromTimestamp?.let { if (item.createdAt < it) return false }
        filter.toTimestamp?.let { if (item.createdAt > it) return false }

        filter.hasGps?.let { needed ->
            if ((item.exifLatitude != null && item.exifLongitude != null) != needed) return false
        }
        filter.hasOcr?.let { needed ->
            if ((item.aiOcrText != null) != needed) return false
        }

        if (filter.favoritesOnly && !item.isFavorite) return false
        if (filter.duplicatesOnly && item.duplicateGroupId == null) return false

        return true
    }
}
