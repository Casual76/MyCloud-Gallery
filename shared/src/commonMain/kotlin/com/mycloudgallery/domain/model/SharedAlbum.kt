package com.mycloudgallery.domain.model

enum class SharedAlbumRole { VIEWER, EDITOR_WITH_APPROVAL, EDITOR }

data class SharedAlbumMember(val userId: String, val role: SharedAlbumRole)

data class PendingRequest(
    val requestId: String,
    val requestedBy: String,
    val mediaPaths: List<String>,
    val requestedAt: Long,
)

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
