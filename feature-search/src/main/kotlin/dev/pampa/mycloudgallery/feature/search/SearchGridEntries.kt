package dev.pampa.mycloudgallery.feature.search

import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineLevel
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.ResultRanking
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Le due sezioni dei risultati (D27, D28): i sicuri e i meno probabili, nello stesso ordine scelto (D48). */
internal enum class ResultSection { CONFIDENT, LIKELY }

/** Una voce della griglia dei risultati, in ordine di apparizione. [key] e' stabile (nessuna cella cambia posto fra due composizioni uguali). */
internal sealed interface GridEntry {
  val key: String

  /** "Risultati (N)" o "Meno probabili (N)". */
  data class Title(val section: ResultSection, val count: Int) : GridEntry {
    override val key: String get() = "title-$section"
  }

  /** L'intestazione di un periodo in "Recenti" (§2.6): un giorno, un mese o un anno; [start] e' il suo primo giorno, null = senza data. */
  data class Period(val section: ResultSection, val start: LocalDate?, val level: GalleryTimelineLevel) : GridEntry {
    override val key: String get() = "period-$section-${start ?: "none"}"
  }

  /** Una cella: la posizione [viewIndex] nella vista della sezione. */
  data class Cell(val section: ResultSection, val viewIndex: Int, val media: MediaId) : GridEntry {
    override val key: String get() = "cell-$section-$media"
  }

  /** "Mostra anche risultati meno probabili (N)" (E-Q12). */
  data class ShowLikely(val count: Int) : GridEntry {
    override val key: String get() = "show-likely"
  }
}

/** Dalla vista dei risultati alle voci della griglia: pura e senza Compose, quindi si prova su JVM. */
internal object SearchGridEntries {

  fun build(results: SearchScreenState.Results, today: LocalDate): List<GridEntry> {
    val out = ArrayList<GridEntry>()
    val grouped = results.order == ResultOrder.RECENT
    if (results.confident.size > 0) {
      out += GridEntry.Title(ResultSection.CONFIDENT, results.confident.size)
      addCells(out, ResultSection.CONFIDENT, results.confident, grouped, today)
    }
    if (results.showLikely) {
      if (results.likely.size > 0) {
        out += GridEntry.Title(ResultSection.LIKELY, results.likely.size)
        addCells(out, ResultSection.LIKELY, results.likely, grouped, today)
      }
    } else if (results.likelyCount > 0) {
      out += GridEntry.ShowLikely(results.likelyCount)
    }
    return out
  }

  private fun addCells(out: MutableList<GridEntry>, section: ResultSection, view: OrderedTimelineView, grouped: Boolean, today: LocalDate) {
    var current: Long? = null
    var started = false
    for (i in 0 until view.size) {
      val index = view.snapshotIndex(i)
      if (grouped) {
        val day = ChipMatching.dayOf(view.snapshot.localDayAt(index))
        val period = ResultRanking.period(day, today)
        if (!started || period != current) {
          out += GridEntry.Period(section, if (day == null) null else LocalDate.ofEpochDay(period), levelOf(day, today))
          current = period
          started = true
        }
      }
      out += GridEntry.Cell(section, i, view.snapshot.idAt(index))
    }
  }

  /** Quanto e' larga l'intestazione di [day]: lo stesso taglio di `ResultRanking.period` (giorno fino a 31 giorni, mese fino a 2 anni, poi anno). */
  fun levelOf(day: LocalDate?, today: LocalDate): GalleryTimelineLevel {
    if (day == null) return GalleryTimelineLevel.All
    val age = ChronoUnit.DAYS.between(day, today)
    return when {
      age <= DAY_WINDOW -> GalleryTimelineLevel.Day
      age <= MONTH_WINDOW -> GalleryTimelineLevel.Month
      else -> GalleryTimelineLevel.Year
    }
  }

  /**
   * La vista con cui si apre il viewer: i sicuri e, se sono mostrati, i meno probabili dopo di loro, **nell'ordine che si vede** (§2.6: "scorre
   * i risultati nell'ordine mostrato"). Lo stesso istante dell'istantanea di partenza: la vista viva (E-Q13) la rifa' il motore.
   */
  fun openingView(results: SearchScreenState.Results): OrderedTimelineView {
    if (!results.showLikely || results.likely.size == 0) return results.confident
    val ids = ArrayList<MediaId>(results.confident.size + results.likely.size)
    for (view in listOf(results.confident, results.likely)) {
      for (i in 0 until view.size) ids += view.snapshot.idAt(view.snapshotIndex(i))
    }
    return IndexOrderedTimelineView.of(results.confident.snapshot, ids)
  }

  private const val DAY_WINDOW = 31L
  private const val MONTH_WINDOW = 730L
}
