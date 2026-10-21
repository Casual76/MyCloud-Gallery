package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// La geometria del testo dal vivo (docs/fase6-progetto.md §2.10, D43). Funzioni pure, nessun Compose di disegno: le prove su JVM la
// guardano da sole.
//
// **Lo spazio.** Le righe arrivano con coordinate normalizzate (0..1) sull'immagine raddrizzata, che non e' quadrata: 0,1 in orizzontale
// e 0,1 in verticale sono distanze diverse. Qui si lavora in uno spazio con l'altezza dell'immagine uguale a 1 (la x si moltiplica per
// la proporzione larghezza/altezza), dove gli angoli e le distanze tornano quelli veri. [LiveTextGeometry.toImage] riporta al
// normalizzato.
//
// **L'inclinazione.** E' in gradi, positiva in senso orario sullo schermo (la riga scende verso destra), come la pubblica la lettura
// (P2/P4: da verificare sul telefono, F65). Il riquadro di una riga e' il minimo rettangolo *dritto* che la contiene.

/**
 * Il telaio di una riga: centro, direzione, lunghezza e spessore, ricavati dal riquadro e dall'inclinazione. Il rettangolo ruotato di
 * lunghezza L e spessore T ha un riquadro dritto di larghezza `L·|cos| + T·|sin|` e altezza `L·|sin| + T·|cos|`: si risolve il
 * sistema; vicino a 45 gradi e' mal condizionato e si ripiega sulla dimensione del riquadro piu' vicina alla direzione.
 */
internal class LineFrame(box: ViewerTextBox, angleDegrees: Int, aspect: Float) {
  private val boxWidth = (box.right - box.left).coerceAtLeast(MIN_SIDE) * aspect
  private val boxHeight = (box.bottom - box.top).coerceAtLeast(MIN_SIDE)
  private val radians = angleDegrees.coerceIn(-90, 90) * PI.toFloat() / 180f

  val center: Offset = Offset((box.left + box.right) / 2f * aspect, (box.top + box.bottom) / 2f)

  /** Il verso della riga (da sinistra a destra lungo il testo). */
  val dir: Offset = Offset(cos(radians), sin(radians))

  /** Il verso "sotto il testo" (perpendicolare, verso il basso quando la riga e' dritta). */
  val normal: Offset = Offset(-dir.y, dir.x)

  val length: Float
  val thickness: Float

  init {
    val c = abs(cos(radians))
    val s = abs(sin(radians))
    val det = c * c - s * s
    var solvedLength = -1f
    var solvedThickness = -1f
    if (abs(det) > WELL_CONDITIONED) {
      solvedLength = (boxWidth * c - boxHeight * s) / det
      solvedThickness = (boxHeight * c - boxWidth * s) / det
    }
    if (solvedLength <= 0f || solvedThickness <= 0f) {
      // Ripiego (vicino a 45 gradi, o un riquadro troppo basso per quell'inclinazione): la riga prende la dimensione del riquadro piu'
      // vicina alla sua direzione, proiettata; lo spessore e' il resto, mai meno di un quarto dell'altra dimensione.
      if (c >= s) {
        solvedLength = boxWidth / max(c, EPSILON)
        solvedThickness = max(boxHeight - solvedLength * s, boxHeight * MIN_THICKNESS_RATIO)
      } else {
        solvedLength = boxHeight / max(s, EPSILON)
        solvedThickness = max(boxWidth - solvedLength * c, boxWidth * MIN_THICKNESS_RATIO)
      }
    }
    length = max(solvedLength, MIN_SIDE)
    thickness = max(solvedThickness, MIN_SIDE)
  }

  /** Il punto della riga a [u] (0 = inizio, 1 = fine) e [v] (-0,5 = sopra, +0,5 = sotto il testo). */
  fun point(u: Float, v: Float): Offset = center + dir * ((u - 0.5f) * length) + normal * (v * thickness)

  /** L'inverso di [point]: x = frazione lungo la riga, y = frazione di spessore. */
  fun local(p: Offset): Offset {
    val d = p - center
    return Offset((d.x * dir.x + d.y * dir.y) / length + 0.5f, (d.x * normal.x + d.y * normal.y) / thickness)
  }

  private companion object {
    const val MIN_SIDE = 1e-4f
    const val WELL_CONDITIONED = 0.25f
    const val EPSILON = 1e-4f
    const val MIN_THICKNESS_RATIO = 0.25f
  }
}

/**
 * Le righe di un testo letto in uno spazio con l'immagine alta 1 ([aspect] = larghezza/altezza dell'immagine raddrizzata): dove cade un
 * punto, qual e' la parola piu' vicina, dove stanno le maniglie e i rettangoli da evidenziare. Una riga con parole che non tornano
 * con il testo (non dovrebbe accadere: P2 lo rifiuta) usa il numero piu' piccolo.
 */
