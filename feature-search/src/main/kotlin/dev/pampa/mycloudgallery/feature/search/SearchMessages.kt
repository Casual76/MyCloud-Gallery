package dev.pampa.mycloudgallery.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// I MESSAGGI di Cerca (Fase 6, P8, §2.6, "Stati vuoti e d'errore"): ognuno con la sua azione. Gli avvisi sopra i risultati sono i `NoticeItem` (puri,
// provati uno per uno); gli stati che prendono la pagina sono questi tre: nessun risultato, nessun risultato sicuro, ricerca non disponibile.
// Solo componenti G2 (illustrazione, bottoni e messaggio di Fluid), nessuno stile nuovo (D36).

@Composable
internal fun SearchNotices(items: List<NoticeItem>, handlers: SearchHandlers, modifier: Modifier = Modifier) {
  if (items.isEmpty()) return
  Column(
    modifier = modifier.padding(horizontal = GalleryDimens.Gutter),
    verticalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
  ) {
    for (item in items) NoticeCard(item, handlers)
  }
}

@Composable
private fun NoticeCard(item: NoticeItem, handlers: SearchHandlers) {
  val message = noticeText(item)
  FluidInlineMessage(message = message, title = noticeTitle(item), tone = noticeTone(item))
  val label = noticeActionLabel(item)
  val action = item.action
  if (label != null && action != null) {
    FluidButton(
      text = label,
      onClick = { run(action, handlers) },
      style = FluidButtonStyle.Tinted,
      size = FluidButtonSize.Large,
      fillWidth = true,
    )
  }
}

/** Quanto pesa un modello, come lo scrive il progetto ("157 MB"): in mebibyte, arrotondato. */
@Composable
internal fun sizeText(bytes: Long): String = stringResource(R.string.search_size_mb, GalleryNumbers.format((bytes + HALF_MIB) / MIB))

@Composable
private fun noticeText(item: NoticeItem): String = when (item) {
  NoticeItem.Preparing -> stringResource(R.string.search_preparing)
  is NoticeItem.ModelMissing -> item.fetching?.let { (done, total) -> stringResource(R.string.search_model_fetching, sizeText(done), sizeText(total)) }
    ?: stringResource(R.string.search_model_missing, sizeText(item.sizeBytes))
  is NoticeItem.NoIndexer -> stringResource(if (item.commonArea) R.string.search_no_indexer_common else R.string.search_no_indexer_private)
  is NoticeItem.Typo -> stringResource(R.string.search_typo, item.searched, item.typed)
  NoticeItem.TextReadingOff -> stringResource(R.string.search_text_reading_off)
  is NoticeItem.CoveragePartial -> stringResource(R.string.search_coverage_partial, GalleryNumbers.format(item.covered), GalleryNumbers.format(item.total))
  is NoticeItem.CoverageUpdating -> stringResource(R.string.search_coverage_updating, item.percent)
  is NoticeItem.PeopleProgress -> stringResource(R.string.search_people_progress, item.percent)
  is NoticeItem.SimilarWithoutVectors -> pluralStringResource(R.plurals.search_similar_without_vectors, item.count, item.count)
}

/** Il titolo corto sopra la frase: dice di che cosa si parla, senza allarmare. */
@Composable
private fun noticeTitle(item: NoticeItem): String = stringResource(
  when (item) {
    NoticeItem.Preparing, is NoticeItem.ModelMissing -> R.string.search_notice_title_content
    is NoticeItem.NoIndexer, is NoticeItem.CoveragePartial, is NoticeItem.CoverageUpdating -> R.string.search_notice_title_coverage
    is NoticeItem.Typo -> R.string.search_notice_title_typo
    NoticeItem.TextReadingOff -> R.string.search_notice_title_text
    is NoticeItem.PeopleProgress -> R.string.search_notice_title_people
    is NoticeItem.SimilarWithoutVectors -> R.string.search_notice_title_similar
  },
)

/** Informare, non allarmare (§12.12): l'azzurro per cio' che si sta facendo, il neutro per il resto, mai il rosso. */
private fun noticeTone(item: NoticeItem): FluidTone = when (item) {
  NoticeItem.Preparing, is NoticeItem.CoverageUpdating, is NoticeItem.PeopleProgress, is NoticeItem.SimilarWithoutVectors -> FluidTone.Info
  is NoticeItem.ModelMissing, is NoticeItem.NoIndexer, is NoticeItem.CoveragePartial, NoticeItem.TextReadingOff -> FluidTone.Neutral
  is NoticeItem.Typo -> FluidTone.Info
}

