package dev.pampa.mycloudgallery.core.rules.run.testing

import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.CheckpointStore
import dev.pampa.mycloudgallery.core.rules.run.StageRules
import dev.pampa.mycloudgallery.core.rules.traffic.BillingCycle
import dev.pampa.mycloudgallery.core.rules.traffic.CycleUsage
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficKey
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// I fake di `StagedRun` (D6: in src/main per riusarli a valle, mai nel dex di release).

/** Un punto di ripresa in memoria; [saves] e [clears] contano le scritture. "Morire" = ricreare lo [StagedRun] con lo stesso store. */
class InMemoryCheckpointStore<C : Any>(initial: C? = null) : CheckpointStore<C> {
  var value: C? = initial
    private set

  var saves: Int = 0
    private set

  var clears: Int = 0
    private set

  override suspend fun load(): C? = value

  override suspend fun save(checkpoint: C) {
    value = checkpoint
    saves++
  }

  override suspend fun clear() {
    value = null
    clears++
  }
}

/**
 * Un registro del traffico in memoria, in ordine di arrivo: [entries] dice cosa e' stato registrato e quando rispetto agli
 * altri eventi del test. Il ciclo e' fisso (nessun orologio).
 */
class InMemoryTrafficLedger(private val cycle: BillingCycle = BillingCycle(YearMonth.of(2026, 10))) : TrafficLedger {

  data class Entry(val source: TrafficSource, val network: TrafficNetwork, val bytes: Long)

  private val log = ArrayList<Entry>()
  private val usage = MutableStateFlow(CycleUsage.empty(cycle))

  val entries: List<Entry> get() = log.toList()

  val totalBytes: Long get() = log.sumOf { it.bytes }

  override suspend fun record(source: TrafficSource, network: TrafficNetwork, bytes: Long) {
    if (bytes <= 0L) return
    log += Entry(source, network, bytes)
    val key = TrafficKey(source, network)
    val current = usage.value
    usage.value = current.copy(bytes = current.bytes + (key to ((current.bytes[key] ?: 0L) + bytes)))
  }

  override suspend fun currentUsage(): CycleUsage = usage.value

  override fun observeCurrentUsage(): Flow<CycleUsage> = usage

  override suspend fun history(): List<CycleUsage> = listOf(usage.value)
}

/**
 * Le regole a sceneggiatura: [script] risponde a ogni chiamata (con il suo numero d'ordine), cosi' un test dice "alla terza
 * rivalutazione Stop". [calls] registra l'ordine del protocollo.
 */
class ScriptedStageRules(
  var plugged: Boolean = true,
  private val script: (Call) -> RuleDecision,
) : StageRules {

  data class Call(val index: Int, val request: WorkRequest, val running: Boolean)

  private val log = ArrayList<Call>()

  val calls: List<Call> get() = log.toList()

  /** Quante valutazioni con `running = true` (cioe' dopo la partenza). */
  val runningCalls: Int get() = log.count { it.running }

  override suspend fun decide(request: WorkRequest, running: Boolean): RuleDecision {
    val call = Call(log.size, request, running)
    log += call
    return script(call)
  }

  override suspend fun isPluggedIn(): Boolean = plugged
}
