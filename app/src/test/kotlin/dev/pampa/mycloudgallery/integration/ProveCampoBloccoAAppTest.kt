package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.work.NasScanWorker
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.work.NasStages
import java.io.File
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
 * I difetti trovati sul telefono vero nel blocco A delle prove sul campo (`docs/prove-campo-esiti.md` §3), sul grafo **vero** dell'app con
 * il simulatore SMB.
 *
 * D-2: dopo un avvio a freddo la prima scansione del NAS partiva prima che la sessione fosse pronta. L'ora del NAS non aveva un'ancora,
 * `SessionNasClock.nowMillis` lanciava e il lavoro "nas-scan-now" finiva consumato: la scansione ripartiva solo al ritorno in primo piano o
 * al giro periodico di 6 ore.
 *
 * O-2: due "Screenshot_….png" dentro `mcg-prova` non si contavano nella loro cartella di "Cosa salvare" e, con la sorgente Screenshot accesa,
 * finivano in `Screenshot/` invece che nella cartella scelta. Un file in una cartella scelta si salva con quella cartella, qualunque sia il tipo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class ProveCampoBloccoAAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend: InMemorySmbBackend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()
  private val flags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  private fun device(name: String, platform: TestPlatform = TestPlatform(time, backend)): TestDevice =
    TestDevice(app, File(temp.root, name), platform, flags).also { devices += it }

  /**
   * Il telefono configurato (account `test`, admin) in un processo che poi muore; ritorna un **processo nuovo** sugli stessi file e con le
   * stesse chiavi: la fiducia si rilegge dai file, la sessione SMB non e' mai stata aperta e l'ora del NAS non ha un'ancora.
   */
  private suspend fun coldPhone(): TestDevice {
    val first = device("telefono")
    first.graph.start()
    first.graph.foundFamily()
    backend.put(TestNas.COMMON, "Foto/2024/IMG_0001.jpg", BackupRig.jpeg(tone = 40), lastWriteMs = time.now() - 2 * DAY)
    first.close()
    val cold = device("telefono", first.platform)
    cold.graph.start()
    cold.graph.awaitSetup { it is SetupState.Ready }
    return cold
  }

  /** La galleria del processo nuovo con il NAS avviato, ma senza nessuna operazione sul NAS: e' il momento in cui parte il lavoro. */
  private suspend fun galleryOf(phone: TestDevice, setup: FamilySetupRepository = phone.graph.setup): BackupRig {
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER, setup = setup).also { rigs += it }
    rig.gallery.startWhenReady()
    withTimeout(TIMEOUT_MS) { rig.gallery.started.first { it } }
    check(rig.nas.start()) { "il NAS non parte" }
    return rig
  }

  @Test
  fun d2_dopoUnAvvioAFreddoLaScansioneAspettaLaSessioneENonVaPersa() = runBlocking<Unit> {
    val phone = coldPhone()
    val rig = galleryOf(phone)
    // La corsa del telefono: il lavoro parte e nessuno ha ancora aperto la sessione.
    assertWithMessage("l'ora del NAS non deve avere ancora un'ancora").that(phone.graph.nasClock.nowMillisOrNull()).isNull()

    val stages = NasStages(phone.graph, rig.nas, phone.context.noBackupFilesDir)
    val outcome = stages.scan()

    assertWithMessage("esito: $outcome").that(outcome).isInstanceOf(StageOutcome.Finished::class.java)
    val reports = rig.nas.lastScan.value
    assertWithMessage("giri: $reports").that(reports.none { it.stoppedBy == ScanStop.UNAVAILABLE }).isTrue()
    assertThat(reports.sumOf { it.folders }).isGreaterThan(0)
    assertThat(reports.sumOf { it.detected }).isEqualTo(1)
    assertThat(phone.graph.nasClock.nowMillisOrNull()).isNotNull()
    // Il battito della verifica c'e': il riepilogo giornaliero non dira' "fermo da N giorni".
    assertThat(stages.scanBeat.lastBeatAt()).isNotNull()
  }

  /**
   * D-2 nei lavori periodici (prove sul campo, ripresa con `test`): la scansione falliva a **ogni** avvio del processo, anche in background, e la
   * scansione periodica di 6 ore parte di solito proprio a processo morto. Il lavoro periodico (`NasWorkScheduling.SCAN_WORK`) e quello "adesso"
   * sono lo stesso `NasScanWorker`: qui gira il lavoro vero, come lo lancia WorkManager, in un processo freddo. Apre la sessione prima di leggere
   * l'ora del NAS, finisce la scansione e in Diagnostica non lascia nessun "errore inatteso" (`EVT_JOB_FAILED`, prima "tappa non riuscita").
   */
  @Test
  fun d2_laScansionePeriodicaInUnProcessoFreddoApreLaSessioneEFinisceSenzaErroriInattesi() = runBlocking<Unit> {
    val ring = RingDiagnosticsLog()
    val first = device("telefono")
    first.graph.start()
    first.graph.foundFamily()
    backend.put(TestNas.COMMON, "Foto/2024/IMG_0001.jpg", BackupRig.jpeg(tone = 40), lastWriteMs = time.now() - 2 * DAY)
    first.close()
    val phone = TestDevice(app, File(temp.root, "telefono"), first.platform, flags, ring).also { devices += it }
    phone.graph.start()
    phone.graph.awaitSetup { it is SetupState.Ready }
    val rig = galleryOf(phone)
    assertWithMessage("l'ora del NAS non deve avere ancora un'ancora").that(phone.graph.nasClock.nowMillisOrNull()).isNull()

    val result = scanWorker(rig, phone).doWork()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    val reports = rig.nas.lastScan.value
    assertWithMessage("giri: $reports").that(reports.none { it.stoppedBy == ScanStop.UNAVAILABLE }).isTrue()
    assertThat(reports.sumOf { it.detected }).isEqualTo(1)
    assertThat(phone.graph.nasClock.nowMillisOrNull()).isNotNull()
    val events = ring.snapshot()
    assertWithMessage("eventi: $events").that(events.none { it.code == DiagCodes.EVT_JOB_FAILED }).isTrue()
  }

  /**
   * D-7 sui lavori del NAS: in un processo appena nato lo stato del dispositivo passa un attimo per "non configurato". Il lavoro della scansione
   * partito in quel momento rispondeva "niente da fare" (la galleria "non pronta") e finiva consumato; adesso aspetta la famiglia pronta e scansiona.
   */
  @Test
  fun d7_laScansioneInUnProcessoAppenaNatoAspettaLaFamigliaProntaENonVaPersa() = runBlocking<Unit> {
    val phone = coldPhone()
    val setup = ColdStartSetup(phone.graph.setup, phone.scope)
    val rig = galleryOf(phone, setup)
    setup.override.value = SetupState.NotConfigured
    setup.state.await { it == SetupState.NotConfigured }
    val settle = launch(Dispatchers.Default) {
      delay(300)
      setup.override.value = null
    }

    val result = scanWorker(rig, phone).doWork()
    settle.join()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    val reports = rig.nas.lastScan.value
    assertWithMessage("giri: $reports").that(reports.sumOf { it.detected }).isEqualTo(1)
  }

  /** Il lavoro `NasScanWorker` come lo crea WorkManager, sul grafo e sulla galleria del test. */
  private fun scanWorker(rig: BackupRig, phone: TestDevice): NasScanWorker = TestListenableWorkerBuilder<NasScanWorker>(app)
    .setWorkerFactory(
      object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
          NasScanWorker(appContext, workerParameters, rig.gallery, phone.graph)
      },
    )
    .build()

  @Test
  fun d2_conLaPasswordRifiutataLaScansioneSiFermaESenzaNuoviTentativi() = runBlocking<Unit> {
    val phone = coldPhone()
    // La password e' cambiata sul NAS mentre il telefono era spento: l'interruttore di sicurezza deve aprirsi al primo rifiuto e restare aperto.
    backend.changePassword(TestNas.ADMIN_USER, "un'altra password")
    val rig = galleryOf(phone)
    val stages = NasStages(phone.graph, rig.nas, phone.context.noBackupFilesDir)

    val first = stages.scan()
    val attempts = backend.authenticateCalls.get()
    val second = stages.scan()

    // Nessuna eccezione dalla tappa: la scansione dice "non raggiungibile" e finisce, come con un NAS che non risponde.
    assertWithMessage("esito: $first").that(first).isInstanceOf(StageOutcome.Finished::class.java)
    assertWithMessage("esito: $second").that(second).isInstanceOf(StageOutcome.Finished::class.java)
    assertThat(rig.nas.lastScan.value.all { it.stoppedBy == ScanStop.UNAVAILABLE }).isTrue()
    // Nessuna autenticazione nuova dopo il rifiuto, nemmeno dal giro dopo: l'interruttore e' aperto.
    assertThat(backend.authenticateCalls.get()).isEqualTo(attempts)
    val account = checkNotNull(phone.graph.account.value)
    assertThat(phone.graph.breakers.forAccount(account).current()).isInstanceOf(BreakerState.Open::class.java)
    assertThat(stages.scanBeat.lastBeatAt()).isNull()
  }

  @Test
  fun o2_unoScreenshotInUnaCartellaSceltaSiContaESiSalvaConLaCartella() = runBlocking<Unit> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER).also { rigs += it }
    val shotInFolder = BackupRig.jpeg(tone = 40)
    val photoInFolder = BackupRig.jpeg(tone = 90)
    val shotElsewhere = BackupRig.jpeg(tone = 140)
    BackupRig.photo(rig.store, 1, shotInFolder, time.now() - HOUR, folder = FOLDER, name = "Screenshot_20240312_101530_Chrome.jpg")
    BackupRig.photo(rig.store, 2, photoInFolder, time.now() - 2 * HOUR, folder = FOLDER, name = "IMG_20240312_101531.jpg")
    BackupRig.photo(rig.store, 3, shotElsewhere, time.now() - 3 * HOUR, folder = "Pictures/Screenshots/", name = "Screenshot_20240312_101532.jpg")
    val backup = rig.open()
    rig.chooseFolder("Pixel di prova")

    // "Cosa salvare": la cartella conta anche lo screenshot; la cartella degli screenshot ha il suo interruttore e non si ripete.
    val folders = checkNotNull(rig.gallery.backupUi).phoneFolders.folders()
    assertThat(folders.map { (it.source as BackupSource.Folder).relativePath to it.media }).containsExactly(FOLDER to 2)

    // La sorgente Screenshot accesa insieme alla cartella: lo screenshot della cartella va con la cartella, l'altro in `Screenshot/`.
    backup.settings.awaitLoaded()
    backup.settings.setSources(BackupSources(setOf(BackupSource.Screenshots, BackupSource.Folder(BackupRig.PRIMARY, FOLDER))))
    val last = checkNotNull(checkNotNull(rig.round()) { "il giro non e' partito" }.last)
    assertWithMessage("giro: $last").that(last.done).isEqualTo(3)
    assertThat(last.stoppedBy).isEqualTo(RunStop.COMPLETE)
    val where = filesUnder(backend, TestNas.ADMIN_USER, "Backup").associateBy { backend.bytes(TestNas.ADMIN_USER, it)!!.toList() }
    assertWithMessage("percorsi: ${where.values}").that(where[shotInFolder.toList()]).startsWith("Backup/Pixel di prova/mcg-prova/")
    assertThat(where[photoInFolder.toList()]).startsWith("Backup/Pixel di prova/mcg-prova/")
    assertThat(where[shotElsewhere.toList()]).startsWith("Backup/Pixel di prova/Screenshot/")
  }

  private companion object {
    const val FOLDER = "DCIM/mcg-prova/"
    const val HOUR = 60L * 60 * 1000
    const val DAY = 24L * 60 * 60 * 1000
    val TIMEOUT_MS = Attese.TETTO_MS
  }
}
