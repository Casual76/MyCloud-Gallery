package com.mycloudgallery.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Fase 4 — Embedding vettoriale di un volto rilevato in una foto */
@Entity(
    tableName = "face_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("mediaItemId"),
        Index("personId"),
    ],
)
data class FaceEmbeddingEntity(
    @PrimaryKey val id: String,
    val mediaItemId: String,
    val embeddingJson: String,
    val boundingBoxJson: String,
    val personId: String? = null,
)
