package com.mycloudgallery.domain.repository

import com.mycloudgallery.domain.model.SharedAlbum
import kotlinx.coroutines.flow.Flow

/**
 * Contratto per la gestione degli album condivisi con la famiglia.
 *
 * I dati vivono localmente in Room e vengono sincronizzati tramite file JSON
 * nella cartella condivisa del NAS: /NAS/.mycloudgallery/shared_albums/{albumId}.json
 */
interface SharedAlbumRepository {

    /** Flusso di tutti gli album condivisi visibili all'utente corrente. */
    fun getAll(): Flow<List<SharedAlbum>>

    /** Album condiviso con l'[id] dato, o null se non trovato. */
    suspend fun getById(id: String): SharedAlbum?

    /** Numero totale di richieste pendenti su tutti gli album di cui si è proprietari. */
    fun getPendingRequestsCount(): Flow<Int>

    /**
     * Sincronizza gli album condivisi col NAS:
     * - legge i JSON da /NAS/.mycloudgallery/shared_albums/
     * - salva/aggiorna il Room locale
     * - scrive il JSON aggiornato se ci sono modifiche locali pendenti
     */
    suspend fun syncWithNas()

    /**
     * Crea un nuovo album condiviso e scrive il JSON sul NAS.
     * @param name nome dell'album
     * @param memberUserIds userId dei membri da invitare (ruolo iniziale: VIEWER)
     */
    suspend fun createSharedAlbum(name: String, memberUserIds: List<String>): SharedAlbum

    /** Approva la richiesta pendente [requestId] sull'album [albumId]. */
    suspend fun approveRequest(albumId: String, requestId: String)

    /** Rifiuta la richiesta pendente [requestId] sull'album [albumId]. */
    suspend fun rejectRequest(albumId: String, requestId: String)

    /**
     * Propone l'aggiunta di [mediaPaths] all'album [albumId].
     * Se l'utente è EDITOR → aggiunge direttamente.
     * Se l'utente è EDITOR_WITH_APPROVAL → crea una PendingRequest.
     */
    suspend fun proposeMediaAddition(albumId: String, mediaPaths: List<String>)
}
