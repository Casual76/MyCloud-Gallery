package dev.pampa.mycloudgallery.feature.library.grid

import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * I segnaposto sfocati (ThumbHash) delle celle **attorno a dove si e'**, decodificati in anticipo su un thread di lavoro.
 *
 * Ogni cella prende il suo segnaposto nella composizione (`PlaceholderCache.image`), e la prima volta lo decodifica: sul
 * thread principale. Sull'S25 (15/10, `docs/bench/misure-s25-2026-10-15.md`) il primo cambio di livello di ogni prova di
 * pinch ricomponeva le righe del livello nuovo, piu' fitto, con decine di segnaposto da decodificare in un fotogramma
 * solo (8-20 ms di ricomposizione contro i 3-5 del 13/10, quando le righe del dataset non avevano ThumbHash). Tutti i
 * livelli mostrano gli stessi elementi nello stesso ordine, quindi le celle che un pinch scoprira' sono quelle **vicine
 * nella vista** a quella al centro: scaldarne qualche centinaio basta per qualunque livello, e scorrendo segue da sola.
 *
 * Non cambia niente di cio' che si vede: e' la stessa cache, riempita prima e altrove.
 */
internal data class PlaceholderWarmup(val view: TimelineView, val center: Int) {

  /** Decodifica (se non ci sono gia') i segnaposto delle celle fra [Before] prima e [After] dopo il centro. */
  suspend fun warm(cache: PlaceholderCache) {
    if (view.size == 0) return
    val snapshot = view.snapshot
    val from = (center - Before).coerceAtLeast(0)
    val to = (center + After).coerceAtMost(view.size - 1)
    fun warmAt(viewIndex: Int) {
      if (viewIndex in from..to) snapshot.thumbHashAt(view.snapshotIndex(viewIndex))?.let { cache.image(it) }
    }
    // Dal centro verso i bordi: se il centro si sposta prima della fine, il lavoro fatto e' quello che serve di piu'.
    warmAt(center)
    for (step in 1..maxOf(center - from, to - center)) {
      if (step % CheckEvery == 0) coroutineContext.ensureActive()
      warmAt(center + step)
      warmAt(center - step)
    }
  }

  companion object {
    /**
     * Il centro della griglia sullo schermo, come posizione nella vista, arrotondato a [Quantum]: la decodifica riparte
     * solo quando ci si sposta davvero, non a ogni fotogramma di scorrimento. Null se la griglia e' vuota.
     */
    fun around(state: MediaGridState, layout: GridLayout): PlaceholderWarmup? {
      if (layout.itemCount == 0 || layout.rowCount == 0) return null
      var row = state.rowAtCenter(layout)
      while (row < layout.rowCount - 1 && layout.itemCountIn(row) == 0) row++
      val position = if (layout.itemCountIn(row) > 0) layout.firstItemIn(row) else layout.itemCount - 1
      val center = layout.viewIndexAt(position.coerceIn(0, layout.itemCount - 1))
      return PlaceholderWarmup(state.shownView, center / Quantum * Quantum)
    }

    /**
     * Quante celle prima e dopo il centro: un livello "Anni" ne mostra un paio di centinaia, e la cache di serie ne tiene
     * 512 (`ImagingBudget.placeholderEntries`): si resta sotto, per non scacciare cio' che serve adesso.
     */
    const val Before: Int = 160
    const val After: Int = 160

    /** Di quante posizioni deve spostarsi il centro perche' la decodifica riparta. */
    const val Quantum: Int = 32

    private const val CheckEvery = 16
  }
}
