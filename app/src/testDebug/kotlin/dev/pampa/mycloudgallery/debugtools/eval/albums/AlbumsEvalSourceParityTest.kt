package dev.pampa.mycloudgallery.debugtools.eval.albums

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Le metriche e la corsa degli album stanno in **due copie** (P12): in `core-albums/src/test/.../eval/` (provate con i viaggi sintetici) e in
 * `app/src/debug/.../debugtools/eval/albums/` (lo strumento sul telefono). Il test di un modulo non si vede dall'altro, e P12 non puo' toccare i file di build per
 * condividerle: questo test garantisce che le copie restino **uguali** (cambia solo la riga `package`). Chi modifica una copia deve modificare l'altra.
 *
 * E poi le guardie sullo strumento: nessun dato di viaggi veri nelle risorse, nessuna scrittura sulla Libreria, niente log, niente nella release.
 */
class AlbumsEvalSourceParityTest {

  private val shared = listOf("AlbumsEvalLabels", "AlbumsEvalFormat", "AlbumsEvalMetrics", "AlbumsEvalTrips", "AlbumsEvalReview", "AlbumsEvalRunner")

  private fun repositoryRoot(): File {
    var d: File? = File("").absoluteFile
    while (d != null) {
      if (File(d, "settings.gradle").isFile) return d
      d = d.parentFile
    }
    error("Radice del repository non trovata.")
  }

  private fun original(): File = File(repositoryRoot(), "core-albums/src/test/kotlin/dev/pampa/mycloudgallery/core/albums/eval")

  private fun copy(): File = File(repositoryRoot(), "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/albums")

  private fun body(file: File): String =
    file.readText(Charsets.UTF_8).replace("\r\n", "\n").lineSequence().filterNot { it.startsWith("package ") }.joinToString("\n").trimEnd()

  /** Il codice di un file senza commenti e senza righe di commento di documentazione. */
  private fun code(file: File): List<String> =
    file.readLines(Charsets.UTF_8).filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }

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
      assertThat(File(original(), "$name.kt").readLines().first { it.startsWith("package ") }).endsWith("core.albums.eval")
      assertThat(File(copy(), "$name.kt").readLines().first { it.startsWith("package ") }).endsWith("debugtools.eval.albums")
    }
  }

  @Test
  fun nessunFileCondivisoESfuggitoAlControllo() {
    // Ogni file di `core-albums/.../eval/` che non e' un test ne' un banco di prova deve stare nell'elenco (e quindi nella copia).
    val candidates = original().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().map { it.name.removeSuffix(".kt") }
      .filterNot { it.endsWith("Test") || it.endsWith("Kit") }
    assertThat(candidates).containsExactlyElementsIn(shared)
  }

  @Test
  fun lAppNonHaFileDiEtichetteNeViaggiNelleRisorse() {
    // Lo strumento non porta con se' dati: nessun file `.mcgalb`, nessun `viaggi-veri*`, nessuna cartella `eval-album-privato` in app/src.
    val files = File(repositoryRoot(), "app/src").walkTopDown().filter { it.isFile }.toList()
    assertThat(files.filter { it.name.endsWith(".mcgalb") || it.name.startsWith("viaggi-veri") || it.name == "viaggi-sintetici.tsv" }).isEmpty()
    assertThat(files.any { it.path.replace('\\', '/').contains("/eval-album-privato/") }).isFalse()
  }

  @Test
  fun loStrumentoNonScriveNellaLibreriaNonLoggaENonUsaLaRete() {
    val tool = copy().listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
    assertThat(tool).isNotEmpty()
    for (file in tool) {
      val lines = code(file)
      // Mai le righe su Room degli album, mai una scrittura SQL, mai un log, mai la rete, mai le coordinate della casa.
      for (forbidden in listOf("RoomSmartRows", "upsert", "Dao", "GalleryLog", "android.util.Log", "Log.", "HttpURLConnection", "URL(", "home_estimate", "HomeEstimate")) {
        assertThat(lines.filter { it.contains(forbidden) }).isEmpty()
      }
      // Nessuna scrittura SQL, tranne l'unica riga che spegne le chiavi esterne del database di lavoro in memoria.
      val sql = lines.filter { it.contains("execSQL") }.map { it.trim() }
      if (file.name == "AppAlbumsEvalEnvironment.kt") {
        assertThat(sql).containsExactly("scratch.openHelper.writableDatabase.execSQL(\"PRAGMA foreign_keys = OFF\")")
      } else {
        assertThat(sql).isEmpty()
      }
      // Il database compare solo nel banco che costruisce il database di lavoro in memoria e nella lettura per nome di file (sola lettura).
      if (file.name != "AppAlbumsEvalEnvironment.kt") assertThat(lines.filter { it.contains("GalleryDatabase") }).isEmpty()
    }
    val app = code(File(copy(), "AppAlbumsEvalEnvironment.kt"))
    assertThat(app.filter { it.contains("GalleryDatabase") }.map { it.trim() }).containsExactly(
      "import dev.pampa.mycloudgallery.core.database.GalleryDatabase",
      "private val database: GalleryDatabase,",
      "private fun scratchDatabase(): GalleryDatabase {",
      "val scratch = GalleryDatabase.build(context, null)",
    )
    assertThat(app.filter { it.contains("openHelper") }.map { it.trim() }).containsExactly(
      "val db = database.openHelper.readableDatabase",
      "scratch.openHelper.writableDatabase.execSQL(\"PRAGMA foreign_keys = OFF\")",
    )
  }

  @Test
  fun laReleaseNonSaNienteDelloStrumento() {
    val root = File(repositoryRoot(), "app/src")
    for (dir in listOf("main", "release")) {
      val offenders = File(root, dir).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
        .filter { f -> f.readText(Charsets.UTF_8).let { "debugtools.eval.albums" in it || "AlbumsEval" in it } }.toList()
      assertThat(offenders).isEmpty()
    }
    // L'aggancio c'e' solo nel file di debug di DeveloperTools (e la release non ha il metodo in piu').
    val debug = File(root, "debug/kotlin/dev/pampa/mycloudgallery/debugtools/DeveloperTools.kt").readText(Charsets.UTF_8)
    assertThat(debug).contains("debugtools.eval.albums.AlbumsEvalEntry")
    assertThat(debug).contains("AlbumsEvalEnvironmentHolder.current")
    assertThat(File(root, "release/kotlin/dev/pampa/mycloudgallery/debugtools/DeveloperTools.kt").readText(Charsets.UTF_8)).doesNotContain("Albums")
  }
}
