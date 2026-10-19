package dev.pampa.mycloudgallery.feature.diagnostics.di

import android.content.Context
import android.os.SystemClock
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import dev.pampa.mycloudgallery.core.rules.ActivityRulesEngine
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealthReader
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestInputSource
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.ActivitiesContributor
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.AndroidDeviceSecuritySource
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.AppStorageSource
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.BackgroundHealthContributor
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.DeviceSecuritySource
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.HeartbeatSource
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.RecentEventsContributor
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.StorageContributor
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.TrafficContributor
import java.util.Optional

/**
 * Le dipendenze che `:app` collega **quando le ha** (P9): finche' non ci sono, la Diagnostica funziona lo stesso,
 * con la sezione corrispondente vuota. `@BindsOptionalOf` e' proprio questo: `Optional.empty()` se nessuno ha
 * dichiarato il legame, il valore se c'e'. Cosi' `:app` compila prima e dopo senza toccare questo modulo.
 *
 * Cosa deve legare `:app` (docs/fase2-progetto.md §4.3, P9):
 *  - `ActivityRulesEngine` → la sezione «Attivita'» (verdetti di `evaluateAll`);
 *  - `TrafficLedger` → «Consumi del mese»;
 *  - [ConnectionTestInputSource] → il test di connessione (l'account NAS di questo dispositivo);
 *  - [HeartbeatSource] → «ore dall'ultimo battito» in «Profili» e l'avviso «lavoro fermo da giorni».
 *
 * `BackgroundHealthReader` e' gia' legato da `:app` (oggi al null-object): basta sostituirlo con
 * `AndroidBackgroundHealthReader(context)` perche' «Avvisi di Android» mostri i valori veri.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsOptionalBindings {

  @BindsOptionalOf
  abstract fun activityRulesEngine(): ActivityRulesEngine

  @BindsOptionalOf
  abstract fun trafficLedger(): TrafficLedger

  @BindsOptionalOf
  abstract fun connectionTestInputSource(): ConnectionTestInputSource

  @BindsOptionalOf
  abstract fun heartbeatSource(): HeartbeatSource
}

/**
 * I contributori di questo modulo (le sezioni che hanno come fonte `core-rules`, `core-common` o il telefono
 * stesso), aggiunti al multibinding `Set<DiagnosticsContributor>` che `:app` dichiara con `@Multibinds`.
 * Quelli di rete, NAS e famiglia li aggiungono i rispettivi moduli attraverso `:app`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsContributorsModule {

  @Provides
  fun deviceSecuritySource(@ApplicationContext context: Context): DeviceSecuritySource = AndroidDeviceSecuritySource(context)

  @Provides
  @IntoSet
  fun backgroundHealth(
    reader: BackgroundHealthReader,
    security: DeviceSecuritySource,
    heartbeat: Optional<HeartbeatSource>,
  ): DiagnosticsContributor = BackgroundHealthContributor(
    reader = reader,
    security = security,
    heartbeat = heartbeat.orElse(null),
    uptimeMillis = SystemClock::elapsedRealtime,
  )

  @Provides
  @IntoSet
  fun storage(@ApplicationContext context: Context): DiagnosticsContributor = StorageContributor(AppStorageSource(context))

  @Provides
  @IntoSet
  fun activities(engine: Optional<ActivityRulesEngine>): DiagnosticsContributor =
    ActivitiesContributor { if (engine.isPresent) engine.get().evaluateAll() else emptyMap() }

  @Provides
  @IntoSet
  fun traffic(ledger: Optional<TrafficLedger>): DiagnosticsContributor =
    TrafficContributor(ledger.orElse(null))

  @Provides
  @IntoSet
  fun recentEvents(@ApplicationContext context: Context, log: DiagnosticsLog): DiagnosticsContributor =
    RecentEventsContributor(log, text = { resId -> if (resId == 0) "" else context.getString(resId) })
}
