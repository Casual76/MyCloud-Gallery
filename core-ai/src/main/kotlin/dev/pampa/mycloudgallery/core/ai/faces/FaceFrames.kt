package dev.pampa.mycloudgallery.core.ai.faces

import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.cutout.Resampler
import kotlin.math.max
import kotlin.math.roundToInt

// IL QUADRO DI YUNET E I TASSELLI (docs/fase8-progetto.md §2.2 "Nel telefono", D17; docs/decisioni.md "Fase 8 P1"): YuNet riceve un
// quadro fisso **1024x1024**, BGR 0-255 senza media ne' scala, con l'immagine ridotta (o ingrandita) a lato lungo 1024 e i **bordi a 0
// in basso e a destra** (come `common.quadro_yunet` di P1, provato contro `FaceDetectorYN`). In riduzione la media sull'area, in
// ingrandimento l'interpolazione bilineare (le due strade di OpenCV, `INTER_AREA` e `INTER_LINEAR`). Puro, sulla JVM.
//
// La seconda passata (D17) guarda l'HQ (fino a 2048 px) a tasselli: ogni lato oltre 1024 px si divide in due parti che si sovrappongono
// di [FaceEngineLimits.TILE_OVERLAP] px (quattro tasselli per un'immagine che ha tutti e due i lati oltre 1024); ogni tassello e' un
// rettangolo dell'immagine che entra nel quadro con la sua scala, e i rilevamenti tornano alle coordinate dell'immagine intera.

/** Un rettangolo dell'immagine, in pixel (`x`, `y` dall'angolo in alto a sinistra). */
internal class FaceRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
  init {
    require(x >= 0 && y >= 0 && width > 0 && height > 0) { "Regione vuota." }
  }

  override fun toString(): String = "FaceRegion(${width}x$height)"
}

/**
 * Dove e' finita una [region] nel quadro: l'immagine occupa `contentWidth` x `contentHeight` pixel dall'angolo in alto a sinistra
 * (il resto sono bordi a 0). Le due scale, orizzontale e verticale, sono quelle vere del ridimensionamento (il lato lungo e' esatto, il
 * corto e' arrotondato al pixel).
 */
internal class FrameGeometry(val region: FaceRegion, val contentWidth: Int, val contentHeight: Int) {
  private val sx: Float = region.width.toFloat() / contentWidth
  private val sy: Float = region.height.toFloat() / contentHeight

  /** Dal quadro all'immagine intera. */
  fun imageX(frameX: Float): Float = region.x + frameX * sx

  fun imageY(frameY: Float): Float = region.y + frameY * sy

  /** Quanti pixel del quadro e' lungo un pixel dell'immagine (il lato lungo dell'immagine nel quadro e' 1024). */
  val scale: Float get() = 1f / sx
}

internal object FaceTiling {

  /** Un rilevamento a meno di questi pixel del quadro da una giunzione fra due tasselli e' tagliato dal bordo del tassello: si scarta. */
  const val SEAM_MARGIN: Float = 3f

  /** I tasselli di un'immagine: uno solo (l'immagine intera) se entra nel quadro, altrimenti la griglia descritta sopra, riga per riga. */
  fun plan(width: Int, height: Int): TilePlan {
    val columns = axis(width)
    val rows = axis(height)
    val regions = ArrayList<FaceRegion>(columns.size * rows.size)
    for ((ry, rh) in rows) for ((cx, cw) in columns) regions += FaceRegion(cx, ry, cw, rh)
    return TilePlan(regions, columns.size, rows.size)
  }

  /** Le parti `(inizio, lunghezza)` di un lato: una se entra in 1024 px, altrimenti due che si sovrappongono di 128 px. */
  private fun axis(length: Int): List<Pair<Int, Int>> {
    require(length > 0) { "Lato vuoto." }
    val parts = (length + FaceEngineLimits.TILE_SIDE - 1) / FaceEngineLimits.TILE_SIDE
    if (parts <= 1) return listOf(0 to length)
    val overlap = FaceEngineLimits.TILE_OVERLAP
    val size = (length + (parts - 1) * overlap + parts - 1) / parts
    return List(parts) { i -> (i * (size - overlap)).coerceAtMost(length - size) to size }
  }
}

