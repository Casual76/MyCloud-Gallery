package dev.pampa.mycloudgallery.release

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.navigation.FluidNavHost
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.LocalWritesHeldByRelease
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.diagnostics.diagnosticsGraph
import dev.pampa.mycloudgallery.root.BlockingText
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// IL MANIFEST ALLA RADICE DELLA UI (Fase 10, P13; docs/fase10-progetto.md §1.5 `ReleaseUiState`, §2.8 "Che cosa puo' fare e che cosa no", E-REL5, E-REL6). Testi solo dal
// catalogo dell'app (`strings_release_root.xml`, un testo per codice di `ReleaseMessage`): il manifest non porta testo libero, link o azioni. L'aspetto e' della fase grafica:
// qui solo componenti del kit (schermata, stato vuoto, scheda, tasti).

/** Che cosa mostra la radice (§1.5). */
sealed interface ReleaseUi {
  data object Normal : ReleaseUi

  /** Sola lettura (sotto la versione minima o un blocco `WRITES`): l'app si usa, con l'avviso "Aggiorna" (E-REL5). */
  data class UpdateBanner(val message: ReleaseMessage) : ReleaseUi

  /** Versione fermata (`ALL`): solo la pagina "Questa versione e' stata fermata", con la Diagnostica e l'esportazione (E-REL6). */
  data class Stopped(val message: ReleaseMessage) : ReleaseUi

  companion object {
    fun of(status: ReleaseStatus): ReleaseUi = when (status) {
      ReleaseStatus.Normal -> Normal
      is ReleaseStatus.ReadOnly -> UpdateBanner(status.message)
      is ReleaseStatus.Stopped -> Stopped(status.message)
    }
  }
}

/** Lo stato del manifest per la radice (un `ViewModel` solo per non far vedere il grafo alla composizione). */
@HiltViewModel
class ReleaseRootViewModel @Inject constructor(release: ReleaseGraph) : ViewModel() {
  val status: StateFlow<ReleaseStatus> = release.status
}

/**
 * La radice sotto il blocco dell'app: con la versione fermata **al posto** della navigazione (nessuna Libreria in composizione, nessuna foto in memoria), con la sola lettura
 * la navigazione di sempre con l'avviso in alto (si chiude per questa volta con "Ho capito"; torna a ogni avvio finche' l'app non si aggiorna).
 *
 * RL-1 (revisione funzionale della Fase 10): `Normal` e `UpdateBanner` hanno la **stessa struttura** (una sola chiamata di [content] nello stesso posto, l'avviso sopra
 * solo se serve): l'arrivo di un blocco `WRITES` a meta' sessione, o lo stato salvato letto un attimo dopo il primo disegno, non butta e non ricompone la navigazione
 * (pagina aperta, viewer, pila). Solo `Stopped` la toglie, apposta. CA-1: in sola lettura le schermate sanno che a fermare le scritture e' il manifest
 * (`LocalWritesHeldByRelease`).
 */
@Composable
fun ReleaseRoot(ui: ReleaseUi, content: @Composable () -> Unit) {
  if (ui is ReleaseUi.Stopped) {
    ReleaseStoppedHost(ui.message)
    return
  }
  val banner = (ui as? ReleaseUi.UpdateBanner)?.message
  var dismissed by rememberSaveable(banner) { mutableStateOf(false) }
  Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalWritesHeldByRelease provides (banner != null)) { content() }
    if (banner != null && !dismissed) {
      ReleaseUpdateBanner(
        message = banner,
        onDismiss = { dismissed = true },
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
  }
}

@Serializable
internal data object ReleaseStoppedRoute

/** La versione fermata: la sua pagina e la Diagnostica (con l'esportazione), nient'altro. Pagine del Fluid Engine 3.0 (la Diagnostica e' un grafo di pagine Fluid). */
@Composable
private fun ReleaseStoppedHost(message: ReleaseMessage) {
  val nav = rememberNavController()
  FluidNavHost(navController = nav, startDestination = ReleaseStoppedRoute) {
    composable<ReleaseStoppedRoute> { ReleaseStoppedScreen(message, onDiagnostics = { nav.navigate(DiagnosticsGraphRoute) }) }
    diagnosticsGraph(nav = nav)
  }
}

/** "Questa versione e' stata fermata" (E-REL6): il perche' dal catalogo, cosa fare (aggiornare dal Pampa Store), la Diagnostica. Nessun dato si cancella. */
@Composable
fun ReleaseStoppedScreen(message: ReleaseMessage, onDiagnostics: () -> Unit) {
  FluidScreen(title = stringResource(R.string.release_stopped_title)) {
    item {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // CS-17 (fase I): la forma comune; la frase resta aperta (dice che cosa resta fermo).
        GalleryFigureState(icon = Icons.Rounded.NewReleases, title = stringResource(R.string.release_stopped_title), fill = false) {
          BlockingText(stringResource(stoppedText(message)))
          FluidButton(text = stringResource(R.string.release_open_diagnostics), onClick = onDiagnostics, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large)
        }
      }
    }
  }
}

/** L'avviso "Aggiorna" (E-REL5): il perche' dal catalogo e cosa resta fermo (le scritture sul NAS). */
@Composable
fun ReleaseUpdateBanner(message: ReleaseMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  FluidCard(modifier = modifier.fillMaxWidth(), highlighted = true) {
    Text(stringResource(R.string.release_update_title))
    Text(stringResource(updateText(message)))
    FluidButton(text = stringResource(R.string.release_update_dismiss), onClick = onDismiss, style = FluidButtonStyle.Tinted, fillWidth = true)
  }
}

internal fun stoppedText(message: ReleaseMessage): Int = when (message) {
  ReleaseMessage.UPDATE -> R.string.release_stopped_update
  ReleaseMessage.SECURITY -> R.string.release_stopped_security
  ReleaseMessage.DATA_RISK -> R.string.release_stopped_data
}

internal fun updateText(message: ReleaseMessage): Int = when (message) {
  ReleaseMessage.UPDATE -> R.string.release_update_update
  ReleaseMessage.SECURITY -> R.string.release_update_security
  ReleaseMessage.DATA_RISK -> R.string.release_update_data
}
