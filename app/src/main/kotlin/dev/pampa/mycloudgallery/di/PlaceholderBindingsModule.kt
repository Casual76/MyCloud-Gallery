package dev.pampa.mycloudgallery.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.family.contract.AdminRecovery
import dev.pampa.mycloudgallery.core.family.contract.DeviceKeyQr
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.FamilyReset
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
import dev.pampa.mycloudgallery.core.family.noop.ClosedTrustGuard
import dev.pampa.mycloudgallery.core.family.noop.IdleOnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.noop.NoAdminRecovery
import dev.pampa.mycloudgallery.core.family.noop.NoFamilyReset
import dev.pampa.mycloudgallery.core.family.noop.NoFolderSurvey
import dev.pampa.mycloudgallery.core.family.noop.NoJoinFlow
import dev.pampa.mycloudgallery.core.family.noop.NoLostReporting
import dev.pampa.mycloudgallery.core.family.noop.NoRegistryService
import dev.pampa.mycloudgallery.core.family.noop.NoSelfWipe
import dev.pampa.mycloudgallery.core.family.noop.NoTrustStateStore
import dev.pampa.mycloudgallery.core.family.noop.NoTrustSyncScheduler
import dev.pampa.mycloudgallery.core.family.noop.RejectingQrCodec
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.NasHousekeeping
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.ShareProbe
import dev.pampa.mycloudgallery.core.nas.noop.IdleAuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.noop.NoConnectionTester
import dev.pampa.mycloudgallery.core.nas.noop.NoNasHousekeeping
import dev.pampa.mycloudgallery.core.nas.noop.NoNasSession
import dev.pampa.mycloudgallery.core.nas.noop.NoShareProbe
import dev.pampa.mycloudgallery.core.network.contract.HomeCapture
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.contract.NasDiscovery
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomation
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.WakeOnLan
import dev.pampa.mycloudgallery.core.network.noop.ClosedNasRouteGate
import dev.pampa.mycloudgallery.core.network.noop.DisabledWakeOnLan
import dev.pampa.mycloudgallery.core.network.noop.NoHomeCapture
import dev.pampa.mycloudgallery.core.network.noop.NoHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.noop.NoNasDiscovery
import dev.pampa.mycloudgallery.core.network.noop.NoReachabilityProbe
import dev.pampa.mycloudgallery.core.network.noop.NoVpnAutomation
import dev.pampa.mycloudgallery.core.network.noop.NoVpnController
import dev.pampa.mycloudgallery.core.network.noop.OfflineNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.noop.UnknownLocalNetworkPermission
import dev.pampa.mycloudgallery.core.qr.contract.QrCodec
import dev.pampa.mycloudgallery.core.qr.noop.UnavailableQrCodec
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealthReader
import dev.pampa.mycloudgallery.core.rules.platform.health.UnknownBackgroundHealthReader

/**
 * I contratti della Fase 2 legati ai loro **null-object** (P0). Tutti "falliscono chiusi": nessuna rete,
 * nessun percorso verso il NAS, nessuna autenticazione, nessuna scrittura, dispositivo mai configurato.
 *
 * P9 sostituisce ogni riga con l'implementazione vera (P1-P8) senza toccare chi le usa. Lo stato del
 * dispositivo (`FamilySetupRepository`) non e' qui: e' in `SetupStateModule`, diverso fra debug (un fake
 * pilotabile dal pannello "Sviluppo") e release (il null-object).
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaceholderBindingsModule {

  // --- core-network (P2) ----------------------------------------------------------------------------
  @Provides fun networkEnvironment(): NetworkEnvironment = OfflineNetworkEnvironment

  @Provides fun nasRouteGate(): NasRouteGate = ClosedNasRouteGate

  @Provides fun homeFingerprintStore(): HomeFingerprintStore = NoHomeFingerprintStore

  @Provides fun homeCapture(): HomeCapture = NoHomeCapture

  @Provides fun vpnController(): VpnController = NoVpnController

  @Provides fun vpnAutomation(): VpnAutomation = NoVpnAutomation

  @Provides fun localNetworkPermission(): LocalNetworkPermission = UnknownLocalNetworkPermission

  @Provides fun nasDiscovery(): NasDiscovery = NoNasDiscovery

  @Provides fun reachabilityProbe(): ReachabilityProbe = NoReachabilityProbe

  @Provides fun wakeOnLan(): WakeOnLan = DisabledWakeOnLan

  // --- core-nas (P1) --------------------------------------------------------------------------------
  @Provides fun nasSessionManager(): NasSessionManager = NoNasSession

  @Provides fun authCircuitBreaker(): AuthCircuitBreaker = IdleAuthCircuitBreaker

  @Provides fun connectionTester(): ConnectionTester = NoConnectionTester

  @Provides fun shareProbe(): ShareProbe = NoShareProbe

  @Provides fun nasHousekeeping(): NasHousekeeping = NoNasHousekeeping

  // --- core-family (P3, P4) -------------------------------------------------------------------------
  @Provides fun onboardingCoordinator(): OnboardingCoordinator = IdleOnboardingCoordinator

  @Provides fun joinFlow(): JoinFlow = NoJoinFlow

  @Provides fun registryService(): RegistryService = NoRegistryService

  @Provides fun trustGuard(): TrustGuard = ClosedTrustGuard

  @Provides fun trustStateStore(): TrustStateStore = NoTrustStateStore

  @Provides fun selfWipe(): SelfWipe = NoSelfWipe

  @Provides fun adminRecovery(): AdminRecovery = NoAdminRecovery

  @Provides fun familyReset(): FamilyReset = NoFamilyReset

  @Provides fun lostReporting(): LostReporting = NoLostReporting

  @Provides fun trustSyncScheduler(): TrustSyncScheduler = NoTrustSyncScheduler

  @Provides fun folderSurvey(): FolderSurvey = NoFolderSurvey

  @Provides fun inviteCodec(): QrPayloadCodec<FamilyInvite> = RejectingQrCodec()

  @Provides fun deviceKeyCodec(): QrPayloadCodec<DeviceKeyQr> = RejectingQrCodec()

  @Provides fun familyStateCodec(): QrPayloadCodec<FamilyStateQr> = RejectingQrCodec()

  // --- core-qr (P5) e core-rules (P8) ---------------------------------------------------------------
  @Provides fun qrCodec(): QrCodec = UnavailableQrCodec

  @Provides fun backgroundHealthReader(): BackgroundHealthReader = UnknownBackgroundHealthReader
}

/**
 * I contributi alla Diagnostica: un insieme vuoto finche' P9 non aggiunge quelli di rete, NAS, famiglia e
 * salute del background (`@IntoSet`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsBindingsModule {
  @Multibinds
  abstract fun diagnosticsContributors(): Set<DiagnosticsContributor>
}
