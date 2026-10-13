package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import dev.pampa.mycloudgallery.core.model.ThumbHash
import kotlin.math.abs
import org.junit.Test

/** ThumbHash dall'algoritmo pubblico: andata e ritorno, dimensioni, trasparenza, robustezza. */
class ThumbHashCodecTest {

  private fun rgba(w: Int, h: Int, pixel: (x: Int, y: Int) -> Int): ByteArray {
    val out = ByteArray(w * h * 4)
    for (y in 0 until h) for (x in 0 until w) {
      val argb = pixel(x, y)
      val i = (y * w + x) * 4
      out[i] = (argb shr 16).toByte()
      out[i + 1] = (argb shr 8).toByte()
      out[i + 2] = argb.toByte()
      out[i + 3] = (argb ushr 24).toByte()
    }
    return out
  }

  private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

  private fun channel(pixel: Int, shift: Int): Int = (pixel shr shift) and 0xFF

  private fun mean(pixels: IntArray, shift: Int): Double = pixels.map { channel(it, shift) }.average()

  @Test
  fun unGrigioPienoHaIlSuoDcNelleIntestazioni() {
    val hash = ThumbHashCodec.encode(8, 8, rgba(8, 8) { _, _ -> argb(255, 128, 128, 128) })
    val bytes = hash.bytes()
    // l_dc = round(63 * 128/255) = 32, p_dc e q_dc = 0 -> 32; scala della luminanza 0; nessuna trasparenza.
    assertThat(bytes[0].toInt() and 0xFF).isEqualTo(0x20)
    assertThat(bytes[1].toInt() and 0xFF).isEqualTo(0x08)
    assertThat(bytes[2].toInt() and 0xFF).isEqualTo(0x02)
    // Quadrato: lx = ly = 7 nei 3 bit bassi; non e' orizzontale.
    assertThat(bytes[3].toInt() and 7).isEqualTo(7)
    assertThat(bytes[4].toInt() and 0x80).isEqualTo(0)
  }

  @Test
  fun andataERitornoDiColoriPieni() {
    for ((r, g, b) in listOf(Triple(220, 40, 40), Triple(40, 200, 60), Triple(30, 60, 220), Triple(250, 250, 250), Triple(5, 5, 5), Triple(200, 180, 40))) {
      val hash = ThumbHashCodec.encode(32, 32, rgba(32, 32) { _, _ -> argb(255, r, g, b) })
      val decoded = ThumbHashCodec.decode(hash, 32)
      assertThat(decoded).hasLength(32 * 32)
      assertThat(abs(mean(decoded, 16) - r)).isLessThan(8.0)
      assertThat(abs(mean(decoded, 8) - g)).isLessThan(8.0)
      assertThat(abs(mean(decoded, 0) - b)).isLessThan(8.0)
      assertThat(decoded.all { it ushr 24 == 0xFF }).isTrue()
    }
  }

  @Test
  fun ilGradienteResta_Sfocato_MaDelloStessoVerso() {
    val hash = ThumbHashCodec.encode(64, 64, rgba(64, 64) { x, _ -> argb(255, x * 4, x * 4, x * 4) })
    val image = ThumbHashCodec.decode(hash, 32)
    val left = (0 until 32).map { y -> channel(image[y * 32 + 2], 8) }.average()
    val right = (0 until 32).map { y -> channel(image[y * 32 + 29], 8) }.average()
    assertThat(left).isLessThan(75.0)
    assertThat(right).isGreaterThan(180.0)
    // Il contrasto orizzontale e' molto piu' grande di quello verticale (che con 4 bit per coefficiente non e' mai esattamente zero).
    val vertical = abs(channel(image[2], 8) - channel(image[31 * 32 + 2], 8))
    assertThat(vertical).isLessThan(((right - left) / 2).toInt())
  }

  @Test
  fun unaCasellaScuraSuSfondoChiaroSiVedeAlPostoGiusto() {
    val hash = ThumbHashCodec.encode(48, 48, rgba(48, 48) { x, y -> if (x < 20 && y < 20) argb(255, 20, 20, 20) else argb(255, 230, 230, 230) })
    val image = ThumbHashCodec.decode(hash, 24)
    val topLeft = channel(image[3 * 24 + 3], 8)
    val bottomRight = channel(image[20 * 24 + 20], 8)
    assertThat(topLeft).isLessThan(bottomRight - 80)
  }

