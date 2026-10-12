package dev.pampa.mycloudgallery.core.family.trust

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.family.R
import dev.pampa.mycloudgallery.core.family.contract.SelfWipe
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.lifecycle.LostReportWatcher
import dev.pampa.mycloudgallery.core.family.registry.DeviceStatusBook
import dev.pampa.mycloudgallery.core.family.registry.RegistryWriter

/** Lo stato proprio come enum esportabile (`ExportValue.Enum`): mai il codice d'approvazione o un nome. */
enum class OwnTrustState { NOT_CONFIGURED, PENDING, APPROVED, ADMIN, RETIRED, REVOKED, LOST, STALE }

/** I codici della sezione "Fiducia" della Diagnostica (§2.6), con gli edge case che coprono. */
object TrustDiagCodes {
  val STATE = DiagCode("TRUST_STATE", setOf("E-S14", "L11"))
  val REJECTED = DiagCode("TRUST_REJECTED_FILES", setOf("E-I14", "C9"))
  val NEWER_UNVERIFIED = DiagCode("TRUST_NEWER_UNVERIFIED", setOf("C7"))
  val LISTING_TRUNCATED = DiagCode("TRUST_LISTING_TRUNCATED", setOf("C9"))
  val NEEDS_APP_UPDATE = DiagCode("TRUST_NEEDS_APP_UPDATE", setOf("E-I3"))
  val FORK = DiagCode("TRUST_REGISTRY_FORK", setOf("C7", "E-S13"))
  val VERSION_REPLACED = DiagCode("TRUST_VERSION_REPLACED", setOf("C7", "L18"))
  val INCOMPLETE = DiagCode("TRUST_SYNC_INCOMPLETE", setOf("L18"))
  val REGISTRY_BEHIND = DiagCode("TRUST_REGISTRY_BEHIND", setOf("C7"))
  val NO_MIN_VERSION = DiagCode("TRUST_NO_MIN_VERSION", setOf("E-O5"))
  val NOT_REFRESHED = DiagCode("TRUST_NOT_REFRESHED")
  val LOCAL_STATE_UNTRUSTED = DiagCode("TRUST_LOCAL_STATE_UNTRUSTED")
  val WIPE = DiagCode("TRUST_WIPE", setOf("E-S2", "C15"))
  val LOST_REPORT_REJECTED = DiagCode("TRUST_LOST_REPORT_REJECTED", setOf("E-S1", "C15"))
  val PUBLISH_UNVERIFIED = DiagCode("TRUST_PUBLISH_UNVERIFIED", setOf("L18"))
  val PUBLISH_BLOCKED = DiagCode("TRUST_PUBLISH_BLOCKED", setOf("L18"))
  val STATUS_REJECTED = DiagCode("TRUST_STATUS_REJECTED", setOf("L11"))

  val ALL: List<DiagCode> = listOf(
    STATE, REJECTED, NEWER_UNVERIFIED, LISTING_TRUNCATED, NEEDS_APP_UPDATE, FORK, VERSION_REPLACED, INCOMPLETE,
    REGISTRY_BEHIND, NO_MIN_VERSION, NOT_REFRESHED, LOCAL_STATE_UNTRUSTED, WIPE, LOST_REPORT_REJECTED,
    PUBLISH_UNVERIFIED, PUBLISH_BLOCKED, STATUS_REJECTED,
  )
}

/**
 * Il contributo della fiducia alla Diagnostica (§2.6, sezione [DiagSection.TRUST]): versione del registro,
 * stato proprio, dispositivi, file scartati per motivo (`RejectionTally`), `newerUnverified`,
 * `listingTruncated`, `needsAppUpdate`, allarme dei rami (equivocazione), versione fidata sostituita,
 * registro rimasto indietro, azzeramento, segnalazioni scartate, pubblicazioni non verificate o bloccate.
 *
 * Legge solo stati gia' in memoria: non tocca il NAS (E-R15). L'esportazione porta solo numeri, flag ed enum.
 */
