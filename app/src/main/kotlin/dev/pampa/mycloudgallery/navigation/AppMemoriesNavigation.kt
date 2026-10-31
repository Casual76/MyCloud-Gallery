package dev.pampa.mycloudgallery.navigation

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.mycloudgallery.albums.AlbumViewerAnchor
import dev.pampa.mycloudgallery.albums.AlbumViewerItems
import dev.pampa.mycloudgallery.core.imaging.ImagingPipeline
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.albums.nav.openAlbum
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesActions
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesRefs
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerPreviewSource
import dev.pampa.mycloudgallery.feature.memories.nav.openAllMemories
import dev.pampa.mycloudgallery.feature.memories.nav.openMemory
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionStates
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.main.ViewerHostState
import dev.pampa.mycloudgallery.memories.AppNotificationAccess
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import dev.pampa.mycloudgallery.notify.OpenMemoriesRequests
import dev.pampa.mycloudgallery.notify.ServeOpenMemoriesRequests
import dev.pampa.mycloudgallery.window.ServesEntryRequests
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// I RICORDI NELLA NAVIGAZIONE DELL'APP (Fase 9, P13; "per P13" di P9 e P10): le uscite delle schermate dei ricordi verso il viewer, gli album, le pagine di
// Android (permesso, canale) e il selettore della canzone propria; il tocco sulla notifica servito dopo il blocco app. feature-memories, feature-albums e
// feature-viewer non si conoscono: le uscite di uno verso gli altri le scrive questo file.

/**
 * Cio' che le schermate dei ricordi ricevono dalla galleria, uno per l'activity: le miniature della Libreria (mai un nascosto: le schermate passano dal filtro
 * finale), l'anteprima **locale** per la presentazione (solo i file del telefono: mai un'attesa sul NAS, E-MEM14) e il viewer aperto da un ricordo (vivo
 * sull'istantanea visibile: un nascosto esce anche dal viewer aperto, E-H1).
 */
@HiltViewModel
class MemoriesHostViewModel @Inject constructor(
  @ApplicationContext context: Context,
  private val gallery: GalleryGraph,
  val memories: MemoriesGraph,
  val access: AppNotificationAccess,
  val requests: OpenMemoriesRequests,
) : ViewModel() {

  // Pigri: questo ViewModel nasce con la schermata principale, e niente qui deve leggere file al primo fotogramma.
  private val phone by lazy { ImagingPipeline.thumbnailSource(context.applicationContext) }

  val thumbnails: MemoriesThumbnails = MemoriesThumbnails(
    gallery.thumbnails,
    gallery.placeholders,
    object : MemoriesRefs {
      override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef? = (snapshot as? ColumnarTimelineSnapshot)?.thumbRefAt(index)
    },
    preview = PlayerPreviewSource { ref, side -> localPreview(ref, side) },
  )

  private suspend fun localPreview(ref: MediaRef, side: Int): ImageBitmap? {
    if (ref !is MediaRef.Phone) return null
    return try {
      phone.load(ref, side, android.os.CancellationSignal())?.asImageBitmap()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
  }

  /** Il viewer sugli elementi di un ricordo, nell'ordine della vista e vivo. */
  fun viewerRequest(view: OrderedTimelineView, start: MediaId): ViewerRequest = ViewerRequest(
    items = AlbumViewerItems(view, gallery.visibleTimeline.snapshot, TimelineViewerItems(gallery.repository, LibraryFilter.ALL), viewModelScope),
    start = start,
    anchor = AlbumViewerAnchor,
    secure = false,
  )

  /**
   * Q3: la canzone scelta con il selettore di sistema (il permesso persistito e' gia' stato preso). Se la scelta non si salva, [onFailed] (MU-3: la pagina lo dice). Salvando,
   * il permesso della canzone di prima si rilascia (I4, `PermissionReleasingMusicSettings`).
   */
  fun setOwnSong(uri: Uri, onFailed: () -> Unit) {
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
      try {
        memories.musicSettings.set(MusicChoice.OwnSong(uri.toString()))
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        onFailed()
      }
    }
  }

  /** La voce `m_<16 caratteri>` della sezione Ricordi: il ricordo dello scaffale di adesso (null se e' uscito mentre si toccava). */
  fun resolveEntry(code: String): MemoryId? =
    memories.memories.shelf.value.items.firstOrNull { MemoriesSectionStates.codeOf(it.id) == code }?.id

  /** D17: il ricordo della notifica in tendina (null se non c'e' piu'). */
  suspend fun notifiedMemory(): MemoryId? = runCatching { memories.notifier.openTarget() }.getOrNull()
}

