package dev.pampa.mycloudgallery.release

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La simulazione del passo non scrive e non esce dalla build debug (P10, §2.10), guardato sui sorgenti:
 *  1. il codice di `release/` (le fonti e il montaggio) non nomina nessuna API di scrittura del NAS ne' del disco;
 *  2. nessun file di `src/main` fuori da `release/`, e nessuno di `src/release`, nomina la simulazione: la build di release non la raggiunge e R8 la toglie
 *     (`tools/check.ps1` lo controlla anche sul dex).
 */
class StepSimulationReadOnlyTest {

  private fun kotlinFiles(dir: String): List<File> = File(dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  private fun code(file: File): List<String> =
    file.readLines().filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/**") }

  @Test
  fun ilCodiceDiReleaseNonNominaScritture() {
    // Fase 10 (P13): nella stessa cartella c'e' anche il manifest firmato (`ReleaseGraph`, che scrive il suo stato locale): la regola vale per la simulazione.
    val files = kotlinFiles("src/main/kotlin/dev/pampa/mycloudgallery/release").filter { it.name.startsWith("StepSimulation") }
    assertThat(files.map { it.name }).containsAtLeast("StepSimulationSources.kt", "StepSimulationWiring.kt")
    val forbidden = Regex(
      // I nomi delle primitive di scrittura si scrivono a pezzi: le barriere di core-common (Phase4 e Phase5) li cercano in ogni sorgente, anche nei test.
      """\b(publishExclusive|NasOperations|Nas""" + """FileOps|NasTrash|Nas""" + """BackupOps|Destructive""" + """NasAccess|mkdirs|rmdir|deleteIfUnchanged|trashMove|renameTo|""" +
        """writeBytes|writeText|FileOutputStream|outbox|flushOutbox|setWritesStopped|startBackup)\b""",
    )
    for (file in files) {
      for (line in code(file)) assertWithMessage("${file.name}: $line").that(forbidden.containsMatchIn(line)).isFalse()
    }
  }

  @Test
  fun lePorteDelMontaggioSonoSoloLetture() {
    val wiring = File("src/main/kotlin/dev/pampa/mycloudgallery/release/StepSimulationWiring.kt").readText()
    // Le uniche chiamate ai grafi: il riepilogo del backup, il conteggio del catalogo, lo spazio, la stima della replica, "Persone attiva qui".
    for (call in listOf("summary()", "countPresentNas", "organizer.space", "quality.value", "activeHere()", "coreIfReady()")) {
      assertThat(wiring).contains(call)
    }
    assertThat(wiring).doesNotContain("syncIndex")
    assertThat(wiring).doesNotContain("prepare(")
  }

  @Test
  fun laReleaseNonRaggiungeLaSimulazione() {
    val outside = kotlinFiles("src/main/kotlin").filterNot { it.invariantSeparatorsPath.contains("/dev/pampa/mycloudgallery/release/") } +
      kotlinFiles("src/release")
    assertThat(outside).isNotEmpty()
    for (file in outside) {
      assertWithMessage("${file.invariantSeparatorsPath} nomina la simulazione del passo: la release la raggiungerebbe")
        .that(file.readText().contains("StepSimulation") || file.readText().contains("StepNasSpace") || file.readText().contains("LiveStepSources")).isFalse()
    }
  }
}
