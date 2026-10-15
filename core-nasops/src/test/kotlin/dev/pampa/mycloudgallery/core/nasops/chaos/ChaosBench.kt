package dev.pampa.mycloudgallery.core.nasops.chaos

import java.io.File

// Il banco: tanti giri (ChaosRound) uno dopo l'altro, con i semi derivati da un seme di base, e il rapporto.

/** L'esito di un banco. */
class BenchResult(
  val rounds: Int,
  val failures: List<RoundReport>,
  val stats: ChaosStats,
  val millis: Long,
  val baseSeed: Long,
  val targetName: String,
  val stackName: String,
  /** I difetti noti dello stack sotto prova (primitive non colpite): sempre nel rapporto. */
  val knownDefects: List<String> = emptyList(),
) {
  val ok: Boolean get() = failures.isEmpty()

  /** Il riassunto per il rapporto: giri, interruzioni per tipo e momento, copertura delle primitive. */
  fun summary(): String = buildString {
    appendLine("=== Prova delle interruzioni: $rounds giri, seme di base $baseSeed ===")
    appendLine("NAS: $targetName; operazioni: $stackName")
    appendLine("durata: ${millis / 1000.0} s; giri con violazioni: ${failures.size}")
    knownDefects.forEach { appendLine("DIFETTO NOTO: $it") }
    stats.knownDefectHits.entries.sortedBy { it.key }.forEach { appendLine("difetto noto ${it.key} riprodotto ${it.value} volte (giri fermati li', non contati come fallimenti)") }
    appendLine("passi eseguiti: ${stats.steps}; verifiche a meta': ${stats.midChecks}; verifiche a operazioni finite: ${stats.quiescentChecks}; riavvii: ${stats.restarts}; annullamenti: ${stats.cancels}")
    appendLine("file riletti con SHA-256: ${stats.filesHashed} (${stats.bytesHashed / 1024} KiB)")
    val total = stats.totalFired
    appendLine("interruzioni cadute: $total")
    for (kind in FaultKind.entries) appendLine("  ${kind.label}: ${stats.firedBy(kind)} (${percent(stats.firedBy(kind), total)})")
    appendLine("  prima della chiamata: ${stats.firedBy(Moment.BEFORE)} (${percent(stats.firedBy(Moment.BEFORE), total)}), dopo (risposta persa): ${stats.firedBy(Moment.AFTER)} (${percent(stats.firedBy(Moment.AFTER), total)})")
    appendLine("passi per tipo:")
    stats.stepKinds.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.key}: ${it.value}") }
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

object ChaosBench {

  /** Il seme del giro [index]: ripetibile da (seme di base, numero del giro). */
  fun seedOf(baseSeed: Long, index: Int): Long = baseSeed * 1_000_003L + index * 7_919L + 17

  /**
   * Fa [rounds] giri. [stopAfterInterruptions]: il runner sul NAS vero conta le interruzioni, non i giri; si ferma quando ne sono cadute
   * abbastanza (i giri sono comunque al massimo [rounds]).
   */
  suspend fun run(
    rounds: Int,
    baseSeed: Long,
    target: ChaosTarget,
    factory: ChaosStackFactory = FakeChaosStackFactory,
    config: ChaosConfig = ChaosConfig(),
    stats: ChaosStats = ChaosStats(),
    stopAfterInterruptions: Int? = null,
    firstRound: Int = 0,
    onFailure: (RoundReport) -> Unit = {},
  ): BenchResult {
    val started = System.nanoTime()
    val failures = ArrayList<RoundReport>()
    var done = 0
    for (index in firstRound until firstRound + rounds) {
      if (stopAfterInterruptions != null && stats.totalFired >= stopAfterInterruptions) break
      val report = ChaosRound(index, seedOf(baseSeed, index), target, factory, config, stats).run()
      done++
      if (!report.ok) {
        failures += report
        onFailure(report)
      }
    }
    return BenchResult(done, failures, stats, (System.nanoTime() - started) / 1_000_000, baseSeed, target.name, factory.name, factory.knownDefects)
  }
}
