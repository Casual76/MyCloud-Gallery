package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
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
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
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
import dev.pampa.mycloudgallery.core.backup.contract.SafeguardStart
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
import dev.pampa.mycloudgallery.core.backup.versions.VersionPlanning
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
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.backup.free.PhoneSystemDialogs
import dev.pampa.mycloudgallery.nas.NasShareArea
import dev.pampa.mycloudgallery.notify.FreeSpaceNotices
import android.content.IntentSender
import java.io.File
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
  private val catalogProvider: suspend () -> MediaCatalog?,
  /** Uno alla volta con il backup (`BackupGraph.exclusive` con un tocco dell'utente che aspetta): null = il turno non si e' liberato. */
  private val exclusive: suspend (suspend () -> Any?) -> Any?,
  onIncomingQueued: (userInitiated: Boolean) -> Unit,
  /**
   * VE-10, MC-3 della verifica funzionale 5B: un lavoro delle versioni nato da un fatto locale (uno scambio dopo un ripristino fatto sul
   * telefono, la modifica di un solo-NAS) chiede subito il lavoro del suo tipo, invece di aspettare il giro periodico (fino a 12 ore).
   */
  private val onVersionWork: (BackupKind) -> Unit = {},
  /** VE-3: "Metti al sicuro adesso" per un media (lo fa il backup: verifica sul momento o "Fai ora"). */
  private val safeguard: suspend (MediaId) -> SafeguardStart = { SafeguardStart.UNAVAILABLE },
  /** Correzioni 5B (LS-2): lo scope del processo, dove il giro di "Libera spazio" va avanti fuori dalla pagina. */
  scope: CoroutineScope? = null,
  /** LS-2: chiede il lavoro avviato dall'utente che ospita la verifica (con la sua notifica); falso = non programmato. */
  freeSpaceWork: () -> Boolean = { false },
  /** LS-2: la notifica "Pronto: N elementi, X GB. Tocca per liberare". */
  notices: FreeSpaceNotices = FreeSpaceNotices.NONE,
  /** LS-2: l'app e' davanti all'utente (allora la notifica "Pronto" non serve: la pagina lo dice). */
  foreground: () -> Boolean = { false },
  /**
   * LS-8: il turno del backup per il guardiano di "Libera spazio", con un'attesa massima (una tappa del giro, non 15 minuti). Null = il turno
   * non si e' liberato in tempo.
   */
  private val guardianTurn: suspend (Duration, suspend () -> Any?) -> Any? = { _, block -> exclusive(block) },
  /**
   * IN-7: lo stato del backup, per dire di ogni file ricevuto a che punto e' (si chiede al primo uso: il grafo del backup nasce con questo). Di serie
   * "in prova", che non dice niente di sbagliato.
   */
  private val backupStatus: () -> StateFlow<BackupStatus> = { MutableStateFlow(BackupStatus.TestOnly) },
  /** IN-7: qualcosa dei file ricevuti e' cambiato (un "Riprova", uno "Scarta"): lo stato del backup e il piede della Libreria si ricalcolano. */
  private val onReceivedChanged: () -> Unit = {},
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

  /**
   * D32: i file che le versioni hanno messo o spostato, per la guardia dei cambiamenti di massa della scansione. R17: solo finche' il catalogo
   * non ha seguito la rinomina e per una settimana d'ora del NAS.
   */
  val franchise: VersionFranchise = VersionFranchise(
    journal = journal,
    catalog = { catalogProvider()?.let { CatalogVersionCatalog(it, ::accountId) } },
    nasNowMs = { clock.nasNowMs() },
  )

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

  /**
   * IN-7 ("Ricevuti in coda"): i file ricevuti che non sono ancora sul NAS, con "Riprova" e "Scarta". Si legge da `incoming_item` e dal diario del
   * backup, con gli stessi cancelli di "Salva sul NAS". Non fa I/O finche' nessuno lo guarda.
   */
  val receivedQueue: AppReceivedQueue = AppReceivedQueue(
    database = database,
    queue = incomingQueue,
    files = incomingFiles,
    gate = incomingGate,
    backupStatus = backupStatus,
    scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default),
    onChanged = onReceivedChanged,
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

  /**
   * Correzioni 5B (LS-1, LS-14): il dialogo del cestino di Android (30 giorni) per [refs], come il cestino del viewer della 4B: dopo il tocco
   * dell'utente su un foglio; il permesso lo da' il sistema. Null se il sistema non ha il dialogo.
   */
  fun trashRequest(refs: List<MediaRef.Phone>): IntentSender? = inputs.intents.trashRequest(refs, trash = true)

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

  /** LS-10: le parti di "Libera spazio" ci sono (il backup e' partito); prima la pagina aspetta invece di dire "niente da liberare". */
  private val freeReady = MutableStateFlow(false)

  /** LS-8: il guardiano di "Libera spazio" aspetta che il backup finisca il file che sta caricando. */
  private val guardianWaiting = MutableStateFlow(false)

  /** LS-11: come "Libera spazio" raggiunge il NAS per l'ora del NAS (una sessione sull'area privata, nessun file letto); niente senza parti. */
  private var reachNas: suspend () -> Unit = {}

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
      // Campo, gruppo A (D3): i metadati della versione corretta con l'estrattore dei file in arrivo (lo lega `IntegrationsGraph`).
      catalog = CatalogVersionCatalog(catalog, ::accountId) { incomingQueue.metadata },
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
    val freeCatalog = CatalogFreeSpaceCatalog(catalog, backupCatalog)
    // LS-11: l'ora del NAS la mette la prima sessione del processo; "Libera spazio" la chiede prima di dire che il NAS non risponde.
    val reach: suspend () -> Unit = { session.withShare(share, Purpose.INTERACTIVE) { } }
    reachNas = reach
    val free = SafeFreeSpace(
      catalog = freeCatalog,
      diary = freeDiary,
      backupStore = store,
      versions = services.keeper,
      guardian = ExclusiveGuardian(guardian, guardianTurn, guardianWaiting),
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
      reachNas = reach,
      zone = inputs.zone(),
    )
    partsFlow.value = Parts(share, services.engine, services.keeper, services.corrections, free, EverywhereCopies(freeCatalog, ::accountId, ::areas, fence))
    freeReady.value = true
    val versions = services.engine ?: return engine
    // E-E7 (nota di P8): le copie scaricate in `Pictures/MyCloud Gallery` e modificate in Galleria sono versioni dei media del NAS, anche se la
    // cartella non e' una sorgente del backup.
    val downloads = BackupSources(setOf(BackupSource.Folder(PRIMARY_VOLUME, DOWNLOADS_FOLDER)))
    val extra: suspend (BackupKind) -> List<BackupCandidate> = { kind ->
      // LA-3 della verifica funzionale 5B: anche questa cartella passa dalla recinzione di D13 (debug, account di prova), come le sorgenti del
      // piano. Una riga che la lettura recintata non apre (`NOT_SELECTED`) farebbe un lavoro `VERSION` che aspetta per sempre. Con la recinzione
      // e senza account, niente (fallire chiusi, come `MediaStorePhoneBytes`); in release la recinzione non c'e'.
      if (fence != null && account()?.let { fence.allowsPhoneFolder(it, DOWNLOADS_FOLDER) } != true) {
        emptyList()
      } else {
        backupCatalog?.candidates(CandidateQuery(downloads, kind, null, DOWNLOAD_PAGE)).orEmpty()
      }
    }
    downloadCandidates = extra
    versionCatalog = CatalogVersionCatalog(catalog, ::accountId)
    return VersionedBackupEngine(engine, versions, planner, clock, extra = extra)
  }

  /** E-E7: i candidati delle versioni in `Pictures/MyCloud Gallery/` (gli stessi che il motore aggiunge al piano), dopo [build]. */
  private var downloadCandidates: suspend (BackupKind) -> List<BackupCandidate> = { emptyList() }
  private var versionCatalog: CatalogVersionCatalog? = null

  /**
   * Correzioni 5B (nota dell'agente delle versioni, LA-4): una copia di "Scarica sul telefono" modificata in Galleria (E-E7) aspetta di diventare
   * una versione **e lo diventerebbe davvero** adesso: non ha gia' un lavoro aperto, e il piano delle versioni la accetta (media proprio, un
   * solito percorso nell'area privata, dentro la radice di prova di D13). Prima `hasWork` non la contava e, da sola, non faceva partire nessun
   * giro; contare ogni candidato invece avrebbe fatto girare il backup a vuoto per uno che non diventa mai un lavoro. Solo dati locali.
   */
  suspend fun downloadVersionWaiting(kind: BackupKind): Boolean {
    val p = parts ?: return false
    if (p.versions == null) return false
    val catalog = versionCatalog ?: return false
    val root = fenceRoot()
    for (c in downloadCandidates(kind)) {
      if (c.original || journal.busy(c.versionId)) continue
      val media = catalog.media(c.mediaId) ?: continue
      val job = VersionPlanning.phoneVersion(media, p.share, c.versionId, c.phone, c.stamp) ?: continue
      if (root == null || under(job.usualPath, root)) return true
    }
    return false
  }

  /** D13: lo stesso controllo del motore delle versioni (il percorso sta sotto la radice di prova). */
  private fun under(path: SharePath, root: SharePath): Boolean {
    val c = path.components
    val r = root.components
    return c.size > r.size && r.indices.all { c[it].equals(r[it], ignoreCase = true) }
  }

  /** L'azzeramento o un cambio d'area: niente resta legato al vecchio NAS. */
  fun clear() {
    partsFlow.value = null
    freeReady.value = false
    reachNas = {}
  }

  // --- All'avvio e dopo la lettura del telefono ---------------------------------------------------------------------------------

  /**
   * All'avvio (P14), con i servizi nati: i file di lavoro lasciati a meta' dalle correzioni si tolgono (`dropLeftoverWork`, i file preparati
   * restano per i loro lavori), le attivita' "Da decidere" si rileggono dal diario, gli esiti di "Libera spazio" in dubbio si risolvono e i
   * media con piu' versioni che il telefono ha riportato a una versione nota mentre l'app era morta hanno il loro scambio ([realign]). Solo
   * file e righe locali.
   */
  suspend fun atStart(catalog: MediaCatalog) {
    // R9: una riscrittura del telefono lasciata a meta' da un processo morto (correzione, "Ripristina originale") si rimette com'era dalla sua
    // copia di sicurezza, prima di ogni altra cosa.
    try {
      val recovery = inputs.rewriter().recover()
      if (recovery.restored + recovery.pending > 0) GalleryLog.w(TAG) { "riscritture interrotte: ${recovery.restored} rimesse, ${recovery.pending} in attesa" }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "recupero delle riscritture non riuscito (${e.javaClass.simpleName})" }
    }
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
    val kinds = HashSet<BackupKind>()
    for (id in ids.distinct()) {
      try {
        if (engine.planRealign(id) != null) {
          planned++
          kinds += kindOf(id)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "scambio dopo il ripristino non pianificato (${e.javaClass.simpleName})" }
      }
    }
    // VE-10: lo scambio parte con il lavoro del suo tipo, adesso (prima solo il diario: lo prendeva il giro periodico, fino a 12 ore).
    kinds.forEach(onVersionWork)
    return planned
  }

  /** Il giro a cui appartiene il media (VE-1): i video con i video, il resto con le foto. */
  private suspend fun kindOf(id: MediaId): BackupKind =
    catalogProvider()?.media(id)?.kind?.let(BackupKind::of) ?: BackupKind.PHOTOS

  // --- Le facciate per le schermate (Hilt): leggono i servizi al momento della chiamata ---------------------------------------------

  /** "Libera spazio" (P9): fino a [build], il null-object (la pagina dice "non ancora pronto"). */
  val freeSpace: FreeSpace = object : FreeSpace {
    private fun port(): FreeSpace = parts?.freeSpace ?: NoFreeSpace

    override suspend fun plan(filter: FreeSpaceFilter, permanent: Boolean): FreeSpacePlan = port().plan(filter, permanent)

    override suspend fun verify(plan: FreeSpacePlan, confirmation: PhoneConfirmation, budget: Duration): FreeSpaceRun =
      port().verify(plan, confirmation, budget)

    /**
     * LS-5: senza le parti (tolte a meta' giro: un cambio d'area, l'azzeramento) il giro e' fermo "non disponibile". Prima il null-object
     * restituiva il giro com'era e la pagina chiedeva un'altra tappa senza fine.
     */
    override suspend fun resume(run: FreeSpaceRun, budget: Duration): FreeSpaceRun =
      parts?.freeSpace?.resume(run, budget) ?: run.copy(state = FreeSpaceRunState.BLOCKED, blocked = FreeSpaceBlock.NOT_AVAILABLE)

    override suspend fun nextBatch(run: FreeSpaceRun): TrashBatch? = port().nextBatch(run)

    override suspend fun recordOutcome(batch: TrashBatch, outcome: TrashOutcome): FreeSpaceRun = port().recordOutcome(batch, outcome)
  }

  /** Le versioni e "Ripristina originale" (P8): lo scambio gira uno alla volta con il backup. */
  val versionKeeper: VersionKeeper = object : VersionKeeper {
    private fun port(): VersionKeeper = parts?.keeper ?: NoVersionKeeper

    override suspend fun plan(mediaId: MediaId): NasVersionPlan = port().plan(mediaId)

    override suspend fun restoreOriginal(mediaId: MediaId, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): RestoreOutcome {
      val keeper = port()
      // VE-8: piano, conferma e permesso del telefono non toccano niente: si sanno subito, senza aspettare il turno del backup (fino a 15 minuti).
      keeper.precheck(mediaId, confirmation, writeGrant)?.let { return it }
      @Suppress("UNCHECKED_CAST")
      return exclusive { keeper.restoreOriginal(mediaId, confirmation, writeGrant) } as RestoreOutcome?
        ?: RestoreOutcome.Unavailable(RestoreAvailability.BUSY)
    }

    override suspend fun precheck(mediaId: MediaId, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): RestoreOutcome? =
      port().precheck(mediaId, confirmation, writeGrant)
  }

  /** Le correzioni (P8, D19): la scrittura e il lavoro `VERSION` girano uno alla volta con il backup. */
  val corrections: Corrections = object : Corrections {
    private fun port(): Corrections = parts?.corrections ?: NoCorrections

    override suspend fun plan(mediaId: MediaId, change: CorrectionRequest): CorrectionPlan = port().plan(mediaId, change)

    override suspend fun apply(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): CorrectionOutcome {
      val corrections = port()
      // VE-8: il permesso del telefono si chiede subito, non dopo il turno del backup.
      corrections.precheck(plan, confirmation, writeGrant)?.let { return it }
      // VE-7: il turno che non si libera e' "occupato", non "non pronta".
      return exclusive { corrections.apply(plan, confirmation, writeGrant) } as CorrectionOutcome?
        ?: CorrectionOutcome.Unavailable(CorrectionReadiness.BUSY)
    }

    override suspend fun precheck(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): CorrectionOutcome? =
      port().precheck(plan, confirmation, writeGrant)

    /** VE-3: lo fa il backup (verifica sul momento della copia sul NAS, o "Fai ora" per un media del telefono in una sorgente). */
    override suspend fun safeguard(mediaId: MediaId): SafeguardStart = if (parts == null) SafeguardStart.UNAVAILABLE else safeguard.invoke(mediaId)
  }

  /**
   * Correzioni 5B (LS-2): il giro di "Libera spazio" del processo. Va avanti fuori dalla pagina (nello scope del grafo), dentro un lavoro avviato
   * dall'utente con la notifica, e si ritrova dal diario dopo la morte del processo (nello stesso avvio, entro le 6 ore). Lo legge la pagina
   * (porta `FreeSpaceRuns`), lo ospita `BackupJobService`.
   */
  val freeRuns: FiveBFreeSpaceRuns = FiveBFreeSpaceRuns(
    service = freeSpace,
    scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default),
    clock = clock,
    active = { parts?.freeSpace?.active() },
    abandon = { id -> parts?.freeSpace?.abandon(id) },
    ready = freeReady.asStateFlow(),
    waitingForBackup = guardianWaiting.asStateFlow(),
    reachNas = { reachNas() },
    startWork = freeSpaceWork,
    notices = notices,
    foreground = foreground,
  )

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
    val planned = try {
      engine.planStaged(mediaId, versionId, sha256, size) != null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "lavoro della versione preparata non pianificato (${e.javaClass.simpleName})" }
      false
    }
    // MC-3: la versione va sul NAS adesso, con il lavoro del suo tipo, non al giro periodico (con Notte, di notte).
    if (planned) onVersionWork(kindOf(mediaId))
    return planned
  }

  /**
   * MC-4: "Modifica con…" di un solo-NAS si apre solo se la modifica potra' diventare una versione sul NAS (un solito percorso proprio nell'area
   * privata, sotto la radice di D13). Senza i servizi, no.
   */
  suspend fun editVersionReady(mediaId: MediaId): Boolean {
    val engine = parts?.versions ?: return false
    return try {
      engine.canPlanStaged(mediaId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "prontezza della modifica non letta (${e.javaClass.simpleName})" }
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

  /**
   * VE-9: quanti media sono "in coda" solo perche' la loro versione attuale aspetta il PC (`SOURCE_CHANGED`): la pagina non li conta fra quelli in
   * coda, cosi' dice "Versioni in attesa del computer" invece di "1 in coda · prossimo giro" per sempre.
   */
  suspend fun sourceChangedCurrentMedia(): Int = withContext(Dispatchers.IO) {
    runCatching {
      database.openHelper.readableDatabase.query(
        "SELECT COUNT(DISTINCT i.media_id) FROM backup_item i JOIN media m ON m.media_id = i.media_id " +
          "WHERE i.kind = 'version' AND i.is_open = 1 AND i.hold = 'source_changed' AND i.version_id = m.current_version_id",
      ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)
  }

  /** VE-10: i tipi (foto, video) che hanno versioni ferme per il PC: dopo una scansione del NAS ripartono con il lavoro del loro tipo. */
  suspend fun sourceChangedKinds(): Set<BackupKind> = withContext(Dispatchers.IO) {
    runCatching {
      database.openHelper.readableDatabase.query(
        "SELECT DISTINCT m.kind = 'video' FROM backup_item i JOIN media m ON m.media_id = i.media_id " +
          "WHERE i.kind = 'version' AND i.is_open = 1 AND i.hold = 'source_changed'",
      ).use { c ->
        buildSet { while (c.moveToNext()) add(if (c.getInt(0) == 1) BackupKind.VIDEOS else BackupKind.PHOTOS) }
      }
    }.getOrDefault(emptySet())
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
 *
 * LS-8 della verifica funzionale 5B: il turno si aspetta al massimo una tappa del giro ([budget]), non 15 minuti, e intanto [waiting] lo dice
 * alla pagina ("aspetto che il backup finisca il file che sta caricando"); il giro riprova alla tappa dopo. Il guardiano, poi, ha il tempo
 * che resta della tappa.
 */
internal class ExclusiveGuardian(
  private val inner: PrivateCopyGuardian,
  private val turn: suspend (Duration, suspend () -> Any?) -> Any?,
  private val waiting: MutableStateFlow<Boolean>,
) : PrivateCopyGuardian {
  override suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport {
    val asked = TimeSource.Monotonic.markNow()
    waiting.value = true
    val report = try {
      turn(budget) {
        waiting.value = false
        inner.ensure(scope, (budget - asked.elapsedNow()).coerceAtLeast(Duration.ZERO))
      } as GuardReport?
    } catch (e: Throwable) {
      waiting.value = false
      throw e
    }
    // Il turno non si e' liberato in tempo: il giro aspetta ancora il backup (la pagina lo dice finche' una tappa non lo trova libero).
    waiting.value = report == null
    return report ?: GuardReport(0, 0, 0, 0, 0L, RunStop.BUDGET)
  }
}