  @Test
  fun leDimensioniSeguonoLaProporzioneDellOriginale() {
    val wide = ThumbHashCodec.encode(100, 50, rgba(100, 50) { x, _ -> argb(255, x * 2, 100, 100) })
    val tall = ThumbHashCodec.encode(50, 100, rgba(50, 100) { _, y -> argb(255, y * 2, 100, 100) })
    val square = ThumbHashCodec.encode(64, 64, rgba(64, 64) { x, _ -> argb(255, x * 3, 100, 100) })
    val w = ThumbHashCodec.decodeImage(wide, 32)
    val t = ThumbHashCodec.decodeImage(tall, 32)
    val s = ThumbHashCodec.decodeImage(square, 32)
    assertThat(w.width).isEqualTo(32)
    assertThat(w.height).isLessThan(32)
    assertThat(w.argb).hasLength(w.width * w.height)
    assertThat(t.height).isEqualTo(32)
    assertThat(t.width).isLessThan(32)
    assertThat(s.width).isEqualTo(32)
    assertThat(s.height).isEqualTo(32)
    // 100x50 = 2:1 si codifica con 7x4 coefficienti (7/3,5 arrotondato per eccesso): circa 1,75.
    assertThat(ThumbHashCodec.aspectRatio(wide)).isWithin(0.01).of(1.75)
    assertThat(ThumbHashCodec.aspectRatio(tall)).isWithin(0.01).of(4.0 / 7.0)
    assertThat(ThumbHashCodec.aspectRatio(square)).isWithin(0.001).of(1.0)
  }

  @Test
  fun decodeResta_QuadratoPerLaGriglia() {
    val hash = ThumbHashCodec.encode(100, 50, rgba(100, 50) { _, _ -> argb(255, 100, 150, 200) })
    // Compatibile con il segnaposto di P0: l'array ha sempre maxSide x maxSide elementi.
    assertThat(ThumbHashCodec.decode(hash, 32)).hasLength(32 * 32)
    assertThat(ThumbHashCodec.decode(hash, 16)).hasLength(16 * 16)
    assertThat(ThumbHashCodec.decode(hash, 0)).hasLength(1)
    assertThat(ThumbHashCodec.decode(hash, -5)).hasLength(1)
  }

  @Test
  fun laTrasparenzaSiConservaEUsaMenoLuminanza() {
    val half = rgba(32, 32) { x, _ -> if (x < 16) argb(0, 0, 0, 0) else argb(255, 200, 30, 30) }
    val hash = ThumbHashCodec.encode(32, 32, half)
    val bytes = hash.bytes()
    assertThat(bytes[2].toInt() and 0x80).isEqualTo(0x80) // il bit "ha trasparenza"
    val image = ThumbHashCodec.decode(hash, 32)
    val leftAlpha = (0 until 32).map { y -> image[y * 32 + 2] ushr 24 }.average()
    val rightAlpha = (0 until 32).map { y -> image[y * 32 + 29] ushr 24 }.average()
    assertThat(leftAlpha).isLessThan(60.0)
    assertThat(rightAlpha).isGreaterThan(195.0)
    // Dove e' opaco, e' rosso.
    val right = image[16 * 32 + 29]
    assertThat(channel(right, 16)).isGreaterThan(channel(right, 8) + 60)
  }

  @Test
  fun nonSuperaMaiIl_Limite_Del_Valore_E_Sta_Nei_36_Byte() {
    val sizes = listOf(1 to 1, 3 to 100, 100 to 3, 100 to 100, 37 to 91, 91 to 37, 5 to 5, 100 to 75)
    for ((w, h) in sizes) {
      for (alpha in listOf(false, true)) {
        val data = rgba(w, h) { x, y -> argb(if (alpha && x % 2 == 0) 90 else 255, (x * 7) and 255, (y * 5) and 255, (x + y) and 255) }
        val hash = ThumbHashCodec.encode(w, h, data)
        assertThat(hash.bytes().size).isAtMost(36)
        assertThat(hash.base64.length).isAtMost(ThumbHash.MAX_LENGTH)
        assertThat(hash.bytes().size).isAtLeast(5)
        assertThat(ThumbHashCodec.decode(hash, 8)).hasLength(64)
      }
    }
  }

