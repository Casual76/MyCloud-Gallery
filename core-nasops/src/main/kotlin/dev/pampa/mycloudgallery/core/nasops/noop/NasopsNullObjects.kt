package dev.pampa.mycloudgallery.core.nasops.noop

import dev.pampa.mycloudgallery.core.nasops.contract.CleanReport
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFilesCleaning
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeReport
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// I null-object di core-nasops (release-safe, fuori da `testing`): nessun NAS configurato, nessuna operazione. Falliscono chiusi: niente
// si pianifica, niente parte, niente si elimina, e tutto lo dice con un motivo invece di fingere. `:app` li lega finche' la 4B non e'
// integrata (P16) e quando il NAS non e' configurato.

/** Nessuna operazione: il piano e' rifiutato, nessuna conferma avvia niente, non c'e' nulla da riprendere. */
object NoNasOperations : NasOperations {
  override suspend fun plan(request: OpRequest): PlanOutcome = PlanOutcome.Rejected(PlanRejection.NAS_UNREACHABLE)

  override suspend fun start(plan: OpPlan, confirmation: UserConfirmation): StartOutcome = StartOutcome.WritesBlocked

  override val operations: StateFlow<List<NasOperationState>> = MutableStateFlow(emptyList())

  override suspend fun cancel(id: OpId) = Unit

  override suspend fun resumeAll(budget: Duration): RunResult =
    RunResult(resumedOps = 0, itemsDone = 0, itemsFailed = 0, itemsWaiting = 0, stoppedBy = RunStop.BLOCKED)
}

/** Nessun cestino: nessuna voce, nessuna pulizia. */
object NoNasTrash : NasTrash {
  override val items: StateFlow<NasTrashState> = MutableStateFlow(NasTrashState.Offline(emptyList()))

  override suspend fun refresh() = Unit

  override suspend fun purgeExpired(budget: Duration): PurgeReport =
    PurgeReport(purged = 0, bytes = 0, kept = emptyMap(), alreadyGone = 0, deferred = 0, stoppedBy = PurgeStop.BLOCKED)
}

object NoNasSpace : NasSpace {
  override suspend fun load(share: String): NasSpaceState = NasSpaceState.Unavailable(OpErrorCode.UNREACHABLE, emptyList())
}

object NoNasFolders : NasFolders {
  override fun observe(share: String, dir: SharePath?): Flow<NasFolderState> = flowOf(NasFolderState.Offline(cached = null, cachedAt = null))

  override suspend fun refresh(share: String, dir: SharePath?) = Unit
}

object NoOwnFilesCleaning : OwnFilesCleaning {
  override suspend fun clean(shares: List<String>, budget: Duration): CleanReport =
    CleanReport(removed = 0, kept = emptyMap(), failed = 0, stoppedBy = PurgeStop.BLOCKED)
}

/** Le scritture sono ferme (come "Ferma le scritture sul NAS"): il cancello di default quando niente lo compone. */
object WritesStoppedGate : OpsGate {
  override fun statusOf(share: String): GateStatus = GateStatus.WRITES_STOPPED
}
