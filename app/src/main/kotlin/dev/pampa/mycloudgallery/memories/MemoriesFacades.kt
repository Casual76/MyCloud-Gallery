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
import java.time.LocalDate
import java.util.EnumMap
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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

  /**
   * **Prima del montaggio non dice niente** (RP-1, 31/10): "non lo so ancora" non e' "non c'e'". Prima rispondeva `null`, e la pagina del ricordo diceva "Non trovo
   * questo ricordo" e la presentazione "Questo ricordo non c'e' piu'" a ogni apertura a freddo (la notifica con il processo morto, il ripristino dai recenti) finche' i
   * ricordi non si montavano. Ora le schermate restano in caricamento; `null` arriva solo dal motore montato (e con la Libreria letta). Senza famiglia (nessun NAS) i
   * ricordi non si monteranno mai: li' "non c'e'" e' la verita'.
   */
  override fun memory(id: MemoryId): Flow<MemoryView?> = graph.live.flatMapLatest { live ->
    when {
      live != null -> live.engine.memory(id)
      graph.canMount -> emptyFlow()
      else -> flowOf(null)
    }
  }

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
 * Cio' che la Diagnostica dei ricordi conta (§2.12, D33): **solo numeri**, mai titoli, luoghi, date, id o nomi. DG-1 e DG-2 (31/10): i conti **si persistono**: gli esclusi
 * ([DropReason]), le notifiche saltate ([NotifySkip]), gli scaffali rigenerati, i doppioni senza vettori, le notifiche tolte e quelle consegnate stanno nel file dei conti
 * ([tally], `memories/diag-counts`) per gli ultimi 14 giorni, gli stessi delle consegne in ritardo ([late], `NOTIFY_LATE`, che decidono il piano B di D20 in F115): cosi'
 * il lavoro delle notifiche e la notte, che girano in processi che muoiono subito, lasciano il loro conto, e "in ritardo" e "consegnate" hanno la stessa base. Il resto
 * (giri, errori, ultimo giro, durata, montaggio fallito) e' del solo processo di adesso: serve a dire come sta l'app ora.
 */
class MemoriesCounters(
  val late: LateDeliveries? = null,
  private val tally: DayTally = DayTally(null) { LocalDate.now() },
) : NotifyEvents {
  @Volatile var mountFailed: Boolean = false

  @Volatile var roundErrors: Int = 0

  @Volatile var rounds: Int = 0
    private set

  @Volatile var lastRound: RefreshReport? = null
    private set

  @Volatile var lastRoundMs: Long? = null
    private set

  /** Quanti scaffali si sono rigenerati (file rovinato) negli ultimi 14 giorni. */
  val shelfResets: Int get() = tally.count(SHELF_RESET)

  /** Quanti giri hanno cercato i doppioni senza i vettori negli ultimi 14 giorni. */
  val withoutVectors: Int get() = tally.count(NO_VECTORS)

  /** Le notifiche consegnate negli ultimi 14 giorni (la base di `late`). */
  val delivered: Int get() = tally.count(DELIVERED)

  /** Le notifiche tolte (un nascosto, uno scarto) negli ultimi 14 giorni. */
  val revoked: Int get() = tally.count(REVOKED)

  /** Un giro del motore: scaffali rigenerati, doppioni senza vettori, esclusi per motivo (persistiti, una sola scrittura). */
  @Synchronized
  fun round(report: RefreshReport, durationMs: Long? = null) {
    rounds++
    lastRound = report
    if (durationMs != null) lastRoundMs = durationMs
    val add = LinkedHashMap<String, Int>()
    if (report.shelfReset) add[SHELF_RESET] = 1
    if (report.withoutVectors) add[NO_VECTORS] = 1
    for ((reason, count) in report.dropped) add[DROPPED + reason.code] = count
    tally.addAll(add)
  }

  /** Gli esclusi per motivo negli ultimi 14 giorni (solo quelli oltre zero). */
  fun droppedByReason(): Map<DropReason, Int> {
    val counts = tally.counts(DROPPED)
    val out = EnumMap<DropReason, Int>(DropReason::class.java)
    for (reason in DropReason.entries) counts[reason.code]?.let { out[reason] = it }
    return out
  }

  /** Le notifiche saltate per motivo negli ultimi 14 giorni (solo quelle oltre zero). */
  fun skippedByReason(): Map<NotifySkip, Int> {
    val counts = tally.counts(SKIPPED)
    val out = EnumMap<NotifySkip, Int>(NotifySkip::class.java)
    for (reason in NotifySkip.entries) counts[reason.code]?.let { out[reason] = it }
    return out
  }

  override fun skipped(reason: NotifySkip) {
    tally.add(SKIPPED + reason.code)
  }

  override fun delivered(late: Boolean) {
    tally.add(DELIVERED)
    if (late) this.late?.record()
  }

  override fun revoked() {
    tally.add(REVOKED)
  }

  private companion object {
    const val SHELF_RESET = "shelf_reset"
    const val NO_VECTORS = "no_vectors"
    const val DROPPED = "dropped."
    const val SKIPPED = "skip."
    const val DELIVERED = "delivered"
    const val REVOKED = "revoked"
  }
}
