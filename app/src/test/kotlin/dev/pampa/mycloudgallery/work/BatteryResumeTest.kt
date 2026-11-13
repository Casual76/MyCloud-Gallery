package dev.pampa.mycloudgallery.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pacchetto B, BS-2 (critico Sol): quando una scelta toglie il blocco della batteria, il lavoro **gia' accodato con il vincolo della carica** non aspetta un evento esterno. Prova deterministica
 * dello scheduler con il WorkManager di prova (esecutore sincrono): backup e analisi, abbassando il livello e spegnendo un gruppo; piu' la funzione pura che decide (mai una corsa attiva interrotta).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BatteryResumeTest {

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val manager: WorkManager get() = WorkManager.getInstance(context)
  private val now = LocalDateTime.of(2026, 11, 13, 12, 0)
  private val low = WaitReason.BatteryLow(55, 60)

  /** Un lavoro che finisce subito: si vede dallo stato se la richiesta era senza vincoli. */
  private class Done(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
  }

  @Before
  fun setUp() {
    val factory = object : WorkerFactory() {
      override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker = Done(appContext, workerParameters)
    }
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
  }

  private fun states(name: String): List<WorkInfo.State> = manager.getWorkInfosForUniqueWork(name).get().map { it.state }

  private fun ids(name: String) = manager.getWorkInfosForUniqueWork(name).get().map { it.id }

  // --- Lo scheduler vero -----------------------------------------------------------------------------------------

  @Test
  fun analisi_abbassandoIlLivello_ilLavoroInAttesaDellaCaricaViene_sostituitoESiAvvia() = runBlocking<Unit> {
    AiWorkScheduling.afterWait(context, user = false, wait = low, now = now)
    // Fermo a 55 con soglia 60: aspetta la carica, e senza cavo non parte.
    assertThat(states(AiWorkScheduling.COMPUTE_NEXT_WORK)).containsExactly(WorkInfo.State.ENQUEUED)
    val old = ids(AiWorkScheduling.COMPUTE_NEXT_WORK).single()

    // L'utente abbassa il livello a 50: il blocco non vale piu'.
    val relaxed = BatteryResume.relaxed(RuleSettings(batteryThresholdPercent = 60), RuleSettings(batteryThresholdPercent = 50))
    assertThat(relaxed).contains(BackgroundActivity.AI_COMPUTE)
    BatteryResume.resume(context, relaxed)

    // Il vecchio lavoro (con la carica) e' tolto; la nuova corsa non ha il vincolo e parte (finisce: senza cavo non sarebbe mai partita).
    assertThat(ids(AiWorkScheduling.COMPUTE_NEXT_WORK)).doesNotContain(old)
    assertThat(states(AiWorkScheduling.COMPUTE_NEXT_WORK)).containsExactly(WorkInfo.State.SUCCEEDED)
  }

  @Test
  fun analisi_spegnendoIlGruppo_ilLavoroInAttesaDellaCaricaViene_sostituito() = runBlocking<Unit> {
    AiWorkScheduling.afterWait(context, user = false, wait = low, now = now)
    FacesWorkScheduling.afterWait(context, user = false, wait = low)
    val oldAi = ids(AiWorkScheduling.COMPUTE_NEXT_WORK).single()
    val oldFaces = ids(FacesWorkScheduling.COMPUTE_NEXT_WORK).single()

    val relaxed = BatteryResume.relaxed(RuleSettings(), RuleSettings(batteryThresholdForAnalysis = false))
    BatteryResume.resume(context, relaxed)

    assertThat(ids(AiWorkScheduling.COMPUTE_NEXT_WORK)).doesNotContain(oldAi)
    assertThat(ids(FacesWorkScheduling.COMPUTE_NEXT_WORK)).doesNotContain(oldFaces)
    assertThat(states(AiWorkScheduling.COMPUTE_NEXT_WORK)).containsExactly(WorkInfo.State.SUCCEEDED)
    assertThat(states(FacesWorkScheduling.COMPUTE_NEXT_WORK)).containsExactly(WorkInfo.State.SUCCEEDED)
  }

  @Test
  fun backup_abbassandoIlLivelloESpegnendoIlGruppo_ilLavoroInAttesaDellaCaricaViene_sostituito() {
    for (change in listOf(
      RuleSettings(batteryThresholdPercent = 60) to RuleSettings(batteryThresholdPercent = 50),
      RuleSettings() to RuleSettings(batteryThresholdForBackup = false),
    )) {
      WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
      BackupWorkScheduling.afterWait(context, BackupWorkScheduling.Kind.PHOTOS, low, now = now)
      val name = BackupWorkScheduling.Kind.PHOTOS.workName
      assertThat(states(name)).containsExactly(WorkInfo.State.ENQUEUED)
      val old = ids(name).single()

      runBlocking { BatteryResume.resume(context, BatteryResume.relaxed(change.first, change.second)) }

      // Il vecchio (con la carica) e' tolto; al suo posto c'e' un lavoro nuovo (la sua richiesta senza carica e' provata in `leRichiesteDiRipresa_...`).
      assertThat(ids(name)).hasSize(1)
      assertThat(ids(name)).doesNotContain(old)
    }
  }

  @Test
  fun leRichiesteDiRipresa_nonHannoIlVincoloDellaCarica() {
    val relaxed = BackgroundActivity.entries.toSet()
    val requests = BatteryResume.targets(relaxed).map { it.name to it.request() }

    assertThat(requests.map { it.first }).containsExactly(
      BackupWorkScheduling.Kind.PHOTOS.workName,
      BackupWorkScheduling.Kind.VIDEOS.workName,
      AiWorkScheduling.COMPUTE_NEXT_WORK,
      FacesWorkScheduling.COMPUTE_NEXT_WORK,
    )
    for ((name, request) in requests) {
      assertThat(request.workSpec.constraints.requiresCharging()).isFalse()
      assertThat(request.workSpec.initialDelay).isEqualTo(0L)
      assertThat(name).isNotEmpty()
    }
  }

  @Test
  fun senzaNienteInAttesa_laRipresaNonCreaNiente() = runBlocking<Unit> {
    BatteryResume.resume(context, BackgroundActivity.entries.toSet())

    assertThat(states(AiWorkScheduling.COMPUTE_NEXT_WORK)).isEmpty()
    assertThat(states(BackupWorkScheduling.Kind.PHOTOS.workName)).isEmpty()
  }

  // --- La funzione che decide ---------------------------------------------------------------------------------

  @Test
  fun unaCorsaAttiva_nonSiInterrompeMai() {
    assertThat(BatteryResume.plan(listOf(WorkInfo.State.RUNNING))).isEqualTo(BatteryResume.Plan.Leave)
    // Anche con un lavoro dopo in attesa dietro di lei: la corsa che gira rivaluta da sola le regole a ogni lotto.
    assertThat(BatteryResume.plan(listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED))).isEqualTo(BatteryResume.Plan.Leave)
  }

  @Test
  fun unLavoroInAttesa_siSostituisce_eNienteInAttesa_nonSiFaNiente() {
    assertThat(BatteryResume.plan(listOf(WorkInfo.State.ENQUEUED))).isEqualTo(BatteryResume.Plan.Replace)
    assertThat(BatteryResume.plan(listOf(WorkInfo.State.BLOCKED))).isEqualTo(BatteryResume.Plan.Replace)
    assertThat(BatteryResume.plan(emptyList())).isEqualTo(BatteryResume.Plan.Leave)
    assertThat(BatteryResume.plan(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED, WorkInfo.State.FAILED))).isEqualTo(BatteryResume.Plan.Leave)
  }

  @Test
  fun cosaLiberaUnCambio() {
    // Abbassare il livello libera tutte le attivita' a cui la soglia valeva.
    assertThat(BatteryResume.relaxed(RuleSettings(batteryThresholdPercent = 60), RuleSettings(batteryThresholdPercent = 50))).containsExactlyElementsIn(BackgroundActivity.entries)
    // Alzarlo, o lasciarlo com'e', non libera niente.
    assertThat(BatteryResume.relaxed(RuleSettings(batteryThresholdPercent = 50), RuleSettings(batteryThresholdPercent = 60))).isEmpty()
    assertThat(BatteryResume.relaxed(RuleSettings(), RuleSettings())).isEmpty()
    // Spegnere il backup libera il backup e, con la regola di BS-1, indice e preparazione.
    assertThat(BatteryResume.relaxed(RuleSettings(), RuleSettings(batteryThresholdForBackup = false)))
      .containsExactly(BackgroundActivity.PHOTO_BACKUP, BackgroundActivity.VIDEO_BACKUP, BackgroundActivity.INDEX_SYNC, BackgroundActivity.PREPARATION)
    // Accendere un interruttore ferma di piu': niente da riprendere.
    assertThat(BatteryResume.relaxed(RuleSettings(batteryThresholdForBackup = false), RuleSettings())).isEmpty()
  }
}
