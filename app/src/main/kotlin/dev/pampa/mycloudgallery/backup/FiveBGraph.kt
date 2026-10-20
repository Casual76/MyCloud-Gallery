package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.CandidateQuery
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionPlan
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpace
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.GuardReport
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.backup.free.BootCounter
import dev.pampa.mycloudgallery.core.backup.free.EverywhereCopies
import dev.pampa.mycloudgallery.core.backup.free.EverywherePlan
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAdvice
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAssessment
import dev.pampa.mycloudgallery.core.backup.free.RoomFreeSpaceDiary
import dev.pampa.mycloudgallery.core.backup.free.SafeFreeSpace
import dev.pampa.mycloudgallery.core.backup.noop.NoCorrections
import dev.pampa.mycloudgallery.core.backup.noop.NoFreeSpace
import dev.pampa.mycloudgallery.core.backup.noop.NoVersionKeeper
import dev.pampa.mycloudgallery.core.backup.store.RoomBackupStore
import dev.pampa.mycloudgallery.core.backup.tasks.RoomPendingTasks
import dev.pampa.mycloudgallery.core.backup.upload.IncomingUploadPorts
import dev.pampa.mycloudgallery.core.backup.upload.StagedBackupEngine
import dev.pampa.mycloudgallery.core.backup.versions.DirectoryStagedFiles
import dev.pampa.mycloudgallery.core.backup.versions.FiveBFactory
import dev.pampa.mycloudgallery.core.backup.versions.FiveBPorts
import dev.pampa.mycloudgallery.core.backup.versions.NasVersionEngine
import dev.pampa.mycloudgallery.core.backup.versions.RoomVersionJournal
import dev.pampa.mycloudgallery.core.backup.versions.VersionFranchise
import dev.pampa.mycloudgallery.core.backup.versions.VersionedBackupEngine
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter
import dev.pampa.mycloudgallery.core.metadata.write.ExifMetadataWriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.backup.free.PhoneSystemDialogs
import dev.pampa.mycloudgallery.nas.NasShareArea
import java.io.File
import java.time.ZoneId
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cio' che il grafo della 5B riceve dall'app oltre al backup: i dialoghi e il riscrittore del telefono (core-mediastore), il controllo della
 * copia corretta (estrattore e decodificatore dell'app), il conteggio dei riavvii, la cartella di una riga del MediaStore (D13).
 */
class FiveBInputs(
  val intents: PhoneMediaIntents,
  val rewriter: () -> PhoneMediaRewriter,
  val fileCheck: () -> WrittenFileCheck,
  val bootCounter: BootCounter,
  val rowFolders: MediaStoreRowFolders?,
  val zone: () -> ZoneId = ZoneId::systemDefault,
)

/**
 * Il grafo della **5B** (P14; docs/fase5-progetto.md §1.4): versioni e correzioni (P8), "Libera spazio" (P9), i file in arrivo (D24), legati
 * al backup della 5A ([BackupGraph]) e con la stessa vita. Due tempi come il backup: nel costruttore, senza I/O, i diari su Room (lavori delle
 * versioni nella tabella del backup, attivita' in sospeso, giri di "Libera spazio"), i file preparati, la coda dei file in arrivo e le
 * **facciate** per le schermate (le porte di Hilt leggono oggetti nati qui: nessun ViewModel resta su un null-object, lezione della verifica
 * funzionale); in [build], con il NAS e il catalogo, i servizi veri (`FiveBFactory`: `:app` non nomina mai le primitive del backup).
 *
 * **Sicurezza.** Ogni servizio che scrive sul NAS o sul telefono gira **uno alla volta** con il backup ([exclusive]: "Ripristina originale",
 * le correzioni, il guardiano di "Libera spazio"); le conferme (`PhoneConfirmation`) arrivano solo dalle schermate; D12/R2 valgono attraverso
 * il cancello del backup (anche per i file in arrivo, con il cancello della share di destinazione); D13 con la radice di prova e la recinzione
 * del telefono. Nei log solo codici.
 */
class FiveBGraph(
  noBackupDir: File,
  private val database: GalleryDatabase,
  private val clock: BackupClock,
  private val store: RoomBackupStore,
  gateInputs: BackupGateInputs,
  private val inputs: FiveBInputs,
  private val fence: BackupFence?,
  private val account: () -> String?,
  private val privateShare: () -> String?,
  private val commonShare: () -> String?,
  private val shares: () -> List<NasShareArea>,
  catalogProvider: suspend () -> MediaCatalog?,
  /** Uno alla volta con il backup (`BackupGraph.exclusive` con un tocco dell'utente che aspetta): null = il turno non si e' liberato. */
  private val exclusive: suspend (suspend () -> Any?) -> Any?,
  onIncomingQueued: (userInitiated: Boolean) -> Unit,
) {

  // --- Nel costruttore: niente I/O ------------------------------------------------------------------------------------------------

  /** I lavori delle versioni e degli scambi (`backup_item`, tipi `version` e `swap`). */
  val journal: RoomVersionJournal = RoomVersionJournal(database, clock)

  /** "Da decidere" (D20) e la porta delle attivita' dei video modificati. */
  val pendingTasks: RoomPendingTasks = RoomPendingTasks(database, clock)

  /** Il diario di "Libera spazio" (`free_space_run`, `free_space_item`, colonne complete dal P14). */
  val freeDiary: RoomFreeSpaceDiary = RoomFreeSpaceDiary(database, clock)

  /** I file preparati delle versioni (correzioni, risultati degli editor) in `noBackupFilesDir/versions/`. */
  val staged: DirectoryStagedFiles = DirectoryStagedFiles(File(noBackupDir, VERSIONS_DIR))

  /** D32: i file che le versioni hanno messo o spostato, per la guardia dei cambiamenti di massa della scansione. */
  val franchise: VersionFranchise = VersionFranchise(journal)

  /** Il cancello dei file in arrivo (share di destinazione, D12, R2, congelamento, emergenza, sola lettura, pausa). */
  val incomingGate: IncomingGate = IncomingGate(gateInputs, commonShare)

  /** `noBackupFilesDir/incoming/`: le copie dei file ricevuti (IncomingStager di P11). */
  val incomingRoot: File = File(noBackupDir, INCOMING_DIR)

  val incomingFiles: AppIncomingLocalFiles = AppIncomingLocalFiles(database, incomingRoot)

  /** La coda dei file in arrivo (porta di feature-integrations), dopo il tocco su "Salva sul NAS". */
  val incomingQueue: AppIncomingQueue = AppIncomingQueue(
    database = database,
    catalog = catalogProvider,
    store = store,
    gate = incomingGate,
    privateShare = privateShare,
    commonShare = commonShare,
    fenceRoot = ::fenceRoot,
    clock = clock,
    onQueued = onIncomingQueued,
  )

  /** Il motore carica i file in arrivo con queste porte (D24): copie locali, cancello della destinazione, radice di D13. */
  fun incomingPorts(): IncomingUploadPorts = IncomingUploadPorts(incomingFiles, incomingGate, fenceRoot())

  /** D13: la radice di prova (`mcg-campo`) con un account di prova in debug; null altrimenti. */
  fun fenceRoot(): SharePath? = account()?.let { fence?.destinationRoot(it) }

  private fun accountId(): AccountId? = account()?.let(AccountId::of)

  private fun areas(): NasAreaLookup {
    val me = accountId() ?: return NasAreaLookup { _, _ -> null }
    return NasAreaLookup.of(shares().map { NasShare(me, it.share, it.area) })
  }

  // --- In start: i servizi veri ------------------------------------------------------------------------------------------------------

  /** I dialoghi di sistema per le schermate della 5B (Android 11+), con la recinzione di D13 riga per riga. */
  val systemDialogs: PhoneSystemDialogs = AppPhoneSystemDialogs(inputs.intents, fence, account) { row -> inputs.rowFolders?.folderOf(row) }

  /** I servizi nati con il NAS e il catalogo. */
  class Parts internal constructor(
    val share: String,
    val versions: NasVersionEngine?,
    val keeper: VersionKeeper,
    val corrections: Corrections,
    val freeSpace: SafeFreeSpace,
    val everywhere: EverywhereCopies,
  )

  private val partsFlow = MutableStateFlow<Parts?>(null)

  val partsState: StateFlow<Parts?> = partsFlow.asStateFlow()

  val parts: Parts? get() = partsFlow.value

  /**
   * Costruisce i servizi per l'area privata [share] e restituisce il motore del backup **con le versioni nella stessa tappa**
   * ([VersionedBackupEngine]: le versioni del telefono si pianificano prima di ogni giro della 5A). Nessun I/O.
   */
  fun build(
    session: NasSessionManager,
    deviceId: String,
    share: String,
    catalog: MediaCatalog,
    phone: PhoneBytes,
    gate: BackupGate,
    guardian: PrivateCopyGuardian,
    reader: NasMediaReader,
    planner: BackupPlanner,
    engine: StagedBackupEngine,
    backupCatalog: BackupCatalog? = null,
  ): StagedBackupEngine {
    val rewriter = AppPhoneRewriter(inputs.rewriter(), PhoneRowFence(fence, account) { volume, id -> catalog.phoneRowFolder(volume, id) })
    val ports = FiveBPorts(
      journal = journal,
      catalog = CatalogVersionCatalog(catalog, ::accountId),
      phone = phone,
      staged = staged,
      gate = gate,
      clock = clock,
      tasks = pendingTasks,
      rewriter = rewriter,
      writer = ExifMetadataWriter(),
      check = inputs.fileCheck(),
      fenceRoot = fenceRoot(),
      zone = inputs.zone,
    )
    val services = FiveBFactory.create(session, deviceId, share, ports)
    val freeCatalog = CatalogFreeSpaceCatalog(catalog)
    val free = SafeFreeSpace(
      catalog = freeCatalog,
      diary = freeDiary,
      backupStore = store,
      versions = services.keeper,
      guardian = ExclusiveGuardian(guardian, exclusive),
      reader = reader,
      phone = phone,
      gate = gate,
      clock = clock,
      boot = inputs.bootCounter,
      account = ::accountId,
      privateShare = privateShare,
      areas = ::areas,
      fence = fence,
      systemDialogs = { inputs.intents.canTrash },
      zone = inputs.zone(),
    )
    partsFlow.value = Parts(share, services.engine, services.keeper, services.corrections, free, EverywhereCopies(freeCatalog, ::accountId, ::areas, fence))
    val versions = services.engine ?: return engine
    // E-E7 (nota di P8): le copie scaricate in `Pictures/MyCloud Gallery` e modificate in Galleria sono versioni dei media del NAS, anche se la
    // cartella non e' una sorgente del backup.
    val downloads = BackupSources(setOf(BackupSource.Folder(PRIMARY_VOLUME, DOWNLOADS_FOLDER)))
    val extra: suspend (BackupKind) -> List<BackupCandidate> = { kind ->
      backupCatalog?.candidates(CandidateQuery(downloads, kind, null, DOWNLOAD_PAGE)).orEmpty()
    }
    return VersionedBackupEngine(engine, versions, planner, clock, extra = extra)
  }

  /** L'azzeramento o un cambio d'area: niente resta legato al vecchio NAS. */
  fun clear() {
    partsFlow.value = null
  }

  // --- All'avvio e dopo la lettura del telefono ---------------------------------------------------------------------------------

  /**
   * All'avvio (P14), con i servizi nati: i file di lavoro lasciati a meta' dalle correzioni si tolgono (`dropLeftoverWork`, i file preparati
   * restano per i loro lavori), le attivita' "Da decidere" si rileggono dal diario, gli esiti di "Libera spazio" in dubbio si risolvono e i
   * media con piu' versioni che il telefono ha riportato a una versione nota mentre l'app era morta hanno il loro scambio ([realign]). Solo
   * file e righe locali.
   */
  suspend fun atStart(catalog: MediaCatalog) {
    runCatching { staged.dropLeftoverWork() }.onFailure { GalleryLog.w(TAG) { "file di lavoro delle versioni non ripuliti" } }
    runCatching { pendingTasks.refresh() }.onFailure { GalleryLog.w(TAG) { "attivita' in sospeso non rilette" } }
    settle()
    val ids = runCatching { catalog.mediaWithVersions(REALIGN_SWEEP) }.getOrDefault(emptyList())
    realign(ids)
  }

  /**
   * Dopo ogni lettura del telefono (P14; note di P9): gli esiti di "Libera spazio" rimasti in dubbio (app uccisa con il dialogo di sistema
   * aperto) si risolvono con la riga del telefono. Restituisce quanti elementi ha risolto.
   */
  suspend fun settle(): Int {
    val p = parts ?: return 0
    return try {
      p.freeSpace.settle()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "esiti di \"Libera spazio\" non risolti (${e.javaClass.simpleName})" }
      0
    }
  }

  /**
   * E-E2 (note di P8): i media che la lettura del telefono ha riportato a una versione nota (`RevertedToVersion`) hanno lo scambio sul NAS
   * (`planRealign`: due rinomine dei file del media stesso, da dati locali, nessuna conferma). Restituisce quanti scambi ha pianificato.
   */
  suspend fun realign(ids: Collection<MediaId>): Int {
    val engine = parts?.versions ?: return 0
    var planned = 0
    for (id in ids.distinct()) {
      try {
        if (engine.planRealign(id) != null) planned++
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "scambio dopo il ripristino non pianificato (${e.javaClass.simpleName})" }
      }
    }
    return planned
  }

  // --- Le facciate per le schermate (Hilt): leggono i servizi al momento della chiamata ---------------------------------------------

  /** "Libera spazio" (P9): fino a [build], il null-object (la pagina dice "non ancora pronto"). */
  val freeSpace: FreeSpace = object : FreeSpace {
    private fun port(): FreeSpace = parts?.freeSpace ?: NoFreeSpace

    override suspend fun plan(filter: FreeSpaceFilter, permanent: Boolean): FreeSpacePlan = port().plan(filter, permanent)

    override suspend fun verify(plan: FreeSpacePlan, confirmation: PhoneConfirmation, budget: Duration): FreeSpaceRun =
      port().verify(plan, confirmation, budget)

    override suspend fun resume(run: FreeSpaceRun, budget: Duration): FreeSpaceRun = port().resume(run, budget)

    override suspend fun nextBatch(run: FreeSpaceRun): TrashBatch? = port().nextBatch(run)

    override suspend fun recordOutcome(batch: TrashBatch, outcome: TrashOutcome): FreeSpaceRun = port().recordOutcome(batch, outcome)
  }

  /** Le versioni e "Ripristina originale" (P8): lo scambio gira uno alla volta con il backup. */
  val versionKeeper: VersionKeeper = object : VersionKeeper {
    private fun port(): VersionKeeper = parts?.keeper ?: NoVersionKeeper

    override suspend fun plan(mediaId: MediaId): NasVersionPlan = port().plan(mediaId)

    override suspend fun restoreOriginal(mediaId: MediaId, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): RestoreOutcome {
      val keeper = port()
      @Suppress("UNCHECKED_CAST")
      return exclusive { keeper.restoreOriginal(mediaId, confirmation, writeGrant) } as RestoreOutcome?
        ?: RestoreOutcome.Unavailable(RestoreAvailability.BUSY)
    }
  }

  /** Le correzioni (P8, D19): la scrittura e il lavoro `VERSION` girano uno alla volta con il backup. */
  val corrections: Corrections = object : Corrections {
    private fun port(): Corrections = parts?.corrections ?: NoCorrections

    override suspend fun plan(mediaId: MediaId, change: CorrectionRequest): CorrectionPlan = port().plan(mediaId, change)

    override suspend fun apply(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): CorrectionOutcome {
      val corrections = port()
      return exclusive { corrections.apply(plan, confirmation, writeGrant) } as CorrectionOutcome?
        ?: CorrectionOutcome.Unavailable(CorrectionReadiness.NOT_AVAILABLE)
    }
  }

  /** "Elimina solo dal telefono" (D22): dal catalogo locale; senza servizi, nessun media si puo' liberare. */
  suspend fun assessPhoneOnly(ids: Set<MediaId>): PhoneOnlyAssessment =
    parts?.freeSpace?.assessPhoneOnly(ids) ?: PhoneOnlyAssessment(ids.associateWith { PhoneOnlyAdvice.UNAVAILABLE })

  /** "Ovunque" (D22, E-D15): le copie di tutte le versioni, dal catalogo locale; null senza servizi. */
  suspend fun everywhere(id: MediaId): EverywherePlan? = parts?.everywhere?.list(id)

  /**
   * "Modifica con…" di un solo-NAS (P11, E-E3): la versione gia' nel catalogo e il file preparato diventano un lavoro `VERSION` (lo esegue il
   * giro dopo, uno alla volta con il resto). Falso senza servizi o se non si puo' (il backup la riprende con `recoverStaged`).
   */
  suspend fun planStaged(mediaId: MediaId, versionId: dev.pampa.mycloudgallery.core.model.VersionId, sha256: dev.pampa.mycloudgallery.core.model.Sha256, size: Long): Boolean {
    val engine = parts?.versions ?: return false
    return try {
      engine.planStaged(mediaId, versionId, sha256, size) != null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "lavoro della versione preparata non pianificato (${e.javaClass.simpleName})" }
      false
    }
  }

  /** I file che le versioni hanno messo in [share] (D32): la franchigia della guardia dei cambiamenti della scansione. */
  suspend fun franchiseFileIds(share: String): Set<Long> = franchise.fileIds(share)

  /** Quante versioni aspettano perche' il PC ha cambiato il file (`SOURCE_CHANGED`): lavori aperti con quell'attesa. */
  suspend fun sourceChangedHolds(): Int = withContext(Dispatchers.IO) {
    runCatching {
      database.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM backup_item WHERE kind = 'version' AND is_open = 1 AND hold = 'source_changed'",
      ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)
  }

  private companion object {
    const val TAG = "BackupFiveB"
    const val VERSIONS_DIR = "versions"
    const val INCOMING_DIR = "incoming"

    /** La cartella di "Scarica sul telefono" (F4-D20) sul volume principale. */
    const val PRIMARY_VOLUME = "external_primary"
    const val DOWNLOADS_FOLDER = "Pictures/MyCloud Gallery/"
    const val DOWNLOAD_PAGE = 200

    /** Quanti media con piu' versioni l'avvio ricontrolla per gli scambi persi (E-E2): i piu' recenti. */
    const val REALIGN_SWEEP = 500
  }
}

/**
 * Il guardiano visto da "Libera spazio" (D9, E-B14): **uno alla volta** con il motore e le verifiche del backup. Se il turno non si libera, il
 * giro aspetta (`BUDGET`: niente fatto, si riprende) invece di scrivere insieme al motore.
 */
private class ExclusiveGuardian(
  private val inner: PrivateCopyGuardian,
  private val exclusive: suspend (suspend () -> Any?) -> Any?,
) : PrivateCopyGuardian {
  override suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport =
    exclusive { inner.ensure(scope, budget) } as GuardReport? ?: GuardReport(0, 0, 0, 0, 0L, RunStop.BUDGET)
}
