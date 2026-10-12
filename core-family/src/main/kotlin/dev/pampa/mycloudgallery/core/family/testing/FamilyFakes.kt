package dev.pampa.mycloudgallery.core.family.testing

import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.AdminRecovery
import dev.pampa.mycloudgallery.core.family.contract.AreaCandidate
import dev.pampa.mycloudgallery.core.family.contract.DeviceView
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.FamilyReset
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.FolderProposal
import dev.pampa.mycloudgallery.core.family.contract.FolderSurvey
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.JoinFlow
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.LocalDataEraser
import dev.pampa.mycloudgallery.core.family.contract.LostReporting
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PendingRequestView
import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.QrPayloadCodec
import dev.pampa.mycloudgallery.core.family.contract.RecoveryCodeDisplay
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.ResetOutcome
import dev.pampa.mycloudgallery.core.family.contract.SelfWipe
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.StopReason
import dev.pampa.mycloudgallery.core.family.contract.SyncReason
import dev.pampa.mycloudgallery.core.family.contract.TrustGuard
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.contract.TrustStateStore
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.TrustSyncScheduler
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.family.contract.WritePermit
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow

// I fake di core-family (D6): in src/main per i test di core-family (P3, P4), delle schermate (P6, P7, P8) e
// di :app (RootViewModel, screenshot dello scaffold). Mai nella release (tools/check.ps1).

/** Lo stato di configurazione pilotato dal test (o, in debug, dal pannello "Sviluppo"). */
class FakeFamilySetupRepository(initial: SetupState = SetupState.NotConfigured) : FamilySetupRepository {
  override val state: MutableStateFlow<SetupState> = MutableStateFlow(initial)
}

/**
 * Un coordinatore finto che avanza da solo: ogni [submit] completa il passo corrente e passa al successivo
 * del piano, salvo che il test abbia messo in coda un esito in [scripted]. Le password ricevute si azzerano
 * subito (come deve fare quello vero) e in [inputs] resta solo il tipo di input.
 *
 * I piani per percorso sono quelli di un fake, non una decisione di prodotto: il piano vero e' di P4.
 */
class FakeOnboardingCoordinator(initial: OnboardingProgress = OnboardingProgress.START) : OnboardingCoordinator {

  override val progress: MutableStateFlow<OnboardingProgress> = MutableStateFlow(initial)

  /** Esiti da restituire, in ordine, prima del comportamento automatico. */
  val scripted: ArrayDeque<StepResult> = ArrayDeque()

  /** I tipi di input ricevuti (mai il contenuto: dentro possono esserci password). */
  val inputs: MutableList<String> = mutableListOf()

  var candidate: HomeCandidate? = null
  var discovered: List<DiscoveredNas> = emptyList()
  var areas: List<AreaCandidate> = emptyList()
  var folders: FolderProposal = FolderProposal.EMPTY
  var recoveryGroups: List<String> = List(RECOVERY_GROUPS) { "ABCD" }

  override suspend fun submit(input: StepInput): StepResult {
    inputs += input::class.simpleName.orEmpty()
    when (input) {
      is StepInput.Credentials -> input.clear()
      is StepInput.RecoveryCodeTyped -> input.clear()
      is StepInput.ChoosePath -> progress.value = progress.value.copy(path = input.path, plan = planFor(input.path))
      else -> Unit
    }
    scripted.removeFirstOrNull()?.let { return it }
    return advance()
  }

  override suspend fun skip(step: OnboardingStep): StepResult {
    val current = progress.value
    if (!step.skippable || step != current.current) return StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    progress.value = current.copy(skipped = current.skipped + step)
    return move(markCompleted = false)
  }

  override suspend fun back() {
    val current = progress.value
    val index = current.plan.indexOf(current.current)
    if (index > 0) progress.value = current.copy(current = current.plan[index - 1])
  }

  override suspend fun resume(step: OnboardingStep): StepResult {
    val current = progress.value
    if (step !in current.skipped) return StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    progress.value = current.copy(current = step)
    return StepResult.Advanced(step)
  }

  override suspend fun homeCandidate(): HomeCandidate? = candidate

  override fun discoverNas(): Flow<DiscoveredNas> = discovered.asFlow()

  override suspend fun proposeAreas(extra: List<String>): List<AreaCandidate> = areas

  override suspend fun proposeFolders(): FolderProposal = folders

  override suspend fun recoveryCode(): RecoveryCodeDisplay = RecoveryCodeDisplay(recoveryGroups.map { it.toCharArray() })

  private fun advance(): StepResult = move(markCompleted = true)

