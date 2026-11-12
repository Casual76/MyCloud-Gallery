package dev.pampa.mycloudgallery.di

import android.app.ActivityManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.BuildConfig
import dev.pampa.mycloudgallery.feature.settings.update.UpdateEntryStatus
import dev.pampa.mycloudgallery.feature.settings.update.UpdateEntrySummary
import dev.pampa.mycloudgallery.feature.update.AppUpdates
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Pacchetto U (12/11/2026): l'aggiornamento dentro l'app. Un solo [AppUpdates] per processo, con uno scope suo (un download continua anche
 * cambiando pagina) e l'ora del telefono per le 24 ore fra un controllo silenzioso e l'altro (feature-update non legge l'orologio). Il `versionName`
 * e' quello INTERO della build (`1.1.0-beta.02`, e `-debug` nella build di lavoro): la beta deve poter ricevere la stabile omonima. La voce delle
 * Impostazioni ([UpdateEntryStatus]) dice la versione nuova trovata.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

  @Provides
  @Singleton
  fun appUpdates(@ApplicationContext context: Context): AppUpdates = AppUpdates.create(
    context = context,
    versionName = BuildConfig.VERSION_NAME,
    now = System::currentTimeMillis,
    foreground = ::appInFront,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
  )

  /**
   * L'app e' davanti (un'activity visibile in cima): solo allora il file pronto va alla sessione di Android, che mostra la sua finestra (giro 1, UU-1).
   * `IMPORTANCE_FOREGROUND` e non `VISIBLE`: un servizio in primo piano (il backup) non basta, la finestra non si aprirebbe.
   */
  private fun appInFront(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
  }

  @Provides
  @Singleton
  fun updateEntryStatus(updates: AppUpdates): UpdateEntryStatus = object : UpdateEntryStatus {
    override val summary: StateFlow<UpdateEntrySummary> = updates.state
      .map { UpdateEntrySummary(available = it.found?.version) }
      .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), SharingStarted.Eagerly, UpdateEntrySummary(updates.state.value.found?.version))
  }
}
