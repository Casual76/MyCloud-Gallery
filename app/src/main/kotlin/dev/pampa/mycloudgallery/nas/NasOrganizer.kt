package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.index.compact.Compactor
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.NasRemoteIndex
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.index.replica.store.RoomReplicaStore
import dev.pampa.mycloudgallery.core.index.store.RoomIndexPublishStore
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasops.clean.MaintenanceReport
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.LargestFile
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeReport
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.nasops.engine.HeavyWorkPolicy
import dev.pampa.mycloudgallery.core.nasops.engine.KnownHashes
import dev.pampa.mycloudgallery.core.nasops.engine.ShareAccessView
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasFolders
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasOperations
import dev.pampa.mycloudgallery.core.nasops.noop.NoNasSpace
import dev.pampa.mycloudgallery.core.nasops.store.RoomOpsStore
import dev.pampa.mycloudgallery.core.nasops.trash.TrashSigningIdentity
import dev.pampa.mycloudgallery.core.nasops.wiring.NasOpsConfig
import dev.pampa.mycloudgallery.core.nasops.wiring.NasOpsServices
import dev.pampa.mycloudgallery.core.nasops.wiring.NasOpsWiring
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncReport
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncStatus
import dev.pampa.mycloudgallery.core.personal.remote.NasPersonalRemote
import dev.pampa.mycloudgallery.core.personal.settings.SyncedPersonalSettings
import dev.pampa.mycloudgallery.core.personal.store.CatalogPersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.FilePersonalLedger
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.sync.NasPersonalSync
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreaKind
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreas
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionTarget
import dev.pampa.mycloudgallery.feature.viewer.menu.NasAreaScope
import dev.pampa.mycloudgallery.feature.viewer.menu.NasLocationInfo
import dev.pampa.mycloudgallery.feature.viewer.menu.NasMediaActions
import dev.pampa.mycloudgallery.feature.viewer.menu.NasMoveDestination
import dev.pampa.mycloudgallery.work.NasOpsJobs
import java.io.File
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Un lavoro che l'azzeramento puo' fermare e aspettare (`GalleryGraph.runIfActive`): null se la galleria e' ferma o la famiglia non e'
 * pronta. Lo passa `GalleryGraph` a [NasGraph], che lo passa qui.
 */
interface ActiveWork {
  suspend fun <T> run(block: suspend () -> T): T?

  companion object {
    /** Senza galleria (test dei pezzi): il blocco gira e basta. */
    val DIRECT: ActiveWork = object : ActiveWork {
      override suspend fun <T> run(block: suspend () -> T): T = block()
    }
  }
}

