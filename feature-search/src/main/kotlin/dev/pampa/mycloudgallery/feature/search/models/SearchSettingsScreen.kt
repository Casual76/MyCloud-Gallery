package dev.pampa.mycloudgallery.feature.search.models

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.feature.search.R
import dev.pampa.mycloudgallery.feature.search.contract.AreaKind
import dev.pampa.mycloudgallery.feature.search.contract.SearchSettingsState
import dev.pampa.mycloudgallery.feature.search.sizeText
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// LA PAGINA IMPOSTAZIONI → RICERCA E MODELLI (Fase 6, P8, docs/fase6-progetto.md §2.6; D15, D20, D31, D41; Q3, Q13): il ruolo Indicizzatore con la misura e
// la stima, lo stato del calcolo e "Indicizza ora", la lettura del testo, la copertura, le aree senza indicizzatori, i modelli, lo spazio e
// "Cancella le ricerche recenti". Righe e pulsanti di Fluid, nessuno stile nuovo (D36). Le sezioni non collegate ([hasIndexer]...) non si disegnano.

@Composable
fun SearchSettingsScreen(
  state: SearchSettingsState,
  measuring: Boolean,
  hasIndexer: Boolean,
  hasTextReading: Boolean,
  hasModels: Boolean,
  handlers: SearchSettingsHandlers,
  onBack: () -> Unit,
  probeFailed: Boolean = false,
  probeHot: Boolean = false,
  probeBusy: Boolean = false,
) {
  val resources = LocalContext.current.resources
  FluidScreen(title = stringResource(R.string.models_title), subtitle = stringResource(R.string.models_subtitle), onBack = onBack) {
    if (hasIndexer) {
      item(key = "indexer-header") { GallerySectionHeader(title = stringResource(R.string.models_section_indexer)) }
      item(key = "indexer") { IndexerBlock(state, measuring, handlers, probeFailed, probeHot, probeBusy) }
    }
    if (hasTextReading) {
      item(key = "reading-header") { GallerySectionHeader(title = stringResource(R.string.models_section_reading)) }
      item(key = "reading") { ReadingBlock(state, handlers) }
    }
    item(key = "coverage-header") { GallerySectionHeader(title = stringResource(R.string.models_section_coverage)) }
    item(key = "coverage") { CoverageBlock(state, resources) }
    if (state.indexer.areasWithoutIndexer.isNotEmpty()) {
      item(key = "areas-header") { GallerySectionHeader(title = stringResource(R.string.models_section_areas)) }
      item(key = "areas") {
        FluidListGroup {
          state.indexer.areasWithoutIndexer.forEachIndexed { index, area ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = stringResource(if (area == AreaKind.COMMON_AREA) R.string.models_area_common else R.string.models_area_private),
              subtitle = "",
              tone = FluidTone.Warning,
              disclosure = false,
              titleMaxLines = 3,
            )
          }
        }
      }
    }
    if (hasModels) modelsSection(state.models, handlers)
    item(key = "history-header") { GallerySectionHeader(title = stringResource(R.string.models_section_history)) }
    item(key = "history") { HistoryBlock(state.historyCount, handlers) }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndexerBlock(state: SearchSettingsState, measuring: Boolean, handlers: SearchSettingsHandlers, probeFailed: Boolean, probeHot: Boolean, probeBusy: Boolean) {
  val indexer = state.indexer
  val title = stringResource(R.string.models_indexer_title)
  val detail = stringResource(R.string.models_indexer_detail)
  val resources = LocalContext.current.resources
  FluidListGroup {
    FluidListRow(
      title = title,
      subtitle = detail,
      tone = if (indexer.indexer) FluidTone.Primary else FluidTone.Neutral,
      leading = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
      disclosure = false,
      titleMaxLines = 3,
      badge = { FluidSwitch(checked = indexer.indexer, onCheckedChange = null) },
      onClick = { handlers.onSetIndexer(!indexer.indexer) },
      modifier = Modifier.semantics(mergeDescendants = true) {
        role = Role.Switch
        toggleableState = ToggleableState(indexer.indexer)
        contentDescription = "$title. $detail"
      },
    )
    if (indexer.indexer) {
      FluidListDivider()
      FluidListRow(title = stringResource(R.string.models_status_title), subtitle = statusText(resources, indexer.status), tone = statusTone(indexer.status), disclosure = false, titleMaxLines = 3)
      FluidListDivider()
      FluidListRow(title = if (probeHot) stringResource(R.string.models_probe_hot) else if (probeBusy) stringResource(R.string.models_probe_busy) else if (probeFailed) stringResource(R.string.models_probe_failed) else estimateText(resources, indexer.toAnalyze, indexer.nights, measuring), subtitle = if (measuring || probeFailed || probeHot || probeBusy) "" else probeText(resources, indexer, false), disclosure = false, titleMaxLines = 4)
    }
  }
  if (state.offerRemoveIndexModel) RemoveIndexModelSheet(handlers)
  if (indexer.indexer) {
    val problem = (indexer.status as? AiStatus.Problem)?.code
    // K4: i tasti vanno a capo interi se non entrano affiancati ("Libera spazio" a testo grande si spezzava in "Libera spazi/o").
    FlowRow(
      modifier = Modifier.padding(top = SearchDimens.TightGap, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter),
      horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
      verticalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
    ) {
      if (probeFailed && !probeHot && !probeBusy && state.models.models.any { it.id == dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.INDEX_440M.id && it.state is dev.pampa.mycloudgallery.core.models.contract.ModelState.Ready }) FluidButton(text = stringResource(R.string.models_probe_retry), onClick = handlers::onRetryProbe, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large)
      FluidButton(text = stringResource(R.string.models_index_now), onClick = handlers::onIndexNow, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large)
      if (problem != null) {
        for (action in SearchCodes.actions(problem)) {
          FluidButton(text = stringResource(action.label), onClick = { runCodeAction(action, handlers) }, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large)
        }
      }
    }
  }
}

/** IX-8: spento il ruolo, "Togliere anche il modello di analisi?" (D15): 370 MB che il dispositivo non usa piu'. */
@Composable
private fun RemoveIndexModelSheet(handlers: SearchSettingsHandlers) {
  val title = stringResource(R.string.models_index_off_title)
  GalleryConfirmSheet(visible = true, onDismissRequest = handlers::onKeepIndexModel, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.Warning,
      tone = GallerySheetTone.Caution,
      title = title,
      message = stringResource(R.string.models_index_off_message),
      actions = {
        FluidButton(
          text = stringResource(R.string.models_remove_confirm),
          onClick = { handlers.onRemoveModel(dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.INDEX_440M.id) },
          style = FluidButtonStyle.Destructive,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(text = stringResource(R.string.models_index_off_keep), onClick = handlers::onKeepIndexModel, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
      },
    )
  }
}

internal fun runCodeAction(action: CodeAction, handlers: SearchSettingsHandlers) = when (action) {
  CodeAction.DIAGNOSTICS -> handlers.onOpenDiagnostics()
  CodeAction.FREE_SPACE -> handlers.onFreeSpace()
  CodeAction.ENABLE_INDEXER -> handlers.onSetIndexer(true)
  CodeAction.DOWNLOAD_NOW, CodeAction.RETRY -> handlers.onIndexNow()
}

/** Lo stato del calcolo in una frase: solo numeri e codici, mai nomi (D40). */
internal fun statusText(resources: Resources, status: AiStatus): String = when (status) {
  AiStatus.Off -> resources.getString(R.string.models_status_off)
  AiStatus.ModelMissing -> resources.getString(R.string.models_status_model_missing)
  is AiStatus.Idle -> resources.getString(R.string.models_status_idle)
  is AiStatus.Running -> status.etaMinutes?.let {
    resources.getString(R.string.models_status_running_eta, GalleryNumbers.format(status.done), GalleryNumbers.format(status.total), it)
  } ?: resources.getString(R.string.models_status_running, GalleryNumbers.format(status.done), GalleryNumbers.format(status.total))
  is AiStatus.Waiting -> resources.getString(
    when (status.reason) {
      AiWaitReason.RULES -> R.string.models_status_waiting_rules
      AiWaitReason.NO_NETWORK -> R.string.models_status_waiting_network
      AiWaitReason.NAS_UNREACHABLE -> R.string.models_status_waiting_nas
      AiWaitReason.STORAGE_LOW -> R.string.models_status_waiting_storage
      AiWaitReason.HOT -> R.string.models_status_waiting_hot
      AiWaitReason.PREPARATION -> R.string.models_status_waiting_preparation
      AiWaitReason.NEXT_STAGE -> R.string.models_status_next_stage
    },
  )
  is AiStatus.BufferOnlyDone -> resources.getString(R.string.models_status_buffer_only, GalleryNumbers.format(status.items))
  is AiStatus.Problem -> SearchCodes.message(resources, status.code)
}

private fun statusTone(status: AiStatus): FluidTone = when (status) {
  is AiStatus.Running -> FluidTone.Info
  is AiStatus.Problem -> FluidTone.Warning
  AiStatus.ModelMissing, is AiStatus.Waiting, is AiStatus.BufferOnlyDone -> FluidTone.Neutral
  AiStatus.Off, is AiStatus.Idle -> FluidTone.Success
}

/** "Analizzare N media richiede circa K notti" (D15): dalla misura e dal lavoro vero che resta. */
internal fun estimateText(resources: Resources, toAnalyze: Int, nights: Int?, measuring: Boolean = false): String = when {
  measuring -> resources.getString(R.string.models_indexer_measuring)
  toAnalyze <= 0 -> resources.getString(R.string.models_indexer_nothing)
  nights != null -> resources.getQuantityString(R.plurals.models_indexer_estimate, nights, GalleryNumbers.format(toAnalyze), nights)
  else -> resources.getString(R.string.models_indexer_no_estimate, GalleryNumbers.format(toAnalyze))
}

private fun probeText(resources: Resources, indexer: dev.pampa.mycloudgallery.feature.search.contract.IndexerState, measuring: Boolean): String {
  val probe = indexer.probe
  return when {
    measuring -> resources.getString(R.string.models_indexer_measuring)
    probe == null -> ""
    else -> resources.getString(
      R.string.models_indexer_probe,
      "%.1f".format(java.util.Locale.ITALIAN, probe.imagesPerSecond),
      resources.getString(if (probe.backend == dev.pampa.mycloudgallery.core.ai.contract.AiBackend.GPU) R.string.models_backend_gpu else R.string.models_backend_cpu),
    )
  }
}

@Composable
private fun CoverageBlock(state: SearchSettingsState, resources: Resources) {
  val coverage = state.coverage
  FluidListGroup {
    FluidListRow(
      title = coverageText(resources, coverage),
      subtitle = "",
      tone = if (coverage.warns) FluidTone.Warning else FluidTone.Success,
      disclosure = false,
      titleMaxLines = 4,
    )
    if (coverage is SearchCoverage.Partial) {
      coverage.reasons.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
        FluidListDivider()
        FluidListRow(title = reasonText(resources, reason, count), subtitle = "", disclosure = false, titleMaxLines = 4)
      }
    }
  }
  if (coverage is SearchCoverage.Partial && CoverageReason.TEXT_READING_OFF in coverage.reasons) {
    FluidSectionFootnote(text = stringResource(R.string.models_reading_coverage_note))
  }
}

internal fun coverageText(resources: Resources, coverage: SearchCoverage): String = when (coverage) {
  SearchCoverage.Complete -> resources.getString(R.string.models_coverage_complete)
  is SearchCoverage.Partial -> resources.getString(R.string.models_coverage_partial, GalleryNumbers.format(coverage.covered), GalleryNumbers.format(coverage.total))
  is SearchCoverage.ModelMissing -> resources.getString(R.string.models_coverage_model_missing, resources.getString(R.string.search_size_mb, GalleryNumbers.format((coverage.sizeBytes + HALF_MIB) / MIB)))
  is SearchCoverage.Updating -> resources.getString(R.string.models_coverage_updating, coverage.percent)
}

private fun reasonText(resources: Resources, reason: CoverageReason, count: Int): String = resources.getString(
  when (reason) {
    CoverageReason.PREPARATION -> R.string.models_reason_preparation
    CoverageReason.INDEXER_RULES -> R.string.models_reason_indexer_rules
    CoverageReason.NO_INDEXER_FOR_AREA -> R.string.models_reason_no_indexer
    CoverageReason.MODEL_MISSING -> R.string.models_reason_model_missing
    CoverageReason.PHONE_ONLY_NOT_INDEXER -> R.string.models_reason_phone_only
    CoverageReason.UNSUPPORTED -> R.string.models_reason_unsupported
    CoverageReason.TEXT_READING_OFF -> R.string.models_reason_text_reading_off
  },
  GalleryNumbers.format(count),
)

@Composable
private fun HistoryBlock(count: Int, handlers: SearchSettingsHandlers) {
  val resources = LocalContext.current.resources
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.models_history_clear),
      subtitle = if (count == 0) stringResource(R.string.models_history_none) else resources.getQuantityString(R.plurals.models_history_detail, count, GalleryNumbers.format(count)),
      tone = FluidTone.Neutral,
      disclosure = false,
      titleMaxLines = 3,
      onClick = if (count > 0) handlers::onClearHistory else null,
    )
  }
}

private const val MIB = 1_048_576L
private const val HALF_MIB = 524_288L
