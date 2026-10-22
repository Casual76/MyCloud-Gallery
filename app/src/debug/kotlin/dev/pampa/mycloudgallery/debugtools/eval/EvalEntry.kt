package dev.pampa.mycloudgallery.debugtools.eval

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/**
 * SOLO debug (Fase 6, P9): i due punti d'ingresso dello strumento di valutazione, **da agganciare in P11** (P9 non tocca `DeveloperTools.kt`, fuori da
 * `src/debug/.../eval/`):
 *  - in `DeveloperTools.destinations(nav)`: `with(EvalEntry) { destination(nav) }`;
 *  - in `DeveloperTools.settingsSection(nav)`, dentro il `FluidListGroup` (dopo `FluidListDivider()`): `EvalEntry.row(nav)`;
 *  - dove P11 monta `SearchGraph` (solo debug): `EvalEnvironmentHolder.current = <la porta [EvalEnvironment]>`.
 * La release non ha niente di questo: la cartella e' di `src/debug` e `check.ps1` (P11) cerca `debugtools.eval` nel dex di release.
 */
internal object EvalEntry {

  fun NavGraphBuilder.destination(nav: NavController) {
    composable<EvalRoute> {
      EvalScreen(onBack = { nav.popBackStack() })
    }
  }

  @Composable
  fun row(nav: NavController) {
    FluidListRow(title = EvalStrings.ENTRY_TITLE, subtitle = EvalStrings.ENTRY_DETAIL, onClick = { nav.navigate(EvalRoute) })
  }
}