internal class LiveTextGeometry(val layout: ViewerTextLayout, val aspect: Float) {

  private val safeAspect = if (aspect > 0f) aspect else 1f

  private val frames: List<LineFrame> = layout.lines.map { LineFrame(it.box, it.angleDegrees, safeAspect) }

  /** Quante parole ha la riga [line] (le estremita' pubblicate, ma non piu' di quelle del testo). */
  fun wordCount(line: Int): Int = layout.lines.getOrNull(line)?.let { min(it.words.size, TextSelections.wordsOf(it).size) } ?: 0

  /** Il primo punto del testo, in ordine di lettura, e l'ultimo. Null se non ce ne sono. */
  fun first(): TextPosition? = layout.lines.indices.firstOrNull { wordCount(it) > 0 }?.let { TextPosition(it, 0) }

  fun last(): TextPosition? = layout.lines.indices.lastOrNull { wordCount(it) > 0 }?.let { TextPosition(it, wordCount(it) - 1) }

  /** Dal normalizzato (0..1 sull'immagine) allo spazio di lavoro, e ritorno. */
  fun toWork(p: Offset): Offset = Offset(p.x * safeAspect, p.y)

  fun toImage(p: Offset): Offset = Offset(p.x / safeAspect, p.y)

  /**
   * La parola sotto [point] (normalizzato), con una tolleranza [tolerance] in altezze dell'immagine intorno alla parola (il dito non
   * e' preciso); null se nessuna parola e' abbastanza vicina. A parita' vince la prima nell'ordine di lettura.
   */
  fun wordAt(point: Offset, tolerance: Float): TextPosition? {
    val nearest = nearest(point) ?: return null
    return if (nearest.second <= tolerance) nearest.first else null
  }

  /** La parola piu' vicina a [point] (normalizzato) in tutto il testo, senza limiti di distanza; null se non c'e' testo. */
  fun nearestWord(point: Offset): TextPosition? = nearest(point)?.first

  private fun nearest(point: Offset): Pair<TextPosition, Float>? {
    val p = toWork(point)
    var best: Pair<TextPosition, Float>? = null
    for (line in layout.lines.indices) {
      val frame = frames[line]
      val local = frame.local(p)
      val spans = layout.lines[line].words
      for (word in 0 until wordCount(line)) {
        val span = spans[word]
        val du = max(0f, max(span.start - local.x, local.x - span.end)) * frame.length
        val dv = max(0f, abs(local.y) - 0.5f) * frame.thickness
        val distance = sqrt(du * du + dv * dv)
        if (best == null || distance < best.second - TIE) best = TextPosition(line, word) to distance
      }
    }
    return best
  }

  /**
   * Il rettangolo (4 angoli, normalizzato sull'immagine) delle parole [fromWord]..[toWord] della riga [line], allargato di [pad]
   * altezze dell'immagine in tutte le direzioni.
   */
  fun quad(line: Int, fromWord: Int, toWord: Int, pad: Float = 0f): List<Offset> {
    val frame = frames[line]
    val spans = layout.lines[line].words
    val start = spans[fromWord].start - pad / frame.length
    val end = spans[toWord].end + pad / frame.length
    val above = -0.5f - pad / frame.thickness
    val below = 0.5f + pad / frame.thickness
    return listOf(frame.point(start, above), frame.point(end, above), frame.point(end, below), frame.point(start, below)).map(::toImage)
  }

  /** Il quadrilatero di tutta la riga [line]. */
  fun lineQuad(line: Int, pad: Float = 0f): List<Offset> {
    val count = wordCount(line)
    return if (count == 0) emptyList() else quad(line, 0, count - 1, pad)
  }

  /**
   * Dove sta una maniglia: l'inizio della parola [position] (maniglia d'inizio) o la sua fine (maniglia di fine), sul bordo basso del
   * testo. [Handle.anchor] e' lo stesso estremo sulla linea di mezzo, il punto da cui il dito "tira" la maniglia.
   */
  fun handle(position: TextPosition, atEnd: Boolean): Handle {
    val frame = frames[position.line]
    val span = layout.lines[position.line].words[position.word]
    val u = if (atEnd) span.end else span.start
    return Handle(tip = toImage(frame.point(u, 0.5f)), anchor = toImage(frame.point(u, 0f)), angleDegrees = layout.lines[position.line].angleDegrees)
  }

  /** Una maniglia: la punta (sul bordo basso), il punto di aggancio (nel mezzo della riga) e l'inclinazione della riga. */
  data class Handle(val tip: Offset, val anchor: Offset, val angleDegrees: Int)

  private companion object {
    const val TIE = 1e-6f
  }
}
