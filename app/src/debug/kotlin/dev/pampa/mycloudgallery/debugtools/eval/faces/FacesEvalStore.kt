package dev.pampa.mycloudgallery.debugtools.eval.faces

import android.content.Context
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import java.io.File
import java.io.IOException

/**
 * SOLO debug (Fase 8, P11): dove stanno i file della valutazione delle persone, in `noBackupFilesDir/eval/faces/` (**fuori dai backup** automatici e dal backup
 * dell'app; dentro `eval/`, quindi l'azzeramento li toglie con il resto: `DeveloperTools.erasableDirs`, I11 della revisione di sicurezza della Fase 6).
 *
 * - `persone.mcgfac`: le etichette delle due sessioni (formato [FacesLabelFormat]): **localizzatori di volti**, non nomi; scritto con [StateFiles] (file
 *   temporaneo, forzatura su disco e rinomina atomica), cosi' una morte del processo a meta' scrittura non lascia un file mozzo;
 * - `report-NN.txt`: i resoconti, **solo numeri** (mai id, posti dei volti o nomi): sono quelli che si copiano in `docs/decisioni.md`.
 *
 * **Mai nel repository**: le etichette si portano sul PC solo se serve, con `adb exec-out run-as`, e si tengono in `%USERPROFILE%\.mycloudgallery\eval\faces\`.
 * Niente log: ne' un id ne' un posto passano da qui a `Log` (e `StateFiles` scrive solo il nome del file di stato).
 */
class FacesEvalStore(private val root: File) {

  private val labelsFile: File get() = File(root, FacesLabelFormat.FILE_NAME)

  /** Le etichette salvate, o un insieme vuoto se non ce ne sono. Un file rovinato lancia con il numero di riga ([FacesLabelFormat.parse]). */
  fun loadLabels(): FacesLabelSet {
    val text = StateFiles.readText(labelsFile) ?: return FacesLabelSet()
    return FacesLabelFormat.parse(text)
  }

  fun saveLabels(labels: FacesLabelSet) {
    StateFiles.writeText(labelsFile, FacesLabelFormat.serialize(labels))
  }

  /**
   * Scrive un resoconto di soli numeri come `report-NN.txt` (NN cresce senza orologio) e ne da' il nome. Se l'ultimo resoconto e' identico non ne scrive un altro
   * (chi tocca "Numeri" due volte non riempie la cartella).
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

  /** Toglie le etichette, i resoconti e i temporanei di questa cartella. Se un file non si riesce a togliere lancia `IOException` (non finge di aver finito). */
  fun wipe() {
    val victims = root.listFiles().orEmpty().filter { it.isFile && (it.name == labelsFile.name || REPORT.matches(it.name) || it.name.endsWith(".tmp")) }
    val stuck = victims.count { !StateFiles.delete(it) }
    if (stuck > 0) throw IOException("Non riesco a togliere $stuck file della valutazione.")
  }

  private fun number(name: String): Int = REPORT.matchEntire(name)?.groupValues?.get(1)?.toInt() ?: 0

  companion object {
    private val REPORT = Regex("report-(\\d{2,})\\.txt")

    /** La cartella di sempre dell'app: `noBackupFilesDir/eval/faces/`. */
    fun of(context: Context): FacesEvalStore = FacesEvalStore(File(File(context.noBackupFilesDir, "eval"), "faces"))
  }
}
