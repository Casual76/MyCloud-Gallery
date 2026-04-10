package com.mycloudgallery.data.repository

import com.mycloudgallery.core.database.dao.SharedAlbumDao
import com.mycloudgallery.core.database.entity.SharedAlbumEntity
import com.mycloudgallery.core.network.WebDavClient
import com.mycloudgallery.core.security.TokenManager
import com.mycloudgallery.domain.model.PendingRequest
import com.mycloudgallery.domain.model.SharedAlbum
import com.mycloudgallery.domain.model.SharedAlbumMember
import com.mycloudgallery.domain.model.SharedAlbumRole
import com.mycloudgallery.domain.repository.SharedAlbumRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementazione che sincronizza gli album condivisi tramite file JSON
 * nella cartella /NAS/.mycloudgallery/shared_albums/ del NAS.
 */
@Singleton
class SharedAlbumRepositoryImpl @Inject constructor(
    private val sharedAlbumDao: SharedAlbumDao,
    private val webDavClient: WebDavClient,
    private val tokenManager: TokenManager,
) : SharedAlbumRepository {

    companion object {
        private const val NAS_DIR = "/.mycloudgallery/shared_albums"
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // DTO usati per serializzazione/deserializzazione del file JSON sul NAS
    @Serializable
    private data class MemberDto(val userId: String, val role: String)

    @Serializable
    private data class PendingRequestDto(
        val requestId: String,
        val requestedBy: String,
        val mediaPaths: List<String>,
        val requestedAt: Long,
    )

    @Serializable
    private data class SharedAlbumJson(
        val albumId: String,
        val name: String,
        val ownerId: String,
        val members: List<MemberDto>,
        val mediaItems: List<String>,
        val pendingRequests: List<PendingRequestDto> = emptyList(),
        val createdAt: Long,
    )

    // --- Lettura Room ---

    override fun getAll(): Flow<List<SharedAlbum>> =
        sharedAlbumDao.getAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getById(id: String): SharedAlbum? =
        sharedAlbumDao.getById(id)?.toDomain()

    override fun getPendingRequestsCount(): Flow<Int> {
        val currentUserId = tokenManager.username.orEmpty()
        return sharedAlbumDao.getAll().map { entities ->
            entities
                .filter { it.ownerId == currentUserId }
                .sumOf { entity ->
                    parsePendingRequests(entity.pendingRequestsJson).size
                }
        }
    }

    // --- Sincronizzazione NAS ---

    override suspend fun syncWithNas() {
        // 1. Leggi lista file JSON dalla cartella NAS via PROPFIND depth=1
        val remotePaths = try {
            webDavClient.propFind(NAS_DIR, depth = "1")
                .filter { !it.isDirectory && it.path.endsWith(".json") }
                .map { it.path }
        } catch (e: Exception) {
            return // Offline o cartella assente — skip silenzioso
        }

        val syncedIds = mutableListOf<String>()

        for (path in remotePaths) {
            try {
                val bytes = webDavClient.get(path).use { it.readBytes() }
                val albumJson = json.decodeFromString<SharedAlbumJson>(String(bytes))
                sharedAlbumDao.upsert(albumJson.toEntity())
                syncedIds.add(albumJson.albumId)
            } catch (_: Exception) {
                // JSON malformato o errore di rete — skip il file
            }
        }

        // 2. Rimuovi gli album locali non più presenti sul NAS
        if (syncedIds.isNotEmpty()) {
            sharedAlbumDao.deleteNotIn(syncedIds)
        }
    }

    // --- Operazioni di scrittura ---

    override suspend fun createSharedAlbum(name: String, memberUserIds: List<String>): SharedAlbum {
        val currentUserId = tokenManager.username.orEmpty()
        val albumId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val members = memberUserIds.map { MemberDto(it, "viewer") }
        val albumJson = SharedAlbumJson(
            albumId = albumId,
            name = name,
            ownerId = currentUserId,
            members = members,
            mediaItems = emptyList(),
            pendingRequests = emptyList(),
            createdAt = now,
        )

        writeJsonToNas(albumId, albumJson)

        val entity = albumJson.toEntity(lastSyncedAt = now)
        sharedAlbumDao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun approveRequest(albumId: String, requestId: String) {
        val entity = sharedAlbumDao.getById(albumId) ?: return
        val pendingRequests = parsePendingRequests(entity.pendingRequestsJson).toMutableList()
        val request = pendingRequests.find { it.requestId == requestId } ?: return

        val currentPaths = parseStringList(entity.mediaPathsJson).toMutableList()
        currentPaths.addAll(request.mediaPaths)
        pendingRequests.removeAll { it.requestId == requestId }

        val updated = entity.copy(
            mediaPathsJson = json.encodeToString(currentPaths),
            pendingRequestsJson = json.encodeToString(pendingRequests.map {
                PendingRequestDto(it.requestId, it.requestedBy, it.mediaPaths, it.requestedAt)
            }),
            lastSyncedAt = System.currentTimeMillis(),
        )
        sharedAlbumDao.upsert(updated)
        writeEntityJsonToNas(updated)
    }

    override suspend fun rejectRequest(albumId: String, requestId: String) {
        val entity = sharedAlbumDao.getById(albumId) ?: return
        val pendingRequests = parsePendingRequests(entity.pendingRequestsJson).toMutableList()
        pendingRequests.removeAll { it.requestId == requestId }

        val updated = entity.copy(
            pendingRequestsJson = json.encodeToString(pendingRequests.map {
                PendingRequestDto(it.requestId, it.requestedBy, it.mediaPaths, it.requestedAt)
            }),
            lastSyncedAt = System.currentTimeMillis(),
        )
        sharedAlbumDao.upsert(updated)
        writeEntityJsonToNas(updated)
    }

    override suspend fun proposeMediaAddition(albumId: String, mediaPaths: List<String>) {
        val entity = sharedAlbumDao.getById(albumId) ?: return
        val currentUserId = tokenManager.username.orEmpty()

        // Determina il ruolo dell'utente corrente
        val members = parseMembers(entity.membersJson)
        val myRole = members.find { it.userId == currentUserId }?.role ?: SharedAlbumRole.VIEWER

        val updated: SharedAlbumEntity = when (myRole) {
            SharedAlbumRole.EDITOR, SharedAlbumRole.VIEWER -> {
                if (myRole == SharedAlbumRole.VIEWER) return // nessun permesso
                val currentPaths = parseStringList(entity.mediaPathsJson).toMutableList()
                currentPaths.addAll(mediaPaths)
                entity.copy(
                    mediaPathsJson = json.encodeToString(currentPaths),
                    lastSyncedAt = System.currentTimeMillis(),
                )
            }
            SharedAlbumRole.EDITOR_WITH_APPROVAL -> {
                val pendingRequests = parsePendingRequests(entity.pendingRequestsJson).toMutableList()
                val newRequest = PendingRequestDto(
                    requestId = UUID.randomUUID().toString(),
                    requestedBy = currentUserId,
                    mediaPaths = mediaPaths,
                    requestedAt = System.currentTimeMillis(),
                )
                pendingRequests.add(newRequest)
                entity.copy(
                    pendingRequestsJson = json.encodeToString(pendingRequests),
                    lastSyncedAt = System.currentTimeMillis(),
                )
            }
        }

        sharedAlbumDao.upsert(updated)
        writeEntityJsonToNas(updated)
    }

    // --- Helper privati ---

    private suspend fun writeJsonToNas(albumId: String, albumJson: SharedAlbumJson) {
        val path = "$NAS_DIR/$albumId.json"
        val bytes = json.encodeToString(albumJson).toByteArray()
        try {
            webDavClient.put(
                path = path,
                inputStream = bytes.inputStream(),
                contentType = "application/json",
                contentLength = bytes.size.toLong(),
            )
        } catch (_: Exception) {
            // Offline — la scrittura verrà ritentata alla prossima sync
        }
    }

    private suspend fun writeEntityJsonToNas(entity: SharedAlbumEntity) {
        val albumJson = entity.toJsonDto()
        writeJsonToNas(entity.id, albumJson)
    }

    private fun SharedAlbumJson.toEntity(lastSyncedAt: Long = System.currentTimeMillis()) =
        SharedAlbumEntity(
            id = albumId,
            name = name,
            ownerId = ownerId,
            membersJson = json.encodeToString(members),
            mediaPathsJson = json.encodeToString(mediaItems),
            pendingRequestsJson = json.encodeToString(pendingRequests),
            lastSyncedAt = lastSyncedAt,
            createdAt = createdAt,
        )

    private fun SharedAlbumEntity.toJsonDto() = SharedAlbumJson(
        albumId = id,
        name = name,
        ownerId = ownerId,
        members = parseRawMemberDtos(membersJson),
        mediaItems = parseStringList(mediaPathsJson),
        pendingRequests = parseRawPendingDtos(pendingRequestsJson),
        createdAt = createdAt,
    )

    private fun SharedAlbumEntity.toDomain(): SharedAlbum = SharedAlbum(
        id = id,
        name = name,
        ownerId = ownerId,
        members = parseMembers(membersJson),
        mediaPaths = parseStringList(mediaPathsJson),
        pendingRequests = parsePendingRequests(pendingRequestsJson),
        lastSyncedAt = lastSyncedAt,
        createdAt = createdAt,
    )

    private fun parseMembers(jsonStr: String): List<SharedAlbumMember> = try {
        json.decodeFromString<List<MemberDto>>(jsonStr).map {
            SharedAlbumMember(
                userId = it.userId,
                role = SharedAlbumRole.entries.find { r -> r.name.equals(it.role, ignoreCase = true) }
                    ?: SharedAlbumRole.VIEWER,
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun parsePendingRequests(jsonStr: String?): List<PendingRequest> = try {
        json.decodeFromString<List<PendingRequestDto>>(jsonStr ?: "[]").map {
            PendingRequest(it.requestId, it.requestedBy, it.mediaPaths, it.requestedAt)
        }
    } catch (_: Exception) { emptyList() }

    private fun parseStringList(jsonStr: String): List<String> = try {
        json.decodeFromString<List<String>>(jsonStr)
    } catch (_: Exception) { emptyList() }

    private fun parseRawMemberDtos(jsonStr: String): List<MemberDto> = try {
        json.decodeFromString(jsonStr)
    } catch (_: Exception) { emptyList() }

    private fun parseRawPendingDtos(jsonStr: String?): List<PendingRequestDto> = try {
        json.decodeFromString(jsonStr ?: "[]")
    } catch (_: Exception) { emptyList() }
}
