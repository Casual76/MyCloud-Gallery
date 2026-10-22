package dev.pampa.mycloudgallery.core.ai.cutout

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AlphaMask
import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 6 (P13, D44, D47): il PNG del ritaglio e' trasparente e **senza metadati** (un test che RILEGGE il file: i blocchi, i pixel), il
 * contorno bianco dell'adesivo, il ritaglio sul rettangolo del soggetto e le taglie grandi.
 */
class PngAndRenderTest {

  /** I tipi dei blocchi di un PNG, in ordine (il lettore verifica anche firma e CRC di ognuno). */
  private fun chunkTypes(png: ByteArray): List<String> = PngReader.read(png).chunkTypes

  private fun decode(png: ByteArray) = PngReader.read(png)

  @Test
  fun ilPngPortaSoloIBlocchiObbligatori() {
    val random = Random(3)
    val w = 37
    val h = 23
    val png = PngEncoder.encode(w, h, PngEncoder.Rows { y, into -> for (i in into.indices) into[i] = (random.nextInt() + y).toByte() })
    val types = chunkTypes(png)
    assertThat(types.first()).isEqualTo("IHDR")
    assertThat(types.last()).isEqualTo("IEND")
    // Nessun blocco accessorio: niente tEXt, eXIf, iCCP, gAMA, pHYs, tIME... (D47: nessun metadato).
    assertThat(types.toSet() - setOf("IHDR", "IDAT", "IEND")).isEmpty()
    assertThat(types.count { it == "IDAT" }).isAtLeast(1)
  }

