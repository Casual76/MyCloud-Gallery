package dev.pampa.mycloudgallery.gallery

import android.content.Context
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.data.CatalogLocalMediaActions
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.library.LibraryCommands
import dev.pampa.mycloudgallery.feature.library.library.LibraryDebugHooks
import dev.pampa.mycloudgallery.feature.library.library.LibraryPhoneStatus
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Solo debug**: cio' che serve alla Libreria sintetica (docs/fase3-progetto.md §1.4, §5.2; nota di P7). L'implementazione
 * sta in `app/src/debug` (usa i fake dei package `testing`, che la release non porta); in release `DeveloperTools` non ne
 * da' nessuna e la voce nel menu non esiste.
 */
interface SyntheticLibraryKit {

  /** Le dimensioni che il menu "Vista" offre (2.000 per gli screenshot, 100.000 per il collaudo di M1). */
  val sizes: List<Int>

  /** I pixel del viewer per i riferimenti sintetici. */
  val viewerImages: ViewerImageSource

  /** I nomi dei luoghi del mini-dataset di prova (le intestazioni della Libreria sintetica hanno i luoghi). */
  val placeNames: PlaceNames

  /** La forma del database scritto da [seed]: se cambia, il database separato si riscrive da capo. */
  val version: Int

  /** Il seme dei pixel del media con questo `_ID` finto (lo stesso del suo segnaposto ThumbHash). */
  fun pixelSeed(mediaStoreId: Long): Long

  /** Riempie [database] (nuovo e vuoto) con [count] elementi della Libreria sintetica; [progress] riceve (fatti, totale). */
  suspend fun seed(database: GalleryDatabase, count: Int, progress: (Int, Int) -> Unit)
}

/**
 * **Solo debug**: la Libreria sintetica sopra il catalogo vero, in un **database separato** (`gallery-synthetic.db`, mai
 * `gallery.db`) con la sua istantanea (`noBackupFilesDir/replica-synthetic`). Indice, istantanea, layout, miniature con il
 * caricatore vero, preferiti e nascosti passano dal codice di produzione; cambiano solo i pixel (gradienti sintetici,
 * riferimenti `MediaRef.Synthetic`) e la lettura del telefono, che qui non c'e'. Niente intent: condividere o eliminare
 * non toccherebbero mai media veri (i riferimenti sintetici non sono del MediaStore).
 */
