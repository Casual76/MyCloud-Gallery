package dev.pampa.mycloudgallery.core.qr.scanner

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.qr.QrRaster
import dev.pampa.mycloudgallery.core.qr.codec.QrRead
import dev.pampa.mycloudgallery.core.qr.codec.ZxingQrCodec
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

/** Dal piano Y di CameraX al quadrato stretto che ZXing legge: passo di riga, riuso, centro. */
class FrameLumaTest {

  /** Un piano finto: il pixel (x, y) vale `(x + 7 * y) % 251`, con [padding] byte di riempimento a fine riga. */
  private fun plane(width: Int, height: Int, padding: Int = 0, pixelStride: Int = 1): Pair<ByteBuffer, Int> {
    val rowStride = width * pixelStride + padding
    val buffer = ByteBuffer.allocate(rowStride * height)
    for (y in 0 until height) for (x in 0 until width) buffer.put(y * rowStride + x * pixelStride, ((x + 7 * y) % 251).toByte())
    return buffer to rowStride
  }

  private fun expected(x: Int, y: Int): Byte = ((x + 7 * y) % 251).toByte()

  @Test
  fun ilLatoEIlMinimoDelleDueDimensioni() {
    assertThat(FrameLuma.squareSide(1280, 960)).isEqualTo(960)
    assertThat(FrameLuma.squareSide(960, 1280)).isEqualTo(960)
  }

  @Test
  fun copiaIlQuadratoCentrale_diUnFotogrammaLargo() {
    val (buffer, stride) = plane(width = 40, height = 30)
    val out = FrameLuma.centerSquare(buffer, stride, 1, 40, 30)
    assertThat(out.size).isEqualTo(30 * 30)
    val left = 5 // (40 - 30) / 2
    for (y in 0 until 30) for (x in 0 until 30) assertThat(out[y * 30 + x]).isEqualTo(expected(left + x, y))
  }

  @Test
  fun copiaIlQuadratoCentrale_diUnFotogrammaAlto() {
    val (buffer, stride) = plane(width = 30, height = 41)
    val out = FrameLuma.centerSquare(buffer, stride, 1, 30, 41)
    assertThat(out.size).isEqualTo(30 * 30)
    val top = 5 // (41 - 30) / 2
    for (y in 0 until 30) for (x in 0 until 30) assertThat(out[y * 30 + x]).isEqualTo(expected(x, top + y))
  }

  @Test
  fun ilPassoDiRiga_piuLargoDellImmagine_nonEntraNellaCopia() {
    val (buffer, stride) = plane(width = 36, height = 24, padding = 28)
    val out = FrameLuma.centerSquare(buffer, stride, 1, 36, 24)
    val left = 6
    for (y in 0 until 24) for (x in 0 until 24) assertThat(out[y * 24 + x]).isEqualTo(expected(left + x, y))
  }

  @Test
  fun ilPassoDiPixelMaggioreDiUno_siSalta() {
    val (buffer, stride) = plane(width = 20, height = 16, pixelStride = 2)
    val out = FrameLuma.centerSquare(buffer, stride, 2, 20, 16)
    val left = 2
    for (y in 0 until 16) for (x in 0 until 16) assertThat(out[y * 16 + x]).isEqualTo(expected(left + x, y))
  }

  @Test
  fun lArrayDellaCopiaPrecedente_siRiusa_seLaMisuraCoincide() {
    val (buffer, stride) = plane(width = 40, height = 30)
    val first = FrameLuma.centerSquare(buffer, stride, 1, 40, 30)
    val second = FrameLuma.centerSquare(buffer, stride, 1, 40, 30, reuse = first)
    assertThat(second).isSameInstanceAs(first)
    val other = FrameLuma.centerSquare(buffer, stride, 1, 40, 30, reuse = ByteArray(10))
    assertThat(other.size).isEqualTo(900)
  }

  @Test
  fun ilBufferDiCameraXNonSiSposta() {
    val (buffer, stride) = plane(width = 40, height = 30)
    buffer.position(3)
    buffer.limit(buffer.capacity() - 1)
    FrameLuma.centerSquare(buffer, stride, 1, 40, 30)
    assertThat(buffer.position()).isEqualTo(3)
    assertThat(buffer.limit()).isEqualTo(buffer.capacity() - 1)
  }

  @Test
  fun unPianoNonValido_siRifiuta() {
    val (buffer, _) = plane(width = 10, height = 10)
    assertThrows(IllegalArgumentException::class.java) { FrameLuma.centerSquare(buffer, rowStride = 5, pixelStride = 1, width = 10, height = 10) }
    assertThrows(IllegalArgumentException::class.java) { FrameLuma.centerSquare(buffer, rowStride = 10, pixelStride = 1, width = 0, height = 10) }
  }

  @Test
  fun dalFotogrammaAlTesto_unQrVistoDaUnaFotocameraInVerticale() {
    // Un fotogramma 4:3 con un passo di riga largo, il QR nel quadrato centrale, ruotato di 90 gradi.
    val bits = ZxingQrCodec.encode("MCG1:Qj9Wk2LpZ0aT-ciao")
    val square = QrRaster.renderWithModulePx(bits, 6)
    val frameH = square.height
    val frameW = frameH * 4 / 3
    val padding = 64
    val stride = frameW + padding
    val buffer = ByteBuffer.allocate(stride * frameH)
    for (i in 0 until stride * frameH) buffer.put(i, 77) // sfondo grigio e riempimento
    val left = (frameW - frameH) / 2
    for (y in 0 until frameH) for (x in 0 until frameH) buffer.put(y * stride + left + x, square.data[y * frameH + x])

    val luma = FrameLuma.centerSquare(buffer, stride, 1, frameW, frameH)
    val read = ZxingQrCodec.read(luma, frameH, frameH)
    assertThat(read).isInstanceOf(QrRead.Found::class.java)
    assertThat((read as QrRead.Found).text).isEqualTo("MCG1:Qj9Wk2LpZ0aT-ciao")
  }
}
