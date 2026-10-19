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
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.MediaId
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
import dev.pampa.mycloudgallery.nas.NasInputs
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
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
 * - **D12**: il [gate] non lascia scrivere su una share che non e' di prova (`FeatureFlags.backupMayWriteTo`, vero in release): lo stato
 *   dice "Backup in prova" e niente si scrive; anche le verifiche e il guardiano si fermano. Vale per ogni build;
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
  val gate: BackupGate = AppBackupGate(
    BackupGateInputs(
      flags = nasInputs.flags,
      familyReady = { familyReady() && nasInputs.trust.writePermit() != null },
      privateShare = ::privateShare,
      account = { nasInputs.account() },
      writesStopped = { nas.emergency.stopped },
      frozen = { share -> nas.frozen.value.containsKey(share.lowercase(Locale.ROOT)) },
      readOnly = { share -> share.lowercase(Locale.ROOT) in readOnlyShares },
      paused = memory::isPaused,
    ),
  )

  /** Il telefono come lo legge il backup: righe e byte del MediaStore, con la recinzione di D13. */
  val phone: PhoneBytes = MediaStorePhoneBytes(
    rows = { phoneProvider()?.rows },
    access = { phoneProvider()?.access?.state?.value ?: PhoneAccess.NONE },
    fence = inputs.fence,
    account = { nasInputs.account() },
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
      partsFlow.value = null
      readOnlyShares.clear()
    }
    if (!nas.start()) return@withLock false
    val runtime = nas.runtime ?: return@withLock false
    val catalog = catalogProvider() ?: return@withLock false
    val share = privateShare() ?: return@withLock false
    val deviceId = nasInputs.deviceId() ?: return@withLock false
    val user = nasInputs.account() ?: return@withLock false
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
      indexing = PreparingUploadIndexing(runtime.preparer, runtime.publisher, CatalogUploadRecords(catalog)),
      foreground = foreground,
    )
    // D13: con la recinzione, il backup scrive solo sotto `mcg-campo/Backup/` delle share di prova.
    val fenceRoot = inputs.fence?.destinationRoot(user)
    val config = UploadConfig(allowedRoots = listOf(BackupRoots.of(fenceRoot)))
    val engine = BackupEngineFactory.create(session, deviceId, share, ports, config)
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
      gate = gate,
      clock = clock,
      privateShare = ::privateShare,
      commonShare = ::commonShare,
      destinationRoot = { fenceRoot },
    )
    if (stopped.get()) return@withLock false
    partsFlow.value = Parts(share, deviceId, catalog, backupCatalog, planner, engine, light, heavy, guardian)
    // Il catalogo cambia (un media arriva al sicuro, la lettura del telefono porta novita'): le schermate rileggono, accorpato.
    changesWatcher = scope.launch { catalog.changes.collect { kick() } }
    kick()
    true
  }

  /** L'azzeramento (R7): niente parte piu' in questo processo; il giro in corso si ferma prima del file successivo. Prima di ogni cancellazione. */
  fun stop() {
    stopped.set(true)
    val engine = partsFlow.value?.engine
    refresher?.cancel()
    refresher = null
    changesWatcher?.cancel()
    changesWatcher = null
    partsFlow.value = null
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
  suspend fun <T> exclusive(waitFor: Duration = EXCLUSIVE_WAIT, block: suspend () -> T): T? {
    val deadline = monotonicMs() + waitFor.inWholeMilliseconds
    // tryLock e non lock + timeout: un'acquisizione che coincide con la scadenza non puo' lasciare il lucchetto preso.
    while (!runLock.tryLock()) {
      if (monotonicMs() >= deadline) return null
      kotlinx.coroutines.delay(EXCLUSIVE_POLL_MS)
    }
    try {
      return block()
    } finally {
      runLock.unlock()
    }
  }

  /**
   * "Ferma" (pagina, notifica): il giro in corso si ferma prima del file successivo e ogni temporaneo resta con il suo punto di ripresa.
   *
   * **R4**: "Ferma" dell'utente ([StopBy.USER]) e' una **pausa**: il segno resta (anche fra un giro e l'altro e dopo la morte del processo)
   * finche' l'utente non tocca "Riprendi" o "Fai ora" ([resumeFromPause]). Il lavoro a tappe dopo una lettura del telefono o il giro delle
   * sei ore non la scavalcano: il cancello e' `PAUSED`. Il segno vale subito (in memoria), il file segue.
   */
  fun requestStop(by: StopBy) {
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
  suspend fun next(): Next {
    val p = parts ?: return Next.Nothing
    if (!gate.stateOf(p.share).open || settings.deviceFolder.value == null) return Next.Nothing
    if (inputs.ruleSettings.current().protectNewPhotos && p.planner.next(BackupKind.PHOTOS, 1, BackupTrigger.QUICK_NEW_PHOTOS).isNotEmpty()) return Next.Quick
    if (p.planner.next(BackupKind.PHOTOS, 1, BackupTrigger.SCHEDULED).isNotEmpty()) return Next.Photos
    if (p.planner.next(BackupKind.VIDEOS, 1, BackupTrigger.SCHEDULED).isNotEmpty()) return Next.Videos
    // Elementi aperti nel diario (un giro interrotto, un file aperto dal PC): si riprendono dal lavoro dei tipi.
    if (store.itemsToResume(1).isNotEmpty()) return Next.Photos
    return Next.Nothing
  }

  /** Le impostazioni delle regole (D34: "Proteggi subito le foto nuove"), per la corsa delle foto nuove. */
  val ruleSettings get() = inputs.ruleSettings

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
    // Un elemento rimasto aperto (un giro interrotto, un file aperto dal PC): si riprende dal giro del suo tipo.
    return trigger != BackupTrigger.QUICK_NEW_PHOTOS && store.itemsToResume(1).isNotEmpty()
  }

  /** I record e le miniature dei media caricati vanno all'indice: pubblicazione dell'outbox, sulle share vere solo locale (D31). */
  suspend fun flushIndex() {
    nas.flushOutbox()
  }

  /** Ci sono copie del guardiano da fare (media propri con la sola copia nell'area comune)? */
  suspend fun guardianHasWork(): Boolean {
    val p = parts ?: return false
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
    val marked = counts.problems.filterKeys { it.marksMedia }
    return BackupDailyFacts(
      queued = (counts.noNasCopy + counts.unverified - marked.values.sum()).coerceAtLeast(0),
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
  }

  private suspend fun recompute() {
    val sources = settings.sources.value
    val share = privateShare()
    val access = phoneProvider()?.access?.state?.value
    val catalog = backupCatalogOrNull()
    val counts = if (catalog != null && !sources.isEmpty) catalog.safetyCounts(sources) else SafetyCounts.ZERO

    val status = composeStatus(sources, share, access, counts)
    statusFlow.value = status
    sourcesFlow.value = sourceStatuses(catalog, sources)
    footerFlow.value = BackupStatusReducers.footer(status, similar.progress.value.pending)
  }

  private suspend fun composeStatus(sources: BackupSources, share: String?, access: PhoneAccess?, counts: SafetyCounts): BackupStatus {
    // D12 vale per primo: sulle share vere il backup e' "in prova", qualunque altra cosa manchi. Con R2, in release, anche per un account
    // di prova (senza la recinzione del telefono di D13 non si scrive da nessuna parte).
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

    val engineStatus = parts?.engine?.status?.value
    if (engineStatus is BackupStatus.Running) return engineStatus
    memory.lastProblem()?.let { problem -> return BackupStatus.Problem(problem, 0) }
    if (engineStatus is BackupStatus.Stopped) return engineStatus

    val problems = counts.problems.filterKeys { it.marksMedia }
    val queued = (counts.noNasCopy + counts.unverified - problems.values.sum()).coerceAtLeast(0)
    if (queued > 0) {
      val bytes = runCatching { store.counts().bytesOpen }.getOrDefault(0L)
      return BackupStatus.Waiting(queued, bytes, waitReason())
    }
    problems.entries.minByOrNull { it.key.ordinal }?.let { (code, count) -> return BackupStatus.Problem(code, count) }
    return BackupStatus.UpToDate(counts.safe, memory.lastComplete())
  }

  /** D12 e R2: il backup non scrive in [share] perche' non e' di prova, o perche' l'account e' di prova e la recinzione del telefono non c'e'. */
  private fun testOnly(share: String): Boolean = !nasInputs.flags.backupMayWriteTo(share) || !nasInputs.flags.backupMayWriteAs(nasInputs.account())

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
    return all.map { source ->
      val counts = catalog.safetyCounts(BackupSources(setOf(source)))
      val problems = counts.problems.filterKeys { it.marksMedia }
      val media = counts.safe + counts.unverified + counts.onlyCommonCopy + counts.noNasCopy + counts.lost
      SourceStatus(
        source = source,
        active = source in chosen,
        media = media,
        safe = counts.safe,
        queued = (counts.noNasCopy + counts.unverified - problems.values.sum()).coerceAtLeast(0),
        problems = counts.problems,
      )
    }
  }

  // --- Il riepilogo del primo backup (una volta, in cache) --------------------------------------------------------------------------

  private class CachedSummary(val key: String, val at: Long, val summary: BackupSummary)

  private val summaryLock = Mutex()
  private var summaryCache: CachedSummary? = null

  /**
   * Il riepilogo del primo backup (§2.11): costa circa 2 query per candidato (13 mila media), quindi si calcola **una volta** e si tiene
   * in cache. Si rifa' solo se cambiano le sorgenti o cio' che si aspetta (la scansione dell'area privata, il confronto delle copie
   * simili), e al massimo una volta al minuto mentre i numeri sono ancora provvisori.
   */
  suspend fun summary(): BackupSummary = summaryLock.withLock {
    val p = parts ?: return@withLock BackupSummary.EMPTY
    val key = summaryKey(settings.sources.value, similar.progress.value.pending)
    val now = monotonicMs()
    val cached = summaryCache
    if (cached != null && cached.key == key && (!cached.summary.comparing || now - cached.at < SUMMARY_REFRESH_MS)) return@withLock cached.summary
    p.planner.summary().also { summaryCache = CachedSummary(key, now, it) }
  }

  private fun summaryKey(sources: BackupSources, pending: Int): String = sources.sources.map { it.id }.sorted().joinToString(",") + "|" + (pending == 0)

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

  private companion object {
    const val TAG = "BackupGraph"
    val EXCLUSIVE_WAIT: Duration = 90.seconds
    val REFRESH_GAP: Duration = 1.seconds
    const val EXCLUSIVE_POLL_MS: Long = 500L
    const val SUMMARY_REFRESH_MS: Long = 60_000L
    const val LOW_STORAGE_BYTES: Long = 1L shl 30
    const val RETRY_LIMIT: Int = 5_000
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
