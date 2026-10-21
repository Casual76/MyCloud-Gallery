package dev.pampa.mycloudgallery.feature.viewer.livetext

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef

// Il TESTO DAL VIVO nel viewer (docs/fase6-progetto.md §2.10, D42, D43, D45, D46; risposta Q9). Porte e stati di P0; la UI
// (evidenziazione, maniglie, barra, pulsante "Testo", foglio "Testo nella foto", azioni di TalkBack, arbitro della pressione
// prolungata) e' di P12.
//
// Il viewer NON vede core-ai ne' core-search (§1.1, Phase6ArchitectureTest): queste porte le definisce lui e le lega :app (P11) al
// testo letto dell'indice locale (`MediaText` di core-search) e al motore OCR (solo se la lettura e' attiva qui). Il testo copiato
// non va mai nei log (D40).

/** Un rettangolo normalizzato (0..1) sull'immagine **raddrizzata** (le coordinate del corpo `ocr`, D43). */
data class ViewerTextBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Gli estremi di una parola lungo la riga, in frazioni della lunghezza della riga (0..1). */
data class ViewerWordSpan(val start: Float, val end: Float)

/** Una riga di testo letto: riquadro, inclinazione (gradi), parole ed estremi, testo. */
data class ViewerTextLine(val box: ViewerTextBox, val angleDegrees: Int, val words: List<ViewerWordSpan>, val text: String) {
  /** Mai il testo (D40). */
  override fun toString(): String = "ViewerTextLine(${words.size} parole)"
}

/** Il testo letto di un media, righe nell'ordine di lettura (dall'alto, poi da sinistra). */
data class ViewerTextLayout(val lines: List<ViewerTextLine>) {
  val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * Il testo letto gia' nell'indice locale (D42, D43): da QUALUNQUE dispositivo della famiglia, anche dove la lettura e' spenta. Null
 * = nessun testo letto per quel media (non ancora analizzato, o nessun dispositivo con la lettura attiva).
 */
fun interface ViewerTextSource {
  suspend fun layout(id: MediaId): ViewerTextLayout?
}

/**
 * La lettura al volo (D42, E-V9): SOLO dove la lettura del testo e' attiva su questo dispositivo ([available]). Il risultato segue il
 * percorso normale (salvato, pubblicato se il file e' in una share pubblicabile). Con [available] falso il viewer non la chiama mai
 * (il fake fallisce se chiamato, §4.3).
 */
interface ViewerTextReader {
  val available: Boolean

  suspend fun readNow(id: MediaId, ref: MediaRef): ViewerTextLayout?
}

/** Una posizione nel testo: riga e parola. */
data class TextPosition(val line: Int, val word: Int)

/** Una selezione (estremi inclusi, nell'ordine di lettura). */
data class TextSelection(val from: TextPosition, val to: TextPosition)

/** Lo stato del testo dal vivo (§1.5, `LiveTextState`; l'aspetto lo decide G3). */
sealed interface LiveTextState {
  data object Idle : LiveTextState

  /** Pulsante "Testo": tutte le righe evidenziate con un velo leggero. */
  data class Highlighted(val layout: ViewerTextLayout) : LiveTextState

  /** Maniglie in movimento. */
  data class Selecting(val layout: ViewerTextLayout, val selection: TextSelection) : LiveTextState

  /** Barra "Copia · Seleziona tutto · Condividi testo". */
  data class Actions(val layout: ViewerTextLayout, val selection: TextSelection) : LiveTextState

  /** Il foglio "Testo nella foto" (anche la via accessibile, D46). */
  data class Sheet(val layout: ViewerTextLayout) : LiveTextState

  /** Lettura al volo in corso ("Leggo il testo…", al piu' 2 s). */
  data object ReadingNow : LiveTextState
}
