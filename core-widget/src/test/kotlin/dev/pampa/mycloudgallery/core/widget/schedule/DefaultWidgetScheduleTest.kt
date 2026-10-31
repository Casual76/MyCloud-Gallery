package dev.pampa.mycloudgallery.core.widget.schedule

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSchedule
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleContract
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleRules
import dev.pampa.mycloudgallery.core.widget.testing.ReferenceWidgetSchedule
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 10 P2: `DefaultWidgetSchedule` passa la suite di contratto di P0 (5.000 ore a caso) e va d'accordo con il riferimento. */
class DefaultWidgetScheduleContractTest : WidgetScheduleContract() {
  override fun schedule(): WidgetSchedule = DefaultWidgetSchedule()
}

/** Ore di quiete e periodi di Q2 (D6): circa ogni 3 ore, mai fra le 23 e le 7 ora locale. */
class DefaultWidgetScheduleTest {

  private val schedule = DefaultWidgetSchedule()

  private fun at(h: Int, m: Int = 0, day: Int = 31, month: Int = 10) = LocalDateTime.of(2026, month, day, h, m)

  @Test
  fun leOreDiQuieteSonoLeVentitreEleSette() {
    assertThat(WidgetScheduleRules.QUIET_FROM).isEqualTo(LocalTime.of(23, 0))
    assertThat(WidgetScheduleRules.QUIET_UNTIL).isEqualTo(LocalTime.of(7, 0))
    for (minute in 0 until 24 * 60) {
      val time = LocalTime.of(minute / 60, minute % 60)
      val expected = minute >= 23 * 60 || minute < 7 * 60
      assertWithMessage("$time").that(schedule.isQuiet(time)).isEqualTo(expected)
    }
    assertThat(schedule.isQuiet(LocalTime.MIDNIGHT)).isTrue()
    assertThat(schedule.isQuiet(LocalTime.MAX)).isTrue()
    assertThat(schedule.isQuiet(LocalTime.of(6, 59, 59, 999_999_999))).isTrue()
    assertThat(schedule.isQuiet(LocalTime.of(7, 0))).isFalse()
    assertThat(schedule.isQuiet(LocalTime.of(22, 59, 59, 999_999_999))).isFalse()
  }

  @Test
  fun ogniTreOreDiGiornoESaltaLaNotte() {
    // I giri di una giornata partendo dalle 7:00: 10, 13, 16, 19, 22, poi (le 25 sarebbero le 1:00) le 7:00 del giorno dopo.
    var now = at(7)
    val seen = mutableListOf<LocalDateTime>()
    repeat(6) {
      now = schedule.nextRun(now, WidgetPeriod.HOURS_3)
      seen += now
    }
    assertThat(seen).containsExactly(at(10), at(13), at(16), at(19), at(22), at(7, day = 1, month = 11)).inOrder()
  }

  @Test
  fun unGiroCheCadrebbeDiNotteVaAlleSette() {
    assertThat(schedule.nextRun(at(20), WidgetPeriod.HOURS_3)).isEqualTo(at(7, day = 1, month = 11)) // 23:00 e' gia' quiete
    assertThat(schedule.nextRun(at(21), WidgetPeriod.HOURS_3)).isEqualTo(at(7, day = 1, month = 11))
    assertThat(schedule.nextRun(at(22, 59), WidgetPeriod.HOURS_3)).isEqualTo(at(7, day = 1, month = 11)) // 1:59
    assertThat(schedule.nextRun(at(3), WidgetPeriod.HOURS_3)).isEqualTo(at(7, day = 31)) // di notte: alle 6:00 e' ancora quiete
    assertThat(schedule.nextRun(at(4), WidgetPeriod.HOURS_3)).isEqualTo(at(7, day = 31)) // 7:00 esatte: fuori dalla quiete
    assertThat(schedule.nextRun(at(4, 1), WidgetPeriod.HOURS_3)).isEqualTo(at(7, 1, day = 31))
    assertThat(schedule.nextRun(at(16, 30), WidgetPeriod.HOURS_6)).isEqualTo(at(22, 30)) // 22:30 e' ancora fuori dalla quiete
    assertThat(schedule.nextRun(at(17, 30), WidgetPeriod.HOURS_6)).isEqualTo(at(7, day = 1, month = 11)) // 23:30: quiete
  }

