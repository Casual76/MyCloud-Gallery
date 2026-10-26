package dev.pampa.mycloudgallery.core.albums.chaos

import java.io.File

// IL BANCO: tanti giri uno dopo l'altro, con i semi derivati da un seme di base, e il rapporto (come `ChaosBench` di core-nasops).

/** L'esito di un banco. */
class ShareChaosResult(
  val rounds: Int,
  val failures: List<RoundReport>,
  val stats: ShareChaosStats,
  val millis: Long,
  val baseSeed: Long,
  val targetName: String,
) {
  val ok: Boolean get() = failures.isEmpty()

  /** Il riassunto: giri, interruzioni per tipo e momento, copertura per punto. */
  fun summary(): String = buildString {
    appendLine("=== Banco delle interruzioni della condivisione: $rounds giri, seme di base $baseSeed ===")
    appendLine("NAS: $targetName")
    appendLine("durata: ${millis / 1000.0} s; giri con violazioni: ${failures.size}")
    appendLine("passi eseguiti: ${stats.steps}; verifiche dopo una morte o a meta': ${stats.midChecks}; verifiche a lavoro finito: ${stats.quiescentChecks}; riavvii: ${stats.restarts}; riprese a vuoto: ${stats.idleRestarts}")
    appendLine("file riletti con SHA-256: ${stats.filesHashed} (${stats.bytesHashed / 1024} KiB); eventi guardati dagli osservatori: ${stats.observed}")
    appendLine("elementi finiti: fatti ${stats.itemsDone}, falliti con un motivo ${stats.itemsFailed}, annullati ${stats.itemsCancelled}")
    val total = stats.totalFired
    appendLine("interruzioni cadute: $total (morti del processo ${stats.totalDeaths})")
    for (kind in ShareFaultKind.entries) appendLine("  ${kind.label}: ${stats.firedBy(kind)} (${percent(stats.firedBy(kind), total)})")
    appendLine("  prima della chiamata: ${stats.firedAt(Moment.BEFORE)} (${percent(stats.firedAt(Moment.BEFORE), total)}), dopo (risposta persa): ${stats.firedAt(Moment.AFTER)} (${percent(stats.firedAt(Moment.AFTER), total)})")
    appendLine("passi per tipo:")
    stats.stepKinds.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.key}: ${it.value}") }
    appendLine("copertura per punto:")
    append(stats.coverageTable())
  }

  private fun percent(part: Int, total: Int): String = if (total == 0) "0%" else "%.0f%%".format(100.0 * part / total)

  fun failureText(limit: Int = 3): String =
    failures.take(limit).joinToString("\n\n") { it.describe() } + if (failures.size > limit) "\n\n... e altri ${failures.size - limit} giri" else ""

  fun writeReport(file: File) {
    file.parentFile?.mkdirs()
    file.writeText(summary() + if (failures.isNotEmpty()) "\n" + failureText(60) else "")
  }

  /** Quali invarianti hanno fatto diventare rossi i giri, e quante volte. */
  fun violatedInvariants(): Map<ShareInvariant, Int> =
    failures.flatMap { it.violations }.groupingBy { it.invariant }.eachCount()
}

object ShareChaosBench {

  /** Il seme del giro [index]: ripetibile da (seme di base, numero del giro). */
  fun seedOf(baseSeed: Long, index: Int): Long = baseSeed * 1_000_003L + index * 7_919L + 17

  /**
   * Fa [rounds] giri. [stopAfterInterruptions]: il runner sul NAS vero conta le interruzioni, non i giri; si ferma quando ne sono cadute
   * abbastanza (i giri sono comunque al massimo [rounds]).
   */
  suspend fun run(
    rounds: Int,
    baseSeed: Long,
    factory: ChaosTargetFactory,
    config: ShareChaosConfig = ShareChaosConfig(factory.capabilities),
    stats: ShareChaosStats = ShareChaosStats(),
    firstRound: Int = 0,
    stopAfterInterruptions: Int? = null,
    hooks: ShareChaosHooks = ShareChaosHooks(),
    stopOnFirstFailure: Boolean = false,
    onFailure: (RoundReport) -> Unit = {},
    /** Dopo ogni giro (giri fatti, giri rossi): l'avanzamento di una prova lunga sul NAS vero. */
    onRound: (Int, Int) -> Unit = { _, _ -> },
  ): ShareChaosResult {
    val started = System.nanoTime()
    val failures = ArrayList<RoundReport>()
    var done = 0
    for (index in firstRound until firstRound + rounds) {
      if (stopAfterInterruptions != null && stats.totalFired >= stopAfterInterruptions) break
      val report = ShareChaosRound(index, seedOf(baseSeed, index), factory, config, stats, hooks).run()
      done++
      if (!report.ok) {
        failures += report
        onFailure(report)
      }
      onRound(done, failures.size)
      if (stopOnFirstFailure && failures.isNotEmpty()) break
    }
    return ShareChaosResult(done, failures, stats, (System.nanoTime() - started) / 1_000_000, baseSeed, factory.name)
  }
}
