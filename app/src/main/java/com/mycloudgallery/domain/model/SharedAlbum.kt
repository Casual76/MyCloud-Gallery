package com.mycloudgallery.domain.model

/**
 * Livello di permesso di un membro su un album condiviso.
 */
enum class SharedAlbumRole {
    /** Solo lettura. */
    VIEWER,

    /** Può proporre aggiunte (salvate come richieste pendenti, richiedono approvazione). */
    EDITOR_WITH_APPROVAL,

    /** Pieno controllo aggiunta/rimozione. */
    EDITOR,
}

/**
 * Membro di un album condiviso.
 * @param userId identificatore utente WD (username)
 * @param role permesso assegnato
 */
data class SharedAlbumMember(
    val userId: String,
    val role: SharedAlbumRole,
)

/**
 * Richiesta pendente di aggiunta media a un album condiviso.
 * @param requestId UUID della richiesta
 * @param requestedBy userId di chi ha proposto l'aggiunta
 * @param mediaPaths lista di path WebDAV da aggiungere
 * @param requestedAt timestamp della richiesta
 */
data class PendingRequest(
    val requestId: String,
    val requestedBy: String,
    val mediaPaths: List<String>,
    val requestedAt: Long,
)

/**
 * Album condiviso tra membri della famiglia.
 * Sincronizzato via file JSON nella cartella condivisa del NAS.
 *
 * @param id UUID dell'album
 * @param name nome dell'album
 * @param ownerId userId del proprietario
 * @param members lista di membri con i loro ruoli
 * @param mediaPaths path WebDAV dei media inclusi
 * @param pendingRequests richieste di aggiunta in attesa di approvazione
 * @param lastSyncedAt timestamp dell'ultima sincronizzazione col NAS
 * @param createdAt timestamp di creazione
 */
data class SharedAlbum(
    val id: String,
    val name: String,
    val ownerId: String,
    val members: List<SharedAlbumMember>,
    val mediaPaths: List<String>,
    val pendingRequests: List<PendingRequest>,
    val lastSyncedAt: Long,
    val createdAt: Long,
) {
    val pendingRequestsCount: Int get() = pendingRequests.size
}
