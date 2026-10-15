package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncStatus
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import kotlinx.coroutines.flow.first

/**
 * I codici che `:app` aggiunge alla sezione "NAS nella Libreria" per la **4B** (gli stessi id ed edge case del catalogo di
 * feature-diagnostics, che li scrive come letterali; `DiagnosticsWiringTest` li confronta).
 */
object NasOpsDiagCodes {
  /** Le operazioni sui file: attive, in pausa (e perche'), riprese dal diario (E-D6). */
  val OPS_STATE = DiagCode("NAS_OPS_STATE", setOf("E-D6"))

  /** File aperti dal PC che le operazioni aspettano (E-N13): si riprova con attese crescenti fino a 24 ore. */
  val OPS_LOCKED = DiagCode("NAS_OPS_LOCKED", setOf("E-N13"))

  /** Elementi fermi nelle ultime operazioni, per motivo: spazio (E-D5), nome (E-D9), file cambiato o gia' spostato (E-D13, E-N11), sola lettura (E-N9). */
  val OPS_FAILED = DiagCode("NAS_OPS_FAILED", setOf("E-D5", "E-D9", "E-D13", "E-N9", "E-N11"))

  /** Il proprio cestino del NAS dal diario locale (E-D2): quante voci, quanto spazio, quante scadute. */
  val TRASH_STATE = DiagCode("NAS_TRASH_STATE", setOf("E-D2"))

  /** L'ultima pulizia del cestino scaduto: solo dal diario locale, ora del NAS, tetti, handle (E-D4); un finto cestino non elimina niente (E-S11). */
  val TRASH_PURGE = DiagCode("NAS_TRASH_PURGE", setOf("E-D4", "E-S11"))

  /** La compattazione dell'indice e la pulizia dei propri file (M6). */
  val INDEX_MAINTENANCE = DiagCode("NAS_INDEX_MAINTENANCE", setOf("M6"))

  /** I dati personali fra i miei dispositivi (E-H4): stato e ultimo giro. */
  val PERSONAL_STATE = DiagCode("NAS_PERSONAL_STATE", setOf("E-H4"))

  /** Voci personali scartate, per motivo (D26; un tipo riservato, E-F5). */
  val PERSONAL_REJECTED = DiagCode("NAS_PERSONAL_REJECTED", setOf("E-F5", "D26"))

  val ALL: List<DiagCode> = listOf(OPS_STATE, OPS_LOCKED, OPS_FAILED, TRASH_STATE, TRASH_PURGE, INDEX_MAINTENANCE, PERSONAL_STATE, PERSONAL_REJECTED)
}

/**
 * La Diagnostica dell'organizzazione del NAS (Fase 4B, P16): operazioni, file aperti dal PC, elementi fermi, cestino proprio, pulizia,
 * manutenzione dell'indice, dati personali. Legge il diario locale e i resoconti dell'ultimo giro: **nessuna chiamata al NAS**.
 *
 * **Solo numeri, flag ed enumerati**: mai nomi di share, cartelle, file, account o id.
 */
