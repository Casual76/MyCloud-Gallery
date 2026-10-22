package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.OcrText

/**
 * Il corpo `ocr` che si salva e si pubblica, da cio' che il lettore ha trovato (§2.2, riga "Controlli"): le parole si puliscono con le
 * regole del formato (`OcrText.cleanWord`), le righe stanno nei tetti del corpo (`AiSegments.admits`: mai un corpo che gli altri rifiuterebbero).
 * Lo usano il calcolo ([MediaComputer]) e la lettura al volo del viewer (Fase 6, P11b): le due strade producono lo stesso corpo.
 */
object OcrBodies {

  /** Il corpo di [raw] letto da [source], o null se nemmeno senza righe entra nei tetti. Senza righe valide il corpo e' vuoto ("letto, nessun testo"). */
  fun of(source: OcrSource, raw: List<OcrLine>, recipe: AiRecipe = AiRecipe.CURRENT): OcrBody? {
    val lines = raw.mapNotNull(::clean).take(AiLimits.MAX_OCR_LINES).toMutableList()
    while (true) {
      val body = OcrBody(OcrEngineCode.MLKIT_V2_LATIN, recipe.ocrVersion, source, lines)
      if (AiSegments.admits(body)) return body
      // Oltre il tetto dei byte del corpo: si tolgono le ultime righe (le prime sono quelle in alto).
      if (lines.isEmpty()) return null
      lines.removeAt(lines.lastIndex)
    }
  }

  /** Una riga com'e' nel formato: parole pulite (`OcrText.cleanWord`), le parole nulle tolte con i loro estremi. */
  private fun clean(line: OcrLine): OcrLine? {
    val pieces = line.text.split(' ')
    if (pieces.size != line.words.size) return null
    val kept = pieces.zip(line.words).mapNotNull { (word, span) -> OcrText.cleanWord(word)?.let { it to span } }
      .take(AiLimits.MAX_WORDS_PER_LINE)
    if (kept.isEmpty()) return null
    var text = kept.joinToString(" ") { it.first }
    var spans = kept.map { it.second }
    while (text.toByteArray(Charsets.UTF_8).size > AiLimits.MAX_LINE_BYTES && spans.size > 1) {
      spans = spans.dropLast(1)
      text = text.substringBeforeLast(' ')
    }
    return runCatching { OcrLine(line.box, line.angle, spans, text) }.getOrNull()?.takeIf { OcrText.isAllowed(it.text) }
  }
}
