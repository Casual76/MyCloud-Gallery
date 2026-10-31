package dev.pampa.mycloudgallery.core.family.support

import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.onboarding.CredentialSaver
import dev.pampa.mycloudgallery.core.family.onboarding.DeviceDescription
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingChoices
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingChoicesSink
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingPorts
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingStateStore
import dev.pampa.mycloudgallery.core.family.onboarding.PreviousAccountData
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import dev.pampa.mycloudgallery.core.family.setup.NasAccountStore
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.family.testing.FakeTrustStateStore
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.NasShareScope
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.NegotiationFacts
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.core.nas.testing.FakeAuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FakeShareProbe
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeCapture
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.testing.FakeLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.testing.FakeNasDiscovery
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.testing.Snapshots
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import dev.pampa.mycloudgallery.core.rules.platform.health.testing.FakeBackgroundHealthReader
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.security.credentials.KeyProtectionLevel
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyUse
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.VaultCopy
import dev.pampa.mycloudgallery.core.trust.crypto.VaultRefusal
import dev.pampa.mycloudgallery.core.trust.crypto.VaultResult
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 12/10/2026 12:00 UTC: l'ora "del NAS" dei test (dentro l'intervallo plausibile dei campi firmati). */
const val NAS_NOON: Long = 1_791_806_400_000L

const val TEST_ULA = "fd12:3456:789a::/48"
const val TEST_GUID = "6f1c0a52-3a4d-4c1e-9d2b-0123456789ab"
const val TEST_IP = "192.168.178.58"

/** Un avanzamento salvato in memoria: sincrono, con il conteggio delle scritture (E-O9: "si salva a ogni passo"). */
class InMemoryOnboardingStateStore(initial: OnboardingSnapshot = OnboardingSnapshot.EMPTY) : OnboardingStateStore {
  private val mutable = MutableStateFlow<StoreState>(StoreState.Ready(initial))
  private val lock = Mutex()
  override val state: StateFlow<StoreState> = mutable

  var writes: Int = 0
    private set

  val snapshot: OnboardingSnapshot get() = (mutable.value as StoreState.Ready).snapshot

  fun setState(state: StoreState) {
    mutable.value = state
  }

  override suspend fun update(transform: (OnboardingSnapshot) -> OnboardingSnapshot): OnboardingSnapshot? = lock.withLock {
    val current = (mutable.value as? StoreState.Ready)?.snapshot ?: return@withLock null
    val updated = transform(current)
    writes++
    mutable.value = StoreState.Ready(updated)
    updated
  }

  override suspend fun clear() {
    mutable.value = StoreState.Ready(OnboardingSnapshot.EMPTY)
  }
}

class CallRecord(val target: TestTarget, val hadCredentials: Boolean, val stages: Set<Stage>)

/** Un test di connessione pilotato: [handler] decide l'esito, [runs] ricorda cosa e' stato chiesto (mai la password). */
class ScriptedConnectionTester(var handler: (TestTarget, NasCredentials?, Set<Stage>) -> ConnectionReport) : ConnectionTester {
  val runs: MutableList<CallRecord> = mutableListOf()

  val authAttempts: Int get() = runs.count { it.hadCredentials && Stage.AUTHENTICATE in it.stages }

  override suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage>): ConnectionReport {
    runs += CallRecord(target, credentials != null, stages)
    return handler(target, credentials, stages)
  }
}

object Reports {
  val FACTS = NegotiationFacts(SmbDialect.SMB_3_1_1, encryptionOffered = true, serverGuid = TEST_GUID, timeOffsetMs = 0L)

  fun ok(stages: Set<Stage>, facts: NegotiationFacts? = FACTS): ConnectionReport =
    ConnectionReport(Stage.entries.filter { it in stages }.map { StageResult(it, StageOutcome.PASSED, Duration.ZERO) }, facts)

  /** Le fasi prima di [at] passano, [at] fallisce con [error], le altre non girano. */
  fun failed(stages: Set<Stage>, at: Stage, error: NasError, facts: NegotiationFacts? = null): ConnectionReport {
    val ordered = Stage.entries.filter { it in stages }
    val failedIndex = ordered.indexOf(at)
    return ConnectionReport(
      ordered.mapIndexed { index, stage ->
        when {
          index < failedIndex -> StageResult(stage, StageOutcome.PASSED, Duration.ZERO)
          index == failedIndex -> StageResult(stage, StageOutcome.FAILED, Duration.ZERO, error)
          else -> StageResult(stage, StageOutcome.NOT_RUN, Duration.ZERO)
        }
      },
      facts,
    )
  }

  /** L'autenticazione e' "saltata" (interruttore aperto) ma il rapporto, per `passed`, sembra riuscito. */
  fun authSkipped(stages: Set<Stage>): ConnectionReport =
    ConnectionReport(
      Stage.entries.filter { it in stages }.map {
        StageResult(it, if (it == Stage.AUTHENTICATE || it == Stage.SECURITY_CHECK) StageOutcome.SKIPPED else StageOutcome.PASSED, Duration.ZERO)
      },
      FACTS,
    )
}

