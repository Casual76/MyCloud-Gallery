package dev.pampa.mycloudgallery.feature.map.nav

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapActions
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails
import dev.pampa.mycloudgallery.feature.map.grid.PlaceGridRoute
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridRoute
import dev.pampa.mycloudgallery.feature.map.map.MapRoute
import dev.pampa.mycloudgallery.feature.map.places.PlacesEntryTarget
import dev.pampa.mycloudgallery.feature.map.privacy.MapPrivacyRoute
import kotlinx.serialization.Serializable

// LE ROTTE DELLA MAPPA (Fase 9, P4): un grafo da agganciare, come `peopleGraph`, `albumsGraph` e `memoriesGraph`. **Non** tocca `MainScaffold` ne' `AppNavHost` (sono di P13): P13 chiama [mapGraph] dentro il
// `NavHost`, apre le rotte con le funzioni `open...` di sotto, porta qui le voci della sezione Luoghi delle Raccolte ([PlacesEntryTarget.parse]) e "Mostra sulla mappa" del viewer ([openMapAt]). Il viewer non
// e' una rotta: [MapActions.openViewer] manda la richiesta a `:app`, che lo ospita sopra la pagina corrente. La mappa si apre a schermo pieno anche sul tablet (D30).

/** La mappa a schermo pieno. [focus] e' un `MediaId` come testo opaco ("Mostra sulla mappa" dal viewer, E-MAP19), o null. */
@Serializable
data class MapDestination(val focus: String? = null) {
  /** D33: mai l'id del media. */
  override fun toString(): String = "MapDestination"
}

/** La griglia di una zona: la cella della bolla (livello e prefisso della chiave di Morton), mai una coordinata chiara. */
@Serializable
data class ZoneDestination(val level: Int, val prefix: Long) {
  /** D33: mai la cella. */
  override fun toString(): String = "ZoneDestination"
}

/** La griglia di un luogo (id GeoNames, un id pubblico di un posto). */
@Serializable
data class PlaceDestination(val placeId: Int) {
  /** D33: mai il luogo. */
  override fun toString(): String = "PlaceDestination"
}

/** Impostazioni -> Mappa (interruttore, nota, "Solo sul Wi-Fi", "Zone senza Internet"). */
@Serializable
data object MapSettingsDestination

fun NavController.openMap() = navigate(MapDestination())

fun NavController.openMapAt(media: MediaId) = navigate(MapDestination(media.value))

fun NavController.openZone(key: CellKey) = navigate(ZoneDestination(key.level, key.prefix))

fun NavController.openPlace(placeId: Int) = navigate(PlaceDestination(placeId))

fun NavController.openMapSettings() = navigate(MapSettingsDestination)

/** Apre la destinazione di una voce della sezione Luoghi ([PlacesEntryTarget.parse]): la mappa, la griglia del luogo; le righe che dicono come stanno le cose non portano da nessuna parte. */
fun NavController.openPlacesEntry(target: PlacesEntryTarget) {
  when (target) {
    PlacesEntryTarget.OpenMap -> openMap()
    is PlacesEntryTarget.Place -> openPlace(target.placeId)
    PlacesEntryTarget.Inert -> Unit
  }
}

/**
 * Le pagine della mappa nel grafo di navigazione: la mappa, la griglia di una zona, la griglia di un luogo e Impostazioni -> Mappa. [actions] sono le uscite verso `:app` (viewer, zone, impostazioni, regioni);
 * [thumbnails] le miniature locali della Libreria. Le "Zone senza Internet" (pagina delle regioni) le aggiunge P5 al suo grafo.
 */
fun NavGraphBuilder.mapGraph(
  nav: NavController,
  actions: MapActions,
  thumbnails: MapThumbnails,
) {
  val back: () -> Unit = { nav.popBackStack() }

  composable<MapDestination> { MapRoute(actions = actions, thumbnails = thumbnails, onBack = back) }
  composable<ZoneDestination> { ZoneGridRoute(actions = actions, thumbnails = thumbnails, onBack = back) }
  composable<PlaceDestination> { PlaceGridRoute(actions = actions, thumbnails = thumbnails, onBack = back) }
  composable<MapSettingsDestination> { MapPrivacyRoute(actions = actions, onBack = back) }
}
