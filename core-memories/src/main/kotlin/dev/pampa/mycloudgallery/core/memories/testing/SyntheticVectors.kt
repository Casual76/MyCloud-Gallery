package dev.pampa.mycloudgallery.core.memories.testing

import java.util.Random
import kotlin.math.sqrt

// Vettori finti per le prove della scelta delle foto (Fase 9, P7, §5.2): "concetti" seminati (due scatti dello stesso concetto sono
// doppioni) e coppie con un coseno voluto (per provare la soglia di 0,92 da una parte e dall'altra). Nessun dato vero: tutto da un seme.
// In src/main come gli altri fake: mai nella release (tools/check.ps1).

object SyntheticVectors {
  /** La dimensione dei vettori finti (il modello vero ne ha di piu': qui conta solo la geometria). */
  const val DIM: Int = 32

  /** Un vettore di lunghezza 1 dal seme [seed] (concetti diversi = vettori quasi ortogonali). */
  fun concept(seed: Long, dim: Int = DIM): FloatArray {
    val random = Random(seed * 7919 + 13)
    return unit(FloatArray(dim) { random.nextGaussian().toFloat() })
  }

  /**
   * Un vettore di lunghezza 1 il cui coseno con [base] (di lunghezza 1) e' [cosine], con una componente ortogonale decisa dal seme [seed]: due
   * foto "simili quanto si vuole". [cosine] in -1..1.
   */
  fun withCosine(base: FloatArray, cosine: Double, seed: Long): FloatArray {
    require(cosine in -1.0..1.0) { "Coseno fuori intervallo." }
    val random = Random(seed * 104_729 + 7)
    val noise = DoubleArray(base.size) { random.nextGaussian() }
    var along = 0.0
    for (i in base.indices) along += noise[i] * base[i]
    val orthogonal = DoubleArray(base.size) { noise[it] - along * base[it] }
    var length = 0.0
    for (x in orthogonal) length += x * x
    length = sqrt(length)
    val sine = sqrt(1.0 - cosine * cosine)
    return FloatArray(base.size) { (cosine * base[it] + sine * orthogonal[it] / length).toFloat() }
  }

  /** [raw] portato a lunghezza 1 (un vettore nullo resta nullo). */
  fun unit(raw: FloatArray): FloatArray {
    var sum = 0.0
    for (x in raw) sum += x.toDouble() * x
    val length = sqrt(sum)
    return if (length == 0.0) raw.copyOf() else FloatArray(raw.size) { (raw[it] / length).toFloat() }
  }
}
