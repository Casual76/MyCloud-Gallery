package dev.pampa.mycloudgallery.core.ai.ocr

import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.OcrText
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// DAL TESTO DI ML KIT ALLE RIGHE DEL FORMATO (docs/fase6-progetto.md D12, D13, D43, §2.3 "Corpo ocr"). Codice puro, senza nessuna classe
// di ML Kit ne' di Android: `MlKitTextReader` traduce `Text.Line` / `Text.Element` in [RawLine] / [RawWord] e qui si fa tutto il resto,
// cosi' le regole del formato (parole non vuote, NFC, niente caratteri di controllo o di direzione, tetti, estremi non decrescenti,
// coordinate normalizzate) si provano sulla JVM con righe scritte a mano, comprese quelle ostili (testo con caratteri di controllo,
// elementi vuoti, riquadri fuori dall'immagine, righe di mille parole).

/** Un punto in pixel dell'immagine passata al motore. */
internal class RawPoint(val x: Float, val y: Float)

/** Una parola ("elemento" di ML Kit): il testo e, se ci sono, i quattro angoli (in senso orario dall'alto a sinistra del TESTO). */
internal class RawWord(val text: String, val corners: List<RawPoint> = emptyList())

/**
 * Una riga letta: testo, i quattro angoli (come sopra) o almeno il riquadro, l'angolo che ML Kit dichiara (gradi, `null` se non c'e')
 * e le parole. Gli angoli hanno la precedenza sull'angolo dichiarato (non hanno ambiguita' di segno).
 */
internal class RawLine(
  val text: String,
  val corners: List<RawPoint> = emptyList(),
  val box: RawBox? = null,
  val angleDegrees: Float? = null,
  val words: List<RawWord> = emptyList(),
)

internal class RawBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Le righe del formato e, per ognuna, l'altezza del testo in pixel dell'immagine letta (serve a [OcrDensity]). */
internal class OcrLayoutResult(val lines: List<OcrLine>, val textHeightsPx: List<Float>)

internal object OcrLayout {

  fun convert(lines: List<RawLine>, imageWidth: Int, imageHeight: Int): OcrLayoutResult {
    require(imageWidth > 0 && imageHeight > 0) { "Immagine vuota." }
    val out = ArrayList<OcrLine>()
    val heights = ArrayList<Float>()
    for (raw in lines) {
      if (out.size >= AiLimits.MAX_OCR_LINES) break
      val converted = convertLine(raw, imageWidth.toFloat(), imageHeight.toFloat()) ?: continue
      out += converted.first
      heights += converted.second
    }
    return OcrLayoutResult(out, heights)
  }

  /** Una riga del formato, o null se non ne resta niente (nessuna parola utile, nessuna geometria). */
  private fun convertLine(raw: RawLine, width: Float, height: Float): Pair<OcrLine, Float>? {
    val words = cleanedWords(raw) ?: return null
    val geometry = Geometry.of(raw) ?: return null

    val text = words.joinToString(" ") { it.text }
    val spans = spans(raw, words, geometry)

    val box = ShortBox(
      x0 = unit(geometry.minX / width, floorIt = true),
      y0 = unit(geometry.minY / height, floorIt = true),
      x1 = unit(geometry.maxX / width, floorIt = false),
      y1 = unit(geometry.maxY / height, floorIt = false),
    )
    val line = try {
      OcrLine(box, foldAngle(geometry.angleDegrees), spans, text)
    } catch (_: IllegalArgumentException) {
      return null
    }
    return line to geometry.textHeight
  }

  // --- Parole ---------------------------------------------------------------------------------------------------------------

  /** Una parola pulita con il suo peso (caratteri) e, se la geometria lo permette, i suoi angoli. */
  private class Word(val text: String, val sourceLength: Int, val corners: List<RawPoint>)

