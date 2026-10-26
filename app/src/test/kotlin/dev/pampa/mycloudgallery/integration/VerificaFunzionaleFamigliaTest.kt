package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.ResetOutcome
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.invite.FamilyStateCodec
import dev.pampa.mycloudgallery.core.family.invite.InviteCodec
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingViewModel
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.snapshotOrNull
import dev.pampa.mycloudgallery.root.GraphRootPorts
import dev.pampa.mycloudgallery.work.TrustSyncOutcome
import dev.pampa.mycloudgallery.work.TrustSyncRun
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La verifica funzionale delle fasi 1-5A (`docs/review/funzionale-fasi-1-5a.md`, voci ON) sul grafo **vero** dell'app,
 * con il simulatore SMB: i percorsi di famiglia che prima erano provati solo con i finti. Dove c'e' una pagina, la guida il
 * suo ViewModel vero (`OnboardingViewModel` sopra `AppGraph`), come nell'app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VerificaFunzionaleFamigliaTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()

  private fun device(name: String): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend)).also { devices += it }

  @Before
  fun setUp() {
    // I ViewModel lavorano sul thread principale: qui sul thread del test, fino alla prima sospensione.
    Dispatchers.setMain(Dispatchers.Unconfined)
  }

  @After
  fun tearDown() {
    devices.forEach { it.close() }
    Dispatchers.resetMain()
  }

  /** Il ViewModel dell'onboarding con le parti vere del dispositivo (quelle che Hilt gli da' nell'app). */
  private fun viewModelOf(device: TestDevice): OnboardingViewModel {
    val g = device.graph
    return OnboardingViewModel(
      coordinator = g.coordinator,
      joinFlow = g.joinFlow,
      adminRecovery = g.adminRecovery,
      inviteCodec = InviteCodec,
      familyStateCodec = FamilyStateCodec,
      vpnController = g.wireGuard,
      healthReader = device.platform.health,
      localNetwork = device.platform.localNetworkPermission,
      vpnConfig = g.vpnConfigStore,
      registry = g.registry,
    )
  }

  private fun AppGraph.snapshot(): OnboardingSnapshot = onboardingStore.snapshotOrNull()!!

  /** L'admin vede la richiesta (la finestra di 15 minuti parte da qui) e la approva con il codice del dispositivo. */
  private suspend fun approve(admin: AppGraph, member: AppGraph): AdminActionResult {
    admin.registry.refresh(Purpose.INTERACTIVE)
    val id = member.engine.myDeviceId()!!
    val code = (member.registry.status.value as TrustStatus.Pending).code
    return admin.registry.approve(id, ApprovalProof.TypedCode(code))
  }

  // --- ON-1: "Entra senza QR" dalla pagina vera fino alla richiesta ---------------------------------------------------

  @Test
  fun on1_entraSenzaQrDalViewModelVeroFinoAllaRichiestaInAttesa() = runBlocking<Unit> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    // L'admin legge i dati a voce dalla pagina "Configura un familiare": id e impronta come li scrive una persona.
    val invite = phone.graph.inviteMember()

    val tablet = device("tablet")
    val t = tablet.graph
    t.start()
    t.awaitSetup { it == SetupState.NotConfigured }
    val vm = viewModelOf(tablet)
    vm.choosePath(OnboardingPath.JOIN_QR)
    until("il percorso e' l'ingresso con il QR") { t.coordinator.progress.value.path == OnboardingPath.JOIN_QR }
    // Dalla scansione, "Inserisci a mano": il percorso diventa quello senza QR.
    vm.joinEnterManually()
    until("il percorso e' l'ingresso a mano") { t.coordinator.progress.value.path == OnboardingPath.JOIN_MANUAL }
    vm.confirmManualHome(true)
    until("la casa e' confermata") { t.snapshot().homeConfirmed != null }
    vm.submitManualJoin(
      ip = TestNas.IP,
      user = TestNas.MEMBER_USER,
      familyId = invite.familyId.uppercase().chunked(4).joinToString("-"),
      fingerprint = invite.familyKeyFingerprint.uppercase().chunked(4).joinToString(" "),
      deviceName = "Tablet di prova",
      commonShare = TestNas.COMMON,
    )
    until("il NAS e' nell'istantanea") { t.snapshot().nas != null }
    vm.submitJoinPassword(TestNas.MEMBER_PASSWORD.toCharArray())
    vm.submitJoinName("Tablet di prova")
    until("la richiesta e' registrata") { t.snapshot().join?.registered == true }
    // Dopo la richiesta il registro si rilegge: il tablet e' "in attesa".
    until("il tablet e' in attesa dell'approvazione") { t.registry.status.value is TrustStatus.Pending }
    assertThat(t.snapshot().join!!.manual).isTrue()
    assertThat(t.snapshot().privateShare).isEqualTo(TestNas.MEMBER_USER)
    // L'admin vede la richiesta e la approva: il tablet entra.
    assertThat(approve(phone.graph, t)).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    assertThat(t.registry.status.value).isEqualTo(TrustStatus.Approved(admin = false))
  }

  // --- ON-2: richiesta scaduta, si rifa' dalla pagina d'attesa -------------------------------------------------------

  @Test
  fun on2_dopoSediciMinutiIlTabletRifaLaRichiestaEVieneApprovato() = runBlocking<Unit> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val invite = phone.graph.inviteMember()
    val tablet = device("tablet")
    val t = tablet.graph
    t.start()
    t.joinWith(invite)
    val firstCode = (t.registry.status.value as TrustStatus.Pending).code

    // L'admin vede la richiesta tardi: 16 minuti dopo la finestra e' chiusa.
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    time.advance(SIXTEEN_MINUTES)
    val stale = t.engine.myDeviceId()!!
    assertThat(phone.graph.registry.approve(stale, ApprovalProof.TypedCode(firstCode))).isEqualTo(AdminActionResult.WindowExpired)

    // "Rifai la richiesta" dalla pagina d'attesa: lo stesso QR, la password, il nome; una chiave nuova.
    val vm = viewModelOf(tablet)
    vm.startRenewal()
    vm.onScanned(InviteCodec.encode(invite))
    vm.continueJoin()
    vm.submitJoinPassword(TestNas.MEMBER_PASSWORD.toCharArray())
    vm.submitJoinName("Tablet di prova")
    until("la richiesta rifatta ha un codice nuovo") { (t.registry.status.value as? TrustStatus.Pending)?.code.let { it != null && it != firstCode } }
    assertThat(t.engine.myDeviceId()).isNotEqualTo(stale)
    assertThat(t.snapshot().finished).isTrue()

    assertThat(approve(phone.graph, t)).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    assertThat(t.awaitSetup { it is SetupState.Ready }).isEqualTo(SetupState.Ready(admin = false))
  }

  // --- ON-4: revocato, poi "Configura di nuovo" ---------------------------------------------------------------------

  @Test
  fun on4_unTabletRevocatoSiConfiguraDiNuovoERientraConUnaChiaveNuova() = runBlocking<Unit> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val tablet = device("tablet")
    val t = tablet.graph
    t.start()
    t.joinWith(phone.graph.inviteMember())
    assertThat(approve(phone.graph, t)).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    t.awaitSetup { it is SetupState.Ready }
    val oldId = t.engine.myDeviceId()!!

    assertThat(phone.graph.registry.changeStatus(oldId, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    assertThat(t.registry.status.value).isEqualTo(TrustStatus.Revoked)
    // Prima restava qui: "non configurato" con l'onboarding finito, cioe' il vecchio riepilogo senza uscita.
    assertThat(t.awaitSetup { it !is SetupState.Ready }).isEqualTo(SetupState.NotConfigured)
    assertThat(t.snapshot().finished).isTrue()

    assertThat(t.coordinator.startOver()).isInstanceOf(StepResult.Advanced::class.java)
    assertThat(t.snapshot().path).isNull()
    assertThat(tablet.platform.keys.hasDeviceKey()).isFalse()
    assertThat(t.account.await { it == null }).isNull()
    assertThat(t.registry.status.value).isEqualTo(TrustStatus.NotConfigured)

    // Rientra con un invito nuovo: un id nuovo, da approvare di nuovo.
    t.joinWith(phone.graph.inviteMember())
    assertThat(t.engine.myDeviceId()).isNotEqualTo(oldId)
    assertThat(approve(phone.graph, t)).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    assertThat(t.awaitSetup { it is SetupState.Ready }).isEqualTo(SetupState.Ready(admin = false))
  }

  // --- ON-5: dopo il reset della famiglia, "Rientra con un QR nuovo" -----------------------------------------------------

  @Test
  fun on5_dopoIlResetUnFamiliareRientraNellaFamigliaNuova() = runBlocking<Unit> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val tablet = device("tablet")
    val t = tablet.graph
    t.start()
    t.joinWith(phone.graph.inviteMember())
    assertThat(approve(phone.graph, t)).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    t.awaitSetup { it is SetupState.Ready }
    val oldFamily = t.snapshot().join!!.familyId

    val reset = phone.graph.familyReset.reset(confirmedTwice = true)
    assertThat(reset).isInstanceOf(ResetOutcome.Done::class.java)
    // Il tablet resta nella famiglia vecchia senza saperlo: il registro nuovo e' firmato da un'altra chiave.
    t.registry.refresh(Purpose.INTERACTIVE)
    assertThat(t.awaitSetup { it is SetupState.Ready }).isInstanceOf(SetupState.Ready::class.java)

    // Impostazioni, Famiglia, "Rientra con un QR nuovo".
    assertThat(t.coordinator.startOver()).isInstanceOf(StepResult.Advanced::class.java)
    t.awaitSetup { it == SetupState.NotConfigured }
    t.joinWith(phone.graph.inviteMember())
    assertThat(t.snapshot().join!!.familyId).isEqualTo((reset as ResetOutcome.Done).newFamilyId)
    assertThat(t.snapshot().join!!.familyId).isNotEqualTo(oldFamily)
    assertThat(approve(phone.graph, t)).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    assertThat(t.awaitSetup { it is SetupState.Ready }).isEqualTo(SetupState.Ready(admin = false))
  }

  // --- ON-8: "Cartelle da escludere" ripresa da Da completare ------------------------------------------------------------

  @Test
  fun on8_leCartelleRipreseDopoLaFondazioneArrivanoAllaScansione() = runBlocking<Unit> {
    val phone = device("telefono")
    val g = phone.graph
    g.start()
    g.settle(g.coordinator.submit(StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY)))
    // I passi del NAS fino alle cartelle, che si saltano; poi il resto (chiave e fondazione comprese).
    while (g.coordinator.progress.value.current != OnboardingStep.FOLDERS) {
      when (g.coordinator.progress.value.current) {
        OnboardingStep.NAS_ADDRESS -> {
          g.settle(g.coordinator.submit(StepInput.ConfirmHome(true)))
          g.settle(g.coordinator.submit(StepInput.NasAddressChosen(TEST_NAS_ADDRESS)))
        }
        OnboardingStep.ACCOUNT -> g.settle(g.coordinator.submit(StepInput.Credentials(TestNas.ADMIN_USER, TestNas.ADMIN_PASSWORD.toCharArray())))
        else -> {
          g.coordinator.proposeAreas(listOf(TestNas.COMMON))
          g.settle(g.coordinator.submit(StepInput.Areas(TestNas.COMMON, TestNas.ADMIN_USER)))
        }
      }
    }
    g.settle(g.coordinator.skip(OnboardingStep.FOLDERS))
    g.localSteps(until = OnboardingStep.FAMILY_KEY)
    g.familyKey()
    g.localSteps(until = OnboardingStep.SUMMARY)
    g.settle(g.coordinator.submit(StepInput.Finish))
    g.awaitSetup { it is SetupState.Ready }
    assertThat(g.engine.state!!.registry.config.commonExclusions).isEmpty()

    // Da completare: "Cartelle da escludere".
    g.settle(g.coordinator.resume(OnboardingStep.FOLDERS))
    assertThat(g.coordinator.submit(StepInput.Folders(setOf("Musica")))).isInstanceOf(StepResult.Advanced::class.java)
    // La configurazione firmata (quella che usa la scansione) le ha, e cosi' il grafo del NAS.
    assertThat(g.engine.state!!.registry.config.commonExclusions).containsExactly("Musica")
    assertThat(g.nasInputs.commonExclusions()).containsExactly("Musica")
  }

  // --- ON-11: "Configura di nuovo" dopo l'azzeramento riavvia il processo ---------------------------------------------

  @Test
  fun on11_configuraDiNuovoDopoLAzzeramentoRiavviaIlProcesso() = runBlocking<Unit> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val tablet = device("tablet")
    val t = tablet.graph
    t.start()
    t.joinWith(phone.graph.inviteMember())
    assertThat(approve(phone.graph, t)).isEqualTo(AdminActionResult.Done)
    t.registry.refresh(Purpose.INTERACTIVE)
    t.awaitSetup { it is SetupState.Ready }
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    assertThat(phone.graph.registry.changeStatus(t.engine.myDeviceId()!!, DeviceStatus.LOST)).isEqualTo(AdminActionResult.Done)
    time.advance(SIXTEEN_MINUTES)
    assertThat(TrustSyncRun(t, notifications = null, notified = null).execute()).isEqualTo(TrustSyncOutcome.WIPE_STARTED)
    t.awaitSetup { it is SetupState.Wiped }

    var restarts = 0
    val ports = GraphRootPorts(t) { restarts++ }
    ports.reconfigureAfterWipe()
    // La galleria e il NAS di questo processo si sono fermati con l'azzeramento: si riparte da un processo nuovo.
    assertThat(restarts).isEqualTo(1)
    assertThat(t.awaitSetup { it !is SetupState.Wiped }).isEqualTo(SetupState.NotConfigured)
    // Niente da dimenticare: nessun riavvio.
    ports.reconfigureAfterWipe()
    assertThat(restarts).isEqualTo(1)
  }

  private companion object {
    const val SIXTEEN_MINUTES = 16 * 60 * 1000L
  }
}
