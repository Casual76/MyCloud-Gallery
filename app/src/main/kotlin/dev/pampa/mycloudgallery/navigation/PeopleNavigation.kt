package dev.pampa.mycloudgallery.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.albums.AlbumViewerAnchor
import dev.pampa.mycloudgallery.albums.AlbumViewerItems
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.faces.FacesCropsFacade
import dev.pampa.mycloudgallery.faces.PeopleOpener
import dev.pampa.mycloudgallery.feature.albums.nav.openSmartEditor
import dev.pampa.mycloudgallery.feature.albums.nav.openSmartEditorForPerson
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.people.activation.FacesActivationRoute
import dev.pampa.mycloudgallery.feature.people.activation.FacesNoticeRoute
import dev.pampa.mycloudgallery.feature.people.activation.PeopleSettingsRoute
import dev.pampa.mycloudgallery.feature.people.contract.PeopleActions
import dev.pampa.mycloudgallery.feature.people.contract.PeopleRefs
import dev.pampa.mycloudgallery.feature.people.contract.PeopleThumbnails
import dev.pampa.mycloudgallery.feature.people.erase.FacesEraseRoute
import dev.pampa.mycloudgallery.feature.people.nav.openHiddenPeople
import dev.pampa.mycloudgallery.feature.people.nav.openOthers
import dev.pampa.mycloudgallery.feature.people.nav.openPeople
import dev.pampa.mycloudgallery.feature.people.nav.openPerson
import dev.pampa.mycloudgallery.feature.people.nav.peopleGraph
import dev.pampa.mycloudgallery.feature.settings.SettingsAboutRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsDevicesRoute
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.main.ViewerHostState
import javax.inject.Inject
import kotlinx.serialization.Serializable

// LE PERSONE NELLA NAVIGAZIONE DELL'APP (Fase 8, P13; P9: "peopleGraph(nav, actions, thumbnails, crops)", P7: "FacesActivationRoute, FacesNoticeRoute,
// PeopleSettingsRoute", P8: "FacesEraseRoute"). feature-people, feature-settings, feature-albums, feature-library e feature-viewer non si conoscono:
// le uscite di una verso le altre le scrive questo file.

/** La pagina di attivazione di Persone (il testo del §2.1 e il consenso). */
@Serializable
data object FacesActivationDestination

/** L'avviso una tantum sugli altri dispositivi dell'account. */
@Serializable
data object FacesNoticeDestination

/** Impostazioni -> Persone. */
@Serializable
data object PeopleSettingsDestination

/** Il foglio "Disattiva e cancella" e la sua pagina d'avanzamento. */
@Serializable
data object FacesEraseDestination

/**
 * Cio' che le schermate delle persone ricevono dalla galleria, uno per l'activity: le miniature locali (dall'indice della Libreria visibile, mai un nascosto),
 * i ritagli dei volti (mai su disco), il viewer aperto da una persona (vivo sull'indice visibile: un nascosto esce anche dal viewer aperto, E-H1) e
 * l'interruttore per l'avviso.
 */
@HiltViewModel
class PeopleHostViewModel @Inject constructor(
  private val gallery: GalleryGraph,
  val crops: FacesCropsFacade,
  val opener: PeopleOpener,
  val switch: FacesSwitch,
) : ViewModel() {

  /** Solo cio' che e' gia' sul dispositivo: le miniature della Libreria, senza nascosti. */
  val thumbnails: PeopleThumbnails = PeopleThumbnails(
    gallery.thumbnails,
    gallery.placeholders,
    object : PeopleRefs {
      override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef? = (snapshot as? ColumnarTimelineSnapshot)?.thumbRefAt(index)
    },
  )

  /** Il viewer sugli elementi di una persona, nell'ordine della vista e vivo. */
  fun viewerRequest(view: OrderedTimelineView, start: MediaId): ViewerRequest = ViewerRequest(
    items = AlbumViewerItems(view, gallery.visibleTimeline.snapshot, TimelineViewerItems(gallery.repository, LibraryFilter.ALL), viewModelScope),
    start = start,
    anchor = AlbumViewerAnchor,
    secure = false,
  )
}

