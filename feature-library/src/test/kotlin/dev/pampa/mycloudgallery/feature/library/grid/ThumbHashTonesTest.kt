package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * I toni che il vetro adattivo (G1, direzione C) legge dall'intestazione di un ThumbHash senza decodificarlo: il colore
 * medio da L, P, Q e gli estremi plausibili della luminanza. Conta soprattutto che il giallo risulti chiaro e il blu
 * scuro: con la sola media dei canali sarebbero due grigi uguali, e la pellicola sbaglierebbe verso.
 */
class ThumbHashTonesTest {

  /** I primi cinque byte di un ThumbHash: L (6 bit), P e Q (6 bit, centrati su 31,5), la scala di L (5 bit). */
  private fun header(l: Int, p: Int, q: Int, scale: Int): ByteArray {
    val h24 = l or (p shl 6) or (q shl 12) or (scale shl 18)
    return byteArrayOf(h24.toByte(), (h24 shr 8).toByte(), (h24 shr 16).toByte(), 0x07, 0x00)
  }

  @Test
  fun biancoENero() {
    assertThat(thumbHashTones(header(63, 32, 32, 0))[0].luminance()).isGreaterThan(0.9f)
    assertThat(thumbHashTones(header(0, 32, 32, 0))[0].luminance()).isLessThan(0.01f)
  }

  @Test
  fun ilGialloEChiaroIlBluEScuro() {
    // Giallo: l = 2/3, p = +1, q = 0. Blu: l = 1/3, p = -2/3 + ... (b alto), q = 0.
    val yellow = thumbHashTones(header(42, 63, 32, 0))[0]
    val blue = thumbHashTones(header(21, 0, 32, 0))[0]
    assertThat(yellow.luminance()).isGreaterThan(0.75f)
    assertThat(blue.luminance()).isLessThan(0.2f)
  }

  @Test
  fun laScalaAllargaGliEstremi() {
    val tones = thumbHashTones(header(32, 32, 32, 31))
    val lum = tones.map { it.luminance() }
    assertThat(lum[1]).isLessThan(lum[0])
    assertThat(lum[2]).isGreaterThan(lum[0])
  }

  @Test
  fun unHashTroppoCortoEIlCasoPeggiore() {
    val tones = thumbHashTones(byteArrayOf(1, 2, 3))
    assertThat(tones.minOf { it.luminance() }).isLessThan(0.01f)
    assertThat(tones.maxOf { it.luminance() }).isGreaterThan(0.99f)
  }
}