/**
 * **Organizzare il NAS** (Fase 4B, P16; docs/fase4-progetto.md §1.4 per la 4B, §2.7, §2.9): la parte del grafo del NAS che lega a
 * `:app` le operazioni sui file (P12), il cestino e lo spazio del NAS, le cartelle, la manutenzione dell'indice e i dati personali
 * sincronizzati (P13). Uno per [NasGraph], cioe' uno per processo.
 *
 * **Due tempi**, come il resto del grafo: nel costruttore nessun I/O (il diario Room, le facciate che rispondono "niente" finche' il NAS
 * non c'e'); in [attach], con il motore del NAS partito (account, chiave del dispositivo, aree), i servizi veri.
 *
 * **Sicurezza** (§6.1, D24, D25, D26; `formato-nas.md` §9):
 * - le primitive di scrittura le costruisce solo `NasOpsWiring` (core-nasops): qui non si vedono;
 * - un'operazione **nasce** solo da `start` con la conferma che la UI ha costruito dopo un tocco; i lavori in background (il
 *   [dev.pampa.mycloudgallery.work.NasOpsJobService], la pulizia del cestino) **riprendono** dal diario locale e basta;
 * - la pulizia del cestino legge solo il diario locale; i record del NAS servono solo a mostrare;
 * - i dati personali si pubblicano solo nell'area privata dell'account (`NasPersonalSync`, M7) e sulle share vere restano sul
 *   telefono finche' vale D31;
 * - nei log solo codici e numeri, mai share, percorsi, nomi o id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasOrganizer(
  private val scope: CoroutineScope,
  private val database: GalleryDatabase,
  private val inputs: NasInputs,
  private val noBackup: File,
  private val catalogProvider: suspend () -> MediaCatalog?,
  private val monotonicMs: () -> Long,
  /** Congelamento (D10), salto dell'ora (D32), emergenza (D31): lo compone [NasGraph]. */
  private val gate: OpsGate,
  /** Il salto dell'ora del NAS sospende la datazione delle modifiche personali (D32). */
  private val clockBlocked: () -> Boolean,
  private val work: ActiveWork,
  private val jobs: NasOpsJobs,
  /** L'app e' in primo piano: solo allora la sincronizzazione accorpata dei dati personali parte da sola (§2.9). */
  private val foreground: () -> Boolean,
  /** Un'operazione si e' chiusa: il contenuto delle cartelle e' cambiato, la Libreria va riverificata. */
  private val onOperationsClosed: () -> Unit,
  /** Il nome dell'area per i fogli del viewer ("La mia area", "Area comune"). */
  private val areaLabel: (NasArea) -> String,
  /** Il valore locale di "Mostra le copertine degli album" (prima della 4B era solo del dispositivo) e il suo specchio. */
  private val localCovers: () -> Boolean = { false },
  private val mirrorCovers: (Boolean) -> Unit = {},
) {

  /** Il diario delle operazioni e del cestino (schema v3): nel database della galleria, quindi l'azzeramento lo toglie con lei. */
  val opsStore: RoomOpsStore = RoomOpsStore(database)

  /** Cio' che nasce in [attach]. */
  class Parts internal constructor(
    val account: AccountId,
    val user: String,
    val deviceId: String,
    val areas: List<NasShareArea>,
    val services: NasOpsServices,
    val personalStore: LedgerPersonalStore,
    /** Null senza un'area privata (nessun posto in cui pubblicare i dati personali, L23). */
    val personalSync: NasPersonalSync?,
    val personalSettings: SyncedPersonalSettings,
  )

  private val partsFlow = MutableStateFlow<Parts?>(null)
  val parts: Parts? get() = partsFlow.value
  val partsState: StateFlow<Parts?> = partsFlow.asStateFlow()

  // --- Orologi e facciate (rispondono anche prima di attach) --------------------------------------------------------------

  /** L'ora del NAS per cio' che si scrive o scade; l'orologio monotono per le durate e per l'eta' delle conferme (§4.3). */
  val clocks: OpClocks = object : OpClocks {
    override fun monotonicMs(): Long = this@NasOrganizer.monotonicMs()

    override fun nasNowMs(): Long? = inputs.nasClock.nowMillisOrNull()
  }

  /** Le operazioni come le vede la UI: quelle vere quando il NAS c'e', con l'avvio che sopravvive alla schermata ([AppNasOperations]). */
  val operations: NasOperations by lazy { AppNasOperations(this) }

  val trash: NasTrash = object : NasTrash {
    override val items: StateFlow<NasTrashState> = partsFlow
      .flatMapLatest { p -> p?.services?.trash?.items ?: flowOf(NasTrashState.Offline(emptyList())) }
      .stateIn(scope, SharingStarted.Eagerly, NasTrashState.Offline(emptyList()))

    override suspend fun refresh() {
      partsFlow.value?.services?.trash?.refresh()
    }

    override suspend fun purgeExpired(budget: Duration): PurgeReport = purgeTrash(budget)
      ?: PurgeReport(purged = 0, bytes = 0, kept = emptyMap(), alreadyGone = 0, deferred = 0, stoppedBy = PurgeStop.BLOCKED)
  }

  val space: NasSpace = object : NasSpace {
    override suspend fun load(share: String): NasSpaceState = partsFlow.value?.services?.space?.load(share) ?: NoNasSpace.load(share)
  }

  val folders: NasFolders = object : NasFolders {
    override fun observe(share: String, dir: SharePath?): Flow<NasFolderState> =
      partsFlow.flatMapLatest { p -> p?.services?.folders?.observe(share, dir) ?: NoNasFolders.observe(share, dir) }

    override suspend fun refresh(share: String, dir: SharePath?) {
      partsFlow.value?.services?.folders?.refresh(share, dir)
    }
  }

  /** Le aree dell'account per le schermate della 4B (prima la privata), dalle scelte dell'onboarding e dal registro. */
  val areas: NasAreas = object : NasAreas {
    override val areas: StateFlow<List<dev.pampa.mycloudgallery.feature.nas.contract.NasArea>> = partsFlow
      .map { p -> p?.areas.orEmpty().map { it.toScreenArea() } }
      .stateIn(scope, SharingStarted.Eagerly, emptyList())
  }

  /** Le voci "Sposta", "Rinomina", "Elimina…" del viewer per un media del NAS (P14): risolte dal catalogo, mai dalla UI. */
  val mediaActions: NasMediaActions = object : NasMediaActions {
    override val operations: NasOperations get() = this@NasOrganizer.operations

    override fun monotonicMs(): Long = clocks.monotonicMs()

    override suspend fun target(ref: MediaRef.Nas): NasActionTarget? = targetOf(ref)
  }

  /** Lo stato della sincronizzazione dei dati personali, per la Diagnostica. */
  val personalStatus: StateFlow<PersonalSyncStatus> = partsFlow
    .flatMapLatest { p -> p?.personalSync?.status ?: flowOf(PersonalSyncStatus.Idle) }
    .stateIn(scope, SharingStarted.Eagerly, PersonalSyncStatus.Idle)

  /** L'ultimo giro di ciascun lavoro della 4B, per la Diagnostica (solo numeri). */
  val lastPurge = MutableStateFlow<PurgeReport?>(null)
  val lastMaintenance = MutableStateFlow<MaintenanceReport?>(null)
  val lastPersonal = MutableStateFlow<PersonalSyncReport?>(null)

  // --- Vita ---------------------------------------------------------------------------------------------------------------

  @Volatile private var stopped = false
  private var watcher: Job? = null

  /**
   * Lega i servizi della 4B al motore del NAS appena partito. Legge il registro locale dei dati personali, carica il diario delle
   * operazioni (cosi' "Operazioni in corso" mostra subito anche quelle rimaste a meta') e, se ce ne sono, chiede il lavoro che le
   * riprende. Le eccezioni arrivano a chi chiama ([NasGraph] le registra e continua senza la 4B).
   */
  suspend fun attach(
    runtime: NasGraph.Runtime,
    user: String,
    identity: IndexIdentity,
    rules: IndexWriteRules,
    remote: NasRemoteIndex,
    publishStore: RoomIndexPublishStore,
    replicaStore: RoomReplicaStore,
    locks: ShareLocks,
    catalog: MediaCatalog,
    knownHashes: KnownHashes,
  ) {
    if (stopped || partsFlow.value != null) return
    val compactor = Compactor(
      identity = identity,
      clock = inputs.nasClock,
      fileSystem = { share -> inputs.session.fileSystem(share) },
      store = publishStore,
      remote = remote,
      rules = rules,
      locks = locks,
      // La 4B accende la compattazione (§1.2, §2.7): con le stesse condizioni della pulizia del cestino (IndexMaintenance).
      enabled = true,
    )
    val config = NasOpsConfig(
      deviceId = runtime.deviceId,
      accountId = user,
      shares = { runtime.areas.map { it.share } },
      clocks = clocks,
      gate = gate,
      // E-N9: le share in sola lettura le dice il NAS quando si prova a scrivere (PERMISSION_DENIED, l'elemento si ferma col suo
      // codice); una prova preventiva per share e' un residuo per il campo (F15).
      access = ShareAccessView.ALL_WRITABLE,
      registry = { inputs.registry() },
      signing = {
        val registry = inputs.registry()
        val signer = inputs.signer()
        if (registry != null && signer != null) TrashSigningIdentity(registry.registry.familyId, signer) else null
      },
      knownHashes = knownHashes,
      // E-D12: il ripiego dal telefono e' un'attivita' pesante: non sui dati a consumo.
      heavyWork = HeavyWorkPolicy { !inputs.metered() },
      largest = { share -> CatalogLargestFiles.of(database, runtime.account, share) },
      indexBytes = { share -> indexBytesOf(replicaStore, share) },
      publishedSegments = { share -> publishStore.publishedSegments(share) },
      currentSegments = { share -> publishStore.ownHead(share)?.segments?.mapTo(HashSet()) { it.hash }.orEmpty() },
      authorName = { id -> inputs.registry()?.registry?.device(id)?.name },
      compact = { share -> compactor.compactIfNeeded(share) },
    )
    val services = NasOpsWiring.build(inputs.session, opsStore, config)

    // Dati personali (§2.9): il registro locale in noBackupFilesDir/personal/, la proiezione sul catalogo, la sincronizzazione solo
    // nell'area privata dell'account. Senza ora del NAS fidata (salto, D32) le modifiche restano "da datare".
    val store = LedgerPersonalStore(
      author = runtime.deviceId,
      nasNow = { if (clockBlocked()) null else inputs.nasClock.nowMillisOrNull() },
      ledger = FilePersonalLedger(File(File(noBackup, PERSONAL_DIR), PERSONAL_LEDGER_FILE)),
      projection = CatalogPersonalProjection(catalog, database),
    )
    store.open()
    val privateArea = runtime.areas.firstOrNull { it.area == NasArea.PRIVATE }
    val personalGate = IndexWriteGate { share -> rules.blockFor(share) }
    val sync = privateArea?.let { area ->
      NasPersonalSync(
        share = area.share,
        account = user,
        remote = NasPersonalRemote(area.share, { share -> inputs.session.fileSystem(share) }, identity),
        store = store,
        identity = identity,
        clock = inputs.nasClock,
        gate = personalGate,
        monotonicMillis = monotonicMs,
      )
    }
    val settings = SyncedPersonalSettings(store, scope)
    // "Mostra le copertine" era solo del dispositivo: la scelta locale fatta prima della 4B diventa la prima voce sincronizzata.
    val coversKnown = store.entries.value.keys.any { it.kind == PersonalKind.SETTING && it.field == PersonalFields.SETTING_SHOW_ALBUM_COVERS }
    if (!coversKnown && localCovers()) settings.setShowAlbumCovers(true)

    val parts = Parts(runtime.account, user, runtime.deviceId, runtime.areas, services, store, sync, settings)
    if (stopped) return
    partsFlow.value = parts
    watcher = scope.launch {
      launch { settings.showAlbumCovers.collect { mirrorCovers(it) } }
      watchClosings(services.operations)
    }
    // Il diario si carica (lo stato mostrato) senza eseguire niente: budget zero. Se c'e' qualcosa a meta', lo riprende il lavoro.
    runCatching { work.run { services.operations.resumeAll(Duration.ZERO) } }
    if (hasOpenOperations()) jobs.resumeLater(0)
  }

  /** L'azzeramento: niente piu' servizi, niente piu' lavori in coda. Le operazioni in corso le ferma la galleria (sono sue figlie). */
  fun stop() {
    stopped = true
    watcher?.cancel()
    pendingPersonal?.cancel()
    partsFlow.value = null
    jobs.cancelAll()
  }

  /** Quando il numero di operazioni attive scende, una si e' chiusa: si riverifica (la Libreria e le cartelle sono cambiate). */
  private suspend fun watchClosings(ops: NasOperations) {
    var previous = ops.operations.value.count { it.active }
    ops.operations.map { list -> list.count { it.active } }.distinctUntilChanged().collect { active ->
      if (active < previous) onOperationsClosed()
      previous = active
    }
  }

  // --- Operazioni: avvio, ripresa, "Ferma" ------------------------------------------------------------------------------------

  /** Le operazioni del diario non ancora chiuse. */
  suspend fun hasOpenOperations(): Boolean = runCatching { opsStore.ops(OPEN_STATES).isNotEmpty() }.getOrDefault(false)

  /**
   * Riprende dal diario per al massimo [budget] (il lavoro delle operazioni). Null senza NAS o a galleria ferma. Mai una conferma nuova:
   * solo cio' che l'utente aveva gia' confermato.
   */
  suspend fun resumeOperations(budget: Duration): RunResult? {
    val ops = partsFlow.value?.services?.operations ?: return null
    return work.run { ops.resumeAll(budget) }
  }

  /**
   * Fra quanto riprovare gli elementi in attesa di un file aperto dal PC (E-N13): la prima attesa che scade, in ora del NAS. Null se
   * non c'e' niente in attesa o l'ora del NAS non e' nota.
   */
  suspend fun nextLockedRetryMs(): Long? {
    val now = inputs.nasClock.nowMillisOrNull() ?: return null
    val next = runCatching {
      opsStore.ops(OPEN_STATES).flatMap { opsStore.items(it.id) }.filter { it.waitingLocked }.mapNotNull { it.nextAttemptAtNas }.minOrNull()
    }.getOrNull() ?: return null
    return (next - now).coerceAtLeast(0)
  }

  /** "Ferma" dalla notifica del lavoro: ferma tutte le operazioni attive prima dell'elemento successivo (non distrugge niente). */
  suspend fun cancelActive() {
    val ops = partsFlow.value?.services?.operations ?: return
    for (state in ops.operations.value.filter { it.active }) operations.cancel(state.id)
  }

  // --- Cestino, manutenzione, dati personali ------------------------------------------------------------------------------------

  /** La pulizia del cestino scaduto (§2.7): solo voci del diario locale, ora del NAS, tetti per giro. */
  suspend fun purgeTrash(budget: Duration): PurgeReport? {
    val trash = partsFlow.value?.services?.trash ?: return null
    return work.run { trash.purgeExpired(budget) }?.also { lastPurge.value = it }
  }

  /** La compattazione dell'indice e la pulizia dei propri file (M6), una volta al giorno per share. */
  suspend fun maintainIndex(budget: Duration): MaintenanceReport? {
    val p = partsFlow.value ?: return null
    val maintenance = p.services.maintenance ?: return null
    return work.run { maintenance.run(p.areas.map { it.share }, budget) }?.also { lastMaintenance.value = it }
  }

  /** Un giro di sincronizzazione dei dati personali (`INDEX_SYNC` o accorpato dopo una modifica), poi la proiezione sul catalogo. */
  suspend fun syncPersonal(budget: Duration): PersonalSyncReport? {
    val p = partsFlow.value ?: return null
    val sync = p.personalSync ?: return null
    return work.run {
      val report = sync.sync(budget)
      runCatching { p.personalStore.reproject() }
      report
    }?.also { lastPersonal.value = it }
  }

  /** Dopo una scansione o una sincronizzazione dell'indice: le voci per media appena arrivati nel catalogo vanno proiettate. */
  suspend fun reproject() {
    val store = partsFlow.value?.personalStore ?: return
    try {
      store.reproject()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "proiezione dei dati personali non riuscita: ${e.javaClass.simpleName}" }
    }
  }

  private val personalLock = Any()
  private var pendingPersonal: Job? = null

  /**
   * Un preferito o un nascosto e' cambiato (dalla Libreria o dal viewer): la sincronizzazione parte fra [PERSONAL_COALESCE] (§2.9:
   * le modifiche si accorpano), solo con l'app in primo piano. Il resto lo fa il lavoro `INDEX_SYNC`.
   */
  fun personalChanged() {
    if (partsFlow.value?.personalSync == null) return
    synchronized(personalLock) {
      if (pendingPersonal?.isActive == true) return
      pendingPersonal = scope.launch {
        delay(PERSONAL_COALESCE)
        if (foreground()) runCatching { syncPersonal(PERSONAL_BUDGET) }
      }
    }
  }

  /** Le cartelle escluse dalla scansione dell'area privata (E-N15, impostazione personale sincronizzata). */
  fun privateExclusions(): List<SharePath> = partsFlow.value?.personalSettings?.privateAreaExclusions?.value?.toList().orEmpty()

  /** "Mostra le copertine degli album": impostazione personale, vale su tutti i dispositivi dell'account (Q9). */
  fun setShowAlbumCovers(show: Boolean) {
    val settings = partsFlow.value?.personalSettings ?: return
    scope.launch {
      runCatching { settings.setShowAlbumCovers(show) }
      personalChanged()
    }
  }

  // --- Il viewer ------------------------------------------------------------------------------------------------------------------

  /** Il file del NAS dietro [ref] e cosa ci si puo' fare (dal catalogo e dalle aree; mai un percorso dalla UI). */
  private suspend fun targetOf(ref: MediaRef.Nas): NasActionTarget? {
    val p = partsFlow.value ?: return null
    val catalog = catalogProvider() ?: return null
    val nas = catalog.nasLocation(LocationId(ref.locationId)) ?: return null
    val info = catalog.nasLocationAt(nas) ?: return null
    if (info.state != LocationState.PRESENT) return null
    val path = runCatching { SharePath.parseMediaPath(nas.path.value) }.getOrNull() ?: return null
    val area = p.areas.firstOrNull { it.share.equals(nas.share, ignoreCase = true) } ?: return null
    val media = catalog.mediaIdOfLocation(info.locationId)?.let { catalog.media(it) }
    val others = media?.locations.orEmpty()
      .filter { it.id != info.locationId }
      .mapNotNull { place ->
        val other = place.location as? Location.Nas ?: return@mapNotNull null
        val otherArea = p.areas.firstOrNull { it.share.equals(other.share, ignoreCase = true) } ?: return@mapNotNull null
        NasLocationInfo(if (otherArea.area == NasArea.PRIVATE) NasLocationInfo.Kind.NAS_PRIVATE else NasLocationInfo.Kind.NAS_COMMON)
      }
    // Dove si puo' spostare: la radice di ogni area (non quella in cui il file e' gia', se e' gia' alla radice).
    val destinations = p.areas
      .filterNot { it.share.equals(nas.share, ignoreCase = true) && path.parent() == null }
      .map { NasMoveDestination(it.share, null, it.area.toScope(), areaLabel(it.area)) }
    return NasActionTarget(
      item = NasItem(share = nas.share, path = path, isFolder = false, size = info.stamp.size, stamp = info.stamp),
      scope = area.area.toScope(),
      // E-N9: la sola lettura la dice il NAS (vedi ShareAccessView sopra). Un congelamento o l'emergenza non sono "sola lettura": il
      // foglio si apre e `start` risponde "scritture ferme" con la sua frase.
      writable = true,
      // Un media del NAS nel viewer ha il riferimento del NAS solo se una copia leggibile sul telefono non c'e' (thumbRefAt):
      // "Solo dal telefono" qui non avrebbe niente da eliminare.
      onPhone = false,
      isVideo = media?.kind == MediaKind.VIDEO,
      others = others,
      destinations = destinations,
    )
  }

  private fun NasArea.toScope(): NasAreaScope = if (this == NasArea.PRIVATE) NasAreaScope.PRIVATE else NasAreaScope.COMMON

  private fun NasShareArea.toScreenArea() = dev.pampa.mycloudgallery.feature.nas.contract.NasArea(
    share = share,
    kind = if (area == NasArea.PRIVATE) NasAreaKind.PRIVATE else NasAreaKind.COMMON,
  )

  /** L'indice nelle teste accettate della share: la somma dei segmenti elencati (Spazio NAS). */
  private suspend fun indexBytesOf(store: RoomReplicaStore, share: String): Long {
    val key = share.lowercase(Locale.ROOT)
    return store.heads(key).sumOf { head -> store.segments(key, head.deviceId).sumOf { it.size } }
  }

  // --- L'avvio che sopravvive alla schermata ------------------------------------------------------------------------------------

  /**
   * Le operazioni della UI. `start` dell'esecutore esegue l'operazione fino in fondo prima di rispondere: qui gira nello scope della
   * galleria (sopravvive alla schermata che l'ha chiesta, l'azzeramento la ferma e la aspetta) e la risposta arriva appena l'operazione
   * e' nel diario, cosi' la UI dice subito "Operazione avviata". Un'operazione lunga chiede anche il lavoro avviato dall'utente (UIDT).
   */
  private class AppNasOperations(private val owner: NasOrganizer) : NasOperations {

    private val starting = Mutex()

    override val operations: StateFlow<List<NasOperationState>> = owner.partsFlow
      .flatMapLatest { p -> p?.services?.operations?.operations ?: flowOf(emptyList()) }
      .stateIn(owner.scope, SharingStarted.Eagerly, emptyList())

    private fun current(): NasOperations? = owner.partsFlow.value?.services?.operations

    override suspend fun plan(request: OpRequest): PlanOutcome = current()?.plan(request) ?: NoNasOperations.plan(request)

    override suspend fun start(plan: OpPlan, confirmation: UserConfirmation): StartOutcome {
      val ops = current() ?: return StartOutcome.WritesBlocked
      return starting.withLock {
        val before = owner.opsStore.ops(OpState.entries.toSet()).mapTo(HashSet()) { it.id }
        val run = owner.scope.async {
          try {
            owner.work.run { ops.start(plan, confirmation) } ?: StartOutcome.WritesBlocked
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // L'esecutore non lancia per un guasto del NAS; un errore inatteso qui non deve far cadere la schermata.
            GalleryLog.w(TAG) { "operazione non avviata: ${e.javaClass.simpleName}" }
            StartOutcome.WritesBlocked
          }
        }
        val created = owner.scope.async {
          owner.opsStore.observeOps().first { list -> list.any { it.id !in before && it.planHash == plan.hash } }
            .first { it.id !in before && it.planHash == plan.hash }.id
        }
        val outcome = select<StartOutcome> {
          run.onAwait { it }
          created.onAwait { id -> StartOutcome.Started(id) }
        }
        created.cancel()
        if (outcome is StartOutcome.Started && NasOpsJobs.isLong(plan)) owner.jobs.userInitiated()
        outcome
      }
    }

    override suspend fun cancel(id: OpId) {
      val ops = current() ?: return
      // Nello scope della galleria: se la schermata si chiude a meta', l'annullamento (che toglie i temporanei) arriva in fondo.
      owner.scope.launch { owner.work.run { ops.cancel(id) } }.join()
    }

    override suspend fun resumeAll(budget: Duration): RunResult =
      owner.resumeOperations(budget) ?: RunResult(0, 0, 0, 0, RunStop.BLOCKED)
  }

  companion object {
    private const val TAG = "NasOrganizer"

    /** La cartella del registro locale dei dati personali in `noBackupFilesDir` (l'azzeramento la toglie). */
    const val PERSONAL_DIR = "personal"
    const val PERSONAL_LEDGER_FILE = "ledger"

    /** §2.9: le modifiche personali si accorpano per 30 secondi. */
    val PERSONAL_COALESCE: Duration = 30.seconds
    private val PERSONAL_BUDGET: Duration = 60.seconds

    private val OPEN_STATES = setOf(OpState.PLANNED, OpState.RUNNING, OpState.PAUSED)
  }
}

