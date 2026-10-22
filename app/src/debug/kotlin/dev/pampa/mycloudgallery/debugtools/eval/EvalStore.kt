package dev.pampa.mycloudgallery.debugtools.eval

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * SOLO debug (Fase 6, P9): dove stanno i file della valutazione, in `noBackupFilesDir/eval/` (**fuori dai backup** automatici e dal backup dell'app).
 *
 * - `labels.mcgeval`: le etichette (query, attese per query, documenti), formato [LabelFormat]; scritto in modo atomico (file temporaneo e poi
 *   rinomina), cosi' una morte del processo a meta' scrittura non lascia un file mozzo;
 * - `report-NN.txt`: i resoconti, **solo numeri** (query, chiavi e nomi mai); sono quelli che si copiano in `docs/decisioni.md`;
 * - `ocr/`: il set OCR sintetico copiato con `adb push` (`manifest.tsv`, `immagini/`) e il `letto.tsv` che la lettura di prova scrive.
 *
 * **Mai nel repository**: le etichette si esportano con `adb` in `%USERPROFILE%\.mycloudgallery\eval\` (vedi il resoconto di P9). Niente log: ne' un
 * nome di query ne' una chiave passano da qui a `Log`.
 */
class EvalStore(private val root: File) {

  init {
    root.mkdirs()
  }

  private val labelsFile: File get() = File(root, LABELS_NAME)

  /** La cartella del set OCR sul telefono. */
  val ocrDir: File get() = File(root, "ocr")

  /** Le etichette salvate, o un insieme vuoto se non ce ne sono. Un file rovinato lancia con il numero di riga ([LabelFormat.parse]). */
  fun loadLabels(): LabelSet = if (labelsFile.isFile) LabelFormat.parse(labelsFile.readText(Charsets.UTF_8)) else LabelSet()

  fun saveLabels(labels: LabelSet) {
    writeAtomically(labelsFile, LabelFormat.serialize(labels))
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

  fun writeOcrRead(text: String) {
    ocrDir.mkdirs()
    writeAtomically(File(ocrDir, "letto.tsv"), text)
  }

  fun writeOcrReport(text: String) {
    ocrDir.mkdirs()
    writeAtomically(File(ocrDir, "ocr-resoconto.txt"), text)
  }

  /** Toglie tutto cio' che la valutazione ha scritto (etichette, resoconti, set OCR): a fine misura. */
  fun wipe() {
    root.deleteRecursively()
    root.mkdirs()
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
    const val LABELS_NAME: String = "labels.mcgeval"
    private val REPORT = Regex("report-(\\d{2,})\\.txt")

    /** La cartella di sempre dell'app: `noBackupFilesDir/eval/`. */
    fun of(context: Context): EvalStore = EvalStore(File(context.noBackupFilesDir, "eval"))
  }
}
