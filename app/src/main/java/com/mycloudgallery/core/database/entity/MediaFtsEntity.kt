package com.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

/**
 * Tabella FTS4 virtuale per ricerca full-text su label AI, testo OCR e nome file.
 * Viene aggiornata dall'IndexingWorker dopo ogni indicizzazione.
 */
@Fts4
@Entity(tableName = "media_fts")
data class MediaFtsEntity(
    /** Corrisponde a MediaItemEntity.id */
    val itemId: String,
    val fileName: String,
    @ColumnInfo(defaultValue = "") val aiLabels: String,
    @ColumnInfo(defaultValue = "") val aiOcrText: String,
    @ColumnInfo(defaultValue = "") val aiScenes: String,
)
