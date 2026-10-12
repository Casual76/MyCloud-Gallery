package dev.pampa.mycloudgallery.feature.diagnostics

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagEvent
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.testing.FakeAuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.testing.FakeConnectionTester
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasCredentialSource
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.testing.FakeVpnController
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestInput
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestInputSource
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestOutcome
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Il view model della Diagnostica: lettura, test di connessione, anteprima e salvataggio dell'esportazione. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private val log = RingDiagnosticsLog(capacity = 10)
  private val tester = FakeConnectionTester()
  private val breaker = FakeAuthCircuitBreaker()
  private val vpn = FakeVpnController()
  private val target = TestTarget(NasAddress("192.168.178.58", "nas"), user = "test", share = "test-comune", expectedGuid = null)

  private fun viewModel(
    contributors: Set<DiagnosticsContributor> = emptySet(),
    configured: Boolean = true,
    credentials: FakeNasCredentialSource = FakeNasCredentialSource { CredentialLoad.Absent },
  ) = DiagnosticsViewModel(
    contributors = contributors,
    log = log,
    connectionTester = tester,
    breaker = breaker,
    credentials = credentials,
    inputSource = if (configured) {
      Optional.of(ConnectionTestInputSource { ConnectionTestInput(target, credentialSlot = null) })
    } else {
      Optional.empty()
    },
    vpn = vpn,
  )

  private class Recording : ExportSink {
    var written: Pair<Uri, String>? = null
    var succeed = true

    override suspend fun write(uri: Uri, text: String): Boolean {
      if (succeed) written = uri to text
      return succeed
    }
  }

  @Test
  fun all_inizioStaCaricando_poiMostraLeVociEPreparaLEsportazione() = runTest(dispatcher) {
    val vm = viewModel(setOf(FakeContributor(DiagSection.NETWORK_AND_NAS) { listOf(item(DiagCodes.NET_POOR, severity = DiagSeverity.WARNING)) }))

    assertThat(vm.state.value.loading).isTrue()
    assertThat(vm.state.value.export).isNull() // prima della lettura non c'e' niente da esportare

    advanceUntilIdle()

    val state = vm.state.value
    assertThat(state.loading).isFalse()
    assertThat(state.sections.getValue(DiagSection.NETWORK_AND_NAS).map { it.code }).containsExactly(DiagCodes.NET_POOR)
    assertThat(state.attentionCount).isEqualTo(1)
    assertThat(state.attentionIn(DiagSection.NETWORK_AND_NAS)).isEqualTo(1)
    assertThat(state.worst(DiagSection.NETWORK_AND_NAS)).isEqualTo(DiagSeverity.WARNING)
    assertThat(state.export!!.text).contains("NET_POOR")
    assertThat(state.exportRefused).isFalse()
  }

  @Test
  fun gliEventiRicordatiSiContano() = runTest(dispatcher) {
    log.record(DiagEvent(1L, DiagCode("NAS_TIMEOUT"), DiagSeverity.WARNING))

    val vm = viewModel()
    advanceUntilIdle()

    assertThat(vm.state.value.recentEvents).isEqualTo(1)
  }

  @Test
  fun aggiornareRileggeTutto() = runTest(dispatcher) {
    var severity = DiagSeverity.OK
    val vm = viewModel(setOf(FakeContributor(DiagSection.TRUST) { listOf(item(DiagCodes.TRUST_APPROVED, DiagSection.TRUST, severity)) }))
    advanceUntilIdle()
    assertThat(vm.state.value.worst(DiagSection.TRUST)).isEqualTo(DiagSeverity.OK)

    severity = DiagSeverity.ERROR
    vm.refresh()
    assertThat(vm.state.value.loading).isTrue()
    advanceUntilIdle()

    assertThat(vm.state.value.worst(DiagSection.TRUST)).isEqualTo(DiagSeverity.ERROR)
    assertThat(vm.state.value.loading).isFalse()
  }

  // --- Test di connessione -----------------------------------------------------------------------------------

  private fun failedReport() = ConnectionReport(
    listOf(
      StageResult(Stage.ROUTE, StageOutcome.PASSED, 4.milliseconds),
      StageResult(Stage.TCP, StageOutcome.FAILED, 6_000.milliseconds, NasError.Timeout),
    ),
    negotiation = null,
  )

  @Test
  fun ilTestParteSoloQuandoLoChiedeLUtente() = runTest(dispatcher) {
    viewModel()
    advanceUntilIdle()

    assertThat(tester.targets).isEmpty()
  }

  @Test
  fun ilTestPassaDaInCorsoAFinitoEIlSuoEsitoEntraNellaSezione() = runTest(dispatcher) {
    tester.report = failedReport()
    val vm = viewModel()
    advanceUntilIdle()

    vm.runConnectionTest()
    assertThat(vm.state.value.connectionTest).isEqualTo(ConnectionTestUiState.Running)
    advanceUntilIdle()

    val finished = vm.state.value.connectionTest as ConnectionTestUiState.Finished
    assertThat((finished.outcome as ConnectionTestOutcome.Done).firstFailure!!.stage).isEqualTo(Stage.TCP)
    val items = vm.state.value.sections.getValue(DiagSection.CONNECTION_TEST)
    assertThat(items.map { it.code }).contains(DiagCodes.NAS_TIMEOUT)
    assertThat(vm.state.value.attentionIn(DiagSection.CONNECTION_TEST)).isAtLeast(1)
    // E nell'esportazione ci sono le fasi, con i tempi.
    assertThat(vm.state.value.export!!.text).contains("\"stage\": \"Stage.TCP\"")
    assertThat(vm.state.value.export!!.text).contains("6000ms")
  }

  @Test
  fun unTestInCorsoNonSiLanciaDueVolte() = runTest(dispatcher) {
    val gate = CompletableDeferred<Unit>()
    val slowTester = object : dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester {
      var runs = 0

      override suspend fun run(
        target: TestTarget,
        credentials: dev.pampa.mycloudgallery.core.security.credentials.NasCredentials?,
        stages: Set<Stage>,
      ): ConnectionReport {
        runs++
        gate.await()
        return ConnectionReport(emptyList(), null)
      }
    }
    val vm = DiagnosticsViewModel(
      emptySet(), log, slowTester, breaker, FakeNasCredentialSource(),
      Optional.of(ConnectionTestInputSource { ConnectionTestInput(target, null) }), vpn,
    )
    advanceUntilIdle()

    vm.runConnectionTest()
    testScheduler.runCurrent() // il test e' partito e aspetta (senza far correre il tempo virtuale fino al tetto)
    vm.runConnectionTest()
    vm.runConnectionTest()
    testScheduler.runCurrent()
    gate.complete(Unit)
    advanceUntilIdle()

    assertThat(slowTester.runs).isEqualTo(1)
  }

  @Test
  fun senzaUnNasConfiguratoIlTestLoDice() = runTest(dispatcher) {
    val vm = viewModel(configured = false)
    advanceUntilIdle()

    vm.runConnectionTest()
    advanceUntilIdle()

    val outcome = (vm.state.value.connectionTest as ConnectionTestUiState.Finished).outcome
    assertThat(outcome).isEqualTo(ConnectionTestOutcome.NotConfigured)
    assertThat(tester.targets).isEmpty()
  }

  @Test
  fun unTesterCheSiRompeDiventaNonConcluso() = runTest(dispatcher) {
    val broken = object : dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester {
      override suspend fun run(
        target: TestTarget,
        credentials: dev.pampa.mycloudgallery.core.security.credentials.NasCredentials?,
        stages: Set<Stage>,
      ): ConnectionReport = error("guasto")
    }
    val vm = DiagnosticsViewModel(
      emptySet(), log, broken, breaker, FakeNasCredentialSource(),
      Optional.of(ConnectionTestInputSource { ConnectionTestInput(target, null) }), vpn,
    )
    advanceUntilIdle()

    vm.runConnectionTest()
    advanceUntilIdle()

    assertThat((vm.state.value.connectionTest as ConnectionTestUiState.Finished).outcome).isEqualTo(ConnectionTestOutcome.TimedOut)
  }

  // --- Esportazione --------------------------------------------------------------------------------------------

  @Test
  fun ilSalvataggioScriveEsattamenteLAnteprima() = runTest(dispatcher) {
    val vm = viewModel(setOf(FakeContributor(DiagSection.NETWORK_AND_NAS) { listOf(item(DiagCodes.NET_PLACE, severity = DiagSeverity.OK)) }))
    advanceUntilIdle()
    val sink = Recording()
    val uri = Uri.parse("content://documenti/diagnostica.json")

    vm.saveExport(uri, sink)
    advanceUntilIdle()

    assertThat(sink.written!!.first).isEqualTo(uri)
    assertThat(sink.written!!.second).isEqualTo(vm.state.value.export!!.text)
    assertThat(vm.state.value.saveResult).isEqualTo(SaveResult.SAVED)
  }

  @Test
  fun unSalvataggioFallitoLoDice() = runTest(dispatcher) {
    val vm = viewModel(setOf(FakeContributor(DiagSection.NETWORK_AND_NAS) { listOf(item()) }))
    advanceUntilIdle()

    vm.saveExport(Uri.parse("content://documenti/x.json"), Recording().apply { succeed = false })
    advanceUntilIdle()

    assertThat(vm.state.value.saveResult).isEqualTo(SaveResult.FAILED)
    vm.clearSaveResult()
    assertThat(vm.state.value.saveResult).isNull()
  }

  @Test
  fun senzaUnaLetturaNonSiSalvaNiente() = runTest(dispatcher) {
    val vm = viewModel()
    val sink = Recording()

    vm.saveExport(Uri.parse("content://documenti/x.json"), sink) // ancora in lettura: niente anteprima
    advanceUntilIdle()

    assertThat(sink.written).isNull()
  }

  // --- WireGuard ----------------------------------------------------------------------------------------------------

  @Test
  fun ilTastoWireGuardEsisteSoloSeWireGuardEInstallato() = runTest(dispatcher) {
    vpn.intent = null
    val without = viewModel()
    advanceUntilIdle()
    assertThat(without.state.value.wireGuardAvailable).isFalse()
    assertThat(without.wireGuardIntent()).isNull()

    vpn.intent = Intent("com.wireguard.android.OPEN")
    val with = viewModel()
    advanceUntilIdle()
    assertThat(with.state.value.wireGuardAvailable).isTrue()
    assertThat(with.wireGuardIntent()!!.action).isEqualTo("com.wireguard.android.OPEN")
  }
}
