package com.mycloudgallery.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mycloudgallery.core.database.dao.AlbumDao
import com.mycloudgallery.core.database.dao.FaceEmbeddingDao
import com.mycloudgallery.core.database.dao.MediaFtsDao
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.database.dao.PersonDao
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
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun mediaFtsDao(): MediaFtsDao
    abstract fun albumDao(): AlbumDao
    abstract fun sharedAlbumDao(): SharedAlbumDao
    abstract fun faceEmbeddingDao(): FaceEmbeddingDao
    abstract fun personDao(): PersonDao

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

        /** Aggiunge tabelle persons e face_embeddings per il face recognition (Fase 4). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `persons` (
                        `id` TEXT NOT NULL,
                        `name` TEXT,
                        `representativeFaceId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `photoCount` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `face_embeddings` (
                        `id` TEXT NOT NULL,
                        `mediaItemId` TEXT NOT NULL,
                        `embeddingJson` TEXT NOT NULL,
                        `boundingBoxJson` TEXT NOT NULL,
                        `personId` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`mediaItemId`) REFERENCES `media_items`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_face_embeddings_mediaItemId` ON `face_embeddings` (`mediaItemId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_face_embeddings_personId` ON `face_embeddings` (`personId`)")
            }
        }
    }
}
