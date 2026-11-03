package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog

/**
 * I codici del backup in Diagnostica (Fase 5A, P7; docs/fase5-progetto.md §2.6): uno per ogni riga della tabella dei problemi
 * (`BackupProblemCode`, "BACKUP_" + il nome) e due voci di stato. Gli stessi id ed edge case del catalogo di feature-diagnostics
 * ([DiagCodes]), che li scrive come letterali; `DiagnosticsWiringTest` li confronta.
 */
object BackupDiagCodes {
  val STATE = DiagCode("BACKUP_STATE")

  /** L'ultimo giro: quanti media salvati, collegati, spariti, falliti e perche' si e' fermato. */
  val LAST_ROUND = DiagCode("BACKUP_LAST_ROUND")

  /** Le copie simili (D36): quante aspettano il confronto. */
  val SIMILAR_COPIES = DiagCode("BACKUP_SIMILAR_COPIES")

  /** Il codice di Diagnostica di un problema del backup: stesso nome, stessi edge case. */
  fun of(problem: BackupProblemCode): DiagCode = DiagCode("BACKUP_" + problem.name, problem.edgeCases)

  val ALL: List<DiagCode> = listOf(STATE, LAST_ROUND, SIMILAR_COPIES) + BackupProblemCode.entries.map(::of)
}

/** Lo stato del backup come enumerato per l'esportazione (senza i dati dentro). */
enum class BackupStatusKind { NOT_CONFIGURED, TEST_ONLY, FIRST_BACKUP_READY, UP_TO_DATE, WAITING, RUNNING, STOPPED, PROBLEM }

/**
 * La Diagnostica del backup (Fase 5A, P7): stato, cancello, sorgenti, coda, diario, ultimo giro, copie simili e **un elemento per ogni
 * problema** attivo (con quanti media riguarda). Legge il catalogo, il diario e la memoria locali: **nessuna chiamata al NAS**.
 *
 * **Solo numeri, flag ed enumerati**: mai nomi di file, cartelle, share, account, id di media o di elementi del diario, impronte.
 */