  @Test
  fun ogniSeOreEIlGiornaliero() {
    assertThat(schedule.nextRun(at(9), WidgetPeriod.HOURS_6)).isEqualTo(at(15))
    assertThat(schedule.nextRun(at(15), WidgetPeriod.HOURS_6)).isEqualTo(at(21))
    assertThat(schedule.nextRun(at(21), WidgetPeriod.HOURS_6)).isEqualTo(at(7, day = 1, month = 11))
    // Il giornaliero e' sempre alle 7:00 del primo mattino dopo adesso.
    assertThat(schedule.nextRun(at(6, 59), WidgetPeriod.DAILY)).isEqualTo(at(7))
    assertThat(schedule.nextRun(at(7), WidgetPeriod.DAILY)).isEqualTo(at(7, day = 1, month = 11))
    assertThat(schedule.nextRun(at(12), WidgetPeriod.DAILY)).isEqualTo(at(7, day = 1, month = 11))
    assertThat(schedule.nextRun(at(23, 30), WidgetPeriod.DAILY)).isEqualTo(at(7, day = 1, month = 11))
  }

  @Test
  fun alCambioDiMeseEDAnnoNonSiRompe() {
    assertThat(schedule.nextRun(LocalDateTime.of(2026, 12, 31, 22, 0), WidgetPeriod.HOURS_3)).isEqualTo(LocalDateTime.of(2027, 1, 1, 7, 0))
    assertThat(schedule.nextRun(LocalDateTime.of(2028, 2, 28, 23, 30), WidgetPeriod.DAILY)).isEqualTo(LocalDateTime.of(2028, 2, 29, 7, 0))
    assertThat(schedule.nextRun(LocalDateTime.of(2028, 2, 29, 23, 30), WidgetPeriod.DAILY)).isEqualTo(LocalDateTime.of(2028, 3, 1, 7, 0))
  }

  @Test
  fun diBaseOgniTreOre() {
    assertThat(WidgetPeriod.DEFAULT).isEqualTo(WidgetPeriod.HOURS_3)
    assertThat(WidgetPeriod.HOURS_3.hours).isEqualTo(3)
    assertThat(WidgetPeriod.HOURS_6.hours).isEqualTo(6)
    assertThat(WidgetPeriod.DAILY.hours).isEqualTo(24)
  }

  @Test
  fun vaDaccordoConIlRiferimentoSuVentimilaOreACaso() {
    val reference = ReferenceWidgetSchedule()
    val random = Random(2026)
    repeat(20_000) {
      val now = LocalDateTime.of(2026 + random.nextInt(3), 1 + random.nextInt(12), 1 + random.nextInt(28), random.nextInt(24), random.nextInt(60), random.nextInt(60), random.nextInt(1_000_000_000))
      assertThat(schedule.isQuiet(now.toLocalTime())).isEqualTo(reference.isQuiet(now.toLocalTime()))
      for (period in WidgetPeriod.entries) assertWithMessage("$now $period").that(schedule.nextRun(now, period)).isEqualTo(reference.nextRun(now, period))
    }
  }

  // --- la politica del lavoro periodico --------------------------------------------------------------------------------------

  private val policy = WidgetRunPolicy(schedule)

  @Test
  fun ilLavoroPartitoDiNotteEsceSubito() {
    assertThat(policy.decide(at(23), widgetCount = 2, powerSaver = false)).isEqualTo(WidgetRunDecision.QUIET)
    assertThat(policy.decide(at(3), widgetCount = 1, powerSaver = false)).isEqualTo(WidgetRunDecision.QUIET)
    assertThat(policy.decide(at(6, 59), widgetCount = 1, powerSaver = false)).isEqualTo(WidgetRunDecision.QUIET)
    assertThat(policy.decide(at(7), widgetCount = 1, powerSaver = false)).isEqualTo(WidgetRunDecision.RUN)
    assertThat(policy.decide(at(22, 59), widgetCount = 1, powerSaver = false)).isEqualTo(WidgetRunDecision.RUN)
  }

  @Test
  fun senzaWidgetIlLavoroSiAnnullaConIlRisparmioEnergeticoTace() {
    assertThat(policy.decide(at(12), widgetCount = 0, powerSaver = false)).isEqualTo(WidgetRunDecision.NO_WIDGETS)
    assertThat(policy.decide(at(3), widgetCount = 0, powerSaver = true)).isEqualTo(WidgetRunDecision.NO_WIDGETS)
    assertThat(policy.decide(at(12), widgetCount = 3, powerSaver = true)).isEqualTo(WidgetRunDecision.POWER_SAVER)
    assertThat(policy.decide(at(2), widgetCount = 3, powerSaver = true)).isEqualTo(WidgetRunDecision.POWER_SAVER)
    assertThat(policy.decide(at(12), widgetCount = -1, powerSaver = false)).isEqualTo(WidgetRunDecision.NO_WIDGETS)
  }

