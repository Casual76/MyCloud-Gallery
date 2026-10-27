package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import kotlin.time.Duration

// IL RICALCOLO DEGLI SMART (D16, §2.2 "Ricalcolo"): quando il catalogo cambia (una scansione, un'unione, un nascosto, un backup) il lavoro di :app
// chiama [onCatalogChanged] con i media toccati; qui si trovano i **giorni** toccati, gli smart (non congelati) il cui periodo li copre e per ognuno si
// ricalcola l'incrementale ([RulesSmartEngine.reevaluate]). Un cambio di regole o di scelte (esclusioni, accettazioni, congelamento) e' [onRulesChanged]:
// la valutazione intera. Solo numeri nel resoconto (D37).

/**
 * Cosa ha fatto un giro: quanti smart ha guardato, quanti ha ricalcolato, quanti non ha fatto in tempo ([skipped]: il giro dopo li riprende, AS-5)
 * e quali ha rimandato perche' pesanti ([deferred], AS-6). [held]: gli smart con la ricerca per contenuto che il budget non ha ammesso in questo giro
 * (R19): li rifa' chi tiene il budget, non il giro dopo di questo.
 */
data class SmartRecomputeReport(
  val albums: Int,
  val recomputed: Int,
  val skipped: Int,
  val deferred: List<AlbumId> = emptyList(),
  val held: Int = 0,
) {
  override fun toString(): String = "SmartRecomputeReport(albums=$albums, recomputed=$recomputed, skipped=$skipped, deferred=${deferred.size}, held=$held)"
}

/** Lo smart cerca per contenuto (una descrizione o delle foto d'esempio): ogni valutazione costa una ricerca sull'intera Libreria (D15, R19). */
internal fun SmartRules.searchesContent(): Boolean = !text.isNullOrBlank() || examples.isNotEmpty()

class SmartRecompute(
  private val rows: SmartRows,
  private val candidates: SmartCandidates,
  private val engine: RulesSmartEngine,
  private val clocks: AlbumClocks,
) {

  /**
   * Il catalogo e' cambiato per [media] (e per i giorni [extraDays], per un media il cui giorno e' cambiato: i giorni di prima il catalogo non li
   * sa). Gli smart che coprono quei giorni (piu' o meno uno) si ricalcolano finche' c'e' tempo in [budget]; gli altri restano per il giro dopo.
   *
   * AS-6: con [deferHeavy] uno smart **senza periodo** con una descrizione o delle foto d'esempio non si rifa' qui: ogni gruppo di novita' di una scansione
   * lunga rilancerebbe la ricerca per contenuto su tutta la Libreria. Torna in [SmartRecomputeReport.deferred] e lo rifa' chi chiama a scansione ferma.
   *
   * R19: uno smart con la ricerca per contenuto (e un periodo: gli altri sono sopra) si rivaluta intero, quindi cerca su tutta la Libreria. Prima di farlo
   * chiede [contentTurn]; se risponde no, lo smart non si tocca qui (chi ha risposto no lo ha messo in fila) e finisce nel conto [SmartRecomputeReport.held].
   */
  suspend fun onCatalogChanged(
    media: Collection<MediaId>,
    extraDays: Collection<LocalDate> = emptyList(),
    budget: Duration,
    deferHeavy: Boolean = false,
    contentTurn: (AlbumId) -> Boolean = { true },
  ): SmartRecomputeReport {
    val days = LinkedHashSet<LocalDate>(extraDays)
    days += candidates.daysOf(media).values
    val ids = rows.smartAlbums()
    if (days.isEmpty()) return SmartRecomputeReport(ids.size, 0, 0)
    val around = days.flatMapTo(HashSet()) { listOf(it.minusDays(1), it, it.plusDays(1)) }
    val start = clocks.monotonicMs()
    var recomputed = 0
    var skipped = 0
    var held = 0
    val deferred = ArrayList<AlbumId>()
    for (id in ids) {
      val state = rows.state(id) ?: continue
      if (state.frozen) continue
      val period = state.rules.period
      if (period != null && around.none { it in period }) continue
      val searches = state.rules.searchesContent()
      if (deferHeavy && period == null && searches) {
        deferred += id
        continue
      }
      if (clocks.monotonicMs() - start >= budget.inWholeMilliseconds && recomputed > 0) {
        skipped++
        continue
      }
      if (searches && !contentTurn(id)) {
        held++
        continue
      }
      when (engine.reevaluate(id, days)) {
        is SmartOutcome.Done -> recomputed++
        is SmartOutcome.Partial -> skipped++
        SmartOutcome.Frozen, SmartOutcome.NotFound -> Unit
      }
    }
    return SmartRecomputeReport(ids.size, recomputed, skipped, deferred, held)
  }

  /** Le regole o le scelte di [id] sono cambiate: la valutazione intera, a lotti. */
  suspend fun onRulesChanged(id: AlbumId, budget: Duration): SmartOutcome = engine.evaluate(id, budget)
}