/** Le uscite delle schermate delle persone verso `:app`: il viewer sopra la pagina, le altre pagine, l'editor degli album smart, la Diagnostica. */
class AppPeopleActions(
  private val nav: NavController,
  private val viewer: ViewerHostState?,
  private val host: PeopleHostViewModel,
  private val services: () -> ViewerServices?,
) : PeopleActions {
  override fun openViewer(view: OrderedTimelineView, start: MediaId) {
    val base = services() ?: return
    // Le voci ⋮ del viewer aperto da una persona sono quelle di sempre ("Aggiungi ad album"): nessun album di contesto.
    viewer?.open(host.viewerRequest(view, start), base)
  }

  override fun openPerson(id: PersonId) = nav.openPerson(id)

  override fun openAllPeople() = nav.openPeople()

  override fun openOthers() = nav.openOthers()

  /** Con l'avviso da vedere si apre l'avviso (una scelta sola, senza requisiti); altrimenti la pagina di attivazione. */
  override fun openActivation() {
    if (host.switch.currentState() is FacesState.AwaitingNotice) nav.navigate(FacesNoticeDestination) else nav.navigate(FacesActivationDestination)
  }

  override fun openPeopleSettings() = nav.navigate(PeopleSettingsDestination)

  override fun openDiagnostics() = nav.navigate(DiagnosticsGraphRoute)

  override fun openHiddenPeople() = nav.openHiddenPeople()

  /** "Crea album smart" dalla pagina persona: l'editor parte con la regola gia' messa e il nome come titolo (senza nome un titolo neutro). */
  override fun createSmartAlbum(person: PersonId, title: String?) {
    val name = title?.takeIf { it.isNotBlank() }
    if (name != null) nav.openSmartEditorForPerson(name, person) else nav.openSmartEditor("")
  }
}

/** Le pagine delle persone nel grafo: "Tutte le persone", "Altri volti", la pagina di una persona, la revisione, le nascoste; attivazione, avviso, Impostazioni, cancellazione. */
fun NavGraphBuilder.peopleScreens(nav: NavController, actions: PeopleActions, host: PeopleHostViewModel) {
  peopleGraph(nav, actions, host.thumbnails, host.crops)
  composable<FacesActivationDestination> {
    FacesActivationRoute(onClose = { nav.popBackStack() }, onOpenLicenses = { nav.navigate(SettingsAboutRoute) })
  }
  composable<FacesNoticeDestination> {
    FacesNoticeRoute(
      onClose = { nav.popBackStack() },
      onRead = {
        nav.popBackStack()
        nav.navigate(FacesActivationDestination)
      },
    )
  }
  composable<PeopleSettingsDestination> {
    PeopleSettingsRoute(
      onOpenActivation = { nav.navigate(FacesActivationDestination) },
      onOpenNotice = { nav.navigate(FacesNoticeDestination) },
      onOpenLicenses = { nav.navigate(SettingsAboutRoute) },
      onBack = { nav.popBackStack() },
      onOpenHidden = { nav.openHiddenPeople() },
      onOpenDiagnostics = { nav.navigate(DiagnosticsGraphRoute) },
      onErase = { nav.navigate(FacesEraseDestination) },
    )
  }
  // CE-5: "Apri Dispositivi" del foglio porta a Impostazioni, Dispositivi (la stessa rotta di `onOpenDevices` della revisione F della Fase 7), dove si revoca un
  // dispositivo dismesso che tiene ferma la fine (L193).
  composable<FacesEraseDestination> { FacesEraseRoute(onClose = { nav.popBackStack() }, onOpenDevices = { nav.navigate(SettingsDevicesRoute) }) }
}

/**
 * L'avviso una tantum (D4, E-F7): quando Persone e' stata accesa da un altro dispositivo dell'account e qui l'avviso non e' ancora stato visto, alla
 * prima apertura dell'app si propone una volta sola per questo processo (la riga delle Raccolte e Impostazioni lo propongono sempre). Prima, nessun dato
 * dei volti e' stato scaricato, calcolato o mostrato qui.
 */
@Composable
internal fun PeopleNoticeEffect(nav: NavController, host: PeopleHostViewModel) {
  val state by host.switch.state.collectAsStateWithLifecycle()
  var proposed by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(state is FacesState.AwaitingNotice) {
    if (state is FacesState.AwaitingNotice && !proposed) {
      proposed = true
      nav.navigate(FacesNoticeDestination)
    }
  }
}

/** Apre la pagina di una persona dal viewer: chiude il viewer e porta alla pagina. */
internal fun PeopleOpener.bind(nav: NavController, viewer: ViewerHostState?) {
  onOpen = { id ->
    viewer?.close()
    nav.openPerson(id)
  }
}
