package dev.pampa.mycloudgallery.di

import android.content.Context
import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import dev.pampa.mycloudgallery.BuildConfig
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.family.contract.AdminRecovery
import dev.pampa.mycloudgallery.core.family.contract.DeviceKeyQr
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.FamilyReset
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.FolderSurvey
import dev.pampa.mycloudgallery.core.family.contract.JoinFlow
import dev.pampa.mycloudgallery.core.family.contract.LostReporting
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.QrPayloadCodec
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.SelfWipe
import dev.pampa.mycloudgallery.core.family.contract.TrustGuard
import dev.pampa.mycloudgallery.core.family.contract.TrustStateStore
import dev.pampa.mycloudgallery.core.family.contract.TrustSyncScheduler
import dev.pampa.mycloudgallery.core.family.invite.DeviceKeyCodec
import dev.pampa.mycloudgallery.core.family.invite.FamilyStateCodec
import dev.pampa.mycloudgallery.core.family.invite.InviteCodec
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.nas.contract.NasHousekeeping
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.ShareProbe
import dev.pampa.mycloudgallery.core.network.contract.HomeCapture
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.contract.NasDiscovery
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.NasSessionActivity
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomation
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.WakeOnLan
import dev.pampa.mycloudgallery.core.qr.codec.ZxingQrCodec
import dev.pampa.mycloudgallery.core.qr.contract.QrCodec
import dev.pampa.mycloudgallery.core.rules.ActivityRulesEngine
import dev.pampa.mycloudgallery.core.rules.AppContextSignal
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealthReader
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestInputSource
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.HeartbeatSource
import dev.pampa.mycloudgallery.feature.settings.port.NasAccountSettings
import dev.pampa.mycloudgallery.graph.AndroidAppPlatform
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.AppPlatform
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.root.GraphRootPorts
import dev.pampa.mycloudgallery.root.RootPorts
import dev.pampa.mycloudgallery.work.InviteWindowStore
import dev.pampa.mycloudgallery.work.WorkManagerCanceller
import dev.pampa.mycloudgallery.work.WorkManagerTrustSyncScheduler
import java.io.File
import java.util.ServiceLoader
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * I contratti della Fase 2 legati alle implementazioni **vere** di P1-P8 (P9), tutte dentro un solo [AppGraph] per
 * processo. Chi le usa (ViewModel dei `feature-*`, `RootViewModel`, lavori) non cambia: vede solo i contratti.
 *
 * Prima di P9 qui c'erano i null-object (`PlaceholderBindingsModule`) e, in debug, uno stato del dispositivo finto: ora
 * debug e release hanno lo stesso grafo. I fake restano nei package `testing` per i test e non arrivano all'APK di
 * release (tools/check.ps1).
 */
@Module
@InstallIn(SingletonComponent::class)
object GraphModule {

  @Provides
  @Singleton
  fun platform(@ApplicationContext context: Context): AppPlatform = AndroidAppPlatform(context)

  @Provides
  @Singleton
  fun graph(@ApplicationContext context: Context, platform: AppPlatform, flags: FeatureFlags): AppGraph {
    val window = InviteWindowStore(File(context.noBackupFilesDir, WorkManagerTrustSyncScheduler.INVITE_WINDOW_FILE))
    return AppGraph(
      context = context,
      // La vita del processo: niente la cancella. Un errore in un figlio non ferma gli altri.
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
      platform = platform,
      flags = flags,
      appVersion = BuildConfig.VERSION_NAME,
      schedulerFactory = { nasNow ->
        WorkManagerTrustSyncScheduler(context, nasNow, monotonicNow = SystemClock::elapsedRealtime, window = window)
      },
      work = WorkManagerCanceller(context, window),
    )
  }

  @Provides
  @Singleton
  fun notifications(@ApplicationContext context: Context): AppNotifications = AppNotifications(context)

  @Provides
  fun rootPorts(graph: AppGraph): RootPorts = GraphRootPorts(graph)

  // --- core-network (P2) ----------------------------------------------------------------------------
  @Provides fun networkEnvironment(graph: AppGraph): NetworkEnvironment = graph.environment

  @Provides fun nasRouteGate(graph: AppGraph): NasRouteGate = graph.routeGate

  @Provides fun homeFingerprintStore(graph: AppGraph): HomeFingerprintStore = graph.homeStore

  @Provides fun homeCapture(graph: AppGraph): HomeCapture = graph.homeCapture

  @Provides fun vpnController(graph: AppGraph): VpnController = graph.wireGuard

  @Provides fun vpnAutomation(graph: AppGraph): VpnAutomation = graph.vpnAutomation

  @Provides fun vpnControlConfigStore(graph: AppGraph): VpnControlConfigStore = graph.vpnConfigStore

  @Provides fun nasSessionActivity(graph: AppGraph): NasSessionActivity = graph.sessionActivity

  @Provides fun localNetworkPermission(graph: AppGraph): LocalNetworkPermission = graph.platform.localNetworkPermission

  @Provides fun nasDiscovery(graph: AppGraph): NasDiscovery = graph.discovery

  @Provides fun reachabilityProbe(graph: AppGraph): ReachabilityProbe = graph.reachability

