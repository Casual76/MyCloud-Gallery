package dev.pampa.mycloudgallery.core.network.gate

import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.env.NetworkLookup
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlin.time.Duration

/**
 * Il socket legato a una rete (D1): si crea **non connesso** dalla fabbrica di socket di quella rete
 * (`Network.getSocketFactory()`) e solo dopo si connette, con un tempo massimo.
 *
 * Perche' cosi' e non in un altro modo:
 *  - `createSocket()` senza argomenti e `connect(InetSocketAddress)` su un indirizzo costruito dai byte: nessuna
 *    risoluzione DNS (E-R7) e nessun tentativo di connessione prima del legame con la rete;
 *  - mai `ConnectivityManager.bindProcessToNetwork`: legherebbe tutto il processo, e se la VPN cade il traffico
 *    scivolerebbe sul Wi-Fi di una rete estranea;
 *  - mai `createSocket(host, port)`: risolverebbe il nome e si connetterebbe senza il tempo massimo.
 *
 * Se la connessione fallisce il socket si chiude: nessun descrittore resta aperto.
 *
 * @param factory la fabbrica della rete; su Android e' `network.socketFactory`.
 */
class NetworkSocketBinding(
  override val networkId: String,
  private val factory: SocketFactory,
) : SocketBinding {

  @Throws(IOException::class)
  override fun connect(addr: InetAddress, port: Int, timeout: Duration): Socket {
    require(port in 1..MAX_PORT) { "porta non valida" }
    require(timeout.isPositive()) { "il tempo massimo di connessione deve essere positivo" }
    val millis = timeout.inWholeMilliseconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    val socket = factory.createSocket()
    try {
      socket.connect(InetSocketAddress(addr, port), millis)
    } catch (e: IOException) {
      closeQuietly(socket)
      throw e
    } catch (e: RuntimeException) {
      closeQuietly(socket)
      throw e
    }
    return socket
  }

  private fun closeQuietly(socket: Socket) {
    try {
      socket.close()
    } catch (_: IOException) {
      // gia' chiuso
    }
  }

  override fun toString(): String = "NetworkSocketBinding($networkId)"

  private companion object {
    const val MAX_PORT = 65535
  }
}

/** Crea il [SocketBinding] per una rete, se esiste ancora. Null se la rete e' sparita. */
fun interface BindingFactory {
  fun bindingFor(networkId: String): SocketBinding?
}

/** I legami veri: `Network.socketFactory` della rete scelta dal cancello. */
class AndroidBindingFactory(private val networks: NetworkLookup) : BindingFactory {
  override fun bindingFor(networkId: String): SocketBinding? =
    networks.network(networkId)?.let { NetworkSocketBinding(networkId, it.socketFactory) }
}