  /**
   * Le parole della riga come le vuole il formato: NFC, senza caratteri vietati, mai vuote, senza spazi dentro (un elemento con
   * spazi diventa piu' parole che si dividono i suoi angoli in proporzione). Al piu' [AiLimits.MAX_WORDS_PER_LINE] parole e
   * [AiLimits.MAX_LINE_BYTES] byte di testo: il resto si taglia. Senza elementi, le parole sono quelle del testo della riga.
   */
  private fun cleanedWords(raw: RawLine): List<Word>? {
    val source: List<RawWord> = raw.words.ifEmpty { raw.text.splitWhitespace().map { RawWord(it) } }
    val words = ArrayList<Word>()
    var bytes = 0
    for (element in source) {
      val pieces = element.text.splitWhitespace()
      for (piece in pieces) {
        val cleaned = OcrText.cleanWord(piece) ?: continue
        val extra = cleaned.toByteArray(Charsets.UTF_8).size + if (words.isEmpty()) 0 else 1
        if (words.size >= AiLimits.MAX_WORDS_PER_LINE || bytes + extra > AiLimits.MAX_LINE_BYTES) return words.takeIf { it.isNotEmpty() }
        bytes += extra
        // Un elemento con piu' pezzi non ha angoli propri per pezzo: si dividera' in proporzione (corners vuoti + peso).
        words += Word(cleaned, piece.length, if (pieces.size == 1) element.corners else emptyList())
      }
    }
    return words.takeIf { it.isNotEmpty() }
  }

