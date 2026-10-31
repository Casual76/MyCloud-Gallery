package dev.pampa.mycloudgallery.integration

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.lifecycle.FileWipeJournal
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.TcpBehaviour
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.notify.NotifiedRequests
import dev.pampa.mycloudgallery.root.RootDestination
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.work.TrustSyncOutcome
import dev.pampa.mycloudgallery.work.TrustSyncRun
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * I flussi principali dell'app con le implementazioni **vere** di P1-P8 collegate (P9, docs/fase2-progetto.md §4.3):
 * lo stesso `AppGraph` di produzione sopra il simulatore SMB di `core-nas` (`InMemorySmbBackend`) e una rete finta
 * (Wi-Fi di casa con l'ULA del FRITZ!Box).
 *
 * 1. avvio senza configurazione → onboarding;
 * 2. l'admin crea la famiglia (14 passi) → Libreria, con registro e kit davvero sul NAS simulato;
 * 3. NAS spento al passo 2 → "Continua piu' tardi" → passi locali → ripresa dal riepilogo;
 * 4. un familiare entra con l'invito → in attesa (sola lettura) → l'admin riceve la notifica e approva → Libreria;
 * 5. il familiare segnato perso dall'admin → il controllo in background lo azzera → "dispositivo azzerato" →
 *    "Configura di nuovo".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend: InMemorySmbBackend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()

  private fun device(name: String): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend)).also { devices += it }

  @After
  fun tearDown() {
    devices.forEach { it.close() }
  }

  // --- 1. Avvio -------------------------------------------------------------------------------------------------

  @Test
  fun avvioSenzaConfigurazione_portaAllOnboarding() = runBlocking {
    val phone = device("telefono")
    // Prima che lo stato salvato sia letto: niente da mostrare (nessun lampo di benvenuto).
    assertThat(phone.graph.setup.state.value).isEqualTo(SetupState.Loading)
    phone.graph.start()
    val state = phone.graph.awaitSetup { it !is SetupState.Loading }
    assertThat(state).isEqualTo(SetupState.NotConfigured)
    assertThat(RootDestination.from(state)).isEqualTo(RootDestination.Onboarding)
    // Nessun NAS configurato: il cancello nega e nessuna connessione e' partita.
    assertThat(phone.graph.routeGate.current()).isInstanceOf(NasRoute.Denied::class.java)
    assertThat(backend.tcpAttempts.get()).isEqualTo(0)
  }

  // --- 2. L'admin crea la famiglia -------------------------------------------------------------------------------

  @Test
  fun lAdminCreaLaFamigliaSulNasSimulatoEArrivaAllaLibreria() = runBlocking {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()

    val state = phone.graph.awaitSetup { it is SetupState.Ready }
    assertThat(state).isEqualTo(SetupState.Ready(admin = true))
    assertThat(RootDestination.from(state)).isEqualTo(RootDestination.Main)

    // Sul NAS: il registro di fondazione nell'area comune e il kit di recupero nell'area privata.
    assertThat(backend.exists(TestNas.COMMON, NasLayout.registryFile(1).value)).isTrue()
    assertThat(backend.exists(TestNas.ADMIN_USER, NasLayout.RECOVERY_DIR.value)).isTrue()
    // La password e' nel "Keystore", la chiave di famiglia nella cassaforte, l'account e' salvato con il GUID.
    assertThat(phone.platform.memoryCredentials.slotNames).isNotEmpty()
    assertThat(phone.platform.vault.stored).isNotNull()
    val account = phone.graph.account.await { it != null }!!
    assertThat(account.user).isEqualTo(TestNas.ADMIN_USER)
    assertThat(account.pinnedGuid).isEqualTo(backend.serverGuid)
    // Casa registrata: il cancello consente il LAN e la sessione e' cifrata (C3).
    assertThat(phone.graph.environment.snapshot.value.place).isEqualTo(NetPlace.HOME)
    assertThat(phone.graph.routeGate.current()).isInstanceOf(NasRoute.Allowed::class.java)
    val ready = phone.graph.session.state.value
    assertThat(ready).isInstanceOf(NasConnState.Ready::class.java)
    assertThat((ready as NasConnState.Ready).info.encrypted).isTrue()
    assertThat(phone.graph.breaker.state.value).isInstanceOf(BreakerState.Closed::class.java)
    // L'indicatore globale (§3.5) dagli stati veri.
    val status = GlobalStatus.from(
      phone.graph.environment.snapshot.value,
      phone.graph.session.state.value,
      phone.graph.reachability.lastResult.value,
      phone.graph.breaker.state.value is BreakerState.Open,
    )
    assertThat(status).isEqualTo(GlobalStatus.HOME_CONNECTED)

    // Il controllo in background (TrustSyncWorker): rispetta le regole, rilegge il registro, lascia il battito.
    val outcome = TrustSyncRun(phone.graph, notifications = null, notified = null).execute()
    assertThat(outcome).isEqualTo(TrustSyncOutcome.DONE)
    assertThat(phone.graph.heartbeat.lastBeatAt()).isEqualTo(time.now())
  }

  /**
   * Fase 10, correzione 1.4 M3: il controllo in background del telefono dell'admin (`TrustSyncRun`, quello del `TrustSyncWorker`) non tocca
   * mai la cassaforte della chiave di famiglia, nemmeno quando la versione fidata del registro e' stata sostituita con spazzatura (N3), che
   * prima si ripubblicava da sola "se la cassaforte si apre". La ripubblicazione resta da fare con il tasto (e il foglio dell'impronta).
   */
  @Test
  fun ilControlloInBackgroundDellAdminNonToccaMaiLaChiaveDiFamiglia() = runBlocking {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    phone.graph.awaitSetup { it is SetupState.Ready }
    val touches = phone.platform.vault.touches.get()
    val trusted = phone.graph.engine.state!!.registry.version
    backend.delete(TestNas.COMMON, NasLayout.registryFile(trusted).value)
    backend.put(TestNas.COMMON, NasLayout.registryFile(trusted).value, "spazzatura".toByteArray())

    repeat(2) { assertThat(TrustSyncRun(phone.graph, notifications = null, notified = null).execute()).isEqualTo(TrustSyncOutcome.DONE) }
    assertThat(phone.platform.vault.touches.get()).isEqualTo(touches)

    // Nemmeno la pagina dei dispositivi che si apre (controllo interattivo) firma da sola: accende il tasto "Ripubblica".
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    assertThat(phone.platform.vault.touches.get()).isEqualTo(touches)
    assertThat(phone.graph.registry.reissueDue.value).isTrue()
    assertThat(backend.exists(TestNas.COMMON, NasLayout.registryFile(trusted + 1).value)).isFalse()
  }

  // --- 3. NAS spento: "Continua piu' tardi" (E-O1) --------------------------------------------------------------------

  @Test
  fun conIlNasSpentoSiContinuaPiuTardiESiRiprendeDalRiepilogo() = runBlocking {
    val phone = device("telefono")
    val graph = phone.graph
    graph.start()
    val c = graph.coordinator
    graph.settle(c.submit(StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY)))
    graph.settle(c.submit(StepInput.ConfirmHome(true)))
    backend.tcp = TcpBehaviour.REFUSE
    val off = c.submit(StepInput.NasAddressChosen(TEST_NAS_ADDRESS))
    assertThat(off).isEqualTo(StepResult.Problem(OnboardingProblem.NAS_SERVICE_DOWN))

    // "Continua piu' tardi": i passi del NAS aspettano, quelli locali si fanno.
    assertThat(graph.settle(c.skip(OnboardingStep.NAS_ADDRESS)).next).isEqualTo(OnboardingStep.BACKUP_SOURCES)
    graph.localSteps(until = OnboardingStep.SUMMARY)
    assertThat(c.submit(StepInput.Finish)).isEqualTo(StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(graph.setup.state.value).isInstanceOf(SetupState.Onboarding::class.java)

    // Il NAS e' tornato: dal riepilogo si riprendono i passi del NAS e si finisce.
    backend.tcp = TcpBehaviour.ACCEPT
    graph.settle(c.resume(OnboardingStep.NAS_ADDRESS))
    graph.nasSteps()
    assertThat(graph.coordinator.progress.value.current).isEqualTo(OnboardingStep.SUMMARY)
    graph.settle(c.submit(StepInput.Finish))
    assertThat(graph.awaitSetup { it is SetupState.Ready }).isEqualTo(SetupState.Ready(admin = true))
  }

  // --- 4 e 5. Un familiare: attesa, approvazione, smarrimento -----------------------------------------------------

  @Test
  fun unFamiliareEntraAspettaLApprovazionePoiSegnatoPersoSiAzzera() = runBlocking {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()

    // L'admin crea l'invito (QR): si apre la finestra d'invito (controlli ogni 15 minuti).
    val invite = phone.graph.registry.createInvite(InviteParams(TestNas.MEMBER_USER, "Tablet di prova", TestNas.MEMBER_USER, presetName = null))
    assertThat(phone.scheduler.inviteWindowUntil).isNotNull()

    // Il tablet entra con l'invito.
    val tablet = device("tablet")
    val t = tablet.graph
    t.start()
    t.awaitSetup { it == SetupState.NotConfigured }
    t.settle(t.coordinator.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    assertThat(t.joinFlow.start(invite)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(t.joinFlow.authenticate(TestNas.MEMBER_PASSWORD.toCharArray())).isInstanceOf(JoinStep.NeedsName::class.java)
    val awaiting = t.joinFlow.register("Tablet di prova") as JoinStep.AwaitingApproval
    t.coordinator.progress.await { it.current == OnboardingStep.ROLES }
    // Intanto finisce i passi sul telefono; alla fine e' "in attesa", in sola lettura (E-S14).
    t.localSteps(until = OnboardingStep.SUMMARY)
    t.settle(t.coordinator.submit(StepInput.Finish))
    val waiting = t.awaitSetup { it is SetupState.AwaitingApproval } as SetupState.AwaitingApproval
    assertThat(waiting.code).isEqualTo(awaiting.code)
    assertThat(RootDestination.from(waiting)).isEqualTo(RootDestination.AwaitingApproval(awaiting.code))
    assertThat(t.deviceKeyQr()).startsWith("MCGK1:")
    assertThat(t.trustGuard.writePermit()).isNull()

    // L'admin: il controllo in background trova la richiesta e notifica una volta sola ("Nuovo dispositivo...").
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val notifications = AppNotifications(phone.context).apply { createChannels() }
    val notified = NotifiedRequests(File(temp.root, "notified"))
    assertThat(TrustSyncRun(phone.graph, notifications, notified).execute()).isEqualTo(TrustSyncOutcome.DONE)
    val manager = shadowOf(phone.context.getSystemService(NotificationManager::class.java))
    assertThat(manager.allNotifications).hasSize(1)
    val posted = manager.allNotifications.single()
    assertThat(posted.channelId).isEqualTo(AppNotifications.CHANNEL_APPROVALS)
    assertThat(shadowOf(posted.contentIntent).isImmutable).isTrue()
    assertThat(shadowOf(posted.contentIntent).savedIntent.extras).isNull() // apre solo l'app
    // Revisione 2, F10: il nome scritto da chi ha creato la richiesta non compare, nemmeno nella versione privata.
    val requestName = phone.graph.registry.pendingRequests.value.single().name
    val shown = listOf(posted, posted.publicVersion).flatMap { n ->
      listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT).mapNotNull { n?.extras?.getCharSequence(it)?.toString() }
    }
    assertThat(shown).isNotEmpty()
    shown.forEach { assertThat(it).doesNotContain(requestName) }
    // Un secondo giro (cinque minuti dopo, ancora dentro la finestra di 15 minuti dell'approvazione): nessuna notifica nuova.
    time.advance(FIVE_MINUTES)
    TrustSyncRun(phone.graph, notifications, notified).execute()
    assertThat(manager.allNotifications).hasSize(1)

    // L'admin approva digitando il codice; il tablet, con la pagina d'attesa aperta, se ne accorge da solo.
    val request = phone.graph.registry.pendingRequests.value.single()
    assertThat(phone.graph.registry.approve(request.id, ApprovalProof.TypedCode(awaiting.code))).isEqualTo(AdminActionResult.Done)
    val final = t.approvalWatcher.watch().first { it !is TrustStatus.Pending && it !is TrustStatus.Stale }
    assertThat(final).isEqualTo(TrustStatus.Approved(admin = false))
    assertThat(t.awaitSetup { it is SetupState.Ready }).isEqualTo(SetupState.Ready(admin = false))
    assertThat(t.trustGuard.writePermit()).isNotNull()

    // Il tablet e' perso: l'admin lo segna; al prossimo controllo in background il tablet si azzera (E-S2, C15).
    val tabletId = t.engine.myDeviceId()!!
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    assertThat(phone.graph.registry.changeStatus(tabletId, DeviceStatus.LOST)).isEqualTo(AdminActionResult.Done)
    time.advance(PREFLIGHT_INTERVAL)
    assertThat(TrustSyncRun(t, notifications = null, notified = null).execute()).isEqualTo(TrustSyncOutcome.WIPE_STARTED)
    assertThat(t.selfWipe.progress.value).isEqualTo(WipePhase.DONE)
    val wiped = t.awaitSetup { it is SetupState.Wiped }
    assertThat(RootDestination.from(wiped)).isEqualTo(RootDestination.Wiped)
    assertThat(tablet.platform.keys.hasDeviceKey()).isFalse()
    assertThat(tablet.platform.memoryCredentials.wiped).isTrue()
    assertThat(t.account.await { it == null }).isNull()
    assertThat(tablet.workCancelled.get()).isEqualTo(1)
    // Il diario resta (fa riprendere un azzeramento interrotto e dice "azzerato" dopo un riavvio).
    assertThat(File(tablet.context.noBackupFilesDir, FileWipeJournal.FILE_NAME).exists()).isTrue()

    // "Configura di nuovo": si torna al benvenuto, con una chiave nuova da riapprovare.
    t.forgetCompletedWipe()
    assertThat(t.awaitSetup { it !is SetupState.Wiped }).isEqualTo(SetupState.NotConfigured)
  }

  private companion object {
    /** La guardia rilegge il registro al massimo ogni 15 minuti. */
    const val PREFLIGHT_INTERVAL = 16 * 60 * 1000L
    const val FIVE_MINUTES = 5 * 60 * 1000L
  }
}
