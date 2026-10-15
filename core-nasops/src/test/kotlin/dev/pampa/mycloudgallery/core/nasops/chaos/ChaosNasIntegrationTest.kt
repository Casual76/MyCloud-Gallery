package dev.pampa.mycloudgallery.core.nasops.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.log.LogLevel
import java.io.File
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * La prova delle interruzioni contro l'AREA DI PROVA del NAS vero (docs/fase4-progetto.md §5.4, §5.5). Tre modi, scelti da `MCG_CHAOS_MODE`:
 *  - `run` (di norma): il runner, `chaos.rounds` interruzioni (400 di norma; una prova breve ne usa al massimo 20) con file da 1 MB e da
 *    `chaos.bigFileMb` MB;
 *  - `seed`: riempie `mcg-it/<giro>/r0/sorgente/` con gli originali e scrive il manifesto (per la prova sul telefono, `tools/chaos/s25.ps1`);
 *  - `verify`: verifica l'area dal manifesto, anche da sola (`MCG_CHAOS_MANIFEST`, `MCG_CHAOS_SETTLED`).
 *
 * Si salta (SENZA aprire un socket) se manca una di queste: l'interruttore (`MCG_NAS_INTEGRATION=true` o `-DnasIntegration=true`), il file delle
 * credenziali `%USERPROFILE%\.mycloudgallery\nas-it.properties`, l'aggancio al NAS vero (`SmbChaosBinding`, di P12). La guardia
 * ([ChaosAreaGuard]) e il suo autotest girano prima di ogni connessione: un errore nel file delle credenziali fa FALLIRE la prova.
 * Tutto vive sotto `mcg-it/<giro>/` delle share di prova; la pulizia dell'area e' quella di `NasIntegrationSuiteTest` di core-nas (al giro dopo).
 */
class ChaosNasIntegrationTest {

  private val mode: String = System.getenv("MCG_CHAOS_MODE")?.lowercase() ?: "run"

  private val runId: String =
    System.getenv("MCG_CHAOS_RUN")?.takeIf { ChaosAreaGuard.RUN_ID.matches(it) } ?: newRunId()

  private fun newRunId(): String = ChaosAreaGuard.newRunId(ZonedDateTime.now(ZoneOffset.UTC), SecureRandom())

  private fun ready(id: String): ChaosNasGate.Outcome.Ready {
    val outcome = ChaosNasGate.evaluate(id)
    assumeTrue((outcome as? ChaosNasGate.Outcome.Skip)?.reason ?: "pronto", outcome is ChaosNasGate.Outcome.Ready)
    return outcome as ChaosNasGate.Outcome.Ready
  }

  private fun seedOf(): Long = System.getenv("MCG_CHAOS_SEED")?.toLongOrNull() ?: System.nanoTime()

  @Test
  fun runnerContro_lAreaDiProvaDelNasVero(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "run")
    val ready = ready(runId)
    val credentials = ready.credentials
    val interruptions = ChaosNasGate.interruptions(credentials)
    val factory = ChaosStacks.selected()
    try {
      val target = GuardedTarget(ready.binding.openTarget(credentials, ready.guard), ready.guard)
      // L'autotest della guardia: ancora prima del primo giro, e senza una sola chiamata al NAS.
      val world = target.openWorld(0)
      val self = ChaosAreaSelfTest.run(ready.guard, world.reader, world.fileOps, target.counter)
      world.close()
      assertWithMessage(self.failures.joinToString()).that(self.failures).isEmpty()
      val settings = ChaosAreaRunner.Settings(
        interruptions = interruptions,
        baseSeed = seedOf(),
        bigBytes = ChaosNasGate.bigFileBytes(credentials),
        firstRound = System.getenv("MCG_CHAOS_FIRST_ROUND")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
      )
      // L'avanzamento, giro per giro, in un file a parte: la prova lunga (100 minuti) si segue senza aspettare il rapporto.
      val progress = File("build/chaos/area-progress-${runId.take(15)}.txt").also { it.parentFile?.mkdirs(); it.writeText("") }
      val started = System.nanoTime()
      // Gli avvisi del codice sotto prova (solo codici e numeri, mai percorsi: regola di GalleryLog), con l'ora dall'inizio.
      val log = File("build/chaos/area-log-${runId.take(15)}.txt").also { it.writeText("") }
      GalleryLog.install { level, tag, message, _ ->
        if (level >= LogLevel.WARN) synchronized(log) { log.appendText("${(System.nanoTime() - started) / 1_000_000} ms $level $tag $message\n") }
      }
      val result = ChaosAreaRunner.run(target, factory, settings) { rounds, red, fired ->
        progress.appendText("giri $rounds, rossi $red, interruzioni $fired di $interruptions, ${(System.nanoTime() - started) / 1_000_000_000} s\n")
      }
      println(result.summary())
      result.writeReport(File("build/chaos/area-report-${runId.take(15)}.txt"))
      assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
      assertThat(result.stats.totalFired).isAtLeast(interruptions)
    } finally {
      GalleryLog.uninstall()
      ready.binding.close()
    }
  }

  @Test
  fun seminaLAreaPerLaProvaSulTelefono(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "seed")
    val ready = ready(runId)
    try {
      val target = GuardedTarget(ready.binding.openTarget(ready.credentials, ready.guard), ready.guard)
      val world = target.openWorld(0)
      val files = System.getenv("MCG_CHAOS_FILES")?.toIntOrNull() ?: 300
      val manifest = ChaosAreaSeeder.seed(
        world = world,
        runId = runId,
        mediaDir = ready.guard.roundRoot(0),
        files = files,
        random = Random(seedOf()),
        mediumBytes = 1 shl 20,
        bigBytes = ChaosNasGate.bigFileBytes(ready.credentials),
        bigPercent = 2,
      )
      val out = File(System.getenv("MCG_CHAOS_MANIFEST") ?: "build/chaos/manifest-${runId.take(15)}.txt")
      manifest.write(out)
      world.close()
      println("area seminata: giro $runId, ${manifest.originals.size} originali; manifesto ${out.absolutePath}")
    } finally {
      ready.binding.close()
    }
  }

  @Test
  fun verificaLAreaDalManifesto(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "verify")
    val path = System.getenv("MCG_CHAOS_MANIFEST")
    assumeTrue("serve MCG_CHAOS_MANIFEST", path != null)
    val manifest = ChaosManifest.read(File(path!!))
    val ready = ready(manifest.runId)
    try {
      val reader = GuardedReader(ready.binding.openReader(ready.credentials, ready.guard), ready.guard)
      val settled = System.getenv("MCG_CHAOS_SETTLED")?.lowercase() != "false"
      val verification = ChaosAreaVerifier.verify(reader, manifest, settled)
      println(verification.text())
      File("build/chaos/verify-${manifest.runId.take(15)}.txt").also { it.parentFile?.mkdirs() }.writeText(verification.text())
      assertWithMessage(verification.text()).that(verification.violations).isEmpty()
    } finally {
      ready.binding.close()
    }
  }
}
