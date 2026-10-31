package dev.pampa.mycloudgallery.core.widget.schedule

import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSchedule
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleRules
import java.time.Duration
import java.time.LocalDateTime

/** Che cosa fa il lavoro periodico `widget-refresh` quando parte (D6, §2.1, E-P10). */
enum class WidgetRunDecision {
  /** Si ridisegna. */
  RUN,

  /** Nessun widget nostro sulla Home: niente da fare e il lavoro periodico si **annulla**. */
  NO_WIDGETS,

  /** Risparmio energetico: il periodico tace, resta l'ultimo contenuto (gia' verificato). */
  POWER_SAVER,

  /** Dentro le ore di quiete (23:00-7:00): esce subito. */
  QUIET,
}

/** I parametri del `PeriodicWorkRequest` per una frequenza: P3 li passa a WorkManager, senza importarne i numeri. */
data class WidgetWorkPlan(val intervalMinutes: Long, val flexMinutes: Long, val initialDelayMinutes: Long) {
  init {
    require(intervalMinutes > 0 && flexMinutes in 0..intervalMinutes && initialDelayMinutes >= 0) { "Piano del lavoro non valido." }
  }
}

/**
 * La politica del lavoro periodico del widget, pura (D6, Q2): quando parte, che frequenza ha, quando tace. Il vincolo "batteria non
 * scarica" e' di WorkManager (P3); qui c'e' tutto cio' che si decide sull'ora locale e sul risparmio energetico.
 */
class WidgetRunPolicy(private val schedule: WidgetSchedule = DefaultWidgetSchedule()) {

  /**
   * Il lavoro e' partito alle [now] (ora locale): [widgetCount] sono i widget nostri sulla Home, [powerSaver] il risparmio energetico.
   * L'ordine conta: senza widget il lavoro si annulla qualunque sia l'ora; poi il risparmio energetico; poi la quiete.
   */
  fun decide(now: LocalDateTime, widgetCount: Int, powerSaver: Boolean): WidgetRunDecision = when {
    widgetCount <= 0 -> WidgetRunDecision.NO_WIDGETS
    powerSaver -> WidgetRunDecision.POWER_SAVER
    schedule.isQuiet(now.toLocalTime()) -> WidgetRunDecision.QUIET
    else -> WidgetRunDecision.RUN
  }

  /**
   * I numeri per WorkManager: intervallo pari alla frequenza ([WidgetPeriod.hours]), flessibilita' di
   * [WidgetScheduleRules.FLEX_MINUTES], e il primo giro al prossimo orario utile ([WidgetSchedule.nextRun]): un widget messo sulla Home alle
   * 22 non parte alle 23 ma alle 7.
   */
  fun workPlan(now: LocalDateTime, period: WidgetPeriod): WidgetWorkPlan {
    val interval = period.hours * 60L
    val delay = Duration.between(now, schedule.nextRun(now, period)).toMinutes().coerceAtLeast(0)
    return WidgetWorkPlan(interval, WidgetScheduleRules.FLEX_MINUTES.coerceAtMost(interval), delay)
  }
}

/**
 * Il ridisegno "l'app e' andata in secondo piano" (§2.1): **dopo 2 s di calma**, **al piu' una volta ogni 15 minuti** ([WidgetScheduleRules]).
 * Pura e con il tempo che le si da' (millisecondi **monotoni**, da `WidgetClock.monotonicMs`): nessun timer qui, P3 aspetta [waitMs] e poi
 * chiede [fire]. Una per widget-graph, non per widget.
 */
class BackgroundRefreshGate {

  private var backgroundedAt: Long? = null
  private var lastFiredAt: Long? = null

  /** L'app e' andata in secondo piano alle [nowMs]. Chiamarla di nuovo senza essere tornati in primo piano non sposta l'attesa. */
  @Synchronized
  fun backgrounded(nowMs: Long) {
    if (backgroundedAt == null) backgroundedAt = nowMs
  }

  /** L'app e' tornata in primo piano: l'attesa in corso cade (la Home non e' piu' visibile). */
  @Synchronized
  fun foregrounded() {
    backgroundedAt = null
  }

  /** Fra quanti millisecondi chiedere [fire], o null se non c'e' un ridisegno in attesa. Mai negativo. */
  @Synchronized
  fun waitMs(nowMs: Long): Long? {
    val since = backgroundedAt ?: return null
    val calmUntil = since + WidgetScheduleRules.BACKGROUND_CALM_MS
    val gapUntil = lastFiredAt?.let { it + WidgetScheduleRules.BACKGROUND_MIN_GAP_MS } ?: Long.MIN_VALUE
    return (maxOf(calmUntil, gapUntil) - nowMs).coerceAtLeast(0)
  }

  /**
   * Vero **una volta sola** quando il ridisegno e' dovuto: l'app e' ancora in secondo piano da almeno 2 s e l'ultimo ridisegno di questo tipo
   * e' di almeno 15 minuti fa. Dopo un vero, l'attesa e' consumata (serve un altro [backgrounded]).
   */
  @Synchronized
  fun fire(nowMs: Long): Boolean {
    val wait = waitMs(nowMs) ?: return false
    if (wait > 0) return false
    backgroundedAt = null
    lastFiredAt = nowMs
    return true
  }
}