  private fun String.splitWhitespace(): List<String> {
    val pieces = ArrayList<String>()
    val current = StringBuilder()
    var i = 0
    while (i < length) {
      val cp = codePointAt(i)
      i += Character.charCount(cp)
      if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
        if (current.isNotEmpty()) {
          pieces += current.toString()
          current.setLength(0)
        }
      } else {
        current.appendCodePoint(cp)
      }
    }
    if (current.isNotEmpty()) pieces += current.toString()
    return pieces
  }

  // --- Estremi delle parole lungo la riga --------------------------------------------------------------------------------------

  private fun spans(raw: RawLine, words: List<Word>, g: Geometry): List<WordSpan> {
    val measured = words.all { it.corners.size >= 3 } && g.lineLength > MIN_LINE_LENGTH
    val fractions: List<Pair<Float, Float>> = if (measured) {
      words.map { word ->
        val projections = word.corners.map { g.along(it) }
        ((projections.min() - g.alongMin) / g.lineLength) to ((projections.max() - g.alongMin) / g.lineLength)
      }
    } else {
      proportional(words)
    }
    var previousStart = 0
    var previousEnd = 0
    return fractions.map { (start, end) ->
      val s = maxOf(previousStart, unit(start, floorIt = true))
      val e = maxOf(previousEnd, s, unit(end, floorIt = false))
      previousStart = s
      previousEnd = e
      WordSpan(s, e)
    }
  }

  /** Senza angoli per parola: la riga divisa in proporzione ai caratteri, uno spazio fra una parola e l'altra. */
  private fun proportional(words: List<Word>): List<Pair<Float, Float>> {
    val total = words.sumOf { it.sourceLength } + (words.size - 1)
    var at = 0
    return words.map { word ->
      val start = at.toFloat() / total
      at += word.sourceLength
      val end = at.toFloat() / total
      at += 1
      start to end
    }
  }

  // --- Geometria ------------------------------------------------------------------------------------------------------------

  /**
   * Il riquadro, la direzione del testo e quanto e' alto. Angoli quando ci sono (quattro, in senso orario dall'alto a sinistra del
   * testo: il primo lato e' la linea di base), altrimenti il riquadro con l'angolo dichiarato (o zero).
   */
  private class Geometry(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val angleDegrees: Float,
    val textHeight: Float,
    private val ux: Float,
    private val uy: Float,
    val alongMin: Float,
    val lineLength: Float,
  ) {
    fun along(p: RawPoint): Float = p.x * ux + p.y * uy

    companion object {
      fun of(raw: RawLine): Geometry? {
        val quad = raw.corners.takeIf { it.size >= 4 && it.all { p -> p.x.isFinite() && p.y.isFinite() } }
        val points: List<RawPoint> = quad ?: raw.box?.takeIf { it.isFinite() }?.let { b ->
          listOf(RawPoint(b.left, b.top), RawPoint(b.right, b.top), RawPoint(b.right, b.bottom), RawPoint(b.left, b.bottom))
        } ?: return null

        val first = points[0]
        val second = points[1]
        val dx = second.x - first.x
        val dy = second.y - first.y
        val degrees = when {
          quad != null && hypot(dx, dy) > MIN_LINE_LENGTH -> Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
          raw.angleDegrees != null && raw.angleDegrees.isFinite() -> raw.angleDegrees
          else -> 0f
        }
        val radians = Math.toRadians(degrees.toDouble())
        val ux = cos(radians).toFloat()
        val uy = sin(radians).toFloat()
        val projections = points.map { it.x * ux + it.y * uy }
        val last = points[3]
        val textHeight = if (quad != null) hypot(last.x - first.x, last.y - first.y) else (points.maxOf { it.y } - points.minOf { it.y })
        return Geometry(
          minX = points.minOf { it.x },
          minY = points.minOf { it.y },
          maxX = points.maxOf { it.x },
          maxY = points.maxOf { it.y },
          angleDegrees = degrees,
          textHeight = textHeight,
          ux = ux,
          uy = uy,
          alongMin = projections.min(),
          lineLength = projections.max() - projections.min(),
        )
      }

      private fun RawBox.isFinite(): Boolean = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
    }
  }

  /**
   * L'inclinazione nel formato, da -90 a 90 gradi: l'angolo della direzione del testo riportato nell'intervallo (un testo capovolto
   * di 180 gradi diventa 0: il formato non distingue il verso, e gli estremi delle parole restano nell'ordine di lettura).
   */
  fun foldAngle(degrees: Float): Int {
    if (!degrees.isFinite()) return 0
    var a = degrees
    while (a > 90f) a -= 180f
    while (a < -90f) a += 180f
    return a.roundToInt().coerceIn(-90, 90)
  }

  /** Da una frazione dell'immagine (0..1, anche fuori) a 0..65535; [floorIt] per il lato piu' piccolo, il piu' grande si arrotonda in su. */
  private fun unit(fraction: Float, floorIt: Boolean): Int {
    if (!fraction.isFinite()) return 0
    val scaled = fraction.coerceIn(0f, 1f) * AiLimits.UNIT
    return (if (floorIt) floor(scaled) else ceil(scaled)).toInt().coerceIn(0, AiLimits.UNIT)
  }

  private const val MIN_LINE_LENGTH = 1e-3f
}

/**
 * Quando il testo e' "fitto" (D12): la seconda passata sull'anteprima HQ (2048 px) serve se ci sono almeno [MIN_LINES] righe con
 * l'altezza mediana sotto [MAX_MEDIAN_HEIGHT_PX] pixel dell'immagine letta (ML Kit legge bene da circa 16 px di altezza per carattere).
 * I valori sono fissati dalle misure di P4 sulle fixture sintetiche (docs/decisioni.md, "Fase 6 P4").
 */
internal object OcrDensity {

  const val MIN_LINES: Int = 6

  /** Altezza mediana del testo, in pixel dell'immagine letta, sotto la quale la prima passata non basta. */
  const val MAX_MEDIAN_HEIGHT_PX: Float = 16f

  fun isDense(textHeightsPx: List<Float>): Boolean {
    if (textHeightsPx.size < MIN_LINES) return false
    val sorted = textHeightsPx.sorted()
    val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
    return median < MAX_MEDIAN_HEIGHT_PX
  }
}
