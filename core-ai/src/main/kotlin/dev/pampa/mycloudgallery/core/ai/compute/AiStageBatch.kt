package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiRunResult
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.run.BatchResult
import dev.pampa.mycloudgallery.core.rules.run.StageBatch
import dev.pampa.mycloudgallery.core.rules.run.StageHalt
import dev.pampa.mycloudgallery.core.rules.run.StageScope
import kotlin.time.Duration

/**
 * Le regole di una tappa (`StageScope` di `StagedRun`, D22) come le vede il calcolo: [check] chiede `proceed()` (tempo della tappa,
 * tetti, rivalutazione delle regole con `running = true`) e legge ritmo (`Pace.REDUCED`, E-A5) e ambito (`LOCAL_BUFFER_ONLY`, E-A6);
 * [checkpoint] salva il punto di ripresa con `commit`.
 */
class StageScopeControl(private val scope: StageScope<AiCheckpoint>) : AiRunControl {
  override suspend fun check(): AiPace {
    if (!scope.proceed()) return AiPace.Halt(if (scope.halt is StageHalt.Rules) AiStop.RULES else AiStop.BUDGET)
    return AiPace.Go(reduced = scope.pace == Pace.REDUCED, localOnly = scope.runScope == RunScope.LOCAL_BUFFER_ONLY)
  }

  override suspend fun checkpoint(point: AiCheckpoint) = scope.commit(point)
}

/**
 * Il lotto di `StagedRun` per `AiComputeWorker` di :app (P11, §2.7): tutti gli ambiti di questo dispositivo in ordine
 * ([AiComputeEngine.runAll]: area privata propria, area comune, media del solo telefono). Il tempo lo misura `StagedRun` (8 minuti):
 * [budget] e' solo un tetto di sicurezza. C'e' altro da fare se un ambito si e' fermato per tempo o regole.
 *
 * `request` della tappa: `WorkRequest.Background(AI_COMPUTE)` con `writesToNas = true` (le teste `ai` e le prenotazioni); "Indicizza
 * ora" e' `UserInitiated(AI_COMPUTE)`.
 */
class AiStageBatch(
  private val engine: AiComputeEngine,
  private val budget: Duration = Duration.INFINITE,
) : StageBatch<AiCheckpoint> {

  /** Gli esiti dell'ultimo lotto (per la Diagnostica e i test): solo numeri. */
  @Volatile var last: List<Pair<AiScope, AiRunResult>> = emptyList()
    private set

  override suspend fun run(scope: StageScope<AiCheckpoint>, from: AiCheckpoint?): BatchResult<AiCheckpoint> {
    val results = engine.runAll(budget, StageScopeControl(scope))
    last = results
    val more = results.any { it.second.stoppedBy == AiStop.BUDGET || it.second.stoppedBy == AiStop.RULES }
    return BatchResult(checkpoint = null, hasMore = more)
  }
}
