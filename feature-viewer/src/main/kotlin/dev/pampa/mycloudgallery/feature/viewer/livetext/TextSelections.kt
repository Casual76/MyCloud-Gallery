package dev.pampa.mycloudgallery.feature.viewer.livetext

import kotlin.math.min

/**
 * La selezione del testo dal vivo come funzioni pure (D43): le posizioni sono (riga, parola) nell'ordine di lettura (dall'alto, poi da
 * sinistra: l'ordine delle righe del [ViewerTextLayout]). Gli estremi di una selezione sono **inclusi**: una parola sola e' `from == to`.
 *
 * Mai nei log: il testo selezionato passa solo da [text] e va dritto agli appunti, al foglio o alla condivisione (D40).
 */
internal object TextSelections {

  /** Le parole di una riga: il testo diviso dagli spazi (P2 garantisce che nessuna sia vuota). */
  fun wordsOf(line: ViewerTextLine): List<String> = line.text.split(' ')

  fun compare(a: TextPosition, b: TextPosition): Int = if (a.line != b.line) a.line.compareTo(b.line) else a.word.compareTo(b.word)

  /** La selezione fra due posizioni qualunque, messe nell'ordine di lettura. */
  fun between(a: TextPosition, b: TextPosition): TextSelection = if (compare(a, b) <= 0) TextSelection(a, b) else TextSelection(b, a)

  /** Una parola sola. */
  fun word(position: TextPosition): TextSelection = TextSelection(position, position)

  /** Tutto il testo, dalla prima all'ultima parola; null se il testo e' vuoto. */
  fun all(geometry: LiveTextGeometry): TextSelection? {
    val first = geometry.first() ?: return null
    val last = geometry.last() ?: return null
    return TextSelection(first, last)
  }

  /** Vero se [selection] copre tutto il testo. */
  fun isAll(geometry: LiveTextGeometry, selection: TextSelection): Boolean = all(geometry) == selection

  /** Vero se la parola [position] sta dentro [selection]. */
  fun contains(selection: TextSelection, position: TextPosition): Boolean =
    compare(selection.from, position) <= 0 && compare(position, selection.to) <= 0

  /**
   * Le parole selezionate di ogni riga: per ogni riga toccata (indice di riga, prima parola, ultima parola), nell'ordine di lettura. Una
   * riga a meta' ha solo le parole dentro la selezione; le righe di mezzo sono intere.
   */
  fun spans(geometry: LiveTextGeometry, selection: TextSelection): List<Triple<Int, Int, Int>> {
    val from = selection.from
    val to = selection.to
    val result = ArrayList<Triple<Int, Int, Int>>()
    for (line in from.line..min(to.line, geometry.layout.lines.lastIndex)) {
      val count = geometry.wordCount(line)
      if (count == 0) continue
      val first = if (line == from.line) from.word.coerceIn(0, count - 1) else 0
      val last = if (line == to.line) to.word.coerceIn(0, count - 1) else count - 1
      if (first <= last) result += Triple(line, first, last)
    }
    return result
  }

  /** Il testo della selezione: parole separate da uno spazio, righe da un a capo. */
  fun text(layout: ViewerTextLayout, selection: TextSelection): String {
    val geometry = LiveTextGeometry(layout, 1f)
    return spans(geometry, selection).joinToString("\n") { (line, first, last) ->
      wordsOf(layout.lines[line]).subList(first, last + 1).joinToString(" ")
    }
  }

  /** Tutto il testo della foto, una riga per riga: cio' che mostra il foglio "Testo nella foto" e copia "Copia tutto". */
  fun allText(layout: ViewerTextLayout): String = layout.lines.joinToString("\n") { it.text }
}