class SavedCredentials(val slot: String, val user: String, val password: String)

class RecordingCredentialSaver(var result: SaveResult = SaveResult.Saved(KeyProtectionLevel.TRUSTED_ENVIRONMENT)) : CredentialSaver {
  val saved: MutableList<SavedCredentials> = mutableListOf()
  val removed: MutableList<String> = mutableListOf()

  override suspend fun save(slot: String, credentials: NasCredentials): SaveResult {
    if (result is SaveResult.Saved) saved += SavedCredentials(slot, credentials.username, String(credentials.password.value))
    return result
  }

  override suspend fun remove(slot: String) {
    removed += slot
  }
}

class InMemoryNasAccountStore : NasAccountStore {
  private val mutable = MutableStateFlow<NasAccount?>(null)
  val saved: MutableList<NasAccount> = mutableListOf()

  override fun observe(): Flow<NasAccount?> = mutable.map { it }

  override suspend fun current(): NasAccount? = mutable.value

  override suspend fun save(account: NasAccount) {
    saved += account
    mutable.value = account
  }

  override suspend fun clear() {
    mutable.value = null
  }
}

/**
 * La chiave del dispositivo in software (stessa curva del Keystore). I guasti si pilotano dal test: [broken] fa lanciare ogni
 * domanda (un Keystore che non risponde, R7), [destroyFailures] le prossime distruzioni che lanciano (R19).
 */
class SoftwareKeyProvider : KeyProvider {
  private var key: FamilyKey? = null
  var destroyed: Int = 0
    private set

  /** Il Keystore non risponde: `hasDeviceKey`, `deviceSigner` e la distruzione lanciano. */
  var broken: Boolean = false

  /** Quante delle prossime distruzioni lanciano (poi riescono). */
  var destroyFailures: Int = 0

  override fun deviceSigner(): Signer {
    check(!broken) { "Keystore non disponibile" }
    return key ?: FamilyKey.generate().also { key = it }
  }

  override fun hasDeviceKey(): Boolean {
    check(!broken) { "Keystore non disponibile" }
    return key != null
  }

  override fun destroyDeviceKey() {
    check(!broken) { "Keystore non disponibile" }
    destroyed++
    if (destroyFailures > 0) {
      destroyFailures--
      throw IllegalStateException("distruzione non riuscita")
    }
    key = null
  }
}

/** La cassaforte della chiave di famiglia in memoria; [secure] falso la fa rifiutare, come un telefono senza blocco schermo. */
class InMemoryFamilyKeyVault(var secure: Boolean = true) : FamilyKeyVault {
  var stored: FamilyKey? = null
    private set
  var stores: Int = 0
    private set

  /** Quante delle prossime distruzioni lanciano (R19); [destroyAttempts] conta anche quelle. */
  var destroyFailures: Int = 0
  var destroyAttempts: Int = 0
    private set

  /** Fase 10, M3: la risposta del foglio dell'impronta o del PIN (null = l'admin conferma); [sheets] conta i fogli mostrati. */
  var sheet: VaultRefusal? = null
  var sheets: Int = 0
    private set

  override suspend fun store(key: FamilyKey, use: FamilyKeyUse): VaultResult<Unit> {
    if (!secure) return VaultResult.Refused(VaultRefusal.NO_SECURE_LOCK)
    sheets++
    sheet?.let { return VaultResult.Refused(it) }
    stores++
    stored = key
    return VaultResult.Done(Unit)
  }

  override suspend fun load(expected: EcPublicKey, use: FamilyKeyUse): VaultResult<FamilyKey> {
    if (!secure) return VaultResult.Refused(VaultRefusal.NO_SECURE_LOCK)
    val key = stored?.takeIf { it.publicKey == expected } ?: return VaultResult.Absent
    sheets++
    sheet?.let { return VaultResult.Refused(it) }
    return VaultResult.Done(key)
  }

  override fun keep(): VaultCopy? {
    val kept = stored ?: return null
    return VaultCopy { stored = kept }
  }

  override fun destroy() {
    destroyAttempts++
    if (destroyFailures > 0) {
      destroyFailures--
      throw IllegalStateException("distruzione non riuscita")
    }
    stored = null
  }
}

class InMemoryRuleSettingsStore(initial: RuleSettings = RuleSettings()) : RuleSettingsStore {
  private val mutable = MutableStateFlow(initial)

  override fun observe(): Flow<RuleSettings> = mutable

  override suspend fun current(): RuleSettings = mutable.value

  override suspend fun save(settings: RuleSettings) {
    mutable.value = settings
  }

  override suspend fun update(transform: (RuleSettings) -> RuleSettings) {
    mutable.value = transform(mutable.value)
  }
}

/** I dati del vecchio account (R6): ricorda quante volte si e' chiesto di toglierli e com'era l'avanzamento in quel momento. */
class RecordingPreviousData(var result: Boolean = true) : PreviousAccountData {
  var erasures: Int = 0
    private set

