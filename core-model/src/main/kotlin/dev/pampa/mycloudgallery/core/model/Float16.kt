package dev.pampa.mycloudgallery.core.model

import kotlin.math.sqrt

/**
 * Fase 6 (`docs/fase6-progetto.md` D4): i vettori si calcolano in float32 e si **salvano e pubblicano in float16** (IEEE 754
 * binary16, i bit grezzi in uno `Short`). Una conversione sola per tutta l'app (indice locale di P1 e P6, codec `vec` di P2, fake
 * dei motori), pura Kotlin: Java 17 non ha `Float.floatToFloat16` e `android.util.Half` non gira sulla JVM dei test.
 *
 * Arrotondamento al pari piu' vicino (come l'hardware); infiniti e NaN si conservano (il codec li rifiuta, E-I20); i numeri troppo
 * piccoli diventano subnormali o zero, quelli troppo grandi infinito.
 */
object Float16 {

  /** I bit float16 di [value]. */
  fun fromFloat(value: Float): Short {
    val bits = java.lang.Float.floatToRawIntBits(value)
    val sign = (bits ushr 16) and 0x8000
    val exp = (bits ushr 23) and 0xff
    var mant = bits and 0x7fffff
    if (exp == 0xff) {
      // Infinito o NaN (un NaN resta NaN: almeno un bit di mantissa).
      return (sign or 0x7c00 or (if (mant != 0) 0x200 or (mant ushr 13) else 0)).toShort()
    }
    val e = exp - 127 + 15
    if (e >= 0x1f) return (sign or 0x7c00).toShort()
    if (e <= 0) {
      if (e < -10) return sign.toShort()
      mant = mant or 0x800000
      val shift = 14 - e
      var half = mant ushr shift
      val rest = mant and ((1 shl shift) - 1)
      val midpoint = 1 shl (shift - 1)
      if (rest > midpoint || (rest == midpoint && (half and 1) == 1)) half++
      return (sign or half).toShort()
    }
    var half = (e shl 10) or (mant ushr 13)
    val rest = mant and 0x1fff
    if (rest > 0x1000 || (rest == 0x1000 && (half and 1) == 1)) half++
    return (sign or half).toShort()
  }

  /** Il valore float32 dei bit float16 [half] (esatto: ogni float16 e' un float32). */
  fun toFloat(half: Short): Float {
    val h = half.toInt() and 0xffff
    val sign = (h and 0x8000) shl 16
    val exp = (h ushr 10) and 0x1f
    val mant = h and 0x3ff
    val bits = when {
      exp == 0 && mant == 0 -> sign
      exp == 0 -> {
        // Subnormale (mant x 2^-24): si normalizza; dopo k spostamenti il valore e' 1,f x 2^(-14-k).
        var m = mant
        var k = 0
        while (m and 0x400 == 0) {
          m = m shl 1
          k++
        }
        sign or ((-14 - k + 127) shl 23) or ((m and 0x3ff) shl 13)
      }
      exp == 0x1f -> sign or 0x7f800000 or (mant shl 13)
      else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
    }
    return java.lang.Float.intBitsToFloat(bits)
  }

  fun encode(values: FloatArray): ShortArray = ShortArray(values.size) { fromFloat(values[it]) }

  fun decode(halves: ShortArray): FloatArray = FloatArray(halves.size) { toFloat(halves[it]) }

  /** La norma euclidea (in float32) dei valori float16 [halves]: il controllo "norma fra 0,99 e 1,01" del codec `vec`. */
  fun norm(halves: ShortArray): Float {
    var sum = 0.0
    for (h in halves) {
      val v = toFloat(h).toDouble()
      sum += v * v
    }
    return sqrt(sum).toFloat()
  }
}
