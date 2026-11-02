package dev.pampa.mycloudgallery.debugtools

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepSimulation
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepSimulationModel
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepSimulationScreen
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepSimulationState
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepSimulationText
import dev.pampa.mycloudgallery.release.StepSimulationWiring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** SOLO debug: la rotta della "Simulazione del passo" (Fase 10 P10). */
@Serializable
data object StepSimulationRoute

/**
 * SOLO debug (build dell'admin; la release non ha niente di questo): il punto d'ingresso di "Simula il passo", agganciato in `DeveloperTools` (debug):
 *  - in `DeveloperTools.destinations(nav)`: `with(StepSimulationEntry) { destination(nav) }`;
 *  - in `DeveloperTools.settingsSection(nav)`, dentro il `FluidListGroup`: `StepSimulationEntry.row(nav)`;
 *  - in `DeveloperTools.installSearch(...)` e `installPeople(...)`: le porte `StepSimulationHolder.gallery` e `.faces`.
 * Sola lettura: la schermata e il giro stanno in `feature-diagnostics`, le fonti in `:app/.../release/`. `check.ps1` cerca i nomi della simulazione nel
 * dex di release e li vieta.
 */
internal object StepSimulationEntry {

  fun NavGraphBuilder.destination(nav: NavController) {
    composable<StepSimulationRoute> { StepSimulationHost(onBack = { nav.popBackStack() }) }
  }

  @Composable
  fun row(nav: NavController) {
    FluidListRow(title = StepSimulationText.TITLE, subtitle = ENTRY_DETAIL, onClick = { nav.navigate(StepSimulationRoute) })
  }

  private const val ENTRY_DETAIL: String =
    "Sola lettura: che cosa scriverebbero le quattro aree sulle share vere, quanti file e byte, in quali cartelle, e lo spazio libero dopo il passo."
}

@Composable
private fun StepSimulationHost(onBack: () -> Unit) {
  val sources = remember { StepSimulationWiring.liveFromHolder() }
  val model = remember(sources) { sources?.let { StepSimulationModel(StepSimulation(it)) } }
  val idle = remember { MutableStateFlow<StepSimulationState>(StepSimulationState.Idle) }
  val state by (model?.state ?: idle).collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var copied by remember { mutableStateOf(false) }
  StepSimulationScreen(
    state = state,
    copied = copied,
    onRun = {
      copied = false
      // Fuori dal thread principale: il riepilogo del backup e il catalogo si leggono da qui.
      model?.let { scope.launch(Dispatchers.Default) { it.run() } }
    },
    onCopy = {
      val done = state as? StepSimulationState.Done
      if (done != null) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("resoconto", StepSimulationText.export(done.report)))
        copied = true
      }
    },
    onBack = onBack,
  )
}
