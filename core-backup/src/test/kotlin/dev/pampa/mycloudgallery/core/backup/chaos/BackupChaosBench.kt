package dev.pampa.mycloudgallery.core.backup.chaos

import java.io.File

// Il banco: tanti giri ([BackupChaosRound]) uno dopo l'altro, con i semi derivati da un seme di base, e il rapporto. Stesso impianto di
// ChaosBench della 4B (core-nasops/.../chaos/ChaosBench.kt).

/** L'esito di un banco. */
class BenchResult(
  val rounds: Int,
  val failures: List<RoundReport>,
  val stats: ChaosStats,
  val millis: Long,
  val baseSeed: Long,
  val targetName: String,
  val stackName: String,
  private val gapRounds: List<RoundReport> = emptyList(),
) {
  val ok: Boolean get() = failures.isEmpty()

  /** I giri in cui il banco ha visto un difetto noto del motore (dichiarato, non fa fallire il giro). */
  val knownGapRounds: List<RoundReport> get() = gapRounds

  /** Il riassunto per il rapporto: giri, interruzioni per tipo e momento, copertura delle primitive. Mai nomi di file ne' impronte. */
  fun summary(): String = buildString {
    appendLine("=== Prova delle interruzioni del backup: $rounds giri, seme di base $baseSeed ===")
    appendLine("NAS: $targetName; motore: $stackName")
    appendLine("durata: ${millis / 1000.0} s; giri con violazioni: ${failures.size}")
    if (gapRounds.isNotEmpty()) {
      appendLine("DIFETTO NOTO F1 (docs/prova-500.md): visto in ${gapRounds.size} giri, ${gapRounds.sumOf { it.knownGaps.size }} elementi (dichiarato: non fa fallire il giro)")
    }
    appendLine("passi eseguiti: ${stats.steps}; media sul telefono: ${stats.mediaTotal}; verifiche a meta': ${stats.midChecks}; verifiche a lavoro finito: ${stats.finalChecks}; riavvii: ${stats.restarts}")
    appendLine("file riletti con SHA-256: ${stats.filesHashed} (${stats.bytesHashed / 1024} KiB); eventi del telefono scattati a meta' lettura: ${stats.phoneEventsFired}")
    val total = stats.totalFired
    if (stats.armedSteps > 0) {
      val n = stats.armedSteps
      appendLine("chiamate per passo senza colpi: ${stats.sumNas / n} al NAS, ${stats.sumStore / n} al diario e al catalogo, ${stats.sumPhone / n} al telefono ($n passi, ${stats.sumUnits / n} unita' di lavoro in media)")
    }
    appendLine("interruzioni cadute: $total")
    for (kind in FaultKind.entries) appendLine("  ${kind.label}: ${stats.firedBy(kind)} (${percent(stats.firedBy(kind), total)})")
    appendLine("  prima della chiamata: ${stats.firedBy(Moment.BEFORE)} (${percent(stats.firedBy(Moment.BEFORE), total)}), dopo (risposta persa): ${stats.firedBy(Moment.AFTER)} (${percent(stats.firedBy(Moment.AFTER), total)})")
    appendLine("passi per tipo:")
    stats.stepKinds.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.key}: ${it.value}") }
    appendLine("eventi tra un passo e l'altro:")
    stats.events.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.key}: ${it.value}") }
    appendLine("stato finale degli elementi del diario:")
    stats.itemStates.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.key}: ${it.value}") }
    appendLine("copertura per primitiva:")
    append(stats.coverageTable())
  }

  private fun percent(part: Int, total: Int): String = if (total == 0) "0%" else "%.0f%%".format(100.0 * part / total)

  fun failureText(limit: Int = 3): String =
    failures.take(limit).joinToString("\n\n") { it.describe() } + if (failures.size > limit) "\n\n... e altri ${failures.size - limit} giri" else ""

  fun writeReport(file: File) {
    file.parentFile?.mkdirs()
    file.writeText(summary() + if (failures.isNotEmpty()) "\n" + failureText(60) else "")
  }
}

object BackupChaosBench {

  /** Il seme del giro [index]: ripetibile da (seme di base, numero del giro). */
  fun seedOf(baseSeed: Long, index: Int): Long = baseSeed * 1_000_003L + index * 7_919L + 17

  /**
   * Fa [rounds] giri. [stopAfterInterruptions]: il runner sul NAS vero conta le interruzioni, non i giri; si ferma quando ne sono cadute
   * abbastanza (i giri sono comunque al massimo [rounds]). [statesDir]: dove salvare lo stato di un giro che fallisce (si verifica da solo).
   */
  suspend fun run(
    rounds: Int,
    baseSeed: Long,
    target: BackupChaosTarget,
    factory: BackupChaosStackFactory = ReferenceStack,
    config: BackupChaosConfig = BackupChaosConfig(),
    stats: ChaosStats = ChaosStats(),
    stopAfterInterruptions: Int? = null,
    firstRound: Int = 0,
    redact: Boolean = false,
    statesDir: File? = null,
    onFailure: (RoundReport) -> Unit = {},
    /** Dopo ogni giro (giri fatti, giri rossi): l'avanzamento di una prova lunga sul NAS vero. */
    onRound: (Int, Int) -> Unit = { _, _ -> },
  ): BenchResult {
    val started = System.nanoTime()
    val failures = ArrayList<RoundReport>()
    val gapRounds = ArrayList<RoundReport>()
    var done = 0
    for (index in firstRound until firstRound + rounds) {
      if (stopAfterInterruptions != null && stats.totalFired >= stopAfterInterruptions) break
      val report = BackupChaosRound(index, seedOf(baseSeed, index), target, factory, config, stats, redact).run()
      done++
      if (report.knownGaps.isNotEmpty()) gapRounds += report
      if (!report.ok) {
        failures += report
        if (statesDir != null) report.badState?.let { runCatching { BackupStateFile.write(it, File(statesDir, "backup-state-$index.txt")) } }
        onFailure(report)
      }
      onRound(done, failures.size)
    }
    return BenchResult(done, failures, stats, (System.nanoTime() - started) / 1_000_000, baseSeed, target.name, factory.name, gapRounds)
  }
}
