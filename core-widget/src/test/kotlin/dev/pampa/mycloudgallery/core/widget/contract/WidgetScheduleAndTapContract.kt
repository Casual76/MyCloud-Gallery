package dev.pampa.mycloudgallery.core.widget.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.core.widget.testing.ReferenceWidgetSchedule
import dev.pampa.mycloudgallery.core.widget.testing.ReferenceWidgetTaps
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto delle ore del widget (Fase 10, P0; D6, Q2 (a)): gira sul riferimento e, da P2, su `schedule/`. Mai un giro dentro le ore
 * di quiete (23-7, ora locale), sempre dopo "adesso", al piu' una frequenza dopo (salvo lo spostamento alle 7), il giornaliero alle 7.
 */
abstract class WidgetScheduleContract {
  protected abstract fun schedule(): WidgetSchedule

  @Test
  fun oreDiQuiete() {
    val s = schedule()
    for ((h, m, quiet) in listOf(Triple(22, 59, false), Triple(23, 0, true), Triple(2, 0, true), Triple(6, 59, true), Triple(7, 0, false), Triple(12, 0, false))) {
      assertWithMessage("$h:$m").that(s.isQuiet(LocalTime.of(h, m))).isEqualTo(quiet)
    }
  }

  @Test
  fun ilProssimoGiroNonCadeMaiDiNotte() {
    val s = schedule()
    val random = Random(31)
    repeat(5_000) {
      val now = LocalDateTime.of(2026, 1 + random.nextInt(12), 1 + random.nextInt(28), random.nextInt(24), random.nextInt(60))
      for (period in WidgetPeriod.entries) {
        val next = s.nextRun(now, period)
        assertWithMessage("$now $period").that(next.isAfter(now)).isTrue()
        assertWithMessage("$now $period dentro la quiete").that(s.isQuiet(next.toLocalTime())).isFalse()
        if (period == WidgetPeriod.DAILY) {
          assertThat(next.toLocalTime()).isEqualTo(WidgetScheduleRules.QUIET_UNTIL)
          assertThat(next).isAtMost(now.plusDays(1))
        } else {
          val plain = now.plusHours(period.hours.toLong())
          if (!s.isQuiet(plain.toLocalTime())) assertThat(next).isEqualTo(plain) else assertThat(next.toLocalTime()).isEqualTo(WidgetScheduleRules.QUIET_UNTIL)
        }
      }
    }
  }

  @Test
  fun diBaseOgniTreOre() {
    assertThat(WidgetPeriod.DEFAULT).isEqualTo(WidgetPeriod.HOURS_3)
    assertThat(schedule().nextRun(LocalDateTime.of(2026, 10, 31, 9, 0), WidgetPeriod.DEFAULT)).isEqualTo(LocalDateTime.of(2026, 10, 31, 12, 0))
    assertThat(schedule().nextRun(LocalDateTime.of(2026, 10, 31, 21, 0), WidgetPeriod.DEFAULT)).isEqualTo(LocalDateTime.of(2026, 11, 1, 7, 0))
  }
}

class ReferenceWidgetScheduleContractTest : WidgetScheduleContract() {
  override fun schedule(): WidgetSchedule = ReferenceWidgetSchedule()
}

/**
 * La suite di contratto del tocco (Fase 10, P0; D9, C17, C433, E-W7, E-W8): gira sul riferimento e, da P2, sul `WidgetTaps` vero. Solo
 * `mcg-widget:<int>/<int>` nei limiti; un widget non nostro o una cella sconosciuta aprono la Libreria; un media non piu' visibile o nascosto la
 * Libreria con l'avviso; la copertina di un ricordo la sua pagina.
 */
abstract class WidgetTapContract {
  protected abstract fun taps(): WidgetTaps

