package dev.pampa.mycloudgallery.gallery

import android.content.ComponentCallbacks2
import android.content.Context
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.imaging.ImagingPipeline
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.loader.PipelineThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.placeholder.ThumbHashDecoder
import dev.pampa.mycloudgallery.core.imaging.placeholder.ThumbHashPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.viewer.PipelineViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.data.CatalogLocalMediaActions
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.data.DataStoreLibraryPreferences
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultExifDetailsReader
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.MediaMetadataVideoProbe
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.places.dataset.PlacesOpenResult
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenLibrary
import dev.pampa.mycloudgallery.feature.library.library.LibraryPhoneStatus
import dev.pampa.mycloudgallery.feature.library.trash.TrashHiddenMask
import dev.pampa.mycloudgallery.feature.viewer.ImageDecoderAnimatedLoader
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.details.CatalogMediaDetailsSource
import dev.pampa.mycloudgallery.feature.viewer.details.ContentResolverViewerBytes
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetailsSource
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cio' che cambia fra il telefono vero, le varianti di misura e i test d'integrazione.
 *
 * @property databaseName il file del catalogo: `gallery.db`; `gallery-bench.db` nella variante di misura (P9); null =
 *   in memoria (test).
 * @property access, intents, recentlyDeleted le parti del MediaStore che la UI usa subito (prima del catalogo).
 * @property phone il montaggio della lettura del telefono quando il catalogo c'e' ([PhoneLibrary.android] o i fake).
 * @property syntheticThumbnails i gradienti della Libreria sintetica (solo debug; altrove nessuna sorgente).
 * @property openPlaces l'apertura del dataset dei luoghi (bloccante: la chiama [LazyPlaces] fuori dal thread principale).
 * @property benchSync la sincronizzazione ferma della variante di misura (`ServiceLoader`, docs/bench/README.md §6): con
 *   questa la lettura del telefono e i lavori in background non partono, perche' riconcilierebbero il dataset sintetico.
 */
class GalleryParts(
  val databaseName: String?,
  val replicaDir: File,
  val preferencesFile: File,
  val access: PhoneAccessReader,
  val intents: PhoneMediaIntents,
  val recentlyDeleted: RecentlyDeletedSource,
  val phone: (PhoneInputs) -> PhoneLibrary,
  val syntheticThumbnails: ThumbnailSource = NoThumbnailSource,
  val openPlaces: () -> PlacesOpenResult = { PlacesOpenResult.NotBundled },
  val benchSync: PhoneLibrarySync? = null,
)

/** Il catalogo e la lettura del telefono: esistono solo quando l'account del dispositivo e' noto (vedi [GalleryGraph]). */
class GalleryCore(
  val catalog: MediaCatalog,
  val timelineSource: CatalogTimelineSource,
  val actions: CatalogLocalMediaActions,
  val details: CatalogMediaDetailsSource,
  val phone: PhoneLibrary,
)

/**
 * Il grafo della **galleria del telefono** (Fase 3, docs/fase3-progetto.md §1.4): catalogo, indice della timeline,
 * lettura del telefono, miniature, viewer, luoghi. Uno per processo, accanto ad `AppGraph` della Fase 2; Hilt lo espone
 * contratto per contratto (`GalleryModule`).
 *
 * **Due tempi.** La parte che la UI usa subito (miniature, preferenze, layout, MediaStore per permessi e intent, il
 * repository della timeline) nasce nel costruttore, senza I/O. Il **catalogo** no: `MediaCatalog` vuole l'account NAS di
 * questo dispositivo (il proprietario dei media del telefono), che si conosce solo dopo aver letto l'archivio
 * dell'account; e la Libreria esiste solo con la famiglia pronta (`SetupState.Ready`). Quindi [core] aspetta lo stato
 * pronto, legge l'account una volta e costruisce catalogo e lettura del telefono **fuori dal thread principale**; chi
 * ha bisogno del catalogo lo chiede sospendendo. Le varianti senza account NAS (misura, prove sull'emulatore) usano un
 * proprietario locale fisso ([LOCAL_OWNER]).
 *
 * **A livello di processo** ([startWhenReady], da `ProcessWatcher`): il repository della timeline parte appena la
 * famiglia e' pronta e segue tutte le scritture del catalogo, anche quelle dei lavori in background, cosi' l'istantanea
 * su file non resta mai indietro (E-H1: un nascosto non resta sul disco).
 */
