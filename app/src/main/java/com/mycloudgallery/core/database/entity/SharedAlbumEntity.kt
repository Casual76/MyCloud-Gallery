package com.mycloudgallery.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Fase 3 — Album condiviso tra membri della famiglia via NAS */
@Entity(tableName = "shared_albums")
data class SharedAlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerId: String,
    /** JSON array di membri: [{"userId":"x","role":"viewer"}] */
    val membersJson: String,
    /** JSON array di path WebDAV dei media inclusi */
    val mediaPathsJson: String,
    /** JSON array di richieste pendenti */
    val pendingRequestsJson: String?,
    val lastSyncedAt: Long,
    val createdAt: Long,
)
