package dev.pampa.mycloudgallery.nas

import android.app.ActivityManager
import android.content.Context
import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.nas.NasBlobScopes
import dev.pampa.mycloudgallery.core.imaging.nas.NasFileLocation
import dev.pampa.mycloudgallery.core.imaging.nas.NasLocationLookup
import dev.pampa.mycloudgallery.core.imaging.nas.NasMediaOriginalThumbnails
import dev.pampa.mycloudgallery.core.imaging.nas.NasThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.viewer.MobileDataConsent
import dev.pampa.mycloudgallery.core.imaging.viewer.NasImageOrigin
import dev.pampa.mycloudgallery.core.imaging.viewer.NasLink
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocator
import dev.pampa.mycloudgallery.core.imaging.viewer.NasOriginalStore
import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.BlobScope
import dev.pampa.mycloudgallery.core.index.contract.IndexSyncStatus
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQuality
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQualityState
import dev.pampa.mycloudgallery.core.index.contract.ReplicaRecords
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.index.contract.SyncReport
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.NasRemoteIndex
import dev.pampa.mycloudgallery.core.index.publish.OutboxIndexPublisher
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.work.NasOpsJobs
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.nas.R as NasScreensR
import dev.pampa.mycloudgallery.core.index.quality.ReplicaQualityController
import dev.pampa.mycloudgallery.core.index.replica.LocalReplica
import dev.pampa.mycloudgallery.core.index.replica.ReplicaProvisionalThumbnails
import dev.pampa.mycloudgallery.core.index.replica.pack.PackDirectory
import dev.pampa.mycloudgallery.core.index.replica.store.RoomProvisionalStore
import dev.pampa.mycloudgallery.core.index.replica.store.RoomReplicaStore
import dev.pampa.mycloudgallery.core.index.store.FileOutboxBlobStore
import dev.pampa.mycloudgallery.core.index.store.RoomIndexPublishStore
import dev.pampa.mycloudgallery.core.index.sync.IndexCounters
import dev.pampa.mycloudgallery.core.index.sync.IndexSyncEngine
import dev.pampa.mycloudgallery.core.index.sync.SyncBlock
import dev.pampa.mycloudgallery.core.index.sync.SyncGate
import dev.pampa.mycloudgallery.core.index.work.LeasedWorkUnits
import dev.pampa.mycloudgallery.core.index.work.RemotePublishedClasses
import dev.pampa.mycloudgallery.core.index.work.TrustWorkLeases
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.status.FrozenInfo
import dev.pampa.mycloudgallery.core.library.status.LibraryNasStatusComposer
import dev.pampa.mycloudgallery.core.library.status.NasAreaProgress
import dev.pampa.mycloudgallery.core.library.status.NasStatusInputs
import dev.pampa.mycloudgallery.core.library.status.ScanProgress
import dev.pampa.mycloudgallery.core.library.status.SyncProgress
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaWriter
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.session.ClockJumpGuard
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.Freeze
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanSettings
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import dev.pampa.mycloudgallery.core.nasmedia.detect.NasDetector
import dev.pampa.mycloudgallery.core.nasmedia.scan.DefaultNasScanner
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.prepare.contract.PrepStop
import dev.pampa.mycloudgallery.core.prepare.contract.PrepStorage
import dev.pampa.mycloudgallery.core.prepare.contract.RunResult
import dev.pampa.mycloudgallery.core.prepare.engine.DefaultPreparer
import dev.pampa.mycloudgallery.core.prepare.run.DefaultPreparationRun
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.viewer.download.CopyLinker
import dev.pampa.mycloudgallery.feature.viewer.download.NasDownloader
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoSource
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Il grafo del **NAS nella Libreria** (Fase 4A, P11; docs/fase4-progetto.md §1.4): sessione → `NasMediaReader` →
 * scansione per share (P5) → catalogo (P2) → indice condiviso: pubblicazione (P3) e replica con sincronizzazione (P4) →
 * preparazione (P6) → Libreria unificata (P7) → viewer e "Scarica sul telefono" (P8). Uno per processo, dentro la galleria
 * (`GalleryGraph`): ne condivide il database, il catalogo e la vita (l'azzeramento lo ferma con lei).
 *
 * **Due tempi**, come la galleria. Nel costruttore, senza I/O: gli archivi (Room, cartelle della replica e dell'outbox),
 * le sorgenti delle miniature e del viewer con le loro **facciate** (rispondono "niente" finche' il NAS non c'e'). In
 * [start], con la famiglia pronta e il catalogo costruito: account, chiave del dispositivo e aree, poi replica, qualita',
 * outbox, sincronizzazione, unita' di lavoro, preparazione e uno scanner per share. Senza NAS configurato (o con
 * `FeatureFlags.nasLibrary` spento) non parte niente e la Libreria resta quella del telefono.
 *
 * **Scritture sul NAS** (solo indice e prenotazioni in `.mycloudgallery/`, mai i media, D1): passano dal cancello
 * [writeGate]: D31 (`indexPublishTestSharesOnly`: sulle share vere solo locale, lo applica `IndexWriteRules`), emergenza
 * dell'utente, congelamento della share (D10), salto dell'ora del NAS (D32), dispositivo non piu' fidato.
 *
 * **Regole e tempi**: i lavori in background (`work/NasWork.kt`) chiamano [scan], [syncIndex], [prepare] dentro una tappa
 * (`StagedRun`, D22) e dentro `GalleryGraph.runIfActive`, cosi' l'azzeramento li ferma e li aspetta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasGraph(
  private val context: Context,
  private val scope: CoroutineScope,
  private val database: GalleryDatabase,
  private val inputs: NasInputs,
  private val catalogProvider: suspend () -> MediaCatalog?,
  private val extractor: () -> MetadataExtractor,
  private val writer: () -> PhoneMediaWriter,
  private val monotonicMs: () -> Long,
  /** Dopo una sincronizzazione con miniature nuove e al ritorno del NAS: la griglia sostituisce le provvisorie (P7). */
  private val onReplicaChanged: () -> Unit = {},
  /** L'app e' in primo piano: solo allora si decodificano gli originali come contenuto visibile (D17). */
  private val foreground: () -> Boolean = { true },
  /** Fase 4B (P16): i lavori che l'azzeramento ferma e aspetta (`GalleryGraph.runIfActive`). */
  private val work: ActiveWork = ActiveWork.DIRECT,
  /** Fase 4B (P16): il lavoro delle operazioni lunghe (UIDT) e della ripresa dal diario. */
  private val opsJobs: NasOpsJobs = NasOpsJobs.NONE,
  /** Fase 4B (P16): un'operazione sui file si e' chiusa (la Libreria va riverificata). */
  private val onOperationsClosed: () -> Unit = {},
) {

  val enabled: Boolean get() = inputs.flags.nasLibrary

  // --- Archivi locali (nessun I/O qui) -------------------------------------------------------------------------------

  private val noBackup: File = context.noBackupFilesDir
  private val publishStore = RoomIndexPublishStore(database)
  private val outboxBlobs = FileOutboxBlobStore(File(noBackup, OUTBOX_DIR))
  private val replicaStore = RoomReplicaStore(database)
  private val packs = PackDirectory(File(File(noBackup, REPLICA_DIR), PACKS_DIR))
  val provisional: ReplicaProvisionalThumbnails = ReplicaProvisionalThumbnails(RoomProvisionalStore(database))
  private val attempts = FilePrepAttempts(File(noBackup, ATTEMPTS_FILE))

  /** "Ferma le scritture sul NAS" di Diagnostica (§1.4): l'emergenza dell'utente, che puo' solo restringere (D31). */
  val emergency: EmergencyStop = EmergencyStop(File(noBackup, EMERGENCY_FILE))

  /**
   * D32 oltre la vita del processo (R7 della revisione 4A): ogni ancora dell'ora del NAS si confronta con l'ultima salvata,
   * anche di un processo di prima; un salto oltre 5 minuti sospende prenotazioni e indice finche' l'utente non accetta l'ora
   * ([acceptClockJump]) o per 24 ore di ora stabile. Il file sta sotto `replica/`, quindi l'azzeramento lo toglie con i pacchi.
   */
  private val clockStore = FileClockJumpStore(File(File(noBackup, REPLICA_DIR), CLOCK_FILE))
  val clockGuard: ClockJumpGuard = ClockJumpGuard(clockStore)

  /** Le conferme "dati mobili" per media e per sessione (E-V2): una per l'app, condivisa da foto, video e "Scarica". */
  val consent: MobileDataConsent = MobileDataConsent()
  val link: NasLink = NasLink { inputs.metered() }
  val counters: IndexCounters = IndexCounters()
  /** Il lettore dei media della sessione (uno per processo, come la sessione). */
  private val reader: NasMediaReader by lazy { inputs.session.mediaReader() }
  private val phoneWriter: PhoneMediaWriter by lazy { writer() }

  // --- Il motore, quando il NAS c'e' (start) ------------------------------------------------------------------------

  /** Cio' che nasce in [start]: tutto legato a un account, a una chiave del dispositivo e alle aree scelte. */
  inner class Runtime internal constructor(
    val account: AccountId,
    val deviceId: String,
    val areas: List<NasShareArea>,
    val replica: LocalReplica,
    val quality: ReplicaQualityController,
    val sync: IndexSyncEngine,
    val publisher: OutboxIndexPublisher,
    val remote: NasRemoteIndex,
    val workSource: CatalogWorkSource,
    val workUnits: LeasedWorkUnits,
    val preparer: DefaultPreparer,
    val scanners: Map<String, DefaultNasScanner>,
  ) {
    fun scannerOf(share: String): DefaultNasScanner? = scanners[share.lowercase(Locale.ROOT)]
  }

  private val runtimeFlow = MutableStateFlow<Runtime?>(null)

  /** Il motore se e' partito (null prima di [start], senza NAS configurato, dopo [stop]). */
  val runtime: Runtime? get() = runtimeFlow.value
  val runtimeState: StateFlow<Runtime?> = runtimeFlow.asStateFlow()

  private val stopped = AtomicBoolean(false)
  private val startLock = Mutex()
  private val runLock = Mutex()
  private var watchers: Job? = null

  /** Il rilevamento fuori casa per il prossimo giro (D22): lo decide la tappa dalla rete su cui le regole dicono Run. */
  @Volatile private var detectionCap: Long? = null

  /** "Mostra le copertine degli album" (E-N14): spento di default, salvato come file (sopravvive al riavvio). */
  private val covers = FileSwitch(File(noBackup, COVERS_FILE))

  /** L'interruttore delle copertine, per le Impostazioni. */
  val albumCovers: StateFlow<Boolean> get() = covers.flow

  /**
   * Vale dalla prossima verifica: le cartelle di musica si rileggono con la scansione seguente (E-N14). Dalla 4B e' un'impostazione
   * personale sincronizzata fra i dispositivi dell'account (Q9): l'interruttore locale resta come specchio (e per prima del NAS).
   */
  var showAlbumCovers: Boolean
    get() = covers.on
    set(value) {
      covers.set(value)
      organizer.setShowAlbumCovers(value)
    }

  /** "Anteprime del NAS" nelle Impostazioni (feature-settings, `PreviewQualitySettings`). */
  val previewQuality: NasPreviewQuality by lazy { NasPreviewQuality(this, scope) }

  // --- Facciate per la Libreria e il viewer (rispondono anche prima di start) ---------------------------------------

  /** I blob della replica (D15): nessuno finche' la replica non e' aperta. Chiamate bloccanti e veloci. */
  val blobs: ReplicaBlobs = object : ReplicaBlobs {
    override fun locate(contentKey: Long, cls: dev.pampa.mycloudgallery.core.trust.data.SegmentClass): BlobLocation? =
      runtimeFlow.value?.replica?.locate(contentKey, cls)

    override fun locate(contentKey: Long, cls: dev.pampa.mycloudgallery.core.trust.data.SegmentClass, scope: BlobScope): BlobLocation? =
      runtimeFlow.value?.replica?.locate(contentKey, cls, scope)

    override fun read(location: BlobLocation): ByteBuffer = runtimeFlow.value?.replica?.read(location) ?: throw IOException("replica chiusa")

    override suspend fun estimate(quality: QualityLevel): SpaceEstimate = runtimeFlow.value?.replica?.estimate(quality) ?: SpaceEstimate(0, 0)
  }

  /** I record della replica per la scansione e le unita' di lavoro (D6). */
  private val records: ReplicaRecords = object : ReplicaRecords {
    override suspend fun matching(share: String, stamps: Map<SharePath, dev.pampa.mycloudgallery.core.model.NasStamp>) =
      runtimeFlow.value?.replica?.matching(share, stamps).orEmpty()
  }

  /** Dove sta sul NAS un media (`MediaRef.Nas.locationId` → share e percorso), dal catalogo: mai dalla UI (§1.2). */
  val locations: CatalogNasLocations = CatalogNasLocations(catalogProvider) { runtimeFlow.value?.account }

  /** Le miniature del NAS (P7): replica → provvisoria EXIF → originale solo per il contenuto visibile, a casa (D17). */
  val thumbnails: ThumbnailSource = NasThumbnailSource(
    blobs = blobs,
    provisional = provisional,
    interactive = NasMediaOriginalThumbnails(
      reader = reader,
      locations = locations,
      allowed = { foreground() && !link.isMetered() && runtimeFlow.value != null },
    ),
    // R1 (revisione 4A): le miniature solo dalle share del media (la sua per prima) e con la chiave intera del catalogo.
    scopes = locations,
  )

  /** Le foto del NAS nel viewer (P8, D19): HQ della replica, poi l'originale a intervalli con lo zoom. */
  val imageOrigin: NasImageOrigin = NasImageOrigin(
    reader = reader,
    locator = locations,
    store = NasOriginalStore(File(context.cacheDir, ORIGINALS_DIR)),
    replica = blobs,
    link = link,
    consent = consent,
    scopes = locations,
  )

  /** I video del NAS in streaming (D19). */
  val video: NasVideoSource by lazy { NasVideoSource(reader, locations, link, consent) }

  /** "Scarica sul telefono" (D20, E-L2): collegato al catalogo **prima** che il file diventi visibile. */
  val downloader: NasDownloader by lazy {
    // R8 (revisione 4A): il file aperto deve avere il timbro del catalogo e i byte letti l'impronta rapida della versione;
    // il collegamento rifiuta una copia con un hash completo diverso da quello noto.
    NasDownloader(scope, CatalogCheckedReader(reader, locations), locations, phoneWriter, CatalogCopyLinker(catalogProvider), link, consent)
  }

  // --- Cancelli -------------------------------------------------------------------------------------------------------

  /**
   * Il cancello delle scritture sull'indice (P3, `IndexWriteGate`): D31 lo applica `IndexWriteRules` prima di questo.
   * Ordine: emergenza, dispositivo non piu' fidato, congelamento della share (D10), salto dell'ora del NAS (D32).
   */
  val writeGate: IndexWriteGate = IndexWriteGate { share ->
    when {
      emergency.stopped -> WriteBlock.EMERGENCY_STOP
      inputs.trust.writePermit() == null -> WriteBlock.NOT_TRUSTED
      frozenShares.value.containsKey(share.lowercase(Locale.ROOT)) -> WriteBlock.FROZEN
      clockGuard.blocked -> WriteBlock.CLOCK_JUMP
      else -> null
    }
  }

  /** Prima di una sincronizzazione (§2.2): fiducia e percorso. Le regole le applica la tappa (`StagedRun`). */
  private val syncGate: SyncGate = SyncGate {
    when {
      inputs.route.current() is NasRoute.Denied -> SyncBlock.ROUTE_CLOSED
      inputs.trust.preflight(Purpose.BACKGROUND).let { it !is PreflightResult.Proceed && it !is PreflightResult.ReadOnly } -> SyncBlock.NOT_TRUSTED
      else -> null
    }
  }

  /** Le ancore dell'ora del NAS alla guardia del salto (R7), con il riferimento del telefono di quell'istante. */
  private suspend fun watchClock() {
    inputs.nasClock.anchors.collect { anchor ->
      withContext(Dispatchers.IO) {
        val elapsedNow = android.os.SystemClock.elapsedRealtime()
        // L'ora del telefono serve solo da metro largo fra due avvii (mai per decidere sui file): quella dell'istante dell'ancora.
        val wallAtAnchor = System.currentTimeMillis() - (elapsedNow - anchor.monotonicMs)
        val boot = runCatching {
          android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
        clockGuard.onAnchor(anchor.nasMs, ClockJumpGuard.Reference(anchor.monotonicMs, boot.takeIf { it >= 0 }?.toLong(), wallAtAnchor))
      }
    }
  }

  init {
    scope.launch { runCatching { watchClock() } }
  }

  // --- Stato: congelamento, Libreria ---------------------------------------------------------------------------------

  private val frozenShares = MutableStateFlow<Map<String, Freeze>>(emptyMap())

  /** Le share congelate adesso (D10), per chiave: Libreria, Diagnostica, riepilogo giornaliero. */
  val frozen: StateFlow<Map<String, Freeze>> = frozenShares.asStateFlow()

  /**
   * Il cancello delle operazioni sui file (4B, §2.7): emergenza, congelamento della share, salto dell'ora. Le operazioni dell'utente si
   * fermano con i primi due; le funzioni a scadenza (pulizie, compattazione) anche con il terzo (`GateStatus.blocksScheduled`).
   */
  val opsGate: OpsGate = OpsGate { share ->
    NasOpsGates.statusOf(
      emergency = emergency.stopped,
      frozen = frozenShares.value.containsKey(share.lowercase(Locale.ROOT)),
      clockJump = clockGuard.blocked,
    )
  }

  /** Organizzare il NAS (Fase 4B, P16): operazioni, cestino, spazio, cartelle, manutenzione dell'indice, dati personali. */
  val organizer: NasOrganizer = NasOrganizer(
    scope = scope,
    database = database,
    inputs = inputs,
    noBackup = noBackup,
    catalogProvider = catalogProvider,
    monotonicMs = monotonicMs,
    gate = opsGate,
    clockBlocked = { clockGuard.blocked },
    // R1 (revisione 4B): dopo un salto dell'ora accettato, il cestino del NAS non conta da prima.
    clockTrustedSince = { clockStore.trustedSinceNasMs() },
    // F20: solo la build debug, solo con il marcatore scritto da adb, accorcia i 30 giorni del cestino (assente dal dex di release).
    trashRetentionMillis = { DeveloperTools.trashRetentionMillis(context) },
    work = work,
    jobs = opsJobs,
    foreground = foreground,
    onOperationsClosed = onOperationsClosed,
    areaLabel = { area ->
      context.getString(if (area == NasArea.PRIVATE) NasScreensR.string.nas_area_private else NasScreensR.string.nas_area_common)
    },
    localCovers = { covers.on },
    mirrorCovers = { on -> if (covers.on != on) covers.set(on) },
  )

  private val composer = LibraryNasStatusComposer { monotonicMs() }

  /** Il piede della Libreria (§1.5, `LibraryTabState.nas`): cosa dire e quando, mai l'aspetto. */
  val libraryStatus: StateFlow<LibraryNasStatus> = runtimeFlow.flatMapLatest { rt ->
    if (rt == null) {
      flowOf(LibraryNasStatus.Idle)
    } else {
      val scans = rt.areas.mapNotNull { area -> rt.scannerOf(area.share)?.status }
      val scanFlow = if (scans.isEmpty()) flowOf(emptyList()) else combine(scans) { it.toList() }
      combine(scanFlow, frozenShares, rt.sync.status) { statuses, frozen, sync -> composer.compose(inputsOf(rt, statuses, frozen, sync)) }
    }
  }.stateIn(scope, SharingStarted.Eagerly, LibraryNasStatus.Idle)

  /** La qualita' come `ReplicaQuality` per la Diagnostica dell'indice (`IndexDiagnostics`): quella del motore, se c'e'. */
  val qualityControl: ReplicaQuality = object : ReplicaQuality {
    override val state: StateFlow<ReplicaQualityState>
      get() = runtimeFlow.value?.quality?.state ?: MutableStateFlow(ReplicaQualityState(QualityLevel.DEFAULT, emptyMap(), 0, converging = false))

    override suspend fun set(level: QualityLevel) = setQuality(level)
  }

  /** Il salto dell'ora del NAS che sospende le scritture (ms), per la Diagnostica (D32, R7): salvato, vale fra i processi. */
  fun clockJumpMs(): Long? = clockGuard.jump.value

  /**
   * "L'ora del NAS e' giusta, continua" (D32, R7): il gesto dell'utente che toglie la sospensione. Viene dalla UI (Diagnostica),
   * mai da un dato del NAS. Falso se non c'era un salto.
   */
  fun acceptClockJump(): Boolean = clockGuard.accept()

  /** La qualita' delle anteprime (Impostazioni, E-L6): null finche' il NAS non c'e'. */
  val quality: StateFlow<ReplicaQualityState?> = runtimeFlow.flatMapLatest { rt -> rt?.quality?.state ?: flowOf(null) }
    .stateIn(scope, SharingStarted.Eagerly, null)

  private fun inputsOf(rt: Runtime, statuses: List<NasScanStatus>, frozen: Map<String, Freeze>, sync: IndexSyncStatus): NasStatusInputs {
    val unavailableSync = sync is IndexSyncStatus.Unavailable
    val areas = rt.areas.mapIndexed { i, area ->
      val status = statuses.getOrNull(i)
      val scan = when (status) {
        is NasScanStatus.Scanning -> if (status.mode == ScanMode.Full) ScanProgress.First(status.filesSeen, status.filesTotal) else ScanProgress.Verifying
        else -> ScanProgress.Idle
      }
      val freeze = frozen[area.share.lowercase(Locale.ROOT)] ?: (status as? NasScanStatus.Frozen)?.freeze
      NasAreaProgress(
        area = area.area,
        scan = scan,
        frozen = freeze?.let { FrozenInfo(it.reason, it.sinceNasMillis) },
        unavailable = status is NasScanStatus.Unavailable || unavailableSync,
      )
    }
    val syncProgress = when (sync) {
      is IndexSyncStatus.Aligning -> SyncProgress.Aligning(sync.bytesDone, sync.bytesTotal)
      is IndexSyncStatus.UpToDate -> SyncProgress.UpToDate(sync.atNasMillis)
      else -> SyncProgress.Idle
    }
    val lastVerified = statuses.mapNotNull { (it as? NasScanStatus.Finished)?.report }.takeIf { it.isNotEmpty() }?.let { inputs.nasClock.nowMillisOrNull() }
    return NasStatusInputs(
      configured = true,
      areas = areas,
      sync = syncProgress,
      localOnly = localOnly(rt.areas),
      lastVerifiedNasMillis = lastVerified,
    )
  }

  /** D31: con la pubblicazione solo sulle share di prova, una share vera lavora in solo locale. */
  fun localOnly(areas: List<NasShareArea> = runtimeFlow.value?.areas.orEmpty()): Boolean =
    inputs.flags.indexPublishTestSharesOnly && areas.any { !TestShares.isTestShare(it.share) }

  // --- Vita ------------------------------------------------------------------------------------------------------------

  /**
   * Fa partire il motore se il NAS e' configurato (famiglia pronta, account, chiave del dispositivo, almeno un'area) e il
   * catalogo c'e'. Idempotente; vero se il motore c'e'. Apre la replica (indice dei blob in memoria), rilegge la qualita',
   * ripassa alla replica cio' che l'outbox non le aveva consegnato (morte del processo), legge i congelamenti salvati e
   * toglie i temporanei orfani di uno "Scarica" interrotto.
   */
  suspend fun start(): Boolean = startLock.withLock {
    if (stopped.get() || !enabled) return@withLock false
    runtimeFlow.value?.let { return@withLock true }
    val user = inputs.account() ?: return@withLock false
    val deviceId = inputs.deviceId() ?: return@withLock false
    val signer = inputs.signer() ?: return@withLock false
    val areas = inputs.shares().filter { isShareName(it.share) }.distinctBy { it.share.lowercase(Locale.ROOT) }
    if (areas.isEmpty()) return@withLock false
    val catalog = catalogProvider() ?: return@withLock false
    val account = AccountId.of(user)
    val names = areas.associate { it.share.lowercase(Locale.ROOT) to it.share }
    val shareName: (String) -> String = { key -> names[key.lowercase(Locale.ROOT)] ?: key }

    val replica = LocalReplica(
      replicaStore,
      packs,
      CatalogReplicaPort(catalog, { account }, shareName) { keys -> keys.forEach { provisional.forget(it) } },
      deviceId,
    )
    val quality = ReplicaQualityController(replicaStore, replica)
    val remote = NasRemoteIndex({ share -> inputs.session.fileSystem(share) }, { inputs.registry() }, user)
    val sync = IndexSyncEngine(
      remote = remote,
      replica = replica,
      store = replicaStore,
      gate = syncGate,
      standings = { inputs.standing(it) },
      nasClock = inputs.nasClock,
      quality = quality,
      counters = counters,
    )
    val identity = IndexIdentity(signer, user) { inputs.registry() }
    val rules = IndexWriteRules(inputs.flags, writeGate)
    // Un lucchetto per share condiviso dalla pubblicazione e dalla compattazione (4B): mai due teste proprie scritte insieme.
    val locks = ShareLocks()
    val publisher = OutboxIndexPublisher(
      identity = identity,
      clock = inputs.nasClock,
      fileSystem = { share -> inputs.session.fileSystem(share) },
      store = publishStore,
      blobStore = outboxBlobs,
      catalog = CatalogShareFiles({ catalog }, { account }),
      rules = rules,
      sink = replica,
      locks = locks,
    )
    val workSource = CatalogWorkSource({ catalog }, { account }, records, { attempts.gaveUp(it) }, monotonicMs)
    val workUnits = LeasedWorkUnits(
      identity = identity,
      source = workSource,
      // R5 (revisione 4A): le classi contano solo con la miniatura S davvero pubblicata (dalla replica, o dalla tabella).
      published = RemotePublishedClasses(
        remote,
        startRound = remote::startRound,
        localBlobKeys = { _, _, segment, cls -> replica.blobKeysOfSegment(segment, cls) },
      ),
      leases = TrustWorkLeases({ share -> inputs.session.fileSystem(share) }, signer, inputs.nasClock, { inputs.registry() }),
      rules = rules,
      unpublished = publisher::hasPending,
      ownDecoderFamily = dev.pampa.mycloudgallery.core.prepare.codec.DecoderFamily.ofDevice(),
    )
    val preparer = DefaultPreparer(prepBudget(), File(context.cacheDir, PREP_DIR))
    val store = MediaCatalogScanStore(catalog)
    val detector = NasDetector(extractor())
    val scanners = areas.associate { area ->
      area.share.lowercase(Locale.ROOT) to DefaultNasScanner(
        account = account,
        share = area.share,
        reader = reader,
        store = store,
        detector = detector,
        replica = records,
        provisional = provisional,
        clock = inputs.nasClock,
        settings = { settingsFor(area) },
        monotonicMs = monotonicMs,
        // R8 (revisione 4B): cio' che l'app stessa ha tolto dalla share (diario locale) non fa scattare il congelamento.
        ownRemovals = organizer.ownRemovals,
      )
    }
    val rt = Runtime(account, deviceId, areas, replica, quality, sync, publisher, remote, workSource, workUnits, preparer, scanners)
    try {
      replica.open()
      quality.load()
      publisher.recover()
      // R2 (revisione 4A): niente outbox per le share non piu' configurate o non pubblicabili (D31, sola lettura).
      runCatching { publisher.purgeOutbox(areas.map { it.share }.toSet()) }
      scanners.values.forEach { it.freezeState.load() }
      runCatching { preparer.sweepStale() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // I5 (revisione 4A): solo la classe dell'eccezione, anche in debug (i messaggi di smbj possono avere percorsi).
      GalleryLog.w(TAG) { "NAS della Libreria non avviato: ${e.javaClass.simpleName}" }
      return@withLock false
    }
    if (stopped.get()) return@withLock false
    runtimeFlow.value = rt
    watchers = scope.launch { watchFreezes(rt) }
    // Fase 4B (P16): operazioni, cestino, spazio, cartelle, manutenzione e dati personali. Se non parte, la Libreria del NAS resta.
    try {
      organizer.attach(rt, user, identity, rules, remote, publishStore, replicaStore, locks, catalog, CatalogKnownHashes(locations))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "organizzazione del NAS non avviata: ${e.javaClass.simpleName}" }
    }
    scope.launch { runCatching { (phoneWriter as? dev.pampa.mycloudgallery.core.mediastore.write.MediaStorePhoneMediaWriter)?.purgeOrphans() } }
    true
  }

  /** Le esclusioni di un'area (D11): quelle firmate dell'area comune; le cartelle di sistema le esclude sempre lo scanner. */
  private fun settingsFor(area: NasShareArea): ScanSettings = ScanSettings(
    // Area comune: la configurazione firmata (D11). Area privata: l'impostazione personale sincronizzata (4B, E-N15, Q9).
    excluded = if (area.area == NasArea.COMMON) {
      inputs.commonExclusions().mapNotNull { runCatching { SharePath.parseMediaPath(it) }.getOrNull() }
    } else {
      organizer.privateExclusions()
    },
    showAlbumCovers = showAlbumCovers,
    detectionByteCap = detectionCap,
  )

  private suspend fun watchFreezes(rt: Runtime) {
    val flows = rt.scanners.map { (key, scanner) -> combine(flowOf(key), scanner.freezeState.frozen) { k, f -> k to f } }
    if (flows.isEmpty()) return
    combine(flows) { pairs -> pairs.mapNotNull { (k, f) -> f?.let { k to it } }.toMap() }.collect { map ->
      frozenShares.value = map
      inputs.freezeSignal.update(map.values.map { it.reason })
    }
  }

  /**
   * Ferma il motore per sempre in questo processo (l'azzeramento, R7): niente parte piu', le facciate rispondono "niente".
   * I lavori in corso li ferma e li aspetta la galleria (sono suoi figli); qui si toglie solo il motore.
   */
  fun stop() {
    stopped.set(true)
    // 4B: niente piu' operazioni ne' lavori in coda; quelle in corso le ferma la galleria (sono figlie del suo scope).
    organizer.stop()
    watchers?.cancel()
    runtimeFlow.value = null
    frozenShares.value = emptyMap()
    inputs.freezeSignal.update(emptyList())
  }

  // --- Lavori ----------------------------------------------------------------------------------------------------------

  /**
   * Un giro di scansione di tutte le aree (D9), in fila, dentro [budget]. [detectionByteCap] e' il tetto del rilevamento
   * di questa tappa (D22: 50 MB fuori casa). Null se il NAS non c'e'.
   */
  suspend fun scan(mode: ScanMode, budget: Duration, detectionByteCap: Long? = null): List<ScanReport>? = runLock.withLock {
    val rt = runtimeFlow.value ?: return@withLock null
    detectionCap = detectionByteCap
    val started = monotonicMs()
    val reports = ArrayList<ScanReport>()
    for (area in rt.areas) {
      val left = budget - (monotonicMs() - started).milliseconds
      if (!left.isPositive()) break
      val scanner = rt.scannerOf(area.share) ?: continue
      reports += scanner.scan(mode, left)
    }
    lastScan.value = reports
    lastScanAtMs = monotonicMs()
    rt.workSource.invalidate()
    // 4B (§2.9): le voci personali per media appena entrati nel catalogo si proiettano adesso.
    organizer.reproject()
    reports
  }

  @Volatile private var lastScanAtMs: Long? = null

  /** Vero se l'ultima verifica (in questo processo) ha piu' di 15 minuti, o non c'e' stata: si verifica all'apertura (§1.4). */
  fun verifyDue(): Boolean {
    if (runtimeFlow.value == null && !enabled) return false
    val last = lastScanAtMs ?: return true
    return monotonicMs() - last > VERIFY_EVERY_MS
  }

  /** L'ultimo giro di scansione di ogni area (Diagnostica). */
  val lastScan = MutableStateFlow<List<ScanReport>>(emptyList())

  /** L'ultimo giro di preparazione (Diagnostica). */
  val lastPrep = MutableStateFlow<RunResult?>(null)

  /**
   * Un giro di sincronizzazione dell'indice (§2.2) delle aree, con le classi della qualita' scelta (D16); [visibleOnly]
   * sui dati mobili (D23). Dopo: la convergenza della qualita' e, se sono arrivate miniature, la sostituzione delle
   * provvisorie nella griglia.
   */
  suspend fun syncIndex(budget: Duration, visibleOnly: Boolean = false): SyncReport? = runLock.withLock {
    val rt = runtimeFlow.value ?: return@withLock null
    val report = rt.sync.sync(SyncScope(rt.areas.map { it.share }.toSet(), rt.quality.syncClasses(), visibleOnly), budget)
    runCatching { rt.quality.converge() }
    rt.workSource.invalidate()
    if (report.segmentsDownloaded > 0 || report.recordsApplied > 0) {
      onReplicaChanged()
      // 4B (§2.9): identita' adottate dalla replica (D7) possono sbloccare voci personali in attesa.
      organizer.reproject()
    }
    report
  }

  /**
   * Un giro di preparazione (§2.4) delle aree, in fila, dentro [budget]: unita' di lavoro con prenotazione, pubblicazione
   * secondo le regole (D31: sulle share vere solo locale). [priority] = le chiavi sullo schermo (D12).
   */
  suspend fun prepare(budget: Duration, control: PrepControl, priority: UnitPriority = NoUnitPriority): RunResult? = runLock.withLock {
    val rt = runtimeFlow.value ?: return@withLock null
    val run = DefaultPreparationRun(
      workUnits = rt.workUnits,
      inputs = NasPrepInputs { reader },
      preparer = rt.preparer,
      publisher = rt.publisher,
      records = CatalogPrepRecords(catalogProvider) { rt.account },
      limits = prepBudget(),
      decoderFamily = dev.pampa.mycloudgallery.core.prepare.codec.DecoderFamily.ofDevice(),
      attempts = attempts,
      control = control,
      // R2 (revisione 4A): sotto 1 GiB libero sul telefono la preparazione aspetta.
      storage = PrepStorage { noBackup.usableSpace },
    )
    val started = monotonicMs()
    var total = RunResult(0, 0, 0, 0, 0, null)
    for (area in rt.areas) {
      val left = budget - (monotonicMs() - started).milliseconds
      if (!left.isPositive()) {
        total = total.copy(stoppedBy = PrepStop.BUDGET)
        break
      }
      val result = run.run(area.share, left, priority)
      total = RunResult(
        units = total.units + result.units,
        prepared = total.prepared + result.prepared,
        failed = total.failed + result.failed,
        handedOver = total.handedOver + result.handedOver,
        bytesRead = total.bytesRead + result.bytesRead,
        stoppedBy = result.stoppedBy.takeUnless { it == PrepStop.NO_WORK } ?: total.stoppedBy,
      )
      if (result.stoppedBy == PrepStop.BUDGET || result.stoppedBy == PrepStop.RULES || result.stoppedBy == PrepStop.UNAVAILABLE ||
        result.stoppedBy == PrepStop.STORAGE
      ) {
        break
      }
    }
    if (total.prepared > 0) onReplicaChanged()
    lastPrep.value = total
    total
  }

  /** Pubblica cio' che aspetta nell'outbox (fine giro, periodico): nelle share di prova, mai sulle vere (D31). */
  suspend fun flushOutbox() {
    val rt = runtimeFlow.value ?: return
    for (area in rt.areas) runCatching { rt.publisher.flush(area.share, dev.pampa.mycloudgallery.core.index.contract.FlushReason.PERIODIC) }
  }

  /** "Qualita' delle anteprime" (Impostazioni, E-L6). */
  suspend fun setQuality(level: QualityLevel) {
    runtimeFlow.value?.quality?.set(level)
  }

  /**
   * "Ho controllato, continua" (D10): scongela la share [share] solo con il conteggio che l'utente ha visto. Il gesto viene
   * dalla UI (Diagnostica), mai da un dato del NAS.
   */
  suspend fun release(share: String, gesture: UserGesture): Boolean =
    runtimeFlow.value?.scannerOf(share)?.freezeState?.release(gesture) ?: false

  private fun prepBudget(): PrepBudget {
    val memory = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB
    return PrepBudget(memoryClassMb = memory.coerceAtLeast(MIN_MEMORY_CLASS_MB), cores = java.lang.Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
  }

  private fun isShareName(share: String): Boolean = share.isNotBlank() && share.none { it == '/' || it == '\\' || it.code < 0x20 }

  companion object {
    private const val TAG = "NasGraph"
    const val OUTBOX_DIR = "index-outbox"
    const val REPLICA_DIR = "replica"
    const val PACKS_DIR = "packs"
    const val ATTEMPTS_FILE = "nas-prep-attempts"
    const val EMERGENCY_FILE = "nas-writes-stopped"
    const val COVERS_FILE = "nas-album-covers"
    const val ORIGINALS_DIR = "nas-originals"
    const val PREP_DIR = "prep"

    /** R7: l'ultima ancora dell'ora del NAS e il salto in corso, sotto `replica/` (l'azzeramento la toglie). */
    const val CLOCK_FILE = "nas-clock"
    private const val VERIFY_EVERY_MS = 15 * 60 * 1000L
    private const val DEFAULT_MEMORY_CLASS_MB = 256
    private const val MIN_MEMORY_CLASS_MB = 64
  }
}

/** Nessuna chiave sullo schermo (i lavori in background). */
object NoUnitPriority : UnitPriority {
  override val visible: StateFlow<Set<Long>> = MutableStateFlow(emptySet())
}

/**
 * Dove sta sul NAS un media (`MediaRef.Nas.locationId`), dal catalogo: per le miniature dall'originale (P7,
 * [NasLocationLookup]) e per il viewer e "Scarica" (P8, [NasMediaLocator]). Il percorso non esce mai di qui verso la UI
 * (§1.2): passa solo a `NasMediaReader`. Null = la posizione non c'e' piu' (sparita, azzerata, E-V6).
 */
class CatalogNasLocations(
  private val catalog: suspend () -> MediaCatalog?,
  /** L'account del NAS di questo dispositivo (per trovare un posto dal percorso, R8). */
  private val account: () -> AccountId? = { null },
) : NasLocationLookup, NasMediaLocator, NasBlobScopes {

  override suspend fun locate(locationId: Long): NasFileLocation? {
    val nas = catalog()?.nasLocation(LocationId(locationId)) ?: return null
    val path = runCatching { SharePath.parseMediaPath(nas.path.value) }.getOrNull() ?: return null
    return NasFileLocation(nas.share, path)
  }

  override suspend fun locate(ref: MediaRef.Nas): NasMediaLocation? {
    val c = catalog() ?: return null
    val nas = c.nasLocation(LocationId(ref.locationId)) ?: return null
    val info = c.nasLocationAt(nas) ?: return null
    if (info.state == dev.pampa.mycloudgallery.core.model.LocationState.ABSENT) return null
    val path = runCatching { SharePath.parseMediaPath(nas.path.value) }.getOrNull() ?: return null
    // R8 (revisione 4A): l'hash completo della versione, se il catalogo lo conosce (calcolato qui o con `MergeRules`, mai
    // dall'indice): lo "Scarica" lo confronta con i byte arrivati.
    val version = c.mediaIdOfLocation(LocationId(ref.locationId))?.let { c.media(it) }
      ?.let { media -> media.locations.firstOrNull { it.id.value == ref.locationId }?.let { media.version(it.versionId) } }
    return NasMediaLocation(
      share = nas.share,
      path = path,
      sizeBytes = info.stamp.size,
      stamp = info.stamp,
      knownSha256 = version?.fingerprint?.fullHash,
    )
  }

  /** R8: il timbro e l'impronta rapida che il catalogo conosce per il posto ([share], [path]), per controllare un download. */
  suspend fun expected(share: String, path: SharePath): CatalogExpectation? {
    val c = catalog() ?: return null
    val nasPath = runCatching { NasPath.of(path.value) }.getOrNull() ?: return null
    val owner = account() ?: return null
    val info = c.nasLocationAt(Location.Nas(owner, share, nasPath)) ?: return null
    if (info.state == dev.pampa.mycloudgallery.core.model.LocationState.ABSENT) return null
    val media = c.mediaIdOfLocation(info.locationId)?.let { c.media(it) } ?: return null
    val place = media.locations.firstOrNull { it.id == info.locationId } ?: return null
    val fingerprint = media.version(place.versionId).fingerprint
    return CatalogExpectation(info.stamp, fingerprint.quickHash, fingerprint.fullHash)
  }

  /**
   * R1 (revisione 4A): dove cercare le anteprime di [ref]: la share della posizione per prima, poi quelle delle altre posizioni
   * del NAS dello stesso media, e la chiave intera della versione se i suoi primi 64 bit sono quelli di [ref].
   */
  override suspend fun scopeOf(ref: MediaRef.Nas): BlobScope? {
    val c = catalog() ?: return null
    val nas = c.nasLocation(LocationId(ref.locationId)) ?: return null
    val media = c.mediaIdOfLocation(LocationId(ref.locationId))?.let { c.media(it) }
    val shares = listOf(nas.share) + media?.locations.orEmpty().mapNotNull { (it.location as? Location.Nas)?.share }
    val fingerprint = media?.locations?.firstOrNull { it.id.value == ref.locationId }?.let { media.version(it.versionId).fingerprint }
    val full = fingerprint?.let { runCatching { ContentKey.of(it.quickHash, it.size) }.getOrNull() }?.takeIf { it.short == ref.contentKey }
    return BlobScope(shares, full)
  }
}

/** Cio' che il catalogo sa di un file del NAS per controllare uno "Scarica" (R8): timbro, impronta rapida, hash completo. */
class CatalogExpectation(
  val stamp: dev.pampa.mycloudgallery.core.model.NasStamp,
  val quickHash: dev.pampa.mycloudgallery.core.model.QuickHash,
  val fullHash: dev.pampa.mycloudgallery.core.model.Sha256?,
)

/**
 * Il collegamento della copia scaricata al media del NAS (D20, E-L2): `attachCopy` con la posizione che il MediaStore ha
 * scritto e la dimensione, **prima** che la riga diventi visibile. La lettura del telefono trovera' un posto noto con gli
 * stessi byte (`SameFile`): due posizioni, nessun doppione.
 */
class CatalogCopyLinker(private val catalog: suspend () -> MediaCatalog?) : CopyLinker {
  override suspend fun link(ref: MediaRef.Nas, copy: dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy) {
    val c = catalog() ?: throw IOException("catalogo non disponibile")
    val nas = c.nasLocation(LocationId(ref.locationId)) ?: throw IOException("posizione sparita")
    val media = c.media(c.mediaIdOfLocation(LocationId(ref.locationId)) ?: throw IOException("media sparito")) ?: throw IOException("media sparito")
    val place = media.locations.firstOrNull { it.location == nas || it.id.value == ref.locationId } ?: throw IOException("posizione sparita")
    // R8 (revisione 4A): una copia con un hash completo diverso da quello noto della versione non si lega (e non compare).
    val known = media.version(place.versionId).fingerprint.fullHash
    if (known != null && known != copy.sha256) throw IOException("copia diversa dalla versione")
    c.attachCopy(media.id, place.versionId, copy.location, FileStamp(copy.size, System.currentTimeMillis()), verifiedAtMillis = null)
  }
}

/**
 * L'emergenza "Ferma le scritture sul NAS" (Diagnostica, §1.4): un file vuoto in `noBackupFilesDir`, che sopravvive al
 * riavvio. Puo' solo restringere: con l'emergenza accesa nessuna testa, nessun segmento, nessuna prenotazione (D31 resta).
 */
class EmergencyStop(private val file: File) {
  @Volatile private var cached: Boolean? = null

  val stopped: Boolean
    get() = cached ?: file.exists().also { cached = it }

  fun set(on: Boolean) {
    try {
      if (on) {
        file.parentFile?.mkdirs()
        file.createNewFile()
      } else {
        file.delete()
      }
    } catch (e: IOException) {
      GalleryLog.w("EmergencyStop") { "emergenza non salvata" }
    }
    cached = on
  }
}
