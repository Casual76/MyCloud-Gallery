package dev.pampa.mycloudgallery.core.family.noop

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
import dev.pampa.mycloudgallery.core.family.contract.LostReporting
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator
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
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

// I null-object di core-family (release-safe, fuori da `testing`): cio' che :app lega finche' P9 non
// collega P3 e P4. Il dispositivo risulta "mai configurato": l'app mostra l'onboarding, che non va avanti, e
// nessuna operazione tocca il NAS ne' cancella niente. Le operazioni che non hanno un "no" sensato (creare
// un invito, azzerare) falliscono rumorosamente invece di fingere.

private val notAvailable = StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE)

/** Mai configurato. */
object NotConfiguredSetupRepository : FamilySetupRepository {
  override val state: StateFlow<SetupState> = MutableStateFlow(SetupState.NotConfigured)
}

/** Fermo al benvenuto: nessun passo va avanti. */
object IdleOnboardingCoordinator : OnboardingCoordinator {
  override val progress: StateFlow<OnboardingProgress> = MutableStateFlow(OnboardingProgress.START)

  override suspend fun submit(input: StepInput): StepResult {
    when (input) {
      is StepInput.Credentials -> input.clear()
      is StepInput.RecoveryCodeTyped -> input.clear()
      else -> Unit
    }
    return notAvailable
  }

  override suspend fun skip(step: OnboardingStep): StepResult = notAvailable

  override suspend fun back() = Unit

  override suspend fun resume(step: OnboardingStep): StepResult = notAvailable

  override suspend fun homeCandidate(): HomeCandidate? = null

  override fun discoverNas(): Flow<DiscoveredNas> = emptyFlow()

  override suspend fun proposeAreas(extra: List<String>): List<AreaCandidate> = emptyList()

  override suspend fun proposeFolders(): FolderProposal = FolderProposal.EMPTY

  override suspend fun recoveryCode(): RecoveryCodeDisplay = RecoveryCodeDisplay(emptyList())
}

object NoJoinFlow : JoinFlow {
  private val failed = JoinStep.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)

  override suspend fun start(invite: FamilyInvite): JoinStep = failed

  override suspend fun authenticate(password: CharArray): JoinStep {
    password.fill('\u0000')
    return failed
  }

  override suspend fun register(name: String): JoinStep = failed
}

/** Nessuna famiglia: nessun dispositivo, nessuna richiesta, nessuna azione. */
object NoRegistryService : RegistryService {
  override val status: StateFlow<TrustStatus> = MutableStateFlow(TrustStatus.NotConfigured)
  override val devices: StateFlow<List<DeviceView>> = MutableStateFlow(emptyList())
  override val pendingRequests: StateFlow<List<PendingRequestView>> = MutableStateFlow(emptyList())

  override suspend fun refresh(purpose: Purpose) = Unit

  override suspend fun approve(requestId: String, proof: ApprovalProof): AdminActionResult = AdminActionResult.NotAdmin

  override suspend fun changeStatus(deviceId: String, status: DeviceStatus): AdminActionResult = AdminActionResult.NotAdmin

  override suspend fun createInvite(params: InviteParams): FamilyInvite =
    throw UnsupportedOperationException("nessuna famiglia configurata: non si puo' creare un invito")
}

/** Nessun lavoro: non configurato. */
object ClosedTrustGuard : TrustGuard {
  override suspend fun preflight(purpose: Purpose): PreflightResult = PreflightResult.Stop(StopReason.NOT_CONFIGURED)

  override fun writePermit(): WritePermit? = null
}

/** Niente salvato e niente da salvare. */
object NoTrustStateStore : TrustStateStore {
  override suspend fun load(): TrustSnapshot? = null

  override suspend fun save(snapshot: TrustSnapshot) = Unit

  override suspend fun clear() = Unit
}

/** Non sa azzerare: [run] fallisce (con il null-object nessun registro puo' dire "perso"). */
object NoSelfWipe : SelfWipe {
  override val progress: StateFlow<WipePhase?> = MutableStateFlow(null)

  override suspend fun run(reason: WipeReason): WipePhase =
    throw UnsupportedOperationException("azzeramento non disponibile prima dell'integrazione (P9)")

  override suspend fun resumeIfInterrupted() = Unit
}

object NoAdminRecovery : AdminRecovery {
  override suspend fun findKits(account: NasAccount, privateShare: String, password: CharArray?): NasResult<List<RecoveryKitRef>> =
    NasResult.Err(NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED))

  override suspend fun recover(
    kit: RecoveryKitRef,
    code: CharArray,
    familyState: FamilyStateQr?,
    oldAdmin: OldAdminFate,
  ): RecoveryOutcome {
    code.fill('\u0000')
    return RecoveryOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
  }
}

object NoFamilyReset : FamilyReset {
  override suspend fun reset(confirmedTwice: Boolean): ResetOutcome = ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
}

object NoLostReporting : LostReporting {
  override suspend fun reportOwnDeviceLost(targetDeviceId: String): AdminActionResult = AdminActionResult.NotAdmin

  override suspend fun convertReports(): AdminActionResult = AdminActionResult.NotAdmin
}

object NoTrustSyncScheduler : TrustSyncScheduler {
  override fun requestSoon(reason: SyncReason) = Unit

  override fun setInviteWindow(untilNasMs: Long) = Unit
}

object NoFolderSurvey : FolderSurvey {
  override suspend fun propose(share: String): FolderProposal = FolderProposal.EMPTY
}

/** Un codec che rifiuta tutto (nessun formato ancora implementato). */
class RejectingQrCodec<T> : QrPayloadCodec<T> {
  override fun encode(value: T): String = throw UnsupportedOperationException("codec non ancora disponibile (P4)")

  override fun decode(text: String): Verdict<T> = Verdict.Rejected(RejectReason.MALFORMED)
}
