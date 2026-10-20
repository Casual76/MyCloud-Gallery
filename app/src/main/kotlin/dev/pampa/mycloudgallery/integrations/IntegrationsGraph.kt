package dev.pampa.mycloudgallery.integrations

import android.content.Context
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
import dev.pampa.mycloudgallery.feature.integrations.edit.DefaultEditSessions
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerPorts
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import dev.pampa.mycloudgallery.feature.integrations.receive.DefaultIncomingUploads
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingStager
import dev.pampa.mycloudgallery.feature.integrations.receive.ReceivePorts
import dev.pampa.mycloudgallery.feature.integrations.share.ContextUriRevoker
import dev.pampa.mycloudgallery.feature.integrations.share.FileProviderUris
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.LocationFreeShare
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.nas.NasShareArea
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

  val entryGate: AppEntryGate = AppEntryGate(lock, familyReady)

  val unlock: AppEntryUnlock = AppEntryUnlock(lock)

  /** Il NAS risponde adesso? Percorso permesso, niente dati mobili senza consenso (D23, E-V2), una prova della share entro pochi secondi (E-X1). */
  suspend fun nasReach(): NasReach {
    val inputs = nasInputs ?: return NasReach.Unreachable(NasUnreachableReason.UNAVAILABLE)
    if (account() == null || areas().isEmpty()) return NasReach.Unreachable(NasUnreachableReason.UNAVAILABLE)
    if (inputs.route.current() !is NasRoute.Allowed) return NasReach.Unreachable(NasUnreachableReason.OFFLINE)
    if (inputs.metered()) return NasReach.Unreachable(NasUnreachableReason.METERED)
    val probe = shareProbe ?: return NasReach.Unreachable(NasUnreachableReason.UNAVAILABLE)
    val share = areas().first().share
    val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe.probe(share) } is NasResult.Ok
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

  /** Il provider dei documenti e chi consegna i solo-NAS (id opachi, revoca). */
  val documents: LibraryMediaDocuments = LibraryMediaDocuments(library, DocumentLabels.from(context))

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
    reachable = { nasReach() == NasReach.Reachable },
  )

  // --- Condividere e modificare (D25, D26) ----------------------------------------------------------------------------------------

  val shareCache: ShareCache = ShareCache(context.applicationContext.cacheDir)

  val uris: FileProviderUris = FileProviderUris(context.applicationContext)

  val revoker: ContextUriRevoker = ContextUriRevoker(context.applicationContext)

  /** "Condividi senza posizione": copia, strip, ricontrollo con i lettori dell'app (estrattore, `ExifInterface`, `MediaMetadataRetriever`). */
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

  /**
   * All'avvio del processo: le impostazioni del blocco seguite a livello di processo, le porte dei componenti d'ingresso installate, le pulizie
   * dei file lasciati (copie di condivisione e di modifica, file in arrivo non piu' citati). Idempotente.
   */
  fun install() {
    if (installed) return
    installed = true
    scope.launch { runCatching { lock.follow(lockSettings) } }
    IntegrationPorts.install(entryGate, documents)
    PickerPorts.install(library, unlock)
    ReceivePorts.install(uploads, destinations, unlock)
    gallery.onShutdown { revokeAll() }
    scope.launch(Dispatchers.IO) { atStart() }
  }

  /**
   * Le pulizie dell'avvio (nota di P11): le sessioni di modifica scadute si chiudono e le loro copie in cache si tolgono **prima** di tutto il
   * resto (`ShareCache.sweepAtStart` tiene solo quelle delle sessioni aperte), poi le cartelle di `incoming/` che nessuna riga aperta cita.
   */
  suspend fun atStart() {
    try {
      val keepEdit = editSessions()?.expireStale().orEmpty()
      withContext(Dispatchers.IO) { shareCache.sweepAtStart(keepEdit) }
      val keepIncoming = runCatching { gallery.database.incomingDao().stagesInStates(IncomingStates.KEEP) }.getOrNull()
      // Senza poter leggere chi e' citato non si toglie niente (fail closed).
      if (keepIncoming != null) withContext(Dispatchers.IO) { stager.sweep(keepIncoming.toSet()) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "pulizia delle integrazioni non riuscita (${e.javaClass.simpleName})" }
    }
  }

  /** Azzeramento dell'app o famiglia che cambia: nessun id dei documenti vale piu', le porte tornano ai null-object. */
  fun revokeAll() {
    runCatching { documents.revokeAll() }
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
      val export = LinkedHashMap<String, ExportValue>()
      (IntegrationCounters.snapshot() + FlowCounters.snapshot()).toSortedMap().forEach { (code, n) -> export[code] = ExportValue.Num(n) }
      export["entryAllowed"] = ExportValue.Flag(entryGate.decide() == dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision.ALLOWED)
      export["receiveReady"] = ExportValue.Flag(uploads != null)
      return listOf(DiagItem(COUNTERS, section, DiagSeverity.INFO, DiagCatalog.titleOf(COUNTERS), null, null, export))
    }
  }

  companion object {
    private const val TAG = "Integrazioni"
    private const val PROBE_TIMEOUT_MS: Long = 5_000L

    /** La voce della Diagnostica con i contatori delle integrazioni (P10, P11). */
    val COUNTERS: DiagCode = DiagCode("INTEGRATIONS_COUNTERS")
  }
}
