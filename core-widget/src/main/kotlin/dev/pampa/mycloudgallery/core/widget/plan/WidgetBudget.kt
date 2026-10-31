package dev.pampa.mycloudgallery.core.widget.plan

import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import kotlin.math.sqrt

/**
 * I tetti delle immagini di un widget (D7), come regole pure: P3 le applica quando decodifica le miniature locali.
 *
 * D7 dice "al piu' 384 px sul lato lungo (512 nella misura grande)" **e** "7 bitmap e 2 MiB in tutto". I due numeri non stanno insieme: sette
 * miniature quadrate da 384 px sono 4,1 MiB (da 512 px, 7,3 MiB). Il tetto che vale e' quello **in byte** (e' quello che Android controlla
 * sui `RemoteViews`): il lato lungo di [WidgetSize.thumbPx] e' un massimo, e [longSidePx] lo abbassa quando le celle sono tante. Nel caso
 * peggiore (miniature quadrate) sette celle scendono a 273 px; quattro a 362 px; una resta a 384.
 */
object WidgetBudget {
  /** ARGB_8888: quattro byte per pixel (la bitmap decodificata, non il file). */
  const val BYTES_PER_PIXEL: Int = 4

  /** I byte di una bitmap [widthPx] x [heightPx]. Dimensioni negative o nulle non hanno senso: [IllegalArgumentException]. */
  fun bytesOf(widthPx: Int, heightPx: Int): Long {
    require(widthPx > 0 && heightPx > 0) { "Dimensioni di bitmap non valide." }
    return widthPx.toLong() * heightPx * BYTES_PER_PIXEL
  }

  /**
   * Il lato lungo massimo da usare quando il widget [size] mostra [cellCount] immagini: [WidgetSize.thumbPx], abbassato quanto basta perche'
   * [cellCount] miniature **quadrate** (il caso peggiore) stiano in [WidgetRules.MAX_BITMAP_BYTES]. Mai sotto 1; [cellCount] fuori da
   * 1..[WidgetRules.MAX_IMAGES] non ha senso ([IllegalArgumentException]).
   */
  fun longSidePx(size: WidgetSize, cellCount: Int): Int {
    require(cellCount in 1..WidgetRules.MAX_IMAGES) { "Numero di immagini fuori dai limiti." }
    val fitting = sqrt(WidgetRules.MAX_BITMAP_BYTES.toDouble() / (BYTES_PER_PIXEL.toDouble() * cellCount)).toInt()
    return minOf(size.thumbPx, fitting).coerceAtLeast(1)
  }

  /**
   * Quante delle immagini [sizes] (larghezza, altezza, **in ordine di cella**) stanno nei tetti: al piu' [WidgetRules.MAX_IMAGES] e
   * [WidgetRules.MAX_BITMAP_BYTES] in tutto. E' un **prefisso**: le celle dopo la prima che sfora cadono (la copertina di un ricordo, la
   * cella 0, e' l'ultima a cadere). Una bitmap sola che sfora da sola da' 0.
   */
  fun fitting(sizes: List<Pair<Int, Int>>): Int {
    var total = 0L
    var count = 0
    for ((w, h) in sizes) {
      if (count == WidgetRules.MAX_IMAGES) break
      total += bytesOf(w, h)
      if (total > WidgetRules.MAX_BITMAP_BYTES) break
      count++
    }
    return count
  }

  /**
   * Il piano ridotto alle prime [count] celle (quelle che hanno passato [fitting]): P3 lo registra e lo disegna **al posto** dell'originale,
   * cosi' il tocco e lo svuotamento di D5 conoscono solo cio' che si vede davvero. [count] a zero (nemmeno una miniatura sta nel tetto) da'
   * `Message(NO_LOCAL_THUMBS)`; [count] uguale o maggiore delle celle lascia il piano com'e'. Un piano senza foto non cambia.
   */
  fun trimmed(plan: WidgetPlan, count: Int): WidgetPlan {
    require(count >= 0) { "Numero di celle negativo." }
    if (plan.mode is WidgetMode.Message || count >= plan.cells.size) return plan
    if (count == 0) return WidgetPlan(plan.slot, plan.size, WidgetMode.Message(WidgetEmpty.NO_LOCAL_THUMBS), emptyList(), plan.epoch)
    return WidgetPlan(plan.slot, plan.size, plan.mode, plan.cells.take(count), plan.epoch)
  }
}
