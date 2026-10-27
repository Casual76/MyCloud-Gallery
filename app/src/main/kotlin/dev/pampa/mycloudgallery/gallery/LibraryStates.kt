package dev.pampa.mycloudgallery.gallery

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.library.LibraryCommands
import dev.pampa.mycloudgallery.feature.library.library.LibraryDebugHooks
import dev.pampa.mycloudgallery.feature.library.library.LibraryShare
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

  /**
   * Apre il selettore di condivisione. Falso se Android lo rifiuta: nessuna app, o troppi URI per la transazione Binder
   * (`TransactionTooLargeException`, che `startActivity` rilancia come `RuntimeException`): la Libreria lo dice (I8).
   */
  fun startChooser(intent: Intent): Boolean

  /** Apre il dialogo di sistema del cestino. Falso se non e' partito (I8). */
  fun launchTrashRequest(sender: IntentSender): Boolean
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

  /** Oltre questo la Libreria avvisa senza aprire il selettore (I8): vedi [SHARE_LIMIT]. */
  override val shareLimit: Int get() = SHARE_LIMIT

  /** GA-8: solo i media con una copia sul telefono; quelli che sono solo sul NAS non hanno byte da condividere o da cestinare qui. */
  override fun onPhone(ids: Set<MediaId>): Set<MediaId> = ids.filterTo(HashSet()) { refOf(it) is MediaRef.Phone }

  override fun share(ids: Set<MediaId>): Boolean {
    // Difesa in profondita': la Libreria controlla il tetto prima, ma un chiamante diverso non deve poter aggirarlo.
    if (ids.size > SHARE_LIMIT) return false
    val refs = phoneRefs(ids)
    // GA-8: niente sul telefono = niente da condividere. Mai "fatto" senza averlo fatto: la Libreria dice com'e' andata.
    if (refs.isEmpty()) return false
    // Correzioni 5B (R4, MC-2): con "Rimuovi la posizione" acceso la selezione passa dalla stessa via del viewer (copie senza posizione,
    // ricontrollate; la scelta dell'app la lancia `ShareHostEffect`), mai dagli originali del MediaStore. Avanzamento e rifiuto: [shareProgress].
    val shares = gallery.shares
    if (shares != null && shares.removeLocation.value) {
      shares.library.start(ids.filter { refOf(it) is MediaRef.Phone })
      return true
    }
    val intent = gallery.intents.share(refs) ?: return false
    return host?.startChooser(intent) ?: false
  }

  // Senza il legame con le integrazioni (solo i test dei comandi) la Libreria non ha condivisioni da mostrare.
  override val shareProgress: StateFlow<LibraryShare> get() = gallery.shares?.libraryProgress ?: IDLE_SHARE

  override fun cancelShare() {
    gallery.shares?.library?.cancel()
  }

  override fun shareWithLocation() {
    gallery.shares?.library?.shareWithLocation()
  }

  override val canDeleteFromPhone: Boolean get() = gallery.intents.canTrash

  // Fase 5B (P14, D21): "Libera spazio" sulla selezione, se il backup c'e' e il sistema ha i dialoghi (Android 11+).
  override val canFreeSpace: Boolean get() = gallery.fiveB != null && gallery.intents.canTrash

  override fun freeSpace(ids: Set<MediaId>) {
    gallery.fiveB?.freeSpace?.invoke(ids)
  }

  // Fase 7 (P13, D35): "Aggiungi ad album" sulla selezione, con gli album (che vivono nei dati personali del NAS, D3): la scelta la mostra la radice.
  override val canAddToAlbum: Boolean get() = gallery.nas != null

  override fun addToAlbum(ids: Set<MediaId>) {
    gallery.albumRequests.add(ids)
  }

  /** Il tetto di una richiesta di cestino di sistema (Android 11+). */
  override val deleteLimit: Int get() = DELETE_LIMIT

  override fun deleteFromPhone(ids: Set<MediaId>): Boolean {
    if (ids.size > DELETE_LIMIT) return false
    val refs = phoneRefs(ids)
    if (refs.isEmpty()) return false
    val sender = gallery.intents.trashRequest(refs, trash = true) ?: return false
    return host?.launchTrashRequest(sender) ?: false
  }

  private fun phoneRefs(ids: Set<MediaId>): List<MediaRef.Phone> = ids.mapNotNull { refOf(it) as? MediaRef.Phone }

  companion object {
    /**
     * Il massimo di elementi per un "Condividi" (I8). Gli URI viaggiano tre volte nella stessa transazione Binder
     * (`EXTRA_STREAM`, `ClipData` e la copia che il selettore ne fa in `EXTRA_INTENT`): a circa 150 byte l'uno, 500 elementi
     * sono circa 225 KB, ben dentro il megabyte che il processo condivide fra tutte le transazioni; qualche migliaio no.
     */
    const val SHARE_LIMIT: Int = 500

    /** Il massimo di URI che `MediaStore.createTrashRequest` accetta (Android 11+): oltre, nessuna richiesta. */
    const val DELETE_LIMIT: Int = AndroidPhoneMediaIntents.MAX_TRASH_URIS

    private val IDLE_SHARE: StateFlow<LibraryShare> = MutableStateFlow(LibraryShare.Idle)
  }
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
  private val nasKit: DebugLibraryKit? = DeveloperTools.nasLibrary()

  /** I servizi del viewer per lo stato [state] (di norma quelli del grafo vero). */
  fun viewerServicesFor(state: LibraryTabState): ViewerServices = synchronized(services) { services[state] } ?: gallery.viewerServices

  override fun open(scope: CoroutineScope): StateFlow<LibraryTabState> {
    lateinit var real: LibraryTabState
    lateinit var flow: MutableStateFlow<LibraryTabState>
    var synthetic: DebugLibraryStack? = null
    var hooks: LibraryDebugHooks? = null
    hooks = kit?.let { k ->
      LibraryDebugHooks(sizes = listOf(PHONE) + k.sizes, current = PHONE) { size ->
        synthetic?.close()
        synthetic = null
        if (size != NAS_SYNTHETIC) nasKit?.dismiss()
        if (size == PHONE) {
          flow.value = real
        } else {
          val stack: DebugLibraryStack = if (size == NAS_SYNTHETIC && nasKit != null) {
            nasKit.open(context, gallery, checkNotNull(hooks))
          } else {
            SyntheticLibraryStack(context, gallery, k, size, checkNotNull(hooks))
          }
          synchronized(services) { services[stack.state] = stack.viewerServices }
          synthetic = stack
          flow.value = stack.state
        }
      }
    }
    real = realState(scope, hooks)
    flow = MutableStateFlow(real)
    // Solo debug (P11, M1): il NAS sintetico chiesto da Impostazioni → Sviluppo prende il posto della Libreria.
    val debugHooks = hooks
    if (nasKit != null && debugHooks != null) scope.launch { nasKit.requested.collect { if (it) debugHooks.select(NAS_SYNTHETIC) } }
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
      // Fase 4 (D18, P7): la copia del telefono se c'e', altrimenti quella del NAS (media solo-NAS, E-L4).
      refOf = { id -> gallery.repository.mediaRefOf(id) ?: throw NoSuchElementException("Nessuna copia leggibile adesso.") },
      level = prefs.level.value,
      style = prefs.style.value,
      refAt = { snapshot, index ->
        if (snapshot is ColumnarTimelineSnapshot) snapshot.thumbRefAt(index) else gallery.repository.mediaRefOf(snapshot.idAt(index))
      },
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
      // Fase 4 (§1.5): lo stato del NAS per il piede; senza NAS resta Idle (il piede non dice niente).
      nas = gallery.nas?.libraryStatus ?: MutableStateFlow(LibraryNasStatus.Idle),
      // Fase 5A (§1.5, D16): lo stato del backup per il piede (terza voce); senza backup resta Idle (il piede non dice niente).
      backup = gallery.backup?.libraryStatus ?: MutableStateFlow(LibraryBackupStatus.Idle),
    )
    synchronized(services) { services[state] = gallery.viewerServices }
    return state
  }

  private companion object {
    /** Nel menu di debug: la Libreria del telefono (le altre voci sono le dimensioni della sintetica). */
    const val PHONE = 0

    /** Il NAS sintetico (solo debug): non e' una voce del menu "Vista", lo si chiede da Impostazioni → Sviluppo. */
    const val NAS_SYNTHETIC = -1
  }
}
