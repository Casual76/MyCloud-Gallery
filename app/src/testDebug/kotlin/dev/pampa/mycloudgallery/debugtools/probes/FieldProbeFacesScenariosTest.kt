package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.integration.Attese
import dev.pampa.mycloudgallery.integration.FacesNode
import dev.pampa.mycloudgallery.integration.FacesRig
import dev.pampa.mycloudgallery.integration.TestNas
import java.io.File
import kotlin.time.Duration.Companion.minutes
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DR-2 (verifica funzionale della Fase 8, correzioni E): il banco F103 (`s25.ps1 -Mode faces`) e la ricetta forzata F109 hanno il loro ricevitore in **debug**.
 * Il ricevitore accoda soltanto un lavoro che fa cio' che farebbe un tocco sull'app (le regole, il cancello D34 e la recinzione D33 restano quelli di sempre), ascolta
 * una sola azione, accetta solo gli scenari noti e non costruisce mai un consenso o una conferma di cancellazione (lo vietano le regole di architettura).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FieldProbeFacesScenariosTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(app)
  private var rig: FacesRig? = null

  @Before
  fun setUp() {
    // Un lavoro che non finisce mai: resta in coda (come fra un giro e l'altro in produzione) e si guarda, senza eseguirlo davvero (lo scenario vero lo prova `run`).
    val factory = object : WorkerFactory() {
      override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
        object : CoroutineWorker(appContext, workerParameters) {
          override suspend fun doWork(): Result = awaitCancellation()
        }
    }
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
  }

  @After
  fun tearDown() {
    rig?.close()
    File(app.filesDir, FieldProbeFacesStatus.DIR).deleteRecursively()
  }

  private fun queued(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

  private fun broadcast(action: String?, scenario: String?) {
    val intent = Intent().apply {
      if (action != null) setAction(action)
      if (scenario != null) putExtra(FieldProbeFacesScenarios.EXTRA_SCENARIO, scenario)
    }
    FieldProbeFacesScenarioReceiver().onReceive(app, intent)
  }

  @Test
  fun iNomiSonoQuelliDelloScriptEUnNomeSbagliatoNonFaNiente() {
    // I tre che `s25.ps1` manda gia' e quello nuovo della ricetta.
    assertThat(FieldProbeFacesScenario.entries.map { it.code }).containsExactly("f103-compute", "f103-erase", "f103-status", "f109-recipe")
    assertThat(FieldProbeFacesScenario.of("f103-compute")).isEqualTo(FieldProbeFacesScenario.COMPUTE)
    assertThat(FieldProbeFacesScenario.of(" F103-ERASE ")).isEqualTo(FieldProbeFacesScenario.ERASE)
    assertThat(FieldProbeFacesScenario.of("f103-delete-everything")).isNull()
    assertThat(FieldProbeFacesScenario.of("")).isNull()
    assertThat(FieldProbeFacesScenario.of(null)).isNull()
    assertThat(FieldProbeFacesScenarios.ACTION).isEqualTo("dev.pampa.mycloudgallery.debug.action.FACES_SCENARIO")
  }

  @Test
  fun ilRicevitoreAccodaUnLavoroUnicoPerScenario() {
    broadcast(FieldProbeFacesScenarios.ACTION, "f103-compute")
    broadcast(FieldProbeFacesScenarios.ACTION, "f103-compute")
    broadcast(FieldProbeFacesScenarios.ACTION, "f103-status")

    assertThat(queued(FieldProbeFacesScenarios.workName(FieldProbeFacesScenario.COMPUTE))).hasSize(1)
    assertThat(queued(FieldProbeFacesScenarios.workName(FieldProbeFacesScenario.STATUS))).hasSize(1)
    assertThat(queued(FieldProbeFacesScenarios.workName(FieldProbeFacesScenario.ERASE))).isEmpty()
    assertThat(queued(FieldProbeFacesScenarios.workName(FieldProbeFacesScenario.RECIPE))).isEmpty()
  }

  @Test
  fun ilRicevitoreAccettaSoloLAzioneNotaEGliScenariNoti() {
    // Un'altra azione (anche quella dei lavori periodici), nessuna azione, uno scenario inventato o mancante: niente in coda.
    broadcast("dev.pampa.mycloudgallery.debug.PERIODIC_NOW", "f103-compute")
    broadcast(Intent.ACTION_VIEW, "f103-erase")
    broadcast(null, "f103-erase")
    broadcast(FieldProbeFacesScenarios.ACTION, "f103-forse")
    broadcast(FieldProbeFacesScenarios.ACTION, null)

    for (scenario in FieldProbeFacesScenario.entries) assertThat(queued(FieldProbeFacesScenarios.workName(scenario))).isEmpty()
    assertThat(workManager.getWorkInfosByTag(FieldProbeFacesScenarios.TAG).get()).isEmpty()
  }

  /** Il manifesto di debug lo dichiara con il permesso `DUMP` (la shell di adb, nessuna app normale) e una sola azione; quello principale non lo conosce. */
  @Test
  fun ilManifestoDiDebugLoProtegge() {
    val debug = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File("src/debug/AndroidManifest.xml")).documentElement
    val android = "http://schemas.android.com/apk/res/android"
    val receivers = debug.getElementsByTagName("receiver")
    val ours = (0 until receivers.length).map { receivers.item(it) as org.w3c.dom.Element }
      .single { it.getAttributeNS(android, "name").endsWith("FieldProbeFacesScenarioReceiver") }
    assertThat(ours.getAttributeNS(android, "permission")).isEqualTo("android.permission.DUMP")
    assertThat(ours.getAttributeNS(android, "exported")).isEqualTo("true")
    val actions = ours.getElementsByTagName("action")
    assertThat((0 until actions.length).map { (actions.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") })
      .containsExactly(FieldProbeFacesScenarios.ACTION)
    assertThat(File("src/main/AndroidManifest.xml").readText()).doesNotContain("FACES_SCENARIO")
  }

  @Test
  fun ilFileDiStatoHaLeChiaviDelloScriptEUnaFaseNota() {
    val values = linkedMapOf(
      "erase_done" to "0", "faces_db" to "1", "keys" to "1", "local_done" to "0", "generation_present" to "1", "state" to "on", "phase" to "computed",
    )
    // Sempre nell'ordine dello script, una per riga, `chiave=valore`.
    assertThat(FieldProbeFacesStatus.render(values).trim().lines()).containsExactly(
      "phase=computed", "state=on", "generation_present=1", "local_done=0", "keys=1", "faces_db=1", "erase_done=0",
    ).inOrder()
    FieldProbeFacesStatus.write(app.filesDir, values)
    assertThat(FieldProbeFacesStatus.read(app.filesDir)).containsExactlyEntriesIn(values)
    assertThat(FieldProbeFacesStatus.PHASES).containsAtLeast("computed", "erased")
    // Mai un percorso o un nome: solo numeri e parole fisse.
    assertThat(FieldProbeFacesStatus.render(values)).doesNotContain("/")
  }

  @Test
  fun laRicettaForzataSiLeggeSoloDalSegnoENonVaMaSottoLaRicettaDiSerie() {
    assertThat(FieldProbeFacesRecipe.read(app.filesDir)).isNull()
    assertThat(DeveloperTools.facesRecipe(app)).isNull()

    FieldProbeFacesRecipe.force(app.filesDir)

    assertThat(FieldProbeFacesRecipe.read(app.filesDir)).isEqualTo(2)
    // Il gancio che legge `FacesWiring` quando monta le persone.
    assertThat(DeveloperTools.facesRecipe(app)).isEqualTo(2)
    // Un valore che non e' una ricetta valida (1 e' quella di serie, 0 e 300 non esistono) non cambia niente.
    for (bad in listOf("1", "0", "300", "due", "")) {
      write(FieldProbeFacesRecipe.file(app.filesDir), bad)
      assertWithMessage(bad).that(FieldProbeFacesRecipe.read(app.filesDir)).isNull()
    }
  }

  private fun write(file: File, text: String) = dev.pampa.mycloudgallery.core.common.files.StateFiles.writeText(file, text)

  // --- Lo scenario sul grafo vero ---------------------------------------------------------------------------------------------------------

  private suspend fun phone(): FacesNode {
    val faces = FacesRig(app, temp.root).also { rig = it }
    val device = faces.ai.foundedDevice()
    faces.putPhotos(TestNas.ADMIN_USER, 6)
    val node = faces.node("telefono", device, TestNas.ADMIN_USER)
    faces.catalogue(node, 6)
    return node
  }

  private fun run(node: FacesNode) = FieldProbeFacesScenarioRun(
    gallery = node.mounted.gallery,
    graph = node.device.graph,
    faces = node.faces,
    filesDir = app.filesDir,
    noBackup = node.noBackup,
    keyCount = { node.keys.aliases().size },
    pause = {},
  )

  private fun status(): Map<String, String> = FieldProbeFacesStatus.read(app.filesDir)

  @Test
  fun senzaAttivazioneLoScenarioDelCalcoloNonAttivaNienteEDiceLaFase() = runBlocking<Unit> {
    val node = phone()
    val scenarios = run(node)

    scenarios.run(FieldProbeFacesScenario.COMPUTE)

    // Il consenso lo da' solo la pagina di attivazione: lo scenario non lo costruisce e non accende Persone da solo.
    assertThat(status()["phase"]).isEqualTo("needs_activation")
    assertThat(status()["state"]).isEqualTo("off")
    assertThat(status()["generation_present"]).isEqualTo("0")
    assertThat(node.databases()).isEmpty()
    assertThat(node.keys.aliases()).isEmpty()
    // Nemmeno la cancellazione si avvia da qui: la conferma e' del foglio.
    scenarios.run(FieldProbeFacesScenario.ERASE)
    assertThat(status()["phase"]).isEqualTo("needs_erase")
    // "status" riscrive solo il file e tiene la fase.
    scenarios.run(FieldProbeFacesScenario.STATUS)
    assertThat(status()["phase"]).isEqualTo("needs_erase")
    // La ricetta forzata scrive il segno e dice solo che c'e'.
    scenarios.run(FieldProbeFacesScenario.RECIPE)
    assertThat(status()["phase"]).isEqualTo("recipe")
    assertThat(FieldProbeFacesRecipe.read(app.filesDir)).isEqualTo(2)
  }

  @Test
  fun conPersoneAttivaLoScenarioCalcolaERaggruppaPoiSiCancellaFinoInFondo() = runBlocking<Unit> {
    val node = phone()
    val scenarios = run(node)
    val outcome = node.live().switch.activate(FacesActivationConsent(FacesSwitch.CURRENT_TEXT_VERSION, SystemClock.elapsedRealtimeNanos()))
    assertThat(outcome).isInstanceOf(ActivateOutcome.Activated::class.java)

    scenarios.run(FieldProbeFacesScenario.COMPUTE)

    // `s25.ps1` aspetta phase=computed e poi guarda la cartella dell'app: un database dei volti, una chiave, Persone accesa.
    assertThat(status()["phase"]).isEqualTo("computed")
    assertThat(status()["state"]).isEqualTo("on")
    assertThat(status()["generation_present"]).isEqualTo("1")
    assertThat(status()["faces_db"]).isEqualTo("1")
    assertThat(status()["keys"]).isEqualTo("1")
    assertThat(status()["erase_done"]).isEqualTo("0")
    // Il raggruppamento di fine calcolo ha fatto nascere le persone.
    assertThat(withTimeout(Attese.TETTO) { node.live().people.others().first { it.people.size == 2 } }.people).hasSize(2)

    // La cancellazione la conferma il foglio (qui il test, che puo' costruire la conferma); lo scenario la riprende e la segue fino in fondo.
    val live = node.live()
    val preview = live.eraser.preview()
    live.eraser.erase(FacesEraseConfirmation(preview.digest, SystemClock.elapsedRealtimeNanos()), PeopleAlbumsChoice.KEEP_PHOTOS)

    // Come sul telefono: lo scenario si ripete (il banco lo rilancia dopo ogni morte) mentre l'ora del NAS va avanti: la voce «spenta» e le conferme escono con le
    // teste, al piu' una ogni cinque minuti.
    var attempts = 0
    while (status()["phase"] != "erased" && attempts++ < 12) {
      checkNotNull(rig).time.advance(6.minutes.inWholeMilliseconds)
      scenarios.run(FieldProbeFacesScenario.ERASE)
    }

    assertThat(status()["phase"]).isEqualTo("erased")
    assertThat(status()["state"]).isEqualTo("off")
    assertThat(status()["generation_present"]).isEqualTo("0")
    assertThat(status()["local_done"]).isEqualTo("1")
    assertThat(status()["keys"]).isEqualTo("0")
    assertThat(status()["faces_db"]).isEqualTo("0")
    assertThat(status()["erase_done"]).isEqualTo("1")
  }
}
