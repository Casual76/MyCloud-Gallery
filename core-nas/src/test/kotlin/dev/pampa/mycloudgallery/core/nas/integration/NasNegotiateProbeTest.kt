package dev.pampa.mycloudgallery.core.nas.integration

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.INTEGRATION_WAIT_SECONDS
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.runBlockingBounded
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.smb.Negotiation
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Before
import org.junit.Test

/**
 * SOLO NEGOTIATE verso il NAS vero (§5.2, autorizzato dall'orchestratore): nessuna autenticazione, nessuna
 * credenziale, nessun rischio di blocco. Raccoglie dialetto, cifratura offerta, cifrario, GUID letto tre volte,
 * scarto dell'orologio e tempi; li scrive in `build/nas-it/negotiate.txt` e sull'output del test.
 */
@NasIntegration
class NasNegotiateProbeTest {

  @Before
  fun onlyWhenAsked() = NasIntegrationConfig.requireEnabled()

  private fun host(): String = NasIntegrationConfig.credentialsFile.takeIf { it.isFile }?.let { file ->
    java.util.Properties().apply { file.inputStream().use { load(it) } }.getProperty("host")?.trim()?.takeIf { it.isNotEmpty() }
  } ?: NasIntegrationConfig.DEFAULT_HOST

  @Test
  fun soloNegotiate() {
    val address = NasAddress(host())
    val transport = SmbjTransport()
    val lines = ArrayList<String>()
    val negotiations = ArrayList<Negotiation>()
    repeat(3) { round ->
      val binding = TimedDirectBinding()
      val started = System.nanoTime()
      val connection = transport.connect(binding, address, 6.seconds)
      val totalMs = (System.nanoTime() - started) / 1_000_000.0
      try {
        val n = connection.negotiation
        negotiations += n
        lines += String.format(
          Locale.ROOT,
          "giro %d: tcp=%.1f ms, tcp+negotiate=%.1f ms, dialetto=%s, cifraturaOfferta=%s, cifrario=%s, guid=%s…, " +
            "scartoNasMenoPc=%s ms, serverRichiedeFirma=%s",
          round + 1, binding.lastConnectMs, totalMs, n.dialect, n.encryptionOffered, n.cipher, n.serverGuid.take(8),
          n.nasTimeOffsetMs, n.serverRequiresSigning,
        )
      } finally {
        connection.close()
      }
    }
    val guids = negotiations.map { it.serverGuid }.toSet()
    lines += "GUID uguale nei 3 giri: ${guids.size == 1}"

    // Lo stesso, attraverso il test di connessione fermo prima dell'autenticazione (credenziali nulle).
    val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, TimedDirectBinding()) }
    val scope = CoroutineScope(SupervisorJob())
    runBlockingBounded("la sonda NEGOTIATE", INTEGRATION_WAIT_SECONDS) {
      val tester = SmbConnectionTester(
        gate,
        AuthBreakerRegistry(InMemoryBreakerStore(), scope),
        clock = MonotonicClock { System.nanoTime() / 1_000_000 },
      )
      val report = tester.run(
        TestTarget(address, user = null, share = null, expectedGuid = negotiations.first().serverGuid),
        credentials = null,
        stages = setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID),
      )
      lines += "ConnectionTester: " + report.stages.joinToString { "${it.stage}=${it.outcome} (${it.elapsed.inWholeMilliseconds} ms)" }
      assertThat(report.passed).isTrue()
    }
    scope.cancel()

    val text = lines.joinToString("\n")
    println("=== NEGOTIATE reale ===\n$text")
    NasIntegrationConfig.reportFile("negotiate.txt").writeText(text + "\n")
    assertThat(guids).hasSize(1)
  }
}
