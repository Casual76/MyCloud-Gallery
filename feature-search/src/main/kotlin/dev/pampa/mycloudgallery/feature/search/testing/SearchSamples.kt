package dev.pampa.mycloudgallery.feature.search.testing

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.feature.search.contract.AreaKind
import dev.pampa.mycloudgallery.feature.search.contract.IndexerState
import dev.pampa.mycloudgallery.feature.search.contract.ModelRow
import dev.pampa.mycloudgallery.feature.search.contract.ModelsState
import dev.pampa.mycloudgallery.feature.search.contract.SearchActions
import dev.pampa.mycloudgallery.feature.search.contract.SearchSettingsState
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingPageState

// Stati di esempio per i test e gli screenshot di P8 (Fase 6, P0). Nessun dato personale. Mai nella release (tools/check.ps1).

object SearchSamples {
  /** La pagina Ricerca e modelli di un telefono Indicizzatore con il 270M pronto, il 440M in scaricamento, la lettura spenta. */
  val settingsIndexer: SearchSettingsState = SearchSettingsState(
    indexer = IndexerState(
      indexer = true,
      status = AiStatus.Running(done = 1_240, total = 15_000, etaMinutes = 95, backend = AiBackend.GPU),
      probe = null,
      toAnalyze = 13_760,
      nights = 2,
      areasWithoutIndexer = listOf(AreaKind.COMMON_AREA),
    ),
    textReading = TextReadingPageState(TextReadingState.Off, TextReadingSwitch.CURRENT_TEXT_VERSION, needsConsent = true),
    models = ModelsState(
      models = listOf(
        ModelRow(ModelCatalog.QUERY_270M.id, ModelState.Ready(ModelCatalog.QUERY_270M.sizeBytes, ModelSourceKind.NAS, 0), ModelCatalog.QUERY_270M.sizeBytes, ModelSourceKind.NAS, removable = true),
        ModelRow(ModelCatalog.INDEX_440M.id, ModelState.Fetching(ModelSourceKind.HTTPS, 120_000_000, ModelCatalog.INDEX_440M.sizeBytes), ModelCatalog.INDEX_440M.sizeBytes, null, removable = true),
        ModelRow(ModelCatalog.CUTOUT_MAGICTOUCH.id, ModelState.Ready(ModelCatalog.CUTOUT_MAGICTOUCH.sizeBytes, ModelSourceKind.BUNDLED, 0), ModelCatalog.CUTOUT_MAGICTOUCH.sizeBytes, ModelSourceKind.BUNDLED, removable = false),
      ),
      vectorBytes = 48_000_000,
      textBytes = 14_000_000,
    ),
    coverage = SearchCoverage.Partial(covered = 12_300, total = 15_000, reasons = mapOf(CoverageReason.PREPARATION to 2_700)),
    historyCount = 12,
  )
}

/** Le azioni registrate (per i test di P8). */
class RecordingSearchActions : SearchActions {
  val calls: MutableList<String> = mutableListOf()

  override fun openResults(view: OrderedTimelineView, start: MediaId) {
    calls += "results"
  }

  override fun openSearchSettings() {
    calls += "settings"
  }

  override fun openTextReadingActivation() {
    calls += "activation"
  }

  override fun openDiagnostics() {
    calls += "diagnostics"
  }

  override fun requestMediaAccess() {
    calls += "access"
  }
}