/** Le uscite delle schermate dei ricordi verso `:app`. */
class AppMemoriesActions(
  private val nav: NavController,
  private val viewer: ViewerHostState?,
  private val host: MemoriesHostViewModel,
  private val services: () -> ViewerServices?,
  private val askPermission: () -> Unit,
  private val openChannel: () -> Unit,
  private val pickSong: (onFailed: () -> Unit) -> Unit,
) : MemoriesActions {
  override fun openViewer(view: OrderedTimelineView, start: MediaId) {
    val base = services() ?: return
    viewer?.open(host.viewerRequest(view, start), base)
  }

  override fun openMemory(id: MemoryId) = nav.openMemory(id)

  override fun openAllMemories() = nav.openAllMemories()

  override fun openAlbum(album: AlbumId) = nav.openAlbum(album)

  override fun requestNotificationPermission() = askPermission()

  override fun openNotificationChannel() = openChannel()

  override fun pickOwnSong(onFailed: () -> Unit) = pickSong(onFailed)
}

/**
 * Le azioni dei ricordi legate all'activity: il dialogo del permesso (**solo da un tocco**, D18), le pagine di Android, il selettore di sistema della canzone
 * propria (`ACTION_OPEN_DOCUMENT` sui file audio, Q3) con il permesso di lettura **persistito** (il file resta sul telefono, mai copiato).
 */
@Composable
internal fun rememberMemoriesActions(nav: NavController, viewer: ViewerHostState?, host: MemoriesHostViewModel, services: () -> ViewerServices?): AppMemoriesActions {
  val context = LocalContext.current
  val activity = remember(context) { context.findActivityOrNull() }
  SideEffect { host.access.explain = { permission -> activity?.shouldShowRequestPermissionRationale(permission) } }
  val holder = remember { arrayOfNulls<dev.pampa.mycloudgallery.memories.MemoriesPermissionActions>(1) }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { holder[0]?.onSystemAnswer() }
  // MU-3: chi ha chiesto la canzone sa dire se la scelta non si e' conservata (il fornitore non concede il permesso persistente): lo tiene qui fino alla risposta.
  val songFailure = remember { arrayOfNulls<() -> Unit>(1) }
  val song = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    val onFailed = songFailure[0] ?: {}
    songFailure[0] = null
    if (uri != null) {
      val kept = runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
      if (kept) host.setOwnSong(uri, onFailed) else onFailed()
    }
  }
  val flow = remember(host) {
    host.access.actions(
      askSystem = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
      open = { intent -> runCatching { context.startActivity(intent) } },
    ).also { holder[0] = it }
  }
  return remember(nav, viewer, host, flow) {
    AppMemoriesActions(
      nav, viewer, host, services,
      askPermission = flow::requestNotificationPermission,
      openChannel = flow::openNotificationChannel,
      pickSong = { onFailed ->
        songFailure[0] = onFailed
        try {
          song.launch(arrayOf("audio/*"))
        } catch (_: ActivityNotFoundException) {
          // Nessun selettore di documenti sul telefono: la scelta resta com'era.
          songFailure[0] = null
        }
      },
    )
  }
}

/**
 * MO-3 (31/10): il giro "di oggi" dei ricordi anche quando l'app **torna in primo piano con Raccolte gia' aperta** (l'app viva da giorni) o e' ripristinata su Raccolte
 * dopo la morte del processo: `onSelectTab` lo chiama solo al cambio di scheda, e in quei casi la scheda e' gia' quella. A ogni ripresa con Raccolte aperta; `OPEN` non rifa'
 * niente se oggi il giro c'e' gia' stato, quindi chiamarlo spesso costa un controllo. Mai in un lavoro in background: qui c'e' solo la schermata.
 */
@Composable
internal fun MemoriesForegroundRound(host: MemoriesHostViewModel, collectionsOpen: Boolean) {
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    if (collectionsOpen) host.memories.onCollectionsOpened()
  }
}

/** D17: il tocco sulla notifica, servito **dopo il blocco app** (questa composizione esiste solo a app sbloccata): il ricordo di oggi, o "Tutti i ricordi". */
@Composable
internal fun MemoriesNotificationEffect(nav: NavController, host: MemoriesHostViewModel) {
  val scope = rememberCoroutineScope()
  // Fase 10 (P4, D16): con due finestre il tocco sulla notifica lo serve la sola finestra principale (l'evento e' del processo: lo prenderebbe la prima che arriva).
  ServesEntryRequests {
    ServeOpenMemoriesRequests(host.requests) {
      scope.launch {
        val id = host.notifiedMemory()
        if (id != null) nav.openMemory(id) else nav.openAllMemories()
      }
    }
  }
}

private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivityOrNull()
  else -> null
}
