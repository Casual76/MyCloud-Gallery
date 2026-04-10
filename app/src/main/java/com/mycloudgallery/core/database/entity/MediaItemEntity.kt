package com.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_items",
    indices = [
        Index("createdAt"),
        Index("isInTrash"),
        Index("isFavorite"),
        Index("isIndexed"),
        Index("webDavPath", unique = true),
    ],
)
data class MediaItemEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val webDavPath: String,
    val thumbnailCachePath: String?,
    val isVideo: Boolean,
    val videoDuration: Long?,
    val width: Int?,
    val height: Int?,
    // EXIF
    val exifLatitude: Double?,
    val exifLongitude: Double?,
    val exifCameraModel: String?,
    val exifIso: Int?,
    val exifFocalLength: Float?,
    // AI (popolati in Fase 2)
    val aiLabels: String?,
    val aiScenes: String?,
    val aiOcrText: String?,
    @ColumnInfo(defaultValue = "0") val isIndexed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isInTrash: Boolean = false,
    val trashedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    // Hash percettivo per duplicati (Fase 2)
    val perceptualHash: String? = null,
    val duplicateGroupId: String? = null,
)