class BackupDiagnostics(private val backup: BackupGraph, private val similar: SimilarCopiesGraph?) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.BACKUP

  override suspend fun collect(): List<DiagItem> {
    val items = ArrayList<DiagItem>()
    val status = backup.status.value
    val share = backup.privateShare()
    val gate = share?.let { backup.gate.stateOf(it) }
    val sources = backup.sources.value

    // Lo stato.
    val state = linkedMapOf<String, ExportValue>(
      "backupStatus" to ExportValue.Enum.of(kindOf(status)),
      "sources" to ExportValue.Num(sources.count { it.active }.toLong()),
      "folderChosen" to ExportValue.Flag(backup.settings.deviceFolder.value != null),
      "safe" to ExportValue.Num(sources.sumOf { it.safe }.toLong()),
      "queued" to ExportValue.Num(sources.sumOf { it.queued }.toLong()),
    )
    gate?.let { state["gate"] = ExportValue.Enum.of(it) }
    runCatching { backup.store.counts() }.getOrNull()?.let {
      state["diaryOpen"] = ExportValue.Num(it.open.toLong())
      state["diaryBytesOpen"] = ExportValue.Num(it.bytesOpen)
    }
    backup.memory.lastProblem()?.let { state["lastProblem"] = ExportValue.Enum.of(it) }
    backup.memory.lastComplete()?.let { done -> backup.clock.nasNowMs()?.let { now -> state["lastCompleteAgeMs"] = ExportValue.Dur((now - done).coerceAtLeast(0)) } }
    items += item(BackupDiagCodes.STATE, DiagSeverity.INFO, state)

    // Un elemento per problema: dallo stato e dalle righe delle sorgenti (quanti media), una volta per codice.
    val problems = linkedMapOf<BackupProblemCode, Int>()
    (status as? BackupStatus.Problem)?.let { problems[it.code] = maxOf(it.count, problems[it.code] ?: 0) }
    if (status == BackupStatus.TestOnly) problems[BackupProblemCode.TEST_ONLY] = 0
    sources.forEach { source -> source.problems.forEach { (code, n) -> problems[code] = (problems[code] ?: 0) + n } }
    backup.memory.lastProblem()?.let { problems.getOrPut(it) { 0 } }
    gate?.problem?.let { problems.getOrPut(it) { 0 } }
    // "Fermo da 3 giorni" e memoria piena: dagli stessi fatti del riepilogo giornaliero.
    runCatching { backup.dailyFacts() }.getOrNull()?.let { facts ->
      val since = facts.lastBeatAt ?: facts.startedAt
      if (facts.queued > 0 && since != null) {
        val days = (System.currentTimeMillis() - since) / DAY_MS
        if (days >= STALL_DAYS) problems.getOrPut(BackupProblemCode.STALE) { 0 }
      }
    }
    if (backup.phoneStorageLow()) problems.getOrPut(BackupProblemCode.PHONE_STORAGE_LOW) { 0 }
    // P14 (5B): le versioni che aspettano perche' il PC ha cambiato il file (D17, E-E6), dal diario locale.
    runCatching { backup.fiveB.sourceChangedHolds() }.getOrDefault(0).takeIf { it > 0 }?.let { problems[BackupProblemCode.SOURCE_CHANGED] = it }
    problems.entries.sortedBy { it.key.ordinal }.forEach { (code, count) ->
      val diagCode = BackupDiagCodes.of(code)
      val severity = DiagCatalog.entry(diagCode)?.severity ?: DiagSeverity.WARNING
      val export = mapOf("count" to ExportValue.Num(count.toLong()))
      items += if (code == BackupProblemCode.WRITES_STOPPED && backup.writesHeldOnlyByRelease) {
        // CA-1 (revisione funzionale della Fase 10): le ferma il manifest firmato, non l'interruttore: la causa ("aggiorna l'app") e nessun "Riattiva le scritture".
        DiagItem(
          diagCode, section, severity, DiagCatalog.titleOf(diagCode),
          DiagText(dev.pampa.mycloudgallery.feature.diagnostics.R.string.diag_backup_writes_held_by_release), DiagnosticsActions.NO_ACTION, export,
        )
      } else {
        item(diagCode, severity, export)
      }
    }

    // L'ultimo giro.
    backup.lastRound.value?.let { round ->
      items += item(
        BackupDiagCodes.LAST_ROUND,
        if (round.failed > 0) DiagSeverity.WARNING else DiagSeverity.INFO,
        mapOf(
          "done" to ExportValue.Num(round.done.toLong()),
          "linked" to ExportValue.Num(round.linked.toLong()),
          "gone" to ExportValue.Num(round.gone.toLong()),
          "failed" to ExportValue.Num(round.failed.toLong()),
          "waiting" to ExportValue.Num(round.waiting.toLong()),
          "bytesUploaded" to ExportValue.Num(round.bytesUploaded),
          "bytesReread" to ExportValue.Num(round.bytesReread),
          "stoppedBy" to ExportValue.Flag(round.stop != COMPLETE),
        ),
      )
    }

    // Le copie simili.
    similar?.let { detector ->
      val progress = detector.progress.value
      items += item(
        BackupDiagCodes.SIMILAR_COPIES,
        DiagSeverity.INFO,
        mapOf(
          "available" to ExportValue.Flag(detector.available),
          "pending" to ExportValue.Num(progress.pending.toLong()),
          "similarRunning" to ExportValue.Flag(progress.running),
        ),
      )
    }
    return items
  }

  private fun kindOf(status: BackupStatus): BackupStatusKind = when (status) {
    is BackupStatus.NotConfigured -> BackupStatusKind.NOT_CONFIGURED
    BackupStatus.TestOnly -> BackupStatusKind.TEST_ONLY
    is BackupStatus.FirstBackupReady -> BackupStatusKind.FIRST_BACKUP_READY
    is BackupStatus.UpToDate -> BackupStatusKind.UP_TO_DATE
    is BackupStatus.Waiting -> BackupStatusKind.WAITING
    is BackupStatus.Running -> BackupStatusKind.RUNNING
    is BackupStatus.Stopped -> BackupStatusKind.STOPPED
    is BackupStatus.Problem -> BackupStatusKind.PROBLEM
  }

  private fun item(code: DiagCode, severity: DiagSeverity, export: Map<String, ExportValue>): DiagItem =
    DiagItem(code, section, severity, DiagCatalog.titleOf(code), null, null, export)

  private companion object {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val STALL_DAYS = 3L
    const val COMPLETE = "complete"
  }
}

/** Senza backup (nessun NAS nella Libreria, varianti di misura): nessuna voce. */
object NoBackupDiagnostics : DiagnosticsContributor {
  override val section: DiagSection = DiagSection.BACKUP

  override suspend fun collect(): List<DiagItem> = emptyList()
}
