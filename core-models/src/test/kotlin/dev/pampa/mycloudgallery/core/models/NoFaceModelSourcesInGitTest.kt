package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Nessun ONNX, ambiente Python o ritratto di prova nel repository** (Fase 8, P1; `docs/fase8-progetto.md` §8, riga P1 del §4.4). I file
 * ONNX di OpenCV Zoo, l'ambiente di conversione e i ritratti di pubblico dominio stanno fuori, in una cartella a parte
 * (`tools/models/faces/LEGGIMI.md`); nel repository entrano solo gli script, `requirements.txt` con le impronte e i due `.tflite` scelti
 * in `core-ai/src/main/assets/faces/`.
 *
 * Guarda i file **tracciati da git** (`git ls-files`, quindi anche quelli in staging):
 *  - nessun file `.onnx` e nessun `.pyc` o `.pyd`;
 *  - nessun percorso con una cartella `venv`, `.venv`, `site-packages` o `__pycache__`;
 *  - nessuna immagine con il nome di un ritratto di prova (`ritratto-NN`) o dentro una cartella `ritratti/`;
 *  - in `tools/models/faces/` solo script e testo (`.py`, `.ps1`, `.md`, `.json`, `.txt`), ciascuno sotto i 256 KB;
 *  - i `.tflite` solo nelle cartelle degli asset di `core-ai` (`cutout/` e `faces/`);
 *  - le regole del `.gitignore` che li escludono.
 * Se `git` non c'e' si guarda la cartella di lavoro intera (meno le cartelle generate). Il controllo e' provato su file veri in una cartella
 * temporanea: se smettesse di vedere le violazioni, il test fallirebbe.
 */
class NoFaceModelSourcesInGitTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun nelRepositoryNonCiSonoOnnxAmbientiPythonNeRitratti() {
    val root = repositoryRoot()
    val files = trackedFiles(root)
    assertThat(files).isNotEmpty()
    assertThat(violations(root, files)).isEmpty()
  }

  @Test
  fun ilGitignoreEscludeOnnxAmbientiERitratti() {
    val ignore = File(repositoryRoot(), ".gitignore").readLines().map { it.trim() }
    for (rule in REQUIRED_IGNORE_RULES) assertThat(ignore).contains(rule)
  }

  @Test
  fun gliStrumentiDelleConversioniCiSonoEPesanoPoco() {
    val dir = File(repositoryRoot(), "tools/models/faces")
    val names = dir.listFiles().orEmpty().map { it.name }
    assertThat(names).containsAtLeast("requirements.txt", "convert.py", "parity.py", "choose.py", "fingerprints.json", "setup-env.ps1")
    // requirements.txt: ogni pacchetto e' fissato con una versione e almeno una impronta (`--require-hashes` non funziona senza).
    val lines = File(dir, "requirements.txt").readLines()
    val packages = lines.filter { Regex("^[A-Za-z0-9_.-]+==[0-9]").containsMatchIn(it) }
    assertThat(packages.size).isAtLeast(20)
    assertThat(lines.count { it.trim().startsWith("--hash=sha256:") }).isAtLeast(packages.size)
    assertThat(lines.filter { it.trim().startsWith("--hash=") }.all { Regex("^--hash=sha256:[0-9a-f]{64}( \\\\)?$").matches(it.trim()) }).isTrue()
  }

  @Test
  fun ilControlloVedeLeViolazioni() {
    val root = tmp.newFolder("repo")
    fun file(path: String, text: String = "x") = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
    val good = listOf(
      file("tools/models/faces/convert.py"),
      file("tools/models/faces/requirements.txt"),
      file("core-ai/src/main/assets/faces/yunet.tflite"),
      file("core-ai/src/main/assets/cutout/magic_touch.tflite"),
      file("docs/nota.md"),
    )
    val bad = listOf(
      file("modelli/face_detection_yunet_2023mar.onnx"),
      file("tools/models/faces/venv/Lib/site-packages/numpy/x.py"),
      file("tools/models/faces/__pycache__/common.cpython-311.pyc"),
      file("tools/models/faces/ritratto-07.jpg"),
      file("tools/fixtures/faces/ritratti/prova.png"),
      file("tools/models/faces/yunet.tflite"),
      file("tools/models/faces/grande.json", "x".repeat(300 * 1024)),
      file("tools/models/faces/immagine.png"),
      file("app/src/main/assets/dati.tflite"),
    )
    assertThat(violations(root, good)).isEmpty()
    val found = violations(root, bad)
    assertThat(found).hasSize(bad.size)
    // Le violazioni dicono il percorso, mai il contenuto.
    assertThat(found.joinToString("\n")).doesNotContain("xxxx")
  }

  // --- Il controllo ----------------------------------------------------------------------------------------------------------------

  private fun violations(root: File, files: List<File>): List<String> {
    val out = ArrayList<String>()
    for (file in files) {
      if (!file.isFile) continue
      val relative = file.relativeTo(root).invariantPath()
      val segments = relative.split('/')
      val name = segments.last().lowercase()
      val folders = segments.dropLast(1).map { it.lowercase() }
      val extension = name.substringAfterLast('.', "")
      val isImage = extension in IMAGE_EXTENSIONS
      val bad = extension in setOf("onnx", "pyc", "pyd") ||
        folders.any { it in FORBIDDEN_FOLDERS } ||
        (isImage && (name.startsWith("ritratto-") || "ritratti" in folders)) ||
        (relative.startsWith("tools/models/faces/") && (extension !in TOOL_EXTENSIONS || file.length() > MAX_TOOL_BYTES)) ||
        (extension == "tflite" && !(relative.startsWith("core-ai/src/main/assets/cutout/") || relative.startsWith("core-ai/src/main/assets/faces/")))
      if (bad) out += relative
    }
    return out
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
    val FORBIDDEN_FOLDERS = setOf("venv", ".venv", "site-packages", "__pycache__")
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "tif", "tiff", "bmp")
    val TOOL_EXTENSIONS = setOf("py", "ps1", "md", "json", "txt")
    const val MAX_TOOL_BYTES = 256L * 1024

    /** Le regole che ci devono essere nel `.gitignore` (riga per riga, esatte). */
    val REQUIRED_IGNORE_RULES = listOf("*.onnx", "venv/", ".venv/", "__pycache__/", "*.pyc", "ritratto-*", "ritratti/*.jpg", "ritratti/*.jpeg", "ritratti/*.png")
  }
}
