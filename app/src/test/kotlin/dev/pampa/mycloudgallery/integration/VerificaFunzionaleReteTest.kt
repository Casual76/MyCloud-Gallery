package dev.pampa.mycloudgallery.integration

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.link.NetworkObservation
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NightWindows
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.RecentEventsContributor
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagStopCause
import dev.pampa.mycloudgallery.feature.settings.rules.RulesChoices
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.process.ProcessWatcher
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.work.AndroidLimitsTracker
import dev.pampa.mycloudgallery.work.StopReports
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La verifica funzionale delle fasi 1-5A (`docs/review/funzionale-fasi-1-5a.md`, voci RE: rete, VPN, Impostazioni, Diagnostica) sul grafo
 * **vero** dell'app, con il simulatore SMB: i percorsi che prima erano provati solo con i finti (`FakeNasReachabilitySignal(true)`,
 * `FakeAndroidLimitsSignal`, `FakeReachabilityProbe`...).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class VerificaFunzionaleReteTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend: InMemorySmbBackend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()
  private val flags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)

  @Before
  fun setUp() {
    // I ViewModel lavorano sul thread principale: qui sul thread del test, fino alla prima sospensione.
    Dispatchers.setMain(Dispatchers.Unconfined)
  }

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
    Dispatchers.resetMain()
  }

  private fun device(name: String, log: RingDiagnosticsLog = RingDiagnosticsLog()): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend), flags, log).also { devices += it }

  private suspend fun foundedDevice(log: RingDiagnosticsLog = RingDiagnosticsLog()): TestDevice {
    val phone = device("telefono", log)
    phone.graph.start()
    phone.graph.foundFamily()
    return phone
  }

  // --- RE-3 e RE-4: un lavoro in un processo freddo ------------------------------------------------------------------

  /**
   * RE-3: WorkManager sveglia l'app la notte e le reti arrivano dai callback un attimo **dopo**. La prima valutazione delle regole
   * vedeva «offline», il giro finiva «riuscito» senza fare niente e il prossimo era dopo sei ore. Ora ogni lavoro a tappe aspetta la rete
   * (`AppGraph.stageRules`) prima della prima valutazione. Il test usa il backup vero (`BackupStages`) sul grafo vero: la rete torna
   * dopo 300 ms. (RE-4: l'esito arriva nell'anello della Diagnostica, solo lavoro e numeri.)
   */
  @Test
  fun re3_unProcessoFreddoAspettaLaReteInvecePerdereIlGiro() = runBlocking {
    val ring = RingDiagnosticsLog()
    val phone = foundedDevice(ring)
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER).also { rigs += it }
    BackupRig.photo(rig.store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - 3 * HOUR)
    rig.open()
    rig.chooseFolder("Pixel di prova")

    // Il processo "freddo": nessuna rete vista; il Wi-Fi di casa compare 300 ms dopo l'inizio del lavoro.
    val home = LinkFactsSamples.observation(LinkFactsSamples.homeWifi())
    phone.platform.network.set(NetworkObservation.EMPTY)
    phone.graph.environment.snapshot.await { it.place == NetPlace.OFFLINE }
    val arrival = launch(Dispatchers.Default) {
      delay(300)
      phone.platform.network.set(home)
    }

    val outcome = checkNotNull(rig.round()) { "il giro non e' partito" }
    arrival.join()

    val last = checkNotNull(outcome.last) { "il lavoro non ha aspettato la rete: ${outcome.stage}" }
    assertThat(last.done).isEqualTo(1)

    // RE-4: l'esito del lavoro e' nell'anello, con il solo nome del lavoro e numeri.
    val events = ring.snapshot().filter { it.code == DiagCodes.EVT_JOB_RESULT }
    assertWithMessage("eventi: ${ring.snapshot()}").that(events).isNotEmpty()
    val items = RecentEventsContributor(ring).collect()
    assertThat(items.map { it.code }).contains(DiagCodes.EVT_JOB_RESULT)
  }

  /** RE-3, il lato delle regole: la stessa valutazione, in un processo freddo, aspetta e poi dice «vai». */
  @Test
  fun re3_leRegoleDiUnaTappaAspettanoLaRetePrimaDellaPrimaValutazione() = runBlocking {
    val phone = foundedDevice()
    val request = WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP)
    val home = LinkFactsSamples.observation(LinkFactsSamples.homeWifi())
    phone.platform.network.set(NetworkObservation.EMPTY)
    phone.graph.environment.snapshot.await { it.place == NetPlace.OFFLINE }
    val arrival = launch(Dispatchers.Default) {
      delay(300)
      phone.platform.network.set(home)
    }

    val decision = phone.graph.stageRules().decide(request, running = false)
    arrival.join()

    assertWithMessage("decisione: $decision").that(decision).isInstanceOf(RuleDecision.Run::class.java)
  }

  /** RE-4: gli errori veri della sessione NAS arrivano nell'anello con il codice della Diagnostica, senza nomi. */
  @Test
  fun re4_ilGuastoDellaSessioneNasArrivaNellAnello() = runBlocking {
    val ring = RingDiagnosticsLog()
    val phone = foundedDevice(ring)
    val slot = phone.graph.account.value!!.credentialSlot
    phone.platform.memoryCredentials.remove(slot)
    phone.graph.session.invalidate(InvalidateReason.USER_REQUEST)

    val error = phone.graph.session.withShare(TestNas.COMMON, Purpose.INTERACTIVE) { it.browser.list(null, 10) }.errorOrNull()

    assertThat(error).isEqualTo(NasError.CredentialsMissing)
    until("l'evento NAS_CREDENTIALS_MISSING nel diario") { ring.snapshot().any { it.code.id == "NAS_CREDENTIALS_MISSING" } }
    val items = RecentEventsContributor(ring).collect()
    assertThat(items.map { it.code.id }).contains("NAS_CREDENTIALS_MISSING")
    // Mai un nome: ne' l'utente del NAS, ne' l'area, ne' l'indirizzo.
    val text = ring.snapshot().joinToString { it.toString() + it.values }
    assertThat(text).doesNotContain(TestNas.ADMIN_USER)
    assertThat(text).doesNotContain(TestNas.COMMON)
    assertThat(text).doesNotContain(TestNas.IP)
  }

  // --- RE-9: la chiave del Keystore invalidata -----------------------------------------------------------------------

  /**
   * RE-9: con la chiave invalidata l'app diceva «NAS non raggiungibile» e ogni lavoro riapriva un socket. Ora la password si chiede **una
   * volta** (indicatore e notifica) e nessun tentativo parte da solo finche' non ne arriva una nuova.
   */
  @Test
  fun re9_laChiaveInvalidataChiedeLaPasswordUnaVoltaSenzaTentativiAutomatici() = runBlocking {
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val ring = RingDiagnosticsLog()
    val phone = foundedDevice(ring)
    val notifier = AppNotifications(phone.context).also { it.createChannels() }
    val watcher = ProcessWatcher(phone.graph, notifier) {}
    watcher.start(app)
    val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
    val slot = phone.graph.account.value!!.credentialSlot
    val touch = suspend { phone.graph.session.withShare(TestNas.COMMON, Purpose.INTERACTIVE) { it.browser.list(null, 10) }.errorOrNull() }

    // La chiave sparisce (E-S9): la prima richiesta lo scopre.
    phone.platform.memoryCredentials.remove(slot)
    phone.graph.session.invalidate(InvalidateReason.USER_REQUEST)
    assertThat(touch()).isEqualTo(NasError.CredentialsMissing)
    val state = phone.graph.session.state.value
    assertThat(state).isEqualTo(NasConnState.Unavailable(NasError.CredentialsMissing))
    assertThat(GlobalStatus.from(phone.graph.environment.snapshot.value, state)).isEqualTo(GlobalStatus.NEEDS_PASSWORD)
    until("la notifica \"Serve la password\"") { notifications.getNotification(AppNotifications.ID_PASSWORD) != null }

    // Cinque lavori dopo: nessun socket, nessuna autenticazione.
    val tcp = backend.tcpAttempts.get()
    val authentications = backend.authenticateCalls.get()
    repeat(5) { assertThat(touch()).isEqualTo(NasError.CredentialsMissing) }
    assertThat(backend.tcpAttempts.get()).isEqualTo(tcp)
    assertThat(backend.authenticateCalls.get()).isEqualTo(authentications)

    // Una password nuova (da Impostazioni): un tentativo, riuscito; l'avviso sparisce.
    phone.platform.memoryCredentials.save(slot, NasCredentials(TestNas.ADMIN_USER, TestNas.ADMIN_PASSWORD.toCharArray()))
    phone.graph.session.invalidate(InvalidateReason.CREDENTIALS_CHANGED)
    assertThat(touch()).isNull()
    assertThat(backend.authenticateCalls.get()).isEqualTo(authentications + 1)
    until("la notifica tolta") { notifications.getNotification(AppNotifications.ID_PASSWORD) == null }
  }

  // --- RE-5: indirizzo del NAS e impronta di casa dell'admin ---------------------------------------------------------

  /**
   * RE-5: il NAS cambia IP, l'admin lo corregge sul suo telefono e il QR d'invito nuovo deve portare quello nuovo (e la nuova rete di casa).
   * L'indirizzo dell'invito viene dalla configurazione firmata: prima nessuno la aggiornava.
   */
  @Test
  fun re5_l_adminCambiaIndirizzoEImprontaEIlQrNuovoPortaIDatiNuovi() = runBlocking {
    val phone = foundedDevice()
    val params = InviteParams(TestNas.MEMBER_USER, "Tablet di prova", TestNas.MEMBER_USER, presetName = null)
    assertThat(phone.graph.registry.createInvite(params).nasIp).isEqualTo(TestNas.IP)

    // Come fa la pagina dell'indirizzo: prima il telefono, poi la configurazione firmata.
    phone.graph.nasAccountSettings.updateAddress(NasAddress("192.168.178.77"))
    phone.graph.session.invalidate(InvalidateReason.USER_REQUEST)
    assertThat(phone.graph.registry.updateNasAddress("192.168.178.77")).isEqualTo(AdminActionResult.Done)
    assertThat(phone.graph.registry.updateHomeUla(setOf("fd99:1234:5678::/48"))).isEqualTo(AdminActionResult.Done)

    val invite = phone.graph.registry.createInvite(params)
    assertThat(phone.graph.account.value!!.address.ip).isEqualTo("192.168.178.77")
    assertThat(invite.nasIp).isEqualTo("192.168.178.77")
    assertThat(invite.homeUla).containsExactly("fd99:1234:5678::/48")
    Unit
  }

  // --- RE-7: i limiti di Android -------------------------------------------------------------------------------------

  /**
   * RE-7: `AppGraph` legava `NoAndroidLimits`, quindi `WaitReason.AndroidLimit` era codice morto. Ora un lavoro fermato da Android per
   * la quota dei job li porta alle regole, e il motivo si salva nell'anello.
   */
  @Test
  fun re7_unoStopPerQuotaDiAndroidArrivaAlleRegoleEAlLAnello() = runBlocking {
    val ring = RingDiagnosticsLog()
    val phone = foundedDevice(ring)
    val request = WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP)
    val engine = phone.graph.rulesEngine
    assertThat(engine.evaluate(request)).isInstanceOf(RuleDecision.Run::class.java)

    StopReports.report(phone.graph, DiagJob.PHOTO_BACKUP, androidx.work.WorkInfo.STOP_REASON_QUOTA)

    val blocked = engine.evaluate(request) as RuleDecision.Wait
    assertThat(listOf(blocked.reason) + blocked.alsoBlockedBy).contains(WaitReason.AndroidLimit(null))
    assertThat(ring.snapshot().map { it.code }).contains(DiagCodes.ACT_JOB_STOPPED)

    // Un'app di nuovo visibile non e' soggetta alla quota.
    phone.graph.androidLimits.clear()
    assertThat(engine.evaluate(request)).isInstanceOf(RuleDecision.Run::class.java)
  }

  @Test
  fun re7_ilBloccoPerQuotaSiToglieDaSoloEsoloPerIMotiviCheSonoLimiti() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val tracker = AndroidLimitsTracker(scope, cooldown = 200.milliseconds)
      tracker.noteStop(DiagStopCause.USER)
      tracker.noteStop(DiagStopCause.TIMEOUT)
      assertThat(tracker.limits.value.jobQuotaExhausted).isFalse()

      tracker.noteStop(DiagStopCause.QUOTA)
      assertThat(tracker.limits.value.jobQuotaExhausted).isTrue()
      until("la quota dei lavori non e' piu' esaurita") { !tracker.limits.value.jobQuotaExhausted }
    } finally {
      scope.cancel()
    }
  }

  // --- RE-8: preset, tetti e roaming dalle Impostazioni -------------------------------------------------------------

  /**
   * RE-8: il preset scelto dalle Impostazioni ha lo stesso effetto del passo 8 dell'onboarding. Qui l'onboarding vero ha scritto il suo
   * (Bilanciato, anteprime standard) nel DataStore vero; cambiare preset da Impostazioni cambia **solo** il preset.
   */
  @Test
  fun re8_ilPresetDelleImpostazioniHaLoStessoEffettoDelPassoDiOnboarding() = runBlocking {
    val phone = foundedDevice()
    val onboarded = phone.graph.ruleSettings.current()
    assertThat(onboarded.preset).isEqualTo(Preset.BALANCED)
    assertThat(onboarded.nightWindow).isEqualTo(NightWindows.DEFAULT)
    // Le anteprime ad alta qualita' sono spente (qualita' standard): le decide la pagina delle anteprime, non quella dei preset.
    assertThat(onboarded.disabledActivities).contains(BackgroundActivity.HQ_PREVIEWS)

    assertThat(RulesChoices.withPreset(onboarded, Preset.MAXIMUM)).isEqualTo(onboarded.copy(preset = Preset.MAXIMUM))

    // Come fa la pagina (RulesViewModel): una modifica atomica delle impostazioni del motore.
    phone.graph.ruleSettings.update { RulesChoices.withRoaming(RulesChoices.withPreset(it, Preset.MAXIMUM), allow = true) }
    val saved = phone.graph.ruleSettings.current()
    assertThat(saved).isEqualTo(onboarded.copy(preset = Preset.MAXIMUM, allowRoaming = true))
    // Il motore le rilegge da solo: con "Massimo" anche i video partono di giorno e sui dati mobili.
    assertThat(saved.toRuleSet().rules.getValue(BackgroundActivity.VIDEO_BACKUP).window).isNull()
  }

  private companion object {
    const val HOUR = 3_600_000L
  }
}
