package dev.pampa.mycloudgallery.graph

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagHasher
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
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
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
import dev.pampa.mycloudgallery.core.rules.settings.DataStoreRuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.traffic.DataStoreTrafficLedger
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.nas.MutableNasFreezeSignal
import dev.pampa.mycloudgallery.nas.NasInputs
import dev.pampa.mycloudgallery.nas.NasShareArea
import dev.pampa.mycloudgallery.nas.VaultNasCredentialSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
) {

  private val noBackup: File = context.noBackupFilesDir

  /**
   * Chi ferma la galleria del telefono viva prima che l'azzeramento ne cancelli i file (fase 5, revisione di sicurezza R7).
   * La galleria nasce dopo questo grafo e dipende da lui: si registra da sola (`GalleryModule`). Nei test senza galleria
   * resta null.
   */
  @Volatile
  var galleryStopper: GalleryStopper? = null

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

  // --- Rete, casa, VPN (P2) -----------------------------------------------------------------------------------
  val environment = LiveNetworkEnvironment(platform.networkObservation, homeStore, platform.localNetworkPermission, scope)
  val routeGate = DefaultNasRouteGate(environment, platform.bindings, scope)
  val homeCapture = ObservedHomeCapture(environment)
  val reachability = ObservedReachabilityProbe(platform.reachabilityProbe, routeGate, scope)
  val wireGuard = WireGuardVpnController(platform.wireGuard, vpnConfigStore, environment, scope)
  val discovery = platform.discovery(environment)
  val wakeOnLan = DefaultWakeOnLan(flags, routeGate, reachability, platform.magicPacketSender(environment))

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
  val connectionTester = SmbConnectionTester(routeGate, breakers, platform.smbTransport, platform.monotonicClock)
  val shareProbe = SmbShareProbe(session)
  val statusMonitor = NasStatusMonitor(session, routeGate, reachability, { account.value }, scope)
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
  val eraser: LocalDataEraser = AppLocalDataEraser(context, this)
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
  )
  val folderSurvey = DefaultFolderSurvey({ share -> session.browser(share) })
  val coordinator = DefaultOnboardingCoordinator(onboardingStore, onboardingPorts, folderSurvey, scope)
  val joinFlow = DefaultJoinFlow(onboardingStore, onboardingPorts)

  private val started = MutableStateFlow(false)
  private val familySetup = DefaultFamilySetupRepository(onboardingStore, registry, selfWipe, scope, platform.deviceNow)

  /**
   * Lo stato del dispositivo (`SetupState`) per la prima schermata. Resta `Loading` finche' [start] non ha letto il
   * diario dell'azzeramento e lo stato fidato salvato: senza, un dispositivo azzerato o approvato mostrerebbe per un
   * attimo il benvenuto.
   */
  val setup: FamilySetupRepository = object : FamilySetupRepository {
    override val state: StateFlow<SetupState> = combine(started, familySetup.state) { ready, state -> if (ready) state else SetupState.Loading }
      .stateIn(scope, SharingStarted.Eagerly, SetupState.Loading)
  }

  // --- Regole delle attivita' (core-rules) e diagnostica ------------------------------------------------------------
  val roles = OnboardingRolesSignal(onboardingStore, scope)

  /** "Una share del NAS e' congelata" (D10, P9): lo aggiorna il grafo del NAS, lo legge il motore delle regole. */
  val nasFreeze: MutableNasFreezeSignal = MutableNasFreezeSignal()
  val stateProvider = ComposedSystemStateProvider(
    device = EnvironmentDeviceStateSource(platform.deviceState, environment),
    home = environment,
    nas = statusMonitor,
    roles = roles,
    limits = NoAndroidLimits,
    app = appContext,
    quality = CombinedNetworkQuality(listOf(environment.quality, statusMonitor), scope),
    freeze = nasFreeze,
  )
  val rulesEngine = ActivityRulesEngine(ruleSettings, trafficLedger, stateProvider)

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

  /**
   * All'avvio del processo, prima della prima schermata: finisce un azzeramento interrotto (o legge quello finito) e
   * ricarica lo stato fidato salvato, cosi' lo stato del dispositivo e' giusto anche senza NAS (fuori casa, offline).
   * Idempotente.
   */
  suspend fun start() {
    try {
      selfWipe.resumeIfInterrupted()
      engine.ensureLoaded()
      engine.refreshStatus()
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG, e) { "avvio della fiducia non riuscito" }
    } finally {
      started.value = true
    }
  }

  /**
   * L'utente riconfigura un dispositivo azzerato ("Configura di nuovo"): si dimentica il diario dell'azzeramento finito
   * e si torna "mai configurato" (docs/famiglia-dispositivi.md §1). Non fa niente se l'azzeramento non e' finito.
   */
  suspend fun forgetCompletedWipe() {
    if (selfWipe.forgetCompletedWipe()) {
      // Subito, senza sospensioni in mezzo: tolto il diario il motore della fiducia non deve dire "perso" piu' a lungo del
      // necessario (lo stato del dispositivo non lo scambia comunque per un azzeramento in corso, DefaultFamilySetupRepository).
      engine.refreshStatus()
      onboardingStore.clear()
    }
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
    const val TRAFFIC_FILE = "traffic_ledger"
    const val TRUST_FILE = "trust-state"
    const val SIGHTINGS_FILE = "approval-sightings"
    const val HEARTBEAT_FILE = "heartbeat"

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
