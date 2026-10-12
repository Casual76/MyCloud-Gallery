package dev.pampa.mycloudgallery.core.qr.scanner

import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Dal piano Y di un fotogramma della fotocamera (YUV_420_888) al quadrato centrale in scala di grigi,
 * stretto: un byte per pixel, nessun passo di riga, nessun byte di riempimento.
 *
 * **Perche' il quadrato centrale.** Il mirino e' un quadrato che mostra il centro del fotogramma (la
 * fotocamera manda un 4:3 e lo schermo ne ritaglia il quadrato): si legge solo quel che l'utente vede.
 * Altrimenti uno scanner leggerebbe un QR sul bordo del fotogramma, fuori dal mirino, senza che nessuno
 * lo sappia. Come effetto secondario si decodificano meno pixel.
 *
 * **Perche' la copia.** Il buffer del fotogramma appartiene a CameraX e va restituito subito; le righe
 * possono avere un passo piu' largo dell'immagine (`rowStride`), e ZXing vuole righe contigue. L'array si
 * riusa fra un fotogramma e l'altro ([reuse]) se ha la misura giusta, per non allocare un megabyte dieci
 * volte al secondo. Il contenuto non va mai su disco ne' in un log.
 */
internal object FrameLuma {

  /** Il lato del quadrato centrale di un fotogramma [width] x [height]. */
  fun squareSide(width: Int, height: Int): Int = min(width, height)

  /**
   * Copia il quadrato centrale di [plane] in un array stretto di `lato x lato` byte.
   *
   * @param rowStride byte fra l'inizio di una riga e quello della successiva
   * @param pixelStride byte fra due pixel di una riga (1 per il piano Y)
   * @return l'array (nuovo, oppure [reuse] se la misura coincide)
   */
  fun centerSquare(
    plane: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    reuse: ByteArray? = null,
  ): ByteArray {
    require(width > 0 && height > 0 && rowStride >= width * pixelStride && pixelStride >= 1) {
      "piano non valido: ${width}x$height, passo di riga $rowStride, passo di pixel $pixelStride"
    }
    val side = squareSide(width, height)
    val left = (width - side) / 2
    val top = (height - side) / 2
    val out = if (reuse != null && reuse.size == side * side) reuse else ByteArray(side * side)
    val source = plane.duplicate() // posizione e limite propri: non toccano quelli di CameraX
    for (row in 0 until side) {
      val rowStart = (top + row) * rowStride + left * pixelStride
      if (pixelStride == 1) {
        source.position(rowStart)
        source.get(out, row * side, side)
      } else {
        for (col in 0 until side) out[row * side + col] = source.get(rowStart + col * pixelStride)
      }
    }
    return out
  }
}
