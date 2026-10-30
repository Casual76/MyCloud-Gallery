package dev.pampa.mycloudgallery.debugtools.eval.memories

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/**
 * SOLO debug (Fase 9, P11): i due punti d'ingresso dello strumento "Valutazione dei ricordi", agganciati in `DeveloperTools` (debug), come quelli degli album e delle persone:
 *  - in `DeveloperTools.destinations(nav)`: `with(MemoriesEvalEntry) { destination(nav) }`;
 *  - in `DeveloperTools.settingsSection(nav)`, dentro il `FluidListGroup` (dopo `FluidListDivider()`): `MemoriesEvalEntry.row(nav)`.
 * La porta ([MemoriesEvalEnvironmentHolder.current]) la mette P13 sopra il motore dei ricordi vero; fino ad allora la schermata dice "non collegati". La release non ha
 * niente di questo: la cartella e' di `src/debug` e `check.ps1` cerca `debugtools/eval` (e i nomi di questo strumento) nel dex di release.
 */
internal object MemoriesEvalEntry {

  fun NavGraphBuilder.destination(nav: NavController) {
    composable<MemoriesEvalRoute> {
      MemoriesEvalScreen(onBack = { nav.popBackStack() })
    }
  }

  @Composable
  fun row(nav: NavController) {
    FluidListRow(title = MemoriesEvalStrings.ENTRY_TITLE, subtitle = MemoriesEvalStrings.ENTRY_DETAIL, onClick = { nav.navigate(MemoriesEvalRoute) })
  }
}
