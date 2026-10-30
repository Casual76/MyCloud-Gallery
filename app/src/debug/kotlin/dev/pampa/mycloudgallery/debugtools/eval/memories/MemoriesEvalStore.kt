package dev.pampa.mycloudgallery.debugtools.eval.memories

import android.content.Context
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import java.io.File
import java.io.IOException

/**
 * SOLO debug (Fase 9, P11): dove stanno i file della valutazione dei ricordi, in `noBackupFilesDir/eval/memories/` (**fuori dai backup** automatici e dal backup
 * dell'app; dentro `eval/`, quindi l'azzeramento li toglie con il resto: `DeveloperTools.erasableDirs`).
 *
 * - `ricordi.mcgmem`: la sessione (i ricordi scelti) e i giudizi (formato [MemoriesLabelFormat]); scritto con [StateFiles] (file temporaneo, forzatura su disco e rinomina
 *   atomica), cosi' una morte del processo a meta' scrittura non lascia un file mozzo;
 * - `report-NN.txt`: i resoconti, **solo numeri** (mai titoli, giorni, luoghi o id): sono quelli che si copiano in `docs/decisioni.md`.
 *
 * **Mai nel repository**: i giudizi si portano sul PC solo se serve, con `adb exec-out run-as`, e si tengono in `%USERPROFILE%\.mycloudgallery\eval\memories\`.
 * Niente log: ne' un titolo ne' un id passano da qui a `Log` (e `StateFiles` scrive solo il nome del file di stato).
 */
class MemoriesEvalStore(private val root: File) {

  private val labelsFile: File get() = File(root, MemoriesLabelFormat.FILE_NAME)

  /** I giudizi salvati, o un insieme vuoto se non ce ne sono. Un file rovinato lancia con il numero di riga ([MemoriesLabelFormat.parse]). */
  fun loadLabels(): MemoriesLabelSet {
    val text = StateFiles.readText(labelsFile) ?: return MemoriesLabelSet()
    return MemoriesLabelFormat.parse(text)
  }

  fun saveLabels(labels: MemoriesLabelSet) {
    StateFiles.writeText(labelsFile, MemoriesLabelFormat.serialize(labels))
  }

  /**
   * Scrive un resoconto di soli numeri come `report-NN.txt` (NN cresce senza orologio) e ne da' il nome. Se l'ultimo resoconto e' identico non ne scrive un altro (chi
   * tocca "Numeri" due volte non riempie la cartella).
   */
  fun saveReport(text: String): String {
    reports().lastOrNull()?.let { last -> if (readReport(last) == text) return last }
    val next = (reports().maxOfOrNull { number(it) } ?: 0) + 1
    val name = "report-%02d.txt".format(next)
    StateFiles.writeText(File(root, name), text)
    return name
  }

  /** I nomi dei resoconti gia' scritti, dal primo. */
  fun reports(): List<String> = root.listFiles { f -> REPORT.matches(f.name) }.orEmpty().map { it.name }.sorted()

  fun readReport(name: String): String? = if (REPORT.matches(name)) StateFiles.readText(File(root, name)) else null

  /** Toglie i giudizi, i resoconti e i temporanei di questa cartella. Se un file non si riesce a togliere lancia `IOException` (non finge di aver finito). */
  fun wipe() {
    val victims = root.listFiles().orEmpty().filter { it.isFile && (it.name == labelsFile.name || REPORT.matches(it.name) || it.name.endsWith(".tmp")) }
    val stuck = victims.count { !StateFiles.delete(it) }
    if (stuck > 0) throw IOException("Non riesco a togliere $stuck file della valutazione.")
  }

  private fun number(name: String): Int = REPORT.matchEntire(name)?.groupValues?.get(1)?.toInt() ?: 0

  companion object {
    private val REPORT = Regex("report-(\\d{2,})\\.txt")

    /** La cartella di sempre dell'app: `noBackupFilesDir/eval/memories/`. */
    fun of(context: Context): MemoriesEvalStore = MemoriesEvalStore(File(File(context.noBackupFilesDir, "eval"), "memories"))
  }
}
