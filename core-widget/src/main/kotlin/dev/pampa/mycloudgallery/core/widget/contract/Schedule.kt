package dev.pampa.mycloudgallery.core.widget.contract

import java.time.LocalDateTime
import java.time.LocalTime

// QUANDO SI RIDISEGNA (docs/fase10-progetto.md §1.2 "Orari", D6; Q2 (a): circa ogni 3 ore, mai di notte). Contratto di P0, regole fisse in
// [WidgetScheduleRules]; l'implementazione vera e' di P2 (`schedule/`) e deve passare la suite `WidgetScheduleContract`.

/** Le costanti del ridisegno (D6, §2.1). Anche in `docs/regole-attivita.md` §6. */
object WidgetScheduleRules {
  /** Ore di quiete, ora **locale**: dalle 23:00 (compresa) alle 7:00 (esclusa) nessun giro periodico. */
  val QUIET_FROM: LocalTime = LocalTime.of(23, 0)
  val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)

  /** Il nome unico del lavoro periodico di WorkManager (solo se c'e' almeno un widget nostro). */
  const val WORK_NAME: String = "widget-refresh"

  /** Flessibilita' del lavoro periodico. */
  const val FLEX_MINUTES: Long = 30

  /** Ridisegno all'uscita dall'app: dopo 2 s di calma, al piu' una volta ogni 15 minuti. */
  const val BACKGROUND_CALM_MS: Long = 2_000
  const val BACKGROUND_MIN_GAP_MS: Long = 15 * 60 * 1_000L
}

/**
 * Le ore del lavoro periodico. Pure: nessun orologio dentro (l'ora arriva da `WidgetClock.nowLocal`).
 * - [isQuiet]: vero dentro le ore di quiete ([WidgetScheduleRules.QUIET_FROM] compresa, [WidgetScheduleRules.QUIET_UNTIL] esclusa);
 * - [nextRun]: il prossimo giro **dopo** [now] con la frequenza [period], mai dentro le ore di quiete (un giro che ci cadrebbe passa alle 7:00
 *   dopo); con [WidgetPeriod.DAILY] il giro e' sempre alle 7:00 del primo mattino dopo [now].
 */
interface WidgetSchedule {
  fun isQuiet(now: LocalTime): Boolean

  fun nextRun(now: LocalDateTime, period: WidgetPeriod): LocalDateTime
}
