package dev.pampa.mycloudgallery.core.faces.chaos

import java.io.File

// IL BANCO: tanti giri uno dopo l'altro, con i semi derivati da un seme di base, e il rapporto (come `ShareChaosBench` e `ChaosBench` degli altri banchi).

/** L'esito di un banco. */
internal class ChaosResult(
  val rounds: Int,
  val failures: List<RoundReport>,
  val stats: ChaosStats,
  val millis: Long,
  val baseSeed: Long,
  val targetName: String,
  /** Perche' il banco si e' fermato prima del tempo (un login rifiutato sul NAS vero), se e' successo. */
  val stoppedBecause: String? = null,
) {
  val ok: Boolean get() = failures.isEmpty() && stoppedBecause == null

  /** Il riassunto: giri, interruzioni per tipo e momento, copertura per punto. */
  fun summary(): String = buildString {
    appendLine("=== Banco delle interruzioni dei volti: $rounds giri, seme di base $baseSeed ===")
    appendLine("NAS: $targetName")
    appendLine("durata: ${millis / 1000.0} s; giri con violazioni: ${failures.size}")
    stoppedBecause?.let { appendLine("FERMATO: $it") }
    appendLine("passi eseguiti: ${stats.steps}; verifiche dopo una morte o a meta': ${stats.midChecks}; verifiche a lavoro finito: ${stats.quietChecks}; riavvii: ${stats.restarts}; riprese a vuoto: ${stats.idleRestarts}")
    appendLine("file riletti con SHA-256: ${stats.filesHashed} (${stats.bytesHashed / 1024} KiB); istantanee del NAS rilette: ${stats.snapshotsRead}")
    appendLine("calcolo: ${stats.computedMedia} corpi dei volti salvati; cancellazioni partite: ${stats.eraseStarted}; prenotazioni viste: ${stats.leaseEvents} (contese ${stats.contendedLeases})")
    val total = stats.totalFired
    appendLine("interruzioni cadute: $total (morti del processo ${stats.totalDeaths})")
    for (kind in FaultKind.entries) appendLine("  ${kind.label}: ${stats.firedOf(kind)} (${percent(stats.firedOf(kind), total)})")
    appendLine("  prima della chiamata: ${stats.firedAt(Moment.BEFORE)} (${percent(stats.firedAt(Moment.BEFORE), total)}), dopo (risposta persa): ${stats.firedAt(Moment.AFTER)} (${percent(stats.firedAt(Moment.AFTER), total)})")
    appendLine("passi per tipo:")
    stats.stepKinds.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.key}: ${it.value}") }
    appendLine("copertura per punto:")
    append(stats.coverageTable())
    val never = stats.neverHit()
    if (never.isNotEmpty()) appendLine("punti dove nessun colpo e' scattato: " + never.joinToString { it.label })
  }

  private fun percent(part: Int, total: Int): String = if (total == 0) "0%" else "%.0f%%".format(100.0 * part / total)

  fun failureText(limit: Int = 3): String =
    failures.take(limit).joinToString("\n\n") { it.describe() } + if (failures.size > limit) "\n\n... e altri ${failures.size - limit} giri" else ""

  fun writeReport(file: File) {
    file.parentFile?.mkdirs()
    file.writeText(summary() + if (failures.isNotEmpty()) "\n" + failureText(60) else "")
  }

  /** Quali invarianti hanno fatto diventare rossi i giri, e quante volte. */
  fun violatedInvariants(): Map<Invariant, Int> = failures.flatMap { it.violations }.groupingBy { it.invariant }.eachCount()
}

internal object ChaosBench {

  /** Il seme del giro [index]: ripetibile da (seme di base, numero del giro). */
  fun seedOf(baseSeed: Long, index: Int): Long = baseSeed * 1_000_003L + index * 7_919L + 17

  /**
   * Fa [rounds] giri. [stopAfterInterruptions]: il runner sul NAS vero conta le interruzioni, non i giri; si ferma quando ne sono cadute abbastanza
   * (i giri sono comunque al massimo [rounds]).
   */
  suspend fun run(
    rounds: Int,
    baseSeed: Long,
    factory: ChaosTargetFactory,
    config: ChaosConfig = ChaosConfig(factory.capabilities),
    stats: ChaosStats = ChaosStats(),
    firstRound: Int = 0,
    stopAfterInterruptions: Int? = null,
    stopOnFirstFailure: Boolean = false,
    /** Chiesto dopo ogni giro: un motivo = ci si ferma (un login rifiutato sul NAS vero, mai un nuovo tentativo). */
    stopReason: () -> String? = { null },
    onFailure: (RoundReport) -> Unit = {},
    /** Dopo ogni giro (giri fatti, giri rossi): l'avanzamento di una prova lunga. */
    onRound: (Int, Int) -> Unit = { _, _ -> },
  ): ChaosResult {
    val started = System.nanoTime()
    val failures = ArrayList<RoundReport>()
    var done = 0
    var stopped: String? = null
    for (index in firstRound until firstRound + rounds) {
      if (stopAfterInterruptions != null && stats.totalFired >= stopAfterInterruptions) break
      val report = try {
        ChaosRound(index, seedOf(baseSeed, index), factory, config, stats).run()
      } catch (e: FacesAreaAuthStop) {
        // Un login rifiutato sul NAS vero: ci si ferma qui, senza nuovi tentativi (il NAS blocca l'account).
        stopped = e.message ?: "login rifiutato dal NAS"
        break
      }
      done++
      if (!report.ok) {
        failures += report
        onFailure(report)
      }
      onRound(done, failures.size)
      if (stopOnFirstFailure && failures.isNotEmpty()) break
      stopped = stopReason()
      if (stopped != null) break
    }
    return ChaosResult(done, failures, stats, (System.nanoTime() - started) / 1_000_000, baseSeed, factory.name, stopped)
  }
}

/** Il NAS finto in memoria: la share di P3 con i colpi, la cancellazione stretta della regola di D31 (2.000 giri a ogni `check.ps1`). */
internal object MemoryTarget : ChaosTargetFactory {
  override val name: String = "NAS finto in memoria (la share di P3 con i colpi su ogni richiesta)"
  override val capabilities: Capabilities = Capabilities.MEMORY

  override fun create(world: ChaosWorld): ChaosNas = MemoryChaosNas(world.inj, world.logs, world.family.common)
}
