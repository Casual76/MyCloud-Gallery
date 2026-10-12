package dev.pampa.mycloudgallery.core.network.gate

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il legame del socket alla rete (D1): il socket nasce NON connesso dalla fabbrica della rete e si connette solo
 * dopo, con un tempo massimo e senza DNS. La fabbrica vera (`Network.socketFactory`) si prova su Robolectric
 * (`AndroidBindingFactoryTest`).
 */
class NetworkSocketBindingTest {

  /** Una fabbrica che registra come e' stata usata e consegna socket veri (sul loopback). */
  private class SpyFactory : SocketFactory() {
    var noArgCreated = 0
    val sockets = mutableListOf<Socket>()
    var throwOnConnect: IOException? = null

    /** Registra i parametri e basta: serve a provare l'arrotondamento senza dipendere dalla rete. */
    var skipRealConnect = false

    override fun createSocket(): Socket {
      noArgCreated++
      return object : Socket() {
        override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
          throwOnConnect?.let { throw it }
          lastTimeout = timeout
          lastEndpoint = endpoint
          if (!skipRealConnect) super.connect(endpoint, timeout)
        }
      }.also { sockets += it }
    }

    var lastTimeout: Int = -1
    var lastEndpoint: java.net.SocketAddress? = null

    // Le altre forme risolverebbero un nome o si connetterebbero senza legame: non devono mai essere usate.
    override fun createSocket(host: String?, port: Int): Socket = throw AssertionError("createSocket(host, port) risolve il nome")

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw AssertionError("non usare")

    override fun createSocket(host: InetAddress?, port: Int): Socket = throw AssertionError("non usare")

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw AssertionError("non usare")
  }

  @Test
  fun ilSocketNasceNonConnessoEPoiSiConnetteConIlTempoMassimo() {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      val factory = SpyFactory()
      val binding = NetworkSocketBinding("net-1", factory)

      val socket = binding.connect(server.inetAddress, server.localPort, 2.seconds)

      assertThat(socket.isConnected).isTrue()
      assertThat(factory.noArgCreated).isEqualTo(1)
      assertThat(factory.lastTimeout).isEqualTo(2000)
      assertThat((factory.lastEndpoint as InetSocketAddress).isUnresolved).isFalse()
      socket.close()
    }
  }

  @Test
  fun seLaConnessioneFallisce_ilSocketSiChiude() {
    val factory = SpyFactory()
    factory.throwOnConnect = java.net.ConnectException("rifiutata")
    val binding = NetworkSocketBinding("net-1", factory)

    assertThrows(IOException::class.java) { binding.connect(InetAddress.getLoopbackAddress(), 445, 1.seconds) }

    assertThat(factory.sockets).hasSize(1)
    assertThat(factory.sockets.single().isClosed).isTrue()
  }

  @Test
  fun unTimeout_siPropagaEIlSocketSiChiude() {
    val factory = SpyFactory()
    factory.throwOnConnect = SocketTimeoutException("tempo scaduto")
    val binding = NetworkSocketBinding("net-1", factory)

    assertThrows(SocketTimeoutException::class.java) { binding.connect(InetAddress.getLoopbackAddress(), 445, 1.seconds) }

    assertThat(factory.sockets.single().isClosed).isTrue()
  }

  @Test
  fun unTempoMassimoNonPositivoOUnaPortaFuoriScala_sonoErrori() {
    val binding = NetworkSocketBinding("net-1", SpyFactory())

    assertThrows(IllegalArgumentException::class.java) { binding.connect(InetAddress.getLoopbackAddress(), 445, Duration.ZERO) }
    assertThrows(IllegalArgumentException::class.java) { binding.connect(InetAddress.getLoopbackAddress(), 0, 1.seconds) }
    assertThrows(IllegalArgumentException::class.java) { binding.connect(InetAddress.getLoopbackAddress(), 70000, 1.seconds) }
  }

  @Test
  fun unTempoMassimoBreveSiArrotondaAUnMillisecondoAlMeno() {
    val factory = SpyFactory().apply { skipRealConnect = true }

    NetworkSocketBinding("net-1", factory).connect(InetAddress.getLoopbackAddress(), 445, 1.milliseconds).close()

    assertThat(factory.lastTimeout).isEqualTo(1)
  }

  @Test
  fun ilLegameDichiaraLaSuaRete() {
    assertThat(NetworkSocketBinding("net-42", SpyFactory()).networkId).isEqualTo("net-42")
  }
}
