package dev.pampa.mycloudgallery.work

import android.app.Application
import android.app.job.JobInfo
import android.content.ComponentName
import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.rules.WaitReason
import java.io.File
import java.time.LocalDateTime
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Con un router senza internet i lavori non giravano (verifica funzionale, RE-11): `NetworkType.CONNECTED` di WorkManager e
 * `NETWORK_TYPE_ANY` di JobScheduler chiedono una rete con internet **validata**, e il Wi-Fi di casa senza WAN non lo e'. Il NAS e' in LAN
 * (docs/rete-casa-vpn.md §5): i vincoli di tutti i lavori chiedono solo una rete, non internet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LanConstraintsTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  }

  /** La richiesta di rete non pretende internet ne' la validazione: una rete locale senza WAN la soddisfa. */
  private fun assertNoInternetRequired(request: NetworkRequest?, what: String) {
    assertWithMessage("$what: nessuna richiesta di rete").that(request).isNotNull()
    assertWithMessage("$what: non deve chiedere INTERNET").that(request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isFalse()
    assertWithMessage("$what: non deve chiedere VALIDATED").that(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).isFalse()
    // Ma vuole un trasporto vero: Wi-Fi, Ethernet o cellulare (il Wi-Fi di casa, o i dati mobili con la VPN).
    assertWithMessage("$what: Wi-Fi").that(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).isTrue()
    assertWithMessage("$what: Ethernet").that(request.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).isTrue()
    assertWithMessage("$what: cellulare").that(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isTrue()
  }

  /**
   * Il difetto, da Android stesso: `NETWORK_TYPE_ANY` di JobScheduler (e `NetworkType.CONNECTED` di WorkManager, che WorkManager 2.11 passa a
   * JobScheduler proprio come `NETWORK_TYPE_ANY`) e' una richiesta con `INTERNET` e `VALIDATED`, che un Wi-Fi di casa senza collegamento a
   * internet non soddisfa.
   */
  @Test
  fun ilVincoloDiPrima_chiedeAndroidUnaReteConInternetValidato() {
    val job = JobInfo.Builder(1, ComponentName(context, JobSchedulerBackupJobs::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
    val request = job.requiredNetwork

    assertThat(request).isNotNull()
    assertThat(request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isTrue()
    assertThat(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).isTrue()
  }

  @Test
  fun laRichiestaDiReteNonPretendeInternet() {
    assertNoInternetRequired(LanConstraints.request(), "LanConstraints")
    assertNoInternetRequired(LanConstraints.anyNetwork().requiredNetworkRequest, "anyNetwork")
  }

  @Test
  fun iLavoriDelBackupConservanoLeAltreCondizioniEPerdonoSoloInternet() {
    // La carica si dice a WorkManager (che avvisa quando cambia); le altre attese non hanno un vincolo. FAB-3: il vincolo "batteria non scarica" non
    // c'e' piu' (non misurava ne' la soglia delle regole, 20% contro il 15% di Android, ne' il risparmio energetico: era sempre gia' soddisfatto).
    val now = LocalDateTime.of(2026, 10, 16, 12, 0)
    fun constraintsAfter(wait: WaitReason) = BackupWorkScheduling.constraintsFor(RuleWaits.followUpOf(wait, now))
    val plain = BackupWorkScheduling.constraintsFor(null)
    assertNoInternetRequired(plain.requiredNetworkRequest, "backup")
    assertThat(plain.requiresCharging()).isFalse()
    assertThat(constraintsAfter(WaitReason.NeedsPower).requiresCharging()).isTrue()
    assertThat(constraintsAfter(WaitReason.BatteryLow(17, 20)).requiresCharging()).isTrue()
    assertThat(constraintsAfter(WaitReason.BatteryLow(17, 20)).requiresBatteryNotLow()).isFalse()
    assertThat(constraintsAfter(WaitReason.PowerSaver).requiresBatteryNotLow()).isFalse()
    assertThat(constraintsAfter(WaitReason.PowerSaver).requiresCharging()).isFalse()
    assertThat(constraintsAfter(WaitReason.Offline).requiresCharging()).isFalse()
    assertNoInternetRequired(constraintsAfter(WaitReason.NeedsPower).requiredNetworkRequest, "backup, carica")
    assertNoInternetRequired(constraintsAfter(WaitReason.PowerSaver).requiredNetworkRequest, "backup, risparmio energetico")
  }

  /** Tutti i lavori periodici veri, come li pianifica l'app: nessuno chiede internet. */
  @Test
  fun tuttiILavoriPeriodiciSonoPianificatiSenzaChiedereInternet() {
    NasWorkScheduling.ensure(context)
    BackupWorkScheduling.ensure(context)
    WorkManagerTrustSyncScheduler(
      context, nasNow = { null }, deviceNow = { 0L }, monotonicNow = { 0L }, window = InviteWindowStore(File(temp.root, "invite-window")),
    ).ensureDaily()

    val periodic = listOf(
      NasWorkScheduling.SCAN_WORK, NasWorkScheduling.SYNC_WORK, NasWorkScheduling.PREP_WORK, NasWorkScheduling.HQ_WORK,
      NasWorkScheduling.TRASH_PURGE_WORK, NasWorkScheduling.MAINTENANCE_WORK, NasWorkScheduling.PERSONAL_WORK,
      BackupWorkScheduling.PHOTOS_PERIODIC_WORK, BackupWorkScheduling.VIDEOS_PERIODIC_WORK, BackupWorkScheduling.SAFETY_PERIODIC_WORK,
      WorkManagerTrustSyncScheduler.DAILY_WORK,
    )
    for (name in periodic) {
      val infos = workManager.getWorkInfosForUniqueWork(name).get().filter { it.state == WorkInfo.State.ENQUEUED }
      assertWithMessage("lavoro $name pianificato").that(infos).hasSize(1)
      assertNoInternetRequired(infos.single().constraints.requiredNetworkRequest, name)
    }
  }

  @Test
  fun laVerificaDelNasEIlControlloDelRegistroImmediatiNonChiedonoInternet() {
    NasWorkScheduling.scanNow(context)
    val scheduler = WorkManagerTrustSyncScheduler(
      context, nasNow = { null }, deviceNow = { 0L }, monotonicNow = { 0L }, window = InviteWindowStore(File(temp.root, "invite-window")),
    )
    scheduler.requestSoon(dev.pampa.mycloudgallery.core.family.contract.SyncReason.APP_OPENED)
    scheduler.setInviteWindow(untilNasMs = 1L)

    val names = listOf(NasWorkScheduling.SCAN_NOW_WORK, WorkManagerTrustSyncScheduler.SOON_WORK, WorkManagerTrustSyncScheduler.INVITE_WORK)
    for (name in names) {
      val infos = workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
      assertWithMessage("lavoro $name").that(infos).isNotEmpty()
      infos.forEach { assertNoInternetRequired(it.constraints.requiredNetworkRequest, name) }
    }
  }

  private fun prerequisiteLines(): List<String> =
    ShadowLog.getLogsForTag("WM-EnqueueRunnable").filter { it.msg.contains("doesn't exist; not enqueuing") }.map { it.msg }

  /**
   * "WM-EnqueueRunnable: Prerequisite ... doesn't exist; not enqueuing" (prova sul campo di Fase 10, 14 righe in 85 minuti): `scanNow` e' l'**unica catena** dell'app (verifica del NAS, poi
   * sincronizzazione dell'indice, poi dati personali: `beginUniqueWork(KEEP).then().then()`). Con la catena gia' in coda (l'app si apre o torna in primo piano, un'operazione sui file finisce) `KEEP` non
   * inserisce la verifica nuova, e WorkManager scrive un errore per ognuno dei due lavori che le vanno dietro e che non ha dove agganciare. Nessuna catena si rompe: quella in coda resta intera, con gli
   * stessi tre lavori, e non ne nasce un'altra (e' proprio cio' che `KEEP` deve fare: "senza spostare un giro gia' in coda"). Qui si prova entrambe le cose: la prima chiamata non scrive niente, la seconda
   * lascia la catena identica e scrive esattamente quelle due righe.
   */
  @Test
  fun scanNowConLaCatenaGiaInCodaLaLasciaIntera_ELeDueRigheDiWorkManagerSonoQuelle() {
    ShadowLog.clear()
    NasWorkScheduling.scanNow(context)
    val first = workManager.getWorkInfosForUniqueWork(NasWorkScheduling.SCAN_NOW_WORK).get()
    assertThat(first).hasSize(3)
    assertThat(first.map { it.state }).containsExactly(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.BLOCKED)
    assertThat(prerequisiteLines()).isEmpty()

    NasWorkScheduling.scanNow(context)
    val second = workManager.getWorkInfosForUniqueWork(NasWorkScheduling.SCAN_NOW_WORK).get()
    assertThat(second.map { it.id }.toSet()).isEqualTo(first.map { it.id }.toSet())
    assertThat(second.map { it.state }).containsExactly(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.BLOCKED)
    assertThat(prerequisiteLines()).hasSize(2)

    // Chiusa la catena (qui annullata: tutti e tre finiti), una chiamata nuova ne accoda una nuova, intera e senza righe d'errore.
    ShadowLog.clear()
    val ids = first.map { it.id }
    workManager.cancelUniqueWork(NasWorkScheduling.SCAN_NOW_WORK).result.get()
    NasWorkScheduling.scanNow(context)
    val again = workManager.getWorkInfosForUniqueWork(NasWorkScheduling.SCAN_NOW_WORK).get().filter { !it.state.isFinished }
    assertThat(again).hasSize(3)
    assertThat(again.map { it.id }.toSet()).containsNoneIn(ids)
    assertThat(prerequisiteLines()).isEmpty()
  }

  /** I lavori dell'utente (UIDT): `setRequiredNetworkType(NETWORK_TYPE_ANY)` chiede una rete validata, `setRequiredNetwork(LanConstraints.request())` no. */
  @Test
  fun iLavoriDellUtenteNonChiedonoInternet() {
    val scheduler = context.getSystemService(JobScheduler::class.java)

    JobSchedulerBackupJobs(context).userInitiated(BackupTrigger.USER_NOW, confirmed = false)
    JobSchedulerNasOpsJobs(context).userInitiated()
    JobSchedulerNasOpsJobs(context).resumeLater(0)

    val jobs = scheduler.allPendingJobs
    assertThat(jobs.map(JobInfo::getId)).containsAtLeast(JobSchedulerBackupJobs.JOB_USER, JobSchedulerNasOpsJobs.JOB_USER, JobSchedulerNasOpsJobs.JOB_RESUME)
    for (job in jobs) assertNoInternetRequired(job.requiredNetwork, "job ${job.id}")
  }
}
