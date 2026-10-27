package dev.pampa.mycloudgallery.feature.search

import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness

/** L'azione di un messaggio di Cerca (§2.6: "ogni stato vuoto o d'errore ha un'azione"). */
internal enum class NoticeAction {
  DOWNLOAD_MODEL,
  WHY_COVERAGE,
  ENABLE_INDEXER,
  LEARN_TEXT_READING,
  ALLOW_ACCESS,
  RETRY,
}

/**
 * Un avviso sopra i risultati, **in forma di dato** (§1.5 `SearchNotice` e `SearchCoverage`, §2.6): quale messaggio e con quale azione. Pura e
 * senza Compose, cosi' un test confronta ogni caso del contratto con il suo messaggio e la sua azione.
 */
internal sealed interface NoticeItem {
  val action: NoticeAction?

  /** "Sto preparando la ricerca per contenuto…" (E-Q11): il motore delle query si sta caricando. */
  data object Preparing : NoticeItem {
    override val action: NoticeAction? = null
  }

  /** "Per cercare per contenuto serve il modello di ricerca (157 MB)…" (E-Q11): [fetching] = (fatti, totale) se si sta scaricando. */
  data class ModelMissing(val sizeBytes: Long, val fetching: Pair<Long, Long>?) : NoticeItem {
    override val action: NoticeAction? get() = if (fetching == null) NoticeAction.DOWNLOAD_MODEL else null
  }

  /** "Nessun dispositivo analizza l'area comune." (E-I12). */
  data class NoIndexer(val commonArea: Boolean) : NoticeItem {
    override val action: NoticeAction = NoticeAction.ENABLE_INDEXER
  }

  /** "Ho cercato «x» invece di «y»." (E-Q5): [searched] e' la parola cercata davvero, [typed] quella scritta. */
  data class Typo(val searched: String, val typed: String) : NoticeItem {
    override val action: NoticeAction? = null
  }

  /** "Le parole dentro le foto non si cercano: la lettura del testo e' spenta." (D31): una volta, poi solo nella pagina. */
  data object TextReadingOff : NoticeItem {
    override val action: NoticeAction = NoticeAction.LEARN_TEXT_READING
  }

  /** "La ricerca per contenuto copre 12.300 media su 15.000." (E-Q6), solo sotto il 98%. */
  data class CoveragePartial(val covered: Int, val total: Int) : NoticeItem {
    override val action: NoticeAction = NoticeAction.WHY_COVERAGE
  }

  /** "Aggiornamento della ricerca: 40%." (D21, E-A10). */
  data class CoverageUpdating(val percent: Int) : NoticeItem {
    override val action: NoticeAction? = null
  }

  /** "Sto ancora riconoscendo i volti: 60%." (Fase 8, §2.7): sopra i risultati con un chip "Persona", finche' il primo calcolo non e' finito. */
  data class PeopleProgress(val percent: Int) : NoticeItem {
    override val action: NoticeAction? = null
  }
}

internal object SearchNoticeItems {

  /**
   * Gli avvisi di una schermata: prima quelli del motore ([notices]), poi la copertura. Il modello assente compare **una volta** (con
   * `OnlyFiltersAndText` o con la copertura `ModelMissing`, o con l'esito di [readiness]); [queryModel] dice a che punto e' lo scaricamento.
   */
  fun of(
    notices: List<SearchNotice>,
    coverage: SearchCoverage,
    readiness: SearchReadiness,
    queryModel: ModelState?,
  ): List<NoticeItem> {
    val out = ArrayList<NoticeItem>()
    // CE-8: con il modello delle query pronto "serve il modello" non e' piu' vero, anche se i risultati in vista sono di prima che arrivasse.
    var modelShown = queryModel is ModelState.Ready
    fun model(size: Long) {
      if (modelShown) return
      modelShown = true
      val fetching = (queryModel as? ModelState.Fetching)?.let { it.done to it.total }
      out += NoticeItem.ModelMissing(size, fetching)
    }
    val missingSize = (coverage as? SearchCoverage.ModelMissing)?.sizeBytes ?: ModelCatalog.QUERY_270M.sizeBytes
    for (notice in notices) {
      when (notice) {
        SearchNotice.PreparingContentSearch -> out += NoticeItem.Preparing
        SearchNotice.OnlyFiltersAndText -> model(missingSize)
        is SearchNotice.NoIndexerForArea -> out += NoticeItem.NoIndexer(notice.commonArea)
        is SearchNotice.TypoCorrected -> out += NoticeItem.Typo(notice.from, notice.to)
        SearchNotice.TextReadingOff -> out += NoticeItem.TextReadingOff
        is SearchNotice.PeopleRecognizing -> out += NoticeItem.PeopleProgress(notice.percent)
      }
    }
    if (coverage is SearchCoverage.ModelMissing) model(coverage.sizeBytes)
    if (readiness is SearchReadiness.FiltersAndTextOnly && readiness.modelMissing) model(missingSize)
    if (readiness == SearchReadiness.PreparingContent && NoticeItem.Preparing !in out) out += NoticeItem.Preparing
    when {
      coverage is SearchCoverage.Updating -> out += NoticeItem.CoverageUpdating(coverage.percent)
      coverage.warns -> (coverage as SearchCoverage.Partial).let { out += NoticeItem.CoveragePartial(it.covered, it.total) }
    }
    return out
  }
}