  @Test
  fun ilPngSiRilegge_RgbaIdentico() {
    val random = Random(11)
    val w = 61
    val h = 40
    val rgba = ByteArray(w * h * 4) { random.nextInt().toByte() }
    val png = PngEncoder.encode(w, h, PngEncoder.Rows { y, into -> System.arraycopy(rgba, y * w * 4, into, 0, w * 4) })
    val image = decode(png)
    assertThat(image.width).isEqualTo(w)
    assertThat(image.height).isEqualTo(h)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val argb = image.getRGB(x, y)
        val i = (y * w + x) * 4
        assertThat((argb ushr 24) and 0xff).isEqualTo(rgba[i + 3].toInt() and 0xff)
        assertThat((argb shr 16) and 0xff).isEqualTo(rgba[i].toInt() and 0xff)
        assertThat((argb shr 8) and 0xff).isEqualTo(rgba[i + 1].toInt() and 0xff)
        assertThat(argb and 0xff).isEqualTo(rgba[i + 2].toInt() and 0xff)
      }
    }
  }

  @Test
  fun unaRigaLarghePerUnPixelEUnaFotoAltaSiScrivono() {
    val tall = PngEncoder.encode(1, 500, PngEncoder.Rows { y, into -> into[0] = y.toByte(); into[3] = 0xff.toByte() })
    assertThat(decode(tall).height).isEqualTo(500)
    val wide = PngEncoder.encode(3000, 2, PngEncoder.Rows { _, into -> for (i in into.indices) into[i] = (i / 4).toByte() })
    assertThat(decode(wide).width).isEqualTo(3000)
    assertThrows(IllegalArgumentException::class.java) { PngEncoder.encode(0, 5, PngEncoder.Rows { _, _ -> }) }
  }

  // --- Il ritaglio ------------------------------------------------------------------------------------------------------------------

  /** Un'immagine rossa con un disco verde al centro e la sua maschera (il disco). */
  private fun scene(w: Int, h: Int, cx: Float, cy: Float, r: Float): Pair<CutoutImage, AlphaMask> {
    val pixels = IntArray(w * h) { 0xFFCC2222.toInt() }
    val alpha = ByteArray(w * h)
    for (i in pixels.indices) {
      if (hypot((i % w + 0.5f) - cx, (i / w + 0.5f) - cy) <= r) {
        pixels[i] = 0xFF22AA44.toInt()
        alpha[i] = 0xff.toByte()
      }
    }
    return AiTestInputs.cutout(w, h, pixels) to AlphaMask(w, h, alpha)
  }

  @Test
  fun ilRitaglioEIlSoggettoSulSuoRettangoloConLaMascheraComeTrasparenza() {
    val (image, mask) = scene(200, 150, 100f, 70f, 30f)
    val png = CutoutRender.png(image, mask, outline = false)
    assertThat(chunkTypes(png).toSet() - setOf("IHDR", "IDAT", "IEND")).isEmpty()
    val out = decode(png)
    val layout = CutoutRender.layoutOf(image, mask, outline = false)
    assertThat(out.width).isEqualTo(layout.width)
    assertThat(out.height).isEqualTo(layout.height)
    // Il disco ha un diametro di 60: il file e' appena piu' grande, non l'immagine intera.
    assertThat(out.width).isIn(60..66)
    assertThat(out.height).isIn(60..66)
    val center = out.getRGB(out.width / 2, out.height / 2)
    assertThat(center ushr 24).isEqualTo(255)
    assertThat(center and 0xffffff).isEqualTo(0x22AA44)
    // L'angolo e' trasparente.
    assertThat(out.getRGB(0, 0) ushr 24).isEqualTo(0)
  }

  @Test
  fun sottoIPixelTrasparentiNonRestaNienteDellaFoto() {
    val (image, mask) = scene(120, 90, 60f, 45f, 20f)
    val png = CutoutRender.png(image, mask, outline = false)
    val out = decode(png)
    for (y in 0 until out.height) {
      for (x in 0 until out.width) {
        val argb = out.getRGB(x, y)
        if (argb ushr 24 == 0) assertThat(argb and 0xffffff).isEqualTo(0)
      }
    }
  }

  @Test
  fun ilContornoEUnBordoBiancoOpacoIntornoAlSoggetto() {
    val (image, mask) = scene(400, 300, 200f, 150f, 80f)
    val plain = CutoutRender.layoutOf(image, mask, outline = false)
    val layout = CutoutRender.layoutOf(image, mask, outline = true)
    // Circa l'1,2% del lato lungo del soggetto (160 px): 2 px, ma almeno 3.
    assertThat(layout.outlineRadius).isEqualTo(3)
    assertThat(layout.width).isGreaterThan(plain.width)
    val out = decode(CutoutRender.png(image, mask, outline = true))
    // A due pixel fuori dal bordo del disco (sul lato sinistro, a meta' altezza) c'e' il bianco opaco del contorno.
    val cy = out.height / 2
    val left = (0 until out.width).first { out.getRGB(it, cy) ushr 24 != 0 }
    assertThat(out.getRGB(left + 1, cy) and 0xffffff).isEqualTo(0xffffff)
    assertThat(out.getRGB(left + 1, cy) ushr 24).isEqualTo(255)
    // Il centro e' sempre il soggetto.
    assertThat(out.getRGB(out.width / 2, cy) and 0xffffff).isEqualTo(0x22AA44)
  }

  @Test
  fun ilRaggioDelContornoCresceConIlSoggetto() {
    val (image, mask) = scene(2000, 1500, 1000f, 750f, 600f)
    val layout = CutoutRender.layoutOf(image, mask, outline = true)
    assertThat(layout.outlineRadius).isEqualTo(14)
    val out = decode(CutoutRender.png(image, mask, outline = true))
    val cy = out.height / 2
    val left = (0 until out.width).first { out.getRGB(it, cy) ushr 24 != 0 }
    // Spessore del bianco a sinistra del disco: il raggio.
    var white = 0
    var x = left
    while (x < out.width && (out.getRGB(x, cy) and 0xffffff) == 0xffffff) {
      white++
      x++
    }
    assertThat(white).isIn(12..16)
  }

  @Test
  fun unSoggettoSulBordoDellaFotoHaIlContornoAncheOltreIlBordo() {
    val (image, mask) = scene(100, 100, 20f, 20f, 25f)
    val layout = CutoutRender.layoutOf(image, mask, outline = true)
    val out = decode(CutoutRender.png(image, mask, outline = true))
    // Il disco tocca il bordo sinistro: il file prosegue di qualche pixel OLTRE la foto, e li' c'e' il bianco del contorno.
    assertThat(out.width).isEqualTo(layout.width)
    val row = out.height / 2
    assertThat(out.getRGB(3, row) ushr 24).isEqualTo(255)
    assertThat(out.getRGB(3, row) and 0xffffff).isEqualTo(0xffffff)
    assertThat(out.getRGB(0, row) ushr 24).isEqualTo(0)
  }

  @Test
  fun unaMascheraDiTagliaDiversaDallImmagineNonSiAccetta() {
    val (image, _) = scene(50, 40, 25f, 20f, 10f)
    assertThrows(IllegalArgumentException::class.java) { CutoutRender.png(image, AlphaMask(10, 10, ByteArray(100)), false) }
  }

  @Test
  fun unaFotoDaDodiciMegapixelSiScriveEIlPngSiRilegge() {
    val (image, mask) = scene(4096, 3072, 2048f, 1536f, 1100f)
    val png = CutoutRender.png(image, mask, outline = true)
    assertThat(chunkTypes(png).toSet() - setOf("IHDR", "IDAT", "IEND")).isEmpty()
    val out = decode(png)
    assertThat(out.width).isAtMost(4096 + 2 * (CutoutRender.OUTLINE_MIN_RADIUS + 40))
    assertThat(out.getRGB(out.width / 2, out.height / 2) and 0xffffff).isEqualTo(0x22AA44)
  }

  @Test
  fun unaMascheraVuotaDaUnFileTrasparenteDellaTagliaDellImmagine() {
    val image = AiTestInputs.cutout(20, 10, IntArray(200) { 0xFF112233.toInt() })
    val out = decode(CutoutRender.png(image, AlphaMask(20, 10, ByteArray(200)), outline = false))
    assertThat(out.width).isEqualTo(20)
    assertThat(out.getRGB(5, 5) ushr 24).isEqualTo(0)
  }
}