internal class SyntheticLibraryStack(
  private val context: Context,
  private val gallery: GalleryGraph,
  private val kit: SyntheticLibraryKit,
  private val count: Int,
  hooks: LibraryDebugHooks,
) : DebugLibraryStack {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val ready = CompletableDeferred<CatalogTimelineSource>()
  private var database: GalleryDatabase? = null
  private val phone = MutableStateFlow(LibraryPhoneStatus(MediaAccess.FULL, SyncStatus.Scanning(0, count, full = true)))

  private val repository = CatalogTimelineRepository(
    DeferredTimelineSource { ready.await() },
    SnapshotStore(File(context.noBackupFilesDir, REPLICA_DIR)),
    scope,
  )

  /** Preferiti e nascosti sul catalogo sintetico (stesso codice della Libreria vera), quando il database e' pronto. */
  private val catalogActions = CompletableDeferred<CatalogLocalMediaActions>()

  private val actions: LocalMediaActions = GuardedMediaActions(
    object : LocalMediaActions {
      override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = catalogActions.await().setFavorite(ids, on)

      override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) = catalogActions.await().setHidden(ids, on)
    },
  )

  override val state: LibraryTabState
  override val viewerServices: ViewerServices

  init {
    val prefs = gallery.preferences
    val filter = prefs.filter.value
    val grid = MediaGridState(
      view = repository.view(filter).value,
      loader = gallery.thumbnails,
      placeholders = gallery.placeholders,
      placeNames = kit.placeNames,
      engine = gallery.layoutEngine,
      refOf = ::refOf,
      level = prefs.level.value,
      style = prefs.style.value,
      refAt = ::syntheticRefAt,
    )
    state = LibraryTabState(
      grid = grid,
      viewerItems = grid.viewerItems,
      viewerImages = kit.viewerImages,
      photoCount = 0,
      videoCount = 0,
      timeline = repository,
      preferences = prefs,
      actions = actions,
      phone = phone,
      commands = SyntheticCommands,
      debug = hooks,
    )
    viewerServices = ViewerServices(
      images = kit.viewerImages,
      thumbnails = gallery.thumbnails,
      placeholders = gallery.placeholders,
      places = kit.placeNames,
      actions = actions,
    )
    scope.launch { open() }
  }

  private suspend fun open() {
    try {
      val db = withContext(Dispatchers.IO) { prepare() }
      database = db
      val catalog = MediaCatalog(db, OWNER, ZoneId::systemDefault) { System.currentTimeMillis() }
      val source = CatalogTimelineSource(db, catalog)
      catalogActions.complete(CatalogLocalMediaActions(catalog, source, repository))
      ready.complete(source)
      repository.start()
      phone.value = LibraryPhoneStatus(MediaAccess.FULL, SyncStatus.Idle(atMs = System.currentTimeMillis()))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG, e) { "Libreria sintetica non preparata" }
    }
  }

  /** Il database separato con [count] elementi: si riusa se ha gia' quella dimensione, altrimenti si rifa' da zero. */
  private suspend fun prepare(): GalleryDatabase {
    val marker = File(context.noBackupFilesDir, "$REPLICA_DIR/$COUNT_FILE")
    val wanted = "$count/${kit.version}"
    val known = runCatching { marker.readText().trim() }.getOrNull()
    if (known == wanted && context.getDatabasePath(DATABASE_NAME).exists()) return GalleryDatabase.build(context, DATABASE_NAME)
    context.deleteDatabase(DATABASE_NAME)
    File(context.noBackupFilesDir, REPLICA_DIR).deleteRecursively()
    val db = GalleryDatabase.build(context, DATABASE_NAME)
    kit.seed(db, count) { done, total -> phone.value = LibraryPhoneStatus(MediaAccess.FULL, SyncStatus.Scanning(done, total, full = true)) }
    marker.parentFile?.mkdirs()
    marker.writeText(wanted)
    return db
  }

  /** Il riferimento sintetico di [id] nell'indice corrente (un id sconosciuto: un gradiente dal suo seme). */
  private fun refOf(id: MediaId): MediaRef {
    val snapshot = repository.snapshot.value
    val index = snapshot?.indexOf(id) ?: -1
    return if (snapshot != null && index >= 0) syntheticRefAt(snapshot, index) else MediaRef.Synthetic(id.value.hashCode().toLong(), LONG_SIDE, LONG_SIDE)
  }

  /** La chiave: l'`_ID` finto scritto nel database (stabile per media) e la proporzione della cella. */
  private fun syntheticRefAt(snapshot: TimelineSnapshot, index: Int): MediaRef {
    val seed = (snapshot as? ColumnarTimelineSnapshot)?.refAt(index)?.mediaStoreId?.let(kit::pixelSeed)
      ?: snapshot.idAt(index).value.hashCode().toLong()
    val aspect = snapshot.aspectMilliAt(index).coerceAtLeast(1)
    val width = if (aspect >= 1000) LONG_SIDE else (LONG_SIDE * aspect / 1000).coerceAtLeast(1)
    val height = if (aspect >= 1000) (LONG_SIDE * 1000 / aspect).coerceAtLeast(1) else LONG_SIDE
    return MediaRef.Synthetic(seed, width, height)
  }

  /** Chiude il database e ferma il lavoro (si torna alla Libreria del telefono o si sceglie un'altra dimensione). */
  override fun close() {
    scope.launch {
      runCatching { repository.flush() }
      database?.close()
      scope.cancel()
    }
  }

  /** Nessun comando verso il sistema: niente permessi da chiedere, niente da condividere o eliminare. */
  private object SyntheticCommands : LibraryCommands

  companion object {
    const val DATABASE_NAME: String = "gallery-synthetic.db"
    private const val REPLICA_DIR = "replica-synthetic"
    private const val COUNT_FILE = "count"
    private const val LONG_SIDE = 4_000
    private const val TAG = "SyntheticLibrary"
    private val OWNER: AccountId = AccountId.of("libreria-sintetica")
  }
}
