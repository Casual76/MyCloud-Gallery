package com.mycloudgallery.domain.repository

import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.SearchFilter

interface SearchRepository {
    suspend fun search(query: String, filter: SearchFilter): List<MediaItem>
    suspend fun searchByDateRange(from: Long, to: Long, filter: SearchFilter): List<MediaItem>
}
