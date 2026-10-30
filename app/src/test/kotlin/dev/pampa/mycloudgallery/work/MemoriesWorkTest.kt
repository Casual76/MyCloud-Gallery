package dev.pampa.mycloudgallery.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.noop.NoMemories
import dev.pampa.mycloudgallery.core.memories.notify.MemoriesOpened
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyPolicy
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyRun
import dev.pampa.mycloudgallery.core.memories.notify.NotifyAccessState
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeNotifyAccess
import dev.pampa.mycloudgallery.core.memories.testing.FakeNotifyPublisher
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifySettings
import dev.pampa.mycloudgallery.core.memories.testing.RecordingNotifyEvents
import dev.pampa.mycloudgallery.core.memories.testing.RecordingNotifyScheduler
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il lavoro unico delle notifiche dei ricordi su WorkManager (Fase 9, P10; docs/fase9-progetto.md D20; E-NT3, E-NT4, E-NT15): **un lavoro solo**, `memories-notify`, **senza
 * vincoli**, con il ritardo fino all'ora scelta calcolato nel fuso del telefono (ora legale compresa); un nuovo orario **sostituisce** quello in attesa; dall'interno del lavoro
 * si accoda a quello che gira; ricordi non montati = niente da fare. Nessun allarme esatto: il lavoro e' l'unico meccanismo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MemoriesWorkTest {

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  @Before
  fun setUp() {
    // Un lavoro con ritardo non parte: resta in coda e si guarda (niente Hilt qui).
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  }

  @After
  fun tearDown() {
    MemoriesWorkScheduling.leaveWorker()
    workManager.cancelAllWork().result.get()
  }

  private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(MemoriesWorkScheduling.NOTIFY_WORK).get().filter { !it.state.isFinished }

  @Test
  fun unLavoroUnicoSenzaVincoliConIlRitardoGiusto() {
    MemoriesWorkScheduling.schedule(context, TimeUnit.HOURS.toMillis(3))
    val work = infos().single()
    assertThat(work.state).isEqualTo(WorkInfo.State.ENQUEUED)
    assertThat(work.constraints).isEqualTo(Constraints.NONE)
    assertThat(work.tags).contains(MemoriesWorkScheduling.TAG)
    // Un secondo in piu' del ritardo: il lavoro non parte mai prima dell'orario.
    assertThat(work.initialDelayMillis).isEqualTo(TimeUnit.HOURS.toMillis(3) + 1_000)
    assertThat(MemoriesWorkScheduling.NOTIFY_WORK).isEqualTo("memories-notify")
  }

  /** Un nuovo orario (impostazioni, avvio) sostituisce quello in attesa: sempre un lavoro solo, con il ritardo nuovo. */
  @Test
  fun unNuovoOrarioSostituisceQuelloInAttesa() {
    MemoriesWorkScheduling.schedule(context, TimeUnit.HOURS.toMillis(3))
    val first = infos().single().id
    MemoriesWorkScheduling.schedule(context, TimeUnit.HOURS.toMillis(5))
    val work = infos().single()
    assertThat(work.id).isNotEqualTo(first)
    assertThat(work.initialDelayMillis).isEqualTo(TimeUnit.HOURS.toMillis(5) + 1_000)
    MemoriesWorkScheduling.cancel(context)
    assertThat(infos()).isEmpty()
  }

  /** Dall'interno del lavoro il lavoro dopo si accoda a quello che gira (non lo cancella mentre finisce). */
  @Test
  fun dalLavoroStessoSiAccodaSenzaCancellarsi() {
    MemoriesWorkScheduling.schedule(context, TimeUnit.HOURS.toMillis(1))
    MemoriesWorkScheduling.enterWorker()
    MemoriesWorkScheduling.schedule(context, TimeUnit.HOURS.toMillis(24))
    MemoriesWorkScheduling.leaveWorker()
    assertThat(infos().map { it.state }).containsExactly(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)
  }

  /** Il ritardo vero: dall'ora locale di adesso all'ora locale scelta nel fuso del telefono, con le lacune e le ore doppie dell'ora legale (E-NT4). */
  @Test
  fun ilRitardoSegueIlFusoELOraLegale() {
    val rome = ZoneId.of("Europe/Rome")
    val scheduler = WorkManagerNotifyScheduler(context) { rome }
    val policy = MemoryNotifyPolicy()
    val prefs = NotifyPrefs()
    // 28 marzo 19:05 CET -> 29 marzo 19:00 CEST: 22h55m (la notte salta un'ora).
    val now = LocalDateTime.of(2026, 3, 28, 19, 5)
    scheduler.scheduleAt(now, policy.nextSlot(now, prefs)!!)
    assertThat(infos().single().initialDelayMillis).isEqualTo(TimeUnit.MINUTES.toMillis(22 * 60 + 55) + 1_000)
    // Il giorno normale: 23h55m.
    val normal = LocalDateTime.of(2026, 5, 10, 19, 5)
    scheduler.scheduleAt(normal, policy.nextSlot(normal, prefs)!!)
    assertThat(infos().single().initialDelayMillis).isEqualTo(TimeUnit.MINUTES.toMillis(23 * 60 + 55) + 1_000)
    scheduler.cancel()
    assertThat(infos()).isEmpty()
  }

  // --- Il lavoro ---------------------------------------------------------------------------------------------------------------------

  private fun worker(slot: MemoryNotifySlot): MemoryNotifyWorker = TestListenableWorkerBuilder<MemoryNotifyWorker>(context)
    .setWorkerFactory(object : WorkerFactory() {
      override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
        MemoryNotifyWorker(appContext, workerParameters, slot)
    })
    .build()

  /** Ricordi non montati (P13 non ha ancora legato il giorno di una notifica): il lavoro finisce senza fare niente e senza ripianificare. */
  @Test
  fun senzaRicordiMontatiNonFaNiente() = runBlocking<Unit> {
    val result = worker(MemoryNotifySlot()).doWork()
    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(infos()).isEmpty()
  }

  /** Con il giorno di una notifica montato: una corsa, l'esito sempre "riuscito", e il lavoro di domani e' rifatto (qui sul programmatore finto). */
  @Test
  fun conIRicordiMontatiUnaCorsaERipianifica() = runBlocking<Unit> {
    val scheduler = RecordingNotifyScheduler()
    val today = LocalDate.of(2026, 11, 3)
    val slot = MemoryNotifySlot().apply {
      runner = MemoryNotifyRun(
        MemoryNotifyPolicy(), InMemoryNotifySettings(NotifyPrefs()), InMemoryNotifyLedgerStore(), NoMemories, visibility(), MemoriesOpened { false },
        FakeNotifyAccess(NotifyAccessState.GRANTED), FakeNotifyPublisher(), FakeMemoryClock(today, LocalTime.of(19, 0)), scheduler, RecordingNotifyEvents(),
      )
    }
    assertThat(worker(slot).doWork()).isEqualTo(ListenableWorker.Result.success())
    assertThat(scheduler.lastNext).isEqualTo(today.plusDays(1).atTime(19, 0))
    // Il lavoro che si ripianifica dall'interno non lascia il flag acceso: dopo, un nuovo orario sostituisce di nuovo.
    MemoriesWorkScheduling.schedule(context, TimeUnit.HOURS.toMillis(1))
    MemoriesWorkScheduling.schedule(context, TimeUnit.HOURS.toMillis(2))
    assertThat(infos()).hasSize(1)
  }

  private fun visibility(): MemoryVisibility {
    val fake = FakeVisibility()
    return object : MemoryVisibility {
      override val snapshot = fake.snapshot

      override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = fake.visible(ids)

      override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = fake.hiddenAmong(ids)
    }
  }
}
