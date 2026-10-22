package dev.pampa.mycloudgallery.core.ai.chaos

import java.io.File

// Il BANCO del calcolo: tanti giri uno dopo l'altro con i semi derivati da un seme di base, e il rapporto. Stesso impianto di `ChaosBench`
// della 4B e del banco dello scaricamento (`core.models.chaos.ModelChaosBench`).

class AiBenchResult(
  val rounds: Int,
  val failures: List<AiRoundReport>,
  val totals: AiRoundStats,
  val millis: Long,
  val baseSeed: Long,
  val stackName: String,
  val byInvariant: Map<AiInvariant, Int>,
  /** Giri in cui e' comparso un difetto noto del codice di produzione (non fanno rosso: sono nel REPORT con il seme). */
  val knownRounds: Map<KnownDefect, List<Long>> = emptyMap(),
) {
  val ok: Boolean get() = failures.isEmpty()

  /** Il riassunto per il rapporto. Mai chiavi, nomi o testo letto: solo numeri. */
  fun summary(): String = buildString {
    appendLine("=== Prova delle interruzioni del calcolo AI: $rounds giri, seme di base $baseSeed ===")
    appendLine("NAS finto di P5 con guasti prima e dopo, motori finti, AiComputeEngine, DiskAiBuffer e LocalModels veri; stack: $stackName")
    appendLine("durata: ${millis / 1000.0} s; giri con violazioni: ${failures.size}")
    appendLine("vite del processo: ${totals.lives}; morti: ${totals.deaths}; controlli degli invarianti: ${totals.checks}")
    appendLine("salvataggi finiti: ${totals.savesDone}; immagini calcolate: ${totals.embedded}; voci pubblicate: ${totals.published}; voci rimesse in fila senza ricalcolo: ${totals.restaged}")
    appendLine("guasti del NAS scattati: ${totals.nasFaultsFired}; unita' prese da un altro a meta': ${totals.takeovers}; prenotazioni perse: ${totals.unitsLost}; pubblicazione chiusa: ${totals.blocks}; voci in attesa pubblicate dopo: ${totals.pendingReleased}")
    appendLine("modello: caricamenti controllati ${totals.modelLoads}, morti durante lo scaricamento ${totals.modelDeaths}")
    appendLine("nascosti calcolati e ricerche di riferimento: ${totals.hiddenComputed} / ${totals.searches}")
    appendLine("violazioni per invariante:")
    for (inv in AiInvariant.entries) appendLine("  I${inv.number} ${inv.text}: ${byInvariant[inv] ?: 0}")
    appendLine("difetti noti del codice di produzione incontrati (non fanno rosso):")
    if (knownRounds.isEmpty()) appendLine("  nessuno")
    for ((d, seeds) in knownRounds) appendLine("  ${d.id}: in ${seeds.size} giri, il primo con il seme ${seeds.first()}")
  }

  fun failureText(limit: Int = 3): String =
    failures.take(limit).joinToString("\n\n") { it.describe() } + if (failures.size > limit) "\n\n... e altri ${failures.size - limit} giri" else ""

  fun writeReport(file: File) {
    file.parentFile?.mkdirs()
    file.writeText(summary() + if (failures.isNotEmpty()) "\n" + failureText(60) else "")
  }
}

object AiChaosBench {
  /** Il seme del giro [index] dato il seme di base (si rilancia un giro solo con il suo seme). */
  fun seedOf(baseSeed: Long, index: Int): Long = baseSeed * 1_000_003L + index * 7_919L + 29L

  fun run(
    rounds: Int,
    baseSeed: Long,
    factory: AiStackFactory = AiStackFactory.REAL,
    stackName: String = "calcolo di produzione",
    stopAtFirstFailure: Boolean = false,
    firstRound: Int = 0,
  ): AiBenchResult {
    val started = System.nanoTime()
    val failures = ArrayList<AiRoundReport>()
    val totals = AiRoundStats()
    val byInvariant = LinkedHashMap<AiInvariant, Int>()
    val known = LinkedHashMap<KnownDefect, MutableList<Long>>()
    var done = 0
    for (i in firstRound until firstRound + rounds) {
      val report = AiChaosRound.run(AiRoundPlan.generate(seedOf(baseSeed, i)), factory)
      report.index = i
      done++
      add(totals, report.stats)
      report.knownDefects.forEach { known.getOrPut(it) { ArrayList() } += report.plan.seed }
      if (report.failures.isNotEmpty()) {
        failures += report
        report.failures.forEach { byInvariant.merge(it.invariant, 1, Int::plus) }
        if (stopAtFirstFailure) break
      }
    }
    return AiBenchResult(done, failures, totals, (System.nanoTime() - started) / 1_000_000, baseSeed, stackName, byInvariant, known)
  }

  private fun add(sum: AiRoundStats, s: AiRoundStats) {
    sum.lives += s.lives
    sum.deaths += s.deaths
    sum.savesDone += s.savesDone
    sum.embedded += s.embedded
    sum.nasFaultsFired += s.nasFaultsFired
    sum.takeovers += s.takeovers
    sum.blocks += s.blocks
    sum.published += s.published
    sum.restaged += s.restaged
    sum.checks += s.checks
    sum.hiddenComputed += s.hiddenComputed
    sum.modelLoads += s.modelLoads
    sum.modelDeaths += s.modelDeaths
    sum.searches += s.searches
    sum.unitsLost += s.unitsLost
    sum.pendingReleased += s.pendingReleased
  }
}
