package dev.pampa.mycloudgallery.core.library.layout

import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.ScrubberModel
import java.time.YearMonth

/**
 * Lo scrubber (§2.4): la barra laterale mappa la frazione dell'altezza del contenuto sulle righe con le somme prefisse
 * (O(log n)), e la bolla mostra il mese della riga. Il mese e' quello della **prima cella della riga** (non della
 * sezione): al livello Anni la bolla dice comunque "marzo 2024". [monthStarts] da' le righe dove il mese cambia: l'aptica
 * a ogni cambio di mese e i passi del cursore regolabile di TalkBack.
 */
object TimelineScrubber : ScrubberModel {

  override fun rowAt(l: GridLayout, fraction: Float): Int {
    if (l.rowCount == 0) return 0
    // Arrotondato, non troncato: fractionOf(row) riporta esattamente a row nonostante la precisione del Float.
    val y = Math.round(fraction.coerceIn(0f, 1f).toDouble() * l.totalHeight).toInt().coerceAtMost(maxOf(0, l.totalHeight - 1))
    return l.rowAtOffset(y)
  }

  override fun fractionOf(l: GridLayout, row: Int): Float {
    if (l.totalHeight <= 0 || l.rowCount == 0) return 0f
    return (l.rowTop(row.coerceIn(0, l.rowCount - 1)).toDouble() / l.totalHeight).toFloat().coerceIn(0f, 1f)
  }

  override fun label(l: GridLayout, row: Int): YearMonth {
    if (l.rowCount == 0) return FALLBACK
    val r = row.coerceIn(0, l.rowCount - 1)
    val month = monthOf(l, r)
    return runCatching { YearMonth.of(month / 100, month % 100) }.getOrDefault(FALLBACK)
  }

  /** `aaaamm` della riga [row]. */
  fun monthOf(l: GridLayout, row: Int): Int =
    if (l is TimelineGridLayout) l.monthOfRow(row) else l.sections[l.sectionOfRow(row)].firstDay / 100

  /** Le righe dove comincia un mese nuovo (la prima riga compresa), in ordine. */
  fun monthStarts(l: GridLayout): IntArray {
    val out = IntArray(l.rowCount)
    var n = 0
    var last = Int.MIN_VALUE
    for (r in 0 until l.rowCount) {
      val m = monthOf(l, r)
      if (m != last) {
        out[n++] = r
        last = m
      }
    }
    return out.copyOf(n)
  }

  private val FALLBACK: YearMonth = YearMonth.of(1970, 1)
}
