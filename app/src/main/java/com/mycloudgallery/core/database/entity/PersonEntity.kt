package com.mycloudgallery.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Fase 4 — Identità persona riconosciuta dal face recognition */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String?,
    val representativeFaceId: String?,
    val createdAt: Long,
    val photoCount: Int = 0,
)
