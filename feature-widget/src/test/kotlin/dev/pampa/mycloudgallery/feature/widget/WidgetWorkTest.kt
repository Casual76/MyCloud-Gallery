package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import android.appwidget.AppWidgetManager
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetEngine
import dev.pampa.mycloudgallery.feature.widget.work.PeriodicOutcome
import dev.pampa.mycloudgallery.feature.widget.work.WidgetPeriodicRun
import dev.pampa.mycloudgallery.feature.widget.work.WidgetRefreshWorker
import dev.pampa.mycloudgallery.feature.widget.work.WidgetWork
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Il lavoro periodico `widget-refresh` (Fase 10, P3, D6, Q2, E-P10): **unico**, ogni 3 ore di base con flessibilita' di 30 minuti e il vincolo "batteria non scarica", programmato
 * solo se c'e' un widget e tolto quando non ce n'e' piu'; la tappa **non ridisegna** nelle ore di quiete (23-7), con il risparmio energetico, senza widget o senza porte, e in quei casi
 * **non monta il grafo**.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetWorkTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
  }

  private fun infos(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WidgetScheduleRules.WORK_NAME).get()

  // --- La programmazione ---------------------------------------------------------------------------------------------------------------

  @Test
  fun ilLavoroEUnicoCircaOgniTreOreMaiDiNotteConIlVincoloDellaBatteria() {
    val at = LocalDateTime.of(2026, 10, 31, 12, 0)
    WidgetWork.schedule(context, WidgetPeriod.HOURS_3, at, replace = false)
    WidgetWork.schedule(context, WidgetPeriod.HOURS_3, at, replace = false)
    val work = infos()
    assertThat(work).hasSize(1)
    assertThat(work.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    val plan = WidgetWork.planFor(WidgetPeriod.HOURS_3, at)
    assertThat(plan.intervalMinutes).isEqualTo(180)
    assertThat(plan.flexMinutes).isEqualTo(30)
    val request = WidgetWork.requestFor(plan)
    assertThat(request.workSpec.isPeriodic).isTrue()
    assertThat(request.workSpec.intervalDuration).isEqualTo(TimeUnit.MINUTES.toMillis(180))
    assertThat(request.workSpec.flexDuration).isEqualTo(TimeUnit.MINUTES.toMillis(30))
    assertThat(request.workSpec.constraints.requiresBatteryNotLow()).isTrue()
    // Nessuna rete: il lavoro non chiede nemmeno una connessione.
    assertThat(request.workSpec.constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
    assertThat(request.workSpec.constraints.requiresCharging()).isFalse()
  }

  @Test
  fun unWidgetMessoDiSeraPartePrimaDelMattino() {
    // Le 22:00 + 3 ore = le 1:00: dentro la quiete, il primo giro slitta alle 7:00 (9 ore dopo).
    val evening = WidgetWork.planFor(WidgetPeriod.HOURS_3, LocalDateTime.of(2026, 10, 31, 22, 0))
    assertThat(evening.initialDelayMinutes).isEqualTo(9 * 60)
    // Di giorno il primo giro e' fra una frequenza.
    assertThat(WidgetWork.planFor(WidgetPeriod.HOURS_3, LocalDateTime.of(2026, 10, 31, 12, 0)).initialDelayMinutes).isEqualTo(180)
    // Senza sapere l'ora (processo freddo, nessun fornitore): fra un periodo intero.
    assertThat(WidgetWork.planFor(WidgetPeriod.HOURS_6, null).initialDelayMinutes).isEqualTo(360)
  }

  @Test
  fun cambiareFrequenzaSostituisceIlLavoroEUnWidgetNuovoNonLoSposta() {
    val at = LocalDateTime.of(2026, 10, 31, 12, 0)
    WidgetWork.schedule(context, WidgetPeriod.HOURS_3, at, replace = false)
    val first = infos().single().id
    // Un altro widget che nasce (KEEP): lo stesso lavoro.
    WidgetWork.schedule(context, WidgetPeriod.HOURS_3, at, replace = false)
    assertThat(infos().single().id).isEqualTo(first)
    // La scelta nuova nelle Impostazioni (UPDATE): un lavoro solo, con la frequenza nuova.
    WidgetWork.schedule(context, WidgetPeriod.DAILY, at, replace = true)
    assertThat(infos()).hasSize(1)
    assertThat(WidgetWork.planFor(WidgetPeriod.DAILY, at).intervalMinutes).isEqualTo(24 * 60)
  }

  @Test
  fun senzaWidgetIlLavoroSiToglie() {
    WidgetWork.schedule(context, WidgetPeriod.HOURS_3, LocalDateTime.of(2026, 10, 31, 12, 0), replace = false)
    assertThat(infos().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    WidgetWork.cancel(context)
    assertThat(infos().all { it.state == WorkInfo.State.CANCELLED }).isTrue()
  }

  // --- La tappa ------------------------------------------------------------------------------------------------------------------------

  private class Run(val engineCalls: MutableList<String>, val cancelled: IntArray, val run: WidgetPeriodicRun)

  private fun run(widgets: Int, clock: WidgetClock?, powerSaver: Boolean = false): Run {
    val events = mutableListOf<String>()
    val dims = mutableMapOf<WidgetSlot, Pair<Int, Int>>()
    repeat(widgets) { dims[WidgetSlot(it + 1)] = 160 to 160 }
    val targets = RecordingTargets(dims, events)
    val services = FakeServices(ScriptedPlanner(events) { s, size, _ -> photoPlan(s, size, 1) }, InMemoryWidgetLedger(), WidgetThumbnails { _, _ -> thumb() })
    val engine = WidgetEngine({ services }, targets, RecordingComposer(context, events), { context.resources })
    val cancelled = IntArray(1)
    return Run(events, cancelled, WidgetPeriodicRun(targets, { engine }, { clock }, { powerSaver }, { cancelled[0]++ }))
  }

  @Test
  fun dentroLeOreDiQuieteEsceSubitoSenzaMontareNiente() = runBlocking {
    for ((hour, expected) in listOf(23 to PeriodicOutcome.QUIET, 0 to PeriodicOutcome.QUIET, 3 to PeriodicOutcome.QUIET, 6 to PeriodicOutcome.QUIET, 7 to PeriodicOutcome.RAN, 12 to PeriodicOutcome.RAN, 22 to PeriodicOutcome.RAN)) {
      val r = run(widgets = 2, clock = FakeWidgetClock(now = LocalDateTime.of(2026, 10, 31, hour, 30)))
      assertThat(r.run.run()).isEqualTo(expected)
      if (expected == PeriodicOutcome.QUIET) assertThat(r.engineCalls).isEmpty() else assertThat(r.engineCalls.count { it.startsWith("push:") }).isEqualTo(2)
    }
  }

  @Test
  fun conIlRisparmioEnergeticoTace() = runBlocking {
    val r = run(widgets = 1, clock = FakeWidgetClock(), powerSaver = true)
    assertThat(r.run.run()).isEqualTo(PeriodicOutcome.POWER_SAVER)
    assertThat(r.engineCalls).isEmpty()
  }

  @Test
  fun senzaWidgetAnnullaIlLavoroQualunqueSiaL_Ora() = runBlocking {
    val r = run(widgets = 0, clock = FakeWidgetClock(now = LocalDateTime.of(2026, 10, 31, 3, 0)))
    assertThat(r.run.run()).isEqualTo(PeriodicOutcome.NO_WIDGETS)
    assertThat(r.cancelled[0]).isEqualTo(1)
    assertThat(r.engineCalls).isEmpty()
  }

  /** Il grafo non e' stato legato (P13 non c'e'): nessun orologio, nessun ridisegno, e il lavoro non si annulla (i widget ci sono). */
  @Test
  fun senzaIlFornitoreNonDisegnaNiente() = runBlocking {
    val r = run(widgets = 2, clock = null)
    assertThat(r.run.run()).isEqualTo(PeriodicOutcome.NOT_MOUNTED)
    assertThat(r.cancelled[0]).isEqualTo(0)
    assertThat(r.engineCalls).isEmpty()
  }

  /** WG-5: la tappa del lavoro periodico aspetta le porte piu' a lungo del ricevitore (10 s invece di 1,5), una volta sola per il giro. */
  @Test
  fun laTappaAspettaLePorteIlTempoDelLavoroPeriodico() = runBlocking<Unit> {
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(WidgetSlot(1) to (160 to 160), WidgetSlot(2) to (160 to 160)), events)
    val services = FakeServices(ScriptedPlanner(events) { s, size, _ -> photoPlan(s, size, 1) }, InMemoryWidgetLedger(), WidgetThumbnails { _, _ -> thumb() })
    val budgets = mutableListOf<Long>()
    val engine = WidgetEngine({ budget -> budgets += budget; services }, targets, RecordingComposer(context, events), { context.resources })
    val run = WidgetPeriodicRun(targets, { engine }, { FakeWidgetClock() }, { false }, {})
    assertThat(run.run()).isEqualTo(PeriodicOutcome.RAN)
    assertThat(budgets.first()).isEqualTo(WidgetRules.PERIODIC_COLD_START_BUDGET_MS)
    assertThat(budgets.count { it > WidgetRules.COLD_START_BUDGET_MS }).isEqualTo(1)
    assertThat(targets.pushed).containsExactly(WidgetSlot(1), WidgetSlot(2))
  }

  // --- Il lavoro vero (WorkManager di prova) -------------------------------------------------------------------------------------------

  @Test
  fun ilLavoroVeroSenzaWidgetFinisceBeneEAnnullaSeStesso() = runBlocking<Unit> {
    WidgetWork.schedule(context, WidgetPeriod.HOURS_3, LocalDateTime.of(2026, 10, 31, 12, 0), replace = false)
    val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context).build()
    assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    // Nessun widget nostro sulla Home: il lavoro periodico si toglie (D6).
    assertThat(infos().all { it.state == WorkInfo.State.CANCELLED }).isTrue()
  }

  /** Un widget c'e' ma il grafo no (nessun fornitore): la tappa finisce bene, non ridisegna e **non toglie** il lavoro (i widget ci sono). */
  @Test
  fun ilLavoroVeroSenzaIlGrafoNonDisegnaENonSiToglie() = runBlocking<Unit> {
    shadowOf(AppWidgetManager.getInstance(context)).createWidget(GalleryWidgetReceiver::class.java, R.layout.widget_initial)
    WidgetWork.schedule(context, WidgetPeriod.HOURS_3, LocalDateTime.of(2026, 10, 31, 12, 0), replace = false)
    val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context).build()
    assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    assertThat(infos().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
  }
}