  private fun move(markCompleted: Boolean): StepResult {
    val current = progress.value
    val index = current.plan.indexOf(current.current)
    val next = current.plan.getOrNull(index + 1)
    val completed = if (markCompleted) current.completed + current.current else current.completed
    progress.value = current.copy(current = next ?: current.current, completed = completed)
    return StepResult.Advanced(next)
  }

  companion object {
    private const val RECOVERY_GROUPS = 7

    fun planFor(path: OnboardingPath): List<OnboardingStep> = when (path) {
      OnboardingPath.FOUND_FAMILY -> OnboardingStep.entries.toList()
      else -> listOf(
        OnboardingStep.WELCOME,
        OnboardingStep.NAS_ADDRESS,
        OnboardingStep.ACCOUNT,
        OnboardingStep.ROLES,
        OnboardingStep.PRESET,
        OnboardingStep.PERMISSIONS,
        OnboardingStep.BATTERY,
        OnboardingStep.VPN,
        OnboardingStep.SUMMARY,
      )
    }
  }
}

/** Ingresso finto: ogni chiamata restituisce il prossimo passo da [steps] (o l'ultimo, ripetuto). */
class FakeJoinFlow(vararg steps: JoinStep) : JoinFlow {
  val steps: ArrayDeque<JoinStep> = ArrayDeque(steps.toList())
  val calls: MutableList<String> = mutableListOf()
  private var last: JoinStep = JoinStep.NeedsPassword

  private fun next(call: String): JoinStep {
    calls += call
    last = steps.removeFirstOrNull() ?: last
    return last
  }

  override suspend fun start(invite: FamilyInvite): JoinStep = next("start")

  override suspend fun authenticate(password: CharArray): JoinStep {
    password.fill('\u0000')
    return next("authenticate")
  }

  override suspend fun register(name: String): JoinStep = next("register")
}

/** Registro finto: stato, dispositivi e richieste si impostano dal test; le azioni restituiscono [nextResult]. */
class FakeRegistryService(
  status: TrustStatus = TrustStatus.NotConfigured,
  devices: List<DeviceView> = emptyList(),
  pending: List<PendingRequestView> = emptyList(),
) : RegistryService {
  override val status: MutableStateFlow<TrustStatus> = MutableStateFlow(status)
  override val devices: MutableStateFlow<List<DeviceView>> = MutableStateFlow(devices)
  override val pendingRequests: MutableStateFlow<List<PendingRequestView>> = MutableStateFlow(pending)

  var nextResult: AdminActionResult = AdminActionResult.Done
  var invite: FamilyInvite? = null
  val refreshes: MutableList<Purpose> = mutableListOf()
  val approvals: MutableList<String> = mutableListOf()
  val statusChanges: MutableList<Pair<String, DeviceStatus>> = mutableListOf()

  override suspend fun refresh(purpose: Purpose) {
    refreshes += purpose
  }

  override suspend fun approve(requestId: String, proof: ApprovalProof): AdminActionResult {
    approvals += requestId
    return nextResult
  }

  override suspend fun changeStatus(deviceId: String, status: DeviceStatus): AdminActionResult {
    statusChanges += deviceId to status
    return nextResult
  }

  override suspend fun createInvite(params: InviteParams): FamilyInvite =
    invite ?: throw IllegalStateException("FakeRegistryService: imposta `invite` nel test")
}

class FakeTrustGuard(
  var result: PreflightResult = PreflightResult.Stop(StopReason.NOT_CONFIGURED),
  var permit: WritePermit? = null,
) : TrustGuard {
  val preflights: MutableList<Purpose> = mutableListOf()

  override suspend fun preflight(purpose: Purpose): PreflightResult {
    preflights += purpose
    return result
  }

  override fun writePermit(): WritePermit? = permit
}

class FakeTrustStateStore(var snapshot: TrustSnapshot? = null) : TrustStateStore {
  override suspend fun load(): TrustSnapshot? = snapshot

  override suspend fun save(snapshot: TrustSnapshot) {
    this.snapshot = snapshot
  }

  override suspend fun clear() {
    snapshot = null
  }
}

/**
 * Un registro condiviso dell'ordine delle operazioni: i fake dell'azzeramento ci scrivono, il test verifica
 * che la chiave sparisca **prima** dei dati (§2.3, test obbligatorio di P3).
 */
class OperationLog {
  private val entries = mutableListOf<String>()

  @Synchronized
  fun record(entry: String) {
    entries += entry
  }

  @Synchronized
  fun all(): List<String> = entries.toList()
}

