package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import kotlin.time.Duration

// IL RICALCOLO DEGLI SMART (D16, §2.2 "Ricalcolo"): quando il catalogo cambia (una scansione, un'unione, un nascosto, un backup) il lavoro di :app
// chiama [onCatalogChanged] con i media toccati; qui si trovano i **giorni** toccati, gli smart (non congelati) il cui periodo li copre e per ognuno si
// ricalcola l'incrementale ([RulesSmartEngine.reevaluate]). Un cambio di regole o di scelte (esclusioni, accettazioni, congelamento) e' [onRulesChanged]:
// la valutazione intera. Solo numeri nel resoconto (D37).

/** Cosa ha fatto un giro: quanti smart ha guardato, quanti ha ricalcolato, quanti non ha fatto in tempo. */
data class SmartRecomputeReport(val albums: Int, val recomputed: Int, val skipped: Int) {
  override fun toString(): String = "SmartRecomputeReport(albums=$albums, recomputed=$recomputed, skipped=$skipped)"
}

class SmartRecompute(
  private val rows: SmartRows,
  private val candidates: SmartCandidates,
  private val engine: RulesSmartEngine,
  private val clocks: AlbumClocks,
) {

  /**
   * Il catalogo e' cambiato per [media] (e per i giorni [extraDays], per un media il cui giorno e' cambiato: i giorni di prima il catalogo non li
   * sa). Gli smart che coprono quei giorni (piu' o meno uno) si ricalcolano finche' c'e' tempo in [budget]; gli altri restano per il giro dopo.
   */
  suspend fun onCatalogChanged(media: Collection<MediaId>, extraDays: Collection<LocalDate> = emptyList(), budget: Duration): SmartRecomputeReport {
    val days = LinkedHashSet<LocalDate>(extraDays)
    days += candidates.daysOf(media).values
    val ids = rows.smartAlbums()
    if (days.isEmpty()) return SmartRecomputeReport(ids.size, 0, 0)
    val around = days.flatMapTo(HashSet()) { listOf(it.minusDays(1), it, it.plusDays(1)) }
    val start = clocks.monotonicMs()
    var recomputed = 0
    var skipped = 0
    for (id in ids) {
      val state = rows.state(id) ?: continue
      if (state.frozen) continue
      val period = state.rules.period
      if (period != null && around.none { it in period }) continue
      if (clocks.monotonicMs() - start >= budget.inWholeMilliseconds && recomputed > 0) {
        skipped++
        continue
      }
      when (engine.reevaluate(id, days)) {
        is SmartOutcome.Done -> recomputed++
        is SmartOutcome.Partial -> skipped++
        SmartOutcome.Frozen, SmartOutcome.NotFound -> Unit
      }
    }
    return SmartRecomputeReport(ids.size, recomputed, skipped)
  }

  /** Le regole o le scelte di [id] sono cambiate: la valutazione intera, a lotti. */
  suspend fun onRulesChanged(id: AlbumId, budget: Duration): SmartOutcome = engine.evaluate(id, budget)
}
