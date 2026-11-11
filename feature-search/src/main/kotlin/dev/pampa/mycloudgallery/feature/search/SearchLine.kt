package dev.pampa.mycloudgallery.feature.search

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.designsystem.chrome.GallerySearchLineSpec
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason

// LA RIGA SOPRA LA BARRA (H4 della fase H, 11/11; I2 della fase I). La pagina di ricerca aveva gli avvisi a schede, lo stato "nessun risultato" a tutta pagina e
// quello d'errore con due bottoni; dentro la Libreria c'e' **una riga sola, piccola, sopra la barra**: la cosa piu' importante da dire adesso, e se ha
// un'azione la riga si tocca. Niente da dire: niente riga.
// I2 («parla troppo»): la riga e' **un segno e al piu' due parole**; quando qualcosa avanza (la ricerca che impara le foto, il modello che arriva, i volti)
// una barra che si allunga, in positivo. Le aree che nessun dispositivo analizza non si dicono piu' qui (solo in Impostazioni > Ricerca e modelli). TalkBack
// dice sempre la frase intera.

/** Che cosa dice la riga sopra la barra. [action]: che cosa fa toccarla (null = solo da leggere). */
internal sealed interface SearchLine {
  val action: NoticeAction?

  /** «Niente per "…"» (E-Q3): [text] e' cio' che si e' cercato; null per "Trova simili". */
  data class NoResults(val text: String?) : SearchLine {
    override val action: NoticeAction? get() = null
  }

  /** «Simili a questa foto» / «Simili a 3 foto»: da dove vengono i risultati di "Trova simili" (il campo e' vuoto). [media]: la prima foto della scelta. */
  data class Similar(val count: Int, val media: MediaId? = null) : SearchLine {
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
        state.similar != null -> SearchLine.Similar(state.similar, state.similarTo.firstOrNull())
        else -> null
      }
    }
  }

  /** La frazione della barra della riga, o null se la riga non ne ha una. */
  fun progressOf(item: NoticeItem): Float? = when (item) {
    is NoticeItem.CoveragePartial -> if (item.total > 0) item.covered.toFloat() / item.total else null
    is NoticeItem.CoverageUpdating -> item.percent / PERCENT
    is NoticeItem.PeopleProgress -> item.percent / PERCENT
    is NoticeItem.ModelMissing -> item.fetching?.let { (done, total) -> if (total > 0) done.toFloat() / total else 0f }
    else -> null
  }

  /** La percentuale intera di una frazione (arrotondata in giu': 99,6% non e' ancora 100%). */
  fun percentOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * PERCENT).toInt()

  private const val PERCENT = 100f
}

/**
 * La riga per TalkBack e per la barra: il segno (o la foto di "Trova simili"), l'etichetta corta, la barra, la frase intera. [library] e [thumbnails]
 * servono solo alla foto di "Trova simili" (si disegna solo se e' ancora nella Libreria visibile).
 */
@Composable
internal fun searchLineSpec(line: SearchLine, library: TimelineSnapshot?, thumbnails: SearchThumbnails?): GallerySearchLineSpec = when (line) {
  // I2: «Niente per…» e' un segno e un numero, zero (la parola cercata e' gia' nel campo, sotto); in alto le recenti o gli esempi con la foto.
  is SearchLine.NoResults -> GallerySearchLineSpec(
    description = if (line.text != null) stringResource(R.string.search_line_no_results, line.text) else stringResource(R.string.search_line_no_similar),
    text = GalleryNumbers.format(0),
    icon = Icons.Rounded.SearchOff,
  )
  is SearchLine.Similar -> {
    val description = if (line.count == 1) stringResource(R.string.search_chip_similar_label) else pluralStringResource(R.plurals.search_chip_similar_many_label, line.count, line.count)
    val text = if (line.count == 1) stringResource(R.string.search_line_similar_short) else stringResource(R.string.search_line_similar_many_short, line.count)
    val photo = line.media?.takeIf { previewRequest(it, library, thumbnails) != null }
    val art: (@Composable () -> Unit)? = if (photo != null) {
      { SuggestionDisc(SuggestionItem(dev.pampa.mycloudgallery.core.search.contract.Suggestion.Term(text), SuggestionArt.Photo(photo)), library, thumbnails) }
    } else {
      null
    }
    GallerySearchLineSpec(description = description, text = text, icon = Icons.Rounded.ImageSearch, art = art)
  }
  is SearchLine.Unavailable -> if (line.reason == SearchUnavailableReason.EMPTY_LIBRARY) {
    GallerySearchLineSpec(
      description = stringResource(R.string.search_line_empty_library),
      text = stringResource(R.string.search_unavailable_empty_action),
      icon = Icons.Rounded.PhotoLibrary,
    )
  } else {
    GallerySearchLineSpec(
      description = stringResource(R.string.search_line_error),
      text = stringResource(R.string.search_unavailable_error_action),
      icon = Icons.Rounded.ErrorOutline,
    )
  }
  is SearchLine.Notice -> noticeLineSpec(line.item)
}

