package dev.pampa.mycloudgallery.core.index.noop

import dev.pampa.mycloudgallery.core.index.ai.AiBufferSink
import dev.pampa.mycloudgallery.core.index.ai.AiDoneClasses
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiIndexPublisher
import dev.pampa.mycloudgallery.core.index.ai.AiPendingCounts
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaSink
import dev.pampa.mycloudgallery.core.index.ai.AiWinner
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.index.ai.FetchReport
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I null-object dei dati AI (Fase 6, P0): falliscono CHIUSI. Senza NAS o senza `AiGraph` niente si pubblica, niente si scarica e
// nessun contenuto risulta "fatto" (il calcolo non salta niente per errore).

/** Nessuna pubblicazione: ogni flush e' bloccato come "solo locale"; le voci non si perdono perche' non si accettano. */
object NoAiIndexPublisher : AiIndexPublisher {
  override suspend fun stage(share: String, entries: List<AiEntry>) = Unit

  override suspend fun flush(share: String, reason: FlushReason): PublishOutcome = PublishOutcome.Blocked(WriteBlock.LOCAL_ONLY)

  override suspend fun releasePending(share: String): Int = 0

  override val pending: StateFlow<AiPendingCounts> = MutableStateFlow(AiPendingCounts.EMPTY)
}

/** Nessuna replica dei dati AI. */
object NoAiReplicaSink : AiReplicaSink {
  override suspend fun apply(share: String, winners: List<AiWinner>) = Unit

  override suspend fun forgetDevice(device: String) = Unit

  override suspend fun forgetShare(share: String) = Unit
}

/** Nessuno scaricamento: tutte le chiavi restano "in attesa della preparazione". */
object NoAnalysisFetcher : AnalysisFetcher {
  override suspend fun fetch(
    share: String,
    keys: List<ContentKey>,
    classes: Set<SegmentClass>,
    into: AiBufferSink,
    budget: Duration,
  ): FetchReport = FetchReport(fetched = 0, missing = keys.size, bytes = 0, stoppedEarly = false)
}

/** Niente di fatto da altri: senza la replica delle teste `ai` il calcolo non salta niente. */
object NoAiDoneClasses : AiDoneClasses {
  override suspend fun done(share: String, keys: Collection<ContentKey>, recipe: Int): Map<ContentKey, Set<AiClass>> = emptyMap()
}
