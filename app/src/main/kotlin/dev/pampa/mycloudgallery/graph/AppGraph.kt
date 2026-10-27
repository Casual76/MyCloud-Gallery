package dev.pampa.mycloudgallery.graph

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.pampa.mycloudgallery.backup.CutoutFence
import dev.pampa.mycloudgallery.backup.DataStoreBackupSettingsStorage
import dev.pampa.mycloudgallery.core.backup.settings.BackupSourceCodec
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagHasher
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import dev.pampa.mycloudgallery.core.common.diagnostics.NoDiagnosticsLog
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.DeviceKeyQr
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.LocalDataEraser
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.TrustSyncScheduler
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.family.invite.ApprovalWatcher
import dev.pampa.mycloudgallery.core.family.invite.DefaultJoinFlow
import dev.pampa.mycloudgallery.core.family.invite.DeviceKeyCodec
import dev.pampa.mycloudgallery.core.family.lifecycle.CredentialWiper
import dev.pampa.mycloudgallery.core.family.lifecycle.DeviceSelfWipe
import dev.pampa.mycloudgallery.core.family.lifecycle.FileWipeJournal
import dev.pampa.mycloudgallery.core.family.lifecycle.KitAdminRecovery
import dev.pampa.mycloudgallery.core.family.lifecycle.LostReportWatcher
import dev.pampa.mycloudgallery.core.family.lifecycle.NasFamilyReset
import dev.pampa.mycloudgallery.core.family.lifecycle.NasLostReporting
import dev.pampa.mycloudgallery.core.family.lifecycle.WorkCanceller
import dev.pampa.mycloudgallery.core.family.onboarding.DataStoreOnboardingStateStore
import dev.pampa.mycloudgallery.core.family.onboarding.CredentialSaver
import dev.pampa.mycloudgallery.core.family.onboarding.DefaultOnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingPorts
import dev.pampa.mycloudgallery.core.family.registry.DeviceStatusBook
import dev.pampa.mycloudgallery.core.family.registry.FileApprovalSightings
import dev.pampa.mycloudgallery.core.family.registry.NasRegistryService
import dev.pampa.mycloudgallery.core.family.registry.RegistryWriter
import dev.pampa.mycloudgallery.core.family.setup.DataStoreNasAccountStore
import dev.pampa.mycloudgallery.core.family.setup.DefaultFamilySetupRepository
import dev.pampa.mycloudgallery.core.family.setup.SetupDataStores
import dev.pampa.mycloudgallery.core.family.survey.DefaultFolderSurvey
import dev.pampa.mycloudgallery.core.family.trust.DefaultTrustGuard
import dev.pampa.mycloudgallery.core.family.trust.FileTrustStateStore
import dev.pampa.mycloudgallery.core.family.trust.TrustDiagnostics
import dev.pampa.mycloudgallery.core.family.trust.TrustEngine
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.breaker.DataStoreBreakerStore
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.fs.SmbNasHousekeeping
import dev.pampa.mycloudgallery.core.nas.fs.SmbShareProbe
import dev.pampa.mycloudgallery.core.nas.session.SessionNasClock
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.status.NasStatusMonitor
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.network.diagnostics.NetworkDiagnosticsContributor
import dev.pampa.mycloudgallery.core.network.env.EnvironmentDeviceStateSource
import dev.pampa.mycloudgallery.core.network.env.LiveNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.gate.DefaultNasRouteGate
import dev.pampa.mycloudgallery.core.network.home.DataStoreHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.home.ObservedHomeCapture
import dev.pampa.mycloudgallery.core.network.policy.CombinedNetworkQuality
import dev.pampa.mycloudgallery.core.network.vpn.DataStoreVpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.vpn.DefaultVpnAutomation
import dev.pampa.mycloudgallery.core.network.vpn.WireGuardVpnController
import dev.pampa.mycloudgallery.core.network.wol.DefaultWakeOnLan
import dev.pampa.mycloudgallery.core.rules.ActivityRulesEngine
import dev.pampa.mycloudgallery.core.rules.AppContext
import dev.pampa.mycloudgallery.core.rules.AppContextSignal
import dev.pampa.mycloudgallery.core.rules.ComposedSystemStateProvider
import dev.pampa.mycloudgallery.core.rules.run.EngineStageRules
import dev.pampa.mycloudgallery.core.rules.run.StageRules
import dev.pampa.mycloudgallery.core.rules.settings.DataStoreRuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.traffic.DataStoreTrafficLedger
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagnosticsRecorder
import dev.pampa.mycloudgallery.nas.MutableNasFreezeSignal
import dev.pampa.mycloudgallery.nas.NasInputs
import dev.pampa.mycloudgallery.nas.NasShareArea
import dev.pampa.mycloudgallery.nas.VaultNasCredentialSource
import dev.pampa.mycloudgallery.root.ProcessRestart
import dev.pampa.mycloudgallery.work.AndroidLimitsTracker
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Il grafo dell'app con le implementazioni **vere** di P1-P8 (docs/fase2-progetto.md §4.3, riga P9): rete e casa
 * (P2), sessione SMB (P1), registro e ciclo di vita (P3), onboarding e ingresso (P4), regole e salute (P8). Un'istanza
 * per processo: la costruisce Hilt (`GraphModule`) e la espone contratto per contratto.
 *
 * E' un oggetto Kotlin e non una serie di `@Provides` perche' gli stessi oggetti si costruiscono identici nei test
 * d'integrazione (Robolectric), cambiando solo [platform]: il simulatore SMB al posto di smbj, una rete finta al posto
 * di `ConnectivityManager`, chiavi in software al posto del Keystore.
 *
 * L'ordine di costruzione segue le dipendenze: archivi → rete → sessione → fiducia → onboarding → regole. Gli oggetti
 * che osservano (ambiente di rete, cancello, sessione, archivi) cominciano a lavorare nel costruttore, su [scope]
 * (la vita del processo).
 *
 * @param schedulerFactory la pianificazione dei controlli del registro (WorkManager in produzione), che riceve l'ora del
 *   NAS (per la finestra d'invito, ora del NAS) e la lettura dello stato.
 * @param work la cancellazione dei lavori in background (fase 6 dell'azzeramento).
 */
class AppGraph(
  private val context: Context,
  val scope: CoroutineScope,
  val platform: AppPlatform,
  val flags: FeatureFlags,
  appVersion: String,
  schedulerFactory: (nasNow: () -> Long?) -> TrustSyncScheduler,
  work: WorkCanceller,
  /**
   * La recinzione delle prove sul campo (D33, Fase 4): `ShareFence` **solo nella build debug** (`DeveloperTools` di debug);
   * in release e nelle varianti di misura e' null e la classe non entra nell'APK (tools/check.ps1 lo verifica sul dex).
   */
  shareGuard: ShareGuard? = null,
  /** L'anello "Ultimi esiti ed errori" della Diagnostica (RE-4): in produzione quello di Hilt, nei test di solito nessuno. */
  diagnostics: DiagnosticsLog = NoDiagnosticsLog,
  /**
   * Il riavvio del processo (ON-11): in produzione `AndroidProcessRestart`, nei test un contatore o niente. Lo chiede "Configura di
   * nuovo" se in questo processo la galleria e' gia' partita (R6): non riparte, e il catalogo avrebbe ancora il proprietario di prima.
   */
  private val restart: ProcessRestart = ProcessRestart {},
) {

  /** Chi scrive nell'anello della Diagnostica: lavori, sessione NAS, backup, VPN (solo codici e numeri). */
  val events = DiagnosticsRecorder(diagnostics, platform.deviceNow)

  private val noBackup: File = context.noBackupFilesDir

  /**
   * Chi ferma la galleria del telefono viva prima che l'azzeramento ne cancelli i file (fase 5, revisione di sicurezza R7).
   * La galleria nasce dopo questo grafo e dipende da lui: si registra da sola (`GalleryModule`). Nei test senza galleria
   * resta null.
   */
  @Volatile
  var galleryStopper: GalleryStopper? = null

  /**
   * Fase 6 (P11): chi ferma `AiGraph` e `SearchGraph` **per primi** all'azzeramento (§1.4), e la parte `ai` dell'indice per il NAS. Nascono
   * dopo la galleria (dipendono da lei): si registrano da soli (`AiModule`). Nei test senza AI restano null.
   */
  @Volatile
  var aiStopper: GalleryStopper? = null

  /**
   * Fase 6, revisione B (AZ-2, I13): chi fa dimenticare all'AI cio' che ricorda **in memoria** di un account (la scelta della lettura del
   * testo, l'esclusione di questo dispositivo, gli avvisi visti, le scelte personali in sospeso). L'azzeramento lo chiama sempre, anche per i
   * soli dati di un account (`eraseAccountData(stopGallery = false)`), dove [aiStopper] non c'e': prima il file veniva tolto ma la memoria
   * restava e valeva per il nuovo account.
   */
  @Volatile
  var aiForgetter: GalleryStopper? = null

  @Volatile
  var aiHooks: dev.pampa.mycloudgallery.nas.NasAiHooks? = null

  /**
   * Fase 7 (P13, §1.4): chi ferma gli album **per primi** all'azzeramento (condivisione, replica, pubblicazione, ricalcoli): niente parte piu' e i
   * giri in corso si aspettano, poi si cancellano diario, replica, proposte e casa (con il database). Nasce dopo la galleria: si registra da solo
   * (`AlbumsModule`). Nei test senza album resta null.
   */
  @Volatile
  var albumsStopper: GalleryStopper? = null

  // --- Archivi locali (DataStore e file in noBackupFilesDir: fuori da backup e trasferimenti) ---------------------
  val accountStore = DataStoreNasAccountStore(SetupDataStores.account(context, scope))
  val account: StateFlow<NasAccount?> = accountStore.observe().stateIn(scope, SharingStarted.Eagerly, null)
  val onboardingStore = DataStoreOnboardingStateStore(
    dataStore = SetupDataStores.onboarding(context, scope),
    scope = scope,
    deviceKeyPresent = { platform.deviceKeys.hasDeviceKey() },
  )
  val homeStore = DataStoreHomeFingerprintStore.create(context, scope)
  val vpnConfigStore = DataStoreVpnControlConfigStore.create(context, scope)
  val ruleSettings = DataStoreRuleSettingsStore(preferences(context, scope, RULES_FILE))
  val trafficLedger = DataStoreTrafficLedger(preferences(context, scope, TRAFFIC_FILE))
  val heartbeat = HeartbeatStore(File(noBackup, HEARTBEAT_FILE))

  /** D13: dove stanno i ritagli del soggetto e la loro sorgente del backup (la recinzione esiste solo in debug). */
  private val cutoutFence = CutoutFence(DeveloperTools.backupFence(), DeveloperTools.fencedCutouts()) { account.value?.user }

  /**
   * Le impostazioni del backup (Fase 5A, P7; D7): del dispositivo, in `noBackupFilesDir/datastore`. Finche' l'utente non sceglie, le sorgenti
   * sono quelle del passo 6 dell'onboarding (fotocamera e screenshot, come le ha scelte); l'azzeramento le cancella.
   */
  val backupSettingsStorage = DataStoreBackupSettingsStorage(
    dataStore = preferences(context, scope, BACKUP_FILE),
    // Fase 6 (P13, D47): con almeno una scelta anche la cartella dei ritagli (in debug con un account di prova, quella recintata di D13).
    initialSources = combine(onboardingStore.state.filterIsInstance<StoreState.Ready>(), account) { state, nasAccount ->
      val choices = state.snapshot.choices
      cutoutFence.adapt(BackupSourceCodec.fromOnboarding(choices.camera, choices.screenshots), nasAccount?.user)
    },
  )

  // --- Rete, casa, VPN (P2) -----------------------------------------------------------------------------------
  val environment = LiveNetworkEnvironment(platform.networkObservation, homeStore, platform.localNetworkPermission, scope)
  val routeGate = DefaultNasRouteGate(environment, platform.bindings, scope)
  val homeCapture = ObservedHomeCapture(environment)
  val wireGuard = WireGuardVpnController(platform.wireGuard, vpnConfigStore, environment, scope)
  val discovery = platform.discovery(environment)

  /** App in primo piano e profilo attivo (`AppContextSignal`): lo aggiorna [ProcessWatcher]. */
  val appContext = MutableAppContext()

  // --- Sessione NAS (P1) --------------------------------------------------------------------------------------
  val credentials: AppCredentialStore = platform.credentials
  val credentialSource: NasCredentialSource = VaultNasCredentialSource { slot -> credentials.load(slot) }
  val breakers = AuthBreakerRegistry(DataStoreBreakerStore.create(context, scope), scope, platform.deviceNow)
  val session: SmbNasSessionManager = SmbNasSessionManager(
    account = { account.value },
    routeGate = routeGate,
    credentials = credentialSource,
    breakers = breakers,
    scope = scope,
    transport = platform.smbTransport,
    clock = platform.monotonicClock,
    deviceId = { engine.myDeviceId() },
    // Solo prima che "casa" sia registrata (passi 2-3 dell'onboarding): il Wi-Fi confermato dall'utente.
    onboardingCandidate = { onboardingStore.snapshotOrNull()?.homeConfirmed?.takeIf { homeStore.fingerprint.value == null } },
    shareGuard = shareGuard,
  )
  val nasClock: SessionNasClock = session.nasClock

  /** La sonda TCP con l'ultimo esito; lo dimentica quando la sessione NAS risponde (RE-6). */
  val reachability = ObservedReachabilityProbe(platform.reachabilityProbe, routeGate, scope, session.state)
  val wakeOnLan = DefaultWakeOnLan(flags, routeGate, reachability, platform.magicPacketSender(environment))
  val connectionTester = SmbConnectionTester(routeGate, breakers, platform.smbTransport, platform.monotonicClock)
  val shareProbe = SmbShareProbe(session)
  val statusMonitor = NasStatusMonitor(session, routeGate, reachability, { account.value }, scope)

  /** L'attesa della rete in un processo freddo, prima della prima valutazione delle regole di un lavoro (RE-3). */
  val networkSettler = NetworkSettler(environment, routeGate, statusMonitor)
  val breaker = AccountAuthBreaker(account, accountStore, breakers, scope)
  val sessionActivity = SessionBusyActivity(session, scope)
  val foreignShares: OneShotForeignShares = OneShotForeignShares(
    routeGate, breakers, platform.smbTransport, platform.monotonicClock, scope,
    tester = connectionTester,
    ownUser = { account.value?.user },
  ) { engine.myDeviceId() }

  // --- Pianificazione (P9) e profilo locale ---------------------------------------------------------------------
  val scheduler: TrustSyncScheduler = schedulerFactory { nasClock.nowMillisOrNull() }
  val profiles = SnapshotDeviceProfiles(onboardingStore, accountStore, platform.deviceInfo, appVersion)
  val housekeeping = SmbNasHousekeeping(session) { listOfNotNull(onboardingStore.snapshotOrNull()?.commonShare) }

  // --- Fiducia, registro, ciclo di vita (P3) ------------------------------------------------------------------
  val trustStore = FileTrustStateStore(File(noBackup, TRUST_FILE))
  val sightings = FileApprovalSightings(File(noBackup, SIGHTINGS_FILE))
  private val localEraser = AppLocalDataEraser(context, this)
  val eraser: LocalDataEraser = localEraser
  val selfWipe: DeviceSelfWipe = DeviceSelfWipe(
    journal = FileWipeJournal(File(noBackup, FileWipeJournal.FILE_NAME)),
    keys = platform.deviceKeys,
    familyKeys = platform.familyKeys,
    credentials = CredentialWiper { credentials.wipeAll() },
    sessions = session,
    trustState = trustStore,
    eraser = eraser,
    work = work,
    deviceClock = platform.deviceNow,
    localState = listOf({ sightings.clear() }),
  )
  val engine: TrustEngine = TrustEngine(session, trustStore, platform.deviceKeys, profiles, nasClock, selfWipe)
  val writer = RegistryWriter(engine, session, platform.familyKeys, nasClock)
  val statuses = DeviceStatusBook(engine, session, platform.deviceKeys)
  val registry = NasRegistryService(engine, session, writer, sightings, statuses, scheduler)
  val lostWatcher = LostReportWatcher(engine, session)
  val trustGuard = DefaultTrustGuard(engine, selfWipe, lostWatcher, statuses, platform.monotonicClock::nowMillis)
  val adminRecovery = KitAdminRecovery(engine, session, platform.deviceKeys, platform.familyKeys, nasClock, foreignShares)
  val familyReset = NasFamilyReset(engine, session, platform.deviceKeys, platform.familyKeys, nasClock)
  val lostReporting = NasLostReporting(engine, session, platform.deviceKeys, writer)
  val trustDiagnostics = TrustDiagnostics(engine, selfWipe, statuses, lostWatcher, writer)
  val approvalWatcher = ApprovalWatcher(registry)

  // --- Onboarding e ingresso (P4) -----------------------------------------------------------------------------
  val onboardingPorts = OnboardingPorts(
    homeCapture = homeCapture,
    homeFingerprints = homeStore,
    discovery = discovery,
    network = environment,
    localNetwork = platform.localNetworkPermission,
    tester = connectionTester,
    shareProbe = shareProbe,
    sessions = session,
    breaker = breaker,
    credentials = AppCredentialSaver(credentials),
    accounts = accountStore,
    trustState = trustStore,
    registry = registry,
    deviceKeys = platform.deviceKeys,
    familyKeys = platform.familyKeys,
    rules = ruleSettings,
    health = platform.healthReader,
    screenLock = platform.screenLock,
    device = platform.deviceInfo,
    choices = AppOnboardingChoices(vpnConfigStore),
    nasClock = nasClock,
    deviceNow = platform.deviceNow,
    allowUserRetry = breaker::allowUserRetry,
    // R6: i dati del vecchio account non passano al nuovo (si tolgono alla fine dell'onboarding, prima che la Libreria si apra).
    previousData = AppPreviousAccountData(localEraser, this),
    // "Configura di nuovo": con la galleria gia' partita in questo processo si riparte da uno nuovo, come per l'azzeramento (ON-11).
    afterStartOver = { if (galleryMayRun()) restart.restart() },
    // Passo 4: le share escluse dalla recinzione delle prove (D33, solo debug) si dicono "escluse per l'account di prova", non "accesso negato".
    shareGuard = shareGuard,
  )
  val folderSurvey = DefaultFolderSurvey({ share -> session.browser(share) })
  val coordinator = DefaultOnboardingCoordinator(onboardingStore, onboardingPorts, folderSurvey, scope)
  val joinFlow = DefaultJoinFlow(onboardingStore, onboardingPorts)

  private val started = MutableStateFlow(false)
  private val familySetup = DefaultFamilySetupRepository(onboardingStore, registry, selfWipe, scope, platform.deviceNow)

  /** Vero se in questo processo la famiglia e' stata pronta almeno una volta: la galleria puo' essere partita. */
  private val readyOnce = AtomicBoolean(false)

  /**
   * La galleria del telefono puo' essere partita in questo processo (la Libreria nasce con la famiglia pronta). Se si', un
   * azzeramento o la tolta dei dati di un altro account la ferma per sempre in questo processo (R7): serve riavviarlo.
   */
  fun galleryMayRun(): Boolean = readyOnce.get()

  /** Riavvia il processo fra poco: il tempo di salvare cio' che la funzione in corso sta finendo (R6, solo se il riavvio di "Configura di nuovo" non c'e' stato). */
  fun restartSoon() {
    scope.launch {
      delay(RESTART_DELAY_MS)
      restart.restart()
    }
  }

  /**
   * Lo stato del dispositivo (`SetupState`) per la prima schermata. Resta `Loading` finche' [start] non ha letto il
   * diario dell'azzeramento e lo stato fidato salvato: senza, un dispositivo azzerato o approvato mostrerebbe per un
   * attimo il benvenuto.
   *
   * **D-7**: dopo il via si segue lo stato della famiglia da quel momento (`flatMapLatest`), non un suo valore ricordato. Con
   * `combine` il via poteva arrivare prima dell'ultimo valore dello stato della famiglia, e passava quello vecchio ("non
   * configurato", calcolato prima che la fiducia fosse letta). [start] da' il via solo quando lo stato della famiglia ha visto la
   * fiducia letta, quindi il primo valore dopo il via e' gia' quello giusto.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  val setup: FamilySetupRepository = object : FamilySetupRepository {
    override val state: StateFlow<SetupState> = started
      .flatMapLatest { ready -> if (ready) familySetup.state else flowOf(SetupState.Loading) }
      .stateIn(scope, SharingStarted.Eagerly, SetupState.Loading)
  }

  init {
    scope.launch {
      setup.state.first { it is SetupState.Ready }
      readyOnce.set(true)
    }
  }

  // --- Regole delle attivita' (core-rules) e diagnostica ------------------------------------------------------------
  val roles = OnboardingRolesSignal(onboardingStore, scope)

  /** I limiti di Android che i lavori incontrano davvero (RE-7), dai motivi di stop di WorkManager. */
  val androidLimits = AndroidLimitsTracker(scope)

  /** "Una share del NAS e' congelata" (D10, P9): lo aggiorna il grafo del NAS, lo legge il motore delle regole. */
  val nasFreeze: MutableNasFreezeSignal = MutableNasFreezeSignal()
  val stateProvider = ComposedSystemStateProvider(
    device = EnvironmentDeviceStateSource(platform.deviceState, environment),
    home = environment,
    nas = statusMonitor,
    roles = roles,
    limits = androidLimits,
    app = appContext,
    quality = CombinedNetworkQuality(listOf(environment.quality, statusMonitor), scope),
    freeze = nasFreeze,
  )
  val rulesEngine = ActivityRulesEngine(ruleSettings, trafficLedger, stateProvider)

  /**
   * Le regole di una tappa di un lavoro (`StagedRun`): il motore vero, preceduto dall'attesa della rete (RE-3). **Un punto solo** per i
   * lavori del NAS e del backup, cosi' nessuno valuta le regole alla cieca in un processo appena avviato.
   */
  fun stageRules(): StageRules = settled(EngineStageRules(rulesEngine, stateProvider))

  /** Le [rules] date, con l'attesa della rete prima della prima valutazione e l'ora del NAS pronta prima del primo lotto (D-2). */
  fun settled(rules: StageRules): StageRules = SettledStageRules(rules, settle = { networkSettler.settle() }, ready = { readyNasClock() })

  /**
   * D-2 (prove sul campo, blocco A): dopo un avvio a freddo la scansione del NAS partiva 180 ms prima che la sessione fosse pronta, l'ora del
   * NAS non aveva un'ancora e il lavoro "adesso" finiva consumato; ripartiva solo al ritorno in primo piano o dopo 6 ore. Qui, quando le regole
   * di una tappa hanno detto «vai», si apre la sessione (o si aspetta quella che qualcun altro sta aprendo: la connessione e' a volo unico) con
   * tutti i controlli di sempre, e il primo lotto trova l'ora del NAS. Con un'ancora fresca o una sessione aperta non costa niente.
   *
   * Un errore vero resta quello che era: password rifiutata o interruttore aperto (nessuna autenticazione nuova, §3.3), NAS spento, percorso
   * negato. Nessun nuovo tentativo qui: la tappa parte comunque e incontra lo stesso errore, come prima.
   */
  private suspend fun readyNasClock() {
    if (account.value == null) return
    val error = session.ensureFreshClock().errorOrNull() ?: return
    GalleryLog.d(TAG) { "ora del NAS non pronta prima della tappa: ${error.javaClass.simpleName}" }
  }

  /**
   * Cio' che il grafo del NAS nella Libreria (Fase 4A, `nas/NasGraph`) riceve da qui: sessione, ora del NAS, fiducia,
   * percorso, regole e la configurazione dell'onboarding (aree, esclusioni firmate dell'area comune).
   */
  val nasInputs: NasInputs by lazy {
    NasInputs(
      flags = flags,
      session = session,
      nasClock = nasClock,
      account = { account.value?.user },
      shares = {
        val snapshot = onboardingStore.snapshotOrNull()
        listOfNotNull(
          snapshot?.privateShare?.let { NasShareArea(it, NasArea.PRIVATE) },
          snapshot?.commonShare?.let { NasShareArea(it, NasArea.COMMON) },
        )
      },
      commonExclusions = { engine.state?.registry?.config?.commonExclusions ?: onboardingStore.snapshotOrNull()?.excluded?.sorted().orEmpty() },
      deviceId = { runCatching { engine.myDeviceId() }.getOrNull() },
      signer = { runCatching { platform.deviceKeys.takeIf { it.hasDeviceKey() }?.deviceSigner() }.getOrNull() },
      registry = { engine.state },
      trust = trustGuard,
      route = routeGate,
      rules = rulesEngine,
      stateProvider = stateProvider,
      ledger = trafficLedger,
      metered = { environment.snapshot.value.underlying?.metered == true },
      meteredChanges = environment.snapshot.map { it.underlying?.metered == true },
      freezeSignal = nasFreeze,
      lastDiskTouchAt = session.lastDiskTouchAt,
    )
  }
  val vpnAutomation = DefaultVpnAutomation(environment, wireGuard, vpnConfigStore, appContext, sessionActivity)
  val networkDiagnostics = NetworkDiagnosticsContributor(
    paths = environment,
    gate = routeGate,
    vpnController = wireGuard,
    vpnAutomation = vpnAutomation,
    probe = reachability,
    hasher = DiagHasher.withRandomKey(),
    nasAddress = { account.value?.address },
  )
  val connectionTestInput = AccountConnectionTestInput(accountStore, profiles)
  val nasAccountSettings = AppNasAccountSettings(account, accountStore, credentials)

  init {
    watchEvents()
  }

  /**
   * Gli eventi che l'anello della Diagnostica riceve da questo grafo (RE-4): la sessione NAS (guasti e ritorno), il controllo della VPN
   * (sospensione). Gli esiti dei lavori li scrivono i lavori stessi. Parte a grafo costruito: i lavori leggono proprieta' gia' pronte.
   */
  private fun watchEvents() {
    scope.launch {
      session.state.collect { state ->
        when (state) {
          is NasConnState.Unavailable -> events.nasFailure(state.error)
          is NasConnState.AuthBlocked -> events.nasBlocked(state.reason)
          is NasConnState.Ready -> events.nasRecovered()
          else -> Unit
        }
      }
    }
    scope.launch {
      vpnConfigStore.suspension.filterNotNull().distinctUntilChanged().collect { events.vpnSuspended(it) }
    }
  }

  /**
   * All'avvio del processo, prima della prima schermata: finisce un azzeramento interrotto (o legge quello finito) e
   * ricarica lo stato fidato salvato, cosi' lo stato del dispositivo e' giusto anche senza NAS (fuori casa, offline).
   * Idempotente: e' anche il "Riprova" della pagina "Keystore non disponibile" (R7), che richiede la chiave al Keystore.
   *
   * **D-7** (prove sul campo): il via ([started]) arriva solo quando lo stato della famiglia ha visto la fiducia appena letta
   * ([DefaultFamilySetupRepository.awaitSourcesSeen]). Prima arrivava subito: lo stato della famiglia si calcola un passo dopo, e in
   * un processo appena nato chi guardava [setup] subito dopo l'avvio vedeva per un attimo "non configurato" (la UI un lampo, i
   * lavori "niente da fare"). Le attese di `awaitBackupReady` (`work/BackupJobService`) e `GalleryGraph.awaitReady` restano come cintura.
   */
  suspend fun start() {
    try {
      selfWipe.resumeIfInterrupted()
      engine.ensureLoaded()
      engine.refreshStatus()
    } catch (e: kotlinx.coroutines.CancellationException) {
      started.value = true
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG, e) { "avvio della fiducia non riuscito" }
      events.startFailed()
    }
    try {
      // Un tetto solo contro un grafo fermo (scope annullato): lo stato della famiglia si calcola in pochi millisecondi.
      if (withTimeoutOrNull(SETUP_SEEN_TIMEOUT_MS) { familySetup.awaitSourcesSeen() } == null) {
        GalleryLog.w(TAG) { "lo stato della famiglia non ha visto la fiducia in tempo: si parte lo stesso" }
      }
    } finally {
      started.value = true
    }
  }

  /**
   * L'utente riconfigura un dispositivo azzerato ("Configura di nuovo"): si dimentica il diario dell'azzeramento finito
   * e si torna "mai configurato" (docs/famiglia-dispositivi.md §1). Non fa niente se l'azzeramento non e' finito. Vero se
   * ha dimenticato un azzeramento: allora chi chiama riavvia il processo (ON-11, la galleria di questo processo e' ferma).
   */
  suspend fun forgetCompletedWipe(): Boolean {
    if (!selfWipe.forgetCompletedWipe()) return false
    // Subito, senza sospensioni in mezzo: tolto il diario il motore della fiducia non deve dire "perso" piu' a lungo del
    // necessario (lo stato del dispositivo non lo scambia comunque per un azzeramento in corso, DefaultFamilySetupRepository).
    engine.refreshStatus()
    onboardingStore.clear()
    return true
  }

  /** Il QR della chiave del dispositivo in attesa (`MCGK1:`), se l'ingresso e' a buon punto e la chiave c'e'. */
  fun deviceKeyQr(): String? = try {
    val familyId = onboardingStore.snapshotOrNull()?.join?.familyId
    val keys = platform.deviceKeys
    if (familyId == null || !keys.hasDeviceKey()) {
      null
    } else {
      val deviceId = engine.myDeviceId() ?: return null
      DeviceKeyCodec.encode(DeviceKeyQr(familyId, deviceId, keys.deviceSigner().publicKey.encoded))
    }
  } catch (e: Exception) {
    null
  }

  private companion object {
    const val TAG = "AppGraph"
    const val RULES_FILE = "rule_settings"
    const val BACKUP_FILE = "backup_settings"
    const val TRAFFIC_FILE = "traffic_ledger"
    const val TRUST_FILE = "trust-state"
    const val SIGHTINGS_FILE = "approval-sightings"
    const val HEARTBEAT_FILE = "heartbeat"

    /** Il tempo che `restartSoon` lascia a chi sta finendo di salvare prima che il processo si riavvii. */
    const val RESTART_DELAY_MS = 1_000L

    /** D-7: il tetto dell'attesa che lo stato della famiglia veda la fiducia letta da [start] (di solito pochi millisecondi). */
    const val SETUP_SEEN_TIMEOUT_MS = 10_000L

    /** Un DataStore di preferenze in `noBackupFilesDir/datastore`, uno per file e per processo. */
    fun preferences(context: Context, scope: CoroutineScope, name: String): DataStore<Preferences> =
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { SetupDataStores.fileOf(context, name) },
      )
  }
}

/** App in primo piano e profilo attivo, aggiornati dal processo ([ProcessWatcher]) e letti dal motore e dalla VPN. */
class MutableAppContext(initial: AppContext = AppContext(inForeground = false, profileActive = true)) : AppContextSignal {
  private val state = MutableStateFlow(initial)
  override val context: StateFlow<AppContext> = state.asStateFlow()

  fun update(inForeground: Boolean, profileActive: Boolean) {
    state.value = AppContext(inForeground = inForeground, profileActive = profileActive)
  }
}

/** Le credenziali salvate dall'onboarding finiscono nel Keystore ([AppCredentialStore]). */
class AppCredentialSaver(private val store: AppCredentialStore) : CredentialSaver {
  override suspend fun save(slot: String, credentials: NasCredentials): SaveResult = store.save(slot, credentials)

  override suspend fun remove(slot: String) = store.remove(slot)
}
