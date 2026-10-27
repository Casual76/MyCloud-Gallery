package dev.pampa.mycloudgallery.core.faces.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Nessuna etichetta, nome o volto dei dati veri nel repository** (docs/fase8-progetto.md §5.3 e riga P11 del §4.4). Le etichette della valutazione
 * delle persone sono posti di volti nelle foto vere della famiglia: stanno solo sul telefono (`noBackupFilesDir/eval/faces/`) e in
 * `%USERPROFILE%\.mycloudgallery\eval\faces\`, mai qui. Come `NoPrivateAlbumsDataInGitTest`, guarda anche il **contenuto**.
 *
 * Il controllo guarda i file **tracciati da git** (`git ls-files`, quindi anche quelli in staging), non solo la cartella di lavoro:
 *  - nessun file con il nome di un file di etichette dei volti (`*.mcgfac`, `persone-veri*`), ne' dentro una cartella `eval-volti-privato` o
 *    `.mycloudgallery`;
 *  - nessun file che cominci con l'intestazione del formato ([FacesLabelFormat.MAGIC]);
 *  - nessun file di testo con **righe del formato** (un gruppo `G`, un volto sbagliato `W` con un posto, un collegamento `E`): sarebbero etichette
 *    incollate in un documento;
 *  - **nessuna immagine** sotto i percorsi dello strumento (`app/src/debug/.../eval/faces/`, `core-faces/src/test/.../eval/`,
 *    `tools/fixtures/faces/`): ritratti e composizioni stanno fuori dal repository, in `mcg-models\faces\`; vale anche per gli screenshot, che
 *    stanno in `docs/screenshots/8-P11/` e sono fatti con dati sintetici;
 *  - nessun nome di chi e' ritratto nei **nomi dei file** o nei sorgenti dello strumento e dei suoi test: i nomi vengono dall'elenco dei ritratti di
 *    pubblico dominio (colonna "nomi" di `tools/fixtures/faces/ritratti.tsv`: chi ha dipinto e chi e' ritratto, parole distintive separate da "/"), che e' l'unico posto dove possono stare;
 *  - le regole del `.gitignore` che li escludono.
 * Se `git` non c'e' si controlla la cartella di lavoro intera (meno le cartelle di build). Il controllo e' provato su file veri in una cartella
 * temporanea: se smettesse di vedere le violazioni, il test fallirebbe.
 */
class NoPrivateFacesEvalInGitTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun nessunaEtichettaNeImmagineENelRepository() {
    val root = repositoryRoot()
    val files = trackedFiles(root)
    assertThat(files).isNotEmpty()
    assertThat(violations(root, files)).isEmpty()
  }

  @Test
  fun ilGitignoreEscludeLeEtichetteEGliOutputDelGeneratore() {
    val ignore = File(repositoryRoot(), ".gitignore").readLines().map { it.trim() }
    for (rule in REQUIRED_IGNORE_RULES) assertThat(ignore).contains(rule)
  }

  @Test
  fun ilControlloVedeLeViolazioni() {
    val root = tmp.newFolder("repo")
    val locator = FaceLocator(ContentKey("ab".repeat(32)), 0, FaceBox(100, 200, 900, 1_200)).encode()
    val person = "7a1c0000-0000-4000-8000-00000000abcd"
    val labels = "${FacesLabelFormat.MAGIC}\nS\t1\t5\t0\t0\t0\t0\t0\t0\nG\t1\t$person\t3\t0\t3\t-\nW\t1\t$person\t$locator\n"
    val ok = File(root, "docs/nota.md").apply { parentFile!!.mkdirs(); writeText("# niente\nG come gruppo\n") }
    val byExtension = File(root, "dati/mie.mcgfac").apply { parentFile!!.mkdirs(); writeText("x") }
    val byName = File(root, "persone-veri-2026.txt").apply { writeText("x") }
    val byFolder = File(root, "eval-volti-privato/qualunque.txt").apply { parentFile!!.mkdirs(); writeText("x") }
    val byUserFolder = File(root, "copia/.mycloudgallery/eval/faces/a.txt").apply { parentFile!!.mkdirs(); writeText("x") }
    val byHeader = File(root, "note/export.txt").apply { parentFile!!.mkdirs(); writeText(labels) }
    val byHeaderCrlf = File(root, "note/export2.txt").apply { writeText(FacesLabelFormat.MAGIC + "\r\nS\t1\t5\t0\t0\t0\t0\t0\t0\r\n") }
    val pasted = File(root, "docs/incollato.md").apply { writeText("# Resoconto\n\n" + labels.removePrefix(FacesLabelFormat.MAGIC + "\n") + "\nfine\n") }
    val pastedLocator = File(root, "docs/incollato2.md").apply { writeText("prima\nW\t2\t$person\t$locator\ndopo\n") }
    val image = File(root, "tools/fixtures/faces/ritratto.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
    val toolImage = File(root, "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/faces/foto.JPG").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
    val testImage = File(root, "core-faces/src/test/kotlin/dev/pampa/mycloudgallery/core/faces/eval/volto.webp").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
    val screenshot = File(root, "docs/screenshots/8-P11/strumento.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
    val found = violations(
      root,
      listOf(ok, byExtension, byName, byFolder, byUserFolder, byHeader, byHeaderCrlf, pasted, pastedLocator, image, toolImage, testImage, screenshot),
      names = listOf("Inventato"),
    )
    assertThat(found).hasSize(11)
    assertThat(found.joinToString("\n")).doesNotContain("nota.md")
    assertThat(found.joinToString("\n")).doesNotContain("8-P11")
    // Le violazioni dicono il percorso, mai il contenuto.
    assertThat(found.joinToString("\n")).doesNotContain(person)
    assertThat(found.joinToString("\n")).doesNotContain(locator)
  }

  @Test
  fun unNomeDiChiERitrattoNeiNomiOnegliSorgentiESegnalato() {
    val root = tmp.newFolder("repo2")
    val inName = File(root, "tools/fixtures/faces/inventato-tv.py").apply { parentFile!!.mkdirs(); writeText("# niente") }
    val inSource = File(root, "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/faces/Qualcosa.kt").apply { parentFile!!.mkdirs(); writeText("// il ritratto di INVENTATO\n") }
    val inTest = File(root, "core-faces/src/test/kotlin/dev/pampa/mycloudgallery/core/faces/eval/AltroTest.kt").apply { parentFile!!.mkdirs(); writeText("val x = \"Inventato\"\n") }
    val clean = File(root, "tools/fixtures/faces/ritratti.tsv").apply { writeText("ritratto-01\ts01\tInventato\n") }
    val found = violations(root, listOf(inName, inSource, inTest, clean), names = listOf("Inventato"))
    assertThat(found.map { it.substringAfterLast('/') }).containsExactly("inventato-tv.py", "Qualcosa.kt", "AltroTest.kt")
  }

  @Test
  fun iSorgentiDelFormatoNonSonoUnaViolazione() {
    // Il codice che definisce l'intestazione e le righe la contiene, ma non e' un file di etichette.
    val dir = File(repositoryRoot(), "core-faces/src/test/kotlin/dev/pampa/mycloudgallery/core/faces/eval")
    val sources = dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
    assertThat(sources).isNotEmpty()
    assertThat(File(dir, "FacesEvalLabels.kt").readText()).contains(FacesLabelFormat.MAGIC)
    assertThat(violations(repositoryRoot(), sources, names = portraitNames(repositoryRoot()))).isEmpty()
  }

  @Test
  fun lElencoDeiRitrattiHaINomiDaCuiVengonoIControlli() {
    val authors = portraitNames(repositoryRoot())
    assertThat(authors).isNotEmpty()
    assertThat(authors.all { it.length >= 3 }).isTrue()
  }

  // --- Il controllo --------------------------------------------------------------------------------------------------------------

  private fun violations(root: File, files: List<File>, names: List<String> = portraitNames(root)): List<String> {
    val out = ArrayList<String>()
    for (file in files) {
      if (!file.isFile) continue
      val relative = file.relativeTo(root).invariantPath()
      val segments = relative.split('/')
      val name = segments.last().lowercase()
      val byName = name.endsWith(".mcgfac") || name.startsWith("persone-veri")
      val byFolder = segments.dropLast(1).any { it.equals("eval-volti-privato", ignoreCase = true) || it.equals(".mycloudgallery", ignoreCase = true) }
      val image = IMAGE_EXTENSIONS.any { name.endsWith(".$it") } && TOOL_PATHS.any { relative.startsWith(it) || relative.contains("/$it") }
      val shot = IMAGE_EXTENSIONS.any { name.endsWith(".$it") } && relative.startsWith("docs/screenshots/8-P11/")
      val portrait = names.isNotEmpty() && inToolPath(relative) && (names.any { mentions(segments.last(), it) } || mentionsAny(file, names))
      // Gli screenshot sono ammessi (dati sintetici); il loro nome non e' una violazione.
      if (shot) continue
      if (byName || byFolder || image || portrait || startsWithMagic(file) || hasLabelLines(file)) out += relative
    }
    return out
  }

  private fun inToolPath(relative: String): Boolean = TOOL_PATHS.any { relative.startsWith(it) || relative.contains("/$it") }

  /** Minuscolo e senza accenti: "perché" e "perche" sono la stessa parola. */
  private fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

  /** Il nome compare come parola (non dentro un'altra parola) in [text]. */
  private fun mentions(text: String, name: String): Boolean = Regex("(?<![a-z])" + Regex.escape(normalized(name)) + "(?![a-z])").containsMatchIn(normalized(text))

  private fun mentionsAny(file: File, names: List<String>): Boolean {
    if (!TEXT_EXTENSIONS.any { file.name.lowercase().endsWith(it) } || file.name == "ritratti.tsv" || file.length() > MAX_SCANNED_BYTES) return false
    val text = file.readText(Charsets.UTF_8)
    return names.any { mentions(text, it) }
  }

  private fun startsWithMagic(file: File): Boolean {
    val head = ByteArray(FacesLabelFormat.MAGIC.length)
    val read = file.inputStream().use { it.read(head) }
    return read == head.size && String(head, Charsets.UTF_8) == FacesLabelFormat.MAGIC
  }

  /** Righe del formato delle etichette in un file di testo (non binario, non enorme): un gruppo `G`, un volto sbagliato `W` con un posto, un collegamento `E`. */
  private fun hasLabelLines(file: File): Boolean {
    if (file.length() > MAX_SCANNED_BYTES) return false
    val bytes = file.readBytes()
    if (bytes.take(SNIFF_BYTES).any { it == 0.toByte() }) return false
    val text = String(bytes, Charsets.UTF_8)
    return GROUP_LINE.containsMatchIn(text) || WRONG_LINE.containsMatchIn(text) || SAME_LINE.containsMatchIn(text)
  }

  private fun File.invariantPath(): String = path.replace('\\', '/')

  /** I nomi di chi e' ritratto (e di chi ha dipinto) dall'elenco di pubblico dominio: le parole che non devono comparire altrove. */
  private fun portraitNames(root: File): List<String> {
    val list = File(root, "tools/fixtures/faces/ritratti.tsv")
    if (!list.isFile) return emptyList()
    val lines = list.readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
    val header = lines.firstOrNull()?.split('\t') ?: return emptyList()
    val column = header.indexOf("nomi")
    if (column < 0) return emptyList()
    return lines.drop(1).mapNotNull { it.split('\t').getOrNull(column)?.trim() }.filter { it.isNotEmpty() }.distinct()
  }

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
    val REQUIRED_IGNORE_RULES = listOf("*.mcgfac", "persone-veri*", "eval-volti-privato/", ".mycloudgallery/", "tools/fixtures/faces/out/")

    /** I percorsi dello strumento e dei suoi dati di prova: dentro, niente immagini e niente nomi di chi e' ritratto. */
    val TOOL_PATHS = listOf(
      "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/faces/",
      "app/src/testDebug/kotlin/dev/pampa/mycloudgallery/debugtools/eval/faces/",
      "core-faces/src/test/kotlin/dev/pampa/mycloudgallery/core/faces/eval/",
      "tools/fixtures/faces/",
    )
    val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "heic", "heif", "bmp", "gif", "tif", "tiff", "avif")
    val TEXT_EXTENSIONS = listOf(".kt", ".py", ".md", ".txt", ".tsv", ".json", ".ps1", ".gradle", ".xml")
    const val MAX_SCANNED_BYTES = 4L * 1024 * 1024
    const val SNIFF_BYTES = 4096
    val GROUP_LINE = Regex("(?m)^G\t[12]\t[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\t[0-9]+\t[01]\t[0-9]+\t[-rm]+\\s*$")
    val WRONG_LINE = Regex("(?m)^W\t[12]\t[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\t[0-9a-z]+:[0-9]+:[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+\\s*$")
    val SAME_LINE = Regex("(?m)^E\t[12]\t[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\t[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\s*$")
  }
}
