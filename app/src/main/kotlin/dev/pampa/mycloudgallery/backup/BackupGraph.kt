package dev.pampa.mycloudgallery.backup

import android.content.Context
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.SafeguardStart
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesProgress
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.SafetyCounts
import dev.pampa.mycloudgallery.core.backup.contract.SafetyVerifier
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.backup.plan.CatalogExistingCopyCensus
import dev.pampa.mycloudgallery.core.backup.plan.DefaultBackupPlanner
import dev.pampa.mycloudgallery.core.backup.plan.PhoneConditions
import dev.pampa.mycloudgallery.core.backup.plan.PhoneConditionsSource
import dev.pampa.mycloudgallery.core.backup.plan.SimilarCopyLookup
import dev.pampa.mycloudgallery.core.backup.safety.BackupSafetyFactory
import dev.pampa.mycloudgallery.core.backup.safety.BackupSafetyVerifier
import dev.pampa.mycloudgallery.core.backup.safety.FirstBackupGate
import dev.pampa.mycloudgallery.core.backup.safety.PrivateCopyFinder
import dev.pampa.mycloudgallery.core.backup.settings.DefaultBackupSettings
import dev.pampa.mycloudgallery.core.backup.store.RoomBackupStore
import dev.pampa.mycloudgallery.core.backup.upload.BackupEnginePorts
import dev.pampa.mycloudgallery.core.backup.upload.BackupEngineFactory
import dev.pampa.mycloudgallery.core.backup.upload.PreparingUploadIndexing
import dev.pampa.mycloudgallery.core.backup.upload.StagedBackupEngine
import dev.pampa.mycloudgallery.core.backup.upload.UploadConfig
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.OwnChanges
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.gallery.PhoneLibrary
import dev.pampa.mycloudgallery.nas.NasGraph
import dev.pampa.mycloudgallery.notify.AppFreeSpaceNotices
import dev.pampa.mycloudgallery.nas.NasInputs
import dev.pampa.mycloudgallery.notify.NasDailySummary
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Il grafo del **backup** (Fase 5A, P7; docs/fase5-progetto.md §1.4): il motore del caricamento (P2), le verifiche e il guardiano (P3), il
 * piano e le impostazioni (P4), le schermate (P5) legati al catalogo, al telefono e al NAS dell'app. Uno per processo, dentro la galleria
 * (`GalleryGraph`): ne condivide il database e la vita (l'azzeramento lo ferma con lei, **per primo**).
 *
 * **Due tempi**, come `NasGraph`. Nel costruttore, senza I/O: il diario (`RoomBackupStore`), le impostazioni (DataStore), il cancello,
 * gli orologi e gli stati che le schermate leggono. In [start], con il motore del NAS partito e il catalogo costruito: l'area privata
 * (`LocalDeviceProfile.privateShare`; assente = `NO_PRIVATE_AREA`), il motore (`BackupEngineFactory`: `:app` **non nomina mai** le
 * primitive del backup, lo verifica `Phase5ArchitectureTest`), il verificatore e il guardiano. Senza NAS configurato non parte niente.
 *
 * **Sicurezza** (§6.1):
 * - **D12**: con la recinzione accesa il [gate] non lascia scrivere su una share che non e' di prova (`FeatureFlags.backupMayWriteTo`): lo
 *   stato dice "Backup in prova" e niente si scrive; anche le verifiche e il guardiano si fermano. Spenta dal passo unico del 12/11/2026;
 * - **D13**: in debug con un account di prova la recinzione del telefono (`DeveloperTools.backupFence()`) limita le sorgenti, le righe
 *   che si aprono e le destinazioni (`mcg-campo/Backup/`); in release `fence` e' null. **R2**: per questo, in release un account di prova
 *   non scrive in nessuna share (`FeatureFlags.backupMayWriteAs`, "Backup in prova" per ogni share): senza la recinzione tutta la libreria
 *   vera finirebbe nella share di prova;
 * - **D32**: emergenza, congelamento, sola lettura e famiglia non pronta chiudono il cancello; il motore lo guarda a ogni elemento;
 * - **R4**: "Ferma" e' una pausa che dura ([BackupRunMemory.isPaused]) finche' l'utente non tocca "Riprendi" o "Fai ora": il cancello e'
 *   `PAUSED` per il motore e il guardiano, le verifiche (sola lettura) continuano;
 * - **R7**: il cancello vale solo per l'area privata di adesso; se cambia, le parti del backup si ricostruiscono ([start]);
 * - verificatore, guardiano e motore girano **uno alla volta** ([exclusive]): il guardiano non scrive mentre il motore guida il diario;
 * - il backup parte solo da dati locali (catalogo, impostazioni, tocchi dell'utente): nessun dato letto dal NAS crea un elemento (E-S11);
 * - nei log solo codici e numeri: mai nomi di file, percorsi, share, account, id ne' impronte.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupGraph(
  context: Context,
  private val scope: CoroutineScope,
  private val database: GalleryDatabase,
  private val nas: NasGraph,
  private val nasInputs: NasInputs,
  private val inputs: BackupInputs,
  private val catalogProvider: suspend () -> MediaCatalog?,
  private val phoneProvider: () -> PhoneLibrary?,
  private val similar: SimilarCopiesGraph,
  private val familyReady: () -> Boolean,
  private val monotonicMs: () -> Long,
  /** Fase 5B (P14): cio' che le versioni, le correzioni e "Libera spazio" ricevono dall'app (dialoghi e riscrittore del telefono, controllo). */
  fiveBInputs: FiveBInputs,
  private val foreground: () -> Boolean = { false },
  /** L'ora del dispositivo (ms): solo per "da quando il backup e' attivo" (E-P15); mai per decidere su file o scadenze. */
  private val deviceNow: () -> Long = { System.currentTimeMillis() },
) {

  private val noBackup: File = context.noBackupFilesDir

  /** Cio' che il backup ricorda fra un lavoro e l'altro (battiti, ultimo giro, ultimo problema). */
  val memory: BackupRunMemory = BackupRunMemory(noBackup)

  // --- Gli orologi, il diario e le impostazioni (nessun I/O qui) -------------------------------------------------------------------

  val clock: BackupClock = object : BackupClock {
    override fun monotonicMs(): Long = this@BackupGraph.monotonicMs()

    override fun nasNowMs(): Long? = nasInputs.nasClock.nowMillisOrNull()
  }

  /** Il diario del backup (schema v4, `backup_item`): nel database della galleria, quindi l'azzeramento lo toglie con lei. */
  val store: RoomBackupStore = RoomBackupStore(database, clock)

  /** Le impostazioni del backup (D7, D13): del dispositivo, in `noBackupFilesDir/datastore`. Con la recinzione solo le sorgenti ammesse. */
  val settings: DefaultBackupSettings = DefaultBackupSettings(inputs.settingsStorage, scope, inputs.fence) { nasInputs.account() }

  // --- Aree e cancello ----------------------------------------------------------------------------------------------------------

  /** L'area privata dichiarata per l'account del dispositivo (D7), null = nessuna (`NO_PRIVATE_AREA`). */
  fun privateShare(): String? = nasInputs.shares().firstOrNull { it.area == NasArea.PRIVATE }?.share

  fun commonShare(): String? = nasInputs.shares().firstOrNull { it.area == NasArea.COMMON }?.share

  /** Le aree che il NAS ha detto in sola lettura per questo account (E-N9, E-I19): lo dice una prova della share o un rifiuto in scrittura. */
  private val readOnlyShares = ConcurrentHashMap.newKeySet<String>()

  /**
   * **Il cancello D12** (e D32): vedi [AppBackupGate]. Lo guardano il motore (prima di ogni elemento), le verifiche e il guardiano.
   * `FeatureFlags.backupMayWriteTo` e' la sola decisione su "share vera o di prova".
   */
  private val gateInputs = BackupGateInputs(
    flags = nasInputs.flags,
    familyReady = { familyReady() && nasInputs.trust.writePermit() != null },
    privateShare = ::privateShare,
    account = { nasInputs.account() },
    // Fase 10 (P13): l'emergenza dell'utente o il manifest firmato (`ReadOnly`, `Stopped`): backup e file in arrivo si fermano allo stesso modo.
    writesStopped = { nas.emergency.stopped || nasInputs.releaseWritesStopped() },
    releaseHoldsWrites = { nasInputs.releaseWritesStopped() },
    frozen = { share -> nas.frozen.value.containsKey(share.lowercase(Locale.ROOT)) },
    readOnly = { share -> share.lowercase(Locale.ROOT) in readOnlyShares },
    paused = memory::isPaused,
  )

  val gate: BackupGate = AppBackupGate(gateInputs)

  /** CA-1 (revisione funzionale della Fase 10): le scritture le ferma **solo** il manifest firmato (non l'emergenza dell'utente): la Diagnostica lo dice con la sua frase. */
  val writesHeldOnlyByRelease: Boolean get() = gateInputs.releaseHoldsWrites() && !nas.emergency.stopped

  /** Il telefono come lo legge il backup: righe e byte del MediaStore, con la recinzione di D13. */
  val phone: PhoneBytes = MediaStorePhoneBytes(
    rows = { phoneProvider()?.rows },
    access = { phoneProvider()?.access?.state?.value ?: PhoneAccess.NONE },
    fence = inputs.fence,
    account = { nasInputs.account() },
  )

  /**
   * **La 5B** (P14): versioni e correzioni, "Libera spazio", file in arrivo. Nasce qui senza I/O (diari, coda degli arrivi, facciate per le
   * schermate); i servizi nascono in [start] con il motore. Chi scrive sul NAS o sul telefono gira **uno alla volta** con il backup
   * ([exclusive], come un tocco dell'utente che aspetta il turno).
   */
  val fiveB: FiveBGraph = FiveBGraph(
    noBackupDir = noBackup,
    database = database,
    clock = clock,
    store = store,
    gateInputs = gateInputs,
    inputs = fiveBInputs,
    fence = inputs.fence,
    account = { nasInputs.account() },
    privateShare = ::privateShare,
    commonShare = ::commonShare,
    shares = { nasInputs.shares() },
    catalogProvider = catalogProvider,
    exclusive = { block -> exclusive(USER_EXCLUSIVE_WAIT, forUser = true) { block() } },
    onIncomingQueued = { userInitiated ->
      kick()
      inputs.jobs.incoming(userInitiated)
    },
    // VE-10, MC-3: un lavoro delle versioni nato adesso chiede subito il lavoro del suo tipo.
    onVersionWork = { kind -> inputs.jobs.versionWork(kind) },
    safeguard = { id -> safeguard(id) },
    // Correzioni 5B (LS-2): la verifica di "Libera spazio" fuori dalla pagina, in un lavoro avviato dall'utente, con la notifica "Pronto".
    scope = scope,
    freeSpaceWork = { inputs.jobs.freeSpaceWork() },
    notices = AppFreeSpaceNotices(context),
    foreground = foreground,
    // LS-8: il guardiano aspetta il turno al massimo per una tappa del giro (non 15 minuti), e intanto la pagina lo dice.
    guardianTurn = { wait, block -> exclusive(wait, forUser = true) { block() } },
    // IN-7 ("Ricevuti in coda"): lo stato del backup (a ogni file ricevuto la sua frase) e il ricalcolo del piede dopo un "Riprova" o uno "Scarta".
    backupStatus = { status },
    onReceivedChanged = ::kick,
  )

  // --- Cio' che nasce in start --------------------------------------------------------------------------------------------------

  /** Cio' che nasce in [start]: tutto legato a un'area privata, alla chiave del dispositivo e al catalogo. */
  class Parts internal constructor(
    val share: String,
    val deviceId: String,
    val catalog: MediaCatalog,
    val backupCatalog: BackupCatalog,
    val planner: DefaultBackupPlanner,
    val engine: StagedBackupEngine,
    /** Le verifiche "leggere" (foto) e "pesanti" (video): la regola dell'attivita' del tipo di media (§2.8). */
    val lightVerifier: SafetyVerifier,
    val heavyVerifier: SafetyVerifier,
    val guardian: PrivateCopyGuardian,
  )

  private val partsFlow = MutableStateFlow<Parts?>(null)
  val parts: Parts? get() = partsFlow.value

  private val stopped = AtomicBoolean(false)

  /** Una lettura del telefono e' finita mentre il backup non era ancora partito (CA-1): i lavori si chiedono appena parte. */
  private val afterReadPending = AtomicBoolean(false)
  private val startLock = Mutex()
  private val runLock = Mutex()

  /** Il catalogo del backup, se il catalogo e' costruito (anche prima di [start]: le schermate leggono i conteggi). */
  suspend fun catalogOrNull(): BackupCatalog? = backupCatalogOrNull()

  private suspend fun backupCatalogOrNull(): BackupCatalog? = parts?.backupCatalog ?: catalogProvider()?.let { newBackupCatalog(it) }

  private fun newBackupCatalog(catalog: MediaCatalog): BackupCatalog = CatalogBackupCatalog(catalog) { nasInputs.account()?.let(AccountId::of) }

  /**
   * Fa partire il backup se il NAS c'e' (famiglia pronta, account, chiave del dispositivo, area privata) e il catalogo anche. Idempotente;
   * vero se il motore c'e'. Non scrive niente e non parla con il NAS: costruisce gli oggetti.
   */
  suspend fun start(): Boolean = startLock.withLock {
    if (stopped.get()) return@withLock false
    partsFlow.value?.let { current ->
      if (current.share.equals(privateShare(), ignoreCase = true)) return@withLock true
      // R7: l'area privata e' cambiata (l'admin ha corretto la configurazione) e il motore e' nato con la share di prima: il cancello gli
      // chiude la porta (`NO_PRIVATE_AREA`) e qui si ricostruiscono le parti con la share di adesso. Il giro in corso si ferma al passo dopo.
      GalleryLog.w(TAG) { "l'area privata e' cambiata: le parti del backup si ricostruiscono" }
      changesWatcher?.cancel()
      changesWatcher = null
      accessWatcher?.cancel()
      accessWatcher = null
      revertsWatcher?.cancel()
      revertsWatcher = null
      partsFlow.value = null
      summaryCache.clear()
      fiveB.clear()
      readOnlyShares.clear()
    }
    if (!nas.start()) return@withLock false
    val runtime = nas.runtime ?: return@withLock false
    val catalog = catalogProvider() ?: return@withLock false
    val share = privateShare() ?: return@withLock false
    val deviceId = nasInputs.deviceId() ?: return@withLock false
    val user = nasInputs.account() ?: return@withLock false
    // BK-14: l'account c'e' (altrimenti non si arriva qui) e puo' essere arrivato dopo le impostazioni: le sorgenti ammesse dalla recinzione
    // (D13, debug) si ricalcolano con l'account di adesso, prima che il piano le legga.
    settings.refresh()
    val account = AccountId.of(user)
    val backupCatalog = newBackupCatalog(catalog)
    val session = nasInputs.session

    val existing = ExistingCopiesProgress {
      val scanned = catalog.nasScanState(account, share)?.lastFullScanAt != null
      // Senza il rilevatore delle copie simili (nessun registro dei dati personali) il confronto non puo' partire: non si aspetta per sempre.
      ExistingCopiesState(privateAreaScanned = scanned, similarPending = if (similar.available) similar.progress.value.pending else 0)
    }
    val conditions = PhoneConditionsSource { currentConditions() }
    val planner = DefaultBackupPlanner(
      catalog = backupCatalog,
      settings = settings,
      existing = existing,
      rules = { inputs.ruleSettings.current().toRuleSet() },
      privateShare = ::privateShare,
      clock = clock,
      census = CatalogExistingCopyCensus(backupCatalog, SimilarCopyLookup { candidates -> similar.withSimilarOnNas(candidates.map { it.mediaId }) }),
      conditions = conditions,
      fence = inputs.fence,
      account = { nasInputs.account() },
      alreadyPlanned = { versionId -> store.openFor(versionId) != null },
    )
    val ports = BackupEnginePorts(
      store = store,
      catalog = backupCatalog,
      phone = phone,
      gate = gate,
      clock = clock,
      planner = planner,
      folder = { settings.deviceFolder.value },
      existing = PrivateCopyFinder(session.mediaReader(), backupCatalog, clock),
      facts = CatalogUploadFacts(catalog),
      // D10: chi carica prepara. L'indice dei media caricati rispetta D31 (sulle share vere solo locale) e i cancelli dell'indice.
      // Fase 6 (P5, P11, D10): sugli indicizzatori `analysis` e `frames` vanno anche al buffer del calcolo.
      indexing = PreparingUploadIndexing(runtime.preparer, runtime.publisher, CatalogUploadRecords(catalog), analysisSink = { nas.analysisSink() }),
      foreground = foreground,
      incoming = fiveB.incomingPorts(),
    )
    // D13: con la recinzione, il backup scrive solo sotto `mcg-campo/Backup/` delle share di prova.
    val fenceRoot = inputs.fence?.destinationRoot(user)
    val config = UploadConfig(allowedRoots = listOf(BackupRoots.of(fenceRoot)))
    // D24 (P14): i file in arrivo li carica lo stesso motore, con il cancello della share di destinazione e la radice di D13.
    val uploads = BackupEngineFactory.create(session, deviceId, share, ports, config)
    val reader = session.mediaReader()
    val protectedBytes: suspend () -> Long? = { protectedBytes(share) }
    val light = BackupSafetyVerifier(
      reader, SizeFilteredCatalog(backupCatalog, maxBytes = SizeFilteredCatalog.HEAVY_BYTES - 1), phone, gate, clock, ::privateShare,
      protectedBytes = protectedBytes,
    )
    val heavy = BackupSafetyVerifier(
      reader, SizeFilteredCatalog(backupCatalog, minBytes = SizeFilteredCatalog.HEAVY_BYTES), phone, gate, clock, ::privateShare,
      protectedBytes = protectedBytes,
    )
    val guardian = BackupSafetyFactory.createGuardian(
      session = session,
      deviceId = deviceId,
      store = store,
      catalog = backupCatalog,
      phone = phone,
      // PU-2 (passo unico, 12/11/2026): finche' il primo backup non e' stato scelto (cartella del dispositivo null) il guardiano non scrive niente.
      gate = FirstBackupGate(gate) { settings.deviceFolder.value != null },
      clock = clock,
      privateShare = ::privateShare,
      commonShare = ::commonShare,
      destinationRoot = { fenceRoot },
    )
    // P14: le versioni del telefono si pianificano prima di ogni giro della 5A e girano nella stessa tappa (una modifica non va su come
    // "nome (2)"); i servizi della 5B (versioni, correzioni, "Libera spazio") nascono con la stessa area, lo stesso cancello e lo stesso catalogo.
    val engine = fiveB.build(session, deviceId, share, catalog, phone, gate, guardian, reader, planner, uploads, backupCatalog)
    if (stopped.get()) return@withLock false
    partsFlow.value = Parts(share, deviceId, catalog, backupCatalog, planner, engine, light, heavy, guardian)
    // Fase I (BS-1): un riepilogo delle parti di prima (un'altra area, un altro account) non vale per queste.
    summaryCache.clear()
    // Il catalogo cambia (un media arriva al sicuro, la lettura del telefono porta novita'): le schermate rileggono, accorpato.
    changesWatcher = scope.launch { catalog.changes.collect { kick() } }
    // BK-13: un permesso dei media che cambia (anche dalle Impostazioni di Android) cambia lo stato senza altri eventi.
    accessWatcher?.cancel()
    accessWatcher = scope.launch { phoneProvider()?.access?.state?.collect { kick() } }
    // P14 (E-E2): la lettura del telefono ha riconosciuto che una riga e' tornata a una versione nota: lo scambio sul NAS si pianifica subito
    // (solo il diario: lo esegue il giro dopo, uno alla volta con il resto).
    revertsWatcher?.cancel()
    revertsWatcher = scope.launch { catalog.reverts.collect { id -> fiveB.realign(listOf(id)) } }
    kick()
    // CA-1: una lettura del telefono finita prima di questo momento chiedeva i lavori e non trovava il backup: adesso li chiede.
    if (afterReadPending.compareAndSet(true, false)) scope.launch { runCatching { afterPhoneRead() } }
    true
  }

  /**
   * P14, all'avvio del processo (dopo [start]): file di lavoro delle correzioni, attivita' in sospeso, esiti di "Libera spazio" in dubbio, scambi
   * persi. Solo dati locali; senza backup non fa niente.
   */
  suspend fun atStart() {
    if (!start()) return
    val catalog = parts?.catalog ?: return
    fiveB.atStart(catalog)
  }

  /**
   * P14: dopo ogni lettura del telefono (app aperta o lavoro di fondo) gli esiti in dubbio di "Libera spazio" si risolvono con il telefono.
   *
   * Campo, gruppo A (D6, VE-10, B-S7): con l'app aperta (la lettura del telefono di primo piano, `GalleryGraph`) si chiedono anche i lavori del backup
   * che servono, come fa `PhoneSyncWorker` in background. Prima nessuno li chiedeva: una modifica fatta in Galleria **ad app chiusa** (dopo un
   * `force-stop` i lavori di Android legati ai cambiamenti del MediaStore non ci sono piu', e il cambiamento e' gia' avvenuto quando l'app riparte) la
   * vedeva solo la lettura di primo piano, e la versione aspettava il giro periodico (sul campo 70 minuti). I lavori sono quelli a tappe di
   * WorkManager, unici (`KEEP`), con le regole di sempre: nessun polling, niente parte se le regole dicono di aspettare.
   */
  suspend fun afterPhoneRead() {
    if (parts == null) {
      // CA-1: a freddo la lettura di primo piano puo' finire PRIMA che il backup sia partito (parte dopo il NAS). La chiamata non si perde: si ricorda e si
      // ripete appena `start()` ha finito. Prima usciva e basta, e la versione aspettava il giro periodico (sul campo 70 minuti), proprio nel caso che D6 voleva coprire.
      if (!stopped.get()) afterReadPending.set(true)
      return
    }
    afterReadPending.set(false)
    fiveB.settle()
    for (next in nextAll()) {
      when (next) {
        Next.Quick -> inputs.jobs.quickWork()
        Next.Photos -> inputs.jobs.versionWork(BackupKind.PHOTOS)
        Next.Videos -> inputs.jobs.versionWork(BackupKind.VIDEOS)
        Next.Nothing -> Unit
      }
    }
  }

  /** L'azzeramento (R7): niente parte piu' in questo processo; il giro in corso si ferma prima del file successivo. Prima di ogni cancellazione. */
  fun stop() {
    stopped.set(true)
    val engine = partsFlow.value?.engine
    refresher?.cancel()
    refresher = null
    changesWatcher?.cancel()
    changesWatcher = null
    accessWatcher?.cancel()
    accessWatcher = null
    revertsWatcher?.cancel()
    revertsWatcher = null
    recheck?.cancel()
    recheck = null
    partsFlow.value = null
    summaryCache.clear()
    fiveB.clear()
    readOnlyShares.clear()
    inputs.jobs.cancelAll()
    if (engine != null) scope.launch { runCatching { engine.stop(StopBy.RESET) } }
  }

  // --- Un lavoro alla volta ---------------------------------------------------------------------------------------------------

  /**
   * Il motore, le verifiche e il guardiano girano **uno alla volta** (§1.4): il guardiano scrive nell'area privata mentre il motore guida
   * il diario, e due motori sullo stesso diario non vanno. Null se dopo [waitFor] il posto e' ancora occupato (un altro giro lungo): il
   * lavoro riparte dopo.
   */
  suspend fun <T> exclusive(waitFor: Duration = EXCLUSIVE_WAIT, forUser: Boolean = false, block: suspend () -> T): T? {
    val deadline = monotonicMs() + waitFor.inWholeMilliseconds
    // BK-6: un tocco dell'utente ("Inizia ora", "Fai ora", "Riprendi") che aspetta il turno lo dice agli altri lavori, che cedono il passo
    // al lotto dopo invece di tenerlo per i loro otto minuti ([userIsWaiting]).
    if (forUser) userWaiters.incrementAndGet()
    var counted = forUser
    try {
      // tryLock e non lock + timeout: un'acquisizione che coincide con la scadenza non puo' lasciare il lucchetto preso.
      while (!runLock.tryLock()) {
        if (monotonicMs() >= deadline) return null
        kotlinx.coroutines.delay(EXCLUSIVE_POLL_MS)
      }
      // Il turno e' suo: chi aspettava ha finito di aspettare (gli altri lavori possono tornare a lavorare con calma).
      if (counted) {
        userWaiters.decrementAndGet()
        counted = false
      }
      try {
        return block()
      } finally {
        runLock.unlock()
      }
    } finally {
      if (counted) userWaiters.decrementAndGet()
    }
  }

  private val userWaiters = AtomicInteger()

  /**
   * BK-6: un lavoro avviato dall'utente aspetta il turno. Gli altri lavori del backup (le tappe di sfondo, le verifiche, il guardiano) lo
   * guardano fra un lotto e l'altro e cedono il passo: si fermano al passo dopo, come a tempo scaduto (il punto di ripresa e' nel diario).
   */
  val userIsWaiting: Boolean get() = userWaiters.get() > 0

  /**
   * "Ferma" (pagina, notifica): il giro in corso si ferma prima del file successivo e ogni temporaneo resta con il suo punto di ripresa.
   *
   * **R4**: "Ferma" dell'utente ([StopBy.USER]) e' una **pausa**: il segno resta (anche fra un giro e l'altro e dopo la morte del processo)
   * finche' l'utente non tocca "Riprendi" o "Fai ora" ([resumeFromPause]). Il lavoro a tappe dopo una lettura del telefono o il giro delle
   * sei ore non la scavalcano: il cancello e' `PAUSED`. Il segno vale subito (in memoria), il file segue.
   */
  fun requestStop(by: StopBy) {
    // Fase I (BS2-2): «Ferma» toglie subito il «sto partendo».
    if (by == StopBy.USER) starting = null
    // Dopo l'azzeramento (stop) niente si scrive piu' nella memoria locale: un segno rimasto vivrebbe nel setup dopo.
    if (by == StopBy.USER && !stopped.get()) {
      memory.pauseNow()
      kick()
      scope.launch { runCatching { memory.setPaused(true) } }
    }
    val engine = partsFlow.value?.engine ?: return
    scope.launch { runCatching { engine.stop(by) } }
  }

  /** R4: l'utente ha fermato il backup e non l'ha ripreso? */
  fun isPaused(): Boolean = memory.isPaused()

  /** R4: "Riprendi" o "Fai ora": la pausa finisce e il backup puo' ripartire (con le regole di sempre). */
  suspend fun resumeFromPause() {
    if (stopped.get() || !memory.isPaused()) return
    memory.setPaused(false)
    kick()
  }

  /**
   * R1: un lavoro del backup e' finito con un errore che nessuno prevedeva. Il problema si salva (la pagina lo dice con
   * [BackupProblemCode.UNEXPECTED], "Riprova" lo toglie) invece di sparire in un "successo" silenzioso, e lo stato si ricalcola.
   */
  suspend fun noteUnexpectedError() {
    // Dopo l'azzeramento (stop) la memoria locale non si riscrive: l'errore di un lavoro fermato dalla chiusura non e' un problema del backup.
    if (stopped.get()) return
    memory.setProblem(BackupProblemCode.UNEXPECTED)
    kick()
  }

  /**
   * L'area privata e' in sola lettura per questo account? (E-N9, E-I19.) Una prova della share, senza creare file (`ShareProbe`): se il
   * NAS non risponde non cambia niente. Si chiama prima di un giro, con la rete.
   */
  suspend fun refreshArea() {
    val share = privateShare() ?: return
    val key = share.lowercase(Locale.ROOT)
    when (val result = inputs.shareProbe.probe(share)) {
      is NasResult.Ok -> when (result.value) {
        ShareAccess.READ_ONLY -> readOnlyShares += key
        ShareAccess.READ_WRITE -> readOnlyShares -= key
        else -> Unit
      }
      is NasResult.Err -> Unit
    }
  }

  /** Un giro si e' fermato per la sola lettura: il NAS ha rifiutato una scrittura (E-N9). */
  fun noteReadOnly(share: String) {
    readOnlyShares += share.lowercase(Locale.ROOT)
    kick()
  }

  // --- Cosa c'e' da fare ----------------------------------------------------------------------------------------------------------

  /** Che giro conviene lanciare dopo una lettura del telefono: nessuna ricerca nel NAS, solo il catalogo e il diario (nessun polling). */
  sealed interface Next {
    data object Nothing : Next

    data object Quick : Next

    data object Photos : Next

    data object Videos : Next
  }

  /**
   * Ci sono media nuovi da proteggere in una sorgente? Prima le foto appena scattate (D34, se la regola e' accesa), poi le foto, poi i
   * video. Vuoto se il cancello e' chiuso, se la cartella del dispositivo non e' stata scelta (il primo backup lo avvia l'utente) o se
   * la scansione dell'area privata non e' finita (§2.11 punto 1: il piano non propone niente).
   */
  suspend fun next(): Next = nextAll().firstOrNull() ?: Next.Nothing

  /**
   * **Tutti** i giri che servono adesso, nell'ordine foto nuove, foto, video (BK-2, BK-10). `next` ne dava uno solo: con una foto nuova da
   * proteggere subito il lavoro delle foto non veniva nemmeno chiesto, e se il giro rapido aspettava la rete (fuori casa) niente riprendeva il
   * resto. Un elemento rimasto aperto nel diario riprende dal lavoro del **suo** tipo: un video interrotto dal lavoro dei video, non da quello
   * delle foto (che lo salta e finisce a vuoto).
   */
  suspend fun nextAll(): List<Next> {
    val p = parts ?: return emptyList()
    if (!gate.stateOf(p.share).open || settings.deviceFolder.value == null) return emptyList()
    val out = ArrayList<Next>(3)
    if (inputs.ruleSettings.current().protectNewPhotos && p.planner.next(BackupKind.PHOTOS, 1, BackupTrigger.QUICK_NEW_PHOTOS).isNotEmpty()) out += Next.Quick
    // P14, VE-1: i lavori delle versioni aperti (correzioni, risultati degli editor, scambi dopo un ripristino del telefono) girano con il lavoro
    // del tipo del loro media (un video con i video, con le sue regole); quelli che aspettano il PC (`SOURCE_CHANGED`) no: niente polling, li
    // riprende la fine di una scansione del NAS ([afterNasScan]) o il giro periodico.
    // Correzioni 5B: anche una copia di "Scarica sul telefono" modificata in Galleria che diventerebbe una versione (E-E7).
    if (p.planner.next(BackupKind.PHOTOS, 1, BackupTrigger.SCHEDULED).isNotEmpty() || hasOpenItems(BackupKind.PHOTOS) ||
      hasOpenVersionWork(BackupKind.PHOTOS, waiting = false) || fiveB.downloadVersionWaiting(BackupKind.PHOTOS)
    ) {
      out += Next.Photos
    }
    if (p.planner.next(BackupKind.VIDEOS, 1, BackupTrigger.SCHEDULED).isNotEmpty() || hasOpenItems(BackupKind.VIDEOS) ||
      hasOpenVersionWork(BackupKind.VIDEOS, waiting = false) || fiveB.downloadVersionWaiting(BackupKind.VIDEOS)
    ) {
      out += Next.Videos
    }
    return out
  }

  /**
   * C'e' un elemento aperto nel diario (un giro interrotto, un file aperto dal PC) di un media di [kind]? Una query sul diario unito al
   * catalogo, mai il NAS. I video sono i media `video`; le motion photo, i RAW e le animate vanno con le foto (`BackupKind.of`).
   */
  private suspend fun hasOpenItems(kind: BackupKind): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      val video = if (kind == BackupKind.VIDEOS) "= 'video'" else "!= 'video'"
      database.openHelper.readableDatabase.query(
        "SELECT 1 FROM backup_item i JOIN media m ON m.media_id = i.media_id WHERE i.is_open = 1 AND i.kind = 'new_copy' AND m.kind $video LIMIT 1",
      ).use { it.moveToFirst() }
    }.getOrDefault(false)
  }

  /** Le impostazioni delle regole (D34: "Proteggi subito le foto nuove"), per la corsa delle foto nuove. */
  val ruleSettings get() = inputs.ruleSettings

  /**
   * Fase I (12/11, punto 7.4): la ricerca sul NAS prima del primo backup **aspetta solo il prossimo giro**: l'area privata non e' ancora scandita, nessuna
   * scansione gira e nessuna regola la ferma (con una regola che la ferma la pagina dice quale, e la ripresa a tempo dei lavori del NAS la rifa'). Chi
   * apre la pagina Backup la chiede subito (`NasWorkScheduling.scanNow`, unica), cosi' la riga «Cerco cosa c'e' gia' sul NAS» dice il vero.
   */
  suspend fun firstSearchIdle(): Boolean {
    val share = privateShare() ?: return false
    val scanner = nas.runtime?.scannerOf(share) ?: return false
    if (scanner.status.value is NasScanStatus.Scanning) return false
    if (privateAreaScanned()) return false
    return nasInputs.rules.evaluate(WorkRequest.Background(BackgroundActivity.INDEX_SYNC)) is RuleDecision.Run
  }

  /** La scansione **iniziale** completa dell'area privata e' finita? (§2.11 punto 1: senza, il piano non propone niente.) */
  suspend fun privateAreaScanned(): Boolean {
    val share = privateShare() ?: return false
    val catalog = parts?.catalog ?: catalogProvider() ?: return false
    val user = nasInputs.account() ?: return false
    return catalog.nasScanState(AccountId.of(user), share)?.lastFullScanAt != null
  }

  /** C'e' qualcosa da caricare per [kind] con [trigger]? Dal catalogo e dal diario, mai dal NAS. */
  suspend fun hasWork(kind: BackupKind, trigger: BackupTrigger): Boolean {
    val p = parts ?: return false
    if (p.planner.next(kind, 1, trigger).isNotEmpty()) return true
    // Un elemento rimasto aperto (un giro interrotto, un file aperto dal PC): si riprende dal giro del suo tipo (BK-10).
    if (trigger == BackupTrigger.QUICK_NEW_PHOTOS) return false
    // P14, VE-1: un lavoro delle versioni aperto gira con il lavoro del tipo del suo media (anche quelli che aspettano il PC: il giro ricalcola
    // il piano). Prima solo le foto lo vedevano: un video modificato saliva con le regole delle foto.
    if (hasOpenItems(kind) || hasOpenVersionWork(kind, waiting = true)) return true
    // Correzioni 5B (nota dell'agente delle versioni): una copia di "Scarica sul telefono" modificata in Galleria (E-E7) da sola non faceva
    // partire nessun giro. Conta solo se diventerebbe davvero un lavoro della versione (mai un giro a vuoto per un candidato che non lo diventa).
    return fiveB.downloadVersionWaiting(kind)
  }

  /**
   * P14: lavori delle versioni o degli scambi aperti nel diario per i media di [kind] (VE-1: i video sono i media `video`, come in
   * [hasOpenItems]). Con [waiting] falso, senza quelli che aspettano il PC (`SOURCE_CHANGED`).
   */
  private suspend fun hasOpenVersionWork(kind: BackupKind, waiting: Boolean): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      val held = if (waiting) "" else " AND (i.hold IS NULL OR i.hold != 'source_changed')"
      val video = if (kind == BackupKind.VIDEOS) "= 'video'" else "!= 'video'"
      database.openHelper.readableDatabase.query(
        "SELECT 1 FROM backup_item i JOIN media m ON m.media_id = i.media_id " +
          "WHERE i.is_open = 1 AND i.kind IN ('version', 'swap') AND m.kind $video$held LIMIT 1",
      ).use { it.moveToFirst() }
    }.getOrDefault(false)
  }

  /**
   * VE-10: alla fine di una scansione del NAS le versioni ferme perche' il PC aveva cambiato il file (`SOURCE_CHANGED`) ripartono con il lavoro
   * del loro tipo: la scansione ha registrato il file del PC e il piano si ricalcola. Prima ripartivano solo con il giro periodico (fino a 12
   * ore), mentre la frase prometteva "riparto da solo dopo la prossima verifica del NAS". Solo il diario e il catalogo.
   */
  suspend fun afterNasScan() {
    if (parts == null) return
    for (kind in fiveB.sourceChangedKinds()) inputs.jobs.versionWork(kind)
  }

  /**
   * VE-3 della verifica funzionale 5B, "Metti al sicuro adesso" (foglio "Prima la metto al sicuro" di una correzione): cio' che serve a **questo**
   * media. Prima il tasto chiedeva solo "Fai ora", che non vede un media fuori dalle sorgenti e non verifica un solo-NAS.
   * - Una copia privata della versione attuale non ancora riletta (un solo-NAS letto dalla scansione): la verifica sul momento di quella copia
   *   (`OnTheSpot`, come "Libera spazio"), uno alla volta con il resto del backup. "Al sicuro" nasce solo da quella rilettura.
   * - Una riga del telefono in una sorgente del backup: "Fai ora" (il lavoro dell'utente; sui dati mobili le regole chiedono conferma).
   * - Una riga fuori dalle sorgenti: [SafeguardStart.NOT_IN_SOURCES], il foglio lo dice. Altrimenti niente da far partire.
   */
  suspend fun safeguard(id: MediaId): SafeguardStart {
    val p = parts ?: return SafeguardStart.UNAVAILABLE
    val media = p.catalog.media(id) ?: return SafeguardStart.UNAVAILABLE
    val current = media.currentVersionId
    val here = media.locations.filter { it.versionId == current && it.state == LocationState.PRESENT }
    val unverified = here.any { l ->
      val nas = l.location as? Location.Nas
      nas != null && nas.share.equals(p.share, ignoreCase = true) && l.verifiedAtMillis == null
    }
    if (unverified) {
      val size = media.versions.firstOrNull { it.id == current }?.fingerprint?.size ?: 0L
      val verifier = if (size >= SizeFilteredCatalog.HEAVY_BYTES) p.heavyVerifier else p.lightVerifier
      scope.launch {
        try {
          exclusive(USER_EXCLUSIVE_WAIT, forUser = true) { verifier.verify(VerifyScope.OnTheSpot(setOf(id)), SAFEGUARD_BUDGET) }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "verifica sul momento non riuscita (${e.javaClass.simpleName})" }
        }
        kick()
      }
      return SafeguardStart.STARTED
    }
    val phone = here.firstNotNullOfOrNull { it.location as? Location.Phone } ?: return SafeguardStart.UNAVAILABLE
    val sources = settings.sources.value
    val inSources = when {
      media.source == MediaSource.CAMERA && BackupSource.Camera in sources -> true
      media.source == MediaSource.SCREENSHOT && BackupSource.Screenshots in sources -> true
      else -> BackupSource.Folder(phone.volume, phone.relativePath.ifEmpty { "/" }) in sources
    }
    if (!inSources) return SafeguardStart.NOT_IN_SOURCES
    // Il primo backup lo avvia l'utente dalla pagina (cartella del dispositivo, D7): da qui non si sceglie.
    if (settings.deviceFolder.value == null) return SafeguardStart.UNAVAILABLE
    inputs.jobs.userInitiated(BackupTrigger.USER_NOW, confirmed = false)
    return SafeguardStart.STARTED
  }

  /** D24 (P14): file in arrivo in coda nel diario (aperti). Una query, mai il NAS. */
  suspend fun hasOpenIncoming(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      database.openHelper.readableDatabase.query("SELECT 1 FROM backup_item WHERE is_open = 1 AND kind = 'incoming' LIMIT 1").use { it.moveToFirst() }
    }.getOrDefault(false)
  }

  /** I record e le miniature dei media caricati vanno all'indice: pubblicazione dell'outbox (con D31 accesa, sulle share vere solo locale). */
  suspend fun flushIndex() {
    nas.flushOutbox()
  }

  /** Il registro su disco delle richieste del guardiano (L76). */
  private val safeCopyLog = dev.pampa.mycloudgallery.albums.FileSafeCopyRequestLog(java.io.File(context.noBackupFilesDir, SAFE_COPY_REQUESTS_FILE))

  /** Quante volte il registro delle richieste del guardiano non si e' potuto leggere (Diagnostica degli album, `ALBUM_REGISTRY_UNREADABLE`: solo il numero). */
  val safeCopyLogUnreadable: Int get() = safeCopyLog.unreadable

  /**
   * Fase 7 (D28, P13): le richieste del guardiano **subito** dopo una condivisione (`SafeCopyRequests` di core-backup, P4), su un registro su disco
   * (L76). Il giro chiesto e' quello di sempre ([GuardScope.All]) con le regole di sempre: la tappa `safety` lo fa girare e le toglie solo a giro
   * finito. Senza il backup partito non c'e' un guardiano: le richieste aspettano.
   */
  val safeCopyRequests: dev.pampa.mycloudgallery.core.backup.safety.GuardianSafeCopyRequests =
    dev.pampa.mycloudgallery.core.backup.safety.GuardianSafeCopyRequests(
      log = safeCopyLog,
      guardian = object : PrivateCopyGuardian {
        override suspend fun ensure(scope: dev.pampa.mycloudgallery.core.backup.contract.GuardScope, budget: kotlin.time.Duration) =
          parts?.guardian?.ensure(scope, budget)
            ?: dev.pampa.mycloudgallery.core.backup.contract.GuardReport(0, 0, 0, 0, 0, dev.pampa.mycloudgallery.core.backup.contract.RunStop.STOPPED)
      },
      wake = { inputs.jobs.safety() },
    )

  /** Ci sono copie del guardiano da fare (media propri con la sola copia nell'area comune, o richieste dopo una condivisione, D28)? */
  suspend fun guardianHasWork(): Boolean {
    val p = parts ?: return false
    if (runCatching { safeCopyRequests.hasPending() }.getOrDefault(false)) return true
    return p.backupCatalog.onlyCommonCopies(dev.pampa.mycloudgallery.core.backup.contract.GuardScope.All, 1).isNotEmpty()
  }

  /**
   * Cio' che il riepilogo giornaliero dice del backup (solo numeri): null se il backup non gira (non configurato, cancello chiuso:
   * "in prova", congelamento e emergenza li raccontano gia' altri). Dal catalogo e dalla memoria, mai dal NAS.
   */
  suspend fun dailyFacts(): BackupDailyFacts? {
    if (!start()) return null
    val p = parts ?: return null
    if (settings.deviceFolder.value == null || !gate.stateOf(p.share).open) return null
    val sources = settings.sources.value
    val counts = if (sources.isEmpty) SafetyCounts.ZERO else p.backupCatalog.safetyCounts(sources)
    return BackupDailyFacts(
      queued = BackupStatusReducers.queuedOf(counts),
      lastBeatAt = listOfNotNull(memory.photoBeat.lastBeatAt(), memory.videoBeat.lastBeatAt()).maxOrNull(),
      startedAt = memory.startedAt(),
      problem = memory.lastProblem(),
      lostBeforeBackup = counts.problems[BackupProblemCode.LOST_BEFORE_BACKUP] ?: 0,
      notIntact = counts.problems[BackupProblemCode.VERIFY_FAILED] ?: 0,
      commonCopyLost = counts.problems[BackupProblemCode.COMMON_COPY_LOST] ?: 0,
    )
  }

  // --- Per le schermate (porte di feature-backup, P7) ---------------------------------------------------------------------------

  /** Qualcosa che cambia lo stato di un media o del backup: lo stato del motore, il catalogo, le sorgenti. Per il pannello del viewer. */
  fun changes(): Flow<Unit> = flow {
    val catalog = catalogProvider()
    val parts = listOfNotNull(
      status.map { },
      settings.sources.map { },
      catalog?.changes?.map { },
    )
    emitAll(merge(*parts.toTypedArray()))
  }

  /** Il nome del dispositivo cosi' com'e' (D7), per proporre la cartella. */
  suspend fun deviceName(): String? = inputs.deviceName()

  /** La radice sotto cui il backup scrive con la recinzione di D13 (`mcg-campo`), null senza. */
  fun fenceRoot(): SharePath? = nasInputs.account()?.let { inputs.fence?.destinationRoot(it) }

  /** Sfoglia (sola lettura) una share del NAS: l'elenco di `Backup/` per proporre la cartella del dispositivo. */
  fun browse(share: String): NasBrowser = nasInputs.session.browser(share)

  /** I nomi degli altri dispositivi approvati dello stesso account (E-B6: lo stesso nome nella cartella). */
  fun otherDeviceNames(): List<String> {
    val me = nasInputs.deviceId()
    val account = nasInputs.account() ?: return emptyList()
    val registry = nasInputs.registry()?.registry ?: return emptyList()
    return registry.devices.filter { it.id != me && it.nasUser == account && it.status == DeviceStatus.APPROVED }.map { it.name }
  }

  /** La memoria del telefono e' quasi piena (E-P3): poca per il backup, che ne usa un po' per lavorare. */
  fun phoneStorageLow(): Boolean = noBackup.usableSpace < LOW_STORAGE_BYTES

  /** I lavori avviati dall'utente (UIDT): "Inizia ora", "Fai ora". */
  val jobs get() = inputs.jobs

  /** La recinzione del telefono di D13 (debug, account di prova): null in release. */
  val fence get() = inputs.fence

  /**
   * "Riprova" (§2.6): toglie il segno dei media che non si leggono, la cui verifica e' fallita o che hanno avuto un errore inatteso (R1), e
   * l'ultimo problema che ha fermato un giro.
   * Il backup li rilegge al prossimo giro. Solo dal catalogo locale. Vero se ha tolto qualcosa.
   */
  suspend fun retryProblems(): Boolean {
    val catalog = catalogProvider() ?: return false
    memory.setProblem(null)
    // BK-3, BK-7: una sola prova per rimettere in discussione anche la sola lettura dell'area (l'admin puo' aver corretto i permessi).
    runCatching { refreshArea() }
    val ids = withContext(Dispatchers.IO) {
      runCatching {
        database.openHelper.readableDatabase.query(
          "SELECT media_id FROM backup_problem WHERE code IN (?, ?, ?) LIMIT ?",
          arrayOf<Any?>(
            BackupProblemCode.UNREADABLE.code, BackupProblemCode.VERIFY_FAILED.code, BackupProblemCode.UNEXPECTED.code, RETRY_LIMIT,
          ),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
      }.getOrDefault(emptyList())
    }
    for (raw in ids) {
      val id = runCatching { MediaId(raw) }.getOrNull() ?: continue
      catalog.markBackupProblem(id, null)
    }
    kick()
    return ids.isNotEmpty()
  }

  /**
   * BK-3: l'utente e' tornato da un'azione che doveva sistemare il problema che ha fermato l'ultimo giro (la password, lo spazio sul NAS, i
   * permessi): il problema ricordato si toglie e l'area si rimette alla prova; il giro che segue lo ritrova se il guasto c'e' ancora. Non
   * tocca i segni dei singoli media ("Riprova" li toglie).
   */
  suspend fun forgetRememberedProblem() {
    if (stopped.get()) return
    memory.setProblem(null)
    runCatching { refreshArea() }
    kick()
  }

  /** I temporanei del backup citati dal diario (D33): la pulizia dei propri file non li toglie finche' qualcuno li cita. */
  suspend fun citedTemps(): Set<SharePath> = store.citedTemps()

  // --- Stato -----------------------------------------------------------------------------------------------------------------------

  private val statusFlow = MutableStateFlow<BackupStatus>(BackupStatus.NotConfigured(setOf(BackupSetupStep.NAS)))

  /** Lo stato del backup (§1.5): pagina Backup e Impostazioni. */
  val status: StateFlow<BackupStatus> = statusFlow.asStateFlow()

  private val sourcesFlow = MutableStateFlow<List<SourceStatus>>(emptyList())

  /** Una riga per sorgente (§1.5 `SourceStatus`). */
  val sources: StateFlow<List<SourceStatus>> = sourcesFlow.asStateFlow()

  private val footerFlow = MutableStateFlow<LibraryBackupStatus>(LibraryBackupStatus.Idle)

  /** Lo stato per il piede della Libreria (`BackupStatus` ridotto a `LibraryBackupStatus`). */
  val libraryStatus: StateFlow<LibraryBackupStatus> = footerFlow.asStateFlow()

  private val kicks = Channel<Unit>(Channel.CONFLATED)
  private var refresher: kotlinx.coroutines.Job? = null
  private var changesWatcher: kotlinx.coroutines.Job? = null
  private var accessWatcher: kotlinx.coroutines.Job? = null
  private var revertsWatcher: kotlinx.coroutines.Job? = null
  private var recheck: kotlinx.coroutines.Job? = null

  /** BK-5: l'avanzamento attraverso i lotti; letto dal file al primo calcolo, scritto quando cambia. */
  private val progress = RunProgress()

  /**
   * D-11: l'ultima tappa non ha trovato niente da fare, ne' per il suo tipo ne' per l'altro (dal piano e dal diario, non dai conteggi): conferma
   * che la campagna e' finita anche senza un giro del motore in questo processo ([composeStatus]). Lo toglie il prossimo giro ([roundStarting]).
   */
  @Volatile
  private var nothingToDo = false

  /**
   * T-1: la coda vista al primo stato dopo un «niente da fare» ([nothingToDo]); -1 = non ancora vista. Finche' la coda non cresce oltre questa, e' una
   * coda che il telefono non serve: la pagina non promette un giro. Una foto nuova la fa crescere e la promessa torna vera.
   */
  @Volatile
  private var unservedQueue = -1

  /** D-11: vedi [nothingToDo]; lo stato si ricalcola. */
  fun noteNothingToDo() {
    nothingToDo = true
    unservedQueue = -1
    kick()
  }
  private var progressLoaded = false

  /** Qualcosa che cambia lo stato e' successo: le schermate lo rileggono (accorpato, mai piu' di una volta ogni [REFRESH_GAP]). */
  fun kick() {
    kicks.trySend(Unit)
  }

  init {
    refresher = scope.launch(Dispatchers.Default) {
      kicks.receiveAsFlow().collect {
        try {
          recompute()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "stato del backup non calcolato: ${e.javaClass.simpleName}" }
        }
        kotlinx.coroutines.delay(REFRESH_GAP)
      }
    }
    // Cio' che cambia lo stato: le impostazioni, il motore, i cancelli (emergenza, congelamento), l'accesso ai media e il NAS.
    scope.launch { settings.sources.collect { kick() } }
    scope.launch {
      settings.deviceFolder.collect { folder ->
        // Da quando c'e' una cartella il backup e' attivo: "fermo da 3 giorni" conta da qui (mai dalla configurazione del NAS).
        if (folder != null) memory.markStartedOnce(deviceNow())
        kick()
      }
    }
    scope.launch { nas.frozen.collect { kick() } }
    scope.launch { nas.runtimeState.collect { kick() } }
    scope.launch { similar.progress.collect { kick() } }
    scope.launch { partsFlow.flatMapLatest { it?.engine?.status ?: emptyFlow() }.collect { kick() } }
    // BK-13: le regole (preset, soglie) cambiano perche' il backup aspetta.
    scope.launch { inputs.ruleSettings.observe().collect { kick() } }
    // BK-11: "Mostra come principale" e "Separa" sono dati personali come preferiti e nascosti: la sincronizzazione parte accorpata.
    // P14 (D22, E-D15): "Ovunque" del viewer prende le copie di tutte le versioni (anche quelle in `Versioni/`) dall'elenco del backup.
    nas.organizer.everywhere = { id -> fiveB.everywhere(id) }
    // P14 (D32): i file che le versioni hanno messo o spostato non fanno scattare il congelamento della scansione per "cambiati".
    nas.ownChanges = OwnChanges { share -> fiveB.franchiseFileIds(share) }
    similar.onChoiceSaved = {
      personalSyncRequests.incrementAndGet()
      nas.organizer.personalChanged()
    }
  }

  /** BK-11: quante volte una scelta sulle copie simili ha chiesto la sincronizzazione dei dati personali (per i test e la Diagnostica). */
  internal val personalSyncRequests = AtomicInteger()

  private suspend fun recompute() {
    // BK-14: le sorgenti ammesse dalla recinzione seguono l'account di adesso (nessun effetto senza recinzione).
    settings.refresh()
    if (!progressLoaded) {
      progressLoaded = true
      progress.restore(memory.progress())
    }
    val sources = settings.sources.value
    val share = privateShare()
    val access = phoneProvider()?.access?.state?.value
    val catalog = backupCatalogOrNull()
    val counts = if (catalog != null && !sources.isEmpty) catalog.safetyCounts(sources) else SafetyCounts.ZERO

    val status = composeStatus(sources, share, access, counts)
    statusFlow.value = status
    sourcesFlow.value = sourceStatuses(catalog, sources)
    // IN-7: i file ricevuti che non si sono caricati lo dicono anche qui (una query sull'indice di `state`, mai il NAS).
    val incomingProblems = try {
      fiveB.receivedQueue.problemCount()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      0
    }
    footerFlow.value = BackupStatusReducers.footer(status, incomingProblems)
    progress.snapshot()?.let { text -> if (!stopped.get()) memory.setProgress(text) }
    // BK-13: "Sto cercando cosa c'e' gia' sul NAS" e' provvisorio e il riepilogo in cache vale un minuto; se nessun altro evento arriva, lo stato
    // non diventerebbe mai "assestato". Un solo ricalcolo, fra un minuto, finche' il riepilogo e' provvisorio.
    if (status is BackupStatus.FirstBackupReady && status.summary.comparing) {
      // Fase I (12/11): mentre la scansione dell'area privata gira, il numero dei file gia' guardati e il motivo di una pausa si rileggono piu'
      // spesso (costano poco: il riepilogo resta quello in cache).
      val wait = summaryCache.recheckInMs(monotonicMs()) ?: SUMMARY_REFRESH_MS
      recheckLater(if (status.summary.canStart) wait else minOf(wait, SCAN_REFRESH_MS))
    }
  }

  private fun recheckLater(afterMs: Long) {
    synchronized(this) {
      if (stopped.get() || recheck?.isActive == true) return
      recheck = scope.launch {
        kotlinx.coroutines.delay(afterMs)
        kick()
      }
    }
  }

  private suspend fun composeStatus(sources: BackupSources, share: String?, access: PhoneAccess?, counts: SafetyCounts): BackupStatus {
    // D12 vale per primo: con la recinzione accesa sulle share vere il backup e' "in prova", qualunque altra cosa manchi. Con R2, in release,
    // anche per un account di prova (senza la recinzione del telefono di D13 non si scrive da nessuna parte).
    if (share != null && familyReady() && testOnly(share)) return BackupStatus.TestOnly
    val missing = LinkedHashSet<BackupSetupStep>()
    if (nasInputs.shares().isEmpty() || nasInputs.deviceId() == null) {
      missing += BackupSetupStep.NAS
    } else if (share == null) {
      missing += BackupSetupStep.PRIVATE_AREA
    }
    if (access != null && access.media == MediaAccess.NONE) missing += BackupSetupStep.MEDIA_PERMISSION
    if (sources.isEmpty && settings.loaded.value) missing += BackupSetupStep.SOURCES
    if (missing.isNotEmpty()) return BackupStatus.NotConfigured(missing)
    val area = checkNotNull(share)
    val gateState = gate.stateOf(area)
    when (val state = gateState) {
      BackupGateState.OPEN -> Unit
      // R4: la pausa dell'utente si dice dopo la cartella del dispositivo (sotto): finche' non c'e' non c'e' nemmeno un backup da fermare.
      BackupGateState.PAUSED -> Unit
      BackupGateState.TEST_ONLY -> return BackupStatus.TestOnly
      BackupGateState.NOT_READY -> return BackupStatus.NotConfigured(setOf(BackupSetupStep.NAS))
      BackupGateState.NO_PRIVATE_AREA -> return BackupStatus.NotConfigured(setOf(BackupSetupStep.PRIVATE_AREA))
      BackupGateState.WRITES_STOPPED, BackupGateState.FROZEN, BackupGateState.READ_ONLY_AREA ->
        return BackupStatus.Problem(checkNotNull(state.problem), 0)
    }
    // Il primo backup lo avvia l'utente, con il riepilogo (§2.11): finche' non ha scelto la cartella del dispositivo non si carica niente.
    if (settings.deviceFolder.value == null) return BackupStatus.FirstBackupReady(summary())
    // R4: "In pausa" prima di tutto il resto (anche mentre il file in volo finisce), cosi' la pagina e la notifica dicono la stessa cosa.
    if (gateState == BackupGateState.PAUSED) return BackupStatus.Stopped(StopBy.USER)

    val queued = queuedOf(counts)
    val engineStatus = parts?.engine?.status?.value
    // BK-5: l'avanzamento e' cumulativo (fatti + in coda), non quello del lotto da 60 secondi. D-11: con i byte veri che restano da caricare.
    if (engineStatus is BackupStatus.Running) {
      // Fase I (BF-1): il motore ha preso il giro: il «sto partendo» finisce.
      starting = null
      return progress.reshape(engineStatus, queued, monotonicMs(), remainingBytes(sources))
    }
    // D-11: una coda vuota chiude la campagna solo se il backup e' d'accordo (l'ultimo giro del motore e' finito senza niente in attesa, o una tappa
    // non ha trovato niente da fare per nessun tipo): uno zero di passaggio dei conteggi chiudeva la campagna e ne toglieva il file ("Video: 0 di 3"
    // dopo una morte).
    val confirmed = engineStatus is BackupStatus.UpToDate || nothingToDo
    if (progress.idle(queued, confirmed)) GalleryLog.d(TAG) { "avanzamento: campagna chiusa (niente in coda)" }
    rememberedProblem(access)?.let { problem -> return BackupStatus.Problem(problem, 0) }
    if (engineStatus is BackupStatus.Stopped) return engineStatus
    startingStatus(queued, sources)?.let { return it }

    if (queued > 0) {
      // D-9 (prove sul campo): "Da caricare" sono i byte dei media in coda sul catalogo. Prima venivano solo dal diario, che ha gli elementi di un
      // giro alla volta: "296 in coda · Da caricare: 0 B". Senza il catalogo, quello che sa il diario.
      val bytes = remainingBytes(sources) ?: runCatching { store.counts().bytesOpen }.getOrDefault(0L)
      // D-13 (prove sul campo): il NAS tiene occupato un file del backup (adesso, nel giro che aspetta, o all'ultimo giro finito): lo stato lo dice,
      // "riprovo tra poco", non "al prossimo giro" (sei ore) ne' "in corso" con i video fermi. Un'attesa delle regole (il Wi-Fi, la carica, il NAS
      // che non risponde) vince: e' quella che fa aspettare adesso.
      val busy = (engineStatus is BackupStatus.Waiting && engineStatus.reason == WaitReason.NAS_BUSY) || memory.anyBusy()
      val rules = waitReason()
      // T-1: dopo un giro che non ha trovato niente da caricare (anche «Fai ora»), questa coda il telefono non la serve: niente «al prossimo giro».
      val unserved = nothingToDo && queued <= (if (unservedQueue < 0) queued.also { unservedQueue = it } else unservedQueue)
      val wait = BackupStatusReducers.queueWait(rules, busy, unserved)
      return waitingStatus(queued, bytes, wait, stalled = isStalled(queued), storageLow = phoneStorageLow())
    }
    // T-1: le foto cancellate prima del backup non tengono la testata lontana da «Al sicuro»: si dicono fra i problemi della pagina.
    BackupStatusReducers.heroProblem(counts.problems)?.let { (code, count) -> return BackupStatus.Problem(code, count) }
    // P14 (5B, D17, E-E6): versioni ferme perche' il PC ha cambiato il file sul NAS. Non e' un problema di un media: lo dice la pagina, con la
    // Diagnostica; riparte da solo dopo la verifica del NAS.
    fiveB.sourceChangedHolds().takeIf { it > 0 }?.let { return BackupStatus.Problem(BackupProblemCode.SOURCE_CHANGED, it) }
    return BackupStatus.UpToDate(counts.safe, memory.lastComplete())
  }

  /**
   * I media ancora da mettere al sicuro, sul catalogo (gli stessi della pagina). VE-9: un media la cui versione attuale aspetta il PC
   * (`SOURCE_CHANGED`) non e' "in coda": lo dice "Versioni in attesa del computer". Prima contava fra i media in coda e la pagina diceva per
   * sempre "1 in coda · prossimo giro".
   */
  private suspend fun queuedOf(counts: SafetyCounts): Int = BackupStatusReducers.queuedOf(counts, held = fiveB.sourceChangedCurrentMedia())

  /**
   * D-9, D-11 (prove sul campo): i byte che restano da caricare: quelli dei media in coda sul catalogo (senza copia sul NAS) meno quelli gia'
   * arrivati nei temporanei dei file a meta' (il punto di ripresa del diario, ogni 64 MiB) o gia' caricati e non ancora collegati. Null se il
   * catalogo non lo sa. Due query, mai il NAS.
   */
  private suspend fun remainingBytes(sources: BackupSources): Long? {
    if (sources.isEmpty) return null
    val queuedBytes = try {
      backupCatalogOrNull()?.queuedBytes(sources)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } ?: return null
    return (queuedBytes - inFlightBytes()).coerceAtLeast(0L)
  }

  /** I byte gia' sul NAS degli elementi aperti del diario che il catalogo conta ancora in coda: il punto di ripresa, o il file intero dopo il caricamento. */
  private suspend fun inFlightBytes(): Long = withContext(Dispatchers.IO) {
    runCatching {
      database.openHelper.readableDatabase.query(
        "SELECT COALESCE(SUM(CASE WHEN state = 'uploading' THEN resume_at " +
          "WHEN state IN ('uploaded', 'verified', 'publishing', 'published') THEN size ELSE 0 END), 0) " +
          "FROM backup_item WHERE is_open = 1 AND kind = 'new_copy'",
      ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }.getOrDefault(0L)
  }

  /**
   * **BK-5**: un giro del motore sta per partire. L'avanzamento si appoggia alla coda di adesso sul catalogo, non ai fatti del motore, che
   * arrivano un po' dopo il catalogo (vedi [RunProgress]): il primo stato visto di una campagna parte da qui. Va chiamato col turno del
   * backup in mano, prima di far girare il motore; senza catalogo o senza sorgenti non fa niente.
   */
  suspend fun roundStarting() {
    nothingToDo = false
    unservedQueue = -1
    try {
      val catalog = backupCatalogOrNull() ?: return
      val sources = settings.sources.value
      if (sources.isEmpty) return
      progress.roundStarting(queuedOf(catalog.safetyCounts(sources)))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Un conto per la barra non deve fermare il backup: il primo stato si appoggia ai fatti del motore, come prima.
      GalleryLog.w(TAG) { "coda di partenza non letta: ${e.javaClass.simpleName}" }
    }
  }

  /**
   * **BK-3**: l'ultimo problema che ha fermato un giro, **se il mondo di adesso lo conferma**. Un problema ricordato non e' un fatto: i permessi
   * dei media si leggono adesso (se l'utente li ha concessi il problema e' risolto, ed e' tolto: prima restava sulla pagina fino alla notte e
   * "Consenti" non faceva piu' niente), e la sola lettura dell'area e' stata appena rimessa alla prova (`refreshArea`: se il cancello non e'
   * piu' `READ_ONLY_AREA` la sola lettura non c'e' piu'). Gli altri (NAS pieno, quota, password) li toglie un giro riuscito, "Riprova" o il
   * ritorno da un'azione ([AppBackupActions.onPageResumed]): l'app non puo' saperlo da sola.
   */
  private suspend fun rememberedProblem(access: PhoneAccess?): BackupProblemCode? {
    val problem = memory.lastProblem() ?: return null
    val solved = when (problem) {
      BackupProblemCode.MEDIA_PERMISSION -> access != null && access.media != MediaAccess.NONE
      BackupProblemCode.MEDIA_LOCATION -> access != null && access.media != MediaAccess.NONE && access.mediaLocation
      BackupProblemCode.PARTIAL_ACCESS -> access != null && access.media == MediaAccess.FULL
      // Qui il cancello non e' `READ_ONLY_AREA` (sarebbe tornato prima): l'area non e' piu' in sola lettura.
      BackupProblemCode.READ_ONLY_AREA -> true
      else -> false
    }
    if (!solved) return problem
    // Dopo l'azzeramento la memoria locale non si riscrive.
    if (!stopped.get()) memory.setProblem(null)
    return null
  }

  /** E-P15 (BK-12): il backup ha qualcosa in coda e non fa strada da tre giorni (ora del dispositivo, I8): stessa regola del riepilogo giornaliero. */
  private suspend fun isStalled(queued: Int): Boolean {
    val facts = BackupDailyFacts(
      queued = queued,
      lastBeatAt = listOfNotNull(memory.photoBeat.lastBeatAt(), memory.videoBeat.lastBeatAt()).maxOrNull(),
      startedAt = memory.startedAt(),
      problem = null,
      lostBeforeBackup = 0,
      notIntact = 0,
      commonCopyLost = 0,
    )
    return NasDailySummary.backupProblems(facts, deviceNow()).any { it is NasDailySummary.Problem.BackupStalled }
  }

  /** D12 e R2: il backup non scrive in [share] perche' non e' di prova, o perche' l'account e' di prova e la recinzione del telefono non c'e'. */
  private fun testOnly(share: String): Boolean = !nasInputs.flags.backupMayWriteTo(share) || !nasInputs.flags.backupMayWriteAs(nasInputs.account())

  /**
   * Fase I (12/11, BF-1): fino a quando (ora monotona) un lavoro chiesto adesso dall'utente «sta partendo», e con quale trigger. Prima, fra il tocco su
   * «Inizia ora» e l'avvio del lavoro da parte di Android, la pagina diceva «In attesa · al prossimo giro» e offriva «Fai ora».
   */
  @Volatile private var starting: Pair<Long, BackupTrigger>? = null

  /**
   * Fase I (BF-1, BS2-2): l'utente ha chiesto il lavoro adesso e **Android l'ha accettato**: la pagina dice che sta partendo, al piu' per
   * [USER_START_WINDOW_MS]. Lo toglie il motore che prende il giro, la scadenza, una coda vuota o «Ferma».
   */
  fun userStarting(trigger: BackupTrigger) {
    starting = (monotonicMs() + USER_START_WINDOW_MS) to trigger
    kick()
  }

  /** Fase I (BS2-2): c'e' un «sto partendo» in corso? (Per le prove del produttore.) */
  internal fun userStartPending(): Boolean = starting != null

  /**
   * Fase I (BF-1, BS2-2): lo stato provvisorio «sto partendo» (un `Running` senza numeri fatti) finche' il motore non prende il giro; scaduta la finestra, o
   * senza niente in coda, lo stato vero con il motivo vero. **Mai sopra un motivo di adesso che ferma proprio il lavoro dell'utente** (la rete, il NAS, il
   * caldo critico, il congelamento: le regole di «Fai ora», non quelle del lavoro di fondo): quel motivo, subito.
   */
  private suspend fun startingStatus(queued: Int, sources: BackupSources): BackupStatus? {
    val (until, trigger) = starting ?: return null
    val left = until - monotonicMs()
    if (left <= 0L || queued <= 0) {
      starting = null
      return null
    }
    recheckLater(left)
    val bytes = remainingBytes(sources) ?: 0L
    BackupStatusReducers.userStartBlock(userDecision(estimatedBytes = null, confirmed = true))?.let { reason ->
      return waitingStatus(queued, bytes, reason, stalled = false, storageLow = phoneStorageLow())
    }
    return BackupStatus.Running(
      kind = BackupKind.PHOTOS,
      done = 0,
      total = queued,
      bytesDone = 0L,
      bytesTotal = bytes,
      etaMillis = null,
      trigger = trigger,
      starting = true,
    )
  }

  /** Perche' il backup aspetta adesso, dalle regole delle attivita' (nessuna rete, nessun orologio del telefono). */
  suspend fun waitReason(): WaitReason {
    val decision = nasInputs.rules.evaluate(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP, writesToNas = true))
    return BackupStatusReducers.waitReasonOf(decision)
  }

  private suspend fun sourceStatuses(catalog: BackupCatalog?, chosen: BackupSources): List<SourceStatus> {
    catalog ?: return emptyList()
    val all = LinkedHashSet<BackupSource>()
    all += BackupSource.Camera
    all += BackupSource.Screenshots
    all += chosen.sources
    // Le cartelle scelte vincono sul tipo (O-2, come il piano): uno screenshot dentro una cartella scelta sta nella riga della cartella, non anche
    // in quella degli screenshot. Prima compariva due volte nella pagina.
    val folders = BackupSources(chosen.sources.filterIsInstanceTo(LinkedHashSet<BackupSource.Folder>()))
    return all.map { source ->
      val counts = if (source is BackupSource.Folder) {
        catalog.safetyCounts(BackupSources(setOf(source)))
      } else {
        catalog.safetyCounts(BackupSources(setOf(source)), outside = folders)
      }
      // T-1: la riga conta cio' che si puo' ancora salvare o e' al sicuro; i persi restano nei problemi (la pagina li dice a parte, una volta).
      SourceStatus(
        source = source,
        active = source in chosen,
        media = BackupStatusReducers.mediaOf(counts),
        safe = counts.safe,
        queued = BackupStatusReducers.queuedOf(counts),
        problems = counts.problems,
      )
    }
  }

  // --- Il riepilogo del primo backup (una volta, in cache) --------------------------------------------------------------------------

  private val summaryLock = Mutex()
  private val summaryCache = SummaryCache(SUMMARY_REFRESH_MS)

  /**
   * Il riepilogo del primo backup (§2.11): costa circa 2 query per candidato (13 mila media), quindi si calcola **una volta** e si tiene
   * in cache. Si rifa' solo se cambiano le sorgenti o cio' che si aspetta (la scansione dell'area privata, il confronto delle copie
   * simili), e al massimo una volta al minuto mentre i numeri sono ancora provvisori.
   */
  suspend fun summary(): BackupSummary = summaryLock.withLock {
    // Fase I (BS2-3): la generazione **prima** delle parti; un riepilogo calcolato su parti che nel frattempo `start` ha cambiato non vale (ne' in cache ne'
    // restituito). `summaryLock` non prende mai `startLock`: nessun ordine inverso.
    val generation = summaryCache.generation()
    // Fase I (punto 4): senza le parti (il NAS non ancora pronto) il riepilogo non c'e' ancora: «cerco» (con il motivo, se una regola ferma la ricerca),
    // mai uno zero "assestato". Lo stesso con le parti di un'altra area (`start` le sta ricostruendo).
    val p = parts
    val share = privateShare()
    if (p == null || share == null || !p.share.equals(share, ignoreCase = true)) return@withLock counting()
    val sources = settings.sources.value
    // BK-8: la chiave comprende cio' che il backup ha fatto (media al sicuro e in coda, una query di conteggi): un riepilogo "assestato" non
    // resta piu' quello di una settimana fa, con i numeri e la stima dell'intero primo backup. Il calcolo pieno costa (due query per
    // candidato): si rifa' solo se qualcosa e' cambiato davvero.
    val counts = if (sources.isEmpty) SafetyCounts.ZERO else p.backupCatalog.safetyCounts(sources)
    // Fase I (BS-1, BF-3): con l'account, l'area delle parti e la scansione finita o no: numeri e cancello si rifanno insieme quando cambiano.
    val key = SummaryCache.keyOf(
      sources.sources.map { it.id }, similar.progress.value.pending == 0, counts, nasInputs.account(), p.share, privateAreaScanned(),
    )
    val base = summaryCache.summaryOf(key, monotonicMs(), generation, stillCurrent = { parts === p }) { p.planner.summary() } ?: return@withLock counting()
    base.copy(existing = live(base.existing))
  }

  /** «Sto contando»: il riepilogo non c'e' (ancora, o piu'), con il motivo che ferma la ricerca se ce n'e' uno. */
  private suspend fun counting(): BackupSummary = BackupSummary.COUNTING.let { it.copy(existing = live(it.existing)) }

  /**
   * Fase I (12/11): sopra il riepilogo (anche quello in cache) quello che cambia da un minuto all'altro e non tocca i numeri ([ExistingCopiesLive]): il
   * motivo che ferma la ricerca o la preparazione delle anteprime (solo se il confronto ne aspetta), e quanti file del NAS la scansione ha gia' guardato.
   * Il piano non lo legge.
   */
  private suspend fun live(state: ExistingCopiesState): ExistingCopiesState {
    val scan = if (state.privateAreaScanned) null else privateShare()?.let { share -> nas.runtime?.scannerOf(share)?.status?.value }
    return ExistingCopiesLive.of(state, scan, similar.awaitingThumbnails.value.isNotEmpty()) { request -> nasInputs.rules.evaluate(request) }
  }

  /**
   * I byte che un lavoro dell'utente deve ancora caricare, per la conferma sui dati mobili e per i tetti (proposta 10). **Mai un'attesa lunga**
   * davanti a un tocco (BK-8): il riepilogo in un processo nuovo costa secondi; se non e' pronto in [ESTIMATE_WAIT_MS] vale l'ultimo che c'e'
   * (anche vecchio) o nessuna stima. Le regole usano la stima solo per il tetto e per dirlo nella conferma: senza, la conferma c'e' lo stesso.
   */
  suspend fun uploadEstimateBytes(): Long? {
    val fresh = runCatching { withTimeoutOrNull(ESTIMATE_WAIT_MS) { summary().toUpload.bytes } }.getOrNull()
    return fresh ?: summaryCache.last()?.toUpload?.bytes
  }

  // --- Condizioni e conteggi --------------------------------------------------------------------------------------------------------

  private suspend fun currentConditions(): PhoneConditions {
    val access = phoneProvider()?.access?.state?.value
    val pluggedIn = runCatching { nasInputs.stateProvider.current().power.pluggedIn }.getOrNull()
    return PhoneConditions(
      onMobileData = nasInputs.metered(),
      pluggedIn = pluggedIn,
      partialMediaAccess = access?.media == MediaAccess.PARTIAL,
      mediaLocationMissing = access != null && access.media != MediaAccess.NONE && !access.mediaLocation,
      phoneStorageLow = noBackup.usableSpace < LOW_STORAGE_BYTES,
    )
  }

  /** Gli ultimi risultati del giro per la Diagnostica (solo numeri). */
  val lastRound = MutableStateFlow<RoundNumbers?>(null)

  /** I byte delle copie private verificate (la quota della rotazione, Q10): una query, nessuna lettura del NAS. */
  private suspend fun protectedBytes(share: String): Long? = withContext(Dispatchers.IO) {
    runCatching {
      database.openHelper.readableDatabase.query(
        "SELECT COALESCE(SUM(size), 0) FROM media_location WHERE location_type = 'nas' AND share_key = ? AND state = 'present' AND verified_at IS NOT NULL",
        arrayOf<Any?>(dev.pampa.mycloudgallery.core.model.Location.Nas.shareKeyOf(share)),
      ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()
  }

  /** Il giro in corso o appena finito, solo numeri (Diagnostica). */
  data class RoundNumbers(val done: Int, val linked: Int, val gone: Int, val failed: Int, val waiting: Int, val bytesUploaded: Long, val bytesReread: Long, val stop: String)

  /** Il verdetto delle regole per un "Fai ora" con la stima: per la conferma sui dati mobili (proposta 10). */
  suspend fun userDecision(estimatedBytes: Long?, confirmed: Boolean): RuleDecision =
    nasInputs.rules.evaluate(WorkRequest.UserInitiated(BackgroundActivity.PHOTO_BACKUP, estimatedBytes, confirmed, writesToNas = true))

  companion object {
    private const val TAG = "BackupGraph"

    /** Fase 7 (D28): il registro delle richieste del guardiano dopo una condivisione (in `noBackupFilesDir/albums/`, tolto dall'azzeramento). */
    const val SAFE_COPY_REQUESTS_FILE: String = "albums/safe-copy-requests"

    /**
     * Lo stato di un backup che ha media in coda e non sta lavorando (BK-12): pura, provata su JVM. I codici che esistevano solo in Diagnostica
     * o che nessuno produceva arrivano alla pagina con la loro azione: "fermo da 3 giorni" (guida della batteria), il tetto dati raggiunto
     * ("Fai ora" con conferma) e la memoria del telefono quasi piena. Altrimenti il solito "N in coda" con il suo perche'. Gli altri due
     * codici (`WAITING_UNLOCK`, `PROFILE_INACTIVE`) non possono comparire qui: la pagina esiste solo con il telefono sbloccato e il profilo
     * attivo, e un'app che non gira non puo' dirlo.
     */
    internal fun waitingStatus(queued: Int, bytes: Long, wait: WaitReason, stalled: Boolean, storageLow: Boolean): BackupStatus = when {
      stalled -> BackupStatus.Problem(BackupProblemCode.STALE, 0)
      wait == WaitReason.DATA_CAP -> BackupStatus.Problem(BackupProblemCode.CAP_REACHED, 0)
      storageLow -> BackupStatus.Problem(BackupProblemCode.PHONE_STORAGE_LOW, 0)
      else -> BackupStatus.Waiting(queued, bytes, wait)
    }

    val EXCLUSIVE_WAIT: Duration = 90.seconds

    /** BK-6: un tocco dell'utente aspetta il turno quanto la tappa piu' lunga (otto minuti piu' un lotto), non 90 secondi. */
    val USER_EXCLUSIVE_WAIT: Duration = 15.minutes

    /** VE-3: quanto puo' durare la verifica sul momento di "Metti al sicuro adesso" (una copia, anche un video grande). */
    private val SAFEGUARD_BUDGET: Duration = 10.minutes

    /** Quanto un tocco aspetta il calcolo della stima prima di andare avanti senza (BK-8). */
    private const val ESTIMATE_WAIT_MS: Long = 3_000L
    private val REFRESH_GAP: Duration = 1.seconds
    private const val EXCLUSIVE_POLL_MS: Long = 500L
    private const val SUMMARY_REFRESH_MS: Long = 60_000L

    /** Fase I: ogni quanto si rilegge l'avanzamento della scansione dell'area privata (e il motivo di una pausa) mentre il primo backup aspetta. */
    private const val SCAN_REFRESH_MS: Long = 10_000L

    /** Fase I (BF-1): quanto dura al piu' il «sto partendo» dopo il tocco dell'utente, se Android non avvia il lavoro. */
    private const val USER_START_WINDOW_MS: Long = 60_000L
    private const val LOW_STORAGE_BYTES: Long = 1L shl 30
    private const val RETRY_LIMIT: Int = 5_000
  }
}

/** Le radici in cui il backup puo' pubblicare (§2.2 "Regole"): `Backup/`, oppure `<radice di prova>/Backup/` con la recinzione di D13. */
object BackupRoots {
  fun of(fenceRoot: SharePath?): SharePath = fenceRoot?.child("Backup") ?: SharePath.of("Backup")
}

/**
 * Cio' che il riepilogo giornaliero sa del backup (Fase 5A, P7; docs/fase5-progetto.md §2.6, E-P15): **solo numeri e codici**.
 *
 * @property queued media in coda (non sul NAS o non verificati, senza i problemi dei singoli media).
 * @property lastBeatAt l'ora del dispositivo dell'ultimo giro che ha fatto strada, o null.
 * @property startedAt da quando il backup ha una cartella del dispositivo (ora del dispositivo), o null.
 * @property problem l'ultimo problema che ha fermato un giro (quota, NAS pieno...), o null se l'ultimo giro e' andato bene.
 */
data class BackupDailyFacts(
  val queued: Int,
  val lastBeatAt: Long?,
  val startedAt: Long?,
  val problem: BackupProblemCode?,
  val lostBeforeBackup: Int,
  val notIntact: Int,
  val commonCopyLost: Int,
)
