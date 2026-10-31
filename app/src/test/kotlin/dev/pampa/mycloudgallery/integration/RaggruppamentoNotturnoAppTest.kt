package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.library.duplicates.AlbumMembership
import dev.pampa.mycloudgallery.core.library.duplicates.ShotVectorSource
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJobResult
import dev.pampa.mycloudgallery.utility.UtilityGraph
import dev.pampa.mycloudgallery.work.FacesNightlyWorker
import dev.pampa.mycloudgallery.work.FacesStages
import dev.pampa.mycloudgallery.work.LibraryMaintenanceWorker
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
 * RG-1 (verifica funzionale della Fase 8, correzioni E): il raggruppamento notturno e la manutenzione della Libreria partono in **un processo appena nato**, dove
 * lo stato della famiglia passa un attimo per «non configurato» prima di «pronta» (D-7). Prima `LibraryMaintenanceWorker` non aspettava la famiglia pronta: `runIfActive`
 * rispondeva null e il lavoro finiva in qualche decimo di secondo con `success`, senza nascite, unioni, viaggi ne' smart (VG-1 della Fase 7). Adesso:
 *
 * - il raggruppamento ha **un lavoro suo** (`FacesNightlyWorker`) che aspetta la famiglia pronta e poi fa nascere le persone che il calcolo non ha potuto far nascere
 *   (meno di 50 volti nuovi);
 * - la manutenzione della Libreria aspetta la famiglia pronta e poi lavora (viaggi, smart, Utility), come tutti gli altri lavori.
 *
 * Grafo vero dell'app (galleria, Fase 6, album, persone) sul NAS simulato; finti solo i motori dei volti e i loro ingressi (scene sintetiche: nessun volto vero).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class RaggruppamentoNotturnoAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private var rig: FacesRig? = null

  @After
  fun tearDown() {
    rig?.close()
  }

  private class Phone(val rig: FacesRig, val node: FacesNode, val setup: ColdStartSetup)

  /** Il telefono con Persone attiva e il calcolo finito sulle foto di prova: i volti ci sono, le persone no (sotto soglia: aspettano la notte). */
  private suspend fun phoneAfterCompute(): Phone {
    val faces = FacesRig(app, temp.root).also { rig = it }
    val device = faces.ai.foundedDevice()
    faces.putPhotos(TestNas.ADMIN_USER, PHOTOS)
    val setup = ColdStartSetup(device.graph.setup, device.scope)
    val node = faces.node("telefono", device, TestNas.ADMIN_USER, setup)
    faces.catalogue(node, PHOTOS)
    val outcome = node.live().switch.activate(FacesActivationConsent(FacesSwitch.CURRENT_TEXT_VERSION, SystemClock.elapsedRealtimeNanos()))
    assertWithMessage("attivazione: $outcome").that(outcome).isInstanceOf(ActivateOutcome.Activated::class.java)
    // Un giro dell'indice (dati personali, poi volti): lo stato e' fresco e questo telefono prende la prenotazione del custode (senza, raggrupperebbe solo in provvisorio).
    node.round()
    val stages = FacesStages(device.graph, node.faces, node.noBackup)
    withTimeout(Attese.TETTO) {
      while (node.live().counters.facesSaved.get() < FACES_EXPECTED) stages.compute(user = true)
    }
    return Phone(faces, node, setup)
  }

  /**
   * Il momento in cui parte il lavoro in un processo nuovo: lo stato della famiglia dice «non configurato» e torna «pronta» poco dopo (come D-7). Aspetta lo stato
   * davvero visto, poi lascia andare il lavoro.
   */
  private suspend fun <T> coldProcess(phone: Phone, work: suspend () -> T): T {
    val setup = phone.setup
    setup.override.value = SetupState.NotConfigured
    withTimeout(Attese.TETTO) { setup.state.first { it == SetupState.NotConfigured } }
    val settle = phone.node.device.scope.launch(Dispatchers.Default) {
      delay(PASSING_MS)
      setup.override.value = null
    }
    try {
      return work()
    } finally {
      settle.join()
    }
  }

  /** Il lavoro come lo crea WorkManager, con il contesto del dispositivo di prova (la sua `noBackupFilesDir`: li' sta il battito). */
  private fun nightlyWorker(phone: Phone): FacesNightlyWorker = TestListenableWorkerBuilder<FacesNightlyWorker>(phone.node.device.context)
    .setWorkerFactory(
      object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
          FacesNightlyWorker(appContext, workerParameters, phone.node.mounted.gallery, phone.node.device.graph, phone.node.faces)
      },
    )
    .build()

  @Test
  fun rg1_ilRaggruppamentoNotturnoInUnProcessoAppenaNatoAspettaLaFamigliaProntaEFaNascereLePersone() = runBlocking<Unit> {
    val phone = phoneAfterCompute()
    val node = phone.node
    val stages = FacesStages(node.device.graph, node.faces, node.noBackup)
    // La premessa della revisione: con meno di 50 volti nuovi il calcolo non ha fatto nascere nessuna persona; aspettano il raggruppamento notturno.
    assertThat(personsInArchive(node)).isEqualTo(0)
    assertThat(stages.nightlyBeat.lastBeatAt()).isNull()

    val result = coldProcess(phone) { nightlyWorker(phone).doWork() }

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    // Il lavoro lascia il suo esito in "Ultimi esiti ed errori" (solo codice e nome del lavoro): finito. Senza l'attesa della famiglia pronta non girava (nessun esito).
    val outcomes = phone.rig.ai.ring.snapshot()
      .filter { it.code == DiagCodes.EVT_JOB_RESULT && it.values["job"] == ExportValue.Enum.of(DiagJob.FACES_NIGHTLY) }
      .map { it.values["result"] }
    assertWithMessage("esiti del raggruppamento notturno: $outcomes").that(outcomes).containsExactly(ExportValue.Enum.of(DiagJobResult.FINISHED))
    // Le persone sono nate: le due identita' delle foto di prova (un'archivio nuovo, nessuna persona prima).
    assertThat(personsInArchive(node)).isEqualTo(2)
    val born = withTimeout(Attese.TETTO) { node.live().people.others().first { it.people.size == 2 } }.people
    assertThat(born).hasSize(2)
    assertWithMessage("il raggruppamento notturno e' finito: il suo battito c'e'").that(stages.nightlyBeat.lastBeatAt()).isNotNull()
  }

  /** Le persone nell'istantanea del raggruppamento dell'archivio aperto di questo dispositivo (solo il numero). */
  private suspend fun personsInArchive(node: FacesNode): Int = checkNotNull(node.live().archives.opened()) { "archivio dei volti chiuso" }.grouping()?.persons?.size ?: 0

  @Test
  fun rg1_unDispositivoNonPronto_ilLavoroNonFaNienteENonScriveIlBattito() = runBlocking<Unit> {
    val phone = phoneAfterCompute()
    val node = phone.node
    val stages = FacesStages(node.device.graph, node.faces, node.noBackup)
    // La famiglia non torna pronta entro l'attesa: niente da fare, e il battito non dice che il raggruppamento e' girato.
    phone.setup.override.value = SetupState.NotConfigured
    withTimeout(Attese.TETTO) { phone.setup.state.first { it == SetupState.NotConfigured } }

    val result = TestListenableWorkerBuilder<FacesNightlyWorker>(node.device.context)
      .setWorkerFactory(
        object : WorkerFactory() {
          override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            FacesNightlyWorker(appContext, workerParameters, node.mounted.gallery, node.device.graph, node.faces)
        },
      )
      .build()
      .let { worker -> withTimeout(Attese.TETTO) { worker.doWork() } }

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(stages.nightlyBeat.lastBeatAt()).isNull()
    assertThat(node.live().people.others().first().people).isEmpty()
    phone.setup.override.value = null
  }

  @Test
  fun rg1_laManutenzioneDellaLibreriaInUnProcessoAppenaNatoAspettaLaFamigliaPronta() = runBlocking<Unit> {
    val phone = phoneAfterCompute()
    val node = phone.node
    val gallery = node.mounted.gallery
    val utility = UtilityGraph(
      catalog = { gallery.coreIfReady()?.catalog },
      visible = gallery.visibleMedia,
      hidden = gallery.hiddenMask,
      albums = AlbumMembership { emptySet() },
      vectors = ShotVectorSource { emptyMap() },
      signatures = { null },
      fullHashOn = { null },
    )
    // Fase 9 (P13): i ricordi come in `MemoriesModule` (la manutenzione li genera dopo i viaggi).
    val memories = node.memoriesGraph()
    val worker = TestListenableWorkerBuilder<LibraryMaintenanceWorker>(node.device.context)
      .setWorkerFactory(
        object : WorkerFactory() {
          override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            LibraryMaintenanceWorker(appContext, workerParameters, gallery, node.mounted.stack.search, node.albums, utility, node.device.graph, memories)
        },
      )
      .build()
    assertThat(node.albums.lastTrips.value).isNull()

    val result = coldProcess(phone) { worker.doWork() }

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    // I viaggi (Fase 7, VG-1) si cercano di notte nello stesso lavoro: senza l'attesa non partivano.
    assertWithMessage("i viaggi sono stati cercati").that(node.albums.lastTrips.value).isNotNull()
    // E il raggruppamento notturno non e' piu' qui dentro: la manutenzione non lo fa girare (ha il suo lavoro).
    assertThat(FacesStages(node.device.graph, node.faces, node.noBackup).nightlyBeat.lastBeatAt()).isNull()
  }

  private companion object {
    const val PHOTOS = 6

    /** Le foto di prova sono sei: due identita' in tutte tranne una, quindi 11 volti (vedi `SceneBook`). */
    const val FACES_EXPECTED = 11

    /** Quanto lo stato «non configurato» dura prima di tornare «pronta» (un processo appena nato: un attimo). */
    const val PASSING_MS = 300L
  }
}
