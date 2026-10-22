package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.RankParams
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.ResultRanking
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import java.time.LocalDate

/**
 * L'ORDINE DEI RISULTATI (D48, §2.4) con la formula condivisa di `ResultRanking` (la stessa dell'esecutore di riferimento): "Recenti"
 * per periodo e dentro il periodo per pertinenza, "Pertinenti" per pertinenza piu' un piccolo premio di recenza. I parametri restano
 * quelli di [RankParams] (costanti da tarare con il set di Q8, P9). Per molte celle (solo filtri su 100 mila) le chiavi si calcolano
 * una volta per cella invece che a ogni confronto: stesso ordine.
 */
internal object Ranking {
  private const val PRECOMPUTE_FROM = 2_048

  /** Una foto senza data valida non vale "oggi": e' la piu' vecchia e senza recenza (CE-12). */
  fun ranked(snapshot: TimelineSnapshot, position: Int, relevance: Float): ResultRanking.Ranked =
    ResultRanking.Ranked(position, relevance, ChipMatching.dayOf(snapshot.localDayAt(position)), snapshot.sortTimeAt(position))

  /** Le posizioni di [items] nell'ordine da mostrare. */
  fun order(items: List<ResultRanking.Ranked>, order: ResultOrder, today: LocalDate, params: RankParams): IntArray {
    if (items.size < PRECOMPUTE_FROM) return items.sortedWith(ResultRanking.comparator(order, today, params)).map { it.index }.toIntArray()
    val n = items.size
    val primary = FloatArray(n)
    val period = LongArray(n)
    for (i in 0 until n) {
      val it = items[i]
      when (order) {
        ResultOrder.RELEVANT ->
          primary[i] = it.relevance + params.recencyWeight * ResultRanking.recency(it.day, today, params)
        ResultOrder.RECENT -> {
          period[i] = ResultRanking.period(it.day, today)
          primary[i] = it.relevance
        }
      }
    }
    val cmp = when (order) {
      ResultOrder.RELEVANT -> Comparator<Int> { a, b ->
        val c = primary[b].compareTo(primary[a])
        if (c != 0) c else items[b].sortTime.compareTo(items[a].sortTime)
      }
      ResultOrder.RECENT -> Comparator<Int> { a, b ->
        var c = period[b].compareTo(period[a])
        if (c == 0) c = primary[b].compareTo(primary[a])
        if (c != 0) c else items[b].sortTime.compareTo(items[a].sortTime)
      }
    }
    return (0 until n).sortedWith(cmp).map { items[it].index }.toIntArray()
  }

  /** Al piu' [SearchThresholds.MAX_LIKELY] meno probabili, i piu' pertinenti (a parita' l'ordine dell'istantanea, come il riferimento). */
  fun capLikely(likely: List<ResultRanking.Ranked>): List<ResultRanking.Ranked> =
    likely.sortedByDescending { it.relevance }.take(SearchThresholds.MAX_LIKELY)
}
