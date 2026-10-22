package dev.pampa.mycloudgallery.core.ai.cutout

import kotlin.math.max
import kotlin.math.min

/**
 * Il FILTRO GUIDATO (K. He, J. Sun, X. Tang, 2010) in virgola mobile: porta i bordi di una maschera grossolana sui bordi dell'immagine
 * (docs/fase6-progetto.md D44: "maschera riportata alla risoluzione dell'immagine con un filtro guidato"). Puro, sulla JVM.
 *
 * `q = a * I + b`, con `a` e `b` per pixel calcolati sulla finestra [radius]; qui ci si ferma ai coefficienti ([coefficients]): chi
 * applica il filtro sceglie la risoluzione (la versione "veloce" calcola `a` e `b` su una griglia piccola e li applica alla guida
 * a piena risoluzione, [MaskRefiner]).
 */
internal object GuidedFilter {

  /**
   * I coefficienti `a` e `b` (gia' mediati sulla finestra) della guida [guide] (0..1) e dell'ingresso [input] (0..1), entrambi
   * [width]x[height]. [radius] e' il raggio della finestra in pixel, [eps] la regolarizzazione (varianza della guida sotto la quale il
   * filtro lascia l'ingresso com'e').
   */
  fun coefficients(guide: FloatArray, input: FloatArray, width: Int, height: Int, radius: Int, eps: Float): Pair<FloatArray, FloatArray> {
    val n = width * height
    require(guide.size == n && input.size == n) { "Taglie diverse." }
    val tmp = FloatArray(n)
    val meanI = box(guide, width, height, radius, tmp)
    val meanP = box(input, width, height, radius, tmp)
    val prodII = FloatArray(n) { guide[it] * guide[it] }
    val meanII = box(prodII, width, height, radius, tmp)
    for (i in 0 until n) prodII[i] = guide[i] * input[i]
    val meanIP = box(prodII, width, height, radius, tmp)
    val a = FloatArray(n)
    val b = FloatArray(n)
    for (i in 0 until n) {
      val varI = meanII[i] - meanI[i] * meanI[i]
      val covIP = meanIP[i] - meanI[i] * meanP[i]
      val ai = covIP / (varI + eps)
      a[i] = ai
      b[i] = meanP[i] - ai * meanI[i]
    }
    val meanA = box(a, width, height, radius, tmp)
    val meanB = box(b, width, height, radius, tmp)
    return meanA to meanB
  }

  /** Il filtro applicato alla stessa risoluzione dei coefficienti. */
  fun filter(guide: FloatArray, input: FloatArray, width: Int, height: Int, radius: Int, eps: Float): FloatArray {
    val (a, b) = coefficients(guide, input, width, height, radius, eps)
    return FloatArray(guide.size) { a[it] * guide[it] + b[it] }
  }

  /** La media su una finestra quadrata di lato `2 * radius + 1`, ritagliata ai bordi (media dei pixel che ci sono). Nuovo array. */
  fun box(src: FloatArray, width: Int, height: Int, radius: Int, tmp: FloatArray = FloatArray(width * height)): FloatArray {
    val out = FloatArray(width * height)
    // Orizzontale: somme scorrevoli per riga, in double per non accumulare l'errore di milioni di somme.
    for (y in 0 until height) {
      val row = y * width
      var sum = 0.0
      var lo = 0
      var hi = -1
      for (x in 0 until width) {
        val newHi = min(width - 1, x + radius)
        val newLo = max(0, x - radius)
        while (hi < newHi) {
          hi++
          sum += src[row + hi]
        }
        while (lo < newLo) {
          sum -= src[row + lo]
          lo++
        }
        tmp[row + x] = (sum / (hi - lo + 1)).toFloat()
      }
    }
    // Verticale: lo stesso sulle colonne del risultato orizzontale (media di medie con lo stesso numero di colonne = media esatta).
    val colSum = DoubleArray(width)
    var lo = 0
    var hi = -1
    for (y in 0 until height) {
      val newHi = min(height - 1, y + radius)
      val newLo = max(0, y - radius)
      while (hi < newHi) {
        hi++
        val row = hi * width
        for (x in 0 until width) colSum[x] += tmp[row + x]
      }
      while (lo < newLo) {
        val row = lo * width
        for (x in 0 until width) colSum[x] -= tmp[row + x]
        lo++
      }
      val inv = 1.0 / (hi - lo + 1)
      val row = y * width
      for (x in 0 until width) out[row + x] = (colSum[x] * inv).toFloat()
    }
    return out
  }
}
