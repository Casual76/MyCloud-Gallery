package dev.pampa.mycloudgallery.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason

// LA RIGA SOPRA LA BARRA (H4 della fase H, 11/11). La pagina di ricerca aveva gli avvisi a schede, lo stato "nessun risultato" a tutta pagina e quello
// d'errore con due bottoni; dentro la Libreria c'e' **una riga sola, piccola, sopra la barra**: la cosa piu' importante da dire adesso, e se ha un'azione la
// riga si tocca. Niente da dire: niente riga.

/** Che cosa dice la riga sopra la barra. [action]: che cosa fa toccarla (null = solo da leggere). */
internal sealed interface SearchLine {
  val action: NoticeAction?

  /** «Niente per "…"» (E-Q3): [text] e' cio' che si e' cercato; null per "Trova simili". */
  data class NoResults(val text: String?) : SearchLine {
    override val action: NoticeAction? get() = null
  }

  /** «Simili a questa foto» / «Simili a 3 foto»: da dove vengono i risultati di "Trova simili" (il campo e' vuoto). */
  data class Similar(val count: Int) : SearchLine {
    override val action: NoticeAction? get() = null
  }

  /** Un avviso del motore o della copertura (§2.6), il primo per importanza. */
  data class Notice(val item: NoticeItem) : SearchLine {
    override val action: NoticeAction? get() = item.action
  }

  /** La ricerca non puo' mostrare niente: la Libreria e' vuota (Consenti accesso) o la ricerca non ha funzionato (Riprova). */
  data class Unavailable(val reason: SearchUnavailableReason) : SearchLine {
    override val action: NoticeAction
      get() = if (reason == SearchUnavailableReason.EMPTY_LIBRARY) NoticeAction.ALLOW_ACCESS else NoticeAction.RETRY
  }
}

/** La scelta della riga: pura, provata su JVM. */
internal object SearchLines {

  /**
   * La riga per lo stato [state]: niente a riposo (dietro c'e' la Libreria); «Niente per…» senza risultati; con i risultati il primo avviso del motore
   * (la parola corretta, il modello che manca, la copertura sotto il 98%...) o, per "Trova simili", da che cosa vengono.
   */
  fun of(state: SearchScreenState, readiness: SearchReadiness, queryModel: ModelState?): SearchLine? = when (state) {
    SearchScreenState.Idle -> null
    is SearchScreenState.Unavailable -> SearchLine.Unavailable(state.reason)
    is SearchScreenState.NoResults -> SearchLine.NoResults(state.text)
    is SearchScreenState.Results -> {
      val notice = SearchNoticeItems.of(state.notices, state.coverage, readiness, queryModel).firstOrNull()
      when {
        notice != null -> SearchLine.Notice(notice)
        state.similar != null -> SearchLine.Similar(state.similar)
        else -> null
      }
    }
  }
}

/** Il testo della riga: una frase corta, che sta in una riga a 360 dp (TalkBack legge la stessa). */
@Composable
internal fun searchLineText(line: SearchLine): String = when (line) {
  is SearchLine.NoResults -> if (line.text != null) stringResource(R.string.search_line_no_results, line.text) else stringResource(R.string.search_line_no_similar)
  is SearchLine.Similar -> if (line.count == 1) stringResource(R.string.search_chip_similar_label) else pluralStringResource(R.plurals.search_chip_similar_many_label, line.count, line.count)
  is SearchLine.Unavailable -> stringResource(
    if (line.reason == SearchUnavailableReason.EMPTY_LIBRARY) R.string.search_line_empty_library else R.string.search_line_error,
  )
  is SearchLine.Notice -> noticeLineText(line.item)
}

@Composable
private fun noticeLineText(item: NoticeItem): String = when (item) {
  NoticeItem.Preparing -> stringResource(R.string.search_preparing)
  is NoticeItem.ModelMissing -> item.fetching?.let { (done, total) -> stringResource(R.string.search_model_fetching, sizeText(done), sizeText(total)) }
    ?: stringResource(R.string.search_line_model_missing)
  is NoticeItem.NoIndexer -> stringResource(if (item.commonArea) R.string.search_no_indexer_common else R.string.search_no_indexer_private)
  is NoticeItem.Typo -> stringResource(R.string.search_typo, item.searched, item.typed)
  NoticeItem.TextReadingOff -> stringResource(R.string.search_line_text_reading_off)
  is NoticeItem.CoveragePartial -> stringResource(R.string.search_coverage_partial, GalleryNumbers.format(item.covered), GalleryNumbers.format(item.total))
  is NoticeItem.CoverageUpdating -> stringResource(R.string.search_coverage_updating, item.percent)
  is NoticeItem.PeopleProgress -> stringResource(R.string.search_people_progress, item.percent)
  is NoticeItem.SimilarWithoutVectors -> pluralStringResource(R.plurals.search_similar_without_vectors, item.count, item.count)
}

/** Che cosa dice TalkBack del tocco sulla riga ("Apri Ricerca e modelli", "Riprova"...), o null se la riga non si tocca. */
@Composable
internal fun searchLineActionLabel(action: NoticeAction?): String? = when (action) {
  NoticeAction.DOWNLOAD_MODEL, NoticeAction.WHY_COVERAGE, NoticeAction.ENABLE_INDEXER -> stringResource(R.string.search_line_open_settings)
  NoticeAction.LEARN_TEXT_READING -> stringResource(R.string.search_text_reading_off_action)
  NoticeAction.ALLOW_ACCESS -> stringResource(R.string.search_unavailable_empty_action)
  NoticeAction.RETRY -> stringResource(R.string.search_unavailable_error_action)
  null -> null
}

/** Quanto pesa un modello, come lo scrive il progetto ("157 MB"): in mebibyte, arrotondato. La usa anche la pagina Ricerca e modelli. */
@Composable
internal fun sizeText(bytes: Long): String = stringResource(R.string.search_size_mb, GalleryNumbers.format((bytes + HALF_MIB) / MIB))

private const val MIB = 1_048_576L
private const val HALF_MIB = 524_288L
