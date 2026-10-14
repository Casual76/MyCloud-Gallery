package dev.pampa.mycloudgallery.core.prepare.photo

import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Le taglie delle uscite (D12, Q2): funzioni pure, provate su JVM. */
internal object OutputPlan {

  /** L'ordine in cui si producono le immagini (e si pubblicano): dalla piu' piccola, quella che serve per prima alla griglia. */
  val IMAGE_ORDER: List<SegmentClass> = listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)

  /**
   * La taglia di [cls] per una sorgente dritta di [width] x [height]:
   * - `thumbS` e `thumbM`: lato **corto** a 200/400 px, con il lato lungo al massimo come l'HQ (un panorama 20:1 non diventa un
   *   nastro da 4.000 px);
   * - `analysis` e `hq`: lato **lungo** a 1024/2048 px.
   * Mai piu' grande della sorgente: una foto da 300 px ha le stesse 300 px in ogni classe.
   */
  fun sizeFor(cls: SegmentClass, width: Int, height: Int, spec: PrepSpec): Pair<Int, Int> {
    val long = max(width, height)
    val short = min(width, height)
    val scale = when (cls) {
      SegmentClass.THUMB_S -> min(spec.thumbS.toDouble() / short, spec.hq.toDouble() / long)
      SegmentClass.THUMB_M -> min(spec.thumbM.toDouble() / short, spec.hq.toDouble() / long)
      SegmentClass.ANALYSIS -> spec.analysis.toDouble() / long
      SegmentClass.HQ -> spec.hq.toDouble() / long
      else -> error("Classe senza taglia: $cls")
    }
    if (scale >= 1.0) return width to height
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
  }

  /** Le classi d'immagine richieste, nell'ordine di produzione. */
  fun imagesOf(spec: PrepSpec): List<SegmentClass> = IMAGE_ORDER.filter { it in spec.classes }

  /** La taglia piu' grande fra quelle richieste (per area): la decodifica si fa una volta sola a quella taglia. */
  fun largest(sizes: Collection<Pair<Int, Int>>): Pair<Int, Int> = sizes.maxBy { it.first.toLong() * it.second }

  /** Qualita' di codifica per classe (D12): WebP per le miniature e l'HQ, JPEG q90 per l'analisi (l'OCR e i volti la vogliono). */
  fun qualityOf(cls: SegmentClass): Int = when (cls) {
    SegmentClass.THUMB_S -> 75
    SegmentClass.THUMB_M -> 78
    SegmentClass.ANALYSIS -> 90
    SegmentClass.HQ -> 80
    else -> error("Classe senza qualita': $cls")
  }
}
