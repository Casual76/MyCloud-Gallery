package dev.pampa.mycloudgallery.core.ai.cutout

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/** Fase 6 (P13): il ridimensionamento e il filtro guidato, puri, sulla JVM. */
class ResamplerAndFilterTest {

  private fun argb(r: Int, g: Int, b: Int): Int = (0xff shl 24) or (r shl 16) or (g shl 8) or b

  @Test
  fun iPesiDiOgniAsseSommanoUno() {
    for ((src, dst) in listOf(4096 to 512, 640 to 512, 100 to 512, 513 to 512, 7 to 3, 2000 to 33)) {
      val taps = Resampler.taps(src, dst)
      for (i in 0 until dst) {
        var sum = 0f
        for (t in 0 until taps.count[i]) {
          sum += taps.weight[taps.offset[i] + t]
          assertThat(taps.first[i] + t).isIn(0 until src)
        }
        assertThat(sum).isWithin(1e-4f).of(1f)
      }
    }
  }

  @Test
  fun inRiduzioneEUnaMediaSullAreaSenzaPixelSaltati() {
    // 4x4 -> 2x2: ogni blocco 2x2 diventa la sua media.
    val pixels = IntArray(16) { argb(it * 10, 255 - it * 10, 0) }
    val out = Resampler.resize(pixels, 4, 4, 2, 2)
    fun avg(vararg indexes: Int, shift: Int) = indexes.map { (pixels[it] shr shift) and 0xff }.average().toFloat()
    assertThat(out[0]).isWithin(1e-3f).of(avg(0, 1, 4, 5, shift = 16))
    assertThat(out[1]).isWithin(1e-3f).of(avg(0, 1, 4, 5, shift = 8))
    assertThat(out[3]).isWithin(1e-3f).of(avg(2, 3, 6, 7, shift = 16))
    assertThat(out[6]).isWithin(1e-3f).of(avg(8, 9, 12, 13, shift = 16))
  }

  @Test
  fun unaFotoGrandeSiRiduceSenzaAliasing() {
    // Scacchiera a 1 pixel: ridotta di 8x deve dare un grigio medio uniforme (con il campionamento puntuale darebbe nero o bianco).
    val w = 512
    val h = 512
    val pixels = IntArray(w * h) { if ((it % w + it / w) % 2 == 0) argb(255, 255, 255) else argb(0, 0, 0) }
    val out = Resampler.resize(pixels, w, h, 64, 64)
    for (i in out.indices) assertThat(out[i]).isWithin(1f).of(127.5f)
  }

  @Test
  fun inIngrandimentoInterpolaEConservaIColoriUniformi() {
    val pixels = IntArray(4 * 4) { argb(10, 200, 30) }
    val out = Resampler.resize(pixels, 4, 4, 9, 7)
    assertThat(out.size).isEqualTo(9 * 7 * 3)
    for (i in 0 until 9 * 7) {
      assertThat(out[i * 3]).isWithin(1e-3f).of(10f)
      assertThat(out[i * 3 + 1]).isWithin(1e-3f).of(200f)
      assertThat(out[i * 3 + 2]).isWithin(1e-3f).of(30f)
    }
  }

  @Test
  fun unaPanoramicaSiRiducePerUnAsseESiAllungaPerLAltro() {
    val pixels = IntArray(40 * 4) { argb(if (it % 40 < 20) 255 else 0, 0, 0) }
    val out = Resampler.resize(pixels, 40, 4, 8, 8)
    assertThat(out[0]).isWithin(1e-3f).of(255f)
    assertThat(out[7 * 3]).isWithin(1e-3f).of(0f)
  }

  @Test
  fun laLuminanzaDeiBlocchiEUnaMedia() {
    val white = argb(255, 255, 255)
    val black = argb(0, 0, 0)
    val pixels = intArrayOf(white, black, black, white)
    val luma = Resampler.lumaBlocks(pixels, 2, 2, 2)
    assertThat(luma.size).isEqualTo(1)
    assertThat(luma[0]).isWithin(1e-3f).of(0.5f)
    assertThat(Resampler.luma(white)).isWithin(1e-3f).of(1f)
  }

  @Test
  fun laMappaDiProbabilitaSiRidimensionaConInterpolazioneBilineare() {
    val src = floatArrayOf(0f, 1f, 0f, 1f)
    val out = Resampler.resizeMap(src, 2, 2, 4, 4)
    assertThat(out[0]).isWithin(1e-4f).of(0f)
    assertThat(out[3]).isWithin(1e-4f).of(1f)
    assertThat(out[1]).isGreaterThan(0f)
    assertThat(out[1]).isLessThan(1f)
  }

  // --- Il filtro guidato ------------------------------------------------------------------------------------------------------

  @Test
  fun laMediaSuFinestraSiAccordaConIlCalcoloDiretto() {
    val random = Random(7)
    val w = 17
    val h = 13
    val src = FloatArray(w * h) { random.nextFloat() }
    for (r in listOf(1, 2, 5, 20)) {
      val fast = GuidedFilter.box(src, w, h, r)
      for (y in 0 until h) {
        for (x in 0 until w) {
          var sum = 0.0
          var n = 0
          for (yy in maxOf(0, y - r)..minOf(h - 1, y + r)) {
            for (xx in maxOf(0, x - r)..minOf(w - 1, x + r)) {
              sum += src[yy * w + xx]
              n++
            }
          }
          assertThat(fast[y * w + x]).isWithin(1e-4f).of((sum / n).toFloat())
        }
      }
    }
  }

  @Test
  fun ilFiltroPortaIlBordoSullaGuida() {
    // La guida ha un gradino a x = 30; la maschera grossolana e' sbagliata di 2 pixel (rampa fra x = 29 e x = 35, a meta' a x = 32).
    val w = 64
    val h = 32
    val guide = FloatArray(w * h) { if (it % w < 30) 0.1f else 0.9f }
    val coarse = FloatArray(w * h) { val x = it % w; ((x - 32) / 6f + 0.5f).coerceIn(0f, 1f) }
    val out = GuidedFilter.filter(guide, coarse, w, h, 6, 1e-3f)
    val row = 10 * w
    // La maschera grossolana, a x = 31, diceva ancora "fuori"; il filtro mette il bordo dove l'ha la guida: a x = 29 fuori, a x = 31 dentro.
    assertThat(coarse[row + 31]).isLessThan(0.45f)
    assertThat(out[row + 29]).isLessThan(0.15f)
    assertThat(out[row + 31]).isGreaterThan(0.55f)
    assertThat(out[row + 38]).isGreaterThan(0.85f)
    assertThat(out[row + 20]).isLessThan(0.05f)
  }

  @Test
  fun conUnaGuidaPiattaIlFiltroDaLaMediaDellIngresso() {
    // Senza bordi nella guida (varianza 0) i coefficienti sono (0, media): il risultato e' l'ingresso mediato due volte sulla finestra.
    val w = 20
    val h = 20
    val guide = FloatArray(w * h) { 0.5f }
    val input = FloatArray(w * h) { if (it % w < 10) 0f else 1f }
    val out = GuidedFilter.filter(guide, input, w, h, 3, 1e-3f)
    val expected = GuidedFilter.box(GuidedFilter.box(input, w, h, 3), w, h, 3)
    for (i in out.indices) assertThat(abs(out[i] - expected[i])).isLessThan(1e-3f)
  }
}
