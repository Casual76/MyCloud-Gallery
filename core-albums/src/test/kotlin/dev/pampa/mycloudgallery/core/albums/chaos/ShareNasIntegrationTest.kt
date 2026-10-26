package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.log.LogLevel
import java.io.File
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * IL BANCO DELLA CONDIVISIONE DAL PC CONTRO IL NAS VERO DI PROVA (docs/fase7-progetto.md §5.5 e §5.6 F80, riga P5 del §4.4; come F16-PC,
 * `ChaosNasIntegrationTest` della 4B): il motore vero di core-nasops sulla JVM del PC contro `test` → `test-comune`, con la porta di completamento,
 * il processo "ucciso" e la connessione tagliata a tempi casuali (ogni chiamata SMB del motore e ogni salvataggio dei suoi diari, prima o dopo),
 * il verificatore che rilegge tutto dal NAS. Si lancia con `tools\chaos\share-pc\share-pc.ps1`.
 *
 * Si salta (SENZA aprire un socket) se manca una di queste: l'interruttore (`MCG_NAS_INTEGRATION=true` o `-DnasIntegration=true`) o il file delle credenziali
 * `%USERPROFILE%\.mycloudgallery\nas-it.properties`, che legge solo questo codice, in memoria. La guardia ([ShareAreaGuard]) e il suo autotest
 * ([ShareAreaSelfTest]) girano prima di ogni connessione: un errore nel file delle credenziali FA FALLIRE la prova. Tutto vive sotto
 * `mcg-it/<giro>/r<N>/` delle share `test` e `test-comune`; a fine prova la cartella del giro si toglie e il rapporto dice cosa e' rimasto. Un errore di
 * autenticazione ferma tutto subito, senza nuovi tentativi (il NAS blocca l'account).
 *
 * Modi (`MCG_SHARE_MODE`): `run` (di norma) e `clean` (toglie la cartella di un giro rimasto, `MCG_SHARE_RUN=<giro>`, dopo una prova interrotta).
 * Variabili: `MCG_SHARE_INTERRUPTIONS` (500), `MCG_SHARE_SEED`, `MCG_SHARE_FIRST_ROUND`, `MCG_SHARE_RUN`.
 */
class ShareNasIntegrationTest {

  private val mode: String = System.getenv("MCG_SHARE_MODE")?.lowercase() ?: "run"

  private val runId: String =
    System.getenv("MCG_SHARE_RUN")?.takeIf { ShareAreaGuard.RUN_ID.matches(it) } ?: ShareAreaGuard.newRunId(ZonedDateTime.now(ZoneOffset.UTC), SecureRandom())

  private fun ready(): ShareAreaGate.Outcome.Ready {
    val outcome = ShareAreaGate.evaluate(runId)
    assumeTrue((outcome as? ShareAreaGate.Outcome.Skip)?.reason ?: "pronto", outcome is ShareAreaGate.Outcome.Ready)
    return outcome as ShareAreaGate.Outcome.Ready
  }

  private fun number(name: String): Long? = System.getenv(name)?.toLongOrNull()

  @Test
  fun bancoDellaCondivisioneContro_lAreaDiProvaDelNasVero(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "run")
    val ready = ready()
    val interruptions = (number("MCG_SHARE_INTERRUPTIONS") ?: DEFAULT_INTERRUPTIONS).toInt().coerceIn(1, 5_000)
    val seed = number("MCG_SHARE_SEED") ?: System.nanoTime()
    val first = (number("MCG_SHARE_FIRST_ROUND") ?: 0).toInt().coerceAtLeast(0)
    val tag = runId.take(15)
    File("build/chaos").mkdirs()
    // L'autotest della guardia: ancora prima della prima connessione, e con zero chiamate al vero.
    val self = ShareAreaSelfTest.run(ready.guard)
    assertWithMessage("autotest della guardia:\n" + self.failures.joinToString("\n")).that(self.failures).isEmpty()
    println("autotest della guardia: ${self.cases} casi, tutti rifiutati, zero chiamate al vero")

    val area = ShareAreas.real(ready.credentials, ready.guard, first)
    val progress = File("build/chaos/share-area-progress-$tag.txt").also { it.writeText("") }
    val started = System.nanoTime()
    // Gli avvisi del codice sotto prova (solo codici e numeri, mai percorsi: regola di GalleryLog), con l'ora dall'inizio.
    val log = File("build/chaos/share-area-log-$tag.txt").also { it.writeText("") }
    GalleryLog.install { level, logTag, message, _ ->
      if (level >= LogLevel.WARN) synchronized(log) { log.appendText("${(System.nanoTime() - started) / 1_000_000} ms $level $logTag $message\n") }
    }
    var result: ShareChaosResult? = null
    var cleanup: CleanupReport? = null
    try {
      result = ShareAreaRunner.run(area, ShareAreaRunner.Settings(interruptions, seed, first)) { rounds, red, fired ->
        progress.appendText("giri $rounds, rossi $red, interruzioni $fired di $interruptions, ${(System.nanoTime() - started) / 1_000_000_000} s\n")
      }
    } finally {
      GalleryLog.uninstall()
      // La pulizia, anche se la prova e' fallita: la cartella del giro non deve restare. Dopo un login rifiutato niente rete, nemmeno per pulire.
      cleanup = if (area.latch.failed) null else runCatching { area.finish() }.getOrNull()
    }
    val summary = buildString {
      append(result.summary())
      appendLine("--- area di prova ---")
      appendLine("giro del NAS: $tag (cartella del giro sotto mcg-it/, una radice per giro del banco)")
      appendLine("chiamate arrivate al NAS attraverso la guardia: ${area.counter.calls.get()}; tentativi di login rifiutati: ${if (area.latch.failed) "SI, fermo" else "nessuno"}")
      val perRound = synchronized(area.cleanups) { area.cleanups.toList() }
      appendLine("pulizia dei mondi dei giri: ${perRound.size} mondi, ${perRound.count { it.clean }} puliti; tolti ${perRound.sumOf { it.files }} file e ${perRound.sumOf { it.folders }} cartelle")
      perRound.filterNot { it.clean }.take(10).forEach { appendLine("  mondo non pulito: ${it.text()}") }
      appendLine("pulizia finale della cartella del giro: ${cleanup?.text() ?: "NON FATTA (login rifiutato o errore)"}")
    }
    println(summary)
    File("build/chaos/share-area-report-$tag.txt").writeText(summary + if (result.failures.isNotEmpty()) "\n" + result.failureText(60) else "")
    assertWithMessage("fermato: ${result.stoppedBecause}").that(result.stoppedBecause).isNull()
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stats.totalFired).isAtLeast(interruptions)
    assertWithMessage("cartella del giro non pulita").that(cleanup?.clean).isTrue()
  }

  @Test
  fun puliziaDellaCartellaDiUnGiroRimasto(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "clean")
    assumeTrue("serve MCG_SHARE_RUN", System.getenv("MCG_SHARE_RUN")?.let { ShareAreaGuard.RUN_ID.matches(it) } == true)
    val ready = ready()
    val self = ShareAreaSelfTest.run(ready.guard)
    assertWithMessage(self.failures.joinToString("\n")).that(self.failures).isEmpty()
    val area = ShareAreas.real(ready.credentials, ready.guard)
    val report = area.finish()
    println("pulizia del giro $runId: ${report.text()}")
    assertThat(report.clean).isTrue()
  }

  private companion object {
    const val DEFAULT_INTERRUPTIONS = 500L
  }
}
