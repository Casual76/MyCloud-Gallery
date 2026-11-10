package dev.pampa.mycloudgallery.feature.albums.nav

import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumActions
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorRoute
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListRoute
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageExits
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageRoute
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionKind
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsRoute
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumEntryTarget
import dev.pampa.mycloudgallery.feature.albums.trips.TripsRoute
import kotlinx.serialization.Serializable

// LE ROTTE DEGLI ALBUM (Fase 7, P10): un grafo da agganciare, come `utilityGraph` e `collectionsGraph`. **Non** tocca `MainScaffold` ne' `AppNavHost`
// (sono di P13): P13 chiama [albumsGraph] dentro il `NavHost` e apre le rotte con le funzioni `open...` di sotto. Il viewer non e' una rotta (D12):
// [AlbumActions.openViewer] manda la richiesta a `:app`, che lo ospita sopra la pagina corrente. "Aggiungi ad album" non e' una rotta: i media da
// aggiungere possono essere migliaia, e `:app` mostra `AddToAlbumHost` sopra la schermata che l'ha chiesto.

/** "Tutti gli album" (e la sezione Album oltre i primi 6). */
@Serializable
data object AlbumListDestination

/** La pagina di un album. [id] e' l'`AlbumId` come testo (stabile, mai un titolo). */
@Serializable
data class AlbumPageDestination(val id: String)

/** "Suggerite" ([toShare] falso) o "Da condividere" ([toShare] vero) di un album. */
@Serializable
data class SuggestionsDestination(val id: String, val toShare: Boolean = false)

/**
 * L'editor delle regole: [title] per un album smart nuovo (parte dal nome), [album] per cambiare le regole di uno che c'e'. Fase 8: [person] (con [title]) e' l'id della
 * persona in base32 da cui parte la regola delle persone ("Crea album smart" dalla pagina persona): l'editor non interpreta il nome e parte da quella persona.
 */
@Serializable
data class SmartEditorDestination(val title: String? = null, val album: String? = null, val person: String? = null)

/** Le proposte di viaggio. */
@Serializable
data object TripsDestination

fun NavController.openAlbums() = navigate(AlbumListDestination)

fun NavController.openAlbum(id: AlbumId) {
  // Avvio istantaneo: l'apertura della pagina di un album si misura dal tocco al primo fotogramma (scheletro o album).
  AvvioTrace.pageOpened(AvvioTrace.PAGE_ALBUM)
  navigate(AlbumPageDestination(id.value))
}

fun NavController.openSuggestions(id: AlbumId, kind: SuggestionKind) = navigate(SuggestionsDestination(id.value, toShare = kind == SuggestionKind.TO_SHARE))

/** Crea un album smart dal nome [title]: l'editor interpreta il nome e propone le regole. */
fun NavController.openSmartEditor(title: String) = navigate(SmartEditorDestination(title = title))

/**
 * Fase 8: "Crea album smart" dalla pagina di una persona: il nome [title] (quello della persona) e la regola con [person]. Lo chiama `:app` (P13) dalla pagina persona,
 * che sta in un altro feature: i feature non si conoscono, il ponte e' questa funzione.
 */
fun NavController.openSmartEditorForPerson(title: String, person: PersonId) = navigate(SmartEditorDestination(title = title, person = person.base32()))

/** Cambia le regole dello smart [id]. */
fun NavController.openRules(id: AlbumId) = navigate(SmartEditorDestination(album = id.value))

fun NavController.openTrips() = navigate(TripsDestination)

/**
 * L'editor delle regole ha salvato lo smart [id]: l'editor esce dalla pila e si e' sulla pagina di quell'album. AS-8: in modifica la pagina dell'album e'
 * gia' sotto l'editor (ci si e' arrivati da li'), quindi basta toglierlo; aprirne una seconda lasciava due pagine dello stesso album e chiedeva due "indietro".
 * In creazione sotto c'e' l'elenco (o le Raccolte): si apre la pagina nuova.
 */
fun NavController.afterRulesSaved(id: AlbumId) {
  popBackStack()
  val below = currentBackStackEntry?.takeIf { it.destination.hasRoute<AlbumPageDestination>() }?.toRoute<AlbumPageDestination>()
  if (below?.id != id.value) openAlbum(id)
}

/**
 * Apre la destinazione di una voce delle sezioni delle Raccolte ([AlbumEntryTarget.parse]). `NewAlbum` porta all'elenco "Tutti gli album", dove c'e' il
 * foglio "Nuovo album" che passa da `ManualAlbumCreator`: l'ospite a parte di `:app` che apriva quel foglio da qui e' stato tolto (OA-20 della review
 * generale), e la sezione Album delle Raccolte, la sola che produceva quella voce, non si mostra piu' dalla scheda Album.
 */
fun NavController.openEntry(target: AlbumEntryTarget) {
  when (target) {
    is AlbumEntryTarget.Album -> openAlbum(target.id)
    is AlbumEntryTarget.SmartAlbum -> openAlbum(target.id)
    AlbumEntryTarget.AllAlbums -> openAlbums()
    is AlbumEntryTarget.Trip -> openTrips()
    AlbumEntryTarget.NewAlbum -> openAlbums()
  }
}

/**
 * Le pagine degli album nel grafo di navigazione: l'elenco, la pagina di un album, "Suggerite" e "Da condividere", l'editor delle regole e le proposte di
 * viaggio. [actions] sono le uscite verso `:app` (viewer, selettore dei media, Diagnostica); [thumbnails] le miniature locali della Libreria.
 */
fun NavGraphBuilder.albumsGraph(nav: NavController, actions: AlbumActions, thumbnails: AlbumThumbnails) {
  val back: () -> Unit = { nav.popBackStack() }

  composable<AlbumListDestination> {
    AlbumListRoute(
      thumbnails = thumbnails,
      onOpenAlbum = nav::openAlbum,
      onNewSmart = nav::openSmartEditor,
      onBack = back,
    )
  }
  composable<AlbumPageDestination> {
    AlbumPageRoute(
      actions = actions,
      thumbnails = thumbnails,
      exits = AlbumPageExits(
        onBack = back,
        onBackToList = back,
        onOpenAlbum = nav::openAlbum,
        onOpenRules = nav::openRules,
        onOpenSuggestions = nav::openSuggestions,
      ),
    )
  }
  composable<SuggestionsDestination> { SuggestionsRoute(actions = actions, thumbnails = thumbnails, onBack = back) }
  composable<SmartEditorDestination> {
    SmartEditorRoute(
      actions = actions,
      thumbnails = thumbnails,
      onBack = back,
      // Salvato: si va alla pagina dell'album e l'editor esce dalla pila (indietro non riporta alle regole).
      onSaved = nav::afterRulesSaved,
    )
  }
  composable<TripsDestination> { TripsRoute(thumbnails = thumbnails, onBack = back, onOpenAlbum = nav::openAlbum) }
}