/** Azzeramento finto: percorre le fasi in ordine registrandole in [log]; si puo' fermare a [stopAfter]. */
class RecordingSelfWipe(private val log: OperationLog = OperationLog(), var stopAfter: WipePhase? = null) : SelfWipe {
  override val progress: MutableStateFlow<WipePhase?> = MutableStateFlow(null)

  override suspend fun run(reason: WipeReason): WipePhase {
    val start = progress.value?.let { WipePhase.entries.indexOf(it) + 1 } ?: 0
    for (phase in WipePhase.entries.drop(start)) {
      log.record("wipe:${phase.name}")
      progress.value = phase
      if (phase == stopAfter) return phase
    }
    return WipePhase.DONE
  }

  override suspend fun resumeIfInterrupted() {
    val current = progress.value ?: return
    if (current != WipePhase.DONE) run(WipeReason.MARKED_LOST)
  }
}

class RecordingLocalDataEraser(private val log: OperationLog = OperationLog(), var result: Boolean = true) : LocalDataEraser {
  override suspend fun eraseAll(): Boolean {
    log.record("eraser:eraseAll")
    return result
  }
}

class FakeAdminRecovery(
  var kits: NasResult<List<RecoveryKitRef>> = NasResult.Ok(emptyList()),
  var outcome: RecoveryOutcome = RecoveryOutcome.WrongCode,
) : AdminRecovery {
  /** Se l'ultima ricerca ha ricevuto una password (mai la password stessa). */
  var lastSearchHadPassword: Boolean = false

  override suspend fun findKits(account: NasAccount, privateShare: String, password: CharArray?): NasResult<List<RecoveryKitRef>> {
    lastSearchHadPassword = password != null
    password?.fill('\u0000')
    return kits
  }

  override suspend fun recover(
    kit: RecoveryKitRef,
    code: CharArray,
    familyState: FamilyStateQr?,
    oldAdmin: OldAdminFate,
  ): RecoveryOutcome {
    code.fill('\u0000')
    return outcome
  }
}

class FakeFamilyReset(
  var outcome: ResetOutcome = ResetOutcome.Done("famiglia-nuova"),
  /** Il codice del kit nuovo dopo un reset riuscito, a gruppi separati da `-`. */
  var newCode: String = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345",
) : FamilyReset {
  var attempts: Int = 0
    private set

  private var pending: String? = null

  override suspend fun reset(confirmedTwice: Boolean): ResetOutcome {
    attempts++
    val result = if (confirmedTwice) outcome else ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    if (result is ResetOutcome.Done) pending = newCode
    return result
  }

  override fun recoveryCodeForDisplay(): RecoveryCodeDisplay? =
    pending?.let { code -> RecoveryCodeDisplay(code.split('-').map { it.toCharArray() }) }

  override fun confirmTypedCode(typed: CharArray): Boolean {
    val ok = pending != null && String(typed).replace("-", "").replace(" ", "").equals(pending!!.replace("-", ""), ignoreCase = true)
    typed.fill('\u0000')
    if (ok) pending = null
    return ok
  }
}

class FakeLostReporting(var result: AdminActionResult = AdminActionResult.Done) : LostReporting {
  val reported: MutableList<String> = mutableListOf()

  override suspend fun reportOwnDeviceLost(targetDeviceId: String): AdminActionResult {
    reported += targetDeviceId
    return result
  }

  override suspend fun convertReports(): AdminActionResult = result
}

class FakeTrustSyncScheduler : TrustSyncScheduler {
  val requests: MutableList<SyncReason> = mutableListOf()
  var inviteWindowUntil: Long? = null

  override fun requestSoon(reason: SyncReason) {
    requests += reason
  }

  override fun setInviteWindow(untilNasMs: Long) {
    inviteWindowUntil = untilNasMs
  }
}

class FakeFolderSurvey(var proposal: FolderProposal = FolderProposal.EMPTY) : FolderSurvey {
  override suspend fun propose(share: String): FolderProposal = proposal
}

/** Codec finto: riconosce solo i testi registrati in [known]; [encode] restituisce la chiave registrata. */
class FakeQrPayloadCodec<T>(val known: MutableMap<String, T> = mutableMapOf()) : QrPayloadCodec<T> {
  override fun encode(value: T): String =
    known.entries.firstOrNull { it.value == value }?.key ?: throw IllegalArgumentException("valore non registrato nel fake")

  override fun decode(text: String): Verdict<T> =
    known[text]?.let { Verdict.Accepted(it) } ?: Verdict.Rejected(RejectReason.MALFORMED)
}
