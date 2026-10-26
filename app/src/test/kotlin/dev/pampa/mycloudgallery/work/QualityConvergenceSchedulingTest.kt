package dev.pampa.mycloudgallery.work

import android.app.Application
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F12, O-B16 (prove sul campo): dopo un cambio di qualita' delle anteprime i lavori che convergono si accodano **subito**, non fra 6 ore. Sono gli
 * stessi lavori del giro periodico (`IndexSyncWorker`, e `HqPreviewsWorker` salendo ad "Alta"), quindi passano dalle regole delle attivita' come
 * sempre; la rete che chiedono e' quella dei lavori del NAS (LAN, niente internet obbligatorio).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QualityConvergenceSchedulingTest {

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  @Before
  fun setUp() {
    // Nessun executor che parte da solo: i lavori restano in coda e si guardano, senza eseguirli.
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  }

  private fun queued(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

  @Test
  fun salireAdAltaAccodaSubitoLaSincronizzazioneELoScaricamentoDellHq() {
    NasWorkScheduling.convergeNow(context, QualityLevel.HIGH)

    assertWithMessage("sincronizzazione dell'indice").that(queued(NasWorkScheduling.SYNC_NOW_WORK)).hasSize(1)
    assertWithMessage("scaricamento dell'HQ").that(queued(NasWorkScheduling.HQ_NOW_WORK)).hasSize(1)
  }

  @Test
  fun scendereOStareSuStandardAccodaSoloLaSincronizzazione() {
    NasWorkScheduling.convergeNow(context, QualityLevel.SAVER)
    NasWorkScheduling.convergeNow(context, QualityLevel.STANDARD)

    assertThat(queued(NasWorkScheduling.SYNC_NOW_WORK)).isNotEmpty()
    assertThat(queued(NasWorkScheduling.HQ_NOW_WORK)).isEmpty()
  }

  @Test
  fun iLavoriSubitoChiedonoSoloUnaReteComeQuelliPeriodiciENonUnoStessoSullaCodaDelPeriodico() {
    NasWorkScheduling.ensure(context)
    NasWorkScheduling.convergeNow(context, QualityLevel.HIGH)

    for (name in listOf(NasWorkScheduling.SYNC_NOW_WORK, NasWorkScheduling.HQ_NOW_WORK)) {
      val info = queued(name).single()
      val request = info.constraints.requiredNetworkRequest
      assertWithMessage("$name: una rete").that(request).isNotNull()
      assertWithMessage("$name: niente internet obbligatorio").that(request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isFalse()
      assertThat(info.tags).contains(NasWorkScheduling.TAG)
    }
    // Il giro periodico di prima resta dov'e': il lavoro subito si aggiunge, non lo sposta.
    assertThat(queued(NasWorkScheduling.SYNC_WORK)).hasSize(1)
    assertThat(queued(NasWorkScheduling.HQ_WORK)).hasSize(1)
  }

  @Test
  fun duePassaggiDiFilaNonPerdonoNeDuplicanoIlLavoroInCoda() {
    NasWorkScheduling.convergeNow(context, QualityLevel.HIGH)
    NasWorkScheduling.convergeNow(context, QualityLevel.STANDARD)

    // Il secondo cambio si accoda dopo il primo (un giro iniziato con le classi di prima non vale per il livello nuovo): niente si perde.
    assertThat(queued(NasWorkScheduling.SYNC_NOW_WORK)).isNotEmpty()
  }
}
