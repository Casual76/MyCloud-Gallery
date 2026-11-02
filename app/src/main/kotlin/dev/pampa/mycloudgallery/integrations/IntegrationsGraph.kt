package dev.pampa.mycloudgallery.integrations

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.backup.IncomingStates
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.EditSessions
import dev.pampa.mycloudgallery.core.backup.contract.IncomingUploads
import dev.pampa.mycloudgallery.core.backup.noop.MonotonicOnlyBackupClock
import dev.pampa.mycloudgallery.core.backup.noop.NoEditSessions
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDragPorts
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDropPorts
import dev.pampa.mycloudgallery.core.imaging.ImagingPipeline
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.strip.AndroidHeicToJpeg
import dev.pampa.mycloudgallery.core.metadata.strip.DefaultLocationStripper
import dev.pampa.mycloudgallery.core.metadata.strip.LocationRechecks
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareProbe
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.drag.DocumentDragPayloads
import dev.pampa.mycloudgallery.feature.integrations.drag.DragCounters
import dev.pampa.mycloudgallery.feature.integrations.drag.DragLeases
import dev.pampa.mycloudgallery.feature.integrations.drag.DropHandoff
import dev.pampa.mycloudgallery.feature.integrations.drag.ForegroundTracker
import dev.pampa.mycloudgallery.feature.integrations.drag.HandlerDragPort
import dev.pampa.mycloudgallery.feature.integrations.drag.IncomingDrops
import dev.pampa.mycloudgallery.feature.integrations.drag.ToastDragNotices
import dev.pampa.mycloudgallery.feature.integrations.edit.DefaultEditSessions
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerPorts
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIdTable
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentsAuthority
import dev.pampa.mycloudgallery.feature.integrations.provider.FileDocumentIdKey
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import dev.pampa.mycloudgallery.feature.integrations.provider.PreparedCopies
import dev.pampa.mycloudgallery.feature.integrations.receive.DefaultIncomingUploads
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingStager
import dev.pampa.mycloudgallery.feature.integrations.receive.ReceivePorts
import dev.pampa.mycloudgallery.feature.integrations.share.ContextUriRevoker
import dev.pampa.mycloudgallery.feature.integrations.share.FileProviderUris
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.LocationFreeShare
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.feature.integrations.share.ShareHolder
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.nas.NasShareArea
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Le **integrazioni** nell'app (Fase 5B, P14; docs/fase5-progetto.md §2.7, D23–D26, D29, C17, E-X1…E-X8): il selettore e il provider dei
 * documenti (P10), la ricezione "Salva sul NAS" (P11), "Modifica con…" e "Condividi senza posizione" (P11), legati al grafo vero. Uno per
 * processo. I componenti d'ingresso li crea il **sistema** (un'altra app): leggono le porte da `IntegrationPorts`, `PickerPorts`,
 * `ReceivePorts`, che [install] riempie all'avvio del processo (`MyCloudGalleryApp.onCreate`, prima di qualunque richiesta).
 *
 * **Sicurezza.**
 * - Il cancello d'ingresso valuta il blocco dell'app **adesso** ([AppEntryGate]); le impostazioni del blocco si leggono a livello di processo
 *   ([install]), cosi' un'activity d'ingresso a processo freddo trova il blocco com'e' (prima, senza la `MainActivity`, restava a "da leggere").
 * - I componenti esportati non agiscono senza un tocco e un grant (lo fanno i moduli: qui nessuna scorciatoia).
 * - L'azzeramento e il cambio di famiglia revocano ogni id dei documenti ([revokeAll]); i contatori della Diagnostica sono solo codici.
 */
/** Lo spazio libero per i file in arrivo: quello della cartella, o della prima cartella che esiste sopra di lei. */
object IncomingSpace {
  fun usableBytes(root: File): Long = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L
}

