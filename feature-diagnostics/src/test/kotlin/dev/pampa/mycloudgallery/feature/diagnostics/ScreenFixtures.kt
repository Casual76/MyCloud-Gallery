package dev.pampa.mycloudgallery.feature.diagnostics

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagEvent
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.CapKind
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitReasonKind
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitRecord
import dev.pampa.mycloudgallery.core.rules.platform.health.HealthPermission
import dev.pampa.mycloudgallery.core.rules.platform.health.PermissionGrant
import dev.pampa.mycloudgallery.core.rules.platform.health.StandbyBucket
import dev.pampa.mycloudgallery.core.rules.platform.health.UnusedAppRestrictions
import dev.pampa.mycloudgallery.core.rules.platform.health.testing.FakeBackgroundHealthReader
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsAggregator
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestOutcome
import dev.pampa.mycloudgallery.feature.diagnostics.connection.NegotiationSummary
import dev.pampa.mycloudgallery.feature.diagnostics.connection.StageRow
import dev.pampa.mycloudgallery.feature.diagnostics.connection.toItems
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.ActivitiesContributor
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.BackgroundHealthContributor
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.DeviceSecuritySource
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.HeartbeatSource
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.RecentEventsContributor
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.StorageContributor
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.StorageSnapshot
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.TrafficContributor
import dev.pampa.mycloudgallery.feature.diagnostics.export.DiagnosticsExporter
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportEnvironment
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking

/**
 * Gli stati delle schermate per i test e gli screenshot: voci vere (prodotte dai contributori di questo modulo con
 * dati finti) e voci scritte come le scriverebbero rete, NAS e famiglia. Niente di personale: e' la fotografia di
 * come appare la Diagnostica in giornata brutta e in giornata buona.
 */
internal object ScreenFixtures {

  private const val NOW = 1_800_000_000_000L

  private fun passthrough(text: String) = DiagText(R.string.diagnostics_passthrough, listOf(text))

  // --- Rete e NAS, Fiducia: scritte come le scriverebbero i moduli core ----------------------------------------

  fun networkItems(): List<DiagItem> = listOf(
    item(DiagCodes.NET_PLACE, severity = DiagSeverity.OK, detail = passthrough("Fuori casa · via VPN (Wi-Fi)"), export = mapOf("place" to ExportValue.Flag(true))),
    item(DiagCodes.NAS_SESSION_OK, severity = DiagSeverity.OK, detail = passthrough("SMB 3.1.1 · AES-128-GCM · firma richiesta · cifrata: sì")),
    item(DiagCodes.NET_FOREIGN_VPN, severity = DiagSeverity.ERROR),
    item(DiagCodes.NAS_AUTH_BLOCKED, severity = DiagSeverity.ERROR),
    item(DiagCodes.NET_IPV4_ONLY_VPN, severity = DiagSeverity.WARNING),
    item(DiagCodes.NET_METERED, severity = DiagSeverity.INFO),
  )

  fun trustItems(): List<DiagItem> = listOf(
    item(DiagCodes.TRUST_APPROVED, DiagSection.TRUST, DiagSeverity.OK, detail = passthrough("Registro v12 · 3 dispositivi approvati")),
    item(DiagCodes.TRUST_PENDING_REQUESTS, DiagSection.TRUST, DiagSeverity.WARNING),
    item(DiagCodes.TRUST_LOOKALIKE, DiagSection.TRUST, DiagSeverity.WARNING),
    item(DiagCodes.TRUST_REJECTIONS, DiagSection.TRUST, DiagSeverity.INFO, detail = passthrough("3 dati scartati per firma non valida")),
  )

  // --- Dai contributori di questo modulo -------------------------------------------------------------------------

