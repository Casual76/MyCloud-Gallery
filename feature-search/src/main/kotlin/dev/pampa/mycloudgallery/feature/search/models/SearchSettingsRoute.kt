package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.ai.contract.DeviceProbe
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.noop.NoSearchEngine
import dev.pampa.mycloudgallery.core.search.noop.NoSearchHistory
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import dev.pampa.mycloudgallery.feature.search.contract.SearchActions
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingAccepted
import dev.pampa.mycloudgallery.feature.search.ui.SearchHost
import java.util.Optional
import javax.inject.Inject

// Le ROTTE della pagina Ricerca e modelli e della pagina di attivazione (Fase 6, P8), per `:app` (P11). Come per Cerca, tutte le porte sono opzionali per
// Dagger ([dev.pampa.mycloudgallery.feature.search.SearchPortsModule]): una porta non collegata nasconde la sua sezione invece di mostrare una riga che non fa niente (D13).

@HiltViewModel
class SearchSettingsViewModel @Inject constructor(
  indexer: Optional<IndexerControl>,
  models: Optional<ModelManager>,
  engine: Optional<SearchEngine>,
  history: Optional<SearchHistory>,
  textReading: Optional<TextReadingSwitch>,
  accepted: Optional<TextReadingAccepted>,
  probe: Optional<DeviceProbe>,
) : ViewModel() {

  val controller: SearchSettingsController = SearchSettingsController(
    indexer = indexer.orElse(null),
    models = models.orElse(null),
    engine = engine.orElse(NoSearchEngine),
    history = history.orElse(NoSearchHistory),
    textReading = textReading.orElse(null),
    accepted = accepted.orElse(null),
    probe = probe.orElse(null),
    scope = viewModelScope,
  )

  init {
    // IX-3: a ogni apertura della pagina il lavoro che resta, lo spazio e lo stato si rileggono.
    controller.refresh()
  }
}

/** Le richieste della pagina: quelle del [controller] e, per le uscite (Diagnostica, pagina di attivazione, "Libera spazio"), le [SearchActions] di `:app`. */
internal class ActionSettingsHandlers(
  private val controller: SearchSettingsController,
  private val actions: SearchActions,
) : SearchSettingsHandlers {
  override fun onSetIndexer(on: Boolean) = controller.setIndexer(on)

  override fun onRetryProbe() = controller.measure()

  override fun onIndexNow() = controller.indexNow()

  override fun onOpenActivation() = actions.openTextReadingActivation()

  override fun onExcludeThisDevice() = controller.excludeThisDevice()

  override fun onIncludeThisDevice() = controller.includeThisDevice()

  override fun onDisableReading() = controller.disableTextReading()

  override fun onDownloadModel(id: ModelId) = controller.downloadModel(id)

  override fun onConfirmMobileDownload(id: ModelId) = controller.confirmMobileDownload(id)

  override fun onRemoveModel(id: ModelId) = controller.removeModel(id)

  override fun onKeepIndexModel() = controller.dismissRemoveOffer()

  override fun onClearHistory() = controller.clearHistory()

  override fun onOpenDiagnostics() = actions.openDiagnostics()

  override fun onFreeSpace() = actions.openFreeSpace()
}

@Composable
fun SearchSettingsRoute(actions: SearchActions, onBack: () -> Unit, viewModel: SearchSettingsViewModel = hiltViewModel()) {
  val controller = viewModel.controller
  val state by controller.state.collectAsStateWithLifecycle()
  val measuring by controller.measuring.collectAsStateWithLifecycle()
  val probeFailed by controller.probeFailed.collectAsStateWithLifecycle()
  val handlers = remember(controller, actions) { ActionSettingsHandlers(controller, actions) }
  SearchHost {
    SearchSettingsScreen(
      state = state,
      measuring = measuring,
      probeFailed = probeFailed,
      hasIndexer = controller.hasIndexer,
      hasTextReading = controller.hasTextReading,
      hasModels = controller.hasModels,
      handlers = handlers,
      onBack = onBack,
    )
  }
}

/**
 * La pagina di attivazione della lettura del testo (D41). "Attiva" salva il consenso con la versione del testo e chiude; "Non ora" chiude e non cambia niente.
 * Senza il collegamento alla lettura del testo la pagina non ha niente da accendere: `:app` non la apre.
 */
@Composable
fun TextReadingActivationRoute(onClose: () -> Unit, viewModel: SearchSettingsViewModel = hiltViewModel()) {
  val controller = viewModel.controller
  val state by controller.state.collectAsStateWithLifecycle()
  val reading = state.textReading
  TextReadingActivationScreen(
    onActivate = { consent ->
      controller.enableTextReading(consent)
      onClose()
    },
    onNotNow = onClose,
    onBack = onClose,
    versionChanged = reading.state is TextReadingState.OnForAccount && reading.needsConsent,
    awaitingNotice = reading.state is TextReadingState.AwaitingNotice,
  )
}