  @Test
  fun soloLaFormaEsatta() {
    val t = taps()
    assertThat(t.parse("mcg-widget:12/3")).isEqualTo(WidgetTapRequest(12, 3))
    assertThat(t.parse("mcg-widget:${Int.MAX_VALUE}/6")).isEqualTo(WidgetTapRequest(Int.MAX_VALUE, 6))
    assertThat(t.parse(WidgetTapUri.format(WidgetTapRequest(5, 0)))).isEqualTo(WidgetTapRequest(5, 0))
    val bad = listOf(
      null, "", "mcg-widget:", "mcg-widget:12", "mcg-widget:12/", "mcg-widget:/3", "mcg-widget:0/1", "mcg-widget:-1/1", "mcg-widget:+1/1",
      "mcg-widget:012/1", "mcg-widget:12/01", "mcg-widget:12/7", "mcg-widget:12/3/4", "mcg-widget:12/3?x=1", "mcg-widget:12/3#f",
      "mcg-widget: 12/3", "mcg-widget:12/3 ", "MCG-WIDGET:12/3", "mcg-widget://12/3", "mcg-widgetx:12/3", "xmcg-widget:12/3",
      "mcg-widget:2147483648/1", "mcg-widget:99999999999/1", "mcg-widget:１２/3", "mcg-widget:12/٣", "mcg-widget:1e3/1", "content://x/12/3",
      "mcg-widget:12\u0000/3", "mcg-widget:12%2F3",
    )
    for (uri in bad) assertWithMessage("accettato: $uri").that(t.parse(uri)).isNull()
  }

  @Test
  fun ilTipoDiQualunqueStringaNonEsplode() {
    val t = taps()
    val random = Random(7)
    val alphabet = "mcg-widget:/0123456789 +-%#?\u0000١x"
    repeat(20_000) {
      val text = String(CharArray(random.nextInt(24)) { alphabet[random.nextInt(alphabet.length)] })
      val parsed = t.parse(text)
      if (parsed != null) assertThat(WidgetTapUri.format(parsed)).isEqualTo(text)
    }
  }

  @Test
  fun siRisolveSulRegistroLocale() = runTest {
    val t = taps()
    fun id(n: Int) = MediaId(UUID(20L, n.toLong()).toString())
    val memory = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 3)))
    val ledger = InMemoryWidgetLedger()
    ledger.record(WidgetPlan(WidgetSlot(4), WidgetSize.WIDE, WidgetMode.Memory(memory, WidgetTitle(null, emptyList(), null, YearMonth.of(2025, 3))),
      listOf(WidgetCell(0, id(1), MediaKind.PHOTO, 20250301), WidgetCell(1, id(2), MediaKind.VIDEO, 20250302)), 1))
    val visibility = FakeVisibility(present = setOf(id(1), id(2)))
    val own = setOf(WidgetSlot(4))
    assertThat(t.resolve(WidgetTapRequest(4, 0), own, ledger, visibility, visibility)).isEqualTo(WidgetTarget.MemoryPage(memory))
    assertThat(t.resolve(WidgetTapRequest(4, 1), own, ledger, visibility, visibility)).isEqualTo(WidgetTarget.Viewer(id(2)))
    assertWithMessage("widget non nostro").that(t.resolve(WidgetTapRequest(5, 0), own, ledger, visibility, visibility)).isEqualTo(WidgetTarget.Library(false))
    assertWithMessage("cella sconosciuta").that(t.resolve(WidgetTapRequest(4, 3), own, ledger, visibility, visibility)).isEqualTo(WidgetTarget.Library(false))
    visibility.hidden += id(2)
    assertWithMessage("E-W8: nascosto nel frattempo").that(t.resolve(WidgetTapRequest(4, 1), own, ledger, visibility, visibility)).isEqualTo(WidgetTarget.Library(true))
    visibility.present -= id(1)
    assertWithMessage("E-W8: eliminato nel frattempo").that(t.resolve(WidgetTapRequest(4, 0), own, ledger, visibility, visibility)).isEqualTo(WidgetTarget.Library(true))
  }
}

class ReferenceWidgetTapsContractTest : WidgetTapContract() {
  override fun taps(): WidgetTaps = ReferenceWidgetTaps()
}