  /** Cosa guardare al momento della richiesta (il test lo imposta: per esempio "l'onboarding non e' ancora finito"). */
  var onErase: () -> Unit = {}

  override suspend fun erase(): Boolean {
    erasures++
    onErase()
    return result
  }
}

class RecordingChoicesSink : OnboardingChoicesSink {
  val applied: MutableList<OnboardingChoices> = mutableListOf()

  override suspend fun apply(choices: OnboardingChoices) {
    applied += choices
  }
}

/**
 * Tutto cio' che serve a un test dell'onboarding o dell'ingresso: i fake dei contratti degli altri moduli, gia'
 * collegati, con un NAS che risponde bene a tutto. Ogni test cambia solo cio' che gli interessa.
 */
class TestEnv {
  var nasNow: Long = NAS_NOON
  var deviceNow: Long = NAS_NOON
  var screenLock: ScreenLockStatus = ScreenLockStatus.SECURE

  val candidate = HomeCandidate("wifi-1", setOf(TEST_ULA), "192.168.178.1", "fritz.box", "192.168.178.0/24")
  val capture = FakeHomeCapture(candidate)
  val fingerprints = FakeHomeFingerprintStore()
  val discovery = FakeNasDiscovery()
  val network = FakeNetworkEnvironment(Snapshots.home())
  val localNetwork = FakeLocalNetworkPermission()
  val tester = ScriptedConnectionTester { _, _, stages -> Reports.ok(stages) }
  val probe = FakeShareProbe(
    mapOf(
      "alessio" to NasResult.Ok(ShareAccess.READ_WRITE),
      "NAS" to NasResult.Ok(ShareAccess.READ_WRITE),
      "Public" to NasResult.Ok(ShareAccess.READ_WRITE),
    ),
  )
  val nas = FakeNasSessionManager()

  private val switchable = SwitchableSessions(nas)

  /** La sessione che i flussi vedono: di solito [nas]; un test la sostituisce con una che manomette i file (anche a coordinatore gia' creato). */
  var sessions: NasSessionManager
    get() = switchable.target
    set(value) {
      switchable.target = value
    }
  val breaker = FakeAuthCircuitBreaker()
  val credentials = RecordingCredentialSaver()
  val accounts = InMemoryNasAccountStore()
  val trustState = FakeTrustStateStore()
  val registry = FakeRegistryService()
  val deviceKeys = SoftwareKeyProvider()
  val familyKeys = InMemoryFamilyKeyVault()
  val rules = InMemoryRuleSettingsStore()
  val health = FakeBackgroundHealthReader(BackgroundHealth.UNKNOWN)
  val choices = RecordingChoicesSink()
  val previousData = RecordingPreviousData()

  /** Quante volte "Configura di nuovo" ha chiesto di riavviare il processo (R6). */
  var restarts: Int = 0
  var deviceDescription = DeviceDescription(model = "SM-S931B", defaultName = "Telefono di Alessio")

  /** P9: la porta che riapre l'interruttore di un account preciso (null = il comportamento di prima, con [breaker]). */
  var allowUserRetry: (suspend (NasAccount) -> Unit)? = null

  /** La recinzione delle prove (D33): di solito nessuna, come in release. */
  var shareGuard: ShareGuard? = null

  fun ports(): OnboardingPorts = OnboardingPorts(
    homeCapture = capture,
    homeFingerprints = fingerprints,
    discovery = discovery,
    network = network,
    localNetwork = localNetwork,
    tester = tester,
    shareProbe = probe,
    sessions = switchable,
    breaker = breaker,
    credentials = credentials,
    accounts = accounts,
    trustState = trustState,
    registry = registry,
    deviceKeys = deviceKeys,
    familyKeys = familyKeys,
    rules = rules,
    health = health,
    screenLock = { screenLock },
    device = { deviceDescription },
    choices = choices,
    nasClock = NasClock { nasNow },
    deviceNow = { deviceNow },
    random = java.security.SecureRandom(),
    allowUserRetry = allowUserRetry,
    previousData = previousData,
    afterStartOver = { restarts++ },
    shareGuard = shareGuard,
  )

  val address: NasAddress = NasAddress(TEST_IP, "MyCloudEX2Ultra.fritz.box")
}

fun password(text: String = "una-password-lunga-12"): CharArray = text.toCharArray()

fun credentials(user: String = "alessio", password: String = "una-password-lunga-12") = StepInput.Credentials(user, password.toCharArray())

/** Una sessione il cui bersaglio si cambia a meta' test: i coordinatori gia' creati la vedono cambiare. */
class SwitchableSessions(var target: NasSessionManager) : NasSessionManager {
  override val state: StateFlow<NasConnState> get() = target.state
  override val lastDiskTouchAt: StateFlow<Long?> get() = target.lastDiskTouchAt

  override suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T> =
    target.withShare(share, purpose, block)

  override fun fileSystem(share: String): NasFileSystem = target.fileSystem(share)

  override fun browser(share: String): NasBrowser = target.browser(share)

  override suspend fun invalidate(reason: InvalidateReason) = target.invalidate(reason)
}
