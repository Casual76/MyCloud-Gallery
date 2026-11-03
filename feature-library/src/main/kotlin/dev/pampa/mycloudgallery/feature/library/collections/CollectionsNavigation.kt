package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.page.FluidBackSwipe
import androidx.navigation.toRoute
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenRoute
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedRoute
import kotlinx.serialization.Serializable

// Le rotte delle Raccolte (docs/fase3-progetto.md §1.2, `collectionsGraph`). Sul telefono ogni raccolta e' una pagina a
// schermo intero raggiunta dall'elenco; sul tablet l'elenco ha il dettaglio accanto e queste rotte non servono.

/** La pagina di una raccolta-filtro; [kind] e' il `code` di una [CollectionKind] (stabile, per questo una stringa). */
@Serializable
data class CollectionGridDestination(val kind: String)

/** I Nascosti: la credenziale a ogni ingresso (E-H3). */
@Serializable
data object HiddenDestination

/** Gli Eliminati di recente: il cestino di sistema. */
@Serializable
data object RecentlyDeletedDestination

/** Apre la pagina di [kind] sul telefono (a schermo intero): la rotta giusta per ognuna delle sei raccolte. */
fun NavController.openCollection(kind: CollectionKind) {
  when (kind) {
    CollectionKind.HIDDEN -> navigate(HiddenDestination)
    CollectionKind.RECENTLY_DELETED -> navigate(RecentlyDeletedDestination)
    else -> navigate(CollectionGridDestination(kind.code))
  }
}

/**
 * Le pagine delle Raccolte nel grafo di navigazione: le quattro griglie filtrate, i Nascosti e gli Eliminati di recente.
 *
 * Il viewer non e' una rotta (D12): [onOpenViewer] manda la richiesta a `:app`, che lo ospita sopra la pagina corrente
 * con lo stesso host della Libreria. Per i Nascosti la richiesta ha `secure = true`.
 */
fun NavGraphBuilder.collectionsGraph(nav: NavController, onOpenViewer: (ViewerRequest) -> Unit) {
  val back: () -> Unit = { nav.popBackStack() }

  composable<CollectionGridDestination>(backSwipe = FluidBackSwipe.EdgeOnly) { entry ->
    val kind = CollectionKind.fromCode(entry.toRoute<CollectionGridDestination>().kind)?.takeIf { it.isFilter }
    if (kind == null) {
      // Una rotta salvata da una versione piu' nuova (o un codice sbagliato): meglio tornare indietro che una pagina vuota.
      UnknownCollection(back)
    } else {
      CollectionGridRoute(kind = kind, onOpenViewer = onOpenViewer, onBack = back)
    }
  }
  composable<HiddenDestination>(backSwipe = FluidBackSwipe.EdgeOnly) { HiddenRoute(onOpenViewer = onOpenViewer, onBack = back) }
  composable<RecentlyDeletedDestination>(backSwipe = FluidBackSwipe.EdgeOnly) { RecentlyDeletedRoute(onBack = back) }
}

@Composable
private fun UnknownCollection(back: () -> Unit) {
  LaunchedEffect(Unit) { back() }
}
