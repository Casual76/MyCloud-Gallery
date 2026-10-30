package dev.pampa.mycloudgallery.debugtools.eval.memories

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Le metriche, i giudizi e il formato dei ricordi stanno in **due copie** (P11): in `core-memories/src/test/.../eval/` (provate con la Libreria sintetica "di una famiglia") e in
 * `app/src/debug/.../debugtools/eval/memories/` (lo strumento sul telefono). Il test di un modulo non si vede dall'altro, e P11 non puo' toccare i file di build per
 * condividerle: questo test garantisce che le copie restino **uguali** (cambia solo la riga `package`). Chi modifica una copia deve modificare l'altra.
 *
 * E poi le guardie sullo strumento: nessun dato di ricordi veri nelle risorse, nessuna scrittura fuori da `StateFiles`, niente log, niente rete, niente nella release.
 */
class MemoriesEvalSourceParityTest {

  private val shared = listOf("MemoriesEvalModel", "MemoriesEvalLabels", "MemoriesEvalFormat", "MemoriesEvalMetrics")

  private fun repositoryRoot(): File {
    var d: File? = File("").absoluteFile
    while (d != null) {
      if (File(d, "settings.gradle").isFile) return d
      d = d.parentFile
    }
    error("Radice del repository non trovata.")
  }

  private fun original(): File = File(repositoryRoot(), "core-memories/src/test/kotlin/dev/pampa/mycloudgallery/core/memories/eval")

  private fun copy(): File = File(repositoryRoot(), "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/memories")

  private fun body(file: File): String =
    file.readText(Charsets.UTF_8).replace("\r\n", "\n").lineSequence().filterNot { it.startsWith("package ") }.joinToString("\n").trimEnd()

  /** Il codice di un file senza commenti e senza righe di commento di documentazione. */
  private fun code(file: File): List<String> =
    file.readLines(Charsets.UTF_8).filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }

  @Test
  fun leDueCopieSonoUguali() {
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
      assertThat(File(original(), "$name.kt").readLines().first { it.startsWith("package ") }).endsWith("core.memories.eval")
      assertThat(File(copy(), "$name.kt").readLines().first { it.startsWith("package ") }).endsWith("debugtools.eval.memories")
    }
  }

  @Test
  fun nessunFileCondivisoESfuggitoAlControllo() {
    // Ogni file di `core-memories/.../eval/` che non e' un test ne' un banco di prova (nome che finisce per Kit) deve stare nell'elenco (e quindi nella copia).
    val candidates = original().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().map { it.name.removeSuffix(".kt") }.filterNot { it.endsWith("Test") || it.endsWith("Kit") }
    assertThat(candidates).containsExactlyElementsIn(shared)
  }

  @Test
  fun lAppNonHaGiudiziNeImmaginiNelleRisorse() {
    // Lo strumento non porta con se' dati: nessun file `.mcgmem`, nessun `ricordi-veri*`, nessuna cartella `eval-ricordi-privato`, nessuna immagine nella sua cartella.
    val files = File(repositoryRoot(), "app/src").walkTopDown().filter { it.isFile }.toList()
    assertThat(files.filter { it.name.endsWith(".mcgmem") || it.name.startsWith("ricordi-veri") }).isEmpty()
    assertThat(files.any { it.path.replace('\\', '/').contains("/eval-ricordi-privato/") }).isFalse()
    val images = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".gif", ".mp4")
    assertThat(files.filter { f -> f.path.replace('\\', '/').contains("/eval/memories/") && images.any { f.name.lowercase().endsWith(it) } }).isEmpty()
  }

  @Test
  fun loStrumentoNonScriveFuoriDaStateFilesNonLoggaENonUsaLaRete() {
    val tool = copy().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
    assertThat(tool).isNotEmpty()
    for (file in tool) {
      val lines = code(file)
      // Mai un log (ne' di sistema ne' a schermo), mai la rete, mai la casa, mai le coordinate, mai i contratti dei nascosti ne' del motore (lo strumento parla con la porta).
      for (forbidden in listOf(
        "GalleryLog", "android.util.Log", "Log.", "println", "HttpURLConnection", "URL(", "OkHttp", "home_estimate", "HomeEstimate", "HomeClearance", "AwayFromHome",
        "GeoPoint", "core.memories.", "core.library.", "VisibleMedia", "HiddenMask", "MemoryShelfStore", "MemoriesPersonalSettings", "execSQL", "Dao",
      )) {
        assertThat(lines.filter { it.contains(forbidden) }).isEmpty()
      }
      // Ogni scrittura di file passa da StateFiles (file temporaneo, forzatura su disco, rinomina atomica): mai `writeText` o `FileOutputStream` per conto proprio.
      assertThat(lines.filter { (it.contains("writeText(") || it.contains("writeBytes(") || it.contains("FileOutputStream") || it.contains("renameTo(")) && !it.contains("StateFiles.") }).isEmpty()
    }
    // E ogni scrittura dello strumento e' nella cartella dell'app che non va nei backup.
    assertThat(code(File(copy(), "MemoriesEvalStore.kt")).filter { it.contains("noBackupFilesDir") }.map { it.trim() })
      .containsExactly("fun of(context: Context): MemoriesEvalStore = MemoriesEvalStore(File(File(context.noBackupFilesDir, \"eval\"), \"memories\"))")
  }

  @Test
  fun laReleaseNonSaNienteDelloStrumento() {
    val root = File(repositoryRoot(), "app/src")
    for (dir in listOf("main", "release")) {
      val offenders = File(root, dir).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
        .filter { f -> f.readText(Charsets.UTF_8).let { "debugtools.eval.memories" in it || "MemoriesEval" in it } }.toList()
      assertThat(offenders).isEmpty()
    }
    // L'aggancio c'e' solo nel file di debug di DeveloperTools (e la release non ha il metodo in piu'); la porta la lega P13 (nessun file di debug la assegna ancora).
    val debug = File(root, "debug/kotlin/dev/pampa/mycloudgallery/debugtools/DeveloperTools.kt").readText(Charsets.UTF_8)
    assertThat(debug).contains("debugtools.eval.memories.MemoriesEvalEntry")
    assertThat(File(root, "release/kotlin/dev/pampa/mycloudgallery/debugtools/DeveloperTools.kt").readText(Charsets.UTF_8)).doesNotContain("Memories")
    val binder = Regex("MemoriesEvalEnvironmentHolder\\.current\\s*=[^=]")
    val binders = File(root, "debug").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && code(it).any { line -> binder.containsMatchIn(line) } }.map { it.name }.toList()
    assertThat(binders).isEmpty()
  }

  @Test
  fun leDueCopieNonNominanoIlMotoreEIlNomeDiUnaPersona() {
    // Il titolo non porta mai il nome di una persona (D12) e la porta e' di tipi neutri: nessun contratto del motore nei file puri.
    for (name in shared) {
      val lines = code(File(copy(), "$name.kt"))
      for (forbidden in listOf("core.memories.contract", "PersonId", "personName", "MemoryIds", "MemoryKey")) assertThat(lines.filter { it.contains(forbidden) }).isEmpty()
    }
  }
}
