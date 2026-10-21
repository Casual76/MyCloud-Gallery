package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Test

/** D4: la conversione float32 <-> float16 dei vettori, uguale per indice locale, codec e fake. */
class Float16Test {

  @Test
  fun iValoriNotiHannoIBitGiusti() {
    assertThat(Float16.fromFloat(0f)).isEqualTo(0x0000.toShort())
    assertThat(Float16.fromFloat(-0f)).isEqualTo(0x8000.toShort())
    assertThat(Float16.fromFloat(1f)).isEqualTo(0x3c00.toShort())
    assertThat(Float16.fromFloat(-2f)).isEqualTo(0xc000.toShort())
    assertThat(Float16.fromFloat(65504f)).isEqualTo(0x7bff.toShort())
    assertThat(Float16.fromFloat(1e6f)).isEqualTo(0x7c00.toShort())
    assertThat(Float16.fromFloat(Float.NEGATIVE_INFINITY)).isEqualTo(0xfc00.toShort())
    assertThat(Float16.toFloat(Float16.fromFloat(Float.NaN)).isNaN()).isTrue()
    // Il piu' piccolo subnormale (2^-24) e lo zero sotto di lui.
    assertThat(Float16.toFloat(0x0001.toShort())).isEqualTo(5.9604645e-8f)
    assertThat(Float16.fromFloat(5.9604645e-8f)).isEqualTo(0x0001.toShort())
    assertThat(Float16.fromFloat(1e-9f)).isEqualTo(0x0000.toShort())
  }

  /** Ogni float16 torna identico dopo un giro float32 (65536 valori, NaN a parte). */
  @Test
  fun ogniFloat16TornaIdentico() {
    for (bits in 0 until 0x10000) {
      val half = bits.toShort()
      val value = Float16.toFloat(half)
      if (value.isNaN()) continue
      assertThat(Float16.fromFloat(value)).isEqualTo(half)
    }
  }

  /** Un vettore normalizzato a 768 dimensioni: errore relativo sotto 1e-3 e norma fra 0,99 e 1,01 (il controllo del codec). */
  @Test
  fun unVettoreNormalizzatoRestaNormalizzato() {
    val random = Random(7)
    val raw = FloatArray(768) { random.nextFloat() * 2 - 1 }
    val n = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
    val unit = FloatArray(768) { raw[it] / n }
    val halves = Float16.encode(unit)
    val back = Float16.decode(halves)
    for (i in unit.indices) assertThat(abs(back[i] - unit[i])).isAtMost(abs(unit[i]) * 1e-3f + 1e-6f)
    assertThat(Float16.norm(halves)).isWithin(0.01f).of(1f)
  }
}
