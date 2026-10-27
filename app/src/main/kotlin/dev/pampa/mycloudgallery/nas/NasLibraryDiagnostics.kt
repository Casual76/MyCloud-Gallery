package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.index.contract.IndexDiag
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMediaDiag
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.prepare.contract.PrepDiag
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import kotlin.math.abs

/** I codici che `:app` aggiunge alla sezione "NAS nella Libreria" (gli stessi id del catalogo di feature-diagnostics). */
object NasLibraryDiagCodes {
  val STATE = DiagCode("NAS_LIBRARY_STATE")
  val WRITES_STOPPED = DiagCode("NAS_WRITES_STOPPED")
  val AREA_UNREACHABLE = DiagCode("NAS_AREA_UNREACHABLE", setOf("E-L4"))

  /** R11 (revisione di fine Fase 7, L163): l'emergenza «Ferma le scritture» vale solo in questo processo, il disco non l'ha salvata. */
  val WRITES_STOP_NOT_SAVED = DiagCode("NAS_WRITES_STOP_NOT_SAVED")

  val ALL: List<DiagCode> = listOf(STATE, WRITES_STOPPED, AREA_UNREACHABLE, WRITES_STOP_NOT_SAVED)
}

/** Lo stato del piede della Libreria come enumerato per l'esportazione (mai i numeri di file che non servono). */
enum class NasFooterState { IDLE, FIRST_SCAN, ALIGNING, UP_TO_DATE, FROZEN, AREAS_UNAVAILABLE, LOCAL_ONLY }

/**
 * La Diagnostica del NAS nella Libreria (Fase 4A, P11): stato, emergenza, aree non raggiungibili, ultima verifica con i suoi
 * casi limite (E-N4, E-N5, E-N6, E-N10, E-N13, E-N14, E-M12, D22), congelamento (D10) con "Ho controllato, continua",
 * preparazione (E-M3, E-M10, E-M13), outbox, solo locale (D31), salto dell'ora (D32), prenotazioni contese (E-I1). Il resto
 * dell'indice (rifiuti, rollback, identita', share tolte, replica) lo dice `IndexDiagnostics` di core-index.
 *
 * **Solo numeri, flag ed enumerati**: mai nomi di share, cartelle, file, account o id.
 */
