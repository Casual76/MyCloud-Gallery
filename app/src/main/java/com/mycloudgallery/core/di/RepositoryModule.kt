package com.mycloudgallery.core.di

import com.mycloudgallery.data.repository.AlbumRepositoryImpl
import com.mycloudgallery.data.repository.AuthRepositoryImpl
import com.mycloudgallery.data.repository.MediaRepositoryImpl
import com.mycloudgallery.data.repository.SearchRepositoryImpl
import com.mycloudgallery.data.repository.SettingsRepositoryImpl
import com.mycloudgallery.data.repository.SharedAlbumRepositoryImpl
import com.mycloudgallery.domain.repository.AlbumRepository
import com.mycloudgallery.domain.repository.AuthRepository
import com.mycloudgallery.domain.repository.MediaRepository
import com.mycloudgallery.domain.repository.SearchRepository
import com.mycloudgallery.domain.repository.SettingsRepository
import com.mycloudgallery.domain.repository.SharedAlbumRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    @Binds @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindAlbumRepository(impl: AlbumRepositoryImpl): AlbumRepository

    @Binds @Singleton
    abstract fun bindSharedAlbumRepository(impl: SharedAlbumRepositoryImpl): SharedAlbumRepository
}
