package dev.pampa.mycloudgallery.core.mediastore.diagnostics

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.database.MediaStoreCursor
import dev.pampa.mycloudgallery.core.mediastore.R
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.mediastore.sync.IgnoreReason
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneLibraryStats

/** I codici della Diagnostica della libreria del telefono, con gli edge case che coprono (il catalogo di P11 li raccoglie). */
object PhoneLibraryDiagCodes {
  val ACCESS = DiagCode("PHONE_ACCESS", setOf("E-P1", "E-P15"))
  val MEDIA_LOCATION = DiagCode("PHONE_MEDIA_LOCATION", setOf("E-P21"))
  val SYNC = DiagCode("PHONE_SYNC", setOf("E-P4", "E-P8", "E-P12", "E-P22"))
  val DISK_FULL = DiagCode("PHONE_DISK_FULL", setOf("E-P3"))
  val STORAGE_LOCKED = DiagCode("PHONE_STORAGE_LOCKED", setOf("E-P2", "E-P10", "E-P13"))
  val VOLUMES = DiagCode("PHONE_VOLUMES", setOf("E-P6"))
  val IGNORED = DiagCode("PHONE_IGNORED", setOf("E-M12"))
  val ABSENCES_BLOCKED = DiagCode("PHONE_ABSENCES_BLOCKED", setOf("E-P1", "E-P15"))
  val OTHER_PROFILES = DiagCode("PHONE_OTHER_PROFILES", setOf("E-P7", "E-P17"))

  val ALL: List<DiagCode> = listOf(ACCESS, MEDIA_LOCATION, SYNC, DISK_FULL, STORAGE_LOCKED, VOLUMES, IGNORED, ABSENCES_BLOCKED, OTHER_PROFILES)
}

/** Lo stato della lettura come valore esportabile (`SyncStatus` e' un'interfaccia sigillata, non un enum). */
enum class PhoneSyncState { IDLE, SCANNING, PAUSED }

/**
 * Il contributo di `core-mediastore` alla sezione "Libreria del telefono" della Diagnostica (`DiagSection.PHONE_LIBRARY`).
 *
 * **Solo numeri ed enumerati** nell'esportazione: accesso, permesso della posizione, stato e bilancio dell'ultimo giro,
 * quanti volumi (mai i loro nomi: l'id di una microSD e' un numero di serie), quanti ignorati per motivo, quante ondate di
 * assenze fermate. I dettagli a schermo non hanno argomenti. [collect] legge stati in memoria e i cursori del catalogo.
 *
 * @param cursors i cursori del MediaStore (di norma `MediaCatalog::mediaStoreCursors`).
 */
