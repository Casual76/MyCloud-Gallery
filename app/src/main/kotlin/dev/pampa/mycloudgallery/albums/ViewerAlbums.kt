package dev.pampa.mycloudgallery.albums

import androidx.compose.runtime.mutableStateMapOf
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.albums.contract.AddToAlbumRequest
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPolicy
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumRefs
import dev.pampa.mycloudgallery.feature.viewer.menu.AlbumMenuResult
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAlbumActions
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAlbumMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// GLI ALBUM FUORI DALLE LORO SCHERMATE (Fase 7, P13; P10-10, D35): le richieste "Aggiungi ad album" (selezione della Libreria, viewer, Nascosti), le
// tre voci ⋮ del viewer, il viewer aperto dalla pagina di un album e le miniature delle schermate degli album. Il viewer e la Libreria non vedono
// core-albums: qui `:app` traduce.

/**
 * Le richieste "Aggiungi ad album" in attesa di essere mostrate: una per volta (`AddToAlbumHost` sopra la schermata che l'ha chiesta). Non e' una
 * rotta perche' i media possono essere migliaia (P10). Una richiesta vuota non nasce.
 */
class AlbumRequests {
  private val pending = MutableStateFlow<AddToAlbumRequest?>(null)

  val addToAlbum: StateFlow<AddToAlbumRequest?> = pending.asStateFlow()

  /** Chi aspetta la fine di una richiesta (la condivisione in arrivo verso un album condiviso: la promessa si chiude quando l'utente ha deciso). */
  @Volatile
  var onDone: (AddToAlbumRequest) -> Unit = {}

  /**
   * "Aggiungi ad album" per [media]; [fromHidden] solo dai Nascosti (li' l'album riceve anche il media nascosto, ed e' una richiesta esplicita); [album]
   * gia' scelto (la condivisione in arrivo). Restituisce la richiesta, o null se non c'era niente da chiedere.
   */
  fun add(media: Collection<MediaId>, fromHidden: Boolean = false, album: AlbumId? = null): AddToAlbumRequest? {
    if (media.isEmpty()) return null
    return AddToAlbumRequest(media.distinct(), fromHidden, album).also { pending.value = it }
  }

  /** La richiesta [request] e' finita (fatta o lasciata): se ne e' arrivata un'altra nel frattempo, resta quella. */
  fun done(request: AddToAlbumRequest) {
    pending.update { if (it === request) null else it }
    onDone(request)
  }
}

/**
 * Le voci ⋮ del viewer per gli album (P10-10). Fuori da un album ([album] null) solo "Aggiungi ad album"; aperto dalla pagina di un album anche
 * "Imposta come copertina" e "Rimuovi dall'album" ("Togli" in uno smart, E-AL5) **se la tabella dei permessi li da'** (`AlbumPolicy.itemActions`,
 * la stessa della pagina): la risposta si calcola alla prima richiesta e il menu la legge da uno stato osservabile (nessun lavoro nella composizione).
 */
