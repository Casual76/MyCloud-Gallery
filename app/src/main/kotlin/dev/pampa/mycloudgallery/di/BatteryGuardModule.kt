package dev.pampa.mycloudgallery.di

import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardPort
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.rules.AppBatteryGuard
import dev.pampa.mycloudgallery.work.BatteryResume
import javax.inject.Singleton

/**
 * Pacchetto B (seconda beta): la porta del controllo «livello di batteria» (`GalleryBatteryGuard`), una sola per tutte le pagine che lo ospitano (impostazioni del Backup e
 * dell'analisi). Le impostazioni delle regole sono le stesse del valutatore: cio' che l'utente sceglie qui e' cio' che le regole leggono al prossimo giro.
 * La dichiarazione opzionale sta qui (e non in un `feature-*`, che non si conoscono fra loro): chi la riceve e' gia' pronto a non disegnare niente senza la porta.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BatteryGuardModule {

  @BindsOptionalOf abstract fun optionalBatteryGuard(): BatteryGuardPort

  companion object {
    @Provides @Singleton
    fun batteryGuard(@ApplicationContext context: Context, store: RuleSettingsStore, graph: AppGraph): BatteryGuardPort =
      AppBatteryGuard(store, graph.scope, onRelaxed = { relaxed -> BatteryResume.resume(context, relaxed) })
  }
}