  @Test
  fun iNumeriPerWorkManager() {
    val plan = policy.workPlan(at(9), WidgetPeriod.HOURS_3)
    assertThat(plan.intervalMinutes).isEqualTo(180)
    assertThat(plan.flexMinutes).isEqualTo(WidgetScheduleRules.FLEX_MINUTES)
    assertThat(plan.flexMinutes).isEqualTo(30)
    assertThat(plan.initialDelayMinutes).isEqualTo(180)
    // Un widget messo sulla Home alle 22 non parte alle 1 ma alle 7 del mattino.
    assertThat(policy.workPlan(at(22), WidgetPeriod.HOURS_3).initialDelayMinutes).isEqualTo(9 * 60)
    assertThat(policy.workPlan(at(12), WidgetPeriod.DAILY).intervalMinutes).isEqualTo(24 * 60)
    assertThat(policy.workPlan(at(12), WidgetPeriod.DAILY).initialDelayMinutes).isEqualTo(19 * 60)
    for (period in WidgetPeriod.entries) {
      val p = policy.workPlan(at(5, 17), period)
      assertThat(Duration.ofMinutes(p.initialDelayMinutes)).isAtMost(Duration.ofHours(period.hours.toLong() + 8))
    }
    assertThrows(IllegalArgumentException::class.java) { WidgetWorkPlan(0, 0, 0) }
    assertThrows(IllegalArgumentException::class.java) { WidgetWorkPlan(60, 61, 0) }
    assertThrows(IllegalArgumentException::class.java) { WidgetWorkPlan(60, 10, -1) }
  }

  // --- l'uscita dall'app --------------------------------------------------------------------------------------------------------

  @Test
  fun ilRidisegnoDopoDueSecondiDiCalma() {
    val gate = BackgroundRefreshGate()
    assertThat(gate.waitMs(0)).isNull()
    assertThat(gate.fire(0)).isFalse()
    gate.backgrounded(10_000)
    assertThat(gate.waitMs(10_000)).isEqualTo(WidgetScheduleRules.BACKGROUND_CALM_MS)
    assertThat(gate.fire(11_999)).isFalse()
    assertThat(gate.waitMs(11_000)).isEqualTo(1_000)
    assertThat(gate.fire(12_000)).isTrue()
    // Consumato: serve un'altra uscita dall'app.
    assertThat(gate.fire(12_001)).isFalse()
    assertThat(gate.waitMs(12_001)).isNull()
  }

  @Test
  fun tornareInPrimoPianoAnnullaLAttesa() {
    val gate = BackgroundRefreshGate()
    gate.backgrounded(0)
    gate.foregrounded()
    assertThat(gate.fire(5_000)).isFalse()
    gate.backgrounded(6_000)
    assertThat(gate.fire(8_000)).isTrue()
  }

  @Test
  fun alPiuUnaVoltaOgniQuindiciMinuti() {
    val gate = BackgroundRefreshGate()
    val gap = WidgetScheduleRules.BACKGROUND_MIN_GAP_MS
    assertThat(gap).isEqualTo(15 * 60 * 1_000L)
    gate.backgrounded(0)
    assertThat(gate.fire(2_000)).isTrue()
    // Un'altra uscita subito dopo: l'attesa arriva ai 15 minuti dal ridisegno, non ai 2 secondi.
    gate.backgrounded(60_000)
    assertThat(gate.fire(62_000)).isFalse()
    assertThat(gate.waitMs(62_000)).isEqualTo(2_000 + gap - 62_000)
    assertThat(gate.fire(2_000 + gap - 1)).isFalse()
    assertThat(gate.fire(2_000 + gap)).isTrue()
    // Un'uscita lontana dall'ultimo ridisegno aspetta solo la calma.
    gate.backgrounded(2_000 + gap + 3_600_000)
    assertThat(gate.waitMs(2_000 + gap + 3_600_000)).isEqualTo(2_000)
  }

  @Test
  fun unSecondoBackgroundedNonSpostaLAttesa() {
    val gate = BackgroundRefreshGate()
    gate.backgrounded(1_000)
    gate.backgrounded(1_900)
    assertThat(gate.fire(3_000)).isTrue()
  }
}
