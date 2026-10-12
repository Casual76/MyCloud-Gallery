package dev.pampa.mycloudgallery.core.network.probe

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.DirectSocketBinding
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** La sonda e' una connessione TCP e basta: nessuna richiesta SMB (E-R15). */
class TcpReachabilityProbeTest {

  private val probe = TcpReachabilityProbe()

  private fun routeFor(binding: SocketBinding) = NasRoute.Allowed(Via.HOME_LAN, binding)

  private fun nasAt(port: Int) = NasAddress("127.0.0.1", port = port)

  /** Un legame che consegna l'eccezione data. */
  private class Failing(private val error: IOException) : SocketBinding {
    var attempts = 0
    override val networkId: String = "failing"

    override fun connect(addr: InetAddress, port: Int, timeout: Duration): Socket {
      attempts++
      throw error
    }
  }

  @Test
  fun laPortaRisponde_eOpen_eSiChiudeSubito() = runTest {
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
      val result = probe.tcp445(routeFor(DirectSocketBinding()), nasAt(server.localPort), 2.seconds)

      assertThat(result).isInstanceOf(ProbeResult.Open::class.java)
      // Il server vede una connessione che si chiude senza aver mandato un solo byte.
      server.accept().use { accepted ->
        accepted.soTimeout = 2_000
        assertThat(accepted.getInputStream().read()).isEqualTo(-1)
      }
    }
  }

  @Test
  fun laPortaChiusa_eRefused() = runTest {
    val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    val result = probe.tcp445(routeFor(DirectSocketBinding()), nasAt(port), 2.seconds)

    assertThat(result).isEqualTo(ProbeResult.Refused)
  }

  @Test
  fun classificaLeEccezioni() = runTest {
    fun result(error: IOException) = TcpReachabilityProbe.classify(error)

    assertThat(result(SocketTimeoutException("failed to connect after 3000ms"))).isEqualTo(ProbeResult.TimedOut)
    assertThat(result(ConnectException("failed to connect to /192.168.178.50 (port 445) after 3000ms: isConnected failed: ETIMEDOUT (Connection timed out)")))
      .isEqualTo(ProbeResult.TimedOut)
    assertThat(result(ConnectException("isConnected failed: ECONNREFUSED (Connection refused)"))).isEqualTo(ProbeResult.Refused)
    assertThat(result(ConnectException("connect failed: ENETUNREACH (Network is unreachable)"))).isEqualTo(ProbeResult.NoRoute)
    assertThat(result(NoRouteToHostException("No route to host"))).isEqualTo(ProbeResult.NoRoute)
    assertThat(result(ConnectException("connect failed: EHOSTUNREACH (No route to host)"))).isEqualTo(ProbeResult.NoRoute)
    assertThat(result(IOException("Socket closed"))).isEqualTo(ProbeResult.NoRoute)
  }

  @Test
  fun lErroreDelLegameSiTraduceInUnEsito_senzaMaiPropagare() = runTest {
    val failing = Failing(SocketTimeoutException("scaduto"))

    val result = probe.tcp445(routeFor(failing), nasAt(445), 1.seconds)

    assertThat(result).isEqualTo(ProbeResult.TimedOut)
    assertThat(failing.attempts).isEqualTo(1)
  }

  @Test
  fun passaSempreDalLegameDellaRotta() = runTest {
    val noRoute = Failing(NoRouteToHostException("rete caduta"))

    assertThat(probe.tcp445(routeFor(noRoute), nasAt(445), 1.seconds)).isEqualTo(ProbeResult.NoRoute)
    assertThat(noRoute.attempts).isEqualTo(1)
  }
}
