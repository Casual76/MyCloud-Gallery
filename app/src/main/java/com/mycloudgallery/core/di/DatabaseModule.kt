package com.mycloudgallery.core.di

import android.content.Context
import androidx.room.Room
import com.mycloudgallery.core.database.AppDatabase
import com.mycloudgallery.core.database.AppDatabase.Companion.MIGRATION_1_2
import com.mycloudgallery.core.database.dao.AlbumDao
import com.mycloudgallery.core.database.dao.MediaFtsDao
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.database.dao.SharedAlbumDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "mycloudgallery.db",
        )
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideMediaItemDao(db: AppDatabase): MediaItemDao = db.mediaItemDao()

    @Provides
    fun provideMediaFtsDao(db: AppDatabase): MediaFtsDao = db.mediaFtsDao()

    @Provides
    fun provideAlbumDao(db: AppDatabase): AlbumDao = db.albumDao()

    @Provides
    fun provideSharedAlbumDao(db: AppDatabase): SharedAlbumDao = db.sharedAlbumDao()
}