@Composable
private fun noticeLineSpec(item: NoticeItem): GallerySearchLineSpec {
  val progress = SearchLines.progressOf(item)
  val percent = progress?.let { stringResource(R.string.search_line_percent, SearchLines.percentOf(it)) }
  return when (item) {
    NoticeItem.Preparing -> GallerySearchLineSpec(description = stringResource(R.string.search_preparing), icon = Icons.Rounded.AutoAwesome, busy = true)
    is NoticeItem.ModelMissing -> item.fetching?.let { (done, total) ->
      GallerySearchLineSpec(
        description = stringResource(R.string.search_model_fetching, sizeText(done), sizeText(total)),
        text = percent,
        icon = Icons.Rounded.Download,
        progress = progress,
      )
    } ?: GallerySearchLineSpec(
      description = stringResource(R.string.search_line_model_missing),
      text = stringResource(R.string.search_line_model_short),
      icon = Icons.Rounded.Download,
    )
    is NoticeItem.Typo -> GallerySearchLineSpec(
      description = stringResource(R.string.search_typo, item.searched, item.typed),
      text = stringResource(R.string.search_line_quoted, item.searched),
      icon = Icons.Rounded.Spellcheck,
    )
    NoticeItem.TextReadingOff -> GallerySearchLineSpec(
      description = stringResource(R.string.search_line_text_reading_off),
      text = stringResource(R.string.search_line_text_reading_short),
      icon = Icons.Rounded.TextFields,
    )
    is NoticeItem.CoveragePartial -> GallerySearchLineSpec(
      description = stringResource(R.string.search_line_coverage_description, GalleryNumbers.format(item.covered), GalleryNumbers.format(item.total)),
      text = percent,
      icon = Icons.Rounded.AutoAwesome,
      progress = progress,
    )
    is NoticeItem.CoverageUpdating -> GallerySearchLineSpec(
      description = stringResource(R.string.search_coverage_updating, item.percent),
      text = percent,
      icon = Icons.Rounded.AutoAwesome,
      progress = progress,
    )
    is NoticeItem.PeopleProgress -> GallerySearchLineSpec(
      description = stringResource(R.string.search_people_progress, item.percent),
      text = percent,
      icon = Icons.Rounded.Face,
      progress = progress,
    )
    is NoticeItem.SimilarWithoutVectors -> GallerySearchLineSpec(
      description = pluralStringResource(R.plurals.search_similar_without_vectors, item.count, item.count),
      text = pluralStringResource(R.plurals.search_line_waiting_short, item.count, item.count),
      icon = Icons.Rounded.HourglassTop,
    )
  }
}

/** Che cosa dice TalkBack del tocco sulla riga ("Apri Ricerca e modelli", "Riprova"...), o null se la riga non si tocca. */
@Composable
internal fun searchLineActionLabel(action: NoticeAction?): String? = when (action) {
  NoticeAction.DOWNLOAD_MODEL, NoticeAction.WHY_COVERAGE -> stringResource(R.string.search_line_open_settings)
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