  @Test
  fun sullImmagineSenzaTrasparenzaIlFormatoPesa_21_Byte_Al_Massimo_Circa() {
    val hash = ThumbHashCodec.encode(100, 75, rgba(100, 75) { x, y -> argb(255, x * 2, y * 3, 120) })
    // 5 byte di intestazione e 4 bit per ogni coefficiente: poche decine di byte in tutto.
    assertThat(hash.bytes().size).isIn(18..30)
  }

  @Test
  fun lACodificaEDeterministicaEUnImmagineGrandeSiRiduce() {
    val data = rgba(40, 30) { x, y -> argb(255, x * 6, y * 8, 100) }
    assertThat(ThumbHashCodec.encode(40, 30, data)).isEqualTo(ThumbHashCodec.encode(40, 30, data))
    val big = rgba(400, 300) { x, y -> argb(255, (x / 2) and 255, (y / 2) and 255, 100) }
    val hash = ThumbHashCodec.encode(400, 300, big)
    // 4:3 -> 7x5 (5,25 arrotondato): circa 1,4.
    assertThat(ThumbHashCodec.aspectRatio(hash)).isWithin(0.1).of(1.4)
  }

  @Test
  fun ricodificareLImmagineDecodificataDaLostessoSegnaposto() {
    val data = rgba(48, 36) { x, y -> argb(255, (x * 5) and 255, (y * 6) and 255, ((x + y) * 3) and 255) }
    val first = ThumbHashCodec.encode(48, 36, data)
    val image = ThumbHashCodec.decodeImage(first, 32)
    val bytes = ByteArray(image.argb.size * 4)
    for (i in image.argb.indices) {
      bytes[i * 4] = (image.argb[i] shr 16).toByte()
      bytes[i * 4 + 1] = (image.argb[i] shr 8).toByte()
      bytes[i * 4 + 2] = image.argb[i].toByte()
      bytes[i * 4 + 3] = (image.argb[i] ushr 24).toByte()
    }
    val second = ThumbHashCodec.encode(image.width, image.height, bytes)
    // Stessa proporzione e stesse medie: la forma non cambia ricodificando.
    assertThat(ThumbHashCodec.aspectRatio(second)).isWithin(0.35).of(ThumbHashCodec.aspectRatio(first))
    val a = ThumbHashCodec.decode(first, 16)
    val b = ThumbHashCodec.decode(second, 16)
    assertThat(abs(mean(a, 8) - mean(b, 8))).isLessThan(10.0)
  }

  @Test
  fun ilNeutroEUnGrigioOpaco() {
    val image = ThumbHashCodec.decode(ThumbHashCodec.NEUTRAL, 4)
    assertThat(image.all { it ushr 24 == 0xFF }).isTrue()
    assertThat(abs(mean(image, 8) - 128)).isLessThan(4.0)
    assertThat(ThumbHashCodec.NEUTRAL.base64.length).isAtMost(ThumbHash.MAX_LENGTH)
  }

  @Test
  fun unHashCortoOMalformatoNonLanciaMai() {
    val tiny = ThumbHash.of(byteArrayOf(1, 2, 3))
    assertThat(ThumbHashCodec.decode(tiny, 16)).hasLength(256)
    // 6 byte con il bit di trasparenza e coefficienti mancanti.
    val partial = ThumbHash.of(byteArrayOf(0x20, 0x08, 0x82.toByte(), 0x07, 0, 0x55))
    assertThat(ThumbHashCodec.decode(partial, 16)).hasLength(256)
    assertThat(ThumbHashCodec.decodeImage(partial, 16).argb).isNotEmpty()
    // Tutti i byte possibili in testa: nessuna eccezione.
    for (b in 0..255) {
      val bytes = ByteArray(25) { (b + it * 7).toByte() }
      assertThat(ThumbHashCodec.decode(ThumbHash.of(bytes), 4)).hasLength(16)
    }
  }

  @Test
  fun ilCodificatoreRifiutaDatiNonValidi() {
    try {
      ThumbHashCodec.encode(4, 4, ByteArray(10))
      error("doveva rifiutare")
    } catch (_: IllegalArgumentException) {
      // corretto
    }
  }
}
