package dev.pampa.mycloudgallery.core.ai.runtime

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.nas.ImageBounds
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Test

/**
 * Fase 6 (P4, "Fatto quando": `ModelInputs` rifiuta JPEG e WebP ostili PRIMA di decodificare): le intestazioni. La decodifica vera
 * (e la prova che un ingresso ostile non arriva mai al decodificatore) e' strumentata (`ModelInputsDeviceTest`); qui la lettura
 * difesa delle poche decine di byte che dicono formato e dimensioni, con file costruiti a mano, compresi quelli che mentono.
 */
class ImageHeadersTest {

  // --- Validi ---------------------------------------------------------------------------------------------------------------

  @Test
  fun unJpegValidoDaFormatoEDimensioni() {
    val h = ImageHeaders.read(Samples.jpeg(1024, 768))!!
    assertThat(h.format).isEqualTo(InputFormat.JPEG)
    assertThat(h.width).isEqualTo(1024)
    assertThat(h.height).isEqualTo(768)
    assertThat(h.longSide).isEqualTo(1024)
  }

  @Test
  fun unJpegProgressivoEUnEsteso() {
    assertThat(ImageHeaders.read(Samples.jpeg(640, 480, sof = 0xC2))).isNotNull()
    assertThat(ImageHeaders.read(Samples.jpeg(640, 480, sof = 0xC1))).isNotNull()
  }

  @Test
  fun unJpegInGrigioEUnoConMolteInformazioniExifPrimaDelSof() {
    assertThat(ImageHeaders.read(Samples.jpeg(100, 50, components = 1))).isNotNull()
    val withExif = Samples.jpeg(800, 600, appBytes = 20_000) // un APP1 grosso (miniatura EXIF) si salta per lunghezza
    assertThat(ImageHeaders.read(withExif)!!.width).isEqualTo(800)
  }