/**
 * Il cancello delle operazioni (§2.7, D10, D31, D32): emergenza → scritture ferme; share congelata → ferma; salto dell'ora → solo le
 * funzioni a scadenza si fermano. Puro, provato su JVM.
 */
object NasOpsGates {
  fun statusOf(emergency: Boolean, frozen: Boolean, clockJump: Boolean): GateStatus = when {
    emergency -> GateStatus.WRITES_STOPPED
    frozen -> GateStatus.FROZEN
    clockJump -> GateStatus.CLOCK_JUMP
    else -> GateStatus.OPEN
  }
}

/** L'impronta completa gia' nota di un file del NAS (calcolata qui, D8), solo per quel timbro esatto (§2.7 "calcolato se non noto"). */
class CatalogKnownHashes(private val locations: CatalogNasLocations) : KnownHashes {
  override suspend fun sha256(share: String, path: SharePath, stamp: NasStamp): dev.pampa.mycloudgallery.core.model.Sha256? {
    val expected = locations.expected(share, path) ?: return null
    val known = expected.stamp
    val same = known.size == stamp.size && known.modifiedAtMillis == stamp.modifiedAtMillis &&
      (known.fileId == null || stamp.fileId == null || known.fileId == stamp.fileId)
    return if (same) expected.fullHash else null
  }
}

/** I file piu' grandi di una share dal catalogo (Spazio NAS, §2.7): nessuna chiamata al NAS. Solo file presenti. */
object CatalogLargestFiles {
  suspend fun of(database: GalleryDatabase, account: AccountId, share: String): List<LargestFile> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val out = ArrayList<LargestFile>()
    database.openHelper.readableDatabase.query(
      "SELECT nas_path, size, modified_at, file_id FROM media_location WHERE location_type = 'nas' AND account_id = ? AND share_key = ? " +
        "AND state = 'present' ORDER BY size DESC LIMIT ${dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceDetails.MAX_LARGEST}",
      arrayOf<Any?>(account.value, Location.Nas.shareKeyOf(share)),
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val path = cursor.getString(0)?.let { runCatching { SharePath.parseMediaPath(it) }.getOrNull() } ?: continue
        val size = cursor.getLong(1)
        val fileId = if (cursor.isNull(3)) null else cursor.getLong(3)
        out += LargestFile(share, path, size, NasStamp(size, cursor.getLong(2), fileId))
      }
    }
    out
  }
}
