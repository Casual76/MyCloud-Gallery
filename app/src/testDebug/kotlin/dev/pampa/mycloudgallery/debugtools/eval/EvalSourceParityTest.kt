package dev.pampa.mycloudgallery.debugtools.eval

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Le metriche e la calibrazione stanno in **due copie** (P9): in `core-search/src/test/.../eval/` (provate con dati sintetici) e in
 * `app/src/debug/.../debugtools/eval/` (lo strumento sul telefono). Il test di un modulo non si vede dall'altro, e P9 non puo' toccare i file di build
 * per condividerle: questo test garantisce che le copie restino **uguali** (cambia solo la riga `package`). Chi modifica una copia deve modificare l'altra.
 */
class EvalSourceParityTest {

  private val shared = listOf("EvalMetrics", "EvalCalibration", "EvalSweep", "EvalOrder", "EvalDocuments", "EvalLabels", "EvalOcr", "EvalScoring")

  /** La cartella `core-search/src/test/.../eval/`, trovata dalla radice del repository (si parte da `app/`). */
  private fun original(): File = File(repositoryRoot(), "core-search/src/test/kotlin/dev/pampa/mycloudgallery/core/search/eval")

  private fun copy(): File = File(repositoryRoot(), "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval")

  private fun repositoryRoot(): File {
    var d: File? = File("").absoluteFile
    while (d != null) {
      if (File(d, "settings.gradle").isFile) return d
      d = d.parentFile
    }
    error("Radice del repository non trovata.")
  }

  private fun body(file: File): String =
    file.readText(Charsets.UTF_8).replace("\r\n", "\n").lineSequence().filterNot { it.startsWith("package ") }.joinToString("\n").trimEnd()

  @Test
  fun leDueCopieDelleMetricheSonoUguali() {
    for (name in shared) {
      val a = File(original(), "$name.kt")
      val b = File(copy(), "$name.kt")
      assertThat(a.isFile).isTrue()
      assertThat(b.isFile).isTrue()
      assertThat(body(b)).isEqualTo(body(a))
    }
  }

  @Test
  fun lePackageSonoQuelleGiuste() {
    for (name in shared) {
      assertThat(File(original(), "$name.kt").readLines().first { it.startsWith("package ") }).endsWith("core.search.eval")
      assertThat(File(copy(), "$name.kt").readLines().first { it.startsWith("package ") }).endsWith("debugtools.eval")
    }
  }

  @Test
  fun nessunFileCondivisoESfuggitoAlControllo() {
    // Ogni file di `core-search/.../eval/` che non e' un test ne' un dato di prova deve stare nell'elenco (e quindi nella copia).
    val candidates = original().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().map { it.name.removeSuffix(".kt") }
      .filterNot { it.endsWith("Test") || it == "EvalTestData" }
    assertThat(candidates).containsExactlyElementsIn(shared)
  }

  @Test
  fun lAppNonHaFileDiEtichetteNeQueryNelleRisorse() {
    // Lo strumento non porta con se' dati: nessun file `.mcgeval` e nessuna cartella `eval-privato` nel codice di debug.
    val files = File(repositoryRoot(), "app/src").walkTopDown().filter { it.isFile }.toList()
    assertThat(files.filter { it.name.endsWith(".mcgeval") || it.name.startsWith("etichette") }).isEmpty()
    assertThat(files.any { it.path.replace('\\', '/').contains("/eval-privato/") }).isFalse()
  }
}
