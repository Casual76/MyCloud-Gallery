package dev.pampa.mycloudgallery.core.models.chaos

import dev.pampa.mycloudgallery.core.models.support.HttpsTestServer
import java.io.File

// Il BANCO: tanti giri uno dopo l'altro con i semi derivati da un seme di base, e il rapporto. Stesso impianto di `ChaosBench` della 4B.
// Il server HTTPS di prova e' uno per banco (si crea una volta sola il contesto TLS); ogni giro installa il suo gestore.

class ModelBenchResult(
  val rounds: Int,
  val failures: List<ModelRoundReport>,
  val totals: ModelRoundStats,
  val millis: Long,
  val baseSeed: Long,
  val backend: ModelBackend,
  val stackName: String,
  val byInvariant: Map<ModelInvariant, Int>,
) {
  val ok: Boolean get() = failures.isEmpty()

  /** Il riassunto per il rapporto. Mai nomi di file, indirizzi o impronte: solo numeri. */
  fun summary(): String = buildString {
    appendLine("=== Prova delle interruzioni dello scaricamento: $rounds giri, seme di base $baseSeed ===")
    appendLine("NAS: ${if (backend == ModelBackend.SMB) "simulatore SMB con sessione e lettura vere" else "NAS finto in memoria con FaultPlan"}; stack: $stackName")
    appendLine("durata: ${millis / 1000.0} s; giri con violazioni: ${failures.size}")
    appendLine("vite del processo: ${totals.lives}; morti: ${totals.deaths}; passi: ${totals.steps}; caricamenti controllati: ${totals.loads}; controlli degli invarianti: ${totals.checks}")
    appendLine("richieste HTTPS con Range: ${totals.rangedRequests}; risposte 200 al posto di 206: ${totals.serverRestarts}; corpi tagliati: ${totals.cuts}; file scartati per impronta o lunghezza: ${totals.mismatches}")
    appendLine("letture del NAS dei modelli: ${totals.nasOpens} aperture, ${totals.nasReads} blocchi letti a intervalli; guasti di lettura scattati: ${totals.nasFaultsFired}")
    appendLine("blocchi salvati: ${totals.savedBlocks} (${totals.recordedBytes / (1024 * 1024)} MiB); file manomessi: ${totals.tampered}; copie sul NAS pubblicate: ${totals.shelfPublications}")
    appendLine("violazioni per invariante:")
    for (inv in ModelInvariant.entries) appendLine("  I${inv.number} ${inv.text}: ${byInvariant[inv] ?: 0}")
  }

  fun failureText(limit: Int = 3): String =
    failures.take(limit).joinToString("\n\n") { it.describe() } + if (failures.size > limit) "\n\n... e altri ${failures.size - limit} giri" else ""

  fun writeReport(file: File) {
    file.parentFile?.mkdirs()
    file.writeText(summary() + if (failures.isNotEmpty()) "\n" + failureText(60) else "")
  }
}

object ModelChaosBench {
  /** Il seme del giro [index] dato il seme di base: una funzione semplice e ripetibile (si rilancia un giro solo con il suo seme). */
  fun seedOf(baseSeed: Long, index: Int): Long = baseSeed * 1_000_003L + index * 7_919L + 13L

  fun run(
    rounds: Int,
    baseSeed: Long,
    backend: ModelBackend,
    factory: ModelStackFactory = ModelStackFactory.REAL,
    stackName: String = "gestore e fetcher di produzione (LocalModels, NasModelFetcher, HttpsModelFetcher)",
    stopAtFirstFailure: Boolean = false,
    firstRound: Int = 0,
  ): ModelBenchResult {
    val started = System.nanoTime()
    val failures = ArrayList<ModelRoundReport>()
    val totals = ModelRoundStats()
    val byInvariant = LinkedHashMap<ModelInvariant, Int>()
    var done = 0
    HttpsTestServer().use { server ->
      for (i in firstRound until firstRound + rounds) {
        val report = ModelChaosRound.run(ModelRoundPlan.generate(seedOf(baseSeed, i), backend), server, factory)
        report.index = i
        done++
        add(totals, report.stats)
        if (report.violations.isNotEmpty()) {
          failures += report
          report.violations.forEach { byInvariant.merge(it.invariant, 1, Int::plus) }
          if (stopAtFirstFailure) break
        }
      }
    }
    return ModelBenchResult(done, failures, totals, (System.nanoTime() - started) / 1_000_000, baseSeed, backend, stackName, byInvariant)
  }

  private fun add(sum: ModelRoundStats, s: ModelRoundStats) {
    sum.lives += s.lives
    sum.deaths += s.deaths
    sum.steps += s.steps
    sum.loads += s.loads
    sum.rangedRequests += s.rangedRequests
    sum.serverRestarts += s.serverRestarts
    sum.cuts += s.cuts
    sum.mismatches += s.mismatches
    sum.savedBlocks += s.savedBlocks
    sum.recordedBytes += s.recordedBytes
    sum.shelfPublications += s.shelfPublications
    sum.tampered += s.tampered
    sum.checks += s.checks
    sum.nasOpens += s.nasOpens
    sum.nasReads += s.nasReads
    sum.nasFaultsFired += s.nasFaultsFired
  }
}
