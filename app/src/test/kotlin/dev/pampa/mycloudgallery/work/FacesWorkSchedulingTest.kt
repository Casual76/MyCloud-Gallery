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
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.faces.FacesGraph
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.awaitCancellation
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 8, P13 (§1.4 "Lavori"): i lavori delle persone hanno **nomi distinti** da quelli degli altri lavori dell'app (come IX-1 della revisione B della
 * Fase 6: due catene con lo stesso nome si annullano a vicenda), un solo "Analizza ora" alla volta e una cancellazione che non chiede la rete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FacesWorkSchedulingTest {

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  @Before
  fun setUp() {
    // Un lavoro che non finisce mai: resta in coda (come fra un giro e l'altro in produzione) e si guarda, senza eseguirlo davvero (niente Hilt qui).
    val factory = object : WorkerFactory() {
      override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
        object : CoroutineWorker(appContext, workerParameters) {
          override suspend fun doWork(): Result = awaitCancellation()
        }
    }
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
  }

  private fun queued(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

  private val names = listOf(
    FacesWorkScheduling.COMPUTE_WORK,
    FacesWorkScheduling.COMPUTE_NOW_WORK,
    FacesWorkScheduling.COMPUTE_NEWS_WORK,
    FacesWorkScheduling.COMPUTE_NEXT_WORK,
    FacesWorkScheduling.ERASE_WORK,
    FacesWorkScheduling.NIGHTLY_WORK,
    FacesWorkScheduling.NIGHTLY_NEXT_WORK,
  )

  @Test
  fun iNomiSonoDistintiFraLoroEDaQuelliDegliAltriLavori() {
    assertThat(names).containsNoDuplicates()
    val dir = File("src/main/kotlin/dev/pampa/mycloudgallery/work")
    val others = dir.listFiles { f -> f.extension == "kt" && f.name != "FacesWork.kt" }.orEmpty().flatMap { file ->
      Regex("""const val \w+(?:: String)? = "([\w-]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toList()
    }
    assertThat(others).isNotEmpty()
    assertWithMessage("nomi di lavoro dei volti gia' usati da altri lavori").that(others.filter { it in names }).isEmpty()
    // Nessun nome degli altri lavori comincia come quelli dei volti (un prefisso comune basta a confonderli nei filtri per nome).
    assertThat(others.filter { it.startsWith("faces-") }).isEmpty()
    assertThat(names).containsNoneIn(listOf(AiWorkScheduling.COMPUTE_WORK, AiWorkScheduling.COMPUTE_NOW_WORK, AiWorkScheduling.COMPUTE_NEWS_WORK, AiWorkScheduling.COMPUTE_NEXT_WORK))
  }

  @Test
  fun ilCalcoloPeriodicoSiProgrammaUnaSolaVolta() {
    FacesWorkScheduling.ensure(context)
    FacesWorkScheduling.ensure(context)
    assertThat(queued(FacesWorkScheduling.COMPUTE_WORK)).hasSize(1)
  }

  @Test
  fun analizzaOraNonSiRaddoppiaConUnSecondoTocco() {
    FacesWorkScheduling.computeNow(context)
    FacesWorkScheduling.computeNow(context)
    assertThat(queued(FacesWorkScheduling.COMPUTE_NOW_WORK)).hasSize(1)
    // Il periodico e il lavoro delle novita' non si toccano.
    assertThat(queued(FacesWorkScheduling.COMPUTE_WORK)).isEmpty()
    assertThat(queued(FacesWorkScheduling.COMPUTE_NEWS_WORK)).isEmpty()
  }

  @Test
  fun leNovitaUsanoIlLoroNomeELaTappaDopoIlSuo() {
    FacesWorkScheduling.afterNews(context)
    FacesWorkScheduling.afterNews(context)
    assertThat(queued(FacesWorkScheduling.COMPUTE_NEWS_WORK)).hasSize(1)

    FacesWorkScheduling.continueCompute(context, user = false)
    assertThat(queued(FacesWorkScheduling.COMPUTE_NEXT_WORK)).isNotEmpty()
    assertThat(queued(FacesWorkScheduling.COMPUTE_NOW_WORK)).isEmpty()

    FacesWorkScheduling.continueCompute(context, user = true)
    assertThat(queued(FacesWorkScheduling.COMPUTE_NOW_WORK)).isNotEmpty()
  }

  @Test
  fun laCancellazioneRiprendeSubitoOFraUnPoSenzaAnnullareQuellaInCorso() {
    FacesWorkScheduling.eraseNow(context)
    FacesWorkScheduling.eraseNow(context)
    assertThat(queued(FacesWorkScheduling.ERASE_WORK)).hasSize(1)
    // Fra un po': si accoda a quella in corso, mai la annulla.
    FacesWorkScheduling.eraseIn(context, 30.minutes)
    assertThat(queued(FacesWorkScheduling.ERASE_WORK)).isNotEmpty()
    // Niente rete obbligatoria: i passi locali non la chiedono.
    for (info in queued(FacesWorkScheduling.ERASE_WORK)) {
      assertThat(info.tags).contains(FacesWorkScheduling.TAG)
    }
  }

  /** RG-1 (correzioni E): il raggruppamento notturno ha un lavoro suo, periodico una volta al giorno in carica e a batteria non scarica. */
  @Test
  fun rg1_ilRaggruppamentoNotturnoHaUnLavoroPeriodicoSuoInCarica() {
    FacesWorkScheduling.ensure(context)
    FacesWorkScheduling.ensure(context)

    val nightly = queued(FacesWorkScheduling.NIGHTLY_WORK)
    assertThat(nightly).hasSize(1)
    assertThat(nightly.single().constraints.requiresCharging()).isTrue()
    assertThat(nightly.single().constraints.requiresBatteryNotLow()).isTrue()
    // Non e' piu' dentro la manutenzione della Libreria e non e' il calcolo: nomi e lavori distinti.
    assertThat(FacesWorkScheduling.NIGHTLY_WORK).isNotEqualTo(GalleryWorkScheduling.MAINTENANCE_WORK)
    assertThat(queued(FacesWorkScheduling.COMPUTE_WORK)).hasSize(1)
    assertThat(queued(FacesWorkScheduling.NIGHTLY_NEXT_WORK)).isEmpty()
  }

  /** RG-1: dopo la tappa del calcolo il raggruppamento segue nella stessa notte, in un lavoro unico (due richieste valgono una). */
  @Test
  fun rg1_dopoIlCalcoloIlRaggruppamentoSegueInUnLavoroUnico() {
    FacesWorkScheduling.nightlyAfterCompute(context)
    FacesWorkScheduling.nightlyAfterCompute(context)

    assertThat(queued(FacesWorkScheduling.NIGHTLY_NEXT_WORK)).hasSize(1)
    // Non tocca ne' il periodico ne' il calcolo.
    assertThat(queued(FacesWorkScheduling.NIGHTLY_WORK)).isEmpty()
    assertThat(queued(FacesWorkScheduling.COMPUTE_NEXT_WORK)).isEmpty()
  }

  // ---- Correzioni C -----------------------------------------------------------------------------------------------------------------------

  /**
   * CA-3: il primo calcolo dopo l'accensione segue le regole di sottofondo. Prima la richiesta del grafo (`COMPUTE`) accodava `computeNow`, cioe' un lavoro
   * dell'utente (`UserInitiated`) che scavalca notte e carica: la pagina diceva «di notte, in carica» e il calcolo partiva di giorno, a batteria.
   */
  @Test
  fun ca3_ilPrimoCalcoloDopoLAccensioneNonEUnLavoroDellUtente() {
    FacesWorkScheduling.request(context, FacesGraph.FacesWork.COMPUTE)

    assertThat(queued(FacesWorkScheduling.COMPUTE_NEWS_WORK)).hasSize(1)
    // Il lavoro dell'utente («Analizza ora») ha un nome suo e qui non c'e'.
    assertThat(queued(FacesWorkScheduling.COMPUTE_NOW_WORK)).isEmpty()
  }

  /** CA-2: «Analizza ora» e' l'unica richiesta dell'utente; un secondo tocco non ne mette un'altra. */
  @Test
  fun ca2_soloAnalizzaOraEUnLavoroDellUtente() {
    FacesWorkScheduling.request(context, FacesGraph.FacesWork.COMPUTE_NOW)
    FacesWorkScheduling.request(context, FacesGraph.FacesWork.COMPUTE_NOW)

    assertThat(queued(FacesWorkScheduling.COMPUTE_NOW_WORK)).hasSize(1)
    assertThat(queued(FacesWorkScheduling.COMPUTE_NEWS_WORK)).isEmpty()
  }

  @Test
  fun ca3_laCancellazioneRiprendeSenzaToccareIlCalcolo() {
    FacesWorkScheduling.request(context, FacesGraph.FacesWork.ERASE)

    assertThat(queued(FacesWorkScheduling.ERASE_WORK)).hasSize(1)
    assertThat(queued(FacesWorkScheduling.COMPUTE_NOW_WORK)).isEmpty()
    assertThat(queued(FacesWorkScheduling.COMPUTE_NEWS_WORK)).isEmpty()
  }

  /**
   * CA-5: dopo «Disattiva e cancella» (che toglie il lavoro periodico) e una riattivazione **nello stesso processo** il periodico torna: prima `ensure` girava solo
   * all'avvio dell'app e il calcolo delle foto nuove non aveva il giro delle 6 ore fino al riavvio.
   */
  @Test
  fun ca5_dopoLaCancellazioneELaRiattivazioneIlPeriodicoTorna() {
    FacesWorkScheduling.ensure(context)
    assertThat(queued(FacesWorkScheduling.COMPUTE_WORK)).hasSize(1)
    // Come `EraseStopWork`: la cancellazione toglie il periodico.
    workManager.cancelUniqueWork(FacesWorkScheduling.COMPUTE_WORK).result.get()
    assertThat(queued(FacesWorkScheduling.COMPUTE_WORK)).isEmpty()

    // Persone si riaccende qui: lo stesso passaggio che chiede il primo calcolo riprogramma il periodico.
    FacesWorkScheduling.request(context, FacesGraph.FacesWork.COMPUTE)

    assertThat(queued(FacesWorkScheduling.COMPUTE_WORK)).hasSize(1)
    assertThat(queued(FacesWorkScheduling.NIGHTLY_WORK)).hasSize(1)
    // Una seconda accensione non ne mette un secondo.
    FacesWorkScheduling.request(context, FacesGraph.FacesWork.COMPUTE)
    assertThat(queued(FacesWorkScheduling.COMPUTE_WORK)).hasSize(1)
  }

  /** CA-4: dopo un giro con novita' il calcolo dei volti segue, ma solo se Persone e' accesa per l'account. */
  @Test
  fun ca4_dopoUnGiroConNovitaIlCalcoloSeguiSoloConPersoneAccesa() {
    val generation = dev.pampa.mycloudgallery.core.model.FaceGeneration.random()
    for (setting in listOf(null, FacesSetting.Off(generation), FacesSetting.Unknown)) {
      FacesWorkScheduling.afterNewsIfOn(context, setting)
      assertThat(queued(FacesWorkScheduling.COMPUTE_NEWS_WORK)).isEmpty()
    }

    FacesWorkScheduling.afterNewsIfOn(context, FacesSetting.On(generation, 1))
    FacesWorkScheduling.afterNewsIfOn(context, FacesSetting.On(generation, 1))

    assertThat(queued(FacesWorkScheduling.COMPUTE_NEWS_WORK)).hasSize(1)
    assertThat(queued(FacesWorkScheduling.COMPUTE_NOW_WORK)).isEmpty()
  }

  /**
   * CA-4: chi chiama `afterNews` dei volti. Prima non aveva chiamanti: i due punti in cui finisce bene `AiWorkScheduling.afterNews` (il giro dell'indice e la
   * preparazione, i modelli pronti) ora chiamano anche i volti.
   */
  @Test
  fun ca4_iPuntiDoveFinisceBeneIlCalcoloAiChiamanoAncheIVolti() {
    val dir = File("src/main/kotlin/dev/pampa/mycloudgallery/work")
    val nas = File(dir, "NasWork.kt").readText()
    val ai = File(dir, "AiWork.kt").readText()
    assertThat(nas).contains("FacesWorkScheduling.afterNewsIfOn(")
    assertThat(ai).contains("FacesWorkScheduling.afterNews(applicationContext)")
    // Ognuno dopo il corrispondente dell'AI, nello stesso punto.
    assertThat(nas.indexOf("FacesWorkScheduling.afterNewsIfOn(")).isGreaterThan(nas.indexOf("AiWorkScheduling.afterNews(applicationContext)"))
    assertThat(ai.lastIndexOf("FacesWorkScheduling.afterNews(applicationContext)")).isGreaterThan(ai.indexOf("AiWorkScheduling.afterNews(applicationContext)"))
  }
}
