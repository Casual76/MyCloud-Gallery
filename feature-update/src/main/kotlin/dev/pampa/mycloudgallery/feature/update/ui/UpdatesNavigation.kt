package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.pampa.mycloudgallery.feature.update.AppUpdates
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// LA PAGINA NELLA NAVIGAZIONE (pacchetto U). Le Impostazioni non conoscono questo modulo (i feature non si conoscono): la voce «Aggiornamenti» porta
// qui con una callback che scrive `:app` (`SettingsLinks.openUpdates`), come la Mappa e i Ricordi. `AppUpdates` lo crea `:app` (uno per processo); il
// ritorno davanti lo segue `UpdateLifecycleEffect`, alla radice.

/** La pagina Impostazioni -> Aggiornamenti. */
@Serializable
data object UpdatesRoute

/** Lo stato e le azioni dell'aggiornamento per la pagina, l'avviso e la pagina «versione fermata». */
@HiltViewModel
class UpdatesViewModel @Inject constructor(private val updates: AppUpdates) : ViewModel() {
  val state: StateFlow<UpdatesState> = updates.state
  val prompt: StateFlow<Boolean> = updates.prompt
  val actions: UpdateActions = UpdateActions.of(updates)

  fun onResumed() = updates.onResumed()

  fun onPaused() = updates.onPaused()

  /** La pagina aperta: un controllo se l'ultimo ha piu' di 24 ore (UU-4). */
  fun refreshIfDue() {
    updates.refreshIfDue()
  }
}

/** Registra la pagina in una navigazione (quella dell'app, e quella della pagina «versione fermata»). */
fun NavGraphBuilder.updatesGraph(nav: NavController) {
  composable<UpdatesRoute> { UpdatesPage(onBack = { nav.popBackStack() }) }
}

/** Apre la pagina Aggiornamenti. */
fun NavController.openUpdates() = navigate(UpdatesRoute)

@Composable
fun UpdatesPage(onBack: () -> Unit, viewModel: UpdatesViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) { viewModel.refreshIfDue() }
  UpdatesScreen(state = state, actions = viewModel.actions, onBack = onBack)
}

/**
 * L'aggiornamento dentro un'altra pagina (la pagina «versione fermata», [stopped]): la figura con il suo tasto. Senza una versione trovata offre
 * «Controlla adesso»: la versione fermata e' proprio il caso in cui l'aggiornamento e' il rimedio, e li' «nessuna versione nuova» e' la clessidra
 * («La versione nuova arriva presto»), non la spunta di «App aggiornata» (giro 2, UU2-7). Aperta, controlla da sola se sono passate 24 ore.
 */
@Composable
fun UpdateInline(viewModel: UpdatesViewModel = hiltViewModel(), stopped: Boolean = false) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) { viewModel.refreshIfDue() }
  UpdatePanel(state = state, actions = viewModel.actions, stopped = stopped)
}
