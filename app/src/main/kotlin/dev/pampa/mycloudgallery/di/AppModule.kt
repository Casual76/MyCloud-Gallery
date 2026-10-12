package dev.pampa.mycloudgallery.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.BuildConfig
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import dev.pampa.mycloudgallery.core.common.diagnostics.FileDiagnosticsLogStore
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettingsStore
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Le impostazioni del blocco app (il flusso di `AppLockSettingsStore`). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LockSettings

/**
 * I legami dell'app a livello di processo che non dipendono dal grafo della Fase 2 (dispatcher, blocco app, anello
 * della Diagnostica, funzioni sperimentali). I moduli `core-*` non conoscono Hilt (D7): li cabla qui `:app`. I
 * contratti della Fase 2 (rete, NAS, famiglia, regole) sono in [GraphModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

  @Provides
  @Singleton
  fun provideDispatchers(): DispatcherProvider = DefaultDispatcherProvider

  /** Unico per processo: il gate presume un solo controller e una sola activity. */
  @Provides
  @Singleton
  fun provideAppLockController(): AppLockController = AppLockController()

  @Provides
  @LockSettings
  fun provideLockSettings(@ApplicationContext context: Context): Flow<@JvmSuppressWildcards AppLockSettings> =
    AppLockSettingsStore.getInstance(context).settings

  /** In release `wol` e' spento comunque (FeatureFlags.forBuild ignora la richiesta). */
  @Provides
  @Singleton
  fun provideFeatureFlags(): FeatureFlags = FeatureFlags.forBuild(debugBuild = BuildConfig.DEBUG)

  /** L'anello degli ultimi 500 eventi, in noBackupFilesDir. */
  @Provides
  @Singleton
  fun provideDiagnosticsLog(@ApplicationContext context: Context): DiagnosticsLog =
    RingDiagnosticsLog(store = FileDiagnosticsLogStore.inNoBackupDir(context))
}
