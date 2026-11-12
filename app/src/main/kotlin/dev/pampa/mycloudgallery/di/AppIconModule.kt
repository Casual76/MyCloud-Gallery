package dev.pampa.mycloudgallery.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.appicon.AndroidLauncherComponents
import dev.pampa.mycloudgallery.appicon.AppIconSwitcher
import dev.pampa.mycloudgallery.appicon.PrefsAppIconStore
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconSettings
import javax.inject.Singleton

/**
 * I5 (12/11/2026): l'icona dell'app a scelta. Un solo [AppIconSwitcher] per processo: lo installa `MyCloudGalleryApp.onCreate` (conta le activity per
 * cambiare l'ingresso del launcher in secondo piano) e lo usano le Impostazioni (Icona dell'app) attraverso la porta [AppIconSettings].
 */
@Module
@InstallIn(SingletonComponent::class)
object AppIconModule {

  @Provides
  @Singleton
  fun appIconSwitcher(@ApplicationContext context: Context): AppIconSwitcher =
    AppIconSwitcher(
      store = PrefsAppIconStore(context),
      components = AndroidLauncherComponents(context),
      art = AppIconSwitcher.ART,
      worker = AppIconSwitcher.serialWorker(),
    )

  @Provides
  fun appIconSettings(switcher: AppIconSwitcher): AppIconSettings = switcher
}
