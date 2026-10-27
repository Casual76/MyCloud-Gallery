package dev.pampa.mycloudgallery.debugtools.eval.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Le metriche e la regola delle soglie stanno in **due copie** (P11): in `core-faces/src/test/.../eval/` (provate con storie sintetiche con errori noti) e in
 * `app/src/debug/.../debugtools/eval/faces/` (lo strumento sul telefono). Il test di un modulo non si vede dall'altro, e P11 non puo' toccare i file di build per
 * condividerle: questo test garantisce che le copie restino **uguali** (cambia solo la riga `package`). Chi modifica una copia deve modificare l'altra.
 *
 * E poi le guardie sullo strumento: nessun dato dei volti veri nelle risorse, nessuna scrittura fuori da `StateFiles`, niente log, niente rete, niente di `core-faces`
 * (la porta e' sua), niente nei sorgenti di release.
 */
class FacesEvalSourceParityTest {

  private val shared = listOf("FacesEvalLabels", "FacesEvalSampling", "FacesEvalMetrics", "FacesEvalThresholds")

  private fun repositoryRoot(): File {
    var d: File? = File("").absoluteFile
    while (d != null) {
      if (File(d, "settings.gradle").isFile) return d
      d = d.parentFile
    }
    error("Radice del repository non trovata.")
  }

  private fun original(): File = File(repositoryRoot(), "core-faces/src/test/kotlin/dev/pampa/mycloudgallery/core/faces/eval")

  private fun copy(): File = File(repositoryRoot(), "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/faces")

  private fun body(file: File): String =
    file.readText(Charsets.UTF_8).replace("\r\n", "\n").lineSequence().filterNot { it.startsWith("package ") }.joinToString("\n").trimEnd()

  /** Il codice di un file senza commenti e senza righe di commento di documentazione. */
  private fun code(file: File): List<String> =
    file.readLines(Charsets.UTF_8).filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }

  private fun tool(): List<File> = copy().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()

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
      assertThat(File(original(), "$name.kt").readLines().first { it.startsWith("package ") }).endsWith("core.faces.eval")
      assertThat(File(copy(), "$name.kt").readLines().first { it.startsWith("package ") }).endsWith("debugtools.eval.faces")
    }
  }

  @Test
  fun nessunFileCondivisoESfuggitoAlControllo() {
    // Ogni file di `core-faces/.../eval/` che non e' un test ne' un banco di prova deve stare nell'elenco (e quindi nella copia).
    val candidates = original().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().map { it.name.removeSuffix(".kt") }
      .filterNot { it.endsWith("Test") || it.endsWith("Kit") }
    assertThat(candidates).containsExactlyElementsIn(shared)
  }

  @Test
  fun lAppNonHaFileDiEtichetteNeImmaginiNeiSorgentiDelloStrumento() {
    // Lo strumento non porta con se' dati: nessun file `.mcgfac`, nessun `persone-veri*`, nessuna cartella `eval-volti-privato`, nessuna immagine di volti.
    val files = File(repositoryRoot(), "app/src").walkTopDown().filter { it.isFile }.toList()
    assertThat(files.filter { it.name.endsWith(".mcgfac") || it.name.startsWith("persone-veri") }).isEmpty()
    assertThat(files.any { it.path.replace('\\', '/').contains("/eval-volti-privato/") }).isFalse()
    val images = listOf("png", "jpg", "jpeg", "webp", "heic", "bmp", "gif")
    val inTool = files.filter { f -> f.path.replace('\\', '/').contains("/eval/faces/") && f.extension.lowercase() in images }
    assertThat(inTool).isEmpty()
  }

  @Test
  fun loStrumentoNonScriveFuoriDaStateFilesNonLoggaENonUsaLaRete() {
    assertThat(tool()).isNotEmpty()
    for (file in tool()) {
      val lines = code(file)
      // Mai un log (ne' un id ne' un posto passano da qui), mai la rete, mai le porte di `core-faces` (la porta e' dello strumento: P13 la lega).
      for (forbidden in listOf("GalleryLog", "android.util.Log", "Log.", "HttpURLConnection", "URL(", "java.net", "okhttp", "SmbNas", "NasFileSystem", "core.faces.")) {
        assertWithFile(file, forbidden, lines.filter { it.contains(forbidden) })
      }
      // Mai i contratti dei nascosti: li passa chi lega la porta, e la fonte e' in un posto solo (D28); se comparissero qui il registro dei nascosti li vorrebbe.
      for (forbidden in listOf("VisibleMedia", "HiddenMask", "WidgetFeed")) assertWithFile(file, forbidden, lines.filter { it.contains(forbidden) })
      // Ogni scrittura di file passa da StateFiles (file di stato: temporaneo, forzatura, rinomina atomica).
      for (raw in listOf("writeText(", "writeBytes(", "FileOutputStream", "renameTo(", "deleteRecursively", ".delete()", "appendText(")) {
        val offending = lines.filter { it.contains(raw) && !it.contains("StateFiles.") }
        assertWithFile(file, raw, offending)
      }
    }
    // Il negozio usa davvero StateFiles.
    val store = code(File(copy(), "FacesEvalStore.kt"))
    assertThat(store.count { it.contains("StateFiles.writeText(") }).isAtLeast(2)
    assertThat(store.any { it.contains("StateFiles.readText(") }).isTrue()
    assertThat(store.any { it.contains("StateFiles.delete(") }).isTrue()
  }

  private fun assertWithFile(file: File, what: String, offending: List<String>) {
    assertWithMessage("${file.name} contiene \"$what\"").that(offending).isEmpty()
  }

  @Test
  fun laReleaseNonSaNienteDelloStrumento() {
    val root = File(repositoryRoot(), "app/src")
    for (dir in listOf("main", "release")) {
      val offenders = File(root, dir).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
        .filter { f -> f.readText(Charsets.UTF_8).let { "debugtools.eval.faces" in it || "FacesEval" in it } }.toList()
      assertThat(offenders).isEmpty()
    }
    // L'aggancio c'e' solo nel file di debug di DeveloperTools (e la release non ha il metodo in piu').
    val debug = File(root, "debug/kotlin/dev/pampa/mycloudgallery/debugtools/DeveloperTools.kt").readText(Charsets.UTF_8)
    assertThat(debug).contains("debugtools.eval.faces.FacesEvalEntry")
    assertThat(File(root, "release/kotlin/dev/pampa/mycloudgallery/debugtools/DeveloperTools.kt").readText(Charsets.UTF_8)).doesNotContain("Faces")
  }

  @Test
  fun lAggancioPerP13EDichiaratoNelFileDellaPorta() {
    // P13 lega la porta: il punto e' dichiarato in un posto solo e il file lo dice.
    val environment = File(copy(), "FacesEvalEnvironment.kt").readText(Charsets.UTF_8)
    assertThat(environment).contains("object FacesEvalEnvironmentHolder")
    assertThat(environment).contains("P13")
    assertThat(File(copy(), "FacesEvalEntry.kt").readText(Charsets.UTF_8)).contains("FacesEvalEnvironmentHolder.current")
  }
}
