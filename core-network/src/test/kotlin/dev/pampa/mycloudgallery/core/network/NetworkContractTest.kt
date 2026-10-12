package dev.pampa.mycloudgallery.core.network

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.noop.ClosedNasRouteGate
import dev.pampa.mycloudgallery.core.network.noop.NoVpnController
import dev.pampa.mycloudgallery.core.network.noop.OfflineNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.testing.Snapshots
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.NetworkClassifier
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NetworkContractTest {

  @Test
  fun lIndirizzoDelNasEUnIpLetteraleMaiUnNome() {
    assertThat(NasAddress.parseOrNull("192.168.178.58")).isNotNull()
    assertThat(NasAddress.parseOrNull("fd12:3456:789a::58")).isNotNull()
    assertThat(NasAddress.parseOrNull("::ffff:192.168.178.58")).isNotNull()
    assertThat(NasAddress.parseOrNull("1:2:3:4:5:6:7:8")).isNotNull()
    // Nomi, forme ambigue e zone: rifiutati (nessuna risoluzione DNS, E-R7).
    for (bad in listOf("nas.fritz.box", "MyCloudEX2", "192.168.178", "192.168.178.256", "192.168.01.1", "fe80::1%wlan0", "1::2::3", "1:2:3:4:5:6:7:8:9", "")) {
      assertThat(NasAddress.parseOrNull(bad)).isNull()
    }
  }

  @Test
  fun lIndirizzoSiCostruisceDaiByteENonFinisceNeiLog() {
    val nas = NasAddress("192.168.178.58", name = "MyCloudEX2Ultra")
    assertThat(nas.toInetAddress().hostAddress).isEqualTo("192.168.178.58")
    assertThat(nas.port).isEqualTo(445)
    assertThat(nas.toString()).doesNotContain("192.168")
    assertThat(nas.toString()).doesNotContain("MyCloud")
    assertThat(NasAddress("fd00::1").toInetAddress().address.size).isEqualTo(16)
  }

  @Test
  fun iNullObjectFallisconoChiusi() = runTest {
    val candidate = HomeCandidate("wifi-1", setOf("fd12:3456:789a::/48"), "192.168.178.1", "fritz.box", "192.168.178.0/24")
    assertThat(ClosedNasRouteGate.current()).isEqualTo(NasRoute.Denied(DenyReason.NOT_CONFIGURED))
    assertThat(ClosedNasRouteGate.onboardingRoute(candidate)).isInstanceOf(NasRoute.Denied::class.java)
    assertThat(OfflineNetworkEnvironment.isAtHome.value).isFalse()
    assertThat(OfflineNetworkEnvironment.snapshot.value.place).isEqualTo(NetPlace.OFFLINE)
    assertThat(NoVpnController.setTunnel(up = true)).isInstanceOf(VpnCommandResult.Skipped::class.java)
    assertThat(NoVpnController.openWireGuardIntent()).isNull()
  }

  @Test
  fun ilFakeTraduceLIstantaneaPerLeRegole() {
    val env = FakeNetworkEnvironment(Snapshots.home())
    assertThat(env.isAtHome.value).isTrue()
    assertThat(NetworkClassifier.classify(env.toNetworkState())).isEqualTo(NetworkClass.HOME_WIFI)

    env.current = Snapshots.awayVpnMobile()
    assertThat(env.isAtHome.value).isFalse()
    assertThat(NetworkClassifier.classify(env.toNetworkState())).isEqualTo(NetworkClass.MOBILE)

    env.current = Snapshots.untrusted()
    assertThat(NetworkClassifier.classify(env.toNetworkState())).isEqualTo(NetworkClass.UNTRUSTED)

    env.current = Snapshots.offline()
    assertThat(NetworkClassifier.classify(env.toNetworkState())).isEqualTo(NetworkClass.OFFLINE)
  }
}
