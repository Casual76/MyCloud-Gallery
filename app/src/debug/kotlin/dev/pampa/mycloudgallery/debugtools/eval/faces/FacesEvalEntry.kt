package dev.pampa.mycloudgallery.debugtools.eval.faces

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/**
 * SOLO debug (Fase 8, P11): i due punti d'ingresso dello strumento "Valutazione delle persone", agganciati in `DeveloperTools` (debug), come quelli della ricerca e degli album:
 *  - in `DeveloperTools.destinations(nav)`: `with(FacesEvalEntry) { destination(nav) }`;
 *  - in `DeveloperTools.settingsSection(nav)`, dentro il `FluidListGroup` (dopo `FluidListDivider()`): `FacesEvalEntry.row(nav)`.
 * **Da agganciare in P13** (P11 non tocca `:app` fuori da `src/debug/.../eval/faces/`, e `:app` non dipende ancora da `core-faces`): la porta, in
 * `DeveloperTools.installSearch(...)` (o dove l'integrazione monta il grafo delle persone), con `FacesEvalEnvironmentHolder.current = <una classe che implementa
 * [FacesEvalEnvironment] sopra People, FacesArchive e GroupingState>`. Senza, la schermata dice che le persone non sono collegate e non fa niente.
 * La release non ha niente di questo: la cartella e' di `src/debug` e `check.ps1` cerca `debugtools/eval/faces` (e i nomi di questo strumento) nel dex di release.
 */
internal object FacesEvalEntry {

  fun NavGraphBuilder.destination(nav: NavController) {
    composable<FacesEvalRoute> {
      FacesEvalScreen(onBack = { nav.popBackStack() })
    }
  }

  @Composable
  fun row(nav: NavController) {
    FluidListRow(title = FacesEvalStrings.ENTRY_TITLE, subtitle = FacesEvalStrings.ENTRY_DETAIL, onClick = { nav.navigate(FacesEvalRoute) })
  }
}