class PhoneLibraryDiagnostics(
  private val sync: PhoneLibrarySync,
  private val access: PhoneAccessReader,
  private val stats: PhoneLibraryStats,
  private val cursors: suspend () -> List<MediaStoreCursor>,
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.PHONE_LIBRARY

  override suspend fun collect(): List<DiagItem> {
    val now = access.state.value
    val snapshot = stats.snapshot.value
    val report = snapshot.lastReport
    val items = ArrayList<DiagItem>()

    // Accesso ai media.
    val accessExport = mapOf(
      "media" to ExportValue.Enum.of(now.media),
      "mediaLocation" to ExportValue.Flag(now.mediaLocation),
      "volumes" to ExportValue.Num(now.volumes.size.toLong()),
    )
    items += when (now.media) {
      MediaAccess.FULL -> item(PhoneLibraryDiagCodes.ACCESS, DiagSeverity.OK, R.string.phone_diag_access_full, export = accessExport)
      MediaAccess.PARTIAL -> item(
        PhoneLibraryDiagCodes.ACCESS, DiagSeverity.INFO, R.string.phone_diag_access_partial, R.string.phone_diag_access_partial_detail,
        DiagAction.OPEN_APP_DETAILS, accessExport,
      )
      MediaAccess.NONE -> item(
        PhoneLibraryDiagCodes.ACCESS, DiagSeverity.WARNING, R.string.phone_diag_access_none, R.string.phone_diag_access_none_detail,
        DiagAction.OPEN_APP_DETAILS, accessExport,
      )
    }

    // Posizione dei media (E-P21).
    val pending = report?.pendingMediaLocation ?: 0
    val waiting = snapshot.metaWaitingForLocation
    val locationExport = mapOf(
      "granted" to ExportValue.Flag(now.mediaLocation),
      "pendingPhotos" to ExportValue.Num(pending.toLong()),
      "metaWaiting" to ExportValue.Num(waiting.toLong()),
    )
    items += if (now.mediaLocation) {
      item(PhoneLibraryDiagCodes.MEDIA_LOCATION, DiagSeverity.OK, R.string.phone_diag_location_ok, export = locationExport)
    } else {
      item(
        PhoneLibraryDiagCodes.MEDIA_LOCATION, DiagSeverity.WARNING, R.string.phone_diag_location_denied, R.string.phone_diag_location_denied_detail,
        DiagAction.OPEN_APP_DETAILS, locationExport,
      )
    }

    // Stato della lettura e bilancio dell'ultimo giro.
    val status = sync.status.value
    val syncExport = LinkedHashMap<String, ExportValue>()
    syncExport["state"] = ExportValue.Enum.of(status.kind())
    (status as? SyncStatus.Paused)?.let { syncExport["pauseReason"] = ExportValue.Enum.of(it.reason) }
    snapshot.lastReason?.let { syncExport["lastReason"] = ExportValue.Enum.of(it) }
    snapshot.lastFailure?.let { syncExport["lastFailure"] = ExportValue.Enum.of(it) }
    if (report != null) {
      syncExport["seen"] = ExportValue.Num(report.seen.toLong())
      syncExport["added"] = ExportValue.Num(report.added.toLong())
      syncExport["updated"] = ExportValue.Num(report.updated.toLong())
      syncExport["vanished"] = ExportValue.Num(report.vanished.toLong())
      syncExport["fullScan"] = ExportValue.Flag(report.fullScan)
      syncExport["completed"] = ExportValue.Flag(report.completed)
    }
    snapshot.deletedFilesAvailable?.let { syncExport["deletedFiles"] = ExportValue.Flag(it) }
    syncExport["fullScansCompleted"] = ExportValue.Num(snapshot.fullScansCompleted.toLong())
    syncExport["reconcileVanished"] = ExportValue.Num(snapshot.reconcileVanished.toLong())
    syncExport["purgedAbsences"] = ExportValue.Num(snapshot.purgedAbsences.toLong())
    syncExport["suspectedAbsences"] = ExportValue.Num(snapshot.suspectedAbsences.toLong())
    syncExport["metaDone"] = ExportValue.Num(snapshot.metaDone)
    syncExport["metaFailed"] = ExportValue.Num(snapshot.metaFailed)
    items += when {
      status is SyncStatus.Scanning -> item(PhoneLibraryDiagCodes.SYNC, DiagSeverity.INFO, R.string.phone_diag_sync_scanning, export = syncExport)
      status is SyncStatus.Paused -> item(PhoneLibraryDiagCodes.SYNC, DiagSeverity.INFO, R.string.phone_diag_sync_paused, export = syncExport)
      report != null && !report.completed ->
        item(PhoneLibraryDiagCodes.SYNC, DiagSeverity.INFO, R.string.phone_diag_sync_incomplete, R.string.phone_diag_sync_incomplete_detail, export = syncExport)
      else -> item(PhoneLibraryDiagCodes.SYNC, DiagSeverity.OK, R.string.phone_diag_sync_idle, export = syncExport)
    }
    if (status is SyncStatus.Paused && status.reason == PauseReason.DISK_FULL) {
      items += item(PhoneLibraryDiagCodes.DISK_FULL, DiagSeverity.ERROR, R.string.phone_diag_disk_full, R.string.phone_diag_disk_full_detail)
    }
    if (status is SyncStatus.Paused && status.reason == PauseReason.STORAGE_LOCKED) {
      items += item(PhoneLibraryDiagCodes.STORAGE_LOCKED, DiagSeverity.INFO, R.string.phone_diag_storage_locked, R.string.phone_diag_storage_locked_detail)
    }

    // Volumi (E-P6): quanti, mai quali.
    val known = cursors()
    val unavailable = known.count { it.volume !in now.volumes }
    val volumesExport = mapOf(
      "mounted" to ExportValue.Num(now.volumes.size.toLong()),
      "known" to ExportValue.Num(known.size.toLong()),
      "unavailable" to ExportValue.Num(unavailable.toLong()),
      "fullScanInProgress" to ExportValue.Num(known.count { it.scanEpoch != null }.toLong()),
      "fullScanPending" to ExportValue.Num(known.count { it.scanEpoch == null && it.generation == null }.toLong()),
    )
    items += if (unavailable > 0) {
      item(PhoneLibraryDiagCodes.VOLUMES, DiagSeverity.INFO, R.string.phone_diag_volumes_unavailable, R.string.phone_diag_volumes_unavailable_detail, export = volumesExport)
    } else {
      item(PhoneLibraryDiagCodes.VOLUMES, DiagSeverity.OK, R.string.phone_diag_volumes, export = volumesExport)
    }

    // Ignorati (E-M12).
    val ignored = IgnoreReason.entries.associateWith { snapshot.ignored[it] ?: 0L }
    val ignoredExport = mapOf(
      "zeroBytes" to ExportValue.Num(ignored.getValue(IgnoreReason.ZERO_BYTES)),
      "notMedia" to ExportValue.Num(ignored.getValue(IgnoreReason.NOT_MEDIA)),
      "invalidName" to ExportValue.Num(ignored.getValue(IgnoreReason.INVALID_NAME)),
      "unreadable" to ExportValue.Num(ignored.getValue(IgnoreReason.UNREADABLE)),
    )
    val excluded = ignored.getValue(IgnoreReason.ZERO_BYTES) + ignored.getValue(IgnoreReason.NOT_MEDIA) + ignored.getValue(IgnoreReason.INVALID_NAME)
    items += if (excluded > 0) {
      item(PhoneLibraryDiagCodes.IGNORED, DiagSeverity.INFO, R.string.phone_diag_ignored, R.string.phone_diag_ignored_detail, export = ignoredExport)
    } else {
      item(PhoneLibraryDiagCodes.IGNORED, DiagSeverity.OK, R.string.phone_diag_ignored_none, export = ignoredExport)
    }

    if (snapshot.absencesBlocked > 0) {
      items += item(
        PhoneLibraryDiagCodes.ABSENCES_BLOCKED, DiagSeverity.WARNING, R.string.phone_diag_absences_blocked, R.string.phone_diag_absences_blocked_detail,
        export = mapOf("times" to ExportValue.Num(snapshot.absencesBlocked.toLong())),
      )
    }

    items += item(PhoneLibraryDiagCodes.OTHER_PROFILES, DiagSeverity.INFO, R.string.phone_diag_other_profiles, R.string.phone_diag_other_profiles_detail)
    return items
  }

  private fun item(
    code: DiagCode,
    severity: DiagSeverity,
    title: Int,
    detail: Int? = null,
    action: DiagAction? = null,
    export: Map<String, ExportValue> = emptyMap(),
  ): DiagItem = DiagItem(code, section, severity, title, detail?.let { DiagText(it) }, action, export)

  private fun SyncStatus.kind(): PhoneSyncState = when (this) {
    is SyncStatus.Idle -> PhoneSyncState.IDLE
    is SyncStatus.Scanning -> PhoneSyncState.SCANNING
    is SyncStatus.Paused -> PhoneSyncState.PAUSED
  }
}
