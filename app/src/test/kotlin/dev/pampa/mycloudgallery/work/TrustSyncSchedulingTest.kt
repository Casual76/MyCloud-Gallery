package dev.pampa.mycloudgallery.work

import android.app.Application
import android.content.Context
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.SyncReason
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La pianificazione dei controlli del registro con WorkManager (P9): una volta al giorno, ogni 15 minuti nella
 * finestra d'invito (che si chiude da sola), "presto" all'apertura e all'arrivo a casa; tutto con la sola condizione
 * "c'e' una rete" (casa, VPN e tetti li decide il motore delle regole dentro il lavoro). L'azzeramento cancella tutto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TrustSyncSchedulingTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private var deviceNow = 1_000_000L
  private var monotonic = 0L
  private var nasNow: Long? = 5_000_000L

  private lateinit var window: InviteWindowStore
  private lateinit var scheduler: WorkManagerTrustSyncScheduler
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  @Before
  fun setUp() {
    val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    window = InviteWindowStore(File(temp.root, "invite-window"))
    scheduler = WorkManagerTrustSyncScheduler(context, nasNow = { nasNow }, deviceNow = { deviceNow }, monotonicNow = { monotonic }, window = window)
  }

  private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

  @Test
  fun ilControlloQuotidianoEUnoSoloEChiedeSoloUnaRete() {
    scheduler.ensureDaily()
    scheduler.ensureDaily()
    val daily = infos(WorkManagerTrustSyncScheduler.DAILY_WORK)
    assertThat(daily).hasSize(1)
    assertThat(daily.single().periodicityInfo?.repeatIntervalMillis).isEqualTo(WorkManagerTrustSyncScheduler.DAILY.inWholeMilliseconds)
    val request = daily.single().constraints.requiredNetworkRequest
    assertThat(request).isNotNull()
    assertThat(request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isFalse()
    assertThat(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).isFalse()
    assertThat(daily.single().tags).contains(TrustSyncWorker.TAG)
  }

  @Test
  fun prestoAllAperturaMaMaiInCiclo() {
    scheduler.requestSoon(SyncReason.APP_OPENED)
    val first = infos(WorkManagerTrustSyncScheduler.SOON_WORK).single().id
    // Riaprendo l'app subito dopo non si accoda niente di nuovo (al massimo uno ogni 15 minuti per motivo).
    workManager.cancelUniqueWork(WorkManagerTrustSyncScheduler.SOON_WORK).result.get()
    scheduler.requestSoon(SyncReason.APP_OPENED)
    assertThat(infos(WorkManagerTrustSyncScheduler.SOON_WORK)).isEmpty()
    // Un altro motivo (arrivo a casa) si', e dopo 15 minuti anche l'apertura.
    scheduler.requestSoon(SyncReason.ARRIVED_HOME)
    assertThat(infos(WorkManagerTrustSyncScheduler.SOON_WORK).single().id).isNotEqualTo(first)
    workManager.cancelUniqueWork(WorkManagerTrustSyncScheduler.SOON_WORK).result.get()
    monotonic += WorkManagerTrustSyncScheduler.MIN_SOON_INTERVAL.inWholeMilliseconds
    scheduler.requestSoon(SyncReason.APP_OPENED)
    assertThat(infos(WorkManagerTrustSyncScheduler.SOON_WORK)).hasSize(1)
  }

  @Test
  fun laFinestraDInvitoControllaOgniQuartoDOraESiChiudeDaSola() {
    val day = WorkManagerTrustSyncScheduler.MAX_WINDOW.inWholeMilliseconds
    scheduler.setInviteWindow(untilNasMs = nasNow!! + day)
    assertThat(scheduler.inviteWindowOpen()).isTrue()
    val invite = infos(WorkManagerTrustSyncScheduler.INVITE_WORK).single()
    assertThat(invite.periodicityInfo?.repeatIntervalMillis).isEqualTo(WorkManagerTrustSyncScheduler.INVITE_INTERVAL.inWholeMilliseconds)

    // Ancora dentro: resta.
    deviceNow += day / 2
    scheduler.closeInviteWindowIfExpired()
    assertThat(infos(WorkManagerTrustSyncScheduler.INVITE_WORK)).hasSize(1)
    // Passate le 24 ore (ora del NAS portata all'ora del telefono): il lavoro di 15 minuti si toglie.
    deviceNow += day
    scheduler.closeInviteWindowIfExpired()
    assertThat(scheduler.inviteWindowOpen()).isFalse()
    assertThat(infos(WorkManagerTrustSyncScheduler.INVITE_WORK)).isEmpty()
  }

  @Test
  fun senzaUnaSessioneRecenteLaFinestraDuraLe24OreDellInvito() {
    nasNow = null
    scheduler.setInviteWindow(untilNasMs = Long.MAX_VALUE)
    assertThat(window.until()).isEqualTo(deviceNow + WorkManagerTrustSyncScheduler.MAX_WINDOW.inWholeMilliseconds)
  }

  @Test
  fun lAzzeramentoCancellaTuttiILavoriELaFinestra() = runBlocking {
    scheduler.ensureDaily()
    scheduler.setInviteWindow(untilNasMs = nasNow!! + 1_000L)
    WorkManagerCanceller(context, window).cancelAll()
    assertThat(infos(WorkManagerTrustSyncScheduler.DAILY_WORK)).isEmpty()
    assertThat(infos(WorkManagerTrustSyncScheduler.INVITE_WORK)).isEmpty()
    assertThat(window.until()).isNull()
  }
}
