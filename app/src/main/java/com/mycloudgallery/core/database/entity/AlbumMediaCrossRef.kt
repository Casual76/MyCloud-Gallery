package com.mycloudgallery.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "album_media_items",
    primaryKeys = ["albumId", "mediaItemId"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("albumId"),
        Index("mediaItemId"),
    ],
)
data class AlbumMediaCrossRef(
    val albumId: String,
    val mediaItemId: String,
    val addedAt: Long = System.currentTimeMillis(),
)
