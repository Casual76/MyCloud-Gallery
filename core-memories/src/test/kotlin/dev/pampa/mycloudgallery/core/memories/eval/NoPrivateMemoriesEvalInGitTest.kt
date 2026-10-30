package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Nessuna etichetta, data, luogo o immagine vera dei ricordi nel repository** (docs/fase9-progetto.md §5.3, riga P11 del §4.4). I giudizi sui ricordi veri sono
 * titoli, giorni e foto della famiglia: stanno solo sul telefono (`noBackupFilesDir/eval/memories/`) e in `%USERPROFILE%\.mycloudgallery\eval\memories\`. Come
 * `NoPrivateAlbumsDataInGitTest`, ma guarda anche la cartella del generatore della Libreria di prova (`tools/fixtures/memories/`): niente immagini, niente date
 * vere (le date della prova sono **relative** al giorno in cui si fa).
 *
 * Il controllo guarda i file **tracciati da git** (`git ls-files`, quindi anche quelli in staging), non solo la cartella di lavoro:
 *  - nessun file con il nome di un file di giudizi (`*.mcgmem`, `ricordi-veri*`), ne' dentro una cartella `eval-ricordi-privato` o `.mycloudgallery`;
 *  - nessun file che cominci con l'intestazione del formato ([MemoriesLabelFormat.MAGIC]);
 *  - nessun file di testo con **righe del formato** dei giudizi in mezzo ad altro (un ricordo `R`, un giudizio `M`, una foto da togliere `F`): sarebbero giudizi
 *    incollati in un documento;
 *  - sotto `tools/fixtures/memories/`: nessuna immagine e nessuna data scritta per esteso (aaaa-mm-gg);
 *  - le regole del `.gitignore` che li escludono.
 * Se `git` non c'e' si controlla la cartella di lavoro intera (meno le cartelle di build). Il controllo e' provato su file veri in una cartella temporanea: se
 * smettesse di vedere le violazioni, il test fallirebbe.
 */
class NoPrivateMemoriesEvalInGitTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun nessunGiudizioSuiRicordiENelRepository() {
    val root = repositoryRoot()
    val files = trackedFiles(root)
    assertThat(files).isNotEmpty()
    assertThat(violations(root, files)).isEmpty()
  }

  @Test
  fun ilGitignoreEscludeIGiudiziSuiRicordi() {
    val ignore = File(repositoryRoot(), ".gitignore").readLines().map { it.trim() }
    for (rule in REQUIRED_IGNORE_RULES) assertThat(ignore).contains(rule)
  }

  @Test
  fun ilControlloVedeLeViolazioni() {
    val root = tmp.newFolder("repo")
    val run = listOf(sampleMemory(1, size = 6))
    val labels = MemoriesLabelFormat.serialize(MemoriesLabelSet().withRun(run).withJudgement(run[0].id, EvalVerdict.DONT_SHOW, EvalWhy.SAD).withFault(run[0].id, run[0].members[1], EvalFault.SAD))
    val ok = File(root, "docs/nota.md").apply { parentFile!!.mkdirs(); writeText("# niente\nR come ricordo\nM come mese\n") }
    val byExtension = File(root, "dati/miei.mcgmem").apply { parentFile!!.mkdirs(); writeText("x") }
    val byName = File(root, "ricordi-veri-2026.txt").apply { writeText("x") }
    val byFolder = File(root, "eval-ricordi-privato/qualunque.txt").apply { parentFile!!.mkdirs(); writeText("x") }
    val byUserFolder = File(root, "copia/.mycloudgallery/eval/memories/a.txt").apply { parentFile!!.mkdirs(); writeText("x") }
    val byHeader = File(root, "note/export.txt").apply { parentFile!!.mkdirs(); writeText(labels) }
    val byHeaderCrlf = File(root, "note/export2.txt").apply { writeText(MemoriesLabelFormat.MAGIC + "\r\nM\t${run[0].id}\tB\t-\r\n") }
    val pasted = File(root, "docs/incollato.md").apply { writeText("# Resoconto\n\n" + labels.removePrefix(MemoriesLabelFormat.MAGIC + "\n") + "\nfine\n") }
    val pastedMedia = File(root, "docs/incollato2.md").apply { writeText("prima\nF\t${run[0].id}\t${run[0].members[2]}\td\ndopo\n") }
    val image = File(root, "tools/fixtures/memories/out/IMG_20260101_120000.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
    val realDate = File(root, "tools/fixtures/memories/spec.tsv").apply { writeText("giorno\n2026-03-12\n") }
    val relativeOnly = File(root, "tools/fixtures/memories/spec-relativa.tsv").apply { writeText("giorni_fa\tanni_fa\n12\t2\n") }
    val found = violations(root, listOf(ok, byExtension, byName, byFolder, byUserFolder, byHeader, byHeaderCrlf, pasted, pastedMedia, image, realDate, relativeOnly))
    assertThat(found).hasSize(10)
    assertThat(found.joinToString("\n")).doesNotContain("nota.md")
    assertThat(found.joinToString("\n")).doesNotContain("spec-relativa")
    // Le violazioni dicono il percorso, mai il contenuto.
    assertThat(found.joinToString("\n")).doesNotContain(run[0].title)
    assertThat(found.joinToString("\n")).doesNotContain(run[0].id)
    assertThat(found.joinToString("\n")).doesNotContain("2026-03-12")
  }

  @Test
  fun iSorgentiDelFormatoNonSonoUnaViolazione() {
    // Il codice che definisce l'intestazione e le righe la contiene, ma non e' un file di giudizi.
    val dir = File(repositoryRoot(), "core-memories/src/test/kotlin/dev/pampa/mycloudgallery/core/memories/eval")
    val sources = dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
    assertThat(sources).isNotEmpty()
    assertThat(File(dir, "MemoriesEvalFormat.kt").readText()).contains(MemoriesLabelFormat.MAGIC)
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
      val byName = name.endsWith(".mcgmem") || name.startsWith("ricordi-veri")
      val byFolder = segments.dropLast(1).any { it.equals("eval-ricordi-privato", ignoreCase = true) || it.equals(".mycloudgallery", ignoreCase = true) }
      val inFixtures = relative.startsWith("tools/fixtures/memories/")
      val image = inFixtures && IMAGE_EXTENSIONS.any { name.endsWith(it) }
      if (byName || byFolder || image || startsWithMagic(file) || hasLabelLines(file) || (inFixtures && hasRealDate(file))) out += relative
    }
    return out
  }

  private fun startsWithMagic(file: File): Boolean {
    val head = ByteArray(MemoriesLabelFormat.MAGIC.length)
    val read = file.inputStream().use { it.read(head) }
    return read == head.size && String(head, Charsets.UTF_8) == MemoriesLabelFormat.MAGIC
  }

  /** Righe del formato dei giudizi in un file di testo (non binario, non enorme): un ricordo `R`, un giudizio `M`, una foto da togliere `F`. */
  private fun hasLabelLines(file: File): Boolean {
    val text = textOf(file) ?: return false
    return MEMORY_LINE.containsMatchIn(text) || JUDGEMENT_LINE.containsMatchIn(text) || FAULT_LINE.containsMatchIn(text)
  }

  /** Una data scritta per esteso (aaaa-mm-gg) in un file della cartella del generatore: le date della prova sono relative al giorno in cui si fa. */
  private fun hasRealDate(file: File): Boolean = textOf(file)?.let { REAL_DATE.containsMatchIn(it) } ?: false

  private fun textOf(file: File): String? {
    if (file.length() > MAX_SCANNED_BYTES) return null
    val bytes = file.readBytes()
    if (bytes.take(SNIFF_BYTES).any { it == 0.toByte() }) return null
    return String(bytes, Charsets.UTF_8)
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
    val REQUIRED_IGNORE_RULES = listOf(
      "*.mcgmem", "ricordi-veri*", "eval-ricordi-privato/", ".mycloudgallery/", "tools/fixtures/memories/out/",
      "tools/fixtures/memories/**/*.jpg", "tools/fixtures/memories/**/*.jpeg", "tools/fixtures/memories/**/*.png", "tools/fixtures/memories/**/*.webp",
    )

    val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif", ".mp4")
    const val MAX_SCANNED_BYTES = 4L * 1024 * 1024
    const val SNIFF_BYTES = 4096
    private const val ID = "[0-9a-hjkmnp-tv-z]{26}"
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    val MEMORY_LINE = Regex("(?m)^R\t$ID\t(?:otd|trip|event|person|month)\t[0-9]{4}-[0-9]{2}-[0-9]{2}\t")
    val JUDGEMENT_LINE = Regex("(?m)^M\t$ID\t[BCN]\t[-tsn]\\s*$")
    val FAULT_LINE = Regex("(?m)^F\t$ID\t$UUID\t[dmsxt]\\s*$")
    val REAL_DATE = Regex("(?<![0-9])(19|20)[0-9]{2}-[0-9]{2}-[0-9]{2}(?![0-9])")
  }
}
