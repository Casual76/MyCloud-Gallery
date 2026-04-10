package com.mycloudgallery.domain.repository

import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.SearchFilter

interface SearchRepository {
    /**
     * Ricerca full-text + filtri.
     * Usa FTS4 se la query non è vuota, altrimenti filtra solo per [filter].
     * @param query stringa libera (supporta AND/OR/NOT FTS)
     * @param filter filtri aggiuntivi da applicare
     * @return lista ordinata per rilevanza/data
     */
    suspend fun search(query: String, filter: SearchFilter): List<MediaItem>

    /** Ricerca rapida per data espressa come range di timestamp. */
    suspend fun searchByDateRange(from: Long, to: Long, filter: SearchFilter): List<MediaItem>
}
