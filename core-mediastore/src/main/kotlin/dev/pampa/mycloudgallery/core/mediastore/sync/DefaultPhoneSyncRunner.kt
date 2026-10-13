package dev.pampa.mycloudgallery.core.mediastore.sync

import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.MetadataPass
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSyncRunner
import dev.pampa.mycloudgallery.core.mediastore.contract.RunResult
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMetaPriority
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException

/**
 * Il corpo di `PhoneSyncWorker` (D10): un giro di sincronizzazione entro il budget e, con il tempo che resta, la fase META
 * dei media appena arrivati (dai piu' recenti), cosi' le foto scattate con l'app chiusa hanno data e luogo quando la si
 * apre. Il lavoro si riaccoda da solo: un giro interrotto riprende da dove era.
 */
class DefaultPhoneSyncRunner(
  private val sync: DefaultPhoneLibrarySync,
  private val metadataPass: MetadataPass? = null,
  private val priority: MetaPriority = NoMetaPriority,
  private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : PhoneSyncRunner {

  override suspend fun run(budget: Duration): RunResult {
    val start = monotonicMs()
    val report = try {
      sync.runWithin(SyncReason.WORKER, budget)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return RunResult.Failed(FAILED_CODE)
    }
    val status = sync.status.value
    // Le foto in attesa del permesso della posizione non fermano il resto (E-P21): video e foto note sono andati avanti.
    if (status is SyncStatus.Paused && status.reason != PauseReason.MEDIA_LOCATION_DENIED) return RunResult.Paused(status.reason)
    val left = budget.inWholeMilliseconds - (monotonicMs() - start)
    if (!report.completed) return if (left <= 0) RunResult.BudgetExhausted(remaining = null) else RunResult.Failed(INCOMPLETE_CODE)
    val pass = metadataPass ?: return RunResult.Done
    if (left <= 0) return RunResult.BudgetExhausted(remaining = null)
    return pass.run(left.milliseconds, priority)
  }

  private companion object {
    const val FAILED_CODE = "PHONE_SYNC_FAILED"
    const val INCOMPLETE_CODE = "PHONE_SYNC_INCOMPLETE"
  }
}
