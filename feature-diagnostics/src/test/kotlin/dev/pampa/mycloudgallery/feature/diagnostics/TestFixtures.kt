package dev.pampa.mycloudgallery.feature.diagnostics

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagHasher
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import dev.pampa.mycloudgallery.core.rules.platform.health.PermissionGrant
import dev.pampa.mycloudgallery.core.rules.platform.health.HealthPermission
import dev.pampa.mycloudgallery.core.rules.platform.health.StandbyBucket
import dev.pampa.mycloudgallery.core.rules.platform.health.UnusedAppRestrictions
import dev.pampa.mycloudgallery.core.rules.traffic.BillingCycle
import dev.pampa.mycloudgallery.core.rules.traffic.CycleUsage
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficKey
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Un contributore finto: le voci (o l'errore) che si decidono nel test. */
internal class FakeContributor(
  override val section: DiagSection,
  private val block: suspend () -> List<DiagItem>,
) : DiagnosticsContributor {
  var calls = 0
    private set

  override suspend fun collect(): List<DiagItem> {
    calls++
    return block()
  }
}

internal fun item(
  code: DiagCode = DiagCodes.NET_PLACE,
  section: DiagSection = DiagSection.NETWORK_AND_NAS,
  severity: DiagSeverity = DiagSeverity.INFO,
  detail: DiagText? = null,
  export: Map<String, ExportValue> = emptyMap(),
  titleRes: Int = DiagCatalog.titleOf(code),
) = DiagItem(code, section, severity, titleRes, detail, action = null, export = export)

/** Un'impronta con una chiave fissa: stessa chiave, stesso risultato. */
internal val testHasher = DiagHasher(ByteArray(32) { (it * 7 + 3).toByte() })

/** Un telefono in buona salute: nessun avviso. */
internal fun healthyHealth(): BackgroundHealth = BackgroundHealth(
  ignoringBatteryOpt = true,
  standbyBucket = StandbyBucket.ACTIVE,
  backgroundRestricted = false,
  unusedAppRestrictions = UnusedAppRestrictions.DISABLED,
  permissions = HealthPermission.entries.associateWith { PermissionGrant.GRANTED },
  userUnlocked = true,
  bootCount = 33,
  lastExitReasons = emptyList(),
  isSystemUser = true,
)

/** Un registro dei consumi in memoria: i contatori veri vivono in DataStore (di `core-rules`, provato la'). */
internal class InMemoryLedger : TrafficLedger {
  private val bytes = LinkedHashMap<TrafficKey, Long>()
  private val cycle = BillingCycle(YearMonth.of(2026, 10))

  override suspend fun record(source: TrafficSource, network: TrafficNetwork, bytes: Long) {
    val key = TrafficKey(source, network)
    this.bytes[key] = (this.bytes[key] ?: 0L) + bytes
  }

  override suspend fun currentUsage(): CycleUsage = CycleUsage(cycle, bytes.toMap())

  override fun observeCurrentUsage(): Flow<CycleUsage> = flowOf(CycleUsage(cycle, bytes.toMap()))

  override suspend fun history(): List<CycleUsage> = listOf(CycleUsage(cycle, bytes.toMap()))
}
