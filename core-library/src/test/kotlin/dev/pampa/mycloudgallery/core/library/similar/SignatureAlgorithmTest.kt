package dev.pampa.mycloudgallery.core.library.similar

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.VisualSignature
import org.junit.Assert.assertThrows
import org.junit.Test

/** La firma visiva, parte pura (Fase 5A, P15): forma, determinismo, indipendenza dalla taglia, colori noti, confini. Sulla JVM, senza Android. */
class SignatureAlgorithmTest {

  @Test
  fun unaFotoHaUnFotogrammoEUnVideoDueSottoIlTettoDelDatabase() {
    val photo = VisualSignatures.of(pattern(1))
    assertThat(photo.algorithm).isEqualTo(VisualSignatures.ALGORITHM)
    assertThat(photo.frames).isEqualTo(1)
    assertThat(photo.size).isEqualTo(VisualSignatures.FRAME_BYTES)
    assertThat(VisualSignatures.FRAME_BYTES).isEqualTo(64 * 64 + 2 * 32 * 32)
    val video = VisualSignatures.of(listOf(pattern(1), pattern(2)))
    assertThat(video.frames).isEqualTo(2)
    assertThat(video.size).isEqualTo(2 * VisualSignatures.FRAME_BYTES)
    assertThat(video.size).isAtMost(VisualSignature.MAX_BYTES)
    assertThrows(IllegalArgumentException::class.java) { VisualSignatures.of(emptyList()) }
  }

  @Test
  fun lAlgoritmoEDeterministicoEIdenticoSuPixelIdentici() {
    val a = VisualSignatures.of(pattern(3))
    val b = VisualSignatures.of(pattern(3))
    assertThat(a).isEqualTo(b)
    val d = SignatureComparison.distance(a, b)!!
    assertThat(d.mean).isEqualTo(0f)
    assertThat(d.peak).isAtMost(0.0006f)
  }

  @Test
  fun laTagliaDellaSorgenteNonCambiaLaFirma() {
    // La stessa scena a 1024x768, a 512x384, a 256x192 (area media) e a una misura storta: le firme sono praticamente uguali.
    val scene = Scene(5, Subject(0.5, 0.78))
    val big = SceneRenderer.render(scene, 1024, 768)
    val base = VisualSignatures.of(big)
    for (other in listOf(RasterOps.resizeArea(big, 512, 384), RasterOps.resizeArea(big, 256, 192), RasterOps.resizeArea(big, 997, 701))) {
      val d = SignatureComparison.distance(base, VisualSignatures.of(other))!!
      assertThat(SimilarityRules.verdict(d)).isEqualTo(VisualVerdict.SAME)
      assertThat(d.mean).isLessThan(0.002f)
    }
  }

  @Test
  fun lePiccoleEGrandiMisureNonRompono() {
    // Un pixel solo, una striscia, meno pixel che celle, una misura che non e' multipla di 64.
    for ((w, h) in listOf(1 to 1, 3000 to 2, 2 to 3000, 63 to 31, 65 to 129, 100 to 75)) {
      val raster = Raster(w, h, IntArray(w * h) { (it * 2654435761L).toInt() and 0xFFFFFF })
      val signature = VisualSignatures.of(raster)
      assertThat(signature.size).isEqualTo(VisualSignatures.FRAME_BYTES)
    }
  }

  @Test
  fun iColoriNotiHannoLaLuminositaELaCromaAttese() {
    fun solid(rgb: Int) = VisualSignatures.of(Raster(64, 64, IntArray(64 * 64) { rgb })).bytes()
    val white = solid(0xFFFFFF)
    val black = solid(0x000000)
    val red = solid(0xFF0000)
    val gray = solid(0x777777)
    val lumaEnd = 64 * 64
    assertThat(white[0].toInt() and 0xFF).isEqualTo(255)
    assertThat(white[lumaEnd].toInt() and 0xFF).isEqualTo(VisualSignatures.CHROMA_ZERO)
    assertThat(white[lumaEnd + 32 * 32].toInt() and 0xFF).isEqualTo(VisualSignatures.CHROMA_ZERO)
    assertThat(black[0].toInt() and 0xFF).isEqualTo(0)
    // Il grigio medio (0x77 = 119 sRGB, il 18% di luce lineare) sta a circa il 57% della luminosita' percettiva di OKLab.
    assertThat(gray[0].toInt() and 0xFF).isIn(Range.closed(140, 150))
    // Il rosso puro ha L = 0,628 in OKLab (160 su 255) e `a` molto positivo.
    assertThat(red[0].toInt() and 0xFF).isIn(Range.closed(155, 165))
    assertThat(red[lumaEnd].toInt() and 0xFF).isGreaterThan(VisualSignatures.CHROMA_ZERO + 50)
  }

  @Test
  fun specchiareECambiareEsposizioneCambiaLaFirma() {
    val scene = Scene(6, Subject(0.3, 0.78))
    val base = VisualSignatures.of(SceneRenderer.render(scene, 512, 384))
    val mirrored = VisualSignatures.of(SceneRenderer.render(scene, 512, 384, Framing(mirrored = true)))
    val brighter = VisualSignatures.of(RasterOps.exposure(SceneRenderer.render(scene, 512, 384), 0.5))
    assertThat(SimilarityRules.visual(base, mirrored)).isEqualTo(VisualVerdict.DIFFERENT)
    assertThat(SimilarityRules.visual(base, brighter)).isEqualTo(VisualVerdict.DIFFERENT)
    // Il confronto e' simmetrico.
    val d1 = SignatureComparison.distance(base, mirrored)!!
    val d2 = SignatureComparison.distance(mirrored, base)!!
    assertThat(d1.mean).isEqualTo(d2.mean)
    assertThat(d1.peak).isEqualTo(d2.peak)
  }

  @Test
  fun leMisureEiTettiSiControllanoPrimaDiAllocare() {
    assertThrows(IllegalArgumentException::class.java) { Raster(0, 5, IntArray(0)) }
    assertThrows(IllegalArgumentException::class.java) { Raster(4, 4, IntArray(3)) }
    assertThrows(IllegalArgumentException::class.java) { Raster(Int.MAX_VALUE, Int.MAX_VALUE, IntArray(1)) }
    assertThat(Raster(2, 2, IntArray(4)).toString()).isEqualTo("Raster(2x2)")
  }
}