class AppViewerAlbumActions(
  private val albums: () -> Albums,
  private val shared: () -> SharedAlbums,
  private val requests: AlbumRequests,
  private val scope: CoroutineScope,
  private val album: AlbumId? = null,
  private val fromHidden: Boolean = false,
) : ViewerAlbumActions {
  private val decided = mutableStateMapOf<MediaId, ViewerAlbumMenu>()
  private val asked = HashSet<MediaId>()

  override fun menu(id: MediaId): ViewerAlbumMenu {
    val outside = ViewerAlbumMenu(addToAlbum = true, removeFromAlbum = false, setAsCover = false)
    val inside = album ?: return outside
    decided[id]?.let { return it }
    val first = synchronized(asked) { asked.add(id) }
    if (first) scope.launch { decide(inside, id)?.let { decided[id] = it } }
    return outside
  }

  private suspend fun decide(inside: AlbumId, id: MediaId): ViewerAlbumMenu? = try {
    val current = albums().all.value.albums.firstOrNull { it.id == inside }
    if (current == null) {
      null
    } else {
      val albumOps = if (current is Album.Shared) shared().allowedOps(inside) else emptySet()
      val itemOps = if (current is Album.Shared) shared().allowedOps(inside, id) else emptySet()
      val actions = AlbumPolicy.itemActions(current, albumOps, itemOps)
      ViewerAlbumMenu(
        addToAlbum = true,
        removeFromAlbum = actions.removeFromAlbum,
        setAsCover = actions.setAsCover,
        removeIsExclude = current is Album.Smart && !current.frozen,
      )
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.w(TAG) { "voci del viewer: ${e.javaClass.simpleName}" }
    null
  }

  override fun addToAlbum(id: MediaId) {
    requests.add(listOf(id), fromHidden)
  }

  override suspend fun removeFromAlbum(id: MediaId): AlbumMenuResult {
    val inside = album ?: return AlbumMenuResult.NOT_ALLOWED
    return resultOf { albums().remove(inside, listOf(id)) }.also { if (it == AlbumMenuResult.DONE) decided.remove(id) }
  }

  override suspend fun setAsCover(id: MediaId): AlbumMenuResult {
    val inside = album ?: return AlbumMenuResult.NOT_ALLOWED
    return resultOf { albums().setCover(inside, id) }
  }

  private suspend fun resultOf(action: suspend () -> EditOutcome): AlbumMenuResult = try {
    when (action()) {
      EditOutcome.Done -> AlbumMenuResult.DONE
      is EditOutcome.NotAllowed -> AlbumMenuResult.NOT_ALLOWED
      else -> AlbumMenuResult.FAILED
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    AlbumMenuResult.FAILED
  }

  /** Lo stesso, per il viewer aperto dalla pagina di [id] o dai Nascosti. */
  fun within(id: AlbumId?, hidden: Boolean = fromHidden): AppViewerAlbumActions = AppViewerAlbumActions(albums, shared, requests, scope, id, hidden)

  private companion object {
    const val TAG = "ViewerAlbums"
  }
}

/** Le miniature delle schermate degli album (P10-8): solo quelle gia' sul dispositivo, dall'indice della Libreria visibile (niente nascosti, E-H1). */
class LibraryAlbumRefs(private val snapshot: () -> TimelineSnapshot?) : AlbumRefs {
  override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef? = (snapshot as? ColumnarTimelineSnapshot)?.thumbRefAt(index)

  override fun request(media: MediaId): ThumbRequest? {
    val current = snapshot() as? ColumnarTimelineSnapshot ?: return null
    val index = current.indexOf(media).takeIf { it >= 0 } ?: return null
    val ref = current.thumbRefAt(index) ?: return null
    return ThumbRequest(ThumbKey(media, current.versionKeyAt(index), ThumbBucket.M), ref)
  }
}

/**
 * Gli elementi del viewer aperto dalla pagina di un album: l'ordine dell'album, **vivo** come in Cerca (E-Q13, D22): quando l'indice della Libreria
 * visibile cambia (un "Nascondi" dal viewer stesso, un'eliminazione altrove) la vista si rifa' sull'indice nuovo con gli stessi id, e chi non c'e'
 * piu' esce. I byte e i riferimenti vengono dalla Libreria ([refs]).
 */
class AlbumViewerItems(
  start: OrderedTimelineView,
  snapshots: StateFlow<TimelineSnapshot?>,
  private val refs: ViewerItems,
  scope: CoroutineScope,
) : ViewerItems {
  private val ids: List<MediaId> = List(start.size) { start.snapshot.idAt(start.snapshotIndex(it)) }

  override val view: StateFlow<TimelineView> = snapshots.filterNotNull()
    .map { snapshot -> if (snapshot === start.snapshot) start else IndexOrderedTimelineView.of(snapshot, ids) }
    .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), start)

  override fun ref(id: MediaId): MediaRef = refs.ref(id)

  override fun refOrNull(id: MediaId): MediaRef? = refs.refOrNull(id)

  private companion object {
    /** Dopo l'ultimo osservatore (il viewer chiuso) il flusso aspetta un attimo, per una rotazione, poi smette. */
    const val STOP_AFTER_MS: Long = 5_000
  }
}

/** Il viewer aperto da un album non ha una griglia della Libreria sotto: niente bordi da cui partire, la bitmap si ricarica. */
object AlbumViewerAnchor : ViewerAnchor {
  override fun boundsOf(id: MediaId): PixelRect? = null

  override suspend fun reveal(id: MediaId) = Unit

  override fun thumbKey(id: MediaId): ThumbKey = ThumbKey(id, 0L, ThumbBucket.M)
}
