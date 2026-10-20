package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.work.BackupJobEnd
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 * I difetti del backup trovati sul telefono vero nella ripresa delle prove sul campo con l'account `test` (`docs/prove-campo-esiti.md`,
 * "Ripresa con `test`"), sul grafo **vero** dell'app con il simulatore SMB. Il lavoro dell'utente e' quello di `BackupJobService`
 * (`BackupRig.userJob`, cioe' `userBackupRun`).
 *
 * D-6: dopo una caduta del Wi-Fi il lavoro dell'utente, rilanciato da Android sui dati mobili, finiva `DONE` in 2 secondi e niente lo faceva
 * ripartire al ritorno del Wi-Fi (fino al giro periodico, 6 ore).
 *
 * D-7: dopo la morte del processo il lavoro dell'utente ripartiva in un processo appena nato e a volte finiva `DONE` in 0,4 secondi senza fare
 * niente: lo stato del dispositivo passava un attimo per "non configurato" e la galleria rispondeva "non pronta".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class ProveCampoBackupAppTest {

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

  private suspend fun phone(): TestDevice {
    val phone = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, backend), flags).also { devices += it }
    phone.graph.start()
    phone.graph.foundFamily()
    return phone
  }

  private fun backedUpFiles(): List<String> = filesUnder(backend, TestNas.ADMIN_USER, "Backup")

  /**
   * D-6: il Wi-Fi se ne va e restano i dati mobili. Il lavoro dell'utente non finisce (sui dati mobili non carica senza la conferma): aspetta,
   * e quando torna il Wi-Fi di casa riparte da solo e porta la foto al sicuro, senza un altro tocco.
   */
  @Test
  fun d6_cadutoIlWifiIlLavoroDellUtenteAspettaERiparteDaSoloAlRitorno() = runBlocking<Unit> {
    val phone = phone()
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER).also { rigs += it }
    BackupRig.photo(rig.store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - HOUR)
    rig.open()
    rig.chooseFolder("Pixel di prova")

    phone.platform.network.set(LinkFactsSamples.observation(LinkFactsSamples.cellular()))
    phone.graph.environment.snapshot.await { it.place != NetPlace.HOME && it.place != NetPlace.OFFLINE }
    val job = async(Dispatchers.Default) { rig.userJob() }
    delay(1_500)
    assertWithMessage("il lavoro dell'utente e' finito sui dati mobili invece di aspettare il Wi-Fi").that(job.isCompleted).isFalse()
    assertThat(backedUpFiles()).isEmpty()

    // Torna il Wi-Fi di casa: il lavoro riparte da solo.
    phone.platform.network.set(LinkFactsSamples.observation(LinkFactsSamples.homeWifi()))
    val end = withTimeout(TIMEOUT_MS) { job.await() }

    assertThat(end).isEqualTo(BackupJobEnd.DONE)
    assertThat(backedUpFiles()).hasSize(1)
  }

  /**
   * D-7: il processo appena nato vede per un attimo il dispositivo "non configurato" (la fiducia letta dal file arriva allo stato della famiglia
   * un istante dopo che l'avvio l'ha dichiarato finito). Il lavoro dell'utente rilanciato in quel momento non deve finire "niente da fare": aspetta
   * che il dispositivo sia pronto e carica.
   */
  @Test
  fun d7_ilLavoroRilanciatoInUnProcessoAppenaNatoAspettaCheSiaProntoENonFinisceAVuoto() = runBlocking<Unit> {
    val phone = phone()
    val setup = ColdStartSetup(phone.graph.setup, phone.scope)
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER, setup = setup).also { rigs += it }
    BackupRig.photo(rig.store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - HOUR)
    rig.open()
    rig.chooseFolder("Pixel di prova")

    // Il passaggio per "non configurato" dura 300 ms dall'avvio del lavoro.
    setup.override.value = SetupState.NotConfigured
    setup.state.await { it == SetupState.NotConfigured }
    val settle = launch(Dispatchers.Default) {
      delay(300)
      setup.override.value = null
    }
    val end = withTimeout(TIMEOUT_MS) { rig.userJob() }
    settle.join()

    assertThat(end).isEqualTo(BackupJobEnd.DONE)
    assertWithMessage("il lavoro e' finito senza caricare la foto").that(backedUpFiles()).hasSize(1)
  }

  /**
   * Seguito di O-2: nella pagina Backup una foto sta in una riga sola. Uno screenshot dentro una cartella scelta e' della cartella (come nel piano),
   * non anche della riga Screenshot: prima la riga Screenshot contava per tipo anche i media della cartella, e quelli comparivano due volte.
   */
  @Test
  fun o2_unoScreenshotDellaCartellaSceltaStaInUnaRigaSolaDellaPagina() = runBlocking<Unit> {
    val phone = phone()
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER).also { rigs += it }
    BackupRig.photo(rig.store, 1, BackupRig.jpeg(tone = 40), time.now() - HOUR, folder = FOLDER, name = "Screenshot_20240312_101530_Chrome.jpg")
    BackupRig.photo(rig.store, 2, BackupRig.jpeg(tone = 90), time.now() - 2 * HOUR, folder = FOLDER, name = "IMG_20240312_101531.jpg")
    BackupRig.photo(rig.store, 3, BackupRig.jpeg(tone = 140), time.now() - 3 * HOUR, folder = "Pictures/Screenshots/", name = "Screenshot_20240312_101532.jpg")
    val backup = rig.open()
    backup.settings.awaitLoaded()
    val folder = BackupSource.Folder(BackupRig.PRIMARY, FOLDER)
    backup.settings.setSources(BackupSources(setOf(BackupSource.Screenshots, folder)))
    backup.kick()

    val rows = backup.sources.await { rows -> rows.any { it.source == folder && it.media == 2 } }
    val bySource = rows.associate { it.source to it.media }
    assertWithMessage("righe: $bySource").that(bySource[BackupSource.Screenshots]).isEqualTo(1)
    assertThat(bySource[folder]).isEqualTo(2)
    // Ogni media in una riga sola: le righe attive sommano i media delle sorgenti scelte.
    assertThat(rows.filter { it.active }.sumOf { it.media }).isEqualTo(3)
  }

  /**
   * D-9: con i media in coda e nessun giro ancora partito la pagina diceva "296 in coda · Da caricare: 0 B": i byte venivano solo dal diario, che
   * ha gli elementi di un giro alla volta. "Da caricare" sono i byte dei media in coda sul catalogo.
   */
  @Test
  fun d9_daCaricareSonoIByteDeiMediaInCodaNonQuelliDelDiario() = runBlocking<Unit> {
    val phone = phone()
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER).also { rigs += it }
    val first = BackupRig.jpeg(tone = 40)
    val second = BackupRig.jpeg(tone = 90)
    BackupRig.photo(rig.store, id = 1, bytes = first, takenAt = time.now() - HOUR)
    BackupRig.photo(rig.store, id = 2, bytes = second, takenAt = time.now() - 2 * HOUR)
    val backup = rig.open()
    rig.chooseFolder("Pixel di prova")
    backup.kick()

    val waiting = backup.status.await { it is BackupStatus.Waiting } as BackupStatus.Waiting
    assertThat(waiting.items).isEqualTo(2)
    assertThat(waiting.bytes).isEqualTo((first.size + second.size).toLong())
  }

  private companion object {
    const val FOLDER = "DCIM/mcg-prova/"
    const val HOUR = 60L * 60 * 1000
    const val TIMEOUT_MS = 60_000L
  }
}

/**
 * D-7: lo stato del dispositivo di [real], con un passaggio di prova ([override]) come quello di un processo appena nato, che vede per un attimo
 * "non configurato" prima di "pronto".
 */
internal class ColdStartSetup(real: FamilySetupRepository, scope: CoroutineScope) : FamilySetupRepository {
  val override = MutableStateFlow<SetupState?>(null)
  override val state: StateFlow<SetupState> =
    combine(real.state, override) { actual, passing -> passing ?: actual }.stateIn(scope, SharingStarted.Eagerly, real.state.value)
}
