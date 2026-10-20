package dev.pampa.mycloudgallery.work

import android.app.Application
import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Correzioni 5B (LS-2): la verifica di "Libera spazio" e' un lavoro avviato dall'utente con un id suo (fuori da quelli di WorkManager e diverso
 * dagli altri lavori del backup e del NAS), con la rete del NAS (nessuna richiesta di internet), e chiederlo di nuovo non lo sostituisce (un lavoro
 * in corso verrebbe fermato). L'azzeramento lo toglie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FreeSpaceJobTest {

  private val context: Application = ApplicationProvider.getApplicationContext()

  @Test
  fun laVerificaDiLiberaSpazioEUnLavoroSuo_eNonSiSostituisce() {
    val id = JobSchedulerBackupJobs.JOB_FREE_SPACE
    assertThat(id in JobSchedulerNasOpsJobs.WORK_MANAGER_JOB_IDS).isFalse()
    assertThat(setOf(id, JobSchedulerBackupJobs.JOB_USER, JobSchedulerBackupJobs.JOB_INCOMING, JobSchedulerNasOpsJobs.JOB_USER, JobSchedulerNasOpsJobs.JOB_RESUME))
      .hasSize(5)

    val jobs = JobSchedulerBackupJobs(context)
    val scheduler = context.getSystemService(JobScheduler::class.java)
    assertThat(jobs.freeSpaceWork()).isTrue()
    val first = scheduler.getPendingJob(id)
    assertThat(first).isNotNull()
    assertThat(first!!.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false).isFalse()
    // Chiesto di nuovo (la pagina riaperta): resta quello di prima.
    assertThat(jobs.freeSpaceWork()).isTrue()
    assertThat(scheduler.allPendingJobs.count { it.id == id }).isEqualTo(1)

    jobs.cancelAll()
    assertThat(scheduler.getPendingJob(id)).isNull()
  }
}
