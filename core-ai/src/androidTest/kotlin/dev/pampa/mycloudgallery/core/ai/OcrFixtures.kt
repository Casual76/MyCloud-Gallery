package dev.pampa.mycloudgallery.core.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.floor

/**
 * Le FIXTURE SINTETICHE dell'OCR (docs/fase6-progetto.md §5.3, D12): pagine bianche con righe di testo italiano inventato (negozi e
 * indirizzi che non esistono, nessun dato personale), disegnate qui con il carattere di sistema a un'altezza data in pixel. Servono a
 * misurare l'errore per carattere di ML Kit a 10-40 px, a 1024 e a 2048, e a fissare la soglia del testo fitto (`OcrDensity`). Il
 * generatore del set pubblico che P9 mettera' in `tools/fixtures/` ne riprende il testo.
 */
internal object OcrFixtures {

  /** Righe da leggere: accenti, apostrofi, euro, cifre, punteggiatura (D13). */
  val LINES: List<String> = listOf(
    "Totale complessivo € 24,50",
    "È aperto anche la domenica fino alle 21:30",
    "Via Giuseppe Garibaldi 12, Firenze",
    "Pomodori pelati 400 g 1,29",
    "Caffè espresso, città, perché, così",
    "Scontrino n. 0042 del 17/03/2025",
    "IVA 22% inclusa nel prezzo",
    "Grazie per la vostra visita!",
    "L'offerta vale dal lunedì al venerdì",
    "Prezzo al chilo € 3,99 sconto 15%",
  )

  /** Quanto spazio c'e' fra una riga e l'altra, in multipli dell'altezza del carattere. */
  private const val LINE_SPACING = 1.9f

  class Page(val bitmap: Bitmap, val lines: List<String>, val fontPx: Float)

  /**
   * Una pagina [width] x [height] con [fontPx] pixel di altezza del carattere; le righe sono tante quante ci stanno, al piu'
   * [maxLines], prese in ordine da [LINES] (ripetute se servono).
   */
  fun page(width: Int, height: Int, fontPx: Float, maxLines: Int = 24): Page {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      typeface = Typeface.SANS_SERIF
      textSize = fontPx
    }
    val margin = width * 0.03f
    val spacing = fontPx * LINE_SPACING
    val count = minOf(maxLines, floor((height - 2 * margin) / spacing).toInt()).coerceAtLeast(1)
    val lines = List(count) { LINES[it % LINES.size] }
    var baseline = margin + fontPx
    for (line in lines) {
      canvas.drawText(line, margin, baseline, paint)
      baseline += spacing
    }
    return Page(bitmap, lines, fontPx)
  }

  /** L'anteprima di analisi: JPEG q90. */
  fun jpeg90(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray()

  /** L'HQ: WebP con perdita, qualita' 90. */
  @Suppress("DEPRECATION")
  fun webp90(bitmap: Bitmap): ByteArray {
    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    return ByteArrayOutputStream().also { check(bitmap.compress(format, 90, it)) }.toByteArray()
  }

  /** La stessa cosa con la puntuazione e gli spazi ridotti, per il confronto fra il testo letto e quello disegnato. */
  fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

  /** Errore per carattere: distanza di Levenshtein fra [read] e [truth] (normalizzati) diviso la lunghezza di [truth]. */
  fun characterErrorRate(truth: String, read: String): Double {
    val a = normalize(truth)
    val b = normalize(read)
    if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
    return levenshtein(a, b).toDouble() / a.length
  }

  private fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
      current[0] = i
      for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
      }
      val swap = previous
      previous = current
      current = swap
    }
    return previous[b.length]
  }
}
