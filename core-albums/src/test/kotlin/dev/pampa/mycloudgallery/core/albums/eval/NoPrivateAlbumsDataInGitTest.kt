package dev.pampa.mycloudgallery.core.albums.eval

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Nessuna etichetta, data o luogo dei viaggi veri nel repository** (docs/fase7-progetto.md §5.3, riga P12 del §4.4). Le etichette dei viaggi sono i
 * giorni, i titoli e le foto dei viaggi veri della famiglia: stanno solo sul telefono (`noBackupFilesDir/eval/albums/`) e in
 * `%USERPROFILE%\.mycloudgallery\eval\albums\`. Come `NoPrivateEvalDataInGitTest` della ricerca, ma piu' stretto: guarda anche il **contenuto**.
 *
 * Il controllo guarda i file **tracciati da git** (`git ls-files`, quindi anche quelli in staging), non solo la cartella di lavoro:
 *  - nessun file con il nome di un file di etichette dei viaggi (`*.mcgalb`, `viaggi-veri*`), ne' dentro una cartella `eval-album-privato` o
 *    `.mycloudgallery`;
 *  - nessun file che cominci con l'intestazione del formato ([AlbumsLabelFormat.MAGIC]);
 *  - nessun file di testo con **righe del formato** delle etichette in mezzo ad altro (un viaggio `V`, una foto del viaggio `Y` o `N`): sarebbero
 *    etichette incollate in un documento;
 *  - le regole del `.gitignore` che li escludono.
 * Se `git` non c'e' si controlla la cartella di lavoro intera (meno le cartelle di build). Il controllo e' provato su file veri in una cartella
 * temporanea: se smettesse di vedere le violazioni, il test fallirebbe.
 */
class NoPrivateAlbumsDataInGitTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun nessunaEtichettaDeiViaggiENelRepository() {
    val root = repositoryRoot()
    val files = trackedFiles(root)
    assertThat(files).isNotEmpty()
    assertThat(violations(root, files)).isEmpty()
  }

  @Test
  fun ilGitignoreEscludeLeEtichetteDeiViaggi() {
    val ignore = File(repositoryRoot(), ".gitignore").readLines().map { it.trim() }
    for (rule in REQUIRED_IGNORE_RULES) assertThat(ignore).contains(rule)
  }

  @Test
  fun ilControlloVedeLeViolazioni() {
    val root = tmp.newFolder("repo")
    val labels = AlbumsLabelFormat.serialize(
      AlbumsLabelSet().withTrip(EvalTrip("v01", java.time.LocalDate.of(2026, 3, 12), java.time.LocalDate.of(2026, 3, 19), "Oslo 2026"))
        .withInTrip("v01", MEDIA, true),
    )
    val ok = File(root, "docs/nota.md").apply { parentFile!!.mkdirs(); writeText("# niente\nV come viaggio\n") }
    val byExtension = File(root, "dati/miei.mcgalb").apply { parentFile!!.mkdirs(); writeText("x") }
    val byName = File(root, "viaggi-veri-2026.txt").apply { writeText("x") }
    val byFolder = File(root, "eval-album-privato/qualunque.txt").apply { parentFile!!.mkdirs(); writeText("x") }
    val byUserFolder = File(root, "copia/.mycloudgallery/eval/albums/a.txt").apply { parentFile!!.mkdirs(); writeText("x") }
    val byHeader = File(root, "note/export.txt").apply { parentFile!!.mkdirs(); writeText(labels) }
    val byHeaderCrlf = File(root, "note/export2.txt").apply { writeText(AlbumsLabelFormat.MAGIC + "\r\nV\tv01\t2026-03-12\t2026-03-19\tx\r\n") }
    val pasted = File(root, "docs/incollato.md").apply { writeText("# Resoconto\n\n" + labels.removePrefix(AlbumsLabelFormat.MAGIC + "\n") + "\nfine\n") }
    val pastedMedia = File(root, "docs/incollato2.md").apply { writeText("prima\nY\tv02\t$MEDIA\ndopo\n") }
    val found = violations(root, listOf(ok, byExtension, byName, byFolder, byUserFolder, byHeader, byHeaderCrlf, pasted, pastedMedia))
    assertThat(found).hasSize(8)
    assertThat(found.joinToString("\n")).doesNotContain("nota.md")
    // Le violazioni dicono il percorso, mai il contenuto.
    assertThat(found.joinToString("\n")).doesNotContain("Oslo")
    assertThat(found.joinToString("\n")).doesNotContain(MEDIA)
  }

  @Test
  fun iSorgentiDelFormatoNonSonoUnaViolazione() {
    // Il codice che definisce l'intestazione e le righe la contiene, ma non e' un file di etichette.
    val dir = File(repositoryRoot(), "core-albums/src/test/kotlin/dev/pampa/mycloudgallery/core/albums/eval")
    val sources = dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
    assertThat(sources).isNotEmpty()
    assertThat(File(dir, "AlbumsEvalFormat.kt").readText()).contains(AlbumsLabelFormat.MAGIC)
    assertThat(violations(repositoryRoot(), sources)).isEmpty()
  }

  // --- Il controllo --------------------------------------------------------------------------------------------------------------

  private fun violations(root: File, files: List<File>): List<String> {
    val out = ArrayList<String>()
    for (file in files) {
      if (!file.isFile) continue
      val relative = file.relativeTo(root).invariantPath()
      val segments = relative.split('/')
      val name = segments.last().lowercase()
      val byName = name.endsWith(".mcgalb") || name.startsWith("viaggi-veri")
      val byFolder = segments.dropLast(1).any { it.equals("eval-album-privato", ignoreCase = true) || it.equals(".mycloudgallery", ignoreCase = true) }
      if (byName || byFolder || startsWithMagic(file) || hasLabelLines(file)) out += relative
    }
    return out
  }

  private fun startsWithMagic(file: File): Boolean {
    val head = ByteArray(AlbumsLabelFormat.MAGIC.length)
    val read = file.inputStream().use { it.read(head) }
    return read == head.size && String(head, Charsets.UTF_8) == AlbumsLabelFormat.MAGIC
  }

  /** Righe del formato delle etichette in un file di testo (non binario, non enorme): un viaggio `V` con due date, una foto `Y` o `N` con un id. */
  private fun hasLabelLines(file: File): Boolean {
    if (file.length() > MAX_SCANNED_BYTES) return false
    val bytes = file.readBytes()
    if (bytes.take(SNIFF_BYTES).any { it == 0.toByte() }) return false
    val text = String(bytes, Charsets.UTF_8)
    return TRIP_LINE.containsMatchIn(text) || MEDIA_LINE.containsMatchIn(text)
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
    val REQUIRED_IGNORE_RULES = listOf("*.mcgalb", "viaggi-veri*", "eval-album-privato/", ".mycloudgallery/")

    const val MEDIA = "7a1c0000-0000-4000-8000-00000000abcd"
    const val MAX_SCANNED_BYTES = 4L * 1024 * 1024
    const val SNIFF_BYTES = 4096
    val TRIP_LINE = Regex("(?m)^V\t(?:v[0-9]{2,3})\t[0-9]{4}-[0-9]{2}-[0-9]{2}\t[0-9]{4}-[0-9]{2}-[0-9]{2}\t")
    val MEDIA_LINE = Regex("(?m)^[YN]\t(?:v[0-9]{2,3})\t[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\s*$")
  }
}