@Composable
private fun noticeActionLabel(item: NoticeItem): String? = when (item.action) {
  NoticeAction.DOWNLOAD_MODEL -> stringResource(R.string.search_model_missing_action)
  NoticeAction.WHY_COVERAGE -> stringResource(R.string.search_coverage_action)
  NoticeAction.ENABLE_INDEXER -> stringResource(R.string.search_no_indexer_action)
  NoticeAction.LEARN_TEXT_READING -> stringResource(R.string.search_text_reading_off_action)
  NoticeAction.ALLOW_ACCESS -> stringResource(R.string.search_unavailable_empty_action)
  NoticeAction.RETRY -> stringResource(R.string.search_unavailable_error_action)
  null -> null
}

private fun run(action: NoticeAction, handlers: SearchHandlers) = when (action) {
  NoticeAction.DOWNLOAD_MODEL -> handlers.onDownloadModel()
  NoticeAction.WHY_COVERAGE -> handlers.onWhyCoverage()
  NoticeAction.ENABLE_INDEXER -> handlers.onEnableIndexerHere()
  NoticeAction.LEARN_TEXT_READING -> handlers.onLearnTextReading()
  NoticeAction.ALLOW_ACCESS -> handlers.onAllowAccess()
  NoticeAction.RETRY -> handlers.onRetry()
}

/** Uno stato che prende la pagina: illustrazione, frase, azione (e, se serve, un secondo bottone). */
@Composable
internal fun SearchStateMessage(
  icon: ImageVector,
  message: String,
  modifier: Modifier = Modifier,
  tone: GalleryIllustrationTone = GalleryIllustrationTone.Brand,
  content: @Composable () -> Unit = {},
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = SearchDimens.BlockGap),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(SearchDimens.BlockGap),
  ) {
    GalleryIllustration(icon = icon, tone = tone)
    Text(
      text = message,
      modifier = Modifier.widthIn(max = SearchDimens.ReadingWidth),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    content()
  }
}

/** E-Q3 e E-Q12: nessun risultato (con i filtri da togliere) o nessun risultato sicuro (con "Mostra anche…"). */
@Composable
internal fun SearchNoResultsState(query: String, state: SearchScreenState.NoResults, handlers: SearchHandlers, modifier: Modifier = Modifier) {
  val onlyLikely = state.likelyCount > 0
  SearchStateMessage(
    icon = Icons.Rounded.SearchOff,
    message = if (onlyLikely) stringResource(R.string.search_no_confident) else stringResource(R.string.search_no_results, query),
    modifier = modifier,
    tone = GalleryIllustrationTone.Caution,
  ) {
    if (onlyLikely) {
      FluidButton(
        text = stringResource(R.string.search_show_likely, GalleryNumbers.format(state.likelyCount)),
        onClick = handlers::onShowLikely,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
      )
    }
    if (state.alternatives.isNotEmpty()) {
      Text(
        text = stringResource(R.string.search_no_results_filters),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      val resources = LocalContext.current.resources
      LazyRow(horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap)) {
        items(state.alternatives, key = { "${it::class.simpleName}-${it.label}" }) { chip ->
          FluidChip(label = chip.label, selected = true, onClick = { handlers.onRemoveChip(chip) }, modifier = Modifier.chipSemantics(resources, chip))
        }
      }
    }
  }
}

/** La ricerca non si puo' fare: nessuna foto (o nessun permesso), oppure un errore interno. */
@Composable
internal fun SearchUnavailableState(reason: SearchUnavailableReason, handlers: SearchHandlers, modifier: Modifier = Modifier) {
  when (reason) {
    SearchUnavailableReason.EMPTY_LIBRARY -> SearchStateMessage(Icons.Rounded.PhotoLibrary, stringResource(R.string.search_unavailable_empty), modifier) {
      FluidButton(text = stringResource(R.string.search_unavailable_empty_action), onClick = handlers::onAllowAccess, size = FluidButtonSize.Large)
    }
    SearchUnavailableReason.INTERNAL_ERROR -> SearchStateMessage(
      Icons.Rounded.ReportProblem,
      stringResource(R.string.search_unavailable_error),
      modifier,
      GalleryIllustrationTone.Caution,
    ) {
      FluidButton(text = stringResource(R.string.search_unavailable_error_action), onClick = handlers::onRetry, size = FluidButtonSize.Large)
      FluidButton(
        text = stringResource(R.string.search_unavailable_error_diagnostics),
        onClick = handlers::onDiagnostics,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
      )
    }
  }
}

private const val MIB = 1_048_576L
private const val HALF_MIB = 524_288L
