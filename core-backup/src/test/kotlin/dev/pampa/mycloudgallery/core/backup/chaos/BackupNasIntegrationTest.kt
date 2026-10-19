package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.log.LogLevel
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * La prova delle interruzioni del backup contro l'AREA DI PROVA del NAS vero (docs/fase5-progetto.md §5.3, §5.4). Due modi, scelti da
 * `MCG_BACKUP_MODE`:
 *  - `run` (di norma): il runner, `backup.rounds` interruzioni (400 di norma; una prova breve ne usa al massimo 20) con file da 1 MB, da 200 MB e,
 *    se il file delle credenziali ha `backup.bigFileMb`, il video enorme di E-B1;
 *  - `verify`: verifica l'area dal manifesto del telefono (`MCG_BACKUP_MANIFEST`, `MCG_BACKUP_SETTLED`), anche da sola: e' la fine della prova
 *    `tools/chaos/s25.ps1 -Mode backup`.
 *
 * Si salta (SENZA aprire un socket) se manca una di queste: l'interruttore (`MCG_NAS_INTEGRATION=true` o `-DnasIntegration=true`) e il file delle
 * credenziali `%USERPROFILE%\.mycloudgallery\nas-it.properties`. La guardia ([BackupAreaGuard]) e il suo autotest girano prima di ogni connessione:
 * un errore nel file delle credenziali fa FALLIRE la prova; un errore di autenticazione sul NAS la ferma SUBITO, senza nuovi tentativi.
 * Tutto vive sotto `mcg-it/<giro>/` delle share di prova; la pulizia dell'area e' quella di `NasIntegrationSuiteTest` di core-nas (al giro dopo).
 * Nessun nome di file, percorso, share, account o impronta nei rapporti. Si lancia con `tools\chaos\area.ps1 -Mode backup -Stage run|verify`.
 */
class BackupNasIntegrationTest {

  private val mode: String = System.getenv("MCG_BACKUP_MODE")?.lowercase() ?: "run"

  private val runId: String =
    System.getenv("MCG_BACKUP_RUN")?.takeIf { BackupAreaGuard.RUN_ID.matches(it) } ?: BackupAreaGuard.newRunId(ZonedDateTime.now(ZoneOffset.UTC), SecureRandom())

  private fun ready(id: String): BackupAreaGate.Outcome.Ready {
    val outcome = BackupAreaGate.evaluate(id)
    assumeTrue((outcome as? BackupAreaGate.Outcome.Skip)?.reason ?: "pronto", outcome is BackupAreaGate.Outcome.Ready)
    return outcome as BackupAreaGate.Outcome.Ready
  }

  private fun seedOf(): Long = System.getenv("MCG_BACKUP_SEED")?.toLongOrNull() ?: System.nanoTime()

  @Test
  fun runnerContro_lAreaDiProvaDelNasVero(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "run")
    val ready = ready(runId)
    val credentials = ready.credentials
    val interruptions = BackupAreaGate.interruptions(credentials)
    val binding = SmbBackupArea(transport = { SmbjTransport() })
    val factory = if (System.getenv("MCG_BACKUP_STACK")?.lowercase() == "reference") ReferenceStack else ProductionStack
    val started = System.nanoTime()
    val dir = File("build/chaos").also { it.mkdirs() }
    try {
      val target = GuardedTarget(binding.openTarget(credentials, ready.guard), ready.guard)
      // L'autotest della guardia: ancora prima del primo giro, e senza una sola chiamata al NAS.
      val world = target.openWorld(0)
      val self = BackupAreaSelfTest.run(ready.guard, world.nas.reader, world.nas.ops, target.counter)
      world.close()
      assertWithMessage(self.failures.joinToString()).that(self.failures).isEmpty()
      val settings = BackupAreaRunner.Settings(
        interruptions = interruptions,
        baseSeed = seedOf(),
        hugeBytes = BackupAreaGate.hugeBytes(credentials),
        firstRound = System.getenv("MCG_BACKUP_FIRST_ROUND")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        // 5B (P13): `off` (di norma), `with` (il caricamento e poi le versioni, gli scambi, il guardiano e "Libera spazio"), `only` (solo la 5B).
        fiveB = BackupAreaRunner.FiveB.of(System.getenv("MCG_BACKUP_FIVEB")),
      )
      // L'avanzamento, giro per giro, in un file a parte: la prova lunga (ore) si segue senza aspettare il rapporto.
      val progress = File(dir, "backup-area-progress-${runId.take(15)}.txt").also { it.writeText("") }
      // Gli avvisi del codice sotto prova (solo codici e numeri, mai percorsi: regola di GalleryLog), con l'ora dall'inizio.
      val log = File(dir, "backup-area-log-${runId.take(15)}.txt").also { it.writeText("") }
      GalleryLog.install { level, tag, message, _ ->
        if (level >= LogLevel.WARN) synchronized(log) { log.appendText("${(System.nanoTime() - started) / 1_000_000} ms $level $tag $message\n") }
      }
      val result = try {
        BackupAreaRunner.run(target, factory, settings, statesDir = null) { rounds, red, fired ->
          progress.appendText("giri $rounds, rossi $red, interruzioni $fired di $interruptions, ${(System.nanoTime() - started) / 1_000_000_000} s\n")
        }
      } catch (stop: AreaAuthStop) {
        File(dir, "backup-area-report-${runId.take(15)}.txt").writeText("PROVA FERMATA: ${stop.message}\n")
        throw AssertionError(stop.message)
      }
      println(result.summary())
      result.writeReport(File(dir, "backup-area-report-${runId.take(15)}.txt"))
      assertWithMessage("giri con violazioni:\n" + result.failureText()).that(result.failures).isEmpty()
      assertThat(result.stats.totalFired).isAtLeast(interruptions)
    } finally {
      GalleryLog.uninstall()
      binding.close()
    }
  }

  @Test
  fun verificaLAreaDalManifestoDelTelefono(): Unit = runBlocking {
    assumeTrue("modo $mode", mode == "verify")
    val path = System.getenv("MCG_BACKUP_MANIFEST")
    assumeTrue("serve MCG_BACKUP_MANIFEST", path != null)
    val manifest = BackupAreaManifest.read(File(path!!))
    val ready = ready(manifest.runId)
    val binding = SmbBackupArea(transport = { SmbjTransport() })
    try {
      // Solo la lettura, e solo le cartelle del manifesto: Backup/ sotto la destinazione e i temporanei della cartella dell'app.
      val roots = manifest.dest.map { (_, dir) -> dir?.child("Backup") ?: SharePath.of("Backup") } +
        manifest.app.map { (_, dir) -> dir?.child(NasLayout.ROOT.name)?.child("tmp") ?: NasLayout.ROOT.child("tmp") } +
        // 5B: le versioni stanno in `Versioni/` alla radice della share (sola lettura, solo se il manifesto ha modifiche).
        (if (manifest.edits.isNotEmpty()) listOf(SharePath.of("Versioni")) else emptyList())
      val reader = GuardedReader(binding.openReader(ready.credentials, ready.guard), ready.guard, readRoots = roots)
      val settled = System.getenv("MCG_BACKUP_SETTLED")?.lowercase() != "false"
      val verification = BackupAreaVerifier.verify(reader, manifest, settled)
      println(verification.text())
      File("build/chaos/backup-verify-${manifest.runId.take(15)}.txt").also { it.parentFile?.mkdirs() }.writeText(verification.text())
      assertWithMessage(verification.text()).that(verification.violations).isEmpty()
    } finally {
      binding.close()
    }
  }
}