class NasOpsDiagnostics(private val nas: NasGraph) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.NAS_LIBRARY

  override suspend fun collect(): List<DiagItem> {
    val organizer = nas.organizer
    if (organizer.parts == null) return emptyList()
    val items = ArrayList<DiagItem>()

    // Operazioni attive e chiuse (dal diario mostrato).
    val states = organizer.operations.operations.value
    val active = states.filter { it.active }
    if (states.isNotEmpty()) {
      val paused = active.filterIsInstance<NasOperationState.Paused>()
      val export = linkedMapOf<String, ExportValue>(
        "opsActive" to ExportValue.Num(active.size.toLong()),
        "opsRunning" to ExportValue.Num(active.count { it is NasOperationState.Running }.toLong()),
        "opsPaused" to ExportValue.Num(paused.size.toLong()),
        "opsLocked" to ExportValue.Num(active.count { it is NasOperationState.WaitingLocked }.toLong()),
        "opsClosed" to ExportValue.Num((states.size - active.size).toLong()),
      )
      paused.firstOrNull()?.reason?.let { export["pauseReason"] = ExportValue.Enum.of(it) }
      val severity = if (paused.any { it.reason == PauseReason.FROZEN || it.reason == PauseReason.WRITES_STOPPED }) DiagSeverity.WARNING else DiagSeverity.INFO
      items += item(NasOpsDiagCodes.OPS_STATE, severity, export)
    }
    val locked = active.filterIsInstance<NasOperationState.WaitingLocked>().sumOf { it.lockedCount }
    if (locked > 0) items += item(NasOpsDiagCodes.OPS_LOCKED, DiagSeverity.INFO, mapOf("count" to ExportValue.Num(locked.toLong())))

    // Elementi fermi nelle operazioni del diario ancora in elenco (le ultime chiuse), per motivo.
    val failed = runCatching {
      val store = organizer.opsStore
      store.ops(OpState.entries.toSet()).takeLast(RECENT_OPS)
        .flatMap { store.items(it.id) }
        .filter { it.state == OpItemState.FAILED }
        .groupingBy { it.errorCode ?: OpErrorCode.UNEXPECTED }
        .eachCount()
    }.getOrDefault(emptyMap())
    if (failed.isNotEmpty()) {
      val export = linkedMapOf<String, ExportValue>("count" to ExportValue.Num(failed.values.sum().toLong()))
      failed.forEach { (code, n) -> export["reason.${code.name}"] = ExportValue.Num(n.toLong()) }
      items += item(NasOpsDiagCodes.OPS_FAILED, DiagSeverity.WARNING, export)
    }

    // Il cestino proprio, dal diario locale (nessuna lettura dal NAS).
    val trashed = runCatching { organizer.opsStore.observeTrash().first().filter { it.state == TrashEntryState.TRASHED } }.getOrDefault(emptyList())
    if (trashed.isNotEmpty()) {
      val now = organizer.clocks.nasNowMs()
      val expired = if (now == null) 0 else trashed.count { now - it.trashedAtNas >= TRASH_DAYS_MS }
      items += item(
        NasOpsDiagCodes.TRASH_STATE,
        DiagSeverity.INFO,
        mapOf(
          "count" to ExportValue.Num(trashed.size.toLong()),
          "bytes" to ExportValue.Num(trashed.sumOf { it.size }),
          "expired" to ExportValue.Num(expired.toLong()),
        ),
      )
    }
    organizer.lastPurge.value?.let { report ->
      val export = linkedMapOf<String, ExportValue>(
        "purged" to ExportValue.Num(report.purged.toLong()),
        "bytes" to ExportValue.Num(report.bytes),
        "alreadyGone" to ExportValue.Num(report.alreadyGone.toLong()),
        "deferred" to ExportValue.Num(report.deferred.toLong()),
        "stoppedBy" to ExportValue.Enum.of(report.stoppedBy),
      )
      report.kept.forEach { (reason, n) -> export["reason.${reason.name}"] = ExportValue.Num(n.toLong()) }
      items += item(NasOpsDiagCodes.TRASH_PURGE, DiagSeverity.INFO, export)
    }
    organizer.lastMaintenance.value?.let { report ->
      items += item(
        NasOpsDiagCodes.INDEX_MAINTENANCE,
        if (report.clean.failed > 0) DiagSeverity.WARNING else DiagSeverity.INFO,
        mapOf(
          "compacted" to ExportValue.Num(report.compacted.toLong()),
          "skipped" to ExportValue.Num(report.skipped.toLong()),
          "removed" to ExportValue.Num(report.clean.removed.toLong()),
          "failed" to ExportValue.Num(report.clean.failed.toLong()),
          "stoppedBy" to ExportValue.Enum.of(report.clean.stoppedBy),
        ),
      )
    }

    // Dati personali.
    val status = organizer.personalStatus.value
    val last = organizer.lastPersonal.value
    if (organizer.parts?.personalSync != null) {
      val export = linkedMapOf<String, ExportValue>("status" to ExportValue.Enum.of(statusOf(status)))
      (status as? PersonalSyncStatus.Unavailable)?.let { export["reason"] = ExportValue.Enum.of(it.reason) }
      if (last != null) {
        export["published"] = ExportValue.Num(last.published.toLong())
        export["applied"] = ExportValue.Num(last.applied.toLong())
        export["stillUndated"] = ExportValue.Num(last.stillUndated.toLong())
        export["unresolved"] = ExportValue.Num(last.unresolved.toLong())
        export["stoppedBy"] = ExportValue.Enum.of(last.stoppedBy)
      }
      items += item(NasOpsDiagCodes.PERSONAL_STATE, DiagSeverity.INFO, export)
    }
    if (last != null && (last.rejected.isNotEmpty() || last.rejectedSegments > 0 || last.rejectedHeads > 0)) {
      val export = linkedMapOf<String, ExportValue>(
        "count" to ExportValue.Num(last.rejected.values.sum().toLong()),
        "rejectedSegments" to ExportValue.Num(last.rejectedSegments.toLong()),
        "rejectedHeads" to ExportValue.Num(last.rejectedHeads.toLong()),
      )
      last.rejected.forEach { (verdict, n) -> export["reason.${verdict.name}"] = ExportValue.Num(n.toLong()) }
      items += item(NasOpsDiagCodes.PERSONAL_REJECTED, DiagSeverity.WARNING, export)
    }
    return items
  }

  /** Lo stato della sincronizzazione come enumerato (senza i dati dentro). */
  enum class PersonalState { IDLE, SYNCING, UP_TO_DATE, UNAVAILABLE, LOCAL_ONLY }

  private fun statusOf(status: PersonalSyncStatus): PersonalState = when (status) {
    PersonalSyncStatus.Idle -> PersonalState.IDLE
    PersonalSyncStatus.Syncing -> PersonalState.SYNCING
    is PersonalSyncStatus.UpToDate -> PersonalState.UP_TO_DATE
    is PersonalSyncStatus.Unavailable -> PersonalState.UNAVAILABLE
    PersonalSyncStatus.LocalOnly -> PersonalState.LOCAL_ONLY
  }

  private fun item(code: DiagCode, severity: DiagSeverity, export: Map<String, ExportValue> = emptyMap()): DiagItem =
    DiagItem(code, section, severity, DiagCatalog.titleOf(code), null, null, export)

  private companion object {
    /** Le operazioni del diario guardate per gli elementi fermi: quelle che "Operazioni in corso" mostra ancora. */
    const val RECENT_OPS = 20
    const val TRASH_DAYS_MS = 30L * 24 * 60 * 60 * 1000
  }
}
