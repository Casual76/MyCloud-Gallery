package dev.pampa.mycloudgallery.feature.diagnostics.connection

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NegotiationFacts
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.testing.FakeAuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.testing.FakeConnectionTester
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasCredentialSource
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Il test di connessione a richiesta: mai con l'interruttore aperto, un solo tentativo, credenziali azzerate. */
class ConnectionTestRunnerTest {

  private val target = TestTarget(NasAddress("192.168.178.58", "nas"), user = "test", share = "test-comune", expectedGuid = "g-1")
  private val input = ConnectionTestInputSource { ConnectionTestInput(target, credentialSlot = "slot-1") }

  private fun passedReport() = ConnectionReport(
    stages = Stage.entries.mapIndexed { i, stage -> StageResult(stage, StageOutcome.PASSED, (10L * (i + 1)).milliseconds) },
    negotiation = NegotiationFacts(SmbDialect.SMB_3_1_1, encryptionOffered = true, serverGuid = "g-1", timeOffsetMs = 800),
  )

  private fun credentials(next: () -> CredentialLoad = { CredentialLoad.Found(NasCredentials("test", "password-lunga-1".toCharArray())) }) =
    FakeNasCredentialSource(next)

  private fun runner(
    tester: ConnectionTester,
    breaker: FakeAuthCircuitBreaker = FakeAuthCircuitBreaker(),
    credentials: FakeNasCredentialSource = credentials(),
    input: ConnectionTestInputSource? = this.input,
    timeout: Duration = 5.seconds,
  ) = ConnectionTestRunner(tester, breaker, credentials, input, timeout)

  @Test
  fun senzaUnNasConfiguratoNonCeNienteDaProvare() = runTest {
    val tester = FakeConnectionTester()

    val outcome = runner(tester, input = null).run()

    assertThat(outcome).isEqualTo(ConnectionTestOutcome.NotConfigured)
    assertThat(tester.targets).isEmpty()
    assertThat(ConnectionTestOutcome.NotConfigured.toItems().single().code).isEqualTo(DiagCodes.TEST_NOT_CONFIGURED)
  }

  @Test
  fun unNasChePassaTutteLeFasi() = runTest {
    val tester = FakeConnectionTester(passedReport())

    val done = runner(tester).run() as ConnectionTestOutcome.Done

    assertThat(done.passed).isTrue()
    assertThat(done.authSkipped).isFalse()
    assertThat(done.rows.map { it.stage }).containsExactlyElementsIn(Stage.entries).inOrder()
    assertThat(done.rows.map { it.elapsed }.toSet()).hasSize(Stage.entries.size) // ognuna col suo tempo
    assertThat(done.negotiation!!.dialect).isEqualTo(SmbDialect.SMB_3_1_1)
    assertThat(tester.credentialsSeen).isEqualTo(1)
    assertThat(done.toItems().last().code).isEqualTo(DiagCodes.TEST_PASSED)
  }

  @Test
  fun conLInterruttoreApertoNonSiCaricanoNemmenoLeCredenziali() = runTest {
    val tester = FakeConnectionTester(passedReport())
    val breaker = FakeAuthCircuitBreaker().apply { open(AuthBlockReason.AUTH_FAILED) }
    val vault = credentials()

    val done = runner(tester, breaker = breaker, credentials = vault).run() as ConnectionTestOutcome.Done

    // C5: nessun tentativo in piu'. Il tester riceve null e la password non e' stata nemmeno letta dal vault.
    assertThat(tester.credentialsSeen).isEqualTo(0)
    assertThat(vault.loads).isEqualTo(0)
    assertThat(done.authSkipped).isTrue()
    assertThat(done.breakerOpen).isTrue()
    assertThat(done.passed).isFalse()
    val verdict = done.toItems().last()
    assertThat(verdict.code).isEqualTo(DiagCodes.TEST_AUTH_SKIPPED)
    assertThat(verdict.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(verdict.export["breakerOpen"]).isEqualTo(ExportValue.Flag(true))
  }

  @Test
  fun unaPasswordIllegibileSaltaLAutenticazioneSenzaRiprovare() = runTest {
    listOf(CredentialLoad.Absent, CredentialLoad.NeedsPassword, CredentialLoad.Unavailable).forEach { load ->
      val tester = FakeConnectionTester(passedReport())
      val vault = credentials { load }

      val done = runner(tester, credentials = vault).run() as ConnectionTestOutcome.Done

      assertThat(vault.loads).isEqualTo(1) // un solo tentativo di lettura
      assertThat(tester.credentialsSeen).isEqualTo(0)
      assertThat(done.authSkipped).isTrue()
    }
  }

  @Test
  fun unVaultCheLanciaUnaEccezioneNonFaCadereIlTest() = runTest {
    val tester = FakeConnectionTester(passedReport())

    val done = runner(tester, credentials = credentials { error("keystore guasto") }).run() as ConnectionTestOutcome.Done

    assertThat(done.authSkipped).isTrue()
  }

  @Test
  fun leCredenzialiSiAzzeranoAppenaIlTestFinisce() = runTest {
    val seen = mutableListOf<NasCredentials>()
    val tester = object : ConnectionTester {
      override suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage>): ConnectionReport {
        seen += checkNotNull(credentials)
        assertThat(credentials.password.value.concatToString()).isEqualTo("password-lunga-1") // durante il test c'e'
        return passedReport()
      }
    }

    runner(tester).run()

    assertThat(seen.single().password.value.all { it == '\u0000' }).isTrue() // dopo no
  }

