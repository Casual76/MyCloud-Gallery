package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.ai.contract.AlphaMask

// LE SOGLIE DI QUALITA' DEL RITAGLIO (docs/fase6-progetto.md §4.4 P13: "IoU sopra una soglia fissata prima di misurare"; §5.2:
// `FakeSubjectCutter` e MagicTouch vero passano la stessa suite sulle stesse fixture).
//
// FISSATE il 22/10/2026 alle 02:48, PRIMA di qualunque misura dei modelli veri (v1 e v2) sulle fixture di [CutoutFixtures]: nessun numero
// qui sotto e' stato scelto guardando un risultato. Se un modello non le raggiunge si dice nel resoconto (e si migliora il modello o la
// pre/post-elaborazione, mai la soglia); un cambio di soglia si scrive in docs/decisioni.md con il motivo.

/** Le soglie con cui un ritagliatore (finto o vero) passa le fixture sintetiche. */
object CutoutQuality {

  /** L'IoU (intersezione su unione) minimo di OGNI fixture con un soggetto: sotto questo il ritaglio e' sbagliato. */
  const val MIN_IOU_EACH: Float = 0.70f

  /** L'IoU medio minimo sulle fixture con un soggetto. */
  const val MIN_IOU_MEAN: Float = 0.85f

  /** Quota minima delle fixture "senza soggetto" (cielo, muro, sfocato, rumore) che deve dare `NoClearSubject` (E-V10). */
  const val MIN_EMPTY_REJECTED: Float = 0.75f

  /** L'IoU fra la maschera (alfa >= 128) e la verita' della fixture. */
  fun iou(mask: AlphaMask, truth: BooleanArray): Float {
    require(mask.width * mask.height == truth.size) { "Maschera e verita' di taglie diverse." }
    val alpha = mask.alpha()
    var inter = 0L
    var union = 0L
    for (i in truth.indices) {
      val a = (alpha[i].toInt() and 0xff) >= 128
      val t = truth[i]
      if (a && t) inter++
      if (a || t) union++
    }
    return if (union == 0L) 1f else inter.toFloat() / union
  }
}
