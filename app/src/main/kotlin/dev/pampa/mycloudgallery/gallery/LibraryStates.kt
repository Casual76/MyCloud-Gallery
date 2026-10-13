package dev.pampa.mycloudgallery.gallery

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.library.LibraryCommands
import dev.pampa.mycloudgallery.feature.library.library.LibraryDebugHooks
import dev.pampa.mycloudgallery.feature.library.library.LibraryStateSource
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Cio' che i comandi della Libreria chiedono all'activity: i dialoghi dei permessi, la condivisione, il dialogo di
 * sistema del cestino. Lo registra la schermata (`LibraryCommandHostEffect`) finche' e' visibile; senza, i comandi non
 * fanno niente (mai una richiesta senza un tocco dell'utente e senza un'activity).
 */
interface LibraryCommandHost {
  fun requestMediaAccess()

  fun requestMediaLocation()

  fun startChooser(intent: Intent)

  fun launchTrashRequest(sender: IntentSender)
}

/**
 * I comandi della Libreria vera (§2.4): i permessi **partono solo da un tocco** ("Consenti accesso", "Consenti tutto",
 * "Consenti"), la condivisione e l'eliminazione passano dagli intent del MediaStore (`PhoneMediaIntents`, D14: cestino di
 * sistema con il suo dialogo; l'app non cancella mai da sola).
 */
class AppLibraryCommands(
  private val gallery: GalleryGraph,
  private val refOf: (MediaId) -> MediaRef?,
) : LibraryCommands {

  @Volatile
  var host: LibraryCommandHost? = null

  override fun requestMediaAccess() {
    host?.requestMediaAccess()
  }

  override fun requestMediaLocation() {
    host?.requestMediaLocation()
  }

  override fun share(ids: Set<MediaId>) {
    val refs = phoneRefs(ids)
    if (refs.isEmpty()) return
    gallery.intents.share(refs)?.let { intent -> host?.startChooser(intent) }
  }

  override val canDeleteFromPhone: Boolean get() = gallery.intents.canTrash

  override fun deleteFromPhone(ids: Set<MediaId>) {
    val refs = phoneRefs(ids)
    if (refs.isEmpty()) return
    gallery.intents.trashRequest(refs, trash = true)?.let { sender -> host?.launchTrashRequest(sender) }
  }

  private fun phoneRefs(ids: Set<MediaId>): List<MediaRef.Phone> = ids.mapNotNull { refOf(it) as? MediaRef.Phone }
}

/**
 * Lo stato della Libreria per `LibraryViewModel` (P11): la Libreria vera sopra il grafo della galleria e, **solo in
 * debug**, la Libreria sintetica con il suo database separato, scelta dal menu "Vista" (nota di P7).
 *
 * Tiene accanto a ogni stato i [ViewerServices] che gli corrispondono: il viewer della Libreria sintetica non deve avere
 * intent ne' dettagli del catalogo vero.
 */
@Singleton
class AppLibraryStates @Inject constructor(
  @ApplicationContext private val context: Context,
  private val gallery: GalleryGraph,
) : LibraryStateSource {

  private val services = WeakHashMap<LibraryTabState, ViewerServices>()
  private val kit: SyntheticLibraryKit? = DeveloperTools.syntheticKit()

  /** I servizi del viewer per lo stato [state] (di norma quelli del grafo vero). */
  fun viewerServicesFor(state: LibraryTabState): ViewerServices = synchronized(services) { services[state] } ?: gallery.viewerServices

  override fun open(scope: CoroutineScope): StateFlow<LibraryTabState> {
    lateinit var real: LibraryTabState
    lateinit var flow: MutableStateFlow<LibraryTabState>
    var synthetic: SyntheticLibraryStack? = null
    var hooks: LibraryDebugHooks? = null
    hooks = kit?.let { k ->
      LibraryDebugHooks(sizes = listOf(PHONE) + k.sizes, current = PHONE) { size ->
        synthetic?.close()
        synthetic = null
        if (size == PHONE) {
          flow.value = real
        } else {
          val stack = SyntheticLibraryStack(context, gallery, k, size, checkNotNull(hooks))
          synchronized(services) { services[stack.state] = stack.viewerServices }
          synthetic = stack
          flow.value = stack.state
        }
      }
    }
    real = realState(scope, hooks)
    flow = MutableStateFlow(real)
    scope.coroutineContext[Job]?.invokeOnCompletion { synthetic?.close() }
    return flow.asStateFlow()
  }
  private fun realState(scope: CoroutineScope, hooks: LibraryDebugHooks?): LibraryTabState {
    val timeline = gallery.visibleTimeline
    val prefs = gallery.preferences
    val grid = MediaGridState(
      view = timeline.view(prefs.filter.value).value,
      loader = gallery.thumbnails,
      placeholders = gallery.placeholders,
      placeNames = gallery.places,
      engine = gallery.layoutEngine,
      refOf = { id -> gallery.repository.refOf(id) ?: throw NoSuchElementException("Nessuna copia leggibile adesso.") },
      level = prefs.level.value,
      style = prefs.style.value,
    )
    // D11: la finestra visibile della griglia guida la fase META (prima i media sullo schermo).
    scope.launch { grid.visibleIds.collect { gallery.metaPriority.show(it) } }
    val state = LibraryTabState(
      grid = grid,
      viewerItems = grid.viewerItems,
      viewerImages = gallery.viewerImages,
      photoCount = 0,
      videoCount = 0,
      timeline = timeline,
      preferences = prefs,
      actions = gallery.actions,
      phone = gallery.libraryPhoneStatus,
      commands = AppLibraryCommands(gallery) { id -> gallery.repository.refOf(id) },
      debug = hooks,
    )
    synchronized(services) { services[state] = gallery.viewerServices }
    return state
  }

  private companion object {
    /** Nel menu di debug: la Libreria del telefono (le altre voci sono le dimensioni della sintetica). */
    const val PHONE = 0
  }
}
