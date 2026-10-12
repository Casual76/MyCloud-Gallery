package dev.pampa.mycloudgallery.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import javax.inject.Singleton

/** I legami dell'app a livello di processo. I moduli `core-*` non conoscono Hilt: li cabla qui `:app`. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

  @Provides
  @Singleton
  fun provideDispatchers(): DispatcherProvider = DefaultDispatcherProvider
}
