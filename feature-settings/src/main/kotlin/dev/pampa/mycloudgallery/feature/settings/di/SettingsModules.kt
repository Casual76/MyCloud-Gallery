package dev.pampa.mycloudgallery.feature.settings.di

import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.security.device.ScreenLockCheck
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettingsStore
import dev.pampa.mycloudgallery.feature.settings.port.AppLockPreferences
import dev.pampa.mycloudgallery.feature.settings.port.NasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.port.ScreenLockProbe
import dev.pampa.mycloudgallery.feature.settings.port.SettingsClock
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualitySettings
import kotlinx.coroutines.flow.Flow

/** I piccoli adattatori che le Impostazioni legano da sole: nessuna riga in :app. */
@Module
@InstallIn(SingletonComponent::class)
object SettingsProvidesModule {

  @Provides
  fun settingsClock(): SettingsClock = SettingsClock { System.currentTimeMillis() }

  @Provides
  fun appLockPreferences(@ApplicationContext context: Context): AppLockPreferences = StoreAppLockPreferences(context)

  @Provides
  fun screenLockProbe(@ApplicationContext context: Context): ScreenLockProbe = ScreenLockProbe { ScreenLockCheck.status(context) }
}

/**
 * Cio' che puo' mancare: dichiarato "opzionale" per Dagger, cosi' `:app` compila prima che P9 leghi
 * l'implementazione vera ([NasAccountSettings]). Un solo `@Provides NasAccountSettings` in :app lo accende.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsOptionalBindingsModule {

  @BindsOptionalOf
  abstract fun nasAccountSettings(): NasAccountSettings

  /** La qualita' delle anteprime del NAS (Fase 4A, P11): `:app` la lega al grafo del NAS. */
  @BindsOptionalOf
  abstract fun previewQualitySettings(): PreviewQualitySettings
}

/** [AppLockPreferences] sopra l'unico archivio del processo (`AppLockSettingsStore.getInstance`). */
internal class StoreAppLockPreferences(context: Context) : AppLockPreferences {

  private val store: AppLockSettingsStore by lazy { AppLockSettingsStore.getInstance(context.applicationContext) }

  override val settings: Flow<AppLockSettings> get() = store.settings

  override suspend fun update(settings: AppLockSettings) = store.update(settings)
}