class IntegrationsGraph(
  private val context: Context,
  private val gallery: GalleryGraph,
  private val scope: CoroutineScope,
  private val lock: AppLockController,
  private val lockSettings: Flow<AppLockSettings>,
  private val familyReady: () -> Boolean,
  private val shareProbe: ShareProbe?,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  /** "Rimuovi la posizione" (Impostazioni → Condivisioni). Senza l'impostazione legata la posizione si toglie: si fallisce chiusi. */
  private val removeLocation: StateFlow<Boolean> = MutableStateFlow(true),
  /** IN-3: la configurazione salvata si sta ancora leggendo (`SetupState.Loading`): il cancello d'ingresso aspetta invece di dire "non pronta". */
  private val familyLoading: () -> Boolean = { false },
  /** IN-3, IN-2: emette quando lo stato della famiglia cambia (per aspettare il caricamento e per notificare le radici ai selettori di documenti). */
  private val familyChanges: Flow<Unit> = emptyFlow(),
) {

  private val nasInputs = gallery.parts.nas
  private val backup get() = gallery.backup

  private fun account(): AccountId? = nasInputs?.account?.invoke()?.let(AccountId::of)

  private fun areas(): List<NasShareArea> = nasInputs?.shares?.invoke().orEmpty()

  private fun reader(): NasMediaReader? = nasInputs?.session?.mediaReader()

  private val catalog: suspend () -> dev.pampa.mycloudgallery.core.database.MediaCatalog? = { gallery.coreIfReady()?.catalog }

  val labels: IntegrationLabels = IntegrationLabels(
    privateArea = context.getString(R.string.integrations_area_private),
    commonArea = context.getString(R.string.integrations_area_common),
    received = context.getString(R.string.integrations_received_folder),
  )

  private val folders = CatalogNasFolders(gallery.database)

  /** L'ora per le sessioni e le conferme: quella del backup (monotona + NAS), o solo la monotona senza backup. */
  val clock: BackupClock get() = backup?.clock ?: MonotonicOnlyBackupClock

  // --- Ingresso ---------------------------------------------------------------------------------------------------------------------

  val entryGate: AppEntryGate = AppEntryGate(lock, familyReady, familyLoading, familyChanges)

  val unlock: AppEntryUnlock = AppEntryUnlock(lock)

  /** Il NAS risponde adesso? Percorso permesso, niente dati mobili senza consenso (D23, E-V2), una prova della share entro pochi secondi (E-X1). */
  suspend fun nasReach(): NasReach {
    val inputs = nasInputs ?: return NasReach.Unreachable(NasUnreachableReason.UNAVAILABLE)
    if (account() == null || areas().isEmpty()) return NasReach.Unreachable(NasUnreachableReason.UNAVAILABLE)
    if (inputs.route.current() !is NasRoute.Allowed) return NasReach.Unreachable(NasUnreachableReason.OFFLINE)
    if (inputs.metered()) return NasReach.Unreachable(NasUnreachableReason.METERED)
    val probe = shareProbe ?: return NasReach.Unreachable(NasUnreachableReason.UNAVAILABLE)
    // IN-13: non solo la prima area (se manca o rifiuta, il NAS che risponde con l'altra sembrava spento): basta che una risponda, nel tempo di una.
    val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) { areas().any { probe.probe(it.share) is NasResult.Ok } } == true
    return if (ok) NasReach.Reachable else NasReach.Unreachable(NasUnreachableReason.OFFLINE)
  }

  /** La Libreria per le altre app: la vista della Libreria (senza nascosti) e un caricatore delle miniature **suo**. */
  val library: AppIntegrationLibrary = AppIntegrationLibrary(
    items = gallery.viewerItems(LibraryFilter.ALL),
    thumbnails = ImagingPipeline.thumbnailLoader(gallery.thumbnailSource, gallery.imagingBudget, dispatchers),
    placeholders = gallery.placeholders,
    intents = gallery.intents,
    resolver = context.applicationContext.contentResolver,
    catalog = catalog,
    reader = ::reader,
    reach = ::nasReach,
    account = ::account,
    areas = ::areas,
    folders = folders,
    labels = labels,
  )

  // --- Ricevere (D24) -------------------------------------------------------------------------------------------------------------

  /** `noBackupFilesDir/incoming/`, la stessa cartella che il diario del backup cita. */
  private val incomingRoot: File = backup?.fiveB?.incomingRoot ?: File(context.noBackupFilesDir, "incoming")

  /**
   * La copia dei file in arrivo. Lo spazio libero si misura sulla cartella che c'e' (`incoming/` nasce alla prima copia: `usableSpace` di una
   * cartella che non esiste e' 0, e ogni prima condivisione sarebbe stata rifiutata per "spazio", trovato dalla prova d'integrazione).
   */
  val stager: IncomingStager = IncomingStager(incomingRoot, freeBytes = { IncomingSpace.usableBytes(incomingRoot) })

  /** "Salva sul NAS": la copia, poi la coda del diario del backup. Senza backup il null-object (niente si mette in coda). */
  val uploads: IncomingUploads? = backup?.let { b -> DefaultIncomingUploads(stager, b.fiveB.incomingQueue, b.clock) }

  val destinations: AppIncomingDestinations = AppIncomingDestinations(
    account = ::account,
    areas = ::areas,
    fenceRoot = { backup?.fiveB?.fenceRoot() },
    folders = folders,
    labels = labels,
    reach = ::nasReach,
    gate = backup?.fiveB?.incomingGate,
  )

  // --- Condividere e modificare (D25, D26) ----------------------------------------------------------------------------------------

  val shareCache: ShareCache = ShareCache(context.applicationContext.cacheDir)

  val uris: FileProviderUris = FileProviderUris(context.applicationContext)

  val revoker: ContextUriRevoker = ContextUriRevoker(context.applicationContext)

  /**
   * "Condividi senza posizione": copia, ricostruzione per elenco di cio' che si tiene, ricontrollo con il rilevatore indipendente e con i lettori
   * dell'app (estrattore, `ExifInterface`, `MediaMetadataRetriever`).
   */
  val share: LocationFreeShare = LocationFreeShare(
    cache = shareCache,
    source = AppShareSource(catalog, gallery.intents, context.applicationContext.contentResolver, ::reader),
    stripper = DefaultLocationStripper(
      rechecks = LocationRechecks.standard(gallery.newExtractor(), AndroidPreviewDecoder(), ZoneId.systemDefault()),
      heic = AndroidHeicToJpeg(),
    ),
    uris = uris,
    revoker = revoker,
  )

  /**
   * Il custode delle copie consegnate a un'altra app (MC-1): le condivisioni della Libreria e del viewer e, dalla Fase 6 (R18), il PNG di "Condividi" del
   * ritaglio del soggetto, che prima restava leggibile fino all'avvio dopo.
   */
  val shareHolder: ShareHolder = ShareHolder(scope)

  /**
   * Correzioni 5B (R4, MC-1, MC-2): ogni "Condividi" dell'app (viewer con e senza backup, selezione della Libreria) con l'impostazione, e il custode
   * che rilascia una copia consegnata solo quando l'altra app ha finito.
   */
  val shares: AppShares = AppShares(
    context = context.applicationContext,
    prepare = { ids, remove, progress -> share.prepare(ids, remove, progress) },
    removeLocation = removeLocation,
    holder = shareHolder,
    scope = scope,
  )

  /**
   * "Rimuovi la posizione" vale anche per il selettore e per il provider dei documenti (decisione dell'utente del 20/10/2026): con l'impostazione accesa
   * cio' che un'altra app riceve da qui e' una copia senza posizione, fatta con lo stesso stripper di "Condividi" ([share]) e tolta quando l'id scade o
   * si revoca. Se la posizione non si toglie, niente si consegna. Senza l'impostazione legata vale "togli" (fallire chiusi).
   */
  val preparedCopies: PreparedCopies = PreparedCopies(share, { removeLocation.value }, scope)

  /**
   * Il provider dei documenti e chi consegna i solo-NAS (id opachi, revoca). IN-2: gli id della radice e delle cartelle sono derivati con una chiave
   * locale su file (`noBackupFilesDir/integrations/doc-id.key`): restano uguali a ogni avvio del processo, cosi' DocumentsUI, che li tiene nella cache
   * di sistema, ritrova la radice dopo un riavvio. Ruotano all'azzeramento ([revokeAll]).
   */
  val documents: LibraryMediaDocuments = LibraryMediaDocuments(
    library,
    DocumentLabels.from(context),
    DocumentIdTable(idKey = FileDocumentIdKey(File(File(context.noBackupFilesDir, "integrations"), "doc-id.key"))),
    preparer = preparedCopies,
    // Fase 10 (I4 della revisione di sicurezza): la vista della Libreria si aggiorna dopo la calma; elenchi, descrizioni e aperture chiedono i nascosti anche al catalogo di adesso.
    hiddenMask = gallery.hiddenMask,
  )

  /**
   * Fase 10 (P6, D20, C436): il trascinamento verso altre app. Gli URI sono quelli del provider dei documenti ([documents], id opachi consegnati), la copia senza posizione e' quella del selettore
   * ([preparedCopies]), il cancello e' quello d'ingresso. I prestiti ([dragLeases]) tengono id e copie finche' l'app e' in primo piano piu' 10 minuti, e non oltre il prossimo avvio.
   */
  val dragLeases: DragLeases = DragLeases(scope)

  val dragPayloads: DocumentDragPayloads = DocumentDragPayloads(
    library = library,
    issuer = documents,
    authority = DocumentsAuthority.of(context),
    gate = { entryGate.decide() },
    leases = dragLeases,
    preparer = preparedCopies,
    // Fase 10 (I4): il filtro finale dei nascosti, all'inizio e subito prima di creare gli id (un "Nascondi" di un attimo fa, anche di un gruppo o arrivato da un altro dispositivo).
    hiddenMask = gallery.hiddenMask,
  )

  private val editLock = Mutex()
  private var editImpl: DefaultEditSessions? = null

  /**
   * Le sessioni di "Modifica con…": nascono con il catalogo e la lettura del telefono (la riga del MediaStore si legge con i contratti di
   * core-mediastore); null senza backup (la versione e il suo lavoro sono del backup) o prima della famiglia pronta.
   */
  suspend fun editSessions(): DefaultEditSessions? = editLock.withLock {
    editImpl?.let { return@withLock it }
    val b = backup ?: return@withLock null
    val core = gallery.coreIfReady() ?: return@withLock null
    val rows = core.phone.rows ?: return@withLock null
    val fence = b.fence
    DefaultEditSessions(
      phone = rows.source,
      rows = rows.lookup,
      catalog = CatalogEditCatalog(catalog, ::account) { runCatching { core.phone.sync.syncNow(SyncReason.CONTENT_CHANGE) } },
      store = RoomEditSessionStore(gallery.database),
      nasCopies = AppEditNasCopies(catalog, ::reader),
      downloads = AppGalleryDownloads(catalog, { gallery.nas?.downloader }),
      staged = b.fiveB.staged,
      planner = { mediaId, versionId, sha256, size -> b.fiveB.planStaged(mediaId, versionId, sha256, size) },
      cache = shareCache,
      clock = b.clock,
      uris = uris,
      revoker = revoker,
      // D13: in debug con un account di prova "Modifica con…" tocca solo le cartelle recintate (`mcg-prova`); in release tutte.
      phoneFolderAllowed = { folder -> fence == null || account()?.let { fence.allowsPhoneFolder(it.value, folder) } == true },
    ).also { editImpl = it }
  }

  /** Le sessioni di "Modifica con…" ancora aperte (dal database: sopravvivono alla morte del processo). */
  suspend fun openEditSessions(): List<dev.pampa.mycloudgallery.core.backup.contract.EditSession> =
    runCatching { RoomEditSessionStore(gallery.database).open().map { it.session } }.getOrDefault(emptyList())

  /** Le sessioni come porta del contratto (null-object finche' non ci sono). */
  suspend fun editSessionsPort(): EditSessions = editSessions() ?: NoEditSessions

  // --- Vita ------------------------------------------------------------------------------------------------------------------------

  @Volatile
  private var installed = false

  /** Fase 7 (P13, P10-10): "Aggiungi ad album" nella ricezione (la porta degli album, `AlbumsGraph.incoming`); null = la voce non compare. */
  @Volatile
  var incomingAlbums: dev.pampa.mycloudgallery.feature.integrations.receive.IncomingAlbums? = null

  /**
   * All'avvio del processo: le impostazioni del blocco seguite a livello di processo, le porte dei componenti d'ingresso installate, le pulizie
   * dei file lasciati (copie di condivisione e di modifica, file in arrivo non piu' citati). Idempotente.
   */
  fun install() {
    if (installed) return
    installed = true
    scope.launch { runCatching { lock.follow(lockSettings) } }
    wireIncomingMetadata()
    IntegrationPorts.install(entryGate, documents)
    // Fase 10 (I4): un "Nascondi" revoca subito gli id gia' consegnati (selettore, provider, trascinamento) e le copie dei media nascosti, senza aspettare la vista.
    gallery.hiddenIdListeners += { ids, hidden -> if (hidden && ids.isNotEmpty()) documents.revokeMedia(ids) }
    PickerPorts.install(library, unlock, preparedCopies)
    ReceivePorts.install(uploads, destinations, unlock, incomingAlbums)
    gallery.onShutdown { revokeAll() }
    followActivities()
    // Fase 10 (P6): il trascinamento da e verso altre app. Le schermate lo leggono da `MediaDragPorts` e `MediaDropPorts`; senza queste righe niente si trascina e niente si riceve (fallire chiusi).
    (context.applicationContext as? Application)?.let { ForegroundTracker(dragLeases).attach(it) }
    MediaDragPorts.install(HandlerDragPort(dragPayloads, ToastDragNotices(context)))
    MediaDropPorts.install(IncomingDrops(context, { entryGate.decide() }, { SystemClock.elapsedRealtime() }))
    // IN-2: le radici si notificano al sistema quando cambia cio' che la radice dice (l'app diventa pronta, il blocco cambia): DocumentsUI le tiene
    // nella cache di sistema finche' nessuno gliene dice una nuova, e a freddo la prima risposta puo' essere stata "non pronta".
    scope.launch { runCatching { entryGate.decisions().collect { notifyRoots() } } }
    scope.launch(Dispatchers.IO) { atStart() }
  }

  /** IN-8: i metadati dei file in arrivo si leggono dai byte locali, con l'estrattore della Libreria (la coda li chiede appena nasce il loro media). */
  internal fun wireIncomingMetadata() {
    backup?.fiveB?.incomingQueue?.metadata = AppIncomingMetadata(gallery.newExtractor(), catalog)
  }

  /** Dice a DocumentsUI di rileggere le radici del provider dei documenti (`ContentResolver.notifyChange` sull'indirizzo delle radici). */
  internal fun notifyRoots() {
    runCatching {
      val app = context.applicationContext
      app.contentResolver.notifyChange(DocumentsContract.buildRootsUri(DocumentsAuthority.of(app)), null)
    }
  }

  /**
   * Correzioni 5B (MC-1): il custode delle condivisioni sa quando l'utente esce dall'app (un'activity va in pausa: la scelta dell'app, l'altra app) e
   * quando torna (un'activity torna davanti): e' il segnale che l'altra app ha finito con la copia.
   */
  private fun followActivities() {
    val application = context.applicationContext as? Application ?: return
    application.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(activity: Activity) = shares.appLeft()

        override fun onActivityResumed(activity: Activity) = shares.appReturned()

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
      },
    )
  }

  /**
   * Le pulizie dell'avvio (nota di P11): le sessioni di modifica scadute si chiudono e le loro copie in cache si tolgono **prima** di tutto il
   * resto (`ShareCache.sweepAtStart` tiene solo quelle delle sessioni aperte), poi le cartelle di `incoming/` che nessuna riga aperta cita.
   */
  suspend fun atStart() {
    try {
      val keepEdit = editSessions()?.expireStale().orEmpty()
      // Fase 10 (P13, §2.13 `DRAG_TEMP_CLEANED`): quante copie temporanee (trascinamento e condivisione, `cacheDir/share/`) toglie la pulizia dell'avvio. La pulizia
      // non lo dice: si contano le cartelle prima e dopo (solo un numero).
      withContext(Dispatchers.IO) {
        val before = shareCopies()
        shareCache.sweepAtStart(keepEdit)
        val cleaned = (before - shareCopies()).coerceAtLeast(0)
        if (cleaned > 0) DragCounters.add(dev.pampa.mycloudgallery.feature.integrations.drag.DragCounter.TEMP_CLEANED, cleaned.toLong())
      }
      val keepIncoming = runCatching { gallery.database.incomingDao().stagesInStates(IncomingStates.KEEP) }.getOrNull()
      // Senza poter leggere chi e' citato non si toglie niente (fail closed).
      if (keepIncoming != null) withContext(Dispatchers.IO) { stager.sweep(keepIncoming.toSet()) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "pulizia delle integrazioni non riuscita (${e.javaClass.simpleName})" }
    }
  }

  /** Le copie temporanee in `cacheDir/share/` (una cartella per condivisione o trascinamento). */
  private fun shareCopies(): Int = shareCache.shareRoot.listFiles()?.size ?: 0

  /** Azzeramento dell'app o famiglia che cambia: nessun id dei documenti vale piu', le porte tornano ai null-object. */
  fun revokeAll() {
    runCatching { documents.revokeAll() }
    // IN-2: gli id della radice sono cambiati (la chiave e' ruotata): DocumentsUI li rilegge.
    notifyRoots()
    runCatching { shares.releaseAll() }
    // Fase 10 (P6): i prestiti del trascinamento (copie e id) si tolgono, le porte tornano chiuse.
    runCatching { dragLeases.releaseAll() }
    MediaDragPorts.reset()
    MediaDropPorts.reset()
    DropHandoff.reset()
    DragCounters.reset()
    IntegrationPorts.reset()
    PickerPorts.reset()
    ReceivePorts.reset()
    IntegrationCounters.reset()
    FlowCounters.reset()
  }

  /** La Diagnostica delle integrazioni: i contatori (solo codici e numeri, mai un nome). */
  val diagnostics: DiagnosticsContributor = object : DiagnosticsContributor {
    override val section: DiagSection = DiagSection.BACKUP

    override suspend fun collect(): List<DiagItem> {
      val export = counterExport(
        entryAllowed = entryGate.decide() == dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision.ALLOWED,
        receiveReady = uploads != null,
      )
      return listOf(DiagItem(COUNTERS, section, DiagSeverity.INFO, DiagCatalog.titleOf(COUNTERS), null, null, export)) + dragItems(section)
    }
  }

  companion object {
    private const val TAG = "Integrazioni"

    /**
     * Fase 10 (P6, P13, §2.13): `DRAG_OUT` (pronti, elementi, solo-NAS, copie senza posizione, non partiti, prestiti finiti, rifiuti per motivo), `DRAG_IN` (rilasci presi,
     * rifiutati, nostri ignorati) e `DRAG_TEMP_CLEANED`: i codici fissi di `DragCounter`, tutti nell'elenco chiuso di `ExportKeys`. Solo numeri.
     */
    internal fun dragItems(section: DiagSection): List<DiagItem> {
      val all = DragCounters.snapshot()
      fun item(code: DiagCode, prefix: String) = DiagItem(
        code, section, DiagSeverity.INFO, DiagCatalog.titleOf(code), null, null,
        all.filterKeys { it.startsWith(prefix) }.toSortedMap().mapValues { (_, n) -> ExportValue.Num(n) },
      )
      return listOf(
        item(dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes.DRAG_OUT, "drag_out_"),
        item(dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes.DRAG_IN, "drag_in_"),
        item(dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes.DRAG_TEMP_CLEANED, "drag_temp_"),
      )
    }
    private const val PROBE_TIMEOUT_MS: Long = 5_000L

    /** La voce della Diagnostica con i contatori delle integrazioni (P10, P11). */
    val COUNTERS: DiagCode = DiagCode("INTEGRATIONS_COUNTERS")

    /**
     * I valori della voce `INTEGRATIONS_COUNTERS` come arrivano alla Diagnostica: un numero per ogni contatore (codice fisso di
     * `IntegrationCounter` e di `FlowCounters`) e i due flag. Tutte le chiavi devono stare in `ExportKeys` (LA-1 della verifica
     * funzionale della 5B: non ci stavano, e il file esportato aveva solo il codice e la gravita'); lo prova
     * `IntegrationsCountersExportTest`.
     */
    internal fun counterExport(entryAllowed: Boolean, receiveReady: Boolean): LinkedHashMap<String, ExportValue> {
      val export = LinkedHashMap<String, ExportValue>()
      (IntegrationCounters.snapshot() + FlowCounters.snapshot()).toSortedMap().forEach { (code, n) -> export[code] = ExportValue.Num(n) }
      export["entryAllowed"] = ExportValue.Flag(entryAllowed)
      export["receiveReady"] = ExportValue.Flag(receiveReady)
      return export
    }
  }
}
