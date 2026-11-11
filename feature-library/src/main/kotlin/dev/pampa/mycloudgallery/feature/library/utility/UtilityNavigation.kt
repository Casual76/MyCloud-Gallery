package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.page.FluidBackSwipe
import androidx.navigation.toRoute
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.feature.library.collections.RecentlyDeletedDestination
import kotlinx.serialization.Serializable

// Fase 7, P8 (D24): le rotte dell'Utility. Sul telefono ogni pagina e' a schermo intero, raggiunta dall'elenco delle Raccolte; sul tablet l'elenco ha il
// dettaglio accanto (`UtilityPageRoute`) e queste rotte non servono. Gli Eliminati di recente tengono la rotta della Fase 3 (`RecentlyDeletedDestination`
// in `collectionsGraph`): la pagina e' la stessa, adesso con i due cestini, e la rotta e' una sola.

/** La pagina-filtro di Utility: [code] e' il `code` di una [UtilityPage] (stabile, per questo una stringa). */
@Serializable
data class UtilityFilterDestination(val code: String)

/** I Doppioni: le due schede "Identici" e "Simili". */
@Serializable
data object DuplicatesDestination

/** Apre la pagina di [page] sul telefono (a schermo intero). Gli Eliminati di recente aprono la rotta propria. */
fun NavController.openUtility(page: UtilityPage) {
  when (page) {
    UtilityPage.DUPLICATES -> navigate(DuplicatesDestination)
    UtilityPage.RECENTLY_DELETED -> navigate(RecentlyDeletedDestination)
    else -> navigate(UtilityFilterDestination(page.code))
  }
}

/**
 * Le pagine dell'Utility nel grafo di navigazione: la pagina-filtro (Modificate) e i Doppioni. Gli Eliminati di recente restano nel grafo delle Raccolte.
 * Il viewer non e' una rotta (D12): [onOpenViewer] manda la richiesta a `:app`, che lo ospita sopra la pagina corrente.
 */
fun NavGraphBuilder.utilityGraph(nav: NavController, onOpenViewer: (ViewerRequest) -> Unit) {
  val back: () -> Unit = { nav.popBackStack() }

  composable<UtilityFilterDestination>(backSwipe = FluidBackSwipe.EdgeOnly) { entry ->
    val page = UtilityPage.fromCode(entry.toRoute<UtilityFilterDestination>().code)?.takeIf { it.isFilter }
    if (page == null) {
      // Una rotta salvata da una versione piu' nuova (o un codice sbagliato): meglio tornare indietro che una pagina vuota.
      UnknownUtilityPage(back)
    } else {
      UtilityFilterRoute(page = page, onOpenViewer = onOpenViewer, onBack = back)
    }
  }
  composable<DuplicatesDestination> { DuplicatesRoute(onBack = back) }
}

@Composable
private fun UnknownUtilityPage(back: () -> Unit) {
  LaunchedEffect(Unit) { back() }
}
