package dev.pampa.mycloudgallery.memories

import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesStatus
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.contract.ShelfView
import dev.pampa.mycloudgallery.core.memories.notify.NotifyEvents
import dev.pampa.mycloudgallery.core.memories.contract.NotifySkip
import dev.pampa.mycloudgallery.core.memories.noop.NoMemories
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * I ricordi per le schermate e per le porte opzionali di feature-memories (Fase 9, P13): parlano con il motore montato e, **prima** del montaggio (famiglia non
 * pronta, primo disegno non ancora fatto), rispondono come i null-object, con lo scaffale "sto preparando i ricordi" (o "la Libreria non e' pronta" se i
 * ricordi qui non si monteranno mai). I `@HiltViewModel` nascono prima del montaggio: i flussi seguono il montaggio da soli (`flatMapLatest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoriesFacade(private val graph: MemoriesGraph, scope: CoroutineScope) : Memories {

  private val idle: Memories = NoMemories
  private val before: ShelfView = MemoriesGraph.beforeMount(graph.canMount)

  private val current: Memories get() = graph.live.value?.engine ?: idle

  override val shelf: StateFlow<ShelfView> =
    graph.live.flatMapLatest { it?.engine?.shelf ?: flowOf(before) }.stateIn(scope, SharingStarted.Eagerly, before)

  override fun memory(id: MemoryId): Flow<MemoryView?> = graph.live.flatMapLatest { it?.engine?.memory(id) ?: flowOf(null) }

  override suspend fun refresh(reason: RefreshReason, budget: Duration): RefreshReport =
    graph.ensureLive()?.engine?.refresh(reason, budget)?.also { graph.counters.round(it) } ?: RefreshReport.NOTHING

  override suspend fun prune(): RefreshReport = current.prune()

  override suspend fun markSeen(id: MemoryId) = current.markSeen(id)

  override suspend fun dismiss(id: MemoryId, dismissed: Boolean) = current.dismiss(id, dismissed)

  /** "Mostra meno" e "Mai" passano dalle scelte con gli alias (P8, `AliasFollowingSettings`): il motore le ha gia' legate cosi'. */
  override suspend fun setLess(target: LessTarget, level: LessLevel?) = current.setLess(target, level)

  override val lessChoices: StateFlow<List<LessChoice>> =
    graph.live.flatMapLatest { it?.engine?.lessChoices ?: idle.lessChoices }.stateIn(scope, SharingStarted.Eagerly, idle.lessChoices.value)

  override val peopleMemories: StateFlow<Boolean> =
    graph.live.flatMapLatest { it?.engine?.peopleMemories ?: idle.peopleMemories }.stateIn(scope, SharingStarted.Eagerly, idle.peopleMemories.value)

  override suspend fun setPeopleMemories(on: Boolean) = current.setPeopleMemories(on)

  override val status: StateFlow<MemoriesStatus> =
    graph.live.flatMapLatest { it?.engine?.status ?: flowOf(MemoriesStatus.Working) }.stateIn(scope, SharingStarted.Eagerly, MemoriesStatus.Working)

  override fun toString(): String = "MemoriesFacade"

  companion object {
    /** Prima del montaggio: "Sto preparando i ricordi". */
    val PREPARING: ShelfView = ShelfView(emptyList(), 0, ShelfState.Working)

    /** Senza famiglia (nessun NAS): i ricordi non si preparano qui. */
    val NOT_READY: ShelfView = ShelfView(emptyList(), 0, ShelfState.Empty(ShelfEmptyReason.LIBRARY_NOT_READY))
  }
}

/**
 * Cio' che la Diagnostica dei ricordi conta (§2.12, D33): **solo numeri**, dal riavvio del processo, piu' l'ultimo giro. Mai titoli, luoghi, date, id o nomi.
 * Le notifiche consegnate in ritardo negli ultimi 14 giorni (`NOTIFY_LATE`) le tiene [late], su file (decidono il piano B di D20 in F115).
 */
class MemoriesCounters(val late: LateDeliveries? = null) : NotifyEvents {
  @Volatile var mountFailed: Boolean = false

  @Volatile var roundErrors: Int = 0

  @Volatile var rounds: Int = 0
    private set

  @Volatile var lastRound: RefreshReport? = null
    private set

  @Volatile var lastRoundMs: Long? = null
    private set

  /** Il giorno (`aaaammgg`) dell'ultimo giro, per "ultimo giro N giorni fa" (lo scrive chi conosce il giorno). */
  val shelfResets = AtomicInteger(0)
  val withoutVectors = AtomicInteger(0)
  private val dropped = EnumMap<DropReason, Int>(DropReason::class.java)
  private val skipped = EnumMap<NotifySkip, Int>(NotifySkip::class.java)
  val delivered = AtomicInteger(0)
  val revoked = AtomicInteger(0)

  /** Un giro del motore: scaffali rigenerati, doppioni senza vettori, esclusi per motivo. */
  @Synchronized
  fun round(report: RefreshReport, durationMs: Long? = null) {
    rounds++
    lastRound = report
    if (durationMs != null) lastRoundMs = durationMs
    if (report.shelfReset) shelfResets.incrementAndGet()
    if (report.withoutVectors) withoutVectors.incrementAndGet()
    for ((reason, count) in report.dropped) dropped[reason] = (dropped[reason] ?: 0) + count
  }

  @Synchronized
  fun droppedByReason(): Map<DropReason, Int> = EnumMap(dropped)

  @Synchronized
  fun skippedByReason(): Map<NotifySkip, Int> = EnumMap(skipped)

  @Synchronized
  override fun skipped(reason: NotifySkip) {
    skipped[reason] = (skipped[reason] ?: 0) + 1
  }

  override fun delivered(late: Boolean) {
    delivered.incrementAndGet()
    if (late) this.late?.record()
  }

  override fun revoked() {
    revoked.incrementAndGet()
  }
}