  @Test
  fun leCredenzialiSiAzzeranoAnchePerUnTestFallito() = runTest {
    val seen = mutableListOf<NasCredentials>()
    val tester = object : ConnectionTester {
      override suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage>): ConnectionReport {
        seen += checkNotNull(credentials)
        error("la rete e' caduta")
      }
    }

    try {
      runner(tester).run()
    } catch (_: IllegalStateException) {
      // un guasto del tester arriva a chi chiama (il view model lo trasforma in «non concluso»)
    }

    assertThat(seen.single().password.value.all { it == '\u0000' }).isTrue()
  }

  @Test
  fun laPrimaFaseFallitaDiceDoveSiFerma() = runTest {
    val report = ConnectionReport(
      stages = listOf(
        StageResult(Stage.ROUTE, StageOutcome.PASSED, 5.milliseconds),
        StageResult(Stage.TCP, StageOutcome.FAILED, 6.seconds, NasError.Timeout),
        StageResult(Stage.NEGOTIATE, StageOutcome.NOT_RUN, Duration.ZERO),
      ),
      negotiation = null,
    )

    val done = runner(FakeConnectionTester(report)).run() as ConnectionTestOutcome.Done
    val items = done.toItems()

    assertThat(done.passed).isFalse()
    assertThat(done.firstFailure!!.stage).isEqualTo(Stage.TCP)
    // La fase fallita porta il codice dell'errore (NAS_TIMEOUT), con i tempi in millisecondi e il tipo, non il messaggio.
    val tcp = items.first { it.code == DiagCodes.NAS_TIMEOUT }
    assertThat(tcp.severity).isEqualTo(DiagSeverity.ERROR)
    assertThat(tcp.export["elapsed"]).isEqualTo(ExportValue.Dur(6_000))
    assertThat(tcp.export["error"].toString()).isEqualTo("Enum(NasErrorKind.TIMEOUT)")
    assertThat(items.last().code).isEqualTo(DiagCodes.TEST_FAILED)
  }

  @Test
  fun unPercorsoNegatoDaIlCodiceDellaRete() = runTest {
    val report = ConnectionReport(
      listOf(StageResult(Stage.ROUTE, StageOutcome.FAILED, 1.milliseconds, NasError.NotAllowedHere(DenyReason.FOREIGN_VPN))),
      negotiation = null,
    )

    val items = (runner(FakeConnectionTester(report)).run() as ConnectionTestOutcome.Done).toItems()

    assertThat(items.first().code).isEqualTo(DiagCodes.NET_FOREIGN_VPN)
  }

  @Test
  fun unTestCheNonSiConcludeEntroIlTempoSiDichiaraNonConcluso() = runTest {
    val slow = object : ConnectionTester {
      override suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage>): ConnectionReport {
        delay(1.hours)
        return passedReport()
      }
    }

    val outcome = runner(slow, timeout = 20.seconds).run()

    assertThat(outcome).isEqualTo(ConnectionTestOutcome.TimedOut)
    assertThat(outcome.toItems().single().code).isEqualTo(DiagCodes.TEST_FAILED)
  }

  @Test
  fun leFasiHannoLEtichettaELaNegoziazioneUnaVoceTutta() = runTest {
    val done = runner(FakeConnectionTester(passedReport())).run() as ConnectionTestOutcome.Done
    val items = done.toItems()

    Stage.entries.forEach { assertThat(stageLabel(it)).isNotEqualTo(0) }
    val negotiation = items.first { it.export.containsKey("dialect") }
    assertThat(negotiation.export["encryptionOffered"]).isEqualTo(ExportValue.Flag(true))
    assertThat(negotiation.export["clockOffsetMs"]).isEqualTo(ExportValue.Num(800))
    assertThat(dialectLabel(SmbDialect.SMB_3_1_1)).isEqualTo("SMB 3.1.1")
    assertThat(dialectLabel(SmbDialect.UNKNOWN)).isEqualTo("SMB ?")
  }

  @Test
  fun unNasSenzaCifraturaFallisceIlControlloDiSicurezza() = runTest {
    val report = ConnectionReport(
      listOf(
        StageResult(Stage.NEGOTIATE, StageOutcome.PASSED, 3.milliseconds),
        StageResult(
          Stage.SECURITY_CHECK,
          StageOutcome.FAILED,
          2.milliseconds,
          NasError.InsecureSession(dev.pampa.mycloudgallery.core.nas.contract.InsecureKind.NO_ENCRYPTION),
        ),
      ),
      negotiation = NegotiationFacts(SmbDialect.SMB_3_0_2, encryptionOffered = false, serverGuid = "g", timeOffsetMs = 0),
    )

    val items = (runner(FakeConnectionTester(report)).run() as ConnectionTestOutcome.Done).toItems()

    assertThat(items.map { it.code }).contains(DiagCodes.NAS_NOT_ENCRYPTED)
    assertThat(items.first { it.export.containsKey("dialect") }.severity).isEqualTo(DiagSeverity.ERROR)
  }
}
