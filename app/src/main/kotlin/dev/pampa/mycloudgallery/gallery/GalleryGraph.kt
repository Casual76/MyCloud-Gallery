package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.albums.AlbumRequests
import dev.pampa.mycloudgallery.feature.viewer.menu.NoViewerAlbumActions
import dev.pampa.mycloudgallery.feature.viewer.menu.ViewerAlbumActions
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.SystemClock
import dev.pampa.mycloudgallery.backup.BackupGraph
import dev.pampa.mycloudgallery.backup.BackupInputs
import dev.pampa.mycloudgallery.backup.BackupUi
import dev.pampa.mycloudgallery.backup.FiveBInputs
import dev.pampa.mycloudgallery.backup.MediaStoreRowFolders
import dev.pampa.mycloudgallery.backup.SettingsBootCounter
import dev.pampa.mycloudgallery.core.mediastore.write.MediaStorePhoneMediaRewriter
import dev.pampa.mycloudgallery.core.metadata.write.ExtractingFileCheck
import dev.pampa.mycloudgallery.backup.SimilarCopiesGraph
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
import dev.pampa.mycloudgallery.core.imaging.nas.NasThumbnailSource
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.noop.NoProvisionalThumbnails
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.loader.PipelineThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.placeholder.ThumbHashDecoder
import dev.pampa.mycloudgallery.core.imaging.placeholder.ThumbHashPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.viewer.ContentImageOrigin
import dev.pampa.mycloudgallery.core.imaging.viewer.PipelineViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.viewer.RoutingImageOrigin
import dev.pampa.mycloudgallery.core.library.contract.FamilyExposure
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.library.data.CatalogLocalMediaActions
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.data.DataStoreLibraryPreferences
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.similar.SignatureSource
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.library.store.SnapshotWriter
import dev.pampa.mycloudgallery.core.library.visibility.CatalogFamilyExposure
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.library.visibility.RotatingWidgetFeed
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.mediastore.write.MediaStorePhoneMediaWriter
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultExifDetailsReader
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.MediaMetadataVideoProbe
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nasops.contract.CitedTemps
import dev.pampa.mycloudgallery.core.places.dataset.PlacesOpenResult
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenLibrary
import dev.pampa.mycloudgallery.feature.library.library.LibraryPhoneStatus
import dev.pampa.mycloudgallery.feature.library.trash.TrashHiddenMask
import dev.pampa.mycloudgallery.feature.viewer.ImageDecoderAnimatedLoader
import dev.pampa.mycloudgallery.feature.viewer.OutsideCopy
import dev.pampa.mycloudgallery.feature.viewer.OutsideCopyLookup
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.details.CatalogMediaDetailsSource
import dev.pampa.mycloudgallery.feature.viewer.details.ContentResolverViewerBytes
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetailsSource
import dev.pampa.mycloudgallery.nas.ActiveWork
import dev.pampa.mycloudgallery.nas.NasGraph
import dev.pampa.mycloudgallery.nas.NasInputs
import dev.pampa.mycloudgallery.work.NasOpsJobs
import java.io.File
import java.time.ZoneId
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cio' che cambia fra il telefono vero, le varianti di misura e i test d'integrazione.
 *
 * @property databaseName il file del catalogo: `gallery.db`; `gallery-bench.db` nella variante di misura (P9); null =
 *   in memoria (test).
 * @property access, intents, recentlyDeleted le parti del MediaStore che la UI usa subito (prima del catalogo).
 * @property phone il montaggio della lettura del telefono quando il catalogo c'e' ([PhoneLibrary.android] o i fake).
 * @property syntheticThumbnails i gradienti della Libreria sintetica (solo debug; altrove nessuna sorgente).
 * @property openPlaces l'apertura del dataset dei luoghi (bloccante: la chiama [LazyPlaces] fuori dal thread principale).
 * @property benchSync la sincronizzazione ferma della variante di misura (`MeasurementHooks`, docs/bench/README.md §6): con
 *   questa la lettura del telefono e i lavori in background non partono, perche' riconcilierebbero il dataset sintetico.
 * @property snapshotDelayMs l'attesa dell'accorpamento dell'istantanea su file (di norma 5 s; i test la accorciano).
 * @property nas il NAS nella Libreria (Fase 4A, P11): i pezzi del grafo dell'app che servono a `NasGraph`; null nelle
 *   varianti senza NAS (misura, prove della sola galleria del telefono).
 * @property monotonicMs l'orologio monotono (tempi delle tappe e delle stime, mai l'ora del telefono).
 * @property benchReplica la replica sintetica della variante di misura (`MeasurementHooks.replicaBlobs`, docs/bench/README.md
 *   §6-bis): le miniature dei media solo-NAS del dataset, senza NAS; null altrove.
 * @property nasJobs Fase 4B (P16): il lavoro delle operazioni lunghe sul NAS (UIDT) e della ripresa dal diario; nessuno nei test.
 * @property onNasOperationsClosed Fase 4B: un'operazione sui file si e' chiusa, la Libreria va riverificata (in produzione la
 *   verifica del NAS con WorkManager; nei test niente).
 * @property similarSignatures solo per i test d'integrazione (Fase 5A): da dove vengono i pixel delle copie simili; null in produzione.
 * @property backup Fase 5A (P7): cio' che il backup riceve oltre al NAS (impostazioni, regole, sonda delle share, recinzione di D13, lavori
 *   dell'utente); null = senza backup (varianti di misura, prove della sola galleria).
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
  val snapshotDelayMs: Long = SnapshotWriter.DEFAULT_DELAY_MS,
  val nas: NasInputs? = null,
  val monotonicMs: () -> Long = { SystemClock.elapsedRealtime() },
  val benchReplica: ReplicaBlobs? = null,
  val nasJobs: NasOpsJobs = NasOpsJobs.NONE,
  val onNasOperationsClosed: () -> Unit = {},
  val backup: BackupInputs? = null,
  val similarSignatures: ((MediaCatalog) -> SignatureSource)? = null,
  /** Fase 6 (P11): la parte `ai` dell'indice condiviso per il NAS (`AiGraph.hooks`), letta quando il NAS parte; null = niente AI. */
  val ai: () -> dev.pampa.mycloudgallery.nas.NasAiHooks? = { null },
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
 *
 * **Vita e morte** (revisione di sicurezza, R7). Tutto cio' che la galleria lancia (costruzione del catalogo, avvio del
 * repository e del suo scrittore, sincronizzazione agganciata) vive sotto un lavoro proprio; i lavori in background
 * passano da [runIfActive]. [shutdown] li ferma **e li aspetta**, chiude la pipeline delle immagini e il database: lo
 * chiama l'azzeramento (`AppLocalDataEraser`) **prima** di cancellare i file, cosi' niente puo' riscriverli. Dopo, la
 * galleria non scrive piu' e non si riavvia in questo processo (serve il prossimo avvio): meglio ferma che viva su dati
 * che non esistono piu'. Con la famiglia non pronta (non configurata, bloccata) [core] e [coreIfReady] non danno il
 * catalogo, anche se e' gia' in memoria.
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

  // --- Vita della galleria (R7) ------------------------------------------------------------------------------------------

  /** Il lavoro di cui sono figli catalogo, repository, scrittore e sincronizzazione agganciata: [shutdown] lo cancella e lo aspetta. */
  private val galleryJob = SupervisorJob(scope.coroutineContext[Job])
  private val galleryScope = CoroutineScope(scope.coroutineContext + galleryJob)

  /** Serializza [shutdown] con le scritture brevi che toccano i file (`flush`, nascondi/preferito). */
  private val lifecycle = Mutex()
  private val workLock = Any()
  private val runningWork = HashSet<Job>()

  @Volatile
  private var retired = false
  private val retiredFlow = MutableStateFlow(false)

  val database: GalleryDatabase = GalleryDatabase.build(context, parts.databaseName)

  // --- Luoghi (P4): null-object finche' il dataset non c'e' -------------------------------------------------------
  val places: LazyPlaces = LazyPlaces(scope, dispatchers.io, parts.openPlaces)

  // --- Il NAS nella Libreria (Fase 4A, P11) ------------------------------------------------------------------------

  /**
   * Il grafo del NAS (scansione, indice condiviso, preparazione, miniature e viewer del NAS): nasce qui senza I/O e parte
   * con la galleria ([startWhenReady]); l'azzeramento lo ferma con lei ([shutdown]). Null senza NAS o con
   * `FeatureFlags.nasLibrary` spento.
   */
  val nas: NasGraph? = parts.nas?.takeIf { it.flags.nasLibrary }?.let { inputs ->
    NasGraph(
      context = context.applicationContext,
      scope = galleryScope,
      database = database,
      inputs = inputs,
      catalogProvider = { coreIfReady()?.catalog },
      extractor = { newExtractor() },
      writer = { MediaStorePhoneMediaWriter(context.applicationContext, dispatchers) },
      monotonicMs = parts.monotonicMs,
      onReplicaChanged = { thumbnails.refreshNas() },
      foreground = { foreground },
      // Fase 4B (P16): operazioni, pulizie e dati personali passano da runIfActive (l'azzeramento li ferma e li aspetta).
      work = object : ActiveWork {
        override suspend fun <T> run(block: suspend () -> T): T? = runIfActive { block() }
      },
      opsJobs = parts.nasJobs,
      onOperationsClosed = parts.onNasOperationsClosed,
      // Fase 5A (D33): i temporanei del diario del backup non si puliscono finche' qualcuno li cita. Se la porta fallisce, nessun
      // temporaneo si toglie in quel giro (lo garantisce la pulizia dei propri file).
      citedTemps = CitedTemps { backup?.citedTemps().orEmpty() },
      aiHooks = parts.ai,
    )
  }

  // --- Miniature e viewer (P6) ------------------------------------------------------------------------------------
  val imagingBudget: ImagingBudget = ImagingBudget.of(context)
  val thumbnailSource: ThumbnailSource =
    ImagingPipeline.thumbnailSource(
      context,
      parts.syntheticThumbnails,
      nas = nas?.thumbnails ?: parts.benchReplica?.let { NasThumbnailSource(it, NoProvisionalThumbnails) } ?: NoThumbnailSource,
    )
  val thumbnails: PipelineThumbnailLoader = ImagingPipeline.thumbnailLoader(thumbnailSource, imagingBudget, dispatchers)

  /** I segnaposto sfocati: il decodificatore ThumbHash e' di core-metadata, che core-imaging non vede (nota di P6). */
  val placeholders: ThumbHashPlaceholderCache =
    ImagingPipeline.placeholderCache(imagingBudget, ThumbHashDecoder { hash, maxSide -> ThumbHashCodec.decode(hash, maxSide) })
  val viewerImages: PipelineViewerImageSource = nas?.let { n ->
    // Fase 4 (P8, D19): i media del NAS dal loro origine (HQ della replica, poi l'originale a intervalli), il resto come prima.
    PipelineViewerImageSource(
      origin = RoutingImageOrigin(ContentImageOrigin(context.applicationContext.contentResolver), n.imageOrigin),
      previews = thumbnailSource,
      budget = imagingBudget,
      dispatchers = dispatchers,
    )
  } ?: ImagingPipeline.viewerImageSource(context, thumbnailSource, imagingBudget, dispatchers)

  // --- Il backup (Fase 5A, P7) ------------------------------------------------------------------------------------------

  /**
   * Il rilevatore delle copie simili (D36, §2.12): uno per processo, per il viewer, la Diagnostica e il riepilogo del primo backup. Senza
   * NAS non c'e' (nessuna copia sul NAS con cui confrontare).
   */
  val similarCopies: SimilarCopiesGraph? = nas?.let { n ->
    SimilarCopiesGraph(
      scope = galleryScope,
      context = context.applicationContext,
      catalogProvider = { coreIfReady()?.catalog },
      personal = { n.organizer.parts?.personalStore },
      blobs = n.blobs,
      blobScopes = n.locations,
      budget = imagingBudget,
      uriOf = intents::uriOf,
      work = { block -> runIfActive { block() } },
      signatures = parts.similarSignatures,
    )
  }

  /**
   * Il backup (D1-D16, D32-D36): motore, verifiche, guardiano, impostazioni, cancello D12, stato per le schermate. Nasce nel costruttore
   * senza I/O e parte con la galleria ([startWhenReady]); l'azzeramento lo ferma **per primo** ([shutdown]). Null senza NAS o senza
   * ingressi del backup.
   */
  val backup: BackupGraph? = if (nas != null && similarCopies != null && parts.nas != null && parts.backup != null) {
    BackupGraph(
      context = context.applicationContext,
      scope = galleryScope,
      database = database,
      nas = nas,
      nasInputs = parts.nas,
      inputs = parts.backup,
      catalogProvider = { coreIfReady()?.catalog },
      phoneProvider = { coreFlow.value?.phone },
      similar = similarCopies,
      familyReady = { setup.state.value is SetupState.Ready },
      monotonicMs = parts.monotonicMs,
      fiveBInputs = parts.backup.fiveB ?: androidFiveBInputs(),
      foreground = { foreground },
      deviceNow = { System.currentTimeMillis() },
    )
  } else {
    null
  }

  /**
   * Fase 5B (P14): i dialoghi di sistema (Android 11+), il riscrittore delle righe del telefono (copia di sicurezza, rilettura), il controllo
   * della copia corretta con l'estrattore e il decodificatore dell'app, `BOOT_COUNT` e la cartella di una riga (D13).
   */
  private fun androidFiveBInputs(): FiveBInputs {
    val app = context.applicationContext
    return FiveBInputs(
      intents = parts.intents,
      rewriter = { MediaStorePhoneMediaRewriter(app, dispatchers) },
      fileCheck = { ExtractingFileCheck(newExtractor(), AndroidPreviewDecoder(), ZoneId.systemDefault()) },
      bootCounter = SettingsBootCounter(app.contentResolver),
      rowFolders = MediaStoreRowFolders(app.contentResolver),
    )
  }

  /**
   * Fase 5B (P14): cio' che le schermate della 5B danno alla galleria (le voci del backup nel viewer, "Libera spazio" sulla selezione della
   * Libreria). Nascono dopo la galleria (dipendono da lei): le lega l'applicazione all'avvio, prima di qualunque schermata.
   */
  class FiveBHooks(val mediaActions: dev.pampa.mycloudgallery.feature.viewer.menu.BackupMediaActions, val freeSpace: (Set<MediaId>) -> Unit)

  @Volatile
  var fiveB: FiveBHooks? = null

  /**
   * Correzioni 5B (R4, MC-2): ogni "Condividi" dell'app (`AppShares` delle integrazioni, con "Rimuovi la posizione"), legato dall'applicazione
   * all'avvio come [fiveB] ma **anche senza backup**: il viewer senza backup e la selezione della Libreria non mandano piu' gli originali.
   */
  @Volatile
  var shares: dev.pampa.mycloudgallery.integrations.AppShares? = null

  /**
   * Fase 6 (P11): le porte del viewer della ricerca (testo dal vivo, "Trova simili", animazione delle foto in movimento e foto in movimento
   * del NAS). Le lega l'applicazione all'avvio, prima di qualunque schermata, come [fiveB]; null = nessuna (test, varianti di misura).
   */
  class SearchViewerHooks(
    val textSource: dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextSource?,
    val textReader: dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextReader?,
    val similarSearch: dev.pampa.mycloudgallery.feature.viewer.menu.ViewerSimilarSearch,
    val motionAnimation: dev.pampa.mycloudgallery.feature.viewer.motion.MotionAnimationSetting,
    val motionTraffic: dev.pampa.mycloudgallery.feature.viewer.video.MotionTraffic,
    /** Il ritaglio del soggetto (P13, D44): il ritagliatore e le tre azioni della barra. Null = nessun ritaglio (D13). */
    val subjectCutter: dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerSubjectCutter? = null,
    val cutoutSaver: dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutSaver? = null,
  )

  @Volatile
  var searchViewer: SearchViewerHooks? = null

  /** Fase 6 (P14): la coda dei video delle foto in movimento del NAS (`cacheDir/motion/`), una per processo. */
  val nasMotion: dev.pampa.mycloudgallery.feature.viewer.video.NasMotionSource? by lazy {
    nas?.let { n ->
      dev.pampa.mycloudgallery.feature.viewer.video.NasMotionSource(
        n.video,
        context.applicationContext.cacheDir,
        searchViewer?.motionTraffic ?: dev.pampa.mycloudgallery.feature.viewer.video.MotionTraffic.None,
      )
    }
  }

  /** Le porte delle schermate del backup (pagina, impostazioni, viewer, Impostazioni): adattatori sul grafo del backup. */
  val backupUi: BackupUi? = backup?.let { b -> BackupUi(b, database, galleryScope, context, parts.nas?.account ?: { null }) }

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
  private val coreDeferred: Deferred<GalleryCore> = galleryScope.async(dispatchers.io, start = CoroutineStart.LAZY) { buildCore() }
  private val coreFlow = MutableStateFlow<GalleryCore?>(null)

  /** Il catalogo e la lettura del telefono, se sono gia' stati costruiti (Diagnostica, `onTrimMemory`). */
  val coreOrNull: GalleryCore? get() = coreFlow.value

  /**
   * Il catalogo e la lettura del telefono: aspetta la famiglia pronta (**ogni volta**, anche con il catalogo gia' in
   * memoria: un dispositivo bloccato o azzerato non lo riceve), poi li costruisce una volta sola. Lancia
   * [GalleryStoppedException] se la galleria e' stata fermata da [shutdown].
   */
  suspend fun core(): GalleryCore {
    checkActive()
    setup.state.first { it is SetupState.Ready }
    checkActive()
    return builtCore()
  }

  /**
   * Come [core], ma per i lavori in background: null se la famiglia non e' pronta adesso (dispositivo non configurato,
   * in attesa, azzerato, bloccato) o se la galleria e' ferma. Mai un'attesa senza fine in un lavoro.
   */
  suspend fun coreIfReady(): GalleryCore? {
    if (retired) return null
    val state = setup.state.first { it !is SetupState.Loading }
    if (state !is SetupState.Ready || retired) return null
    return try {
      builtCore()
    } catch (_: GalleryStoppedException) {
      null
    }
  }

  /**
   * **D-7** (prove sul campo): un lavoro partito in un processo appena nato aspetta, al massimo [timeout], che la famiglia sia **pronta**,
   * invece di fidarsi del primo stato che non e' piu' "in caricamento". All'avvio lo stato del dispositivo puo' passare un attimo per "non
   * configurato": l'avvio dichiara finita la lettura della fiducia un istante prima che lo stato della famiglia la veda, e [coreIfReady]
   * rispondeva null. Il lavoro del backup dell'utente rilanciato da Android dopo la morte del processo finiva cosi' in 0,4 secondi con "niente
   * da fare". Vero se la famiglia e' pronta e la galleria viva; falso dopo [timeout] (un dispositivo davvero non pronto) o a galleria ferma.
   */
  suspend fun awaitReady(timeout: Duration): Boolean {
    if (retired) return false
    val ready = withTimeoutOrNull(timeout) { setup.state.first { it is SetupState.Ready } }
    return ready != null && !retired
  }

  private suspend fun builtCore(): GalleryCore {
    coreFlow.value?.let { return it }
    return try {
      coreDeferred.await()
    } catch (e: CancellationException) {
      // Annullato dalla chiusura della galleria (non da chi aspetta): per chi chiede e' un errore, non un annullamento.
      if (retired) throw GalleryStoppedException() else throw e
    }
  }

  private fun checkActive() {
    if (retired) throw GalleryStoppedException()
  }

  /** L'indice letto dal catalogo, quando c'e' (prima, nessun cambiamento e nessuna riga). */
  private val timelineSource = DeferredTimelineSource { core().timelineSource }

  /** Il repository della timeline: uno per processo. Parte con [startWhenReady]. */
  val repository: CatalogTimelineRepository =
    CatalogTimelineRepository(timelineSource, SnapshotStore(parts.replicaDir), galleryScope, dispatchers.default, parts.snapshotDelayMs)

  /** Preferiti e nascosti sul catalogo (nascondere toglie prima dall'indice e dall'istantanea, E-H1). */
  val actions: LocalMediaActions = GuardedMediaActions(
    object : LocalMediaActions {
      override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
        val core = core()
        writing { core.actions.setFavorite(ids, on) }
        // Fase 4B (§2.9): la modifica viaggia ai miei dispositivi (accorpata 30 s; la adotta `adoptLocal` della sincronizzazione).
        nas?.organizer?.personalChanged()
      }

      override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
        val core = core()
        writing { core.actions.setHidden(ids, on) }
        nas?.organizer?.personalChanged()
        // Fase 7 (P9, D22): il widget di prova (solo debug) si ridisegna subito; in release non fa niente.
        DeveloperTools.hiddenChanged(context)
        // Fase 7 (P13): copertine e conteggi degli album condivisi senza aspettare il giro dopo.
        onHiddenChanged()
      }

      // Fase 7 (P9, E-H2): quanti degli elementi nascosti i familiari vedono ancora (area comune, album condivisi).
      override suspend fun familyStillSees(ids: Set<MediaId>): Int = core().actions.familyStillSees(ids)
    },
  )

  /** Fase 7 (P13): dopo un "Nascondi" o "Mostra" fatto qui (lo lega `AlbumsModule`: gli album condivisi ricalcolano copertine e conteggi). */
  @Volatile
  var onHiddenChanged: () -> Unit = {}

  /**
   * Fase 7 (P13, P10-10, D35): le richieste "Aggiungi ad album" (selezione della Libreria, viewer, Nascosti), che la radice mostra con `AddToAlbumHost`,
   * e le voci ⋮ degli album di ogni viewer ([viewerAlbums], lo lega `AlbumsModule`; di serie nessuna voce).
   */
  val albumRequests: AlbumRequests = AlbumRequests()

  @Volatile
  var viewerAlbums: ViewerAlbumActions = NoViewerAlbumActions

  /**
   * Fase 8 (P13, D25): le persone del media aperto nel viewer (la riga "Persone" delle info, "Chi e'?"). Le lega `FacesModule` con il processo; di serie
   * nessuna (il viewer non mostra la riga). Letta quando il viewer si costruisce, dopo l'avvio dell'app.
   */
  @Volatile
  var viewerPeople: dev.pampa.mycloudgallery.feature.viewer.people.ViewerPeople? = null

  /** Una scrittura che tocca catalogo e istantanea: mai insieme a [shutdown], e mai dopo. */
  private suspend fun <T> writing(block: suspend () -> T): T = lifecycle.withLock {
    checkActive()
    block()
  }

  /** La sincronizzazione che la UI e il processo vedono: quella vera, o quella ferma della variante di misura. */
  private val syncFlow: Flow<PhoneLibrarySync?> =
    parts.benchSync?.let { flowOf(it) } ?: coreFlow.map { it?.phone?.sync }

  /** Lo stato della lettura del telefono (prima del catalogo: ferma, ancora niente). */
  val phoneStatus: StateFlow<SyncStatus> = syncFlow
    .flatMapLatest { sync -> sync?.status ?: flowOf(SyncStatus.Idle(atMs = 0L)) }
    .stateIn(scope, SharingStarted.Eagerly, SyncStatus.Idle(atMs = 0L))

  /**
   * Con l'accesso parziale, i media accessibili adesso (E-P1); null con l'accesso completo **o finche' la prima lettura non
   * l'ha detto** (la Libreria non lo scambia per "tutto": vedi [PartialAccessTimeline]). A galleria ferma, un insieme
   * vuoto: niente si mostra piu'.
   */
  val partialSelection: StateFlow<Set<MediaId>?> = combine(
    coreFlow.flatMapLatest { core -> if (parts.benchSync != null || core == null) flowOf(null) else core.phone.sync.partialSelection },
    retiredFlow,
  ) { selection, stopped -> if (stopped) emptySet() else selection }
    .stateIn(scope, SharingStarted.Eagerly, null)

  /**
   * L'accesso ai media com'e' adesso (letto subito dal sistema, non dalla prima lettura del catalogo). Nella variante di
   * misura la Libreria e' il dataset sintetico: l'accesso vero del telefono non la riguarda.
   */
  private val viewAccess: StateFlow<MediaAccess> =
    if (parts.benchSync != null) {
      MutableStateFlow(MediaAccess.FULL)
    } else {
      access.state.map { it.media }.stateIn(scope, SharingStarted.Eagerly, access.state.value.media)
    }

  /** Le foto nuove in attesa del permesso della posizione nell'ultimo giro (E-P21). */
  private val pendingLocation: StateFlow<Int> = coreFlow
    .flatMapLatest { core -> core?.phone?.stats?.snapshot?.map { it.lastReport?.pendingMediaLocation ?: 0 } ?: flowOf(0) }
    .stateIn(scope, SharingStarted.Eagerly, 0)

  /** Lo stato del telefono che la Libreria racconta: accesso, lettura, foto in attesa. */
  val libraryPhoneStatus: StateFlow<LibraryPhoneStatus> =
    combine(viewAccess, phoneStatus, pendingLocation, partialSelection) { media, sync, pending, chosen ->
      LibraryPhoneStatus(
        access = media,
        sync = sync,
        pendingLocation = if (media == MediaAccess.NONE) 0 else pending,
        // Accesso parziale e prima lettura non ancora finita: la Libreria dice che sta leggendo, non mostra niente (R5).
        selectionPending = media == MediaAccess.PARTIAL && chosen == null,
      )
    }.stateIn(
      scope,
      SharingStarted.Eagerly,
      viewAccess.value.let { media -> LibraryPhoneStatus(access = media, selectionPending = media == MediaAccess.PARTIAL) },
    )

  /**
   * La Libreria con l'accesso parziale: solo cio' che e' accessibile adesso (E-P1). **Fail closed** (R5): con l'accesso
   * parziale, o senza accesso, e finche' la prima lettura non ha detto cosa e' accessibile, la vista e' vuota.
   */
  val visibleTimeline: TimelineRepository = PartialAccessTimeline(repository, partialSelection, viewAccess, scope)

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

  /**
   * I nascosti: **il solo punto** che li legge, e solo dopo lo sblocco (lo garantisce chi chiama, P10). La vista si rilegge finche' e'
   * guardata (GA-1); la galleria che si ferma ferma anche lei (`galleryScope`).
   */
  val hiddenLibrary: HiddenLibrary = HiddenLibrary { CatalogHidden.read(database, core().catalog, galleryScope) }

  /** Quali elementi del cestino di sistema sono nascosti nell'app (si mostrano col segno, solo da sbloccati). */
  val trashMask: TrashHiddenMask = TrashHiddenMask { refs -> CatalogHidden.hiddenAmong(database, core().catalog, refs) }

  // --- Una sola fonte dei nascosti per le superfici nuove (Fase 7, P9, D22) ---------------------------------------------------

  /**
   * Il filtro finale dei nascosti (`HiddenMask`): lo chiama ogni superficie nuova subito prima di mostrare. Fallisce chiuso (un errore o un id
   * sconosciuto = nascosto). Legge solo il database: non aspetta la famiglia pronta, e prima non c'e' niente da mostrare.
   */
  val hiddenMask: HiddenMask = CatalogHiddenMask(database)

  /** La vista del catalogo senza nascosti (`VisibleMedia`): l'istantanea della Libreria e "quali di questi si possono mostrare adesso". */
  val visibleMedia: VisibleMedia = CatalogVisibleMedia(database, visibleTimeline.snapshot)

  /** La sorgente del widget (solo debug oggi, Fase 10 poi): a giro sulla Libreria, mai un nascosto. */
  val widgetFeed: WidgetFeed = RotatingWidgetFeed(visibleMedia, hiddenMask)

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
      // Fase 4 (P8): dati mobili (E-V2), originali con lo zoom, video in streaming, "Scarica sul telefono" (D19, D20).
      onConfirmMobileData = nas?.consent?.let { consent -> { ref: MediaRef -> consent.confirm(ref) } },
      originals = nas?.imageOrigin,
      nasVideo = nas?.video,
      download = nas?.downloader,
      // D-15: il viewer segue la rete di adesso (VPN spenta fuori casa, ritorno del Wi-Fi) invece di restare su uno stato calcolato una volta.
      link = nas?.link,
      // Fase 4B (P16): "Sposta", "Rinomina", "Elimina…" per i media del NAS (il viewer non scrive mai da solo, D24).
      nasActions = nas?.organizer?.mediaActions,
      dispatchers = dispatchers,
      // Fase 5A (P7): la riga "Backup" del pannello informazioni (D16) e la sezione "N copie" (D36), con le copie fuori indice.
      backup = backupUi?.mediaStates,
      similarCopies = similarCopies,
      outsideCopy = similarCopies?.let { OutsideCopyLookup { id -> outsideCopyOf(id) } },
      // Fase 5B (P14): Versioni, Ripristina originale, Correggi, Ruota, "Modifica con…", "Condividi" con o senza posizione, "Ovunque".
      // Correzioni 5B (R4): senza backup restano le voci di "Condividi" (con "Rimuovi la posizione"), non il percorso di prima con l'originale.
      backupActions = fiveB?.mediaActions ?: shares?.viewerActions(),
      // Fase 6 (P11, P11b): testo dal vivo e lettura al volo (P12), "Trova simili" (P8), foto in movimento (P14), ritaglio del soggetto (P13).
      motionAnimation = searchViewer?.motionAnimation ?: dev.pampa.mycloudgallery.feature.viewer.motion.MotionAnimationSetting.On,
      nasMotion = nasMotion,
      textSource = searchViewer?.textSource,
      textReader = searchViewer?.textReader,
      similarSearch = searchViewer?.similarSearch ?: dev.pampa.mycloudgallery.feature.viewer.menu.NoViewerSimilarSearch,
      subjectCutter = searchViewer?.subjectCutter,
      cutoutSaver = searchViewer?.cutoutSaver,
      // Fase 8 (P13): la riga "Persone" nelle info (solo con la porta legata).
      people = viewerPeople,
      // Fase 7 (P13): "Aggiungi ad album" dal viewer; le voci si leggono a ogni richiesta da [viewerAlbums] (gli album si legano dopo la galleria).
      albums = object : ViewerAlbumActions {
        override fun menu(id: MediaId) = viewerAlbums.menu(id)

        override fun addToAlbum(id: MediaId) = viewerAlbums.addToAlbum(id)

        override suspend fun removeFromAlbum(id: MediaId) = viewerAlbums.removeFromAlbum(id)

        override suspend fun setAsCover(id: MediaId) = viewerAlbums.setAsCover(id)
      },
    )
  }

  /**
   * Dove sta una copia che non e' nell'indice (D36: le copie simili sono unite in una cella, l'indice ha solo la copertina): dal catalogo,
   * la copia del telefono se c'e' e altrimenti quella del NAS. Null se non ne ha una leggibile adesso.
   */
  private suspend fun outsideCopyOf(id: MediaId): OutsideCopy? {
    val catalog = coreIfReady()?.catalog ?: return null
    val ref: MediaRef = catalog.phoneRefs(listOf(id))[id] ?: catalog.nasRefs(listOf(id))[id] ?: return null
    return OutsideCopy(ref, catalog.media(id)?.kind ?: MediaKind.PHOTO)
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
    galleryScope.launch {
      try {
        core()
        repository.start()
        startedFlow.value = true
        // Il NAS nella Libreria parte con la galleria (famiglia pronta, catalogo costruito); senza NAS non fa niente.
        nas?.let { n ->
          galleryScope.launch {
            runCatching { n.start() }
            // Il backup parte dopo il NAS: area privata, chiave del dispositivo e catalogo servono tutti (senza, non parte niente).
            backup?.let { b -> runCatching { b.start() } }
            // Fase 5B (P14): file di lavoro, attivita' in sospeso, esiti di "Libera spazio" in dubbio, scambi persi (solo dati locali).
            backup?.let { b -> runCatching { b.atStart() } }
            // Fase 5A (D36): con il registro dei dati personali pronto il confronto delle copie simili puo' partire.
            similarCopies?.requestRun()
          }
        }
        // Dopo ogni lettura del telefono finita con l'app aperta (META compresa) il confronto riparte: a tappe, su Default, chi lo chiede non aspetta.
        similarCopies?.let { detector ->
          galleryScope.launch {
            phoneStatus.map { it is SyncStatus.Idle }.distinctUntilChanged().filter { it }.collect { detector.requestRun() }
          }
        }
        // Fase 5B (P14): dopo ogni lettura del telefono gli esiti in dubbio di "Libera spazio" si risolvono con il telefono (P9).
        backup?.let { b ->
          galleryScope.launch {
            phoneStatus.map { it is SyncStatus.Idle }.distinctUntilChanged().filter { it }.collect { runCatching { b.afterPhoneRead() } }
          }
        }
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
    // GA-3: un `onTrimMemory` in secondo piano ha svuotato le miniature della finestra: al ritorno si richiedono.
    if (!retired) thumbnails.resume()
    if (startedFlow.value && !retired) attachSync()
  }

  /** L'app va in secondo piano: la lettura si ferma (resta il lavoro a trigger di contenuto) e l'istantanea si salva. */
  fun onBackground() {
    foreground = false
    currentSync()?.detach()
    if (startedFlow.value) galleryScope.launch { runCatching { flush() } }
  }

  /** Ogni `onResume` e dopo ogni richiesta di permessi: l'accesso non si memorizza mai (E-P1, E-P15). */
  fun refreshAccess() {
    access.refresh()
    coreFlow.value?.phone?.access?.refresh()
  }

  /** `onTrimMemory`: la cache delle miniature si riduce secondo il livello (nota di P6). */
  fun onTrimMemory(level: Int) {
    if (retired) return
    thumbnails.trim(level)
    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && startedFlow.value) galleryScope.launch { runCatching { flush() } }
  }

  /** La sincronizzazione agganciata al primo piano (quella vera o quella ferma della misura). */
  private fun attachSync() {
    currentSync()?.attach(galleryScope)
  }

  /**
   * Scrive subito l'istantanea in attesa (secondo piano, `onTrimMemory`, fine della manutenzione). Mai a galleria ferma e
   * mai insieme a [shutdown]: dopo l'azzeramento niente deve riscrivere `replica/timeline.idx` (R7).
   */
  suspend fun flush() {
    lifecycle.withLock {
      if (retired) return
      repository.flush()
    }
  }

  /**
   * Esegue [block] (un lavoro in background che usa il catalogo) solo se la famiglia e' pronta e la galleria viva, e in modo
   * che [shutdown] lo possa **fermare e aspettare**. Null se non c'era niente da fare, o se la galleria si e' fermata
   * mentre girava. Cio' che [block] solleva (tranne l'annullamento dovuto alla chiusura) arriva a chi chiama.
   */
  suspend fun <T> runIfActive(block: suspend (GalleryCore) -> T): T? {
    val core = coreIfReady() ?: return null
    return try {
      coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        val registered = synchronized(workLock) { if (retired) false else runningWork.add(job) }
        if (!registered) return@coroutineScope null
        try {
          block(core)
        } finally {
          synchronized(workLock) { runningWork.remove(job) }
        }
      }
    } catch (e: CancellationException) {
      if (retired) null else throw e
    }
  }

  /**
   * Ferma la galleria viva, per sempre in questo processo (R7). Nell'ordine: la rende "ferma" (niente di nuovo parte e
   * niente si mostra piu'), annulla i lavori in corso e li aspetta, stacca la sincronizzazione, annulla repository e
   * scrittore dell'istantanea e li aspetta, chiude la pipeline delle immagini e il database. Quando ritorna nessuno puo'
   * piu' scrivere in `replica/` o nel database: l'azzeramento li puo' cancellare. Idempotente.
   *
   * Se la galleria non e' mai partita (nessun catalogo costruito: dispositivo mai pronto in questo processo) non c'e'
   * niente da fermare e resta utilizzabile, cosi' una configurazione fatta dopo l'azzeramento nello stesso processo funziona.
   */
  suspend fun shutdown() {
    lifecycle.withLock {
      if (retired) return
      // Fase 5B (P14): le integrazioni revocano subito gli id dei documenti e tornano ai null-object (anche se la galleria non e' mai partita).
      shutdownHooks.forEach { hook -> runCatching { hook() } }
      if (coreFlow.value == null && !coreDeferred.isActive) return
      val running = synchronized(workLock) {
        retired = true
        runningWork.toList()
      }
      retiredFlow.value = true
      // Il backup si ferma per primo (§1.4): il giro in corso si ferma prima del file successivo e niente di nuovo parte; poi il NAS.
      backup?.stop()
      // Il NAS si ferma poi: niente parte piu', le sue facciate rispondono "niente" (R7, §1.4).
      nas?.stop()
      running.forEach { it.cancel() }
      currentSync()?.detach()
      galleryJob.cancelAndJoin()
      running.joinAll()
      startedFlow.value = false
      thumbnails.close()
      withContext(dispatchers.io) {
        try {
          database.close()
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "database non chiuso: ${e.javaClass.simpleName}" }
        }
      }
    }
  }

  private val shutdownHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

  /** Fase 5B (P14): cio' che va fatto all'azzeramento prima di fermare la galleria (la revoca degli id dei documenti delle integrazioni). */
  fun onShutdown(hook: () -> Unit) {
    shutdownHooks += hook
  }

  private fun currentSync(): PhoneLibrarySync? = parts.benchSync ?: coreFlow.value?.phone?.sync

  /** Se i lavori in background possono girare: mai nella variante di misura (il dataset e' sintetico). */
  val backgroundWorkAllowed: Boolean get() = parts.benchSync == null

  private suspend fun buildCore(): GalleryCore = withContext(dispatchers.io) {
    val owner = ownerAccount() ?: LOCAL_OWNER
    val catalog = MediaCatalog(database, owner, ZoneId::systemDefault, clock)
    val extractor = newExtractor()
    // Fase 4 (D18): i riferimenti al NAS delle celle solo-NAS, anche con la share non raggiungibile (E-L4).
    val source = CatalogTimelineSource(database, catalog, nasRefsLookup = if (nas != null || parts.benchReplica != null) catalog::nasRefs else null)
    val core = GalleryCore(
      catalog = catalog,
      timelineSource = source,
      // Fase 7 (P9, E-H2): i familiari vedono ancora cio' che sta nell'area comune; senza NAS non c'e' nessuno da avvisare.
      actions = CatalogLocalMediaActions(catalog, source, repository, if (nas != null) CatalogFamilyExposure(database) else FamilyExposure.None),
      details = CatalogMediaDetailsSource(catalog, database),
      phone = parts.phone(PhoneInputs(context, database, catalog, extractor, clock, metaPriority)),
    )
    checkActive()
    coreFlow.value = core
    core
  }

  /** L'estrattore dei metadati (telefono e NAS): luoghi offline, fusi, sonda dei video, anteprime incorporate. */
  /** L'estrattore dei metadati dell'app (anche per il NAS sintetico di debug, che scansiona con lo stesso). */
  internal fun newExtractor() = DefaultMetadataExtractor(
    geocoder = places,
    zones = places,
    videoProbe = MediaMetadataVideoProbe(),
    previewDecoder = AndroidPreviewDecoder(),
    // I fusi dei "vicini" (stessa cartella e stesso modello) non sono ricavabili dal catalogo senza cambiare lo
    // schema: senza, un'immagine senza fuso e senza GPS prende il fuso del telefono alla lettura (§2.2, ultima riga).
    neighbors = null,
    clock = clock,
  )

  companion object {
    private const val TAG = "Gallery"

    /**
     * Il proprietario dei media del telefono quando il dispositivo non ha un account NAS: solo le varianti di prova (la
     * misura di P9, lo smoke sull'emulatore). Con la famiglia pronta l'account c'e' sempre (l'onboarding lo salva).
     */
    val LOCAL_OWNER: AccountId = AccountId.of("questo-telefono")
  }
}

/** La galleria e' stata fermata ([GalleryGraph.shutdown], per l'azzeramento): nessuna operazione sul catalogo e' piu' possibile. */
class GalleryStoppedException : IllegalStateException("La galleria e' ferma.")

/** La finestra visibile della griglia per la fase META (D11): la Libreria la scrive, META la legge. */
class WindowMetaPriority : MetaPriority {
  private val flow = MutableStateFlow<List<MediaId>>(emptyList())
  override val visible: StateFlow<List<MediaId>> = flow.asStateFlow()

  fun show(ids: List<MediaId>) {
    if (flow.value != ids) flow.value = ids
  }
}
