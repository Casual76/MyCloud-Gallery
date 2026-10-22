package dev.pampa.mycloudgallery.debugtools.eval

import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import dev.pampa.mycloudgallery.core.model.OcrSource
import java.io.File

/**
 * SOLO debug (Fase 6, P9): la **lettura di prova** del set OCR sintetico con il lettore vero (ML Kit dietro il cancello della lettura del testo,
 * D41). Il set lo genera `tools/fixtures/generate-ocr.ps1` sul PC e si copia sul telefono con `adb push` in `noBackupFilesDir/eval/ocr/`
 * (`manifest.tsv` e `immagini/`); la corsa legge ogni immagine **come la legge il calcolo vero** (`ModelInputs.forOcr`: stessa difesa dell'ingresso,
 * stesso JPEG q90 a 1024 o WebP/JPEG a 2048, mai il decodificatore nativo su byte non controllati), scrive `letto.tsv` e il resoconto dell'errore
 * per carattere e della ricerca per parola, di soli numeri.
 *
 * Con la lettura del testo spenta il lettore lancia (`TEXT_READING_OFF`): la corsa si ferma subito e non inizializza ML Kit. Non e' una misura che si
 * fa sulla JVM: il lettore finto sta in `tools/fixtures` (prova del solo resoconto).
 */
class EvalOcrRunner(private val reader: TextReader, private val store: EvalStore) {

  /** L'esito di una corsa: quante immagini lette, quante rifiutate (ingresso non valido) e il resoconto. */
  class Result(val read: Int, val refused: Int, val failed: Int, val report: String)

  /** Il motivo per cui il set non si puo' leggere (manca, o e' vuoto), o null se si puo' partire. */
  fun problem(): String? {
    val manifest = File(store.ocrDir, "manifest.tsv")
    return when {
      !manifest.isFile -> "Manca il set OCR: copia MCG-ocr in noBackupFilesDir/eval/ocr con adb (vedi il resoconto di P9)."
      parse(manifest).isEmpty() -> "Il manifesto del set OCR e' vuoto."
      else -> null
    }
  }

  /** Legge tutte le immagini; [onProgress] riceve (fatte, totali). Una cancellazione passa com'e'; il lettore spento ferma la corsa. */
  suspend fun run(readerName: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
    val cases = parse(File(store.ocrDir, "manifest.tsv"))
    val read = LinkedHashMap<String, String>()
    var refused = 0
    var failed = 0
    cases.forEachIndexed { i, c ->
      val file = File(store.ocrDir, "immagini/${c.id}.jpg")
      val text = if (!file.isFile) {
        failed++
        ""
      } else {
        val image = ModelInputs.forOcr(file.readBytes(), if (c.width >= HQ_WIDTH) OcrSource.HQ else OcrSource.ANALYSIS)
        if (image == null) {
          refused++
          ""
        } else {
          // Un'eccezione (lettura spenta, motore guasto, cancellazione) ferma la corsa: le altre immagini darebbero lo stesso.
          reader.read(image).lines.joinToString("\n") { it.text }
        }
      }
      read[c.id] = text
      onProgress(i + 1, cases.size)
    }
    store.writeOcrRead(read.entries.joinToString("") { (id, text) -> EvalOcr.formatRead(id, text) })
    val report = EvalOcr.report(cases, read, readerName)
    store.writeOcrReport(report)
    return Result(read.size - refused - failed, refused, failed, report)
  }

  private fun parse(manifest: File): List<OcrCase> = EvalOcr.parseManifest(manifest.readText(Charsets.UTF_8))

  private companion object {
    /** Da questa larghezza in su l'immagine e' l'anteprima HQ (2048 px), sotto quella di analisi (1024 px). */
    const val HQ_WIDTH = 1536
  }
}
