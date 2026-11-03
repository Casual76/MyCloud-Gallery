package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.feature.widget.host.WidgetHost
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServices
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServicesProvider
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import dev.pampa.mycloudgallery.feature.widget.refresh.GlanceWidgetRefresher
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il `WidgetRefresher` vero e il posto del widget nel processo (Fase 10, P3, §2.1 "Quando si ridisegna", D5, D42, E-P13): il ridisegno all'uscita dall'app dopo 2 s di calma e al piu'
 * ogni 15 minuti, ogni causa fa il suo lavoro, nessun errore esce, e il `WidgetHost` senza fornitore risponde "niente".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WidgetRefresherTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val one = WidgetSlot(1)
  private val two = WidgetSlot(2)

  @After
  fun tearDown() {
    WidgetHost.uninstall()
  }

  private class Setup(val refresher: GlanceWidgetRefresher, val events: MutableList<String>, val targets: RecordingTargets, val clock: () -> Long)

  /** Un `WidgetRefresher` sopra un motore con porte finte e **il tempo virtuale** di [scope] come orologio monotono. */
  private fun TestScope.setup(planner: (Int) -> Unit = {}): Setup {
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(one to (160 to 160), two to (320 to 160)), events)
    val services = FakeServices(ScriptedPlanner(events) { s, size, call ->
      planner(call)
      photoPlan(s, size, 1, epoch = call.toLong())
    }, InMemoryWidgetLedger(), WidgetThumbnails { _, _ -> thumb() })
    val engine = WidgetEngine({ services }, targets, RecordingComposer(context, events), { context.resources })
    val now = { testScheduler.currentTime }
    return Setup(GlanceWidgetRefresher(context, { engine }, this, now), events, targets, now)
  }

  private fun Setup.plans() = events.count { it.startsWith("plan:") }

  @Test
  fun inSecondoPianoRidisegnaDopoDueSecondiDiCalma() = runTest {
    val s = setup()
    s.refresher.appBackgrounded()
    advanceTimeBy(WidgetScheduleRules.BACKGROUND_CALM_MS - 1)
    runCurrent()
    assertThat(s.plans()).isEqualTo(0)
    advanceTimeBy(2)
    runCurrent()
    // Un ridisegno per ogni widget, non uno di piu'.
    assertThat(s.plans()).isEqualTo(2)
  }

  @Test
  fun alPiuUnaVoltaOgniQuindiciMinuti() = runTest {
    val s = setup()
    s.refresher.appBackgrounded()
    advanceTimeBy(WidgetScheduleRules.BACKGROUND_CALM_MS + 1)
    runCurrent()
    assertThat(s.plans()).isEqualTo(2)
    // Dopo cinque minuti l'app esce di nuovo: troppo presto, aspetta il quarto d'ora.
    advanceTimeBy(5 * 60_000)
    s.refresher.appBackgrounded()
    advanceTimeBy(WidgetScheduleRules.BACKGROUND_CALM_MS + 1)
    runCurrent()
    assertThat(s.plans()).isEqualTo(2)
    advanceTimeBy(WidgetScheduleRules.BACKGROUND_MIN_GAP_MS)
    runCurrent()
    assertThat(s.plans()).isEqualTo(4)
  }

  @Test
  fun tornareInPrimoPianoAnnullaLAttesa() = runTest {
    val s = setup()
    s.refresher.appBackgrounded()
    advanceTimeBy(1_000)
    s.refresher.appForegrounded()
    advanceUntilIdle()
    assertThat(s.plans()).isEqualTo(0)
  }

  @Test
  fun ognunaDelleCauseFaIlSuoLavoro() = runTest {
    val s = setup()
    s.refresher.shelfChanged()
    runCurrent()
    assertThat(s.plans()).isEqualTo(2)
    s.refresher.appearanceChanged()
    runCurrent()
    assertThat(s.plans()).isEqualTo(4)
    s.refresher.lockChanged()
    runCurrent()
    assertThat(s.plans()).isEqualTo(6)
    // Un nascosto che nessun widget mostra non cambia niente; uno che un widget mostra lo svuota prima.
    s.refresher.hiddenChanged(setOf(mediaId(99)))
    runCurrent()
    assertThat(s.plans()).isEqualTo(6)
    s.events.clear()
    s.refresher.hiddenChanged(setOf(mediaId(0)))
    runCurrent()
    assertThat(s.events.first { it.startsWith("compose:") }).contains("Message")
    assertThat(s.events.indexOfFirst { it.startsWith("compose:") }).isLessThan(s.events.indexOfFirst { it.startsWith("plan:") })
  }

  @Test
  fun lAzzeramentoAspettaCheIWidgetSianoVuoti() = runTest {
    val s = setup()
    s.refresher.shelfChanged()
    runCurrent()
    s.events.clear()
    s.refresher.blankAll(WidgetEmpty.NOT_CONFIGURED)
    // `blankAll` e' sospensiva: quando ritorna i disegni sono consegnati e il registro e' vuoto.
    assertThat(s.targets.pushed.takeLast(2)).containsExactly(one, two)
    assertThat(s.events.filter { it.startsWith("plan:") }).isEmpty()
  }

  @Test
  fun unErroreNonEsceMai() = runTest {
    val s = setup(planner = { throw IllegalStateException("lettura") })
    s.refresher.shelfChanged()
    s.refresher.hiddenChanged(setOf(mediaId(0)))
    s.refresher.appBackgrounded()
    advanceUntilIdle()
    // Nessuna eccezione e **nessuna foto** (il piano e' caduto: il registro non si scrive). L'unico disegno e' lo svuotamento per il nascosto: i due widget non sono nel
    // registro, quindi non si sa cosa mostrano e si svuotano (R4).
    assertThat(s.events.filter { it.startsWith("compose:") }.all { it.endsWith(":Message") }).isTrue()
    assertThat(s.events.none { it.startsWith("record:") && it.contains("Photos") }).isTrue()
  }

  // --- Il posto del widget nel processo -------------------------------------------------------------------------------------------------

  @Test
  fun senzaFornitoreLePorteNonCi_Sono() = runTest {
    assertThat(WidgetHost.isInstalled).isFalse()
    assertThat(WidgetHost.services(1_500)).isNull()
    assertThat(WidgetHost.clock()).isNull()
  }

  @Test
  fun ilFornitoreRiceveIlBudgetEUnErroreDiventaNiente() = runTest {
    val budgets = mutableListOf<Long>()
    val services: WidgetServices = FakeServices(ScriptedPlanner(mutableListOf()) { s, size, _ -> photoPlan(s, size, 1) }, InMemoryWidgetLedger(), WidgetThumbnails { _, _ -> thumb() })
    val clock: WidgetClock = FakeWidgetClock()
    var failing = false
    WidgetHost.install(object : WidgetServicesProvider {
      override val clock: WidgetClock = clock

      override suspend fun await(budgetMs: Long): WidgetServices? {
        budgets += budgetMs
        if (failing) throw IllegalStateException("grafo")
        return services
      }
    })
    assertThat(WidgetHost.isInstalled).isTrue()
    assertThat(WidgetHost.clock()).isSameInstanceAs(clock)
    assertThat(WidgetHost.services(1_500)).isSameInstanceAs(services)
    assertThat(WidgetHost.services(-5)).isSameInstanceAs(services)
    assertThat(budgets).containsExactly(1_500L, 0L).inOrder()
    failing = true
    assertThat(WidgetHost.services(1_500)).isNull()
  }
}
