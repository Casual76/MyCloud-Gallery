package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisLiveSource
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import dev.pampa.mycloudgallery.feature.search.contract.SearchActions
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.ui.SearchHost
import java.util.Optional
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * La rotta della pagina "Analisi delle foto" (Pacchetto X): lo stato ridotto e il nastro dalle porte (opzionali, come il resto della
 * pagina Ricerca e modelli). Senza il controllo dell'analisi la pagina non si apre (la testata non e' toccabile); senza la sorgente del
 * nastro mostra solo lo stato.
 */
@HiltViewModel
class AnalysisViewModel @Inject constructor(
  indexer: Optional<IndexerControl>,
  liveSource: Optional<AnalysisLiveSource>,
  models: Optional<ModelManager>,
) : ViewModel() {
  private val control: IndexerControl? = indexer.orElse(null)
  private val manager: ModelManager? = models.orElse(null)

  val status: StateFlow<LibraryAnalysisStatus> = control?.analysis ?: MutableStateFlow(LibraryAnalysisStatus.Idle)

  val live: StateFlow<AnalysisFlow> = liveSource.orElse(null)?.live ?: MutableStateFlow(AnalysisFlow.EMPTY)

  init {
    // Come la pagina Ricerca e modelli: a ogni apertura lavoro, spazio e stato si rileggono.
    control?.refresh()
  }

  fun analyzeNow() {
    control?.indexNow()
  }

  /** I rimedi di un problema, come nella pagina Ricerca e modelli (`runCodeAction`): richieste, mai lavori nascosti. */
  fun codeAction(action: CodeAction, actions: SearchActions) {
    when (action) {
      CodeAction.DIAGNOSTICS -> actions.openDiagnostics()
      CodeAction.FREE_SPACE -> actions.openFreeSpace()
      CodeAction.ENABLE_INDEXER -> viewModelScope.launch { runCatching { control?.setIndexer(true) } }
      CodeAction.DOWNLOAD_NOW -> {
        val manager = manager ?: return
        viewModelScope.launch { runCatching { manager.ensure(ModelCatalog.INDEX_440M.id, ModelTrigger.USER_NOW, DOWNLOAD_BUDGET) } }
      }
      CodeAction.RETRY -> analyzeNow()
    }
  }

  private companion object {
    val DOWNLOAD_BUDGET = 30.minutes
  }
}

@Composable
fun AnalysisRoute(
  actions: SearchActions,
  thumbnails: SearchThumbnails?,
  onBack: () -> Unit,
  viewModel: AnalysisViewModel = hiltViewModel(),
) {
  val status by viewModel.status.collectAsStateWithLifecycle()
  val live by viewModel.live.collectAsStateWithLifecycle()
  val handlers = remember(viewModel, actions) {
    object : AnalysisHandlers {
      override fun onAnalyzeNow() = viewModel.analyzeNow()

      override fun onCodeAction(action: CodeAction) = viewModel.codeAction(action, actions)
    }
  }
  SearchHost {
    AnalysisScreen(status = status, flow = live, thumbnails = thumbnails, handlers = handlers, onBack = onBack)
  }
}
