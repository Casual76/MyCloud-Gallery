package dev.pampa.mycloudgallery.core.widget.schedule

import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSchedule
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleRules
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Le ore del lavoro periodico (D6, Q2 (a): circa ogni 3 ore, mai di notte). Pura: l'ora locale arriva da `WidgetClock.nowLocal`.
 *
 * Il prossimo giro e' [now] piu' la frequenza; se cadrebbe nelle ore di quiete (dalle 23:00 alle 7:00 **ora locale**, 23:00 compresa e
 * 7:00 esclusa) slitta alle 7:00 del mattino dopo quel momento. Con [WidgetPeriod.DAILY] il giro e' sempre alle 7:00 del primo mattino
 * **dopo** [now] (alle 7:00 in punto, il giorno dopo). Un'ora locale che non esiste o e' doppia per il cambio dell'ora legale non crea
 * problemi: si ragiona sull'ora scritta sull'orologio, e `WorkManager` ha la sua flessibilita' di 30 minuti.
 */
class DefaultWidgetSchedule : WidgetSchedule {

  override fun isQuiet(now: LocalTime): Boolean {
    val from = WidgetScheduleRules.QUIET_FROM
    val until = WidgetScheduleRules.QUIET_UNTIL
    // La quiete attraversa la mezzanotte: da 23:00 in poi, oppure prima delle 7:00.
    return if (from.isAfter(until)) !now.isBefore(from) || now.isBefore(until) else !now.isBefore(from) && now.isBefore(until)
  }

  override fun nextRun(now: LocalDateTime, period: WidgetPeriod): LocalDateTime {
    if (period == WidgetPeriod.DAILY) return firstMorningAfter(now)
    val plain = now.plusHours(period.hours.toLong())
    return if (isQuiet(plain.toLocalTime())) firstMorningAfter(plain) else plain
  }

  /** Le 7:00 (fine della quiete) del primo mattino **strettamente dopo** [moment]. */
  private fun firstMorningAfter(moment: LocalDateTime): LocalDateTime {
    val date: LocalDate = moment.toLocalDate()
    val sameDay = date.atTime(WidgetScheduleRules.QUIET_UNTIL)
    return if (sameDay.isAfter(moment)) sameDay else sameDay.plusDays(1)
  }
}
