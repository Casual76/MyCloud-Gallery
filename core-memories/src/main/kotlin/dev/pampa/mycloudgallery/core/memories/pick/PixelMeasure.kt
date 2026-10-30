package dev.pampa.mycloudgallery.core.memories.pick

import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe

/**
 * La misura dei pixel di una miniatura (Fase 9, P7, D6): **funzione pura** su un array di luminanze. La decodifica della miniatura locale e il
 * ridimensionamento a [TARGET_SIDE] px li lega `:app` (P13, porta `MemoryPixels`); qui non c'e' ne' `Bitmap` ne' orologio ne' file.
 *
 *  - **Luce**: la luminanza media da 0 (nero) a 1 (bianco); le soglie sono di `PickWeights` (sotto 0,12 o sopra 0,92: -1).
 *  - **Nitidezza**: la varianza del laplaciano (nucleo a quattro vicini, `[0 1 0; 1 -4 1; 0 1 0]`) sulle luminanze 0..255 dei pixel interni, come
 *    il metodo comune "varianza del laplaciano" per la messa a fuoco. Una foto mossa ha pochi bordi netti e una varianza bassa; sotto
 *    `PickWeights.SHARPNESS_MIN` e' mossa (-1,5). La misura dipende dalla risoluzione: la soglia e' tarata sul lato lungo di [TARGET_SIDE] px.
 *
 * Una miniatura non misurabile (troppo piccola o troppo grande, o con l'array di lunghezza sbagliata) da' **null**: nessun segnale, mai un
 * numero inventato e mai un'eccezione (l'assenza di misura e' un caso previsto, E-MEM15).
 */
object PixelMeasure {
  /** Il lato lungo della miniatura su cui e' tarata la nitidezza (D6: "varianza del laplaciano su 256 px"). */
  const val TARGET_SIDE: Int = 256

  /** Oltre questo lato la misura non e' piu' confrontabile con la soglia e il costo cresce: non si misura. */
  const val MAX_SIDE: Int = 512

  /** Il laplaciano ha bisogno di almeno 3 x 3 pixel. */
  const val MIN_SIDE: Int = 3

  /**
   * Le misure di una miniatura in scala di grigi: [luminances] una per pixel, 0..255 senza segno (un `Byte` negativo vale 128..255), per righe,
   * di [width] x [height]. Null se non e' misurabile.
   */
  fun probe(luminances: ByteArray, width: Int, height: Int): PixelProbe? {
    if (!measurable(width, height, luminances.size)) return null
    return measure(width, height) { luminances[it].toInt() and 0xFF }
  }

  /** Come [probe] per i pixel ARGB di `Bitmap.getPixels` (l'alfa si ignora): la luminanza e' quella di [luminanceOf]. */
  fun probeArgb(pixels: IntArray, width: Int, height: Int): PixelProbe? {
    if (!measurable(width, height, pixels.size)) return null
    return measure(width, height) { luminanceOf(pixels[it]) }
  }

  /** La luminanza 0..255 di un pixel ARGB (coefficienti Rec. 709 in interi, arrotondata): nero 0, bianco 255. */
  fun luminanceOf(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (2_126 * r + 7_152 * g + 722 * b + 5_000) / 10_000
  }

  private fun measurable(width: Int, height: Int, size: Int): Boolean =
    width in MIN_SIDE..MAX_SIDE && height in MIN_SIDE..MAX_SIDE && size == width * height

  private inline fun measure(width: Int, height: Int, luma: (Int) -> Int): PixelProbe {
    var total = 0L
    for (i in 0 until width * height) total += luma(i)
    val mean = total.toDouble() / (width.toLong() * height) / MAX_LUMA

    var sum = 0L
    var sumOfSquares = 0L
    for (y in 1 until height - 1) {
      val row = y * width
      for (x in 1 until width - 1) {
        val at = row + x
        val laplacian = luma(at - width) + luma(at + width) + luma(at - 1) + luma(at + 1) - 4 * luma(at)
        sum += laplacian
        sumOfSquares += laplacian.toLong() * laplacian
      }
    }
    val inner = (width - 2).toLong() * (height - 2)
    val average = sum.toDouble() / inner
    val variance = (sumOfSquares.toDouble() / inner - average * average).coerceAtLeast(0.0)
    return PixelProbe(variance.toFloat(), mean.toFloat().coerceIn(0f, 1f))
  }

  private const val MAX_LUMA: Double = 255.0
}
