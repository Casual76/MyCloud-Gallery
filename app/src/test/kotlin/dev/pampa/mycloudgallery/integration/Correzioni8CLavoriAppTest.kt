package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.work.GalleryWorkScheduling
import dev.pampa.mycloudgallery.work.PhoneSyncWorker
import dev.pampa.mycloudgallery.work.ShareWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L372 (correzioni C, come RG-1 e D-7): la lettura del telefono in background (`PhoneSyncWorker`) e la condivisione (`ShareWorker`) partono in **un processo appena nato**,
 * dove lo stato della famiglia passa un attimo per «non configurato» prima di «pronta». Prima nessuno dei due aspettava la famiglia pronta: `runIfActive` rispondeva null e il
 * giro finiva in qualche decimo di secondo con `success` (e la lettura del telefono non riaccodava nemmeno il lavoro a trigger di contenuto: la catena riprendeva solo al
 * prossimo avvio dell'app). Grafo vero dell'app sul NAS simulato.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class Correzioni8CLavoriAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private var rig: FacesRig? = null

  @After
  fun tearDown() {
    rig?.close()
  }

  private class Phone(val rig: FacesRig, val node: FacesNode, val setup: ColdStartSetup)

  private suspend fun phone(): Phone {
    val faces = FacesRig(app, temp.root).also { rig = it }
    val device = faces.ai.foundedDevice()
    val setup = ColdStartSetup(device.graph.setup, device.scope)
    val node = faces.node("telefono", device, TestNas.ADMIN_USER, setup)
    return Phone(faces, node, setup)
  }

  /** Il lavoro parte con la famiglia «non configurata» e torna «pronta» dopo [PASSING_MS]: com'e' in un processo appena nato. [work] risponde anche lo stato visto all'uscita. */
  private suspend fun <T> coldProcess(phone: Phone, work: suspend () -> T): Pair<T, SetupState> {
    val setup = phone.setup
    setup.override.value = SetupState.NotConfigured
    withTimeout(Attese.TETTO) { setup.state.first { it == SetupState.NotConfigured } }
    val settle = phone.node.device.scope.launch(Dispatchers.Default) {
      delay(PASSING_MS)
      setup.override.value = null
    }
    try {
      val done = work()
      return done to setup.state.value
    } finally {
      settle.join()
    }
  }

  private fun phoneSyncWorker(phone: Phone): PhoneSyncWorker = TestListenableWorkerBuilder<PhoneSyncWorker>(phone.node.device.context)
    .setWorkerFactory(
      object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
          PhoneSyncWorker(appContext, workerParameters, phone.node.mounted.gallery)
      },
    )
    .build()

  private fun shareWorker(phone: Phone): ShareWorker = TestListenableWorkerBuilder<ShareWorker>(phone.node.device.context)
    .setWorkerFactory(
      object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
          ShareWorker(appContext, workerParameters, phone.node.mounted.gallery, phone.node.albums, phone.node.device.graph)
      },
    )
    .build()

  private fun queued(context: Context, name: String): List<WorkInfo> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

  @Test
  fun l372_ilGiroDellaCondivisioneInUnProcessoAppenaNatoAspettaLaFamigliaPronta() = runBlocking<Unit> {
    val phone = phone()

    val (result, seenAtExit) = coldProcess(phone) { shareWorker(phone).doWork() }

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    // Il lavoro e' uscito a famiglia pronta: ha aspettato (senza l'attesa tornava subito, con «non configurata»).
    assertWithMessage("stato visto all'uscita: $seenAtExit").that(seenAtExit).isNotEqualTo(SetupState.NotConfigured)
    // E il giro e' girato davvero: lascia il suo esito in «Ultimi esiti ed errori» (solo codice e nome del lavoro).
    val outcomes = phone.rig.ai.ring.snapshot().filter { it.code == DiagCodes.EVT_JOB_RESULT && it.values["job"] == ExportValue.Enum.of(DiagJob.ALBUM_SHARE) }
    assertWithMessage("esiti della condivisione: $outcomes").that(outcomes).isNotEmpty()
  }

  @Test
  fun l372_laLetturaDelTelefonoInUnProcessoAppenaNatoAspettaLaFamigliaPronta() = runBlocking<Unit> {
    val phone = phone()
    WorkManagerTestInitHelper.initializeTestWorkManager(phone.node.device.context)

    val (result, seenAtExit) = coldProcess(phone) { phoneSyncWorker(phone).doWork() }

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertWithMessage("stato visto all'uscita: $seenAtExit").that(seenAtExit).isNotEqualTo(SetupState.NotConfigured)
    // Il lavoro a trigger di contenuto vale una volta sola: si riaccoda.
    assertThat(queued(phone.node.device.context, GalleryWorkScheduling.PHONE_SYNC_WORK)).isNotEmpty()
  }

  @Test
  fun l372_unDispositivoNonProntoRiaccodaComunqueLaCatenaATriggerDiContenuto() = runBlocking<Unit> {
    val phone = phone()
    WorkManagerTestInitHelper.initializeTestWorkManager(phone.node.device.context)
    assertThat(queued(phone.node.device.context, GalleryWorkScheduling.PHONE_SYNC_WORK)).isEmpty()
    // La famiglia non torna pronta entro l'attesa: niente da leggere, ma il prossimo cambiamento del MediaStore deve trovare un lavoro (prima la catena si spegneva).
    phone.setup.override.value = SetupState.NotConfigured
    withTimeout(Attese.TETTO) { phone.setup.state.first { it == SetupState.NotConfigured } }

    val result = withTimeout(Attese.TETTO) { phoneSyncWorker(phone).doWork() }

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(queued(phone.node.device.context, GalleryWorkScheduling.PHONE_SYNC_WORK)).hasSize(1)
    phone.setup.override.value = null
  }

  private companion object {
    /** Quanto lo stato «non configurato» dura prima di tornare «pronta» (un processo appena nato: un attimo). */
    const val PASSING_MS = 300L
  }
}
