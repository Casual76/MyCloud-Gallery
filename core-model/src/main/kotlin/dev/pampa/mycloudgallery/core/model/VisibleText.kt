package dev.pampa.mycloudgallery.core.model

/**
 * Se un testo **si vede** (Fase 8, correzioni D, R9): ha almeno un carattere che non e' uno spazio, un carattere di controllo o di formato (il giunto ZWJ,
 * la direzione), un segno combinante (i selettori di variante degli emoji, gli accenti da soli) o un codice non assegnato. Un nome fatto solo di quelli appare vuoto:
 * `PersonNames` non lo ammette e la cronologia non lo cerca come testo. Un emoji ("❤"), un simbolo ("?") o una lettera di qualunque alfabeto si vedono.
 *
 * Puro Kotlin: si guarda un punto di codice alla volta (gli emoji fuori dal piano base non si rompono).
 */
object VisibleText {

  /** Vero se [text] ha almeno un carattere che si vede. */
  fun hasVisible(text: String): Boolean {
    var i = 0
    while (i < text.length) {
      val codePoint = text.codePointAt(i)
      i += Character.charCount(codePoint)
      if (isShown(codePoint)) return true
    }
    return false
  }

  private fun isShown(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.CONTROL, Character.FORMAT, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK, Character.UNASSIGNED, Character.SURROGATE,
    Character.PRIVATE_USE,
    -> false
    else -> true
  }
}
