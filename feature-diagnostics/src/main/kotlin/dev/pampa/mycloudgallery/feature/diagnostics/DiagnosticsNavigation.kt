package dev.pampa.mycloudgallery.feature.diagnostics

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.antigravity.fluidengine.ui.navigation.composable
import androidx.navigation.navigation
import androidx.navigation.toRoute
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.feature.diagnostics.actions.ActionIntents
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagActionPolicy
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsExportScreen
import dev.pampa.mycloudgallery.feature.diagnostics.ui.DiagnosticsSectionScreen
import kotlinx.serialization.Serializable

@Serializable
data object DiagnosticsGraphRoute

@Serializable
data object DiagnosticsHomeRoute

/** Una sezione; il nome e' quello di [DiagSection] (le rotte tipizzate non portano un enum di un altro modulo). */
@Serializable
data class DiagnosticsSectionRoute(val section: String)

@Serializable
data object DiagnosticsExportRoute

/** Il nome del file proposto al selettore di sistema. */
internal const val EXPORT_FILE_NAME = "mycloudgallery-diagnostica.json"
internal const val EXPORT_MIME_TYPE = "application/json"

/**
 * La Diagnostica (docs/fase2-progetto.md §2.6): la pagina con le nove sezioni, il dettaglio di ognuna, il test di
 * connessione e l'esportazione con anteprima (`ACTION_CREATE_DOCUMENT`).
 *
 * Nessuna uscita verso altre funzioni di suo (`feature-*` non si conoscono fra loro): le azioni che portano fuori
 * (aprire i dispositivi, riprendere la configurazione, reinserire la password) le esegue `:app`, che le dichiara in
 * [appActions] ed esegue in [onAppAction]. Le altre azioni (aprire le impostazioni di Android o WireGuard, lanciare il
 * test) le esegue questo modulo. Un'azione che nessuno sa eseguire non ha il tasto.
 *
 * Il [DiagnosticsViewModel] e' uno solo per tutto il grafo: le pagine condividono la stessa lettura e lo stesso
 * esito del test, e tornando indietro non si rifa' niente.
 */
fun NavGraphBuilder.diagnosticsGraph(
  nav: NavController,
  appActions: Set<DiagAction> = emptySet(),
  onAppAction: (DiagAction) -> Unit = {},
) {
  navigation<DiagnosticsGraphRoute>(startDestination = DiagnosticsHomeRoute) {
    composable<DiagnosticsHomeRoute> { entry ->
      val viewModel = graphViewModel(nav, entry)
      val state by viewModel.state.collectAsStateWithLifecycle()
      DiagnosticsScreen(
        state = state,
        onBack = { nav.popBackStack() },
        onRefresh = viewModel::refresh,
        onOpenSection = { nav.navigate(DiagnosticsSectionRoute(it.name)) },
        onExport = { nav.navigate(DiagnosticsExportRoute) },
      )
    }

    composable<DiagnosticsSectionRoute> { entry ->
      val viewModel = graphViewModel(nav, entry)
      val state by viewModel.state.collectAsStateWithLifecycle()
      val section = DiagSection.entries.firstOrNull { it.name == entry.toRoute<DiagnosticsSectionRoute>().section }
      if (section == null) {
        // Una rotta con un nome che non esiste (link vecchio): si torna alla pagina d'ingresso.
        DisposableEffect(Unit) {
          nav.popBackStack()
          onDispose { }
        }
        return@composable
      }
      val actions = remember(state.wireGuardAvailable, appActions) {
        DiagActionPolicy(appActions = appActions, wireGuardAvailable = state.wireGuardAvailable)
      }
      val run = rememberActionRunner(
        viewModel = viewModel,
        nav = nav,
        currentSection = section,
        appActions = appActions,
        onAppAction = onAppAction,
      )
      DiagnosticsSectionScreen(
        section = section,
        state = state,
        onBack = { nav.popBackStack() },
        onRefresh = viewModel::refresh,
        onRunTest = viewModel::runConnectionTest,
        onAction = run,
        actions = actions,
      )
    }

    composable<DiagnosticsExportRoute> { entry ->
      val viewModel = graphViewModel(nav, entry)
      val state by viewModel.state.collectAsStateWithLifecycle()
      val context = LocalContext.current
      val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.saveExport(uri, ContentResolverExportSink(context.contentResolver))
      }
      DisposableEffect(Unit) {
        // Revisione 2, F15: l'anteprima si ferma qui; si salva quella, anche se intanto finisce il test.
        viewModel.freezeExport()
        onDispose { viewModel.clearSaveResult() }
      }
      DiagnosticsExportScreen(
        document = state.export,
        refused = state.exportRefused,
        saveResult = state.saveResult,
        onBack = { nav.popBackStack() },
        onSave = { launcher.launch(EXPORT_FILE_NAME) },
      )
    }
  }
}

/** Il view model condiviso dal grafo: lo stesso per la pagina d'ingresso, le sezioni e l'esportazione. */
@Composable
private fun graphViewModel(nav: NavController, entry: NavBackStackEntry): DiagnosticsViewModel {
  val parent = remember(entry) { nav.getBackStackEntry<DiagnosticsGraphRoute>() }
  return hiltViewModel(viewModelStoreOwner = parent)
}

/**
 * Cosa succede toccando un'azione: il test di connessione si lancia (portando alla sua pagina se non ci si e' gia'),
 * le schermate di Android si aprono con il primo intent che Android risolve, il resto va a `:app`.
 */
@Composable
private fun rememberActionRunner(
  viewModel: DiagnosticsViewModel,
  nav: NavController,
  currentSection: DiagSection,
  appActions: Set<DiagAction>,
  onAppAction: (DiagAction) -> Unit,
): (DiagAction) -> Unit {
  val context = LocalContext.current
  return remember(viewModel, nav, currentSection, appActions, onAppAction, context) {
    { action ->
      when {
        action in DiagnosticsActions.LOCAL_ACTIONS -> {
          if (currentSection != DiagSection.CONNECTION_TEST) {
            nav.navigate(DiagnosticsSectionRoute(DiagSection.CONNECTION_TEST.name))
          }
          viewModel.runConnectionTest()
        }
        action in DiagnosticsActions.INTENT_ACTIONS -> {
          if (!ActionIntents.launch(context, action, viewModel.wireGuardIntent())) {
            Toast.makeText(context, R.string.diagnostics_action_failed, Toast.LENGTH_SHORT).show()
          }
        }
        action in appActions -> onAppAction(action)
      }
    }
  }
}
