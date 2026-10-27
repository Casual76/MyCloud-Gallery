package dev.pampa.mycloudgallery.feature.nas.hub

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.R
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersRoute
import dev.pampa.mycloudgallery.feature.nas.operations.NasOperationsRoute
import dev.pampa.mycloudgallery.feature.nas.space.NasSpaceRoute
import dev.pampa.mycloudgallery.feature.nas.trash.NasTrashElsewhere
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// Il punto di collegamento fra le quattro schermate della 4B e il resto dell'app (docs/fase4-progetto.md §1.1, §1.5). I `feature-*`
// non si conoscono: `:app` (P16) vede questo file e `feature-library` (che ospita l'elenco delle Raccolte) riceve da `:app` solo dati.
//
//  - [nasHubEntries]: le quattro voci per Raccolte, come dati (titolo, frase, glifo, tono, numero di cose da guardare);
//  - [NasScreenContent]: la pagina di una voce, da mostrare nel pannello di dettaglio del tablet o in una rotta;
//  - [nasGraph] e [openNas]: le rotte (`NasScreen.route`, mai un percorso del NAS dentro).

/**
 * La pagina "Cestino" di Cartelle NAS (ED-1, R4 della revisione di Fase 7). **Non e' di questo modulo**: mostra nome e cartella dei file eliminati, fra
 * cui ci possono essere quelli di media nascosti, quindi sta dietro il cancello dei Nascosti. La da' `:app`: gli Eliminati di recente (telefono e NAS
 * insieme, dietro la credenziale di sistema). Senza, [NasScreenContent] mostra una pagina che manda li' e non elenca niente.
 *
 * Gli argomenti sono quelli di ogni pagina di questo modulo: il modificatore, il tasto indietro (null nel pannello di dettaglio del tablet),
 * l'ingombro della pillola e il fondale per il vetro.
 */
typealias NasTrashPage = @Composable (
  modifier: Modifier,
  onBack: (() -> Unit)?,
  hostPadding: PaddingValues,
  backdropOut: ((GlassBackdropState) -> Unit)?,
) -> Unit

/** Una voce delle schermate della 4B in Raccolte. */
@Immutable
data class NasHubEntry(
  val screen: NasScreen,
  val title: String,
  val subtitle: String,
  val glyph: ImageVector,
  val tone: FluidTone,
  /** Quante cose chiedono un'occhiata adesso (le operazioni in corso); 0 = niente da segnalare. */
  val badge: Int = 0,
)

/** Le operazioni attive, per il numero accanto alla voce "Operazioni in corso". */
@HiltViewModel
class NasHubViewModel @Inject constructor(operations: NasOperations) : ViewModel() {
  val activeOperations: StateFlow<Int> = operations.operations
    .map { list -> list.count { it.active } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), operations.operations.value.count { it.active })

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** Le quattro voci, nell'ordine in cui compaiono in Raccolte. */
@Composable
fun nasHubEntries(viewModel: NasHubViewModel = hiltViewModel()): List<NasHubEntry> {
  val active by viewModel.activeOperations.collectAsStateWithLifecycle()
  return nasHubEntries(active)
}

/** Come sopra, da un numero (i test e le anteprime non hanno un ViewModel). */
@Composable
fun nasHubEntries(activeOperations: Int): List<NasHubEntry> = listOf(
  NasHubEntry(NasScreen.FOLDERS, stringResource(R.string.nas_hub_folders_title), stringResource(R.string.nas_hub_folders_subtitle), Icons.Rounded.Folder, FluidTone.Primary),
  NasHubEntry(NasScreen.SPACE, stringResource(R.string.nas_hub_space_title), stringResource(R.string.nas_hub_space_subtitle), Icons.Rounded.Storage, FluidTone.Info),
  NasHubEntry(NasScreen.TRASH, stringResource(R.string.nas_hub_trash_title), stringResource(R.string.nas_hub_trash_subtitle), Icons.Rounded.Delete, FluidTone.Danger),
  NasHubEntry(
    screen = NasScreen.OPERATIONS,
    title = stringResource(R.string.nas_hub_ops_title),
    subtitle = if (activeOperations > 0) {
      pluralStringResource(R.plurals.nas_hub_ops_subtitle_active, activeOperations, GalleryNumbers.format(activeOperations))
    } else {
      stringResource(R.string.nas_hub_ops_subtitle_none)
    },
    glyph = Icons.Rounded.Sync,
    tone = FluidTone.Success,
    badge = activeOperations,
  ),
)

/**
 * La pagina di [screen], nello stesso modo per il pannello di dettaglio del tablet e per una rotta. [onNavigate] porta da una
 * schermata a un'altra (il "Vedi" dopo un'operazione, la riga del cestino in Spazio NAS).
 *
 * @param onBack il tasto indietro; null nel pannello di dettaglio.
 * @param hostPadding l'ingombro della pillola delle schede quando la pagina sta nel pannello di dettaglio.
 * @param onOpenFile cosa fare toccando un file in Cartelle NAS (aprire il viewer, che e' di `:app`).
 * @param trashPage la pagina del Cestino, dietro il cancello dei Nascosti ([NasTrashPage]); senza, una pagina che non elenca niente.
 */
@Composable
fun NasScreenContent(
  screen: NasScreen,
  onNavigate: (NasScreen) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  onOpenFile: ((share: String, path: SharePath) -> Unit)? = null,
  trashPage: NasTrashPage? = null,
) {
  when (screen) {
    NasScreen.FOLDERS -> NasFoldersRoute(
      modifier = modifier,
      onBack = onBack,
      hostPadding = hostPadding,
      backdropOut = backdropOut,
      onOpenOperations = { onNavigate(NasScreen.OPERATIONS) },
      onOpenFile = onOpenFile,
    )
    NasScreen.SPACE -> NasSpaceRoute(
      modifier = modifier,
      onBack = onBack,
      hostPadding = hostPadding,
      backdropOut = backdropOut,
      onOpenTrash = { onNavigate(NasScreen.TRASH) },
    )
    NasScreen.TRASH -> if (trashPage != null) {
      trashPage(modifier, onBack, hostPadding, backdropOut)
    } else {
      NasTrashElsewhere(modifier = modifier, onBack = onBack, hostPadding = hostPadding, backdropOut = backdropOut)
    }
    NasScreen.OPERATIONS -> NasOperationsRoute(modifier = modifier, onBack = onBack, hostPadding = hostPadding, backdropOut = backdropOut)
  }
}

/** Apre [screen] sul telefono (a schermo intero). Le rotte sono stringhe stabili senza dati dell'utente dentro. */
fun NavController.openNas(screen: NasScreen) {
  navigate(screen.route)
}

/** Le quattro pagine nel grafo di navigazione di `:app`. */
fun NavGraphBuilder.nasGraph(
  nav: NavController,
  onOpenFile: ((share: String, path: SharePath) -> Unit)? = null,
  trashPage: NasTrashPage? = null,
) {
  val back: () -> Unit = { nav.popBackStack() }
  for (screen in NasScreen.entries) {
    composable(screen.route) {
      NasScreenContent(screen = screen, onNavigate = nav::openNas, onBack = back, onOpenFile = onOpenFile, trashPage = trashPage)
    }
  }
}