class TrustDiagnostics(
  private val engine: TrustEngine,
  private val selfWipe: SelfWipe,
  private val statuses: DeviceStatusBook? = null,
  private val lostReports: LostReportWatcher? = null,
  private val writer: RegistryWriter? = null,
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.TRUST

  override suspend fun collect(): List<DiagItem> {
    val facts = engine.facts.value
    val status = engine.status.value
    val items = ArrayList<DiagItem>()
    val own = ownState(status)
    items += item(
      TrustDiagCodes.STATE,
      if (own == OwnTrustState.STALE || own == OwnTrustState.PENDING) DiagSeverity.INFO else DiagSeverity.OK,
      R.string.trust_diag_state,
      action = DiagAction.OPEN_DEVICES,
      export = buildMap {
        put("state", ExportValue.Enum.of(own))
        facts.registryVersion?.let { put("registryVersion", ExportValue.Num(it)) }
        put("devices", ExportValue.Num(facts.deviceCount.toLong()))
      },
    )
    selfWipe.progress.value?.let { phase ->
      items += item(TrustDiagCodes.WIPE, if (phase == WipePhase.DONE) DiagSeverity.INFO else DiagSeverity.ERROR, R.string.trust_diag_wipe, export = mapOf("phase" to ExportValue.Enum.of(phase)))
    }
    if (facts.localStateUntrusted) items += item(TrustDiagCodes.LOCAL_STATE_UNTRUSTED, DiagSeverity.ERROR, R.string.trust_diag_local_state_untrusted)
    if (facts.rejected.isNotEmpty()) {
      items += item(
        TrustDiagCodes.REJECTED,
        DiagSeverity.WARNING,
        R.string.trust_diag_rejected,
        export = facts.rejected.entries.associate { (reason, count) -> "rejected.${reason.name.lowercase()}" to ExportValue.Num(count.toLong()) } +
          ("total" to ExportValue.Num(facts.rejected.values.sum().toLong())),
      )
      items += item(TrustDiagCodes.NEWER_UNVERIFIED, DiagSeverity.WARNING, R.string.trust_diag_newer_unverified)
    }
    if (facts.listingTruncated) items += item(TrustDiagCodes.LISTING_TRUNCATED, DiagSeverity.WARNING, R.string.trust_diag_listing_truncated)
    if (facts.needsAppUpdate) items += item(TrustDiagCodes.NEEDS_APP_UPDATE, DiagSeverity.ERROR, R.string.trust_diag_needs_app_update)
    if (facts.forkCount > 0) {
      items += item(TrustDiagCodes.FORK, DiagSeverity.ERROR, R.string.trust_diag_fork, action = DiagAction.OPEN_DEVICES, export = mapOf("forks" to ExportValue.Num(facts.forkCount.toLong())))
    }
    if (facts.trustedVersionReplaced) items += item(TrustDiagCodes.VERSION_REPLACED, DiagSeverity.WARNING, R.string.trust_diag_version_replaced)
    if (facts.incomplete) items += item(TrustDiagCodes.INCOMPLETE, DiagSeverity.WARNING, R.string.trust_diag_incomplete)
    if (facts.registryBehind) items += item(TrustDiagCodes.REGISTRY_BEHIND, DiagSeverity.WARNING, R.string.trust_diag_registry_behind)
    if (facts.manualAnchor) items += item(TrustDiagCodes.NO_MIN_VERSION, DiagSeverity.INFO, R.string.trust_diag_no_min_version)
    if (facts.lastSyncFailed) {
      items += item(TrustDiagCodes.NOT_REFRESHED, DiagSeverity.WARNING, R.string.trust_diag_not_refreshed, action = DiagAction.RUN_CONNECTION_TEST)
    }
    lostReports?.lastScan?.value?.takeIf { it.rejected.total > 0 }?.let { scan ->
      items += item(TrustDiagCodes.LOST_REPORT_REJECTED, DiagSeverity.WARNING, R.string.trust_diag_lost_report_rejected, export = mapOf("total" to ExportValue.Num(scan.rejected.total.toLong())))
    }
    statuses?.rejected?.value?.takeIf { it > 0 }?.let { count ->
      items += item(TrustDiagCodes.STATUS_REJECTED, DiagSeverity.INFO, R.string.trust_diag_status_rejected, export = mapOf("statusRejected" to ExportValue.Num(count.toLong())))
    }
    if (writer?.unverifiedBeyond?.value == true) items += item(TrustDiagCodes.PUBLISH_UNVERIFIED, DiagSeverity.WARNING, R.string.trust_diag_publish_unverified)
    writer?.blockedPaths?.value?.takeIf { it.isNotEmpty() }?.let { junk ->
      items += item(TrustDiagCodes.PUBLISH_BLOCKED, DiagSeverity.ERROR, R.string.trust_diag_publish_blocked, export = mapOf("files" to ExportValue.Num(junk.size.toLong())))
    }
    return items
  }

  private fun item(
    code: DiagCode,
    severity: DiagSeverity,
    titleRes: Int,
    action: DiagAction? = null,
    export: Map<String, ExportValue> = emptyMap(),
  ) = DiagItem(code, DiagSection.TRUST, severity, titleRes, detail = null, action = action, export = export)

  companion object {
    fun ownState(status: TrustStatus): OwnTrustState = when (status) {
      TrustStatus.NotConfigured -> OwnTrustState.NOT_CONFIGURED
      is TrustStatus.Pending -> OwnTrustState.PENDING
      is TrustStatus.Approved -> if (status.admin) OwnTrustState.ADMIN else OwnTrustState.APPROVED
      TrustStatus.Retired -> OwnTrustState.RETIRED
      TrustStatus.Revoked -> OwnTrustState.REVOKED
      TrustStatus.Lost -> OwnTrustState.LOST
      is TrustStatus.Stale -> OwnTrustState.STALE
    }
  }
}
