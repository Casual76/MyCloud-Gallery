package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.noop.NoConnectionTester
import dev.pampa.mycloudgallery.core.nas.noop.NoNasSession
import dev.pampa.mycloudgallery.core.nas.testing.FakeAuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasFileSystem
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class NasContractTest {

  @Test
  fun soloGliErroriDAutenticazioneApronoLInterruttore() {
    AuthBlockReason.entries.forEach { assertThat(NasError.Auth(it).opensBreaker).isTrue() }
    val others = listOf(
      NasError.InsecureSession(dev.pampa.mycloudgallery.core.nas.contract.InsecureKind.GUEST),
      NasError.IdentityMismatch,
      NasError.PermissionDenied,
      NasError.Timeout,
      NasError.ServiceDown,
      NasError.NotAllowedHere(DenyReason.UNTRUSTED_NETWORK),
      NasError.LocalNetworkBlocked,
      NasError.Stalled,
      NasError.AuthBlocked(AuthBlockReason.AUTH_FAILED),
    )
    others.forEach { assertThat(it.opensBreaker).isFalse() }
  }

  @Test
  fun nomiDiShareEdErroriNonFinisconoNeiMessaggi() {
    val missing = NasError.ShareMissing("Foto di Mario")
    assertThat(missing.toString()).doesNotContain("Mario")
    assertThat(NasIoException(missing).message).doesNotContain("Mario")
  }

  @Test
  fun ilNullObjectNonEseguenulla() = runTest {
    var ran = false
    val result = NoNasSession.withShare("test-comune", Purpose.INTERACTIVE) { ran = true }
    assertThat(ran).isFalse()
    assertThat(result.errorOrNull()).isEqualTo(NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED))
    assertThrows(NasIoException::class.java) { NoNasSession.fileSystem("x").read(SharePath.of("a"), 10) }

    val report = NoConnectionTester.run(TestTarget(NasAddress("192.168.178.58"), "test", null, null), null)
    assertThat(report.firstFailure?.stage).isEqualTo(Stage.ROUTE)
    assertThat(report.stages.drop(1).map { it.outcome }.toSet()).containsExactly(StageOutcome.NOT_RUN)
  }

  @Test
  fun laShareInMemoriaHaLaSemanticaDiSamba() {
    val fs = InMemoryNasFileSystem()
    val path = SharePath.of(".mycloudgallery", "family", "registry", "1")
    assertThat(fs.publishExclusive(path, byteArrayOf(1, 2, 3))).isEqualTo(PublishResult.CREATED)
    // Stesso nome con maiuscole diverse: per il NAS e' lo stesso file, non si sovrascrive.
    val sameName = SharePath.of(".MyCloudGallery", "FAMILY", "registry", "1")
    assertThat(fs.publishExclusive(sameName, byteArrayOf(9))).isEqualTo(PublishResult.ALREADY_EXISTS)
    assertThat((fs.read(path, maxBytes = 3) as NasRead.Data).bytes).isEqualTo(byteArrayOf(1, 2, 3))
    assertThat(fs.read(path, maxBytes = 2)).isEqualTo(NasRead.TooLarge)
    assertThat(fs.list(SharePath.of(".mycloudgallery", "family"), limit = 10).names).containsExactly("registry")
  }

  @Test
  fun laSessioneFintaNonToccaLaShareSeCeUnErrore() = runTest {
    val session = FakeNasSessionManager()
    session.error = NasError.NotAllowedHere(DenyReason.FOREIGN_VPN)
    var touched = false
    val result = session.withShare("test-comune", Purpose.BACKGROUND) { touched = true }
    assertThat(touched).isFalse()
    assertThat(result).isInstanceOf(NasResult.Err::class.java)
    assertThat(session.calls).hasSize(1)
  }

  @Test
  fun lInterruttoreFintoSiRichiudeSoloConUnaPasswordNuova() = runTest {
    val breaker = FakeAuthCircuitBreaker()
    breaker.open(AuthBlockReason.AUTH_FAILED)
    assertThat(breaker.state.value).isInstanceOf(BreakerState.Open::class.java)
    breaker.onNewPasswordSaved()
    assertThat(breaker.state.value).isEqualTo(BreakerState.Closed(generation = 2))
  }
}
