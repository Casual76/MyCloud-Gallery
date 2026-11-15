package dev.pampa.mycloudgallery.work

import android.app.Application
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.run.NextStage
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import dev.pampa.mycloudgallery.core.rules.run.YieldReason
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Il ritardo termico di prima non deve assorbire le anteprime richieste da una sincronizzazione appena conclusa. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PreparationNewsSchedulingTest {
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val manager: WorkManager get() = WorkManager.getInstance(context)
  private val stats = StageStats(batches = 0, bytes = 0, roundBytes = 0, elapsed = Duration.ZERO, resumed = false)

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  }

  private fun pending(name: String): List<WorkInfo> = manager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

  @Test
  fun completedSyncRequestsPreparationWithoutMovingTheOldThermalWait() {
    val waitName = NasWorkScheduling.waitName(NasWorkScheduling.PREP_WAIT_WORK, WaitReason.Thermal(ThermalStatus.SEVERE))
    val old = OneTimeWorkRequestBuilder<PreparationWorker>().setInitialDelay(40, TimeUnit.MINUTES).build()
    manager.enqueueUniqueWork(waitName, ExistingWorkPolicy.KEEP, old).result.get()

    NasWorkScheduling.prepareAfterSync(context, DiagJob.INDEX_SYNC, StageOutcome.Finished(stats))

    val fresh = pending(NasWorkScheduling.PREP_NOW_WORK).single()
    assertThat(fresh.initialDelayMillis).isEqualTo(0)
    assertThat(fresh.tags).contains(PreparationWorker::class.java.name)
    assertThat(fresh.tags).contains(NasWorkScheduling.TAG)
    val network = checkNotNull(fresh.constraints.requiredNetworkRequest)
    assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isFalse()
    val preserved = pending(waitName).single()
    assertThat(preserved.id).isEqualTo(old.id)
    assertThat(preserved.initialDelayMillis).isEqualTo(TimeUnit.MINUTES.toMillis(40))
  }

  @Test
  fun repeatedSyncsKeepTheSamePendingPreparation() {
    NasWorkScheduling.prepareAfterSync(context, DiagJob.INDEX_SYNC, StageOutcome.Finished(stats))
    val first = pending(NasWorkScheduling.PREP_NOW_WORK).single().id
    repeat(3) { NasWorkScheduling.prepareAfterSync(context, DiagJob.INDEX_SYNC, StageOutcome.Finished(stats)) }
    assertThat(pending(NasWorkScheduling.PREP_NOW_WORK).single().id).isEqualTo(first)
  }

  @Test
  fun preparationNeverSchedulesItselfAndIncompleteSyncDoesNotStartIt() {
    NasWorkScheduling.prepareAfterSync(context, DiagJob.PREPARATION, StageOutcome.Finished(stats))
    NasWorkScheduling.prepareAfterSync(context, DiagJob.NAS_SCAN, StageOutcome.Finished(stats))
    NasWorkScheduling.prepareAfterSync(context, DiagJob.INDEX_SYNC, null)
    NasWorkScheduling.prepareAfterSync(context, DiagJob.INDEX_SYNC, StageOutcome.Waiting(WaitReason.Thermal(ThermalStatus.SEVERE), emptyList(), stats))
    NasWorkScheduling.prepareAfterSync(context, DiagJob.INDEX_SYNC, StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.WHEN_ALLOWED, stats))
    assertThat(pending(NasWorkScheduling.PREP_NOW_WORK)).isEmpty()
  }
}
