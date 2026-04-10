package com.mycloudgallery.domain.repository

import com.mycloudgallery.domain.model.Album
import com.mycloudgallery.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * Contratto del repository per la gestione degli album locali.
 * Tutti i dati sono persistiti in Room; la sincronizzazione col NAS è delegata a [SharedAlbumRepository].
 */
interface AlbumRepository {

    /** Flusso di tutti gli album ordinati per [Album.sortOrder] poi per nome. */
    fun getAll(): Flow<List<Album>>

    /** Flusso dei media appartenenti all'album [albumId]. */
    fun getMediaForAlbum(albumId: String): Flow<List<MediaItem>>

    /** Conteggio dei media preferiti (per l'album virtuale "Preferiti"). */
    fun getFavoritesCount(): Flow<Int>

    /** Crea un nuovo album con il nome dato e restituisce l'entità creata. */
    suspend fun createAlbum(name: String): Album

    /**
     * Rinomina l'album [id].
     * @throws IllegalArgumentException se l'album non esiste o è auto-generato.
     */
    suspend fun renameAlbum(id: String, newName: String)

    /**
     * Elimina l'album [id] senza eliminare i file dal NAS.
     * @throws IllegalArgumentException se l'album è auto-generato.
     */
    suspend fun deleteAlbum(id: String)

    /** Aggiunge [mediaIds] all'album [albumId]. Ignora silenziosamente i duplicati. */
    suspend fun addMediaToAlbum(albumId: String, mediaIds: List<String>)

    /** Rimuove [mediaIds] dall'album [albumId] senza eliminare i file. */
    suspend fun removeMediaFromAlbum(albumId: String, mediaIds: List<String>)

    /** Aggiorna l'ordinamento dell'album (drag & drop). */
    suspend fun updateSortOrder(id: String, newSortOrder: Int)

    /** Imposta la cover dell'album al media [mediaId]. */
    suspend fun updateCover(albumId: String, mediaId: String)
}