  @Provides fun wakeOnLan(graph: AppGraph): WakeOnLan = graph.wakeOnLan

  // --- core-nas (P1) --------------------------------------------------------------------------------
  @Provides fun nasSessionManager(graph: AppGraph): NasSessionManager = graph.session

  @Provides fun authCircuitBreaker(graph: AppGraph): AuthCircuitBreaker = graph.breaker

  @Provides fun connectionTester(graph: AppGraph): ConnectionTester = graph.connectionTester

  @Provides fun shareProbe(graph: AppGraph): ShareProbe = graph.shareProbe

  @Provides fun nasHousekeeping(graph: AppGraph): NasHousekeeping = graph.housekeeping

  /** Il ponte fra core-nas e il Keystore (`CredentialVault`): la password si legge solo quando una sessione la chiede. */
  @Provides fun nasCredentialSource(graph: AppGraph): NasCredentialSource = graph.credentialSource

  // --- core-family (P3, P4) -------------------------------------------------------------------------
  /**
   * Lo stato del dispositivo. Due eccezioni, mai nella release:
   * - la variante di misura (`benchmarkRelease`, P9) registra con `ServiceLoader` la famiglia "pronta" senza NAS
   *   (docs/bench/README.md §6); nelle altre varianti `ServiceLoader` non trova niente;
   * - la debug, **solo** con il marcatore scritto da `adb` sull'emulatore (`DeveloperTools.familyOverride`), per lo smoke
   *   della Libreria senza un NAS. In release `familyOverride` e' sempre null.
   */
  @Provides
  @Singleton
  fun familySetupRepository(@ApplicationContext context: Context, graph: AppGraph): FamilySetupRepository =
    ServiceLoader.load(FamilySetupRepository::class.java, FamilySetupRepository::class.java.classLoader).firstOrNull()
      ?: DeveloperTools.familyOverride(context)
      ?: graph.setup

  @Provides fun onboardingCoordinator(graph: AppGraph): OnboardingCoordinator = graph.coordinator

  @Provides fun joinFlow(graph: AppGraph): JoinFlow = graph.joinFlow

  @Provides fun registryService(graph: AppGraph): RegistryService = graph.registry

  @Provides fun trustGuard(graph: AppGraph): TrustGuard = graph.trustGuard

  @Provides fun trustStateStore(graph: AppGraph): TrustStateStore = graph.trustStore

  @Provides fun selfWipe(graph: AppGraph): SelfWipe = graph.selfWipe

  @Provides fun adminRecovery(graph: AppGraph): AdminRecovery = graph.adminRecovery

  @Provides fun familyReset(graph: AppGraph): FamilyReset = graph.familyReset

  @Provides fun lostReporting(graph: AppGraph): LostReporting = graph.lostReporting

  @Provides fun trustSyncScheduler(graph: AppGraph): TrustSyncScheduler = graph.scheduler

  @Provides fun folderSurvey(graph: AppGraph): FolderSurvey = graph.folderSurvey

  @Provides fun inviteCodec(): QrPayloadCodec<FamilyInvite> = InviteCodec

  @Provides fun deviceKeyCodec(): QrPayloadCodec<DeviceKeyQr> = DeviceKeyCodec

  @Provides fun familyStateCodec(): QrPayloadCodec<FamilyStateQr> = FamilyStateCodec

  // --- core-qr (P5) e core-rules (P8) ---------------------------------------------------------------
  @Provides fun qrCodec(): QrCodec = ZxingQrCodec

  @Provides fun backgroundHealthReader(graph: AppGraph): BackgroundHealthReader = graph.platform.healthReader

  @Provides fun activityRulesEngine(graph: AppGraph): ActivityRulesEngine = graph.rulesEngine

  @Provides fun ruleSettingsStore(graph: AppGraph): RuleSettingsStore = graph.ruleSettings

  @Provides fun trafficLedger(graph: AppGraph): TrafficLedger = graph.trafficLedger

  @Provides fun appContextSignal(graph: AppGraph): AppContextSignal = graph.appContext

  // --- Porte dei feature-* che solo :app sa riempire (P7, P8) ----------------------------------------
  @Provides fun nasAccountSettings(graph: AppGraph): NasAccountSettings = graph.nasAccountSettings

  @Provides fun connectionTestInputSource(graph: AppGraph): ConnectionTestInputSource = graph.connectionTestInput

  @Provides fun heartbeatSource(graph: AppGraph): HeartbeatSource = graph.heartbeat

  // --- Diagnostica: i contributori di rete, NAS e famiglia (quelli di core-rules li aggiunge feature-diagnostics) --
  @Provides
  @IntoSet
  fun networkDiagnostics(graph: AppGraph): DiagnosticsContributor = graph.networkDiagnostics

  @Provides
  @IntoSet
  fun trustDiagnostics(graph: AppGraph): DiagnosticsContributor = graph.trustDiagnostics
}

/** Il multibinding dei contributi alla Diagnostica (lo riempiono [GraphModule] e `feature-diagnostics`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsBindingsModule {
  @Multibinds
  abstract fun diagnosticsContributors(): Set<DiagnosticsContributor>
}
