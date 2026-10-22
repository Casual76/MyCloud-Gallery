package dev.pampa.mycloudgallery.core.search.eval

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Nessuna etichetta e nessuna query del set privato nel repository** (docs/fase6-progetto.md §5.3, riga P9 del §4.4). Le etichette sono chiavi
 * di contenuto e query sulle foto vere della famiglia: stanno solo sul telefono (`noBackupFilesDir/eval/`) e in `%USERPROFILE%\.mycloudgallery\eval\`.
 *
 * Il controllo guarda i file **tracciati da git** (`git ls-files`, quindi anche quelli in staging), non solo la cartella di lavoro:
 *  - nessun file con il nome di un file di etichette (`*.mcgeval`, `etichette*`, `query-private*`), ne' dentro una cartella `eval-privato` o `.mycloudgallery`;
 *  - nessun file il cui contenuto cominci con l'intestazione del formato di [LabelFormat] (`MCGEVAL1`);
 *  - le regole del `.gitignore` che li escludono.
 * Se `git` non c'e' si controlla la cartella di lavoro intera (meno le cartelle di build). Il controllo e' provato su file veri in una cartella
 * temporanea: se smettesse di vedere le violazioni, il test fallirebbe.
 */
class NoPrivateEvalDataInGitTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun nessunFileDiEtichetteENelRepository() {
    val root = repositoryRoot()
    val files = trackedFiles(root)
    assertThat(files).isNotEmpty()
    assertThat(violations(root, files)).isEmpty()
  }

  @Test
  fun ilGitignoreEscludeLeEtichetteEILoroNomi() {
    val ignore = File(repositoryRoot(), ".gitignore").readLines().map { it.trim() }
    for (rule in REQUIRED_IGNORE_RULES) assertThat(ignore).contains(rule)
  }

  @Test
  fun ilControlloVedeLeViolazioni() {
    val root = tmp.newFolder("repo")
    val ok = File(root, "docs/nota.md").apply { parentFile!!.mkdirs(); writeText("# niente") }
    val byExtension = File(root, "dati/mie.mcgeval").apply { parentFile!!.mkdirs(); writeText("x") }
    val byName = File(root, "etichette-cane.txt").apply { writeText("x") }
    val byFolder = File(root, "eval-privato/qualunque.txt").apply { parentFile!!.mkdirs(); writeText("x") }
    val byUserFolder = File(root, "copia/.mycloudgallery/eval/a.txt").apply { parentFile!!.mkdirs(); writeText("x") }
    val byHeader = File(root, "note/export.txt").apply { parentFile!!.mkdirs(); writeText(LabelFormat.serialize(LabelSet())) }
    val byHeaderCrlf = File(root, "note/export2.txt").apply { writeText(LabelFormat.MAGIC + "\r\nQ\tq1\toggetti\tcane\r\n") }
    val found = violations(root, listOf(ok, byExtension, byName, byFolder, byUserFolder, byHeader, byHeaderCrlf))
    assertThat(found).hasSize(6)
    assertThat(found.joinToString("\n")).doesNotContain("nota.md")
    // Le violazioni dicono il percorso, mai il contenuto.
    assertThat(found.joinToString("\n")).doesNotContain("cane\r")
  }

  @Test
  fun ilSorgenteDelFormatoNonEUnaViolazione() {
    // Il codice che definisce l'intestazione la contiene, ma non comincia con essa: non e' un file di etichette.
    val source = File(repositoryRoot(), "core-search/src/test/kotlin/dev/pampa/mycloudgallery/core/search/eval/EvalLabels.kt")
    assertThat(source.readText()).contains(LabelFormat.MAGIC)
    assertThat(violations(repositoryRoot(), listOf(source))).isEmpty()
  }

  // --- Il controllo --------------------------------------------------------------------------------------------------------------

  private fun violations(root: File, files: List<File>): List<String> {
    val out = ArrayList<String>()
    for (file in files) {
      if (!file.isFile) continue
      val relative = file.relativeTo(root).invariantPath()
      val segments = relative.split('/')
      val name = segments.last().lowercase()
      val byName = name.endsWith(".mcgeval") || name.startsWith("etichette") || name.startsWith("query-private") || name.startsWith("query-private")
      val byFolder = segments.dropLast(1).any { it.equals("eval-privato", ignoreCase = true) || it.equals(".mycloudgallery", ignoreCase = true) }
      if (byName || byFolder || startsWithMagic(file)) out += relative
    }
    return out
  }

  private fun startsWithMagic(file: File): Boolean {
    val head = ByteArray(LabelFormat.MAGIC.length)
    val read = file.inputStream().use { it.read(head) }
    return read == head.size && String(head, Charsets.UTF_8) == LabelFormat.MAGIC
  }

  private fun File.invariantPath(): String = path.replace('\\', '/')

  /** La radice del repository: la cartella piu' vicina verso l'alto che ha `settings.gradle`. */
  private fun repositoryRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle").isFile) return dir
      dir = dir.parentFile
    }
    error("Radice del repository non trovata.")
  }

  /** I file tracciati da git; senza `git` (o fuori da un repository) tutta la cartella di lavoro meno le cartelle generate. */
  private fun trackedFiles(root: File): List<File> {
    gitList(root)?.let { return it }
    return root.walkTopDown().onEnter { it.name !in SKIPPED_DIRS }.filter { it.isFile }.toList()
  }

  private fun gitList(root: File): List<File>? = try {
    val process = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(false).start()
    val bytes = process.inputStream.readBytes()
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) null
    else String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.map { File(root, it) }
  } catch (e: java.io.IOException) {
    null
  }

  private companion object {
    val SKIPPED_DIRS = setOf(".git", "build", ".gradle", ".kotlin", ".idea", "out", "node_modules")

    /** Le regole che ci devono essere nel `.gitignore` (riga per riga, esatte). */
    val REQUIRED_IGNORE_RULES = listOf("*.mcgeval", "etichette*", "query-private*", "eval-privato/", ".mycloudgallery/")
  }
}