  fun unhealthyHealth() = healthyHealth().copy(
    ignoringBatteryOpt = false,
    standbyBucket = StandbyBucket.RESTRICTED,
    unusedAppRestrictions = UnusedAppRestrictions.ENABLED,
    permissions = HealthPermission.entries.associateWith { PermissionGrant.GRANTED } + mapOf(
      HealthPermission.NOTIFICATIONS to PermissionGrant.DENIED,
      HealthPermission.MEDIA_IMAGES to PermissionGrant.DENIED,
      HealthPermission.MEDIA_VIDEO to PermissionGrant.DENIED,
      HealthPermission.MEDIA_PARTIAL to PermissionGrant.GRANTED,
    ),
    isSystemUser = false,
    lastExitReasons = listOf(
      ExitRecord(ExitReasonKind.LOW_MEMORY, NOW - 3.hours.inWholeMilliseconds),
      ExitRecord(ExitReasonKind.USER_REQUESTED, NOW - 30.hours.inWholeMilliseconds),
    ),
  )

  fun healthContributor(health: dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth) = BackgroundHealthContributor(
    reader = FakeBackgroundHealthReader(health),
    security = DeviceSecuritySource { true },
    heartbeat = HeartbeatSource { NOW - 31.hours.inWholeMilliseconds },
    nowMillis = { NOW },
    uptimeMillis = { 5.hours.inWholeMilliseconds },
  )