/** I tasselli di un'immagine, in `columns` x `rows`, riga per riga. */
internal class TilePlan(val regions: List<FaceRegion>, val columns: Int, val rows: Int) {
  /** Il tassello [index] ha un tassello vicino a sinistra, a destra, sopra o sotto (una giunzione, non il bordo dell'immagine). */
  fun seams(index: Int): Seams {
    val col = index % columns
    val row = index / columns
    return Seams(left = col > 0, right = col < columns - 1, top = row > 0, bottom = row < rows - 1)
  }

  class Seams(val left: Boolean, val right: Boolean, val top: Boolean, val bottom: Boolean)
}

/**
 * Il quadro 1024x1024x3 float32 (BGR, riga per riga) che YuNet riceve. Uno per rilevatore: la matrice si riusa a ogni corsa
 * (12,6 MB: niente allocazioni nel ciclo). Non e' sicuro fra thread: lo usa il thread del motore.
 */
internal class FrameBuilder {

  /** Il quadro, valido fino alla [build] dopo. */
  val frame: FloatArray = FloatArray(YuNetLayout.INPUT_SIDE * YuNetLayout.INPUT_SIDE * 3)

  private val rowCache = Array(CACHE_ROWS) { FloatArray(YuNetLayout.INPUT_SIDE * 3) }
  private val cachedRow = IntArray(CACHE_ROWS)

  /**
   * Ridimensiona [region] di [pixels] (ARGB, [imageWidth] di larghezza) a lato lungo 1024 e la mette nel quadro, bordi a 0.
   * L'alfa e' ignorato (le immagini dei volti sono opache: `ModelInputs.forFaces` compone la trasparenza sul bianco).
   */
  fun build(pixels: IntArray, imageWidth: Int, region: FaceRegion): FrameGeometry {
    val side = YuNetLayout.INPUT_SIDE
    val scale = side.toDouble() / max(region.width, region.height)
    val cw = max(1, (region.width * scale).roundToInt()).coerceAtMost(side)
    val ch = max(1, (region.height * scale).roundToInt()).coerceAtMost(side)
    frame.fill(0f)
    if (cw == region.width && ch == region.height) {
      // Il caso piu' comune (l'anteprima di analisi ha il lato lungo di 1024 px): nessun ridimensionamento, solo l'ordine B, G, R.
      for (y in 0 until ch) {
        var src = (region.y + y) * imageWidth + region.x
        var o = y * side * 3
        for (x in 0 until cw) {
          val p = pixels[src++]
          frame[o] = (p and 0xff).toFloat()
          frame[o + 1] = ((p shr 8) and 0xff).toFloat()
          frame[o + 2] = ((p shr 16) and 0xff).toFloat()
          o += 3
        }
      }
      return FrameGeometry(region, cw, ch)
    }
    val tx = Resampler.taps(region.width, cw)
    val ty = Resampler.taps(region.height, ch)
    cachedRow.fill(-1)
    var next = 0
    for (dy in 0 until ch) {
      val base = dy * side * 3
      for (t in 0 until ty.count[dy]) {
        val sy = ty.first[dy] + t
        val wy = ty.weight[ty.offset[dy] + t]
        var slot = -1
        for (i in 0 until CACHE_ROWS) if (cachedRow[i] == sy) slot = i
        if (slot < 0) {
          slot = next
          next = (next + 1) % CACHE_ROWS
          horizontal(pixels, (region.y + sy) * imageWidth + region.x, tx, rowCache[slot], cw)
          cachedRow[slot] = sy
        }
        val row = rowCache[slot]
        // La riga in cache e' gia' in ordine B, G, R: si somma con il peso verticale.
        for (i in 0 until cw * 3) frame[base + i] += row[i] * wy
      }
    }
    return FrameGeometry(region, cw, ch)
  }

  /** Una riga sorgente (da [rowStart] in [src]) ridotta a [dw] pixel, scritta in ordine B, G, R. */
  private fun horizontal(src: IntArray, rowStart: Int, tx: Resampler.Taps, into: FloatArray, dw: Int) {
    var o = 0
    for (dx in 0 until dw) {
      var r = 0f
      var g = 0f
      var b = 0f
      val first = rowStart + tx.first[dx]
      val w0 = tx.offset[dx]
      for (t in 0 until tx.count[dx]) {
        val p = src[first + t]
        val w = tx.weight[w0 + t]
        r += ((p shr 16) and 0xff) * w
        g += ((p shr 8) and 0xff) * w
        b += (p and 0xff) * w
      }
      into[o] = b
      into[o + 1] = g
      into[o + 2] = r
      o += 3
    }
  }

  private companion object {
    const val CACHE_ROWS = 4
  }
}
