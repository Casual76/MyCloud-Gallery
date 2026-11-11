package dev.pampa.mycloudgallery.feature.search.testing

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
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

  /** Con la lettura del testo attiva per l'account e su questo dispositivo, il modello delle query pronto e il 440M scaricato. */
  val settingsReadingOn: SearchSettingsState = settingsIndexer.copy(
    textReading = TextReadingPageState(TextReadingState.OnForAccount(thisDevice = true), TextReadingSwitch.CURRENT_TEXT_VERSION, needsConsent = false),
    coverage = SearchCoverage.Complete,
    indexer = settingsIndexer.indexer.copy(status = AiStatus.Idle(null), toAnalyze = 0, nights = null, areasWithoutIndexer = emptyList()),
  )

  /** La lettura attiva per l'account ma questo dispositivo si e' escluso, e il testo della spiegazione e' cambiato: va riletto. */
  val settingsReadingChanged: SearchSettingsState = settingsIndexer.copy(
    textReading = TextReadingPageState(TextReadingState.OnForAccount(thisDevice = false), TextReadingSwitch.CURRENT_TEXT_VERSION, needsConsent = true),
  )

  /** Un telefono che non analizza, con i problemi dei modelli e del calcolo (codici del §2.6). */
  val settingsProblems: SearchSettingsState = settingsIndexer.copy(
    indexer = settingsIndexer.indexer.copy(
      indexer = true,
      status = AiStatus.Problem(AiDiag.AI_STORAGE_LOW),
      probe = ProbeResult(imagesPerSecond = 2.8, backend = AiBackend.CPU, coldStartMs = 3_600),
      nights = 2,
    ),
    models = settingsIndexer.models.copy(
      models = listOf(
        ModelRow(ModelCatalog.QUERY_270M.id, ModelState.Failed(ModelFailCode.FETCH_FAILED), ModelCatalog.QUERY_270M.sizeBytes, null, removable = true),
        ModelRow(ModelCatalog.INDEX_440M.id, ModelState.Failed(ModelFailCode.NO_SPACE), ModelCatalog.INDEX_440M.sizeBytes, null, removable = true),
        ModelRow(ModelCatalog.CUTOUT_MAGICTOUCH.id, ModelState.Ready(ModelCatalog.CUTOUT_MAGICTOUCH.sizeBytes, ModelSourceKind.BUNDLED, 0), ModelCatalog.CUTOUT_MAGICTOUCH.sizeBytes, ModelSourceKind.BUNDLED, removable = false),
      ),
    ),
    coverage = SearchCoverage.ModelMissing(ModelCatalog.QUERY_270M.sizeBytes),
    historyCount = 0,
  )

  /** Un tablet che non analizza e ha il modello delle query da scaricare. */
  val settingsAbsent: SearchSettingsState = settingsIndexer.copy(
    indexer = IndexerState(indexer = false, status = AiStatus.Off, probe = null, toAnalyze = 0, nights = null, areasWithoutIndexer = emptyList()),
    models = settingsIndexer.models.copy(
      models = listOf(
        ModelRow(ModelCatalog.QUERY_270M.id, ModelState.Absent, ModelCatalog.QUERY_270M.sizeBytes, null, removable = true),
        ModelRow(ModelCatalog.INDEX_440M.id, ModelState.Absent, ModelCatalog.INDEX_440M.sizeBytes, null, removable = true),
        ModelRow(ModelCatalog.CUTOUT_MAGICTOUCH.id, ModelState.Ready(ModelCatalog.CUTOUT_MAGICTOUCH.sizeBytes, ModelSourceKind.BUNDLED, 0), ModelCatalog.CUTOUT_MAGICTOUCH.sizeBytes, ModelSourceKind.BUNDLED, removable = false),
      ),
    ),
    coverage = SearchCoverage.ModelMissing(ModelCatalog.QUERY_270M.sizeBytes),
    historyCount = 3,
  )
}

/** Le azioni registrate (per i test di P8). */
class RecordingSearchActions : SearchActions {
  val calls: MutableList<String> = mutableListOf()

  override fun openResults(view: OrderedTimelineView, start: MediaId, anchor: ViewerAnchor) {
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

  override fun openFreeSpace() {
    calls += "freeSpace"
  }
}