class GalleryGraph(
  private val context: Context,
  val scope: CoroutineScope,
  val parts: GalleryParts,
  private val setup: FamilySetupRepository,
  private val ownerAccount: suspend () -> AccountId?,
  val clock: () -> Long,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) {

  val database: GalleryDatabase = GalleryDatabase.build(context, parts.databaseName)

  // --- Luoghi (P4): null-object finche' il dataset non c'e' -------------------------------------------------------
  val places: LazyPlaces = LazyPlaces(scope, dispatchers.io, parts.openPlaces)

  // --- Miniature e viewer (P6) ------------------------------------------------------------------------------------
  val imagingBudget: ImagingBudget = ImagingBudget.of(context)
  val thumbnailSource: ThumbnailSource = ImagingPipeline.thumbnailSource(context, parts.syntheticThumbnails)
  val thumbnails: PipelineThumbnailLoader = ImagingPipeline.thumbnailLoader(thumbnailSource, imagingBudget, dispatchers)

  /** I segnaposto sfocati: il decodificatore ThumbHash e' di core-metadata, che core-imaging non vede (nota di P6). */
  val placeholders: ThumbHashPlaceholderCache =
    ImagingPipeline.placeholderCache(imagingBudget, ThumbHashDecoder { hash, maxSide -> ThumbHashCodec.decode(hash, maxSide) })
  val viewerImages: PipelineViewerImageSource = ImagingPipeline.viewerImageSource(context, thumbnailSource, imagingBudget, dispatchers)

  // --- Libreria (P5) -----------------------------------------------------------------------------------------------
  val preferences: DataStoreLibraryPreferences = DataStoreLibraryPreferences.create(parts.preferencesFile, scope)
  val layoutEngine: TimelineLayoutEngine = TimelineLayoutEngine()

  /** La finestra visibile della griglia (D11): la fase META legge per primi i media sullo schermo. */
  val metaPriority: WindowMetaPriority = WindowMetaPriority()

  // --- MediaStore per la UI (P2) --------------------------------------------------------------------------------------
  val access: PhoneAccessReader get() = parts.access
  val intents: PhoneMediaIntents get() = parts.intents
  val recentlyDeleted: RecentlyDeletedSource get() = parts.recentlyDeleted

  // --- Il catalogo, quando l'account e' noto ---------------------------------------------------------------------------
  private val coreDeferred: Deferred<GalleryCore> = scope.async(dispatchers.io, start = CoroutineStart.LAZY) { buildCore() }
  private val coreFlow = MutableStateFlow<GalleryCore?>(null)

  /** Il catalogo e la lettura del telefono, se sono gia' stati costruiti (Diagnostica, `onTrimMemory`). */
  val coreOrNull: GalleryCore? get() = coreFlow.value

  /** Il catalogo e la lettura del telefono: aspetta la famiglia pronta, poi li costruisce una volta sola. */
  suspend fun core(): GalleryCore {
    coreFlow.value?.let { return it }
    setup.state.first { it is SetupState.Ready }
    return coreDeferred.await()
  }

  /**
   * Come [core], ma per i lavori in background: null se la famiglia non e' pronta adesso (dispositivo non configurato,
   * in attesa, azzerato, bloccato). Mai un'attesa senza fine in un lavoro.
   */
  suspend fun coreIfReady(): GalleryCore? {
    coreFlow.value?.let { return it }
    val state = setup.state.first { it !is SetupState.Loading }
    return if (state is SetupState.Ready) coreDeferred.await() else null
  }

  /** L'indice letto dal catalogo, quando c'e' (prima, nessun cambiamento e nessuna riga). */
  private val timelineSource = DeferredTimelineSource { core().timelineSource }

  /** Il repository della timeline: uno per processo. Parte con [startWhenReady]. */
  val repository: CatalogTimelineRepository =
    CatalogTimelineRepository(timelineSource, SnapshotStore(parts.replicaDir), scope, dispatchers.default)

  /** Preferiti e nascosti sul catalogo (nascondere toglie prima dall'indice e dall'istantanea, E-H1). */
  val actions: LocalMediaActions = GuardedMediaActions(
    object : LocalMediaActions {
      override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = core().actions.setFavorite(ids, on)

      override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) = core().actions.setHidden(ids, on)
    },
  )

  /** La sincronizzazione che la UI e il processo vedono: quella vera, o quella ferma della variante di misura. */
  private val syncFlow: Flow<PhoneLibrarySync?> =
    parts.benchSync?.let { flowOf(it) } ?: coreFlow.map { it?.phone?.sync }

  /** Lo stato della lettura del telefono (prima del catalogo: ferma, ancora niente). */
  val phoneStatus: StateFlow<SyncStatus> = syncFlow
    .flatMapLatest { sync -> sync?.status ?: flowOf(SyncStatus.Idle(atMs = 0L)) }
    .stateIn(scope, SharingStarted.Eagerly, SyncStatus.Idle(atMs = 0L))

  /** Con l'accesso parziale, i media accessibili adesso (E-P1); null con l'accesso completo. */
  val partialSelection: StateFlow<Set<MediaId>?> = coreFlow
    .flatMapLatest { core -> if (parts.benchSync != null || core == null) flowOf(null) else core.phone.sync.partialSelection }
    .stateIn(scope, SharingStarted.Eagerly, null)

  /** Le foto nuove in attesa del permesso della posizione nell'ultimo giro (E-P21). */
  private val pendingLocation: StateFlow<Int> = coreFlow
    .flatMapLatest { core -> core?.phone?.stats?.snapshot?.map { it.lastReport?.pendingMediaLocation ?: 0 } ?: flowOf(0) }
    .stateIn(scope, SharingStarted.Eagerly, 0)

  /** Lo stato del telefono che la Libreria racconta: accesso, lettura, foto in attesa. */
  val libraryPhoneStatus: StateFlow<LibraryPhoneStatus> =
    combine(access.state, phoneStatus, pendingLocation) { a, sync, pending ->
      LibraryPhoneStatus(access = a.media, sync = sync, pendingLocation = if (a.media == MediaAccess.NONE) 0 else pending)
    }.stateIn(scope, SharingStarted.Eagerly, LibraryPhoneStatus(access = access.state.value.media))

  /** La Libreria con l'accesso parziale: solo cio' che e' accessibile adesso (E-P1). */
  val visibleTimeline: TimelineRepository = PartialAccessTimeline(repository, partialSelection, scope)

  // --- Porte delle Raccolte, dei Nascosti e del cestino (P10) ---------------------------------------------------------

  /** Le raccolte-filtro: la vista dell'indice (mai i nascosti, E-H1) con quel filtro, e i riferimenti ai byte. */
  val collectionItems: CollectionItemsSource = CollectionItemsSource { filter -> viewerItems(filter) }

  /** Gli elementi per un filtro, sopra l'indice (con l'accesso parziale, solo quelli accessibili). */
  fun viewerItems(filter: LibraryFilter): ViewerItems = object : ViewerItems {
    private val delegate = TimelineViewerItems(repository, filter)
    override val view = visibleTimeline.view(filter)

    override fun ref(id: MediaId): MediaRef = delegate.ref(id)

    override fun refOrNull(id: MediaId): MediaRef? = delegate.refOrNull(id)
  }

  /** I nascosti: **il solo punto** che li legge, e solo dopo lo sblocco (lo garantisce chi chiama, P10). */
  val hiddenLibrary: HiddenLibrary = HiddenLibrary { CatalogHidden.read(database, core().catalog) }

  /** Quali elementi del cestino di sistema sono nascosti nell'app (si mostrano col segno, solo da sbloccati). */
  val trashMask: TrashHiddenMask = TrashHiddenMask { refs -> CatalogHidden.hiddenAmong(database, core().catalog, refs) }

  /** I dettagli del pannello informazioni, dal catalogo. */
  val details: MediaDetailsSource = object : MediaDetailsSource {
    override suspend fun details(id: MediaId): MediaDetails? = core().details.details(id)
  }

  /** Tutto cio' che il viewer riceve dal grafo (P8): pixel, miniature, dettagli, EXIF, azioni, intent. */
  val viewerServices: ViewerServices by lazy {
    val resolver = context.applicationContext.contentResolver
    val screenSide = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }.coerceAtLeast(1)
    val bytes = ContentResolverViewerBytes(resolver, intents::uriOf, dispatchers)
    ViewerServices(
      images = viewerImages,
      thumbnails = thumbnails,
      placeholders = placeholders,
      details = details,
      exif = DefaultExifDetailsReader(),
      bytes = bytes,
      places = places,
      geocoder = places,
      actions = actions,
      intents = intents,
      // GIF e WebP animati dalla stessa pipeline (contratto `AnimatedDrawableSource` di P6), alla taglia dello schermo; il
      // ripiego ha lo stesso tetto di pixel (revisione di sicurezza della Fase 3, R2).
      animated = { ref ->
        viewerImages.drawable(ref, screenSide)
          ?: ImageDecoderAnimatedLoader(resolver, intents::uriOf, dispatchers, screenSide, imagingBudget.viewerMaxPixels).load(ref)
      },
      dispatchers = dispatchers,
    )
  }

  /** Il contributo alla sezione "Libreria del telefono" della Diagnostica (P2): vuoto finche' il catalogo non c'e'. */
  val phoneDiagnostics: DiagnosticsContributor = object : DiagnosticsContributor {
    override val section: DiagSection = DiagSection.PHONE_LIBRARY

    override suspend fun collect(): List<DiagItem> = coreFlow.value?.phone?.diagnostics?.collect().orEmpty()
  }

  // --- Processo ------------------------------------------------------------------------------------------------------

  private val startedFlow = MutableStateFlow(false)

  /** Vero quando il repository e' partito (famiglia pronta, catalogo costruito). */
  val started: StateFlow<Boolean> = startedFlow.asStateFlow()

  @Volatile
  private var foreground = false

  /**
   * Dal processo (`ProcessWatcher.start`): appena la famiglia e' pronta costruisce il catalogo, avvia il repository della
   * timeline (che da li' segue ogni scrittura del catalogo) e, se l'app e' in primo piano, aggancia la lettura del
   * telefono. Idempotente.
   */
  fun startWhenReady(onStarted: () -> Unit = {}) {
    scope.launch {
      try {
        core()
        repository.start()
        startedFlow.value = true
        if (foreground) attachSync()
        onStarted()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.e(TAG, e) { "galleria non avviata" }
      }
    }
  }

  /** L'app torna in primo piano: permessi riletti, lettura del telefono agganciata (D10: in primo piano gira sempre). */
  fun onForeground() {
    foreground = true
    refreshAccess()
    if (startedFlow.value) attachSync()
  }

  /** L'app va in secondo piano: la lettura si ferma (resta il lavoro a trigger di contenuto) e l'istantanea si salva. */
  fun onBackground() {
    foreground = false
    currentSync()?.detach()
    if (startedFlow.value) scope.launch { runCatching { repository.flush() } }
  }

  /** Ogni `onResume` e dopo ogni richiesta di permessi: l'accesso non si memorizza mai (E-P1, E-P15). */
  fun refreshAccess() {
    access.refresh()
    coreFlow.value?.phone?.access?.refresh()
  }

  /** `onTrimMemory`: la cache delle miniature si riduce secondo il livello (nota di P6). */
  fun onTrimMemory(level: Int) {
    thumbnails.trim(level)
    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && startedFlow.value) scope.launch { runCatching { repository.flush() } }
  }

  /** La sincronizzazione agganciata al primo piano (quella vera o quella ferma della misura). */
  private fun attachSync() {
    currentSync()?.attach(scope)
  }

  private fun currentSync(): PhoneLibrarySync? = parts.benchSync ?: coreFlow.value?.phone?.sync

  /** Se i lavori in background possono girare: mai nella variante di misura (il dataset e' sintetico). */
  val backgroundWorkAllowed: Boolean get() = parts.benchSync == null

  private suspend fun buildCore(): GalleryCore = withContext(dispatchers.io) {
    val owner = ownerAccount() ?: LOCAL_OWNER
    val catalog = MediaCatalog(database, owner, ZoneId::systemDefault, clock)
    val extractor = DefaultMetadataExtractor(
      geocoder = places,
      zones = places,
      videoProbe = MediaMetadataVideoProbe(),
      previewDecoder = AndroidPreviewDecoder(),
      // I fusi dei "vicini" (stessa cartella e stesso modello) non sono ricavabili dal catalogo senza cambiare lo
      // schema: senza, un'immagine senza fuso e senza GPS prende il fuso del telefono alla lettura (§2.2, ultima riga).
      neighbors = null,
      clock = clock,
    )
    val source = CatalogTimelineSource(database, catalog)
    val core = GalleryCore(
      catalog = catalog,
      timelineSource = source,
      actions = CatalogLocalMediaActions(catalog, source, repository),
      details = CatalogMediaDetailsSource(catalog, database),
      phone = parts.phone(PhoneInputs(context, database, catalog, extractor, clock, metaPriority)),
    )
    coreFlow.value = core
    core
  }

  companion object {
    private const val TAG = "Gallery"

    /**
     * Il proprietario dei media del telefono quando il dispositivo non ha un account NAS: solo le varianti di prova (la
     * misura di P9, lo smoke sull'emulatore). Con la famiglia pronta l'account c'e' sempre (l'onboarding lo salva).
     */
    val LOCAL_OWNER: AccountId = AccountId.of("questo-telefono")
  }
}

/** La finestra visibile della griglia per la fase META (D11): la Libreria la scrive, META la legge. */
class WindowMetaPriority : MetaPriority {
  private val flow = MutableStateFlow<List<MediaId>>(emptyList())
  override val visible: StateFlow<List<MediaId>> = flow.asStateFlow()

  fun show(ids: List<MediaId>) {
    if (flow.value != ids) flow.value = ids
  }
}
