package dev.pampa.mycloudgallery.debugtools.eval.albums

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * SOLO debug (Fase 7, P12): dove stanno i file della valutazione degli album, in `noBackupFilesDir/eval/albums/` (**fuori dai backup** automatici e dal
 * backup dell'app; dentro `eval/`, quindi l'azzeramento li toglie con il resto: `DeveloperTools.erasableDirs`).
 *
 * - `viaggi.mcgalb`: le etichette dei viaggi (formato [AlbumsLabelFormat]); scritto in modo atomico (file temporaneo e poi rinomina), cosi' una morte del
 *   processo a meta' scrittura non lascia un file mozzo;
 * - `report-NN.txt`: i resoconti, **solo numeri** (mai titoli, date, luoghi o id): sono quelli che si copiano in `docs/decisioni.md`;
 * - `import/viaggi-sintetici.tsv`: l'elenco degli album dei viaggi sintetici (prova generale), portato dentro con `adb push` e `run-as`; la prova generale tiene
 *   le sue etichette in una cartella sua (`prova/`), mai mescolate a quelle dei viaggi veri.
 *
 * **Mai nel repository**: le etichette si portano sul PC con `adb exec-out run-as` (vedi `docs/decisioni.md`, "Fase 7 P12") e si tengono in
 * `%USERPROFILE%\.mycloudgallery\eval\albums\`. Niente log: ne' un titolo ne' un id passano da qui a `Log`.
 */
class AlbumsEvalStore(private val root: File, private val importDir: File = File(root, IMPORT_NAME)) {

  init {
    root.mkdirs()
  }

  private val labelsFile: File get() = File(root, AlbumsLabelFormat.FILE_NAME)

  /** Le etichette salvate, o un insieme vuoto se non ce ne sono. Un file rovinato lancia con il numero di riga ([AlbumsLabelFormat.parse]). */
  fun loadLabels(): AlbumsLabelSet = if (labelsFile.isFile) AlbumsLabelFormat.parse(labelsFile.readText(Charsets.UTF_8)) else AlbumsLabelSet()

  fun saveLabels(labels: AlbumsLabelSet) {
    writeAtomically(labelsFile, AlbumsLabelFormat.serialize(labels))
  }

  /** Scrive un resoconto di soli numeri come `report-NN.txt` (NN cresce senza orologio) e ne da' il nome. */
  fun saveReport(text: String): String {
    val next = (reports().maxOfOrNull { number(it) } ?: 0) + 1
    val name = "report-%02d.txt".format(next)
    writeAtomically(File(root, name), text)
    return name
  }

  /** I nomi dei resoconti gia' scritti, dal primo. */
  fun reports(): List<String> = root.listFiles { f -> REPORT.matches(f.name) }.orEmpty().map { it.name }.sorted()

  fun readReport(name: String): String? = File(root, name).takeIf { REPORT.matches(name) && it.isFile }?.readText(Charsets.UTF_8)

  /** Il file della prova generale portato dentro con `adb`, o null. */
  fun readImport(): String? = File(importDir, IMPORT_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8)

  /** Toglie le etichette e i resoconti di questa cartella (non il file portato dentro dal PC, non le altre cartelle). */
  fun wipe() {
    root.listFiles().orEmpty().filter { it.isFile && (it.name.startsWith(AlbumsLabelFormat.FILE_NAME) || REPORT.matches(it.name) || it.name.endsWith(".tmp")) }.forEach { it.delete() }
  }

  private fun number(name: String): Int = REPORT.matchEntire(name)?.groupValues?.get(1)?.toInt() ?: 0

  private fun writeAtomically(target: File, text: String) {
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, target.name + ".tmp")
    temporary.writeText(text, Charsets.UTF_8)
    if (!temporary.renameTo(target)) {
      // Su alcuni file system la rinomina sopra un file esistente fallisce: si toglie e si riprova.
      target.delete()
      if (!temporary.renameTo(target)) throw IOException("Non riesco a salvare ${target.name}.")
    }
  }

  companion object {
    private val REPORT = Regex("report-(\\d{2,})\\.txt")
    const val IMPORT_NAME: String = "import"
    const val IMPORT_FILE: String = "viaggi-sintetici.tsv"

    /** La cartella dei viaggi veri: `noBackupFilesDir/eval/albums/`. */
    fun of(context: Context): AlbumsEvalStore {
      val albums = File(File(context.noBackupFilesDir, "eval"), "albums")
      return AlbumsEvalStore(albums, File(albums, IMPORT_NAME))
    }

    /** La cartella della prova generale: `noBackupFilesDir/eval/albums/prova/`, con le sue etichette e i suoi resoconti; il file portato dentro e' lo stesso. */
    fun rehearsalOf(context: Context): AlbumsEvalStore {
      val albums = File(File(context.noBackupFilesDir, "eval"), "albums")
      return AlbumsEvalStore(File(albums, "prova"), File(albums, IMPORT_NAME))
    }
  }
}
