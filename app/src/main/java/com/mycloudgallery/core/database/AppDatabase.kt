package com.mycloudgallery.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mycloudgallery.core.database.dao.AlbumDao
import com.mycloudgallery.core.database.dao.MediaFtsDao
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.database.dao.SharedAlbumDao
import com.mycloudgallery.core.database.entity.AlbumEntity
import com.mycloudgallery.core.database.entity.AlbumMediaCrossRef
import com.mycloudgallery.core.database.entity.FaceEmbeddingEntity
import com.mycloudgallery.core.database.entity.MediaFtsEntity
import com.mycloudgallery.core.database.entity.MediaItemEntity
import com.mycloudgallery.core.database.entity.PersonEntity
import com.mycloudgallery.core.database.entity.SharedAlbumEntity

@Database(
    entities = [
        MediaItemEntity::class,
        MediaFtsEntity::class,
        AlbumEntity::class,
        AlbumMediaCrossRef::class,
        PersonEntity::class,
        FaceEmbeddingEntity::class,
        SharedAlbumEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun mediaFtsDao(): MediaFtsDao
    abstract fun albumDao(): AlbumDao
    abstract fun sharedAlbumDao(): SharedAlbumDao

    companion object {
        /** Aggiunge tabella FTS4 per ricerca full-text (Fase 2). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE VIRTUAL TABLE IF NOT EXISTS `media_fts`
                       USING fts4(
                           `itemId`, `fileName`,
                           `aiLabels`, `aiOcrText`, `aiScenes`
                       )"""
                )
            }
        }
    }
}