class NasLibraryDiagnostics(private val nas: NasGraph) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.NAS_LIBRARY

  override suspend fun collect(): List<DiagItem> {
    val items = ArrayList<DiagItem>()
    val runtime = nas.runtime
    val status = nas.libraryStatus.value
    items += item(
      NasLibraryDiagCodes.STATE,
      DiagSeverity.INFO,
      mapOf(
        "configured" to ExportValue.Flag(runtime != null),
        "areas" to ExportValue.Num(runtime?.areas?.size?.toLong() ?: 0),
        "status" to ExportValue.Enum.of(footerOf(status)),
        "localOnly" to ExportValue.Flag(runtime != null && nas.localOnly()),
        "emergency" to ExportValue.Flag(nas.emergency.stopped),
      ),
      action = if (runtime != null && !nas.emergency.stopped) DiagnosticsActions.NAS_STOP_WRITES else null,
    )
    if (runtime == null) return items

    if (nas.emergency.stopped) items += item(NasLibraryDiagCodes.WRITES_STOPPED, DiagSeverity.WARNING)
    // R11, L163: l'emergenza accesa che il disco non ha salvato vale solo finche' l'app resta aperta: la pagina lo dice, l'utente puo' riprovare.
    if (nas.emergency.notSaved) items += item(NasLibraryDiagCodes.WRITES_STOP_NOT_SAVED, DiagCatalog.entry(NasLibraryDiagCodes.WRITES_STOP_NOT_SAVED)?.severity ?: DiagSeverity.WARNING)
    val unreachable = runtime.areas.count { area -> runtime.scannerOf(area.share)?.status?.value is NasScanStatus.Unavailable }
    if (unreachable > 0 || status is LibraryNasStatus.AreasUnavailable) {
      items += item(NasLibraryDiagCodes.AREA_UNREACHABLE, DiagSeverity.WARNING, mapOf("unreachable" to ExportValue.Num(unreachable.toLong())))
    }

    // Congelamento (D10): una voce per tutte le aree congelate, con la ragione piu' grave e i conteggi.
    val frozen = nas.frozen.value
    if (frozen.isNotEmpty()) {
      val worst = frozen.values.minBy { it.sinceNasMillis }
      items += item(
        NasMediaDiag.NAS_SCAN_FROZEN,
        DiagSeverity.ERROR,
        mapOf(
          "frozen" to ExportValue.Num(frozen.size.toLong()),
          "reason" to ExportValue.Enum.of(worst.reason),
          "vanished" to ExportValue.Num(worst.diff.vanished.toLong()),
          "changed" to ExportValue.Num(worst.diff.changed.toLong()),
          "notMedia" to ExportValue.Num(worst.diff.notMedia.toLong()),
          "known" to ExportValue.Num(worst.diff.knownPresent.toLong()),
        ),
      )
    }

    // L'ultima verifica (somma delle aree) e i suoi casi limite.
    val reports = nas.lastScan.value
    if (reports.isNotEmpty()) {
      val sum = reports.reduce(::plus)
      items += item(
        NasMediaDiag.NAS_SCAN_STATE,
        if (reports.all { it.complete }) DiagSeverity.OK else DiagSeverity.INFO,
        mapOf(
          "mode" to ExportValue.Enum.of(modeOf(reports.first())),
          "folders" to ExportValue.Num(sum.folders.toLong()),
          "files" to ExportValue.Num(sum.files.toLong()),
          "unchanged" to ExportValue.Num(sum.unchanged.toLong()),
          "adopted" to ExportValue.Num(sum.adopted.toLong()),
          "detected" to ExportValue.Num(sum.detected.toLong()),
          "moved" to ExportValue.Num(sum.moved.toLong()),
          "changed" to ExportValue.Num(sum.changed.toLong()),
          "vanished" to ExportValue.Num(sum.vanished.toLong()),
          "skippedSystem" to ExportValue.Num(sum.skippedSystem.toLong()),
          "excludedFolders" to ExportValue.Num(sum.excludedFolders.toLong()),
          "bytesRead" to ExportValue.Num(sum.bytesRead),
          "complete" to ExportValue.Flag(reports.all { it.complete }),
          "resumed" to ExportValue.Flag(reports.any { it.resumed }),
        ) + (reports.firstNotNullOfOrNull { it.stoppedBy }?.let { mapOf("stoppedBy" to ExportValue.Enum.of(it)) } ?: emptyMap()),
      )
      count(items, NasMediaDiag.NAS_SCAN_SKIPPED_NAMES, sum.skippedNames, DiagSeverity.WARNING)
      count(items, NasMediaDiag.NAS_SCAN_ALBUM_COVERS, sum.albumCovers, DiagSeverity.INFO)
      count(items, NasMediaDiag.NAS_SCAN_NOT_MEDIA, sum.notMedia, DiagSeverity.INFO)
      count(items, NasMediaDiag.NAS_SCAN_ARRIVING, sum.arriving, DiagSeverity.INFO)
      count(items, NasMediaDiag.NAS_SCAN_BUSY, sum.busy, DiagSeverity.INFO)
      count(items, NasMediaDiag.NAS_SCAN_TRUNCATED, sum.blockedFolders, DiagSeverity.WARNING)
      count(items, IndexDiag.INDEX_IDENTITY_CONFLICT, sum.identityRefused, DiagSeverity.WARNING)
      if (reports.any { it.stoppedBy == ScanStop.BYTE_CAP }) items += item(NasMediaDiag.NAS_SCAN_AWAY_CAP, DiagSeverity.INFO)
    }

    // La preparazione (P6).
    nas.lastPrep.value?.let { run ->
      items += item(
        PrepDiag.PREP_STATE,
        DiagSeverity.INFO,
        mapOf(
          "units" to ExportValue.Num(run.units.toLong()),
          "prepared" to ExportValue.Num(run.prepared.toLong()),
          "failed" to ExportValue.Num(run.failed.toLong()),
          "handedOver" to ExportValue.Num(run.handedOver.toLong()),
          "bytesRead" to ExportValue.Num(run.bytesRead),
        ) + (run.stoppedBy?.let { mapOf("stoppedBy" to ExportValue.Enum.of(it)) } ?: emptyMap()),
      )
      count(items, PrepDiag.PREP_DECODER_MISSING, run.handedOver, DiagSeverity.INFO)
      count(items, PrepDiag.PREP_UNREADABLE, run.failed, DiagSeverity.WARNING)
    }

    // L'indice: outbox, solo locale (D31), salto dell'ora (D32), prenotazioni contese (E-I1).
    val pending = runtime.publisher.pending.value
    if (!pending.isEmpty) {
      items += item(
        IndexDiag.INDEX_OUTBOX_PENDING,
        DiagSeverity.INFO,
        mapOf("records" to ExportValue.Num(pending.records.toLong()), "blobs" to ExportValue.Num(pending.blobs.toLong()), "bytes" to ExportValue.Num(pending.bytes)),
      )
    }
    if (nas.localOnly()) items += item(IndexDiag.INDEX_LOCAL_ONLY, DiagSeverity.INFO)
    if (nas.clockJumpMs()?.let { abs(it) > CLOCK_JUMP_MS } == true) items += item(IndexDiag.INDEX_CLOCK_JUMP, DiagSeverity.WARNING)
    val leases = runtime.workUnits.stats
    if (leases.contended.get() > 0 || leases.unleased.get() > 0) {
      items += item(
        IndexDiag.INDEX_LEASES_CONTENDED,
        DiagSeverity.INFO,
        mapOf(
          "contended" to ExportValue.Num(leases.contended.get().toLong()),
          "busyLeases" to ExportValue.Num(leases.busy.get().toLong()),
          "unleased" to ExportValue.Num(leases.unleased.get().toLong()),
          "lost" to ExportValue.Num(leases.lost.get().toLong()),
        ),
      )
    }
    return items
  }

  private fun count(items: MutableList<DiagItem>, code: DiagCode, n: Int, severity: DiagSeverity) {
    if (n > 0) items += item(code, severity, mapOf("count" to ExportValue.Num(n.toLong())))
  }

  private fun item(code: DiagCode, severity: DiagSeverity, export: Map<String, ExportValue> = emptyMap(), action: DiagAction? = null): DiagItem =
    DiagItem(code, section, severity, DiagCatalog.titleOf(code), null, action, export)

  private fun plus(a: ScanReport, b: ScanReport): ScanReport = a.copy(
    folders = a.folders + b.folders,
    files = a.files + b.files,
    unchanged = a.unchanged + b.unchanged,
    adopted = a.adopted + b.adopted,
    detected = a.detected + b.detected,
    arriving = a.arriving + b.arriving,
    skippedNames = a.skippedNames + b.skippedNames,
    skippedSystem = a.skippedSystem + b.skippedSystem,
    albumCovers = a.albumCovers + b.albumCovers,
    notMedia = a.notMedia + b.notMedia,
    busy = a.busy + b.busy,
    vanished = a.vanished + b.vanished,
    bytesRead = a.bytesRead + b.bytesRead,
    moved = a.moved + b.moved,
    changed = a.changed + b.changed,
    excludedFolders = a.excludedFolders + b.excludedFolders,
    blockedFolders = a.blockedFolders + b.blockedFolders,
    identityRefused = a.identityRefused + b.identityRefused,
  )

  private enum class ScanKind { FULL, VERIFY, FOLDER }

  private fun modeOf(report: ScanReport): ScanKind = when (report.mode) {
    dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode.Full -> ScanKind.FULL
    dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode.Verify -> ScanKind.VERIFY
    is dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode.Folder -> ScanKind.FOLDER
  }

  private fun footerOf(status: LibraryNasStatus): NasFooterState = when (status) {
    LibraryNasStatus.Idle -> NasFooterState.IDLE
    is LibraryNasStatus.FirstScan -> NasFooterState.FIRST_SCAN
    is LibraryNasStatus.Aligning -> NasFooterState.ALIGNING
    is LibraryNasStatus.UpToDate -> NasFooterState.UP_TO_DATE
    is LibraryNasStatus.Frozen -> NasFooterState.FROZEN
    is LibraryNasStatus.AreasUnavailable -> NasFooterState.AREAS_UNAVAILABLE
    LibraryNasStatus.LocalOnly -> NasFooterState.LOCAL_ONLY
  }

  private companion object {
    const val CLOCK_JUMP_MS = 5 * 60 * 1000L
  }
}
