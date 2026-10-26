package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import java.time.MonthDay

// LA RICERCA PER CONTENUTO DEGLI ALBUM SMART (D15, §2.2 regole C e S4, §2.9). Descrizione e foto d'esempio passano dai contratti della Fase 6:
// `SearchEngine.search` con i chip del periodo come vincoli e la descrizione come testo; `SearchEngine.similar` per ogni foto d'esempio. Le
// classi sono quelle del motore: "sicuri" (regola C) e "meno probabili" (S4, F6-D27). **Senza il motore pronto le regole C e S4 tacciono** e
// l'editor lo dice (`ProposalNote.NO_SEARCH`, `SmartPreview.searchUsed`).
//
// Le richieste della Fase 7 a P6 della Fase 6 (§2.9: `SearchRequest.within`, `similarToAll`) non ci sono: si filtra dopo (stesso risultato, piu'
// lento) e le foto d'esempio si combinano per unione dei "Trova simili" di ognuna. Una richiesta degli album non finisce nella cronologia: la
// cronologia la scrive la schermata Cerca, non `search()`.

interface SmartContentSearch {
  /** Il motore puo' rispondere adesso per contenuto (modello caricato). Se no, [match] da' [ContentMatches.NONE]. */
  val ready: Boolean

  /** I media che [text] e le foto d'[examples] indicano, nel [period] (null = sempre). Mai un nascosto (il motore non li ha). */
  suspend fun match(text: String?, examples: List<MediaId>, period: SmartPeriod?): ContentMatches
}

/** Nessun motore: C e S4 tacciono. */
object NoSmartContentSearch : SmartContentSearch {
  override val ready: Boolean = false

  override suspend fun match(text: String?, examples: List<MediaId>, period: SmartPeriod?): ContentMatches = ContentMatches.NONE
}

/** Il motore di ricerca della Fase 6 (vero o di riferimento) come ricerca degli album. */
class EngineContentSearch(private val engine: SearchEngine) : SmartContentSearch {
  override val ready: Boolean get() = engine.readiness.value is SearchReadiness.Ready

  override suspend fun match(text: String?, examples: List<MediaId>, period: SmartPeriod?): ContentMatches {
    val query = text?.trim().orEmpty()
    if (!ready || (query.isEmpty() && examples.isEmpty())) return ContentMatches.NONE
    val sure = LinkedHashSet<MediaId>()
    val likely = LinkedHashSet<MediaId>()
    if (query.isNotEmpty()) {
      // I chip sono **espliciti** (anche vuoti): la descrizione non deve essere riletta come una data o un luogo.
      for (chips in chipsOf(period)) {
        val results = engine.search(SearchRequest(query, editedChips = chips, showLikely = true))
        sure += idsOf(results.confident)
        likely += idsOf(results.likely)
      }
    }
    for (example in examples.distinct()) {
      val results = engine.similar(example)
      sure += idsOf(results.confident)
      likely += idsOf(results.likely)
    }
    return ContentMatches(sure, likely - sure, used = true)
  }

  private fun idsOf(view: OrderedTimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

  /**
   * Le ricerche da fare per il periodo (la soglia del motore e' relativa all'insieme che cerca: un chip per periodo la tiene sui giorni
   * dell'album). Una per serie di giorni; "ogni anno" e le feste mobili sono un chip solo; senza periodo nessun chip.
   */
  private fun chipsOf(period: SmartPeriod?): List<List<SearchChip>> = when (period) {
    null -> listOf(emptyList())
    is SmartPeriod.Ranges -> period.ranges.map { listOf(rangeChip(it)) }
    is SmartPeriod.EveryYear -> listOf(listOf(everyYearChip(period.from, period.to)))
    is SmartPeriod.Days ->
      if (period.days.size <= SearchChip.DateSet.MAX_DAYS) listOf(listOf(SearchChip.DateSet(period.days, "Periodo"))) else listOf(emptyList())
  }

  private fun rangeChip(range: LocalDayRange): SearchChip = SearchChip.DateRange(range.from, range.to, everyYear = false, label = "Periodo")

  /** "Tutti gli anni" conta solo mese e giorno: l'anno e' uno bisestile qualunque (come i chip dell'analizzatore). */
  private fun everyYearChip(from: MonthDay, to: MonthDay): SearchChip =
    SearchChip.DateRange(from.atYear(ANY_YEAR), to.atYear(ANY_YEAR), everyYear = true, label = "Periodo")

  private companion object {
    const val ANY_YEAR = 2000
  }
}
