package dev.pampa.mycloudgallery.feature.viewer.livetext

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Righe di prova **fisicamente coerenti**: il riquadro e' il minimo rettangolo dritto che contiene la riga ruotata (come lo da' la
 * lettura vera). La riga inclinata di `LiveTextFixtures.tilted` (P0) ha un riquadro troppo basso per i suoi 12 gradi: per le prove
 * dell'inclinazione serve questa.
 */
internal object LiveTextTestLines {

  /**
   * @param cx centro, normalizzato sull'immagine.
   * @param cy centro, normalizzato sull'immagine.
   * @param length lunghezza della riga in altezze dell'immagine.
   * @param thickness spessore della riga in altezze dell'immagine.
   * @param angle gradi, positivi in senso orario sullo schermo.
   * @param aspect larghezza/altezza dell'immagine.
   */
  fun line(text: String, cx: Float, cy: Float, length: Float, thickness: Float, angle: Int, aspect: Float): ViewerTextLine {
    val radians = angle * PI.toFloat() / 180f
    val c = abs(cos(radians))
    val s = abs(sin(radians))
    val width = length * c + thickness * s
    val height = length * s + thickness * c
    val box = ViewerTextBox(cx - width / 2f / aspect, cy - height / 2f, cx + width / 2f / aspect, cy + height / 2f)
    val words = text.split(' ')
    var cursor = 0
    val spans = words.map { word ->
      val start = cursor / text.length.toFloat()
      cursor += word.length
      val end = cursor / text.length.toFloat()
      cursor += 1
      ViewerWordSpan(start, end)
    }
    return ViewerTextLine(box, angle, spans, text)
  }

  const val ASPECT: Float = 16f / 9f

  /** Una riga dritta, al centro dell'immagine: due parole. */
  val centered = ViewerTextLayout(listOf(line("Via Roma", 0.5f, 0.5f, 0.30f, 0.05f, 0, ASPECT)))

  /** Una riga a 12 gradi (scende verso destra), con un riquadro coerente. */
  val tilted = ViewerTextLayout(listOf(line("Totale euro 23,40", 0.45f, 0.45f, 0.70f, 0.06f, 12, ASPECT)))

  /** Una riga a -30 gradi (sale verso destra). */
  val steep = ViewerTextLayout(listOf(line("Uscita nord est", 0.5f, 0.5f, 0.50f, 0.06f, -30, ASPECT)))

  /** Tre righe dritte, la seconda inclinata di 8 gradi: un testo su piu' righe. */
  val multiline = ViewerTextLayout(
    listOf(
      line("Prova di lettura", 0.32f, 0.38f, 0.40f, 0.05f, 0, ASPECT),
      line("su piu' righe di testo", 0.36f, 0.46f, 0.52f, 0.05f, 8, ASPECT),
      line("fine", 0.22f, 0.55f, 0.14f, 0.05f, 0, ASPECT),
    ),
  )
}
