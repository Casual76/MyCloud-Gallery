package dev.pampa.mycloudgallery.widget

import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetBlankCause
import dev.pampa.mycloudgallery.core.widget.contract.WidgetDiag
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlanner
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRefresher
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSchedule
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import java.time.Duration
import java.time.LocalDateTime
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// CIO' CHE IL WIDGET CONTA PER LA DIAGNOSTICA (Fase 10, P13, docs/fase10-progetto.md §2.13, D39, C449). Il motore di feature-widget non conta (nota di P3): si conta
// qui, **attorno** alle porte che `:app` gli da' (pianificatore, miniature, blocco, ridisegno). Solo numeri: mai un id di media o di ricordo, un titolo, un luogo.
// In memoria, per processo (come i contatori delle integrazioni); l'ultimo aggiornamento e i giri mancati si leggono dal registro su file, che sopravvive.

/** I contatori del widget in questo processo. */
class WidgetCounters {
  private val noThumb = AtomicInteger()
  private val locked = AtomicInteger()
  private val blanked = EnumMap<WidgetBlankCause, AtomicInteger>(WidgetBlankCause::class.java).apply {
    WidgetBlankCause.entries.forEach { put(it, AtomicInteger()) }
  }

  @Volatile
  var lastError: WidgetEmpty? = null
    private set

  fun skippedNoThumb(n: Int) {
    if (n > 0) noThumb.addAndGet(n)
  }

  fun skippedLocked(n: Int) {
    if (n > 0) locked.addAndGet(n)
  }

  fun blanked(cause: WidgetBlankCause, widgets: Int) {
    if (widgets > 0) blanked.getValue(cause).addAndGet(widgets)
  }

  fun planned(plan: WidgetPlan) {
    val mode = plan.mode
    if (mode is WidgetMode.Message && mode.reason == WidgetEmpty.ERROR) lastError = WidgetEmpty.ERROR
  }

  /** I numeri del §2.13 con quelli che il processo non sa da solo (widget per misura, minuti dall'ultimo aggiornamento, frequenza, giri mancati). */
  fun diag(widgets: Map<WidgetSize, Int>, minutesSinceUpdate: Long?, period: WidgetPeriod, lateRuns7d: Int): WidgetDiag = WidgetDiag(
    widgets = WidgetSize.entries.associateWith { widgets[it] ?: 0 },
    minutesSinceUpdate = minutesSinceUpdate,
    period = period,
    skippedNoThumb = noThumb.get(),
    skippedLocked = locked.get(),
    blanked = WidgetBlankCause.entries.associateWith { blanked.getValue(it).get() },
    lateRuns7d = lateRuns7d,
    lastError = lastError,
  )

  /** Azzeramento (D42): i numeri di prima non valgono per l'app di dopo. */
  fun reset() {
    noThumb.set(0)
    locked.set(0)
    blanked.values.forEach { it.set(0) }
    lastError = null
  }

  companion object {
    /**
     * I giri periodici mancati fra l'ultimo aggiornamento [last] e [now] (al piu' negli ultimi 7 giorni): gli orari di [schedule] (mai 23-7, D6) passati da piu'
     * della flessibilita' di WorkManager (30 minuti) senza un aggiornamento. Zero senza un aggiornamento noto.
     */
    fun lateRuns(schedule: WidgetSchedule, period: WidgetPeriod, last: LocalDateTime?, now: LocalDateTime): Int {
      if (last == null || !now.isAfter(last)) return 0
      val from = maxOf(last, now.minusDays(LATE_WINDOW_DAYS))
      var late = 0
      var due = schedule.nextRun(from, period)
      while (due.plus(FLEX).isBefore(now) && late < MAX_LATE) {
        late++
        due = schedule.nextRun(due, period)
      }
      return late
    }

    private const val LATE_WINDOW_DAYS = 7L
    private const val MAX_LATE = 7 * 8
    private val FLEX: Duration = Duration.ofMinutes(30)
  }
}

/** Il pianificatore con il conto dei giri finiti in errore (WIDGET_ERROR). Nessuna logica in piu': il piano e' quello di [inner]. */
class CountingWidgetPlanner(private val inner: WidgetPlanner, private val counters: WidgetCounters) : WidgetPlanner {
  override suspend fun plan(slot: WidgetSlot, size: WidgetSize, budgetMs: Long): WidgetPlan = inner.plan(slot, size, budgetMs).also { counters.planned(it) }
}

/**
 * Il ridisegno con il conto degli svuotamenti per causa (WIDGET_BLANKED): quanti widget mostravano un nascosto o un ricordo scartato (dal registro, come fa il
 * motore), e tutti all'azzeramento. Il ridisegno vero e' [inner]; un errore del conto non ferma mai lo svuotamento.
 */
class CountingWidgetRefresher(
  private val inner: WidgetRefresher,
  private val ledger: WidgetLedger,
  private val counters: WidgetCounters,
  private val widgets: () -> Int,
  private val scope: CoroutineScope,
) : WidgetRefresher {

  override fun hiddenChanged(ids: Set<MediaId>) {
    if (ids.isEmpty()) return
    counted(WidgetBlankCause.HIDDEN, { ledger.showing(ids).size }) { inner.hiddenChanged(ids) }
  }

  override fun memoryDropped(id: MemoryId) {
    counted(WidgetBlankCause.MEMORY_DROPPED, { ledger.showingMemory(id).size }) { inner.memoryDropped(id) }
  }

  override fun shelfChanged() = inner.shelfChanged()

  override fun appearanceChanged() = inner.appearanceChanged()

  override fun appBackgrounded() = inner.appBackgrounded()

  override fun lockChanged() = inner.lockChanged()

  override suspend fun blankAll(reason: WidgetEmpty) {
    val n = runCatching { widgets() }.getOrDefault(0)
    inner.blankAll(reason)
    counters.blanked(WidgetBlankCause.RESET, n)
  }

  /**
   * Si conta **prima** di passare la causa al motore (che svuota e riscrive il registro: dopo, il registro non mostrerebbe piu' quei media). La lettura del registro e' in
   * memoria; il motore la rifa' comunque per sapere chi svuotare.
   */
  private fun counted(cause: WidgetBlankCause, affected: suspend () -> Int, then: () -> Unit) {
    scope.launch {
      val n = try {
        affected()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Il registro non si legge: il motore svuota tutti (fallire chiusi); il conto resta com'era.
        0
      }
      then()
      counters.blanked(cause, n)
    }
  }
}
