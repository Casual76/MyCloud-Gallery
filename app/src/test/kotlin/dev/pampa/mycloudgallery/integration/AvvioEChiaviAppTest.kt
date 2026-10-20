package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import dev.pampa.mycloudgallery.graph.snapshotOrNull
import dev.pampa.mycloudgallery.root.GraphRootPorts
import dev.pampa.mycloudgallery.work.TrustSyncOutcome
import dev.pampa.mycloudgallery.work.TrustSyncRun
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L'avvio di un processo appena nato sul grafo **vero**, con le chiavi che restano (come il Keystore) e i file del processo di prima.
 *
 * - **D-7** (prove sul campo): `AppGraph.start` dava il via prima che lo stato della famiglia vedesse la fiducia letta, e per un
 *   attimo il dispositivo risultava "non configurato" (la UI un lampo, i lavori ripartiti "niente da fare").
 * - **R7** della revisione 5B, il residuo: un Keystore che non risponde dava "non configurato" e la pagina "fuori dalla famiglia";
 *   ora e' la pagina "Keystore non disponibile", chiusa, con "Riprova".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AvvioEChiaviAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()
  private val threads = mutableListOf<ExecutorCoroutineDispatcher>()
  private val watchers = mutableListOf<Job>()

  private fun device(name: String, platform: TestPlatform = TestPlatform(time, backend), dispatcher: CoroutineDispatcher = Dispatchers.Default): TestDevice =
    TestDevice(app, File(temp.root, name), platform, dispatcher = dispatcher).also { devices += it }

  @After
  fun tearDown() {
    watchers.forEach { it.cancel() }
    devices.forEach { it.close() }
    threads.forEach { it.close() }
  }

  /** Il telefono fonda la famiglia, poi il suo processo muore: restano i file e le chiavi (la piattaforma, come il Keystore). */
  private suspend fun foundedThenKilled(): TestPlatform {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    phone.close()
    return phone.platform
  }

  /** Il processo nuovo, sulle cartelle di prima. */
  private fun reborn(platform: TestPlatform, dispatcher: CoroutineDispatcher = Dispatchers.Default): TestDevice = device("telefono", platform, dispatcher)

  /** Ogni valore dello stato del dispositivo, dal primo istante (come la radice), in ordine. */
  private fun watch(device: TestDevice): List<SetupState> {
    val seen = CopyOnWriteArrayList<SetupState>()
    watchers += CoroutineScope(Dispatchers.Unconfined).launch { device.graph.setup.state.collect { seen += it } }
    return seen
  }

  // --- D-7 --------------------------------------------------------------------------------------------------------------

  /**
   * La corsa, resa ripetibile: il grafo su un thread solo e l'avvio sullo stesso thread. Prima dell'avvio lo stato della famiglia ha gia'
   * letto l'avanzamento (onboarding finito) con la fiducia iniziale del motore, cioe' "non configurato". Senza la correzione l'avvio
   * metteva il via un passo prima che lo stato della famiglia vedesse "approvato": chi leggeva subito dopo l'avvio (come
   * `TrustSyncRun` e i lavori di un processo appena nato) e chi guardava dall'inizio vedevano "non configurato".
   */
  @Test
  fun d7_inUnProcessoAppenaNatoLoStatoNonPassaMaiPerNonConfigurato() = runBlocking<Unit> {
    val platform = foundedThenKilled()
    val single = Executors.newSingleThreadExecutor().asCoroutineDispatcher().also { threads += it }
    val again = reborn(platform, single)
    val g = again.graph
    val seen = watch(again)

    val first = withContext(single) {
      g.onboardingStore.state.first { it is StoreState.Ready }
      // Lo stato della famiglia ha visto l'avanzamento letto: dice "non configurato", ma nessuno lo vede (il via non c'e').
      repeat(DRAIN_STEPS) { yield() }
      assertThat(g.setup.state.value).isEqualTo(SetupState.Loading)
      g.start()
      g.setup.state.first { it !is SetupState.Loading }
    }

    assertThat(first).isEqualTo(SetupState.Ready(admin = true))
    assertThat(seen.first()).isEqualTo(SetupState.Loading)
    assertThat(seen).doesNotContain(SetupState.NotConfigured)
    assertThat(seen.last()).isEqualTo(SetupState.Ready(admin = true))
  }

  /** La stessa cosa con i thread di sempre: subito dopo l'avvio lo stato e' quello vero, per 5 processi di fila. */
  @Test
  fun d7_subitoDopoLAvvioLoStatoEQuelloVero() = runBlocking<Unit> {
    var platform = foundedThenKilled()
    repeat(REBIRTHS) {
      val again = reborn(platform)
      val seen = watch(again)
      again.graph.start()
      assertThat(again.graph.setup.state.first { it !is SetupState.Loading }).isEqualTo(SetupState.Ready(admin = true))
      assertThat(seen).doesNotContain(SetupState.NotConfigured)
      again.close()
      platform = again.platform
    }
  }

  // --- R7 ---------------------------------------------------------------------------------------------------------------

  @Test
  fun r7_conIlKeystoreCheNonRispondeLaRadiceVaAllaPaginaDelleChiaviMaiFuoriDallaFamiglia() = runBlocking<Unit> {
    val platform = foundedThenKilled()
    platform.keys.broken = true
    val again = reborn(platform)
    val g = again.graph
    val seen = watch(again)

    g.start()

    assertThat(g.awaitSetup { it !is SetupState.Loading }).isEqualTo(SetupState.Blocked(BlockReason.KEYSTORE_UNAVAILABLE))
    assertThat(seen).doesNotContain(SetupState.NotConfigured)
    assertThat(g.registry.status.value).isEqualTo(TrustStatus.KeystoreUnavailable)
    // Chiuso: nessun permesso di scrittura; il controllo del registro si ferma senza firmare ne' leggere niente.
    assertThat(g.trustGuard.writePermit()).isNull()
    assertThat(TrustSyncRun(g, notifications = null, notified = null).execute()).isEqualTo(TrustSyncOutcome.STOPPED)
    // Niente si e' toccato: chiave di famiglia, avanzamento, account, nessun azzeramento.
    assertThat(platform.vault.stored).isNotNull()
    assertThat(g.selfWipe.progress.value).isNull()
    assertThat(g.onboardingStore.snapshotOrNull()!!.finished).isTrue()
    assertThat(g.account.value).isNotNull()
  }

  @Test
  fun r7_riprovaConIlKeystoreGuaritoTornaFidatoEAllaLibreria() = runBlocking<Unit> {
    val platform = foundedThenKilled()
    platform.keys.broken = true
    val g = reborn(platform).graph
    g.start()
    g.awaitSetup { it == SetupState.Blocked(BlockReason.KEYSTORE_UNAVAILABLE) }
    val ports = GraphRootPorts(g)

    // Ancora guasto: la pagina resta.
    assertThat(ports.recheckKeys()).isFalse()
    assertThat(g.setup.state.value).isEqualTo(SetupState.Blocked(BlockReason.KEYSTORE_UNAVAILABLE))

    platform.keys.broken = false
    assertThat(ports.recheckKeys()).isTrue()

    assertThat(g.awaitSetup { it !is SetupState.Blocked }).isEqualTo(SetupState.Ready(admin = true))
    assertThat(g.registry.status.value).isEqualTo(TrustStatus.Approved(admin = true))
    assertThat(g.trustGuard.writePermit()).isNotNull()
  }

  @Test
  fun r7_configuraDiNuovoDallaPaginaDelleChiaviConIlKeystoreGuastoNonToccaNiente() = runBlocking<Unit> {
    val platform = foundedThenKilled()
    platform.keys.broken = true
    val g = reborn(platform).graph
    g.start()
    g.awaitSetup { it == SetupState.Blocked(BlockReason.KEYSTORE_UNAVAILABLE) }
    val ports = GraphRootPorts(g)

    // Il foglio dira' che si perde la chiave di famiglia (il fondatore e' l'admin).
    assertThat(ports.holdsFamilyKey()).isTrue()
    assertThat(ports.startOver()).isEqualTo(StepResult.Problem(OnboardingProblem.KEYS_NOT_REMOVED))

    assertThat(platform.vault.stored).isNotNull()
    assertThat(g.onboardingStore.snapshotOrNull()!!.finished).isTrue()
    assertThat(g.account.value).isNotNull()
    platform.keys.broken = false
    assertThat(platform.keys.hasDeviceKey()).isTrue()
    assertThat(g.setup.state.value).isEqualTo(SetupState.Blocked(BlockReason.KEYSTORE_UNAVAILABLE))
  }

  private companion object {
    /** I passi del thread del grafo che bastano a far arrivare l'avanzamento letto allo stato della famiglia (pochi: abbondanti). */
    const val DRAIN_STEPS = 50

    const val REBIRTHS = 5
  }
}