  fun activitiesContributor() = ActivitiesContributor {
    mapOf(
      BackgroundActivity.PHOTO_BACKUP to RuleDecision.Run(network = NetworkClass.EXTERNAL_WIFI, remainingBytes = 640_000_000L),
      BackgroundActivity.VIDEO_BACKUP to RuleDecision.Wait(WaitReason.HeavyNeedsHomeWifi),
      BackgroundActivity.INDEX_SYNC to RuleDecision.Run(network = NetworkClass.EXTERNAL_WIFI),
      BackgroundActivity.PREPARATION to RuleDecision.Wait(
        WaitReason.OutsideWindow(TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0)), LocalDateTime.of(2026, 10, 12, 23, 0)),
        listOf(WaitReason.NeedsPower),
      ),
      BackgroundActivity.AI_COMPUTE to RuleDecision.Run(scope = RunScope.LOCAL_BUFFER_ONLY, pace = Pace.REDUCED, network = NetworkClass.OFFLINE),
      BackgroundActivity.HQ_PREVIEWS to RuleDecision.Wait(WaitReason.CapReached(CapKind.MOBILE, 1_200_000_000L, 1_000_000_000L)),
    )
  }

  fun storageContributor() = StorageContributor {
    StorageSnapshot(
      databaseBytes = 48_300_000,
      cacheBytes = 212_000_000,
      noBackupBytes = 640_000,
      filesBytes = 1_900_000,
      freeBytes = 380_000_000L,
      totalBytes = 128_000_000_000L,
    )
  }

  fun trafficContributor(): DiagnosticsContributor {
    val ledger = InMemoryLedger()
    runBlocking {
      ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.HOME_WIFI, 1_800_000_000L)
      ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.MOBILE, 230_000_000L)
      ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.EXTERNAL_WIFI, 41_000_000L)
      ledger.record(TrafficSource.VISIBLE_CONTENT, TrafficNetwork.ROAMING, 6_000_000L)
    }
    return TrafficContributor(ledger)
  }

  fun recentEventsContributor(): DiagnosticsContributor {
    val log = RingDiagnosticsLog(capacity = 50)
    val hasher = testHasher
    listOf(
      DiagEvent(NOW - 12.hours.inWholeMilliseconds, DiagCodes.NAS_TIMEOUT, DiagSeverity.WARNING, mapOf("attempts" to ExportValue.Num(2))),
      DiagEvent(NOW - 5.hours.inWholeMilliseconds, DiagCodes.EVT_QR_REJECTED, DiagSeverity.WARNING),
      DiagEvent(NOW - 2.hours.inWholeMilliseconds, DiagCodes.NAS_AUTH_FAILED, DiagSeverity.ERROR, mapOf("account" to hasher.hash("mario"))),
      DiagEvent(NOW - 40.minutes.inWholeMilliseconds, DiagCodes.ACT_JOB_STOPPED, DiagSeverity.INFO, mapOf("stopReason" to ExportValue.Num(7))),
      DiagEvent(NOW - 90_000L, DiagCode("QUALCOSA_DI_NUOVO"), DiagSeverity.INFO),
    ).forEach(log::record)
    return RecentEventsContributor(log, nowMillis = { NOW })
  }

  /** L'esito di un test di connessione: la quarta fase (autenticazione) non e' stata provata. */
  fun testOutcomeFailed(): ConnectionTestOutcome.Done = ConnectionTestOutcome.Done(
    rows = listOf(
      StageRow(Stage.ROUTE, StageOutcome.PASSED, 3.milliseconds, null),
      StageRow(Stage.TCP, StageOutcome.PASSED, 41.milliseconds, null),
      StageRow(Stage.NEGOTIATE, StageOutcome.PASSED, 96.milliseconds, null),
      StageRow(Stage.GUID, StageOutcome.PASSED, 1.milliseconds, null),
      StageRow(Stage.AUTHENTICATE, StageOutcome.SKIPPED, 0.milliseconds, null),
      StageRow(Stage.SHARE, StageOutcome.FAILED, 1_240.milliseconds, NasError.ShareMissing("test-comune")),
      StageRow(Stage.READ, StageOutcome.NOT_RUN, 0.milliseconds, null),
    ),
    negotiation = NegotiationSummary(SmbDialect.SMB_3_1_1, encryptionOffered = true, clockOffsetMs = 820),
    passed = false,
    authSkipped = true,
    breakerOpen = true,
  )

  // --- Stati interi -----------------------------------------------------------------------------------------------

  private suspend fun collect(contributors: List<DiagnosticsContributor>): Map<DiagSection, List<DiagItem>> =
    DiagnosticsAggregator(contributors, RingDiagnosticsLog(capacity = 1)).collect().sections

  private fun contributors(health: dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth) = listOf(
    FakeContributor(DiagSection.NETWORK_AND_NAS) { networkItems() },
    FakeContributor(DiagSection.TRUST) { trustItems() },
    healthContributor(health),
    activitiesContributor(),
    storageContributor(),
    trafficContributor(),
    recentEventsContributor(),
  )

  /** Una giornata brutta: avvisi in quasi tutte le sezioni. */
  fun attentionState(testOutcome: ConnectionTestOutcome? = null, loading: Boolean = false): DiagnosticsUiState {
    val sections = runBlocking { collect(contributors(unhealthyHealth())) }.toMutableMap()
    if (testOutcome != null) sections[DiagSection.CONNECTION_TEST] = testOutcome.toItems()
    return state(sections.toSortedMap(), loading, testOutcome)
  }

  /** Una giornata buona: tutto verde. */
  fun healthyState(): DiagnosticsUiState {
    val sections = runBlocking {
      collect(
        listOf(
          FakeContributor(DiagSection.NETWORK_AND_NAS) { networkItems().filter { it.severity == DiagSeverity.OK } },
          FakeContributor(DiagSection.TRUST) { trustItems().take(1) },
          BackgroundHealthContributor(FakeBackgroundHealthReader(healthyHealth()), DeviceSecuritySource { true }, nowMillis = { NOW }),
          StorageContributor { StorageSnapshot(48_300_000, 20_000_000, 640_000, 1_900_000, 60_000_000_000L, 128_000_000_000L) },
        ),
      )
    }
    return state(sections, loading = false, testOutcome = null)
  }

  fun loadingState() = DiagnosticsUiState(loading = true, sections = emptyMap(), recentEvents = 0)

  private fun state(sections: Map<DiagSection, List<DiagItem>>, loading: Boolean, testOutcome: ConnectionTestOutcome?): DiagnosticsUiState {
    val snapshot = dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot(sections, recentEvents = 37)
    return DiagnosticsUiState(
      loading = loading,
      sections = sections,
      recentEvents = 37,
      connectionTest = testOutcome?.let { ConnectionTestUiState.Finished(it) } ?: ConnectionTestUiState.Idle,
      export = DiagnosticsExporter.render(snapshot, ExportEnvironment(sdkInt = 36)),
      wireGuardAvailable = true,
    )
  }

  /** Le azioni che `:app` esegue: aprire i dispositivi e reinserire la password. */
  val appActions: Set<DiagAction> = setOf(DiagAction.OPEN_DEVICES, DiagAction.REENTER_NAS_PASSWORD, DiagAction.RESUME_ONBOARDING)
}
