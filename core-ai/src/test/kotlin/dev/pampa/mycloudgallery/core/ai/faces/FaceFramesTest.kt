package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fase 8 (P2): il quadro di YuNet. 1024x1024x3 float32 in ordine **B, G, R** (0-255, senza media ne' scala), l'immagine ridotta con la media sull'area
 * o ingrandita in modo bilineare a lato lungo 1024, i bordi a 0 in basso e a destra, i tasselli dell'HQ come rettangoli con la loro scala; e il ritorno
 * dalle coordinate del quadro a quelle dell'immagine (le stesse formule di `common.quadro_yunet` di P1, provate da P1 contro OpenCV).
 */
class FaceFramesTest {

  private val side = YuNetLayout.INPUT_SIDE

  private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

  private fun at(frame: FloatArray, x: Int, y: Int): FloatArray = FloatArray(3) { frame[(y * side + x) * 3 + it] }

  @Test
  fun unaImmagineDa1024EInOrdineBgrEUnaDa1024x768ARisoluzioneInvariata() {
    val w = 1024
    val h = 768
    val pixels = IntArray(w * h) { argb((it % w) % 256, (it / w) % 256, 77) }
    pixels[7 * w + 5] = argb(10, 20, 30)
    val builder = FrameBuilder()
    val geometry = builder.build(pixels, w, FaceRegion(0, 0, w, h))
    assertThat(geometry.contentWidth).isEqualTo(1024)
    assertThat(geometry.contentHeight).isEqualTo(768)
    assertThat(geometry.scale).isEqualTo(1f)
    // B, G, R: il canale blu per primo.
    assertThat(at(builder.frame, 5, 7).toList()).containsExactly(30f, 20f, 10f).inOrder()
    assertThat(at(builder.frame, 100, 200).toList()).containsExactly(77f, 200f, 100f).inOrder()
    // I bordi in basso sono a zero; la riga 767 e' ancora immagine.
    assertThat(at(builder.frame, 100, 767)[0]).isEqualTo(77f)
    assertThat(at(builder.frame, 100, 768).toList()).containsExactly(0f, 0f, 0f)
    assertThat(at(builder.frame, 1023, 1023).toList()).containsExactly(0f, 0f, 0f)
  }

  @Test
  fun unaImmaginePiuGrandeSiRiduceConLaMediaSuLArea() {
    val w = 2048
    val h = 1536
    // Ogni quadrato 2x2 ha quattro valori diversi: la media sull'area dei quattro e' il pixel del quadro.
    val pixels = IntArray(w * h) { i ->
      val x = i % w
      val y = i / w
      argb((x * 7 + y * 3) % 256, (x + 2 * y) % 256, (5 * x) % 256)
    }
    val builder = FrameBuilder()
    val geometry = builder.build(pixels, w, FaceRegion(0, 0, w, h))
    assertThat(geometry.contentWidth).isEqualTo(1024)
    assertThat(geometry.contentHeight).isEqualTo(768)
    for ((dx, dy) in listOf(0 to 0, 17 to 3, 500 to 400, 1023 to 767)) {
      var r = 0.0
      var g = 0.0
      var b = 0.0
      for (sy in 2 * dy..2 * dy + 1) for (sx in 2 * dx..2 * dx + 1) {
        val p = pixels[sy * w + sx]
        r += (p shr 16) and 0xff
        g += (p shr 8) and 0xff
        b += p and 0xff
      }
      val got = at(builder.frame, dx, dy)
      assertThat(got[2].toDouble()).isWithin(1e-3).of(r / 4)
      assertThat(got[1].toDouble()).isWithin(1e-3).of(g / 4)
      assertThat(got[0].toDouble()).isWithin(1e-3).of(b / 4)
    }
    assertThat(at(builder.frame, 10, 768).toList()).containsExactly(0f, 0f, 0f)
    assertThat(geometry.scale).isWithin(1e-6f).of(0.5f)
  }

  @Test
  fun unaImmaginePiuPiccolaSiIngrandisceInterpolandoEIlCentroDelPixelContaMezzoPixel() {
    val w = 512
    val h = 384
    // R = x, G = y: bilineare di una funzione lineare = la funzione, sul centro del pixel di arrivo (i + 0,5) / 2 - 0,5.
    val pixels = IntArray(w * h) { argb(it % w % 256, it / w % 256, 0) }
    val builder = FrameBuilder()
    val geometry = builder.build(pixels, w, FaceRegion(0, 0, w, h))
    assertThat(geometry.contentWidth).isEqualTo(1024)
    assertThat(geometry.contentHeight).isEqualTo(768)
    for ((dx, dy) in listOf(100 to 50, 333 to 600, 1000 to 700)) {
      val got = at(builder.frame, dx, dy)
      assertThat(got[2]).isWithin(0.02f).of(((dx + 0.5f) / 2f - 0.5f) % 256f)
      assertThat(got[1]).isWithin(0.02f).of(((dy + 0.5f) / 2f - 0.5f) % 256f)
    }
  }

  @Test
  fun unaImmagineAltaEStrettaStaInAltoASinistraConIBordiAZero() {
    val w = 300
    val h = 1000
    val builder = FrameBuilder()
    val geometry = builder.build(IntArray(w * h) { argb(200, 100, 50) }, w, FaceRegion(0, 0, w, h))
    assertThat(geometry.contentHeight).isEqualTo(1024)
    assertThat(geometry.contentWidth).isEqualTo(307) // 300 * 1,024
    val inside = at(builder.frame, 306, 500)
    assertThat(inside[0]).isWithin(1e-3f).of(50f)
    assertThat(inside[1]).isWithin(1e-3f).of(100f)
    assertThat(inside[2]).isWithin(1e-3f).of(200f)
    assertThat(at(builder.frame, 307, 500).toList()).containsExactly(0f, 0f, 0f)
    assertThat(at(builder.frame, 900, 1000).toList()).containsExactly(0f, 0f, 0f)
    // Le coordinate tornano all'immagine: il bordo destro del contenuto e' x = 300.
    assertThat(geometry.imageX(307f)).isWithin(1e-3f).of(300f)
    assertThat(geometry.imageY(1024f)).isWithin(1e-3f).of(1000f)
  }

  @Test
  fun unTasselloEUnRettangoloDellImmagineConLaSuaScala() {
    val w = 2048
    val h = 1536
    val pixels = IntArray(w * h) { i -> argb((i % w) / 8, (i / w) / 8, 0) } // R = x / 8, G = y / 8: lineari a tratti
    val builder = FrameBuilder()
    val region = FaceRegion(960, 704, 1088, 832)
    val geometry = builder.build(pixels, w, region)
    assertThat(geometry.contentWidth).isEqualTo(1024)
    assertThat(geometry.contentHeight).isEqualTo(783) // 832 * (1024 / 1088) arrotondato
    // L'angolo in alto a sinistra del quadro e' l'angolo in alto a sinistra del tassello (960, 704): R = 120, G = 88.
    val corner = at(builder.frame, 0, 0)
    assertThat(corner[2]).isWithin(0.8f).of(120f)
    assertThat(corner[1]).isWithin(0.8f).of(88f)
    // E l'ultimo pixel del contenuto e' l'angolo in basso a destra: x = 2047 -> R = 255 (tetto del gradiente di prova), y = 1535 -> G = 191.
    val last = at(builder.frame, 1023, 782)
    assertThat(last[1]).isWithin(1.5f).of(191f)
    assertThat(at(builder.frame, 0, 783).toList()).containsExactly(0f, 0f, 0f)
    // Dal quadro all'immagine: il centro del quadro cade nel centro del tassello.
    assertThat(geometry.imageX(512f)).isWithin(1e-3f).of(960f + 544f)
    assertThat(geometry.imageY(391.5f)).isWithin(0.5f).of(704f + 416f)
    assertThat(geometry.imageX(0f)).isEqualTo(960f)
  }

  @Test
  fun ilQuadroSiRiusaESiAzzera() {
    val builder = FrameBuilder()
    builder.build(IntArray(1024 * 1024) { argb(255, 255, 255) }, 1024, FaceRegion(0, 0, 1024, 1024))
    assertThat(at(builder.frame, 1000, 1000)[0]).isEqualTo(255f)
    builder.build(IntArray(200 * 100) { argb(9, 9, 9) }, 200, FaceRegion(0, 0, 200, 100))
    // 200x100 -> 1024x512: sotto la riga 512 e' tutto a zero, anche dove il quadro di prima era bianco.
    assertThat(at(builder.frame, 1000, 1000).toList()).containsExactly(0f, 0f, 0f)
    assertThat(at(builder.frame, 500, 511)[0]).isWithin(1e-4f).of(9f)
  }

  @Test
  fun iTasselliCopronoTuttaLImmagineConLaSovrapposizione() {
    for ((w, h) in listOf(2048 to 2048, 2048 to 1536, 1536 to 2048, 1300 to 1100, 1025 to 1025, 2000 to 800)) {
      val plan = FaceTiling.plan(w, h)
      for (r in plan.regions) {
        assertThat(r.x + r.width).isAtMost(w)
        assertThat(r.y + r.height).isAtMost(h)
        // Il quadro porta il lato lungo del tassello a 1024 senza che il tassello superi 1088 px.
        assertThat(maxOf(r.width, r.height)).isAtMost(1088)
      }
      for (y in 0 until h step 7) for (x in 0 until w step 7) {
        assertThat(plan.regions.any { x in it.x until it.x + it.width && y in it.y until it.y + it.height }).isTrue()
      }
      assertThat(plan.regions.size).isEqualTo(plan.columns * plan.rows)
    }
  }
}
