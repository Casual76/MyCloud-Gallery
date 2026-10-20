package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.backup.BackupRunMemory
import dev.pampa.mycloudgallery.backup.FiveBGraph
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.AppLocalDataEraser
import dev.pampa.mycloudgallery.graph.AppPreviousAccountData
import dev.pampa.mycloudgallery.graph.snapshotOrNull
import dev.pampa.mycloudgallery.nas.NasGraph
import dev.pampa.mycloudgallery.nas.NasOrganizer
import dev.pampa.mycloudgallery.root.ProcessRestart
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R6 della revisione di sicurezza della Fase 5B (`docs/review/5b-fase5b.md`) sul grafo **vero**: dopo "Configura di nuovo" con un
 * altro account i dati del vecchio non restano sul telefono; con lo stesso account sullo stesso NAS restano (ON-4, ON-5). E
 * l'azzeramento dei dati di un account non tocca l'onboarding in corso, l'account, la casa, e toglie anche i file della 5B.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CorrezioniConfigurazione5BAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()

  /** Il NAS di prova con un terzo account, `test3`: un'altra persona della famiglia. */
  private val backend: InMemorySmbBackend = InMemorySmbBackend(nasTime = time::now).apply {
    addUser(TestNas.ADMIN_USER, TestNas.ADMIN_PASSWORD)
    addUser(TestNas.MEMBER_USER, TestNas.MEMBER_PASSWORD)
    addUser(OTHER_USER, OTHER_PASSWORD)
    addShare(
      TestNas.COMMON,
      mapOf(TestNas.ADMIN_USER to SimAccess.READ_WRITE, TestNas.MEMBER_USER to SimAccess.READ_WRITE, OTHER_USER to SimAccess.READ_WRITE),
    )
    addShare(TestNas.ADMIN_USER, mapOf(TestNas.ADMIN_USER to SimAccess.READ_WRITE))
    addShare(TestNas.MEMBER_USER, mapOf(TestNas.MEMBER_USER to SimAccess.READ_WRITE))
    addShare(OTHER_USER, mapOf(OTHER_USER to SimAccess.READ_WRITE))
  }

  private fun device(name: String, platform: TestPlatform = TestPlatform(time, backend), restart: ProcessRestart = ProcessRestart {}): TestDevice =
    TestDevice(app, File(temp.root, name), platform, restart = restart).also { devices += it }

  @After
  fun tearDown() {
    devices.forEach { it.close() }
  }

  private suspend fun until(condition: () -> Boolean) {
    withTimeout(15.seconds) { while (!condition()) delay(20) }
  }

  /** I dati del telefono che appartengono a un account: file e cartelle di ogni parte della 5B, e le impostazioni del backup. */
  private suspend fun seedAccountData(device: TestDevice): List<File> {
    val noBackup = device.context.noBackupFilesDir
    val files = listOf(
      File(File(noBackup, "replica"), "timeline.idx"),
      File(File(noBackup, NasOrganizer.PERSONAL_DIR), NasOrganizer.PERSONAL_LEDGER_FILE),
      File(File(noBackup, AppLocalDataEraser.VERSIONS_DIR), "preparato.jpg"),
      File(File(noBackup, AppLocalDataEraser.INCOMING_DIR), "ricevuto/foto.jpg"),
      File(File(noBackup, "thumbnails"), "m1.webp"),
      File(File(noBackup, "previews"), "m1.webp"),
      File(File(noBackup, NasGraph.OUTBOX_DIR), "m0000"),
      File(noBackup, BackupRunMemory.FILES.first()),
      File(File(device.context.cacheDir, "share"), "copia.jpg"),
    )
    files.forEach { it.parentFile!!.mkdirs(); it.writeText("dati") }
    device.graph.backupSettingsStorage.update { it.copy(deviceFolder = "Pixel di prova") }
    return files
  }

  /** Il familiare entra con il QR dell'admin come [user] e arriva in attesa dell'approvazione (come `joinWith`, per un account qualunque). */
  private suspend fun AppGraph.joinAs(user: String, password: String, invite: FamilyInvite) {
    awaitSetup { it == SetupState.NotConfigured }
    settle(coordinator.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    assertThat(joinFlow.start(invite)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(joinFlow.authenticate(password.toCharArray())).isInstanceOf(JoinStep.NeedsName::class.java)
    assertThat(joinFlow.register("Tablet di $user")).isInstanceOf(JoinStep.AwaitingApproval::class.java)
    coordinator.progress.await { it.current == OnboardingStep.ROLES }
    localSteps(until = OnboardingStep.SUMMARY)
  }

  /** Un tablet di `test2` approvato, pronto, con i dati di quell'account sul telefono; poi revocato e "Configura di nuovo". */
  private suspend fun revokedAndStartedOver(phone: TestDevice, restarts: IntArray): Pair<TestDevice, List<File>> {
    phone.graph.start()
    phone.graph.foundFamily()
    val tablet = device("tablet", restart = { restarts[0]++ })
    val t = tablet.graph
    t.start()
    t.joinWith(phone.graph.inviteMember())
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    val id = t.engine.myDeviceId()!!
    val code = (t.registry.status.value as TrustStatus.Pending).code
    assertThat(phone.graph.registry.approve(id, ApprovalProof.TypedCode(code))).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    t.awaitSetup { it is SetupState.Ready }
    until { t.galleryMayRun() }
    val seeded = seedAccountData(tablet)
    assertThat(phone.graph.registry.changeStatus(id, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    t.awaitSetup { it == SetupState.NotConfigured }

    assertThat(t.coordinator.startOver()).isInstanceOf(StepResult.Advanced::class.java)
    return tablet to seeded
  }

  @Test
  fun r6_conUnAltroAccountIDatiDelVecchioNonRestanoSulTelefono() = runBlocking<Unit> {
    val phone = device("telefono")
    val restarts = intArrayOf(0)
    val (tablet, seeded) = revokedAndStartedOver(phone, restarts)

    // Il tablet aveva la Libreria viva in questo processo: "Configura di nuovo" lo riavvia (la galleria non riparte, e il
    // catalogo avrebbe ancora il proprietario di prima). Fin qui i dati ci sono ancora.
    assertThat(restarts[0]).isEqualTo(1)
    seeded.forEach { assertThat(it.exists()).isTrue() }
    assertThat(tablet.graph.onboardingStore.snapshotOrNull()!!.previousOwner).isNotNull()

    // Il processo nuovo: stessi file, stesse chiavi del telefono (il Keystore), un grafo nuovo.
    tablet.close()
    var secondRestarts = 0
    val again = device("tablet", tablet.platform) { secondRestarts++ }
    val a = again.graph
    a.start()
    a.awaitSetup { it == SetupState.NotConfigured }
    assertThat(a.galleryMayRun()).isFalse()
    seeded.forEach { assertThat(it.exists()).isTrue() }

    // Un'altra persona entra con il suo account: fino a "Vai alla Libreria" i dati non si toccano, poi si tolgono.
    a.joinAs(OTHER_USER, OTHER_PASSWORD, phone.graph.registry.createInvite(InviteParams(OTHER_USER, "Tablet di prova", OTHER_USER, presetName = null)))
    seeded.forEach { assertThat(it.exists()).isTrue() }
    a.settle(a.coordinator.submit(StepInput.Finish))
    a.awaitSetup { it is SetupState.AwaitingApproval }

    seeded.forEach { assertThat(it.exists()).isFalse() }
    assertThat(a.backupSettingsStorage.data.first().deviceFolder).isNull()
    // L'onboarding finito, l'account nuovo e il ricordo di chi c'era prima tolto; nessun riavvio (la galleria non era partita).
    val snapshot = a.onboardingStore.snapshotOrNull()!!
    assertThat(snapshot.finished).isTrue()
    assertThat(snapshot.nasUser).isEqualTo(OTHER_USER)
    assertThat(snapshot.previousOwner).isNull()
    assertThat(a.account.first()?.user).isEqualTo(OTHER_USER)
    assertThat(secondRestarts).isEqualTo(0)
  }

  @Test
  fun r6_conLoStessoAccountSulloStessoNasIDatiRestano() = runBlocking<Unit> {
    val phone = device("telefono")
    val restarts = intArrayOf(0)
    val (tablet, seeded) = revokedAndStartedOver(phone, restarts)
    assertThat(restarts[0]).isEqualTo(1)

    tablet.close()
    val again = device("tablet", tablet.platform)
    val a = again.graph
    a.start()
    a.joinWith(phone.graph.inviteMember())

    // La stessa persona (test2) sullo stesso NAS: ON-4, ON-5, restano nascosti, preferiti, miniature e catalogo.
    seeded.forEach { assertThat(it.exists()).isTrue() }
    assertThat(a.onboardingStore.snapshotOrNull()!!.finished).isTrue()
    assertThat(a.onboardingStore.snapshotOrNull()!!.previousOwner).isNull()
  }

  @Test
  fun r6_ConfiguraDiNuovoSenzaLibreriaVivaNonRiavviaIlProcesso() = runBlocking<Unit> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    var restarts = 0
    // Un dispositivo che in questo processo non e' mai stato pronto: la galleria non e' partita, non c'e' niente da riavviare.
    val tablet = device("tablet", restart = { restarts++ })
    val t = tablet.graph
    t.start()
    t.joinWith(phone.graph.inviteMember())
    assertThat(t.galleryMayRun()).isFalse()

    assertThat(t.coordinator.startOver()).isInstanceOf(StepResult.Advanced::class.java)
    assertThat(restarts).isEqualTo(0)
  }

  @Test
  fun eraseAccountData_togliIDatiDiUnAccountEUnicamenteQuelli() = runBlocking<Unit> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val g = phone.graph
    val seeded = seedAccountData(phone)
    val snapshotBefore = g.onboardingStore.snapshotOrNull()!!
    val accountBefore = g.account.first()

    val eraser = AppPreviousAccountData(AppLocalDataEraser(phone.context, g), g)
    assertThat(eraser.erase()).isTrue()

    seeded.forEach { assertThat(it.exists()).isFalse() }
    // Mai l'onboarding, l'account, la casa, le chiavi: sono della persona che sta entrando.
    assertThat(g.onboardingStore.snapshotOrNull()).isEqualTo(snapshotBefore)
    assertThat(g.account.first()).isEqualTo(accountBefore)
    assertThat(phone.platform.keys.hasDeviceKey()).isTrue()
    assertThat(g.homeStore.fingerprint.value).isNotNull()
    // L'azzeramento intero invece le toglie, e toglie anche i file della 5B (prima restavano).
    val again = seedAccountData(phone)
    assertThat(g.eraser.eraseAll()).isTrue()
    again.forEach { assertThat(it.exists()).isFalse() }
    assertThat(g.account.first()).isNull()
  }

  /** I nomi delle due cartelle della 5B nell'elenco dell'azzeramento sono quelli veri di `FiveBGraph` (le sue costanti sono private: si leggono per riflessione). */
  @Test
  fun iNomiDelleCartelleDellaCinqueBNellAzzeramentoSonoQuelliDelGrafo() {
    fun constant(name: String): String = FiveBGraph::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(null) as String
    assertThat(AppLocalDataEraser.VERSIONS_DIR).isEqualTo(constant("VERSIONS_DIR"))
    assertThat(AppLocalDataEraser.INCOMING_DIR).isEqualTo(constant("INCOMING_DIR"))
  }

  private companion object {
    const val OTHER_USER = "test3"
    const val OTHER_PASSWORD = "Chiave-Di-Prova-3"
  }
}
