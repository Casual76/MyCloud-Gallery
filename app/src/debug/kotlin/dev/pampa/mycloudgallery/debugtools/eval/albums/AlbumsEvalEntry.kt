package dev.pampa.mycloudgallery.debugtools.eval.albums

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/**
 * SOLO debug (Fase 7, P12): i due punti d'ingresso dello strumento "Valutazione degli album", agganciati in `DeveloperTools` (debug), come quello della ricerca:
 *  - in `DeveloperTools.destinations(nav)`: `with(AlbumsEvalEntry) { destination(nav) }`;
 *  - in `DeveloperTools.settingsSection(nav)`, dentro il `FluidListGroup` (dopo `FluidListDivider()`): `AlbumsEvalEntry.row(nav)`;
 *  - in `DeveloperTools.installSearch(...)` (la porta che l'app lega all'avvio): `AlbumsEvalEnvironmentHolder.current = AppAlbumsEvalEnvironment(...)`.
 * La release non ha niente di questo: la cartella e' di `src/debug` e `check.ps1` cerca `debugtools/eval` (e i nomi di questo strumento) nel dex di release.
 */
internal object AlbumsEvalEntry {

  fun NavGraphBuilder.destination(nav: NavController) {
    composable<AlbumsEvalRoute> {
      AlbumsEvalScreen(onBack = { nav.popBackStack() })
    }
  }

  @Composable
  fun row(nav: NavController) {
    FluidListRow(title = AlbumsEvalStrings.ENTRY_TITLE, subtitle = AlbumsEvalStrings.ENTRY_DETAIL, onClick = { nav.navigate(AlbumsEvalRoute) })
  }
}
