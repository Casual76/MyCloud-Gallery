package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.nas.NasInputs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **D13** (Fase 5A, P7; docs/fase5-progetto.md §0.1, §5.3): la recinzione del telefono del backup con gli account di prova, in debug. Con
 * l'account `test` il backup **legge soltanto** le righe di `DCIM/mcg-prova/` e `Pictures/mcg-prova/` e **scrive soltanto** sotto
 * `mcg-campo/Backup/` della share di prova: una foto vera della famiglia (qui `DCIM/Camera/`) non viene mai letta, caricata, ne' messa
 * nel diario, e nulla compare in `Backup/` della radice. In release `DeveloperTools.backupFence()` risponde null e la classe non esiste
 * (tools/check.ps1 lo cerca nel dex); questo test sta in `src/testDebug` perche' prova il codice di debug.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class BackupFenceIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  @Test
  fun conUnAccountDiProvaSiLeggonoSoloLeCartelleDiProvaESiScriveSoloSottoMcgCampo() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val phone = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, backend)).also { devices += it }
    phone.graph.start()
    phone.graph.foundFamily()

    val fence = checkNotNull(DeveloperTools.backupFence()) { "in debug la recinzione c'e'" }
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER, fence = fence).also { rigs += it }
    val seeded1 = BackupRig.jpeg(tone = 30)
    val seeded2 = BackupRig.jpeg(tone = 90)
    val family = BackupRig.jpeg(tone = 200)
    BackupRig.photo(rig.store, id = 1, bytes = seeded1, takenAt = time.now() - 3 * HOUR, folder = "DCIM/mcg-prova/")
    BackupRig.photo(rig.store, id = 2, bytes = seeded2, takenAt = time.now() - 2 * HOUR, folder = "Pictures/mcg-prova/")
    // Una foto vera della famiglia: nella cartella della fotocamera. Con la recinzione non si legge, non si carica, non entra nel diario.
    BackupRig.photo(rig.store, id = 3, bytes = family, takenAt = time.now() - 1 * HOUR, folder = "DCIM/Camera/")

    val backup = rig.open()
    // L'utente sceglie tutto (la fotocamera e le due cartelle di prova): la recinzione restringe alle sole cartelle di prova.
    backup.settings.setSources(
      BackupSources(
        setOf(
          BackupSource.Camera,
          BackupSource.Folder(BackupRig.PRIMARY, "DCIM/mcg-prova/"),
          BackupSource.Folder(BackupRig.PRIMARY, "Pictures/mcg-prova/"),
          BackupSource.Folder(BackupRig.PRIMARY, "DCIM/Camera/"),
        ),
      ),
    )
    assertThat(backup.settings.sources.value.sources).containsExactly(
      BackupSource.Folder(BackupRig.PRIMARY, "DCIM/mcg-prova/"),
      BackupSource.Folder(BackupRig.PRIMARY, "Pictures/mcg-prova/"),
    )
    rig.chooseFolder("Pixel di prova")

    // La porta del telefono: la riga della famiglia non si apre nemmeno (fallire chiusi), le due di prova si'.
    val version = rig.store.version(BackupRig.PRIMARY)
    assertThat(backup.phone.open(PhoneRef(BackupRig.PRIMARY, 3, version))).isEqualTo(PhoneOpen.Denied(PhoneDenied.NOT_SELECTED))
    val opened = backup.phone.open(PhoneRef(BackupRig.PRIMARY, 1, version))
    assertThat(opened).isInstanceOf(PhoneOpen.Opened::class.java)
    (opened as PhoneOpen.Opened).file.close()

    // Un giro: carica le due foto di prova e solo quelle.
    assertThat(backup.gate.stateOf(TestNas.ADMIN_USER)).isEqualTo(BackupGateState.OPEN)
    val outcome = checkNotNull(rig.round()) { "il giro non e' partito" }
    val last = checkNotNull(outcome.last) { "tappa: ${outcome.stage}" }
    assertWithMessage("giro: $last").that(last.done).isEqualTo(2)
    assertThat(last.stoppedBy).isEqualTo(RunStop.COMPLETE)

    // Scritto soltanto sotto mcg-campo/Backup/: nulla in Backup/ della radice, nulla in giro.
    val stored = filesUnder(backend, TestNas.ADMIN_USER, "mcg-campo/Backup")
    assertThat(stored).hasSize(2)
    assertThat(stored.map { backend.bytes(TestNas.ADMIN_USER, it)!!.toList() }.toSet())
      .containsExactly(seeded1.toList(), seeded2.toList())
    assertThat(filesUnder(backend, TestNas.ADMIN_USER, "Backup")).isEmpty()
    assertThat(backend.exists(TestNas.ADMIN_USER, "Backup")).isFalse()
    assertThat(filesUnder(backend, TestNas.COMMON, "Backup")).isEmpty()
    assertThat(stored.none { backend.bytes(TestNas.ADMIN_USER, it)!!.contentEquals(family) }).isTrue()
    // E il diario non ha mai conosciuto la foto della famiglia: due elementi in tutto.
    val diary = backup.store.counts()
    assertThat(diary.byState.values.sum()).isEqualTo(2)

    // L'elenco delle cartelle da scegliere (Impostazioni → Backup) mostra solo le cartelle di prova.
    val folders = rig.gallery.backupUi!!.phoneFolders.folders().map { (it.source as BackupSource.Folder).relativePath }
    assertThat(folders).containsExactly("DCIM/mcg-prova/", "Pictures/mcg-prova/")
  }

  /**
   * **BK-14**: l'account NAS arriva **dopo** le impostazioni del backup. Il magazzino si legge una volta sola all'avvio e la recinzione guardava
   * l'account in quel momento: senza account fallisce chiusa (nessuna sorgente) e le sorgenti restavano vuote per tutto il processo, quindi nessuna
   * prova sul campo (F21 e seguenti) caricava niente. Adesso `BackupGraph.start` le ricalcola con l'account di adesso: `DCIM/mcg-prova/` c'e'.
   */
  @Test
  fun bk14_seLAccountArrivaDopoLeImpostazioniLeSorgentiDiProvaCiSono() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val phone = TestDevice(app, File(temp.root, "telefono-tardi"), TestPlatform(time, backend)).also { devices += it }
    phone.graph.start()
    phone.graph.foundFamily()
    val real = phone.graph.nasInputs
    var account: String? = null
    val late = NasInputs(
      real.flags, real.session, real.nasClock, { account }, real.shares, real.commonExclusions, real.deviceId, real.signer, real.registry,
      real.trust, real.route, real.rules, real.stateProvider, real.ledger, real.metered, real.freezeSignal, real.lastDiskTouchAt,
    )
    val fence = checkNotNull(DeveloperTools.backupFence()) { "in debug la recinzione c'e'" }
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER, fence = fence, nasInputs = late).also { rigs += it }
    val settings = rig.backup.settings
    settings.awaitLoaded()
    settings.setSources(BackupSources(setOf(BackupSource.Camera, BackupSource.Folder(BackupRig.PRIMARY, "DCIM/mcg-prova/"))))
    // L'archivio degli account non ha ancora risposto: fallire chiusi, nessuna sorgente.
    assertThat(settings.sources.value).isEqualTo(BackupSources.NONE)

    account = TestNas.ADMIN_USER
    rig.open()

    assertThat(settings.sources.value.sources).containsExactly(BackupSource.Folder(BackupRig.PRIMARY, "DCIM/mcg-prova/"))
    // Il valore scelto resta quello salvato, intero: la recinzione restringe e basta.
    assertThat(settings.chosenSources.value.sources).containsExactly(BackupSource.Camera, BackupSource.Folder(BackupRig.PRIMARY, "DCIM/mcg-prova/"))
  }

  private companion object {
    const val HOUR = 3_600_000L
  }
}
