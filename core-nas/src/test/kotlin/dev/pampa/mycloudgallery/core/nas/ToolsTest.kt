package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.SessionNasClock
import dev.pampa.mycloudgallery.core.nas.status.NasStatusMonitor
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nas.testing.TcpBehaviour
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeReachabilityProbe
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolsTest {

  private fun TestScope.harness() = Harness(backgroundScope, virtualClock())

  private fun Harness.tester() = SmbConnectionTester(gate, breakers, backend, clock, NasSessionConfig(), Dispatchers.Unconfined)

  private fun Harness.target(share: String? = SHARE, guid: String? = backend.serverGuid, candidate: HomeCandidate? = null) =
    TestTarget(NasAddress("192.168.178.58"), USER, share, guid, candidate)

  private fun credentials(password: String = GOOD_PASSWORD) = NasCredentials(USER, password.toCharArray())

  // --- ConnectionTester ---------------------------------------------------------------------------------------

  @Test
  fun testDiConnessioneCompleto() = runTest {
    val h = harness()
    val report = h.tester().run(h.target(), credentials())
    assertThat(report.stages.map { it.stage }).containsExactlyElementsIn(Stage.entries).inOrder()
    assertThat(report.stages.map { it.outcome }.toSet()).containsExactly(StageOutcome.PASSED)
    assertThat(report.passed).isTrue()
    assertThat(report.negotiation?.dialect).isEqualTo(SmbDialect.SMB_3_1_1)
    assertThat(report.negotiation?.encryptionOffered).isTrue()
    assertThat(report.negotiation?.serverGuid).isEqualTo(h.backend.serverGuid)
    // Il test usa una connessione sua e la chiude; la sessione del gestore non c'entra.
    assertThat(h.backend.openConnections).isEqualTo(0)
  }

  @Test
  fun percorsoNegatoNessunaTcp() = runTest {
    val h = harness()
    h.gate.deny(DenyReason.UNTRUSTED_NETWORK)
    val report = h.tester().run(h.target(), credentials())
    assertThat(report.firstFailure?.stage).isEqualTo(Stage.ROUTE)
    assertThat(report.firstFailure?.error).isEqualTo(NasError.NotAllowedHere(DenyReason.UNTRUSTED_NETWORK))
    assertThat(report.stages.drop(1).map { it.outcome }.toSet()).containsExactly(StageOutcome.NOT_RUN)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(0)
  }

  @Test
  fun tcpRifiutataEGuidDiverso() = runTest {
    val refused = harness().apply { backend.tcp = TcpBehaviour.REFUSE }
    val report = refused.tester().run(refused.target(), credentials())
    assertThat(report.firstFailure?.let { it.stage to it.error }).isEqualTo(Stage.TCP to NasError.ServiceDown)

    val other = harness()
    val mismatch = other.tester().run(other.target(guid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), credentials())
    assertThat(mismatch.firstFailure?.let { it.stage to it.error }).isEqualTo(Stage.GUID to NasError.IdentityMismatch)
    assertThat(other.backend.authenticateCalls.get()).isEqualTo(0)
    assertThat(mismatch.negotiation?.serverGuid).isEqualTo(other.backend.serverGuid)
  }

  @Test
  fun senzaCredenzialiSiFermaPrimaDellAutenticazione() = runTest {
    val h = harness()
    val report = h.tester().run(h.target(guid = null), credentials = null)
    val byStage = report.stages.associate { it.stage to it.outcome }
    assertThat(byStage.getValue(Stage.GUID)).isEqualTo(StageOutcome.PASSED)
    assertThat(byStage.getValue(Stage.AUTHENTICATE)).isEqualTo(StageOutcome.SKIPPED)
    assertThat(byStage.getValue(Stage.READ)).isEqualTo(StageOutcome.NOT_RUN)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)

    // Solo le fasi fino al GUID (passo 2 dell'onboarding): il rapporto e' "passato".
    val early = h.tester().run(h.target(guid = null), null, setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID))
    assertThat(early.passed).isTrue()
    assertThat(early.stages).hasSize(4)
  }

  @Test
  fun passwordSbagliataUnTentativoPoiFaseSaltata() = runTest {
    val h = harness()
    val first = h.tester().run(h.target(), credentials("sbagliata"))
    assertThat(first.firstFailure?.let { it.stage to it.error }).isEqualTo(Stage.AUTHENTICATE to NasError.Auth(AuthBlockReason.AUTH_FAILED))
    val second = h.tester().run(h.target(), credentials("sbagliata"))
    val auth = second.stages.first { it.stage == Stage.AUTHENTICATE }
    assertThat(auth.outcome).isEqualTo(StageOutcome.SKIPPED)
    assertThat(auth.error).isEqualTo(NasError.AuthBlocked(AuthBlockReason.AUTH_FAILED))
    assertThat(second.passed).isFalse()
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(1)
    // Stesso interruttore della sessione: anche il gestore non autentica.
    assertThat(h.manager.withShare(SHARE, Purpose.INTERACTIVE) { }.let { (it as? dev.pampa.mycloudgallery.core.nas.contract.NasResult.Err)?.error })
      .isEqualTo(NasError.AuthBlocked(AuthBlockReason.AUTH_FAILED))
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(1)
  }

  @Test
  fun faseNonChiestaFallitaRicadeSullaPrimaChiesta() = runTest {
    val h = harness()
    h.backend.tcp = TcpBehaviour.TIMEOUT
    val report = h.tester().run(h.target(), credentials(), setOf(Stage.AUTHENTICATE, Stage.SHARE))
    assertThat(report.stages.map { it.stage to it.outcome })
      .containsExactly(Stage.AUTHENTICATE to StageOutcome.FAILED, Stage.SHARE to StageOutcome.NOT_RUN).inOrder()
    assertThat(report.stages.first().error).isEqualTo(NasError.Timeout)
  }

  @Test
  fun shareMancanteNelTest() = runTest {
    val h = harness()
    val report = h.tester().run(h.target(share = "non-esiste"), credentials())
    assertThat(report.firstFailure?.let { it.stage to it.error }).isEqualTo(Stage.SHARE to NasError.ShareMissing("non-esiste"))
  }

  @Test
  fun percorsoDellOnboardingDalCandidato() = runTest {
    val h = harness()
    h.gate.deny(DenyReason.NOT_CONFIGURED)
    h.gate.onboarding = dev.pampa.mycloudgallery.core.network.contract.NasRoute.Allowed(Via.HOME_LAN, h.backend.binding("wifi-candidato"))
    val candidate = HomeCandidate("wifi-candidato", setOf("fdd1:1234:5678::/48"), "192.168.178.1", "fritz.box", "192.168.178.0/24")
    val report = h.tester().run(h.target(candidate = candidate), credentials())
    assertThat(report.passed).isTrue()
    assertThat(h.backend.connectedNetworks.toSet()).containsExactly("wifi-candidato")
  }

  // --- SessionNasClock ----------------------------------------------------------------------------------------

  @Test
  fun orologioDelNasAncoratoAllaNegotiate() = runTest {
    val monotonic = ManualMonotonicClock(1_000)
    val clock = SessionNasClock(monotonic)
    assertThat(clock.nowMillisOrNull()).isNull()
    assertThrows(NasIoException::class.java) { clock.nowMillis() }
    val nas = 1_791_763_200_000L
    assertThat(clock.anchor(nas, monotonic.nowMillis())).isTrue()
    monotonic.advance(90_000)
    assertThat(clock.nowMillis()).isEqualTo(nas + 90_000)
    // Ora implausibile (NAS con l'orologio azzerato): ignorata.
    assertThat(clock.anchor(0L, monotonic.nowMillis())).isFalse()
    assertThat(clock.nowMillis()).isEqualTo(nas + 90_000)
    // Salto di un'ora tra due NEGOTIATE: segnalato.
    assertThat(clock.lastJumpMs.value).isNull()
    clock.anchor(nas + 90_000 + 3_600_000, monotonic.nowMillis())
    assertThat(clock.lastJumpMs.value).isEqualTo(3_600_000L)
  }

  @Test
  fun ilGestoreAncoraLOraDelNasEApreUnaSessioneSeServe() = runTest {
    val h = harness()
    val nas = 1_791_763_200_000L
    h.backend.nasTime = { nas }
    val clock = h.manager.ensureFreshClock().value()
    assertThat(clock.nowMillis()).isEqualTo(nas)
    advanceTimeBy(10.seconds)
    assertThat(clock.nowMillis()).isEqualTo(nas + 10_000)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
    // Con l'ora gia' fresca non si riapre niente.
    h.manager.ensureFreshClock().value()
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
  }

  // --- NasStatusMonitor ---------------------------------------------------------------------------------------

  @Test
  fun raggiungibilitaDallaSessioneEDallaSondaTcp() = runTest {
    val h = harness()
    val probe = FakeReachabilityProbe(ProbeResult.Open(5.milliseconds))
    val monitor = NasStatusMonitor(h.manager, h.gate, probe, { h.account }, backgroundScope)
    runCurrent()
    assertThat(probe.probes).isEqualTo(1)
    assertThat(monitor.isReachable.value).isTrue()

    h.backend.tcp = TcpBehaviour.REFUSE
    h.manager.withShare(SHARE, Purpose.BACKGROUND) { }
    runCurrent()
    assertThat(monitor.isReachable.value).isFalse()

    h.backend.tcp = TcpBehaviour.ACCEPT
    h.manager.withShare(SHARE, Purpose.BACKGROUND) { }
    runCurrent()
    assertThat(monitor.isReachable.value).isTrue()

    h.gate.deny(DenyReason.VPN_OFF_AWAY)
    runCurrent()
    assertThat(monitor.isReachable.value).isFalse()
    // La sonda non tocca i dischi e non parte con il percorso negato.
    assertThat(monitor.probeNow()).isNull()
    assertThat(probe.probes).isEqualTo(1)
  }

  @Test
  fun unBloccoRendeLaReteScadentePerDieciMinuti() = runTest {
    val clock = ManualMonotonicClock()
    val h = Harness(backgroundScope, clock)
    val monitor = NasStatusMonitor(h.manager, h.gate, FakeReachabilityProbe(), { h.account }, backgroundScope)
    runCurrent()
    assertThat(monitor.isPoor.value).isFalse()
    h.backend.put(SHARE, "video.mp4", ByteArray(100 * 1024))
    h.backend.maxBytesPerRead = 1024
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) clock.advance(1000) }
    assertThrows(NasIoException::class.java) { h.manager.fileSystem(SHARE).read(SharePath.of("video.mp4"), 1 shl 20) }
    runCurrent()
    assertThat(monitor.isPoor.value).isTrue()
    advanceTimeBy(9.minutes)
    runCurrent()
    assertThat(monitor.isPoor.value).isTrue()
    advanceTimeBy(1.minutes + 1.seconds)
    runCurrent()
    assertThat(monitor.isPoor.value).isFalse()
  }

  // --- Regole R8 ------------------------------------------------------------------------------------------------

  @Test
  fun regoleR8PerSmbjEMbassador() {
    val rules = File("consumer-rules.pro").readText()
    listOf(
      "-keep class net.engio.mbassy.** { *; }",
      "@net.engio.mbassy.listener.Handler *;",
      "-keep class com.hierynomus.** { *; }",
      "-dontwarn javax.el.**",
      "-dontwarn org.ietf.jgss.**",
    ).forEach { assertThat(rules).contains(it) }
  }
}