  @Test
  fun unJpegConRiempimentoFraIMarcatoriSiLegge() {
    val plain = Samples.jpeg(320, 240)
    // FF FF prima di un marcatore e' riempimento lecito.
    val padded = plain.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte()) + plain.copyOfRange(2, plain.size)
    assertThat(ImageHeaders.read(padded)).isNotNull()
  }

  @Test
  fun unWebpLossyLosslessEdEsteso() {
    assertThat(ImageHeaders.read(Samples.webpLossy(2048, 1536))!!.longSide).isEqualTo(2048)
    val lossless = ImageHeaders.read(Samples.webpLossless(1000, 700))!!
    assertThat(lossless.width).isEqualTo(1000)
    assertThat(lossless.height).isEqualTo(700)
    val extended = ImageHeaders.read(Samples.webpExtended(canvasW = 2048, canvasH = 1536, imageW = 2048, imageH = 1536))!!
    assertThat(extended.format).isEqualTo(InputFormat.WEBP)
    assertThat(extended.height).isEqualTo(1536)
  }

  @Test
  fun iConfiniDiImageBoundsSiAccettanoEUnPixelInPiuNo() {
    val maxSide = 4096
    assertThat(ImageHeaders.read(Samples.jpeg(maxSide, maxSide))).isNotNull() // 16,7 milioni di pixel: il tetto
    assertThat(ImageHeaders.read(Samples.jpeg(maxSide + 1, maxSide))).isNull()
    assertThat(ImageHeaders.read(Samples.jpeg(16384, 1))).isNotNull() // un lato lungo ma pochi pixel
    assertThat(ImageHeaders.read(Samples.jpeg(16385, 1))).isNull()
    assertThat(ImageBounds.isDecodable(4096, 4096)).isTrue()
  }

  // --- Ostili ---------------------------------------------------------------------------------------------------------------

  @Test
  fun formatiNonAmmessiESpazzatura() {
    assertThat(ImageHeaders.read(ByteArray(0))).isNull()
    assertThat(ImageHeaders.read(byteArrayOf(0xFF.toByte()))).isNull()
    assertThat(ImageHeaders.read("not an image at all".toByteArray())).isNull()
    assertThat(ImageHeaders.read(Samples.png())).isNull() // PNG: non e' un ingresso ammesso
    assertThat(ImageHeaders.read(ByteArray(4096))).isNull()
    assertThat(ImageHeaders.read(ByteArray(4096) { 0xFF.toByte() })).isNull()
  }

  @Test
  fun jpegConDimensioniNulleOEnormi() {
    assertThat(ImageHeaders.read(Samples.jpeg(0, 100))).isNull()
    assertThat(ImageHeaders.read(Samples.jpeg(100, 0))).isNull()
    assertThat(ImageHeaders.read(Samples.jpeg(65535, 65535))).isNull()
    assertThat(ImageHeaders.read(Samples.jpeg(30000, 30000))).isNull()
  }

  @Test
  fun jpegSenzaSofOConSofDoppio() {
    assertThat(ImageHeaders.read(Samples.jpeg(100, 100, omitSof = true))).isNull()
    val doubled = Samples.jpeg(100, 100)
    val sofAt = Samples.indexOfMarker(doubled, 0xC0)
    val segment = doubled.copyOfRange(sofAt, sofAt + 2 + 17)
    val twice = doubled.copyOfRange(0, sofAt) + segment + doubled.copyOfRange(sofAt, doubled.size)
    assertThat(ImageHeaders.read(twice)).isNull()
  }

  @Test
  fun jpegConCodificheCheSkiaNonDecodifica() {
    for (sof in listOf(0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)) {
      assertThat(ImageHeaders.read(Samples.jpeg(100, 100, sof = sof))).isNull()
    }
    assertThat(ImageHeaders.read(Samples.jpeg(100, 100, precision = 12))).isNull()
    assertThat(ImageHeaders.read(Samples.jpeg(100, 100, components = 4))).isNull() // CMYK
    assertThat(ImageHeaders.read(Samples.jpeg(100, 100, components = 2))).isNull()
    assertThat(ImageHeaders.read(Samples.jpeg(100, 100, sampling = 0x55))).isNull() // 5x5 non esiste
    assertThat(ImageHeaders.read(Samples.jpeg(100, 100, sampling = 0x00))).isNull()
  }

  @Test
  fun jpegConLunghezzeCheMentono() {
    val ok = Samples.jpeg(200, 100)
    val sofAt = Samples.indexOfMarker(ok, 0xC0)
    // Lunghezza del SOF piu' grande dei byte che restano: il segmento esce dal file.
    val longSof = ok.copyOf().also { it[sofAt + 2] = 0xFF.toByte(); it[sofAt + 3] = 0xF0.toByte() }
    assertThat(ImageHeaders.read(longSof)).isNull()
    // Lunghezza 0 e 1 (minimo 2).
    for (bad in listOf(0, 1)) {
      val b = ok.copyOf().also { it[sofAt + 2] = 0; it[sofAt + 3] = bad.toByte() }
      assertThat(ImageHeaders.read(b)).isNull()
    }
    // Un APP1 che dichiara piu' byte di quelli del file.
    val app = Samples.jpeg(200, 100, appBytes = 100)
    val appAt = Samples.indexOfMarker(app, 0xE1)
    val liar = app.copyOf().also { it[appAt + 2] = 0x7F; it[appAt + 3] = 0xFF.toByte() }
    assertThat(ImageHeaders.read(liar)).isNull()
    // Lunghezza del SOF incoerente con il numero di componenti.
    val wrongLen = ok.copyOf().also { it[sofAt + 3] = (it[sofAt + 3] + 3).toByte() }
    assertThat(ImageHeaders.read(wrongLen)).isNull()
  }

  @Test
  fun jpegTroncatoPrimaDelSofOSenzaMarcatori() {
    val ok = Samples.jpeg(200, 100)
    val sofAt = Samples.indexOfMarker(ok, 0xC0)
    for (cut in listOf(3, 4, 10, sofAt, sofAt + 2, sofAt + 5)) assertThat(ImageHeaders.read(ok.copyOfRange(0, cut))).isNull()
    assertThat(ImageHeaders.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))).isNull() // SOI + EOI e basta
  }

  @Test
  fun jpegConDnlOConSoiDoppio() {
    val ok = Samples.jpeg(100, 100)
    val dnl = byteArrayOf(0xFF.toByte(), 0xDC.toByte(), 0, 4, 0, 100)
    assertThat(ImageHeaders.read(ok.copyOfRange(0, 2) + dnl + ok.copyOfRange(2, ok.size))).isNull()
    val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    assertThat(ImageHeaders.read(ok.copyOfRange(0, 2) + soi + ok.copyOfRange(2, ok.size))).isNull()
  }

  @Test
  fun jpegConMoltissimiMarcatoriSiArrende() {
    val ok = Samples.jpeg(100, 100)
    val comment = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 2)
    var many = ok.copyOfRange(0, 2)
    repeat(1000) { many += comment }
    many += ok.copyOfRange(2, ok.size)
    assertThat(ImageHeaders.read(many)).isNull()
  }

  @Test
  fun webpTroncatoOConRiffCheMente() {
    val ok = Samples.webpLossy(640, 480)
    assertThat(ImageHeaders.read(ok.copyOfRange(0, ok.size - 1))).isNull() // RIFF dichiara piu' byte di quelli che ci sono
    assertThat(ImageHeaders.read(ok.copyOfRange(0, 20))).isNull()
    val tiny = ok.copyOf().also { Samples.putU32(it, 4, 3) } // meno di "WEBP" + un chunk
    assertThat(ImageHeaders.read(tiny)).isNull()
    val huge = ok.copyOf().also { Samples.putU32(it, 4, 0xFFFFFFF0L) }
    assertThat(ImageHeaders.read(huge)).isNull()
    // Un chunk che dichiara piu' byte del RIFF.
    val chunkLiar = ok.copyOf().also { Samples.putU32(it, 16, 100_000) }
    assertThat(ImageHeaders.read(chunkLiar)).isNull()
  }

  @Test
  fun webpAnimatoOSenzaImmagineOConPrimoChunkSbagliato() {
    assertThat(ImageHeaders.read(Samples.webpExtended(100, 100, 100, 100, animated = true))).isNull()
    assertThat(ImageHeaders.read(Samples.webpExtended(100, 100, 100, 100, withAnmf = true))).isNull()
    assertThat(ImageHeaders.read(Samples.webpExtended(100, 100, 100, 100, omitImage = true))).isNull()
    assertThat(ImageHeaders.read(Samples.webpWithFirstChunk("EXIF"))).isNull()
  }

  @Test
  fun webpConTelaDiversaDalleDimensioniDelChunk() {
    assertThat(ImageHeaders.read(Samples.webpExtended(canvasW = 4000, canvasH = 3000, imageW = 100, imageH = 100))).isNull()
    assertThat(ImageHeaders.read(Samples.webpExtended(canvasW = 100, canvasH = 100, imageW = 100, imageH = 101))).isNull()
  }

  @Test
  fun webpCorrotto() {
    val ok = Samples.webpLossy(640, 480)
    val inter = ok.copyOf().also { it[20] = (it[20].toInt() or 1).toByte() } // non e' un fotogramma chiave
    assertThat(ImageHeaders.read(inter)).isNull()
    val noStart = ok.copyOf().also { it[23] = 0 }
    assertThat(ImageHeaders.read(noStart)).isNull()
    val zero = Samples.webpLossy(0, 480)
    assertThat(ImageHeaders.read(zero)).isNull()
    val badLossless = Samples.webpLossless(100, 100).also { it[20] = 0x2E }
    assertThat(ImageHeaders.read(badLossless)).isNull() // firma sbagliata
    val badVersion = Samples.webpLossless(100, 100, version = 1)
    assertThat(ImageHeaders.read(badVersion)).isNull()
  }

  @Test
  fun webpDimensioniDentroIDeiTetti() {
    assertThat(ImageHeaders.read(Samples.webpLossless(16383, 1))).isNotNull()
    assertThat(ImageHeaders.read(Samples.webpLossless(16384, 16384))).isNull() // 268 MP
    assertThat(ImageHeaders.read(Samples.webpExtended(canvasW = 16_000_000, canvasH = 16_000_000, imageW = 16_000_000, imageH = 16_000_000))).isNull()
  }

  // --- Mutazioni ------------------------------------------------------------------------------------------------------------

  /** Qualunque mutazione di un'intestazione valida: mai un'eccezione, e se passa le dimensioni stanno nei tetti. */
  @Test
  fun leMutazioniNonFannoMaiEccezioniEPassanoSoloDentroITetti() {
    val random = Random(20261021)
    val bases = listOf(
      Samples.jpeg(1024, 768), Samples.jpeg(300, 200, appBytes = 600), Samples.jpeg(64, 64, components = 1, sof = 0xC2),
      Samples.webpLossy(2048, 1536), Samples.webpLossless(512, 512), Samples.webpExtended(800, 600, 800, 600),
    )
    var accepted = 0
    repeat(30_000) {
      val base = bases[random.nextInt(bases.size)]
      val mutated = base.copyOf()
      repeat(1 + random.nextInt(4)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
      val bytes = if (random.nextInt(5) == 0) mutated.copyOfRange(0, random.nextInt(mutated.size + 1)) else mutated
      val header = ImageHeaders.read(bytes)
      if (header != null) {
        accepted++
        assertThat(ImageBounds.isDecodable(header.width, header.height)).isTrue()
        assertThat(header.width).isAtLeast(1)
        assertThat(header.height).isAtLeast(1)
      }
    }
    // Il controllo vede qualcosa: un quarto circa delle mutazioni non tocca i byte che contano.
    assertThat(accepted).isGreaterThan(1_000)
  }

  @Test
  fun byteCasualiNonPassanoMai() {
    val random = Random(7)
    repeat(20_000) {
      val bytes = random.nextBytes(random.nextInt(0, 200))
      assertThat(ImageHeaders.read(bytes)).isNull()
    }
  }

  // --- Costruttori di file di prova -----------------------------------------------------------------------------------------

  private object Samples {

    fun jpeg(
      width: Int,
      height: Int,
      components: Int = 3,
      sof: Int = 0xC0,
      precision: Int = 8,
      sampling: Int = 0x11,
      appBytes: Int = 0,
      omitSof: Boolean = false,
    ): ByteArray {
      val out = ByteArrayOutputStream()
      out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
      // JFIF
      out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
      if (appBytes > 0) {
        out.write(0xFF)
        out.write(0xE1)
        val len = appBytes + 2
        out.write(len shr 8)
        out.write(len and 0xFF)
        // Dentro l'APP1 c'e' perfino qualcosa che somiglia a un SOF: si salta per lunghezza.
        out.write(ByteArray(appBytes) { if (it % 7 == 0) 0xFF.toByte() else 0xC0.toByte() })
      }
      // DQT minimo
      out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 67, 0))
      out.write(ByteArray(64) { 8 })
      if (!omitSof) {
        out.write(0xFF)
        out.write(sof)
        val len = 8 + 3 * components
        out.write(len shr 8)
        out.write(len and 0xFF)
        out.write(precision)
        out.write(height shr 8)
        out.write(height and 0xFF)
        out.write(width shr 8)
        out.write(width and 0xFF)
        out.write(components)
        for (c in 0 until components) {
          out.write(c + 1)
          out.write(sampling)
          out.write(0)
        }
      }
      // DHT minimo, SOS e dati finti
      out.write(byteArrayOf(0xFF.toByte(), 0xC4.toByte(), 0, 19, 0))
      out.write(ByteArray(16))
      out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0))
      out.write(ByteArray(32) { 0x55 })
      out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
      return out.toByteArray()
    }

    fun indexOfMarker(bytes: ByteArray, marker: Int): Int {
      var i = 2
      while (i + 3 < bytes.size) {
        check(bytes[i] == 0xFF.toByte()) { "fuori sincronia" }
        val m = bytes[i + 1].toInt() and 0xFF
        if (m == marker) return i
        val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
        i += 2 + len
      }
      error("marcatore non trovato")
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
      val body = ByteArrayOutputStream()
      body.write("WEBP".toByteArray())
      for (c in chunks) body.write(c)
      val bytes = body.toByteArray()
      val out = ByteArrayOutputStream()
      out.write("RIFF".toByteArray())
      out.write(u32(bytes.size.toLong()))
      out.write(bytes)
      return out.toByteArray()
    }

    private fun chunk(type: String, payload: ByteArray): ByteArray {
      val out = ByteArrayOutputStream()
      out.write(type.toByteArray())
      out.write(u32(payload.size.toLong()))
      out.write(payload)
      if (payload.size % 2 == 1) out.write(0)
      return out.toByteArray()
    }

    private fun u32(value: Long): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    fun putU32(b: ByteArray, at: Int, value: Long) {
      u32(value).copyInto(b, at)
    }

    private fun vp8Payload(width: Int, height: Int): ByteArray {
      val p = ByteArray(10)
      p[0] = 0x10 // fotogramma chiave (bit 0 = 0), versione 0, dati visibili
      p[3] = 0x9D.toByte()
      p[4] = 0x01
      p[5] = 0x2A
      p[6] = width.toByte()
      p[7] = (width shr 8).toByte()
      p[8] = height.toByte()
      p[9] = (height shr 8).toByte()
      return p
    }

    fun webpLossy(width: Int, height: Int): ByteArray = riff(chunk("VP8 ", vp8Payload(width, height)))

    fun webpLossless(width: Int, height: Int, version: Int = 0): ByteArray {
      val bits = (width - 1).toLong() or ((height - 1).toLong() shl 14) or (version.toLong() shl 29)
      val p = ByteArray(5)
      p[0] = 0x2F
      u32(bits).copyInto(p, 1)
      return riff(chunk("VP8L", p))
    }

    fun webpExtended(
      canvasW: Int,
      canvasH: Int,
      imageW: Int,
      imageH: Int,
      animated: Boolean = false,
      withAnmf: Boolean = false,
      omitImage: Boolean = false,
    ): ByteArray {
      val p = ByteArray(10)
      p[0] = if (animated) 0x02 else 0x00
      val cw = canvasW - 1
      val ch = canvasH - 1
      p[4] = cw.toByte(); p[5] = (cw shr 8).toByte(); p[6] = (cw shr 16).toByte()
      p[7] = ch.toByte(); p[8] = (ch shr 8).toByte(); p[9] = (ch shr 16).toByte()
      val chunks = mutableListOf(chunk("VP8X", p))
      if (withAnmf) chunks += chunk("ANMF", ByteArray(16))
      if (!omitImage) chunks += chunk("VP8 ", vp8Payload(imageW, imageH))
      return riff(*chunks.toTypedArray())
    }

    fun webpWithFirstChunk(type: String): ByteArray = riff(chunk(type, ByteArray(12)), chunk("VP8 ", vp8Payload(100, 100)))

    fun png(): ByteArray =
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 100, 0, 0, 0, 100, 8, 6, 0, 0, 0)
  }
}
