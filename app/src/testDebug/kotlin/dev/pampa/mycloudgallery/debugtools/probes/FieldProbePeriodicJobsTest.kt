package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.work.BackupWorkScheduling
import dev.pampa.mycloudgallery.work.NasWorkScheduling
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Correzioni dal campo, gruppo B, punto 9: "Fai ora i lavori periodici" (solo debug). Per far partire la pulizia del cestino, la convergenza delle
 * anteprime o le scansioni le prove sul campo modificavano il database di WorkManager; ora basta un'azione che accoda **gli stessi lavori** del giro
 * periodico, che poi passano dalle regole delle attivita' come sempre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FieldProbePeriodicJobsTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  }

  private fun queued(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

  @Test
  fun iNomiDelleProveSonoQuelliDocumentatiEUnNomeSbagliatoNonFaNiente() {
    assertThat(FieldProbePeriodicJobs.parse(null)).containsExactlyElementsIn(FieldProbePeriodicJob.ALL).inOrder()
    assertThat(FieldProbePeriodicJobs.parse("")).containsExactlyElementsIn(FieldProbePeriodicJob.ALL).inOrder()
    assertThat(FieldProbePeriodicJobs.parse("all")).containsExactlyElementsIn(FieldProbePeriodicJob.ALL).inOrder()
    assertThat(FieldProbePeriodicJobs.parse("trash,hq,prep,sync,scan,backup")).containsExactlyElementsIn(FieldProbePeriodicJob.ALL).inOrder()
    // Maiuscole e spazi non contano; l'ordine e' sempre quello dell'elenco; un nome ripetuto vale una volta.
    assertThat(FieldProbePeriodicJobs.parse(" SCAN , trash,scan")).containsExactly(FieldProbePeriodicJob.TRASH, FieldProbePeriodicJob.SCAN).inOrder()
    // Un nome che non c'e': meglio niente che indovinare.
    assertThat(FieldProbePeriodicJobs.parse("trash,cestino")).isNull()
  }

  @Test
  fun tuttiAccodaSubitoOgniLavoroPeriodicoConIVincoliDiSempre() {
    val names = FieldProbePeriodicJobs.enqueue(context, FieldProbePeriodicJob.ALL)

    val expected = listOf(
      NasWorkScheduling.TRASH_PURGE_NOW_WORK, NasWorkScheduling.HQ_NOW_WORK, NasWorkScheduling.PREP_NOW_WORK, NasWorkScheduling.SYNC_NOW_WORK,
      NasWorkScheduling.SCAN_NOW_WORK, BackupWorkScheduling.PHOTOS_WORK, BackupWorkScheduling.VIDEOS_WORK, BackupWorkScheduling.SAFETY_WORK,
    )
    assertThat(names).containsExactlyElementsIn(expected)
    for (name in expected) {
      val info = queued(name)
      assertWithMessage("lavoro $name accodato").that(info).isNotEmpty()
      // Una rete (anche senza internet: il NAS e' in LAN), come i lavori periodici veri.
      val request = info.first().constraints.requiredNetworkRequest
      assertWithMessage("$name: una rete").that(request).isNotNull()
      assertWithMessage("$name: niente internet obbligatorio").that(request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isFalse()
    }
  }

  @Test
  fun unLavoroSoloNonTocaGliAltri() {
    val names = FieldProbePeriodicJobs.enqueue(context, listOf(FieldProbePeriodicJob.TRASH))

    assertThat(names).containsExactly(NasWorkScheduling.TRASH_PURGE_NOW_WORK)
    assertThat(queued(NasWorkScheduling.TRASH_PURGE_NOW_WORK)).hasSize(1)
    assertThat(queued(NasWorkScheduling.HQ_NOW_WORK)).isEmpty()
    assertThat(queued(NasWorkScheduling.SCAN_NOW_WORK)).isEmpty()
    assertThat(queued(BackupWorkScheduling.PHOTOS_WORK)).isEmpty()
  }

  @Test
  fun dueRichiesteDiFilaNonFannoPartireDueLavoriInsieme() {
    FieldProbePeriodicJobs.enqueue(context, listOf(FieldProbePeriodicJob.SYNC))
    FieldProbePeriodicJobs.enqueue(context, listOf(FieldProbePeriodicJob.SYNC))

    // Lavoro unico: il secondo si accoda dopo il primo (nessuno e' partito: i vincoli non sono soddisfatti), ma il nome e' uno.
    val all = workManager.getWorkInfosForUniqueWork(NasWorkScheduling.SYNC_NOW_WORK).get()
    assertThat(all.count { it.state == WorkInfo.State.RUNNING }).isEqualTo(0)
    assertThat(all).isNotEmpty()
  }

  @Test
  fun ilRicevitoreAccodaCioCheIlBroadcastChiedeESoloConLAzioneGiusta() {
    val receiver = FieldProbePeriodicJobsReceiver()

    receiver.onReceive(context, Intent("un'altra.azione").putExtra(FieldProbePeriodicJobs.EXTRA_JOBS, "all"))
    assertThat(queued(NasWorkScheduling.TRASH_PURGE_NOW_WORK)).isEmpty()

    receiver.onReceive(context, Intent(FieldProbePeriodicJobs.ACTION).putExtra(FieldProbePeriodicJobs.EXTRA_JOBS, "trash,sync"))
    assertThat(queued(NasWorkScheduling.TRASH_PURGE_NOW_WORK)).hasSize(1)
    assertThat(queued(NasWorkScheduling.SYNC_NOW_WORK)).isNotEmpty()
    assertThat(queued(NasWorkScheduling.HQ_NOW_WORK)).isEmpty()

    // Un nome che non esiste non accoda niente di nuovo.
    receiver.onReceive(context, Intent(FieldProbePeriodicJobs.ACTION).putExtra(FieldProbePeriodicJobs.EXTRA_JOBS, "hq,boh"))
    assertThat(queued(NasWorkScheduling.HQ_NOW_WORK)).isEmpty()
  }

  @Test
  fun ilRicevitoreNelManifestDiDebugEProtettoDaDump() {
    // Esportato ma solo per chi ha DUMP (la shell di adb): un'altra app non puo' far partire i lavori.
    val manifest = File("src/debug/AndroidManifest.xml").takeIf { it.isFile } ?: File("app/src/debug/AndroidManifest.xml")
    val text = manifest.readText()
    val receiver = Regex("""<receiver[^>]*FieldProbePeriodicJobsReceiver[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(text)?.value
    assertThat(receiver).isNotNull()
    assertThat(receiver).contains("android:permission=\"android.permission.DUMP\"")
    assertThat(text).contains("dev.pampa.mycloudgallery.debug.PERIODIC_NOW")
  }
}
