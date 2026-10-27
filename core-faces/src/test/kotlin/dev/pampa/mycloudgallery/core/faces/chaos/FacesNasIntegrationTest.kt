package dev.pampa.mycloudgallery.core.faces.chaos

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
 * IL BANCO DEI VOLTI DAL PC CONTRO IL NAS VERO DI PROVA (docs/fase8-progetto.md §5.5 e §5.6 F103, riga P12 del §4.4; come F16-PC e F80): il calcolo, la
 * pubblicazione, la replica e "Disattiva e cancella" veri sulla JVM del PC contro la share di prova `test`, con la sessione SMB di produzione, le primitive
 * vere e il vero `NasFacesEraser`; il processo "ucciso" e la connessione tagliata a tempi casuali (ogni richiesta SMB di ogni dispositivo e ogni salvataggio
 * durevole, prima o dopo); il verificatore che rilegge tutto dal NAS. Si lancia con `tools\chaos\faces-pc\faces-pc.ps1`.
 *
 * Si salta (SENZA aprire un socket) se manca una di queste: l'interruttore (`MCG_NAS_INTEGRATION=true` o `-DnasIntegration=true`) o il file delle credenziali
 * `%USERPROFILE%\.mycloudgallery\nas-it.properties`, che legge solo questo codice, in memoria. La guardia ([FacesAreaGuard]) e il suo autotest
 * ([FacesAreaSelfTest]) girano prima di ogni connessione: un errore nel file delle credenziali FA FALLIRE la prova. Tutto vive sotto `mcg-it/<giro>/r<N>/` della
 * share `test` (l'area comune `test-comune` non si scrive mai); a fine prova la cartella del giro si toglie e il rapporto dice cosa e' rimasto. Un errore di
 * autenticazione ferma tutto subito, senza nuovi tentativi (il NAS blocca l'account).
 *
 * Modi (`MCG_FACES_MODE`): `run` (di norma), `clean` (toglie la cartella di un giro rimasto, `MCG_FACES_RUN=<giro>`, dopo una prova interrotta) e `verify`
 * (verifica in SOLA LETTURA i dati dei volti scritti dal telefono, `MCG_FACES_MANIFEST=<manifesto>`, `MCG_FACES_PHASE=computed|erased`).
 * Variabili: `MCG_FACES_INTERRUPTIONS` (400), `MCG_FACES_SEED`, `MCG_FACES_FIRST_ROUND`, `MCG_FACES_RUN`.
 */
class FacesNasIntegrationTest {

  private val mode: String = System.getenv("MCG_FACES_MODE")?.lowercase() ?: "run"

  private val runId: String =
    System.getenv("MCG_FACES_RUN")?.takeIf { FacesAreaGuard.RUN_ID.matches(it) } ?: FacesAreaGuard.newRunId(ZonedDateTime.now(ZoneOffset.UTC), SecureRandom())

  private fun ready(forRun: String = runId): FacesAreaGate.Outcome.Ready {
    val outcome = FacesAreaGate.evaluate(forRun)
    assumeTrue((outcome as? FacesAreaGate.Outcome.Skip)?.reason ?: "pronto", outcome is FacesAreaGate.Outcome.Ready)
    return outcome as FacesAreaGate.Outcome.Ready
  }

  private fun number(name: String): Long? = System.getenv(name)?.toLongOrNull()

  @Test
  fun bancoDeiVoltiContro_lAreaDiProvaDelNasVero(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "run")
    val ready = ready()
    val interruptions = (number("MCG_FACES_INTERRUPTIONS") ?: DEFAULT_INTERRUPTIONS).toInt().coerceIn(1, 5_000)
    val seed = number("MCG_FACES_SEED") ?: System.nanoTime()
    val first = (number("MCG_FACES_FIRST_ROUND") ?: 0).toInt().coerceAtLeast(0)
    val tag = runId.take(15)
    File("build/chaos").mkdirs()
    // L'autotest della guardia: ancora prima della prima connessione, e con zero chiamate al vero.
    val self = FacesAreaSelfTest.run(ready.guard)
    assertWithMessage("autotest della guardia:\n" + self.failures.joinToString("\n")).that(self.failures).isEmpty()
    println("autotest della guardia: ${self.cases} casi, tutti rifiutati, zero chiamate al vero")

    val area = FacesAreas.real(ready.credentials, ready.guard, first)
    val progress = File("build/chaos/faces-area-progress-$tag.txt").also { it.writeText("") }
    val started = System.nanoTime()
    // Gli avvisi del codice sotto prova (solo codici e numeri, mai percorsi: regola di GalleryLog), con l'ora dall'inizio.
    val log = File("build/chaos/faces-area-log-$tag.txt").also { it.writeText("") }
    GalleryLog.install { level, logTag, message, _ ->
      if (level >= LogLevel.WARN) synchronized(log) { log.appendText("${(System.nanoTime() - started) / 1_000_000} ms $level $logTag $message\n") }
    }
    var result: ChaosResult? = null
    var cleanup: FacesCleanupReport? = null
    try {
      result = FacesAreaRunner.run(area, FacesAreaRunner.Settings(interruptions, seed, first)) { rounds, red, fired ->
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
      appendLine("giro del NAS: $tag (cartella del giro sotto mcg-it/, una radice per giro del banco, solo nella share privata di prova)")
      appendLine("chiamate arrivate al NAS attraverso la guardia: ${area.counter.calls.get()}; tentativi di login rifiutati: ${if (area.latch.failed) "SI, fermo" else "nessuno"}")
      val perRound = synchronized(area.cleanups) { area.cleanups.toList() }
      appendLine("pulizia dei mondi dei giri: ${perRound.size} mondi, ${perRound.count { it.clean }} puliti; tolti ${perRound.sumOf { it.files }} file e ${perRound.sumOf { it.folders }} cartelle")
      perRound.filterNot { it.clean }.take(10).forEach { appendLine("  mondo non pulito: ${it.text()}") }
      appendLine("pulizia finale della cartella del giro: ${cleanup?.text() ?: "NON FATTA (login rifiutato o errore)"}")
    }
    println(summary)
    File("build/chaos/faces-area-report-$tag.txt").writeText(summary + if (result.failures.isNotEmpty()) "\n" + result.failureText(60) else "")
    assertWithMessage("fermato: ${result.stoppedBecause}").that(result.stoppedBecause).isNull()
    assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stats.totalFired).isAtLeast(interruptions)
    assertWithMessage("cartella del giro non pulita").that(cleanup?.clean).isTrue()
  }

  @Test
  fun puliziaDellaCartellaDiUnGiroRimasto(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "clean")
    assumeTrue("serve MCG_FACES_RUN", System.getenv("MCG_FACES_RUN")?.let { FacesAreaGuard.RUN_ID.matches(it) } == true)
    val ready = ready()
    val self = FacesAreaSelfTest.run(ready.guard)
    assertWithMessage(self.failures.joinToString("\n")).that(self.failures).isEmpty()
    val area = FacesAreas.real(ready.credentials, ready.guard)
    val report = area.finish()
    println("pulizia del giro $runId: ${report.text()}")
    assertThat(report.clean).isTrue()
  }

  /**
   * I dati dei volti scritti dal telefono (`s25.ps1 -Mode faces`): le due share di prova si leggono in SOLA LETTURA, solo la cartella del backup del manifesto e
   * `.mycloudgallery/{faces,tmp}`; `MCG_FACES_PHASE=computed|erased` dice cosa ci si aspetta.
   */
  @Test
  fun verificaIDatiDeiVoltiDelTelefonoDalManifesto(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "verify")
    val path = System.getenv("MCG_FACES_MANIFEST")
    assumeTrue("serve MCG_FACES_MANIFEST", path != null)
    val manifest = FacesManifest.read(File(path!!))
    val phase = System.getenv("MCG_FACES_PHASE")?.lowercase() ?: FacesManifestVerifier.COMPUTED
    val ready = ready(manifest.runId)
    val self = FacesAreaSelfTest.run(ready.guard)
    assertWithMessage(self.failures.joinToString("\n")).that(self.failures).isEmpty()
    val link = FacesAreas.realLink(ready.credentials, ready.guard)
    val latch = FacesAuthLatch()
    val connection = FacesConnection(ready.credentials.user, ready.credentials.password.copyOf(), ready.credentials.host, link.guid, link.binding)
    val area = FacesReadOnlyArea(FacesReadOnlyTransport(link.transport, ready.guard, FacesManifestVerifier.readable(manifest), latch), connection, latch)
    try {
      area.open()
      val verification = FacesManifestVerifier.verify(area, manifest, phase)
      println(verification.text())
      File("build/chaos").mkdirs()
      File("build/chaos/faces-verify-report-${manifest.runId.take(15)}.txt").writeText(verification.text())
      assertWithMessage(verification.text()).that(verification.violations).isEmpty()
    } finally {
      area.close()
    }
  }

  private companion object {
    const val DEFAULT_INTERRUPTIONS = 400L
  }
}
