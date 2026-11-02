package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * Prova sul campo di Fase 10 (release candidata sull'S25): in 85 minuti la release ha scritto 833 stack trace su `System.err`. In release i log hanno solo numeri e codici
 * (C21, S6): il logger della galleria (`GalleryLog`) non ha un sink e non scrive niente; e nessun sorgente di produzione scrive per conto suo. Il codice nostro non chiama
 * `printStackTrace()`, non scrive su `System.out` o `System.err` e non usa `print` o `println` (su Android finiscono in logcat con i tag `System.out` e `System.err`): un
 * file nuovo che lo fa fa fallire questo test. Gli stack trace che arrivavano dal codice nativo (un'eccezione uscita da `MediaDataSource.readAt`) li chiude
 * `QuietMediaDataSource` (`QuietMediaDataSourceTest` in core-metadata).
 *
 * Unica eccezione, nominata: `BuildPlacesDataset`, uno strumento a riga di comando per la riga di sviluppo (non gira mai nell'app).
 */
class Phase10LogsArchitectureTest {

  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle").isFile && File(it, "core-common").isDirectory }
    ?: error("Non trovo la radice del progetto (settings.gradle) sopra ${File(".").absolutePath}")

  private val modules: List<String> = File(root, "settings.gradle").readLines()
    .mapNotNull { INCLUDE.find(it.substringBefore("//"))?.groupValues?.get(1) }

  private fun sources(): Sequence<File> = modules.asSequence().flatMap { module ->
    SETS.asSequence().map { File(root, "$module/src/$it") }.filter { it.isDirectory }
  }.flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }

  private fun rel(file: File): String = file.relativeTo(root).invariantSeparatorsPath

  @Test
  fun nessunSorgenteDiProduzioneScriveStackTraceOSuiFlussiDiSistema() {
    val offenders = sources().filter { rel(it) !in ALLOWED }.flatMap { file ->
      NoNetworkSources.stripComments(file.readText()).lineSequence().mapIndexedNotNull { i, line ->
        WRITES.find(line)?.let { "${rel(file)}:${i + 1}: ${it.value}" }
      }
    }.toList()
    assertWithMessage(
      "in release i log hanno solo numeri e codici (C21): niente printStackTrace(), System.out, System.err, print, println; usare GalleryLog (che in release non scrive):\n" +
        offenders.joinToString("\n"),
    ).that(offenders).isEmpty()
  }

  @Test
  fun ilControlloVedeIFile_EMordePerDavvero() {
    assertThat(modules).containsAtLeast("app", "core-nasmedia", "core-metadata", "core-prepare", "feature-viewer")
    assertThat(sources().count()).isAtLeast(1_000)
    // l'eccezione nominata esiste ed e' proprio una che scrive: senza, il test non proverebbe niente
    val tool = NoNetworkSources.stripComments(File(root, "core-places/src/main/kotlin/dev/pampa/mycloudgallery/core/places/tool/BuildPlacesDataset.kt").readText())
    assertThat(WRITES.containsMatchIn(tool)).isTrue()
    for (bad in listOf(
      "e.printStackTrace()",
      "throwable.printStackTrace(System.err)",
      "System.err.println(\"x\")",
      "System . out . println(x)",
      "println(\"debug\")",
      "  print(value)",
      "kotlin.io.println(x)",
    )) {
      assertWithMessage("non vieta: $bad").that(WRITES.containsMatchIn(bad)).isTrue()
    }
    for (good in listOf(
      "GalleryLog.e(TAG, e) { \"tappa non riuscita\" }",
      "val footprint = Footprint(1, 2)",
      "fun fingerprint(size: Long): String",
      "writer.println(line)",
      "printer.print(value)",
      "val sprint = 3",
      "fun Preview.printable() = this",
    )) {
      assertWithMessage("vieta per errore: $good").that(WRITES.containsMatchIn(good)).isFalse()
    }
  }

  private companion object {
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")
    val SETS = listOf("main", "debug", "release", "benchmarkRelease")
    val ALLOWED = setOf("core-places/src/main/kotlin/dev/pampa/mycloudgallery/core/places/tool/BuildPlacesDataset.kt")

    // I nomi sono scritti in pezzi dove servirebbe: questo file e' un test e non si scansiona, ma resta leggibile.
    val WRITES = Regex(
      """\bprintStack""" + """Trace\b|\bSystem\s*\.\s*(out|err)\b|(?<![.\w])println\s*\(|(?<![.\w])print\s*\(|\bkotlin\.io\.print(ln)?\s*\(""",
    )
  }
}
