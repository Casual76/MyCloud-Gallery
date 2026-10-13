package dev.pampa.mycloudgallery.feature.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 3, R2: il ripiego che decodifica GIF e WebP animati (`ImageDecoderAnimatedLoader`)
 * ha lo **stesso tetto** del percorso primario di core-imaging. Una tela ostile (VP8X arriva a 16383×16383) non si anima
 * e non si decodifica mai a grandezza piena.
 */
class AnimatedSizingTest {

  private val screen = 2_400
  private val cap = 16L * 1024 * 1024

  @Test
  fun unaTelaOstileNonSiAnimaESiFermaAllaTagliaDelloSchermo() {
    assertThat(AnimatedSizing.tooLargeToAnimate(16_383, 16_383, cap)).isTrue()
    val (w, h) = AnimatedSizing.stillTarget(16_383, 16_383, screen, cap)!!
    assertThat(maxOf(w, h)).isAtMost(screen)
    assertThat(w.toLong() * h).isAtMost(cap)
  }

  @Test
  fun unaGifNormaleSiAnimaEntroUnQuartoDelTetto() {
    assertThat(AnimatedSizing.tooLargeToAnimate(3_000, 3_000, cap)).isFalse()
    val (w, h) = AnimatedSizing.animatedTarget(3_000, 3_000, screen, cap)!!
    assertThat(maxOf(w, h)).isAtMost(screen)
    assertThat(w.toLong() * h).isAtMost(cap / 4)
    // La proporzione resta.
    assertThat(w).isEqualTo(h)
  }

  @Test
  fun unaGifPiccolaNonSiTocca() {
    assertThat(AnimatedSizing.animatedTarget(480, 270, screen, cap)).isNull()
    assertThat(AnimatedSizing.stillTarget(480, 270, screen, cap)).isNull()
  }

  @Test
  fun dimensioniAssurdeNonRompono() {
    assertThat(AnimatedSizing.animatedTarget(0, 100, screen, cap)).isNull()
    assertThat(AnimatedSizing.animatedTarget(100, -1, screen, cap)).isNull()
    // Una striscia lunghissima: il lato lungo comanda, mai un lato a zero.
    val (w, h) = AnimatedSizing.animatedTarget(100_000, 10, screen, cap)!!
    assertThat(w).isAtMost(screen)
    assertThat(h).isAtLeast(1)
  }
}
