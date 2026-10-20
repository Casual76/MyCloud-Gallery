package dev.pampa.mycloudgallery.core.nas.integration

import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.time.Duration

/**
 * **D-13** (misure sul WD): un binding che passa dal PC al NAS attraverso un **ripetitore** nel processo dei test ([ZombieRelay]), cosi' il
 * test puo' far "morire" una connessione SMB come muore sul telefono, **senza** chiudere niente con SMB (nessuna `CLOSE`, nessun `LOGOFF`):
 * - [ZombieRelay.cutRst] / [ZombieRelay.cutFin]: il processo muore con la rete su (il kernel chiude il socket: RST o FIN arrivano al NAS);
 * - [ZombieRelay.freeze]: la rete se ne va senza che il NAS lo sappia. Dal lato del NAS la connessione resta viva e muta: il PC risponde
 *   ancora a livello TCP (non si puo' far sparire un socket del PC senza toccare il firewall), quindi il NAS la tiene finche' qualcuno non la
 *   chiude. E' il caso del telefono morto con il Wi-Fi giu', visto dal NAS fino al suo primo controllo della connessione.
 *
 * Nessun tipo di smbj: il ripetitore copia byte. La guardia dell'area di prova resta davanti al trasporto, come sempre.
 */
internal class RelayBinding(private val direct: SocketBinding, override val networkId: String = direct.networkId) : SocketBinding {
  val relays: MutableList<ZombieRelay> = CopyOnWriteArrayList()

  override fun connect(addr: InetAddress, port: Int, timeout: Duration): Socket {
    val nas = direct.connect(addr, port, timeout)
    return try {
      ZombieRelay(nas).also { relays += it }.client
    } catch (e: IOException) {
      nas.close()
      throw e
    }
  }

  /** L'ultimo ripetitore aperto (la connessione della sessione di adesso). */
  val last: ZombieRelay get() = relays.last()
}

/** Un ripetitore fra il client SMB (socket in loopback, [client]) e il NAS ([nas]). */
internal class ZombieRelay(private val nas: Socket) {
  private val inner: Socket
  val client: Socket

  @Volatile private var frozen = false

  /** I byte arrivati dal NAS dopo [freeze] (un keepalive del NAS, una risposta tardiva): contati e buttati. */
  val bytesFromNasAfterFreeze = AtomicLong(0)

  /** Quando il NAS ha chiuso il suo lato (fine del flusso o errore), in ms dall'epoca; 0 = mai. */
  val nasClosedAt = AtomicLong(0)

  init {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      client = Socket()
      client.connect(server.localSocketAddress, CONNECT_MS)
      inner = server.accept()
    }
    thread(isDaemon = true, name = "ripetitore-su") { pump(inner.getInputStream(), nas.getOutputStream(), up = true) }
    thread(isDaemon = true, name = "ripetitore-giu") { pump(nas.getInputStream(), inner.getOutputStream(), up = false) }
  }

  private fun pump(from: InputStream, to: OutputStream, up: Boolean) {
    val buffer = ByteArray(BUFFER)
    try {
      while (true) {
        val n = from.read(buffer)
        if (n < 0) break
        if (frozen) {
          if (!up) bytesFromNasAfterFreeze.addAndGet(n.toLong())
          continue
        }
        to.write(buffer, 0, n)
        to.flush()
      }
    } catch (_: IOException) {
      // Socket chiuso da una delle due parti.
    }
    if (!up) nasClosedAt.compareAndSet(0, System.currentTimeMillis())
    // Il client ha chiuso: con il ripetitore congelato il NAS non lo deve sapere (la rete "non c'e'"); altrimenti si chiude come farebbe il kernel.
    if (up && !frozen) quietly { nas.shutdownOutput() }
    if (!up && !frozen) quietly { inner.close() }
  }

  /** La rete se ne va: da qui niente passa piu' in nessuna direzione, e il NAS non riceve ne' FIN ne' RST. */
  fun freeze() {
    frozen = true
  }

  /** Il processo muore con la rete su e dati non letti: il kernel manda RST al NAS. Nessuna richiesta SMB parte. */
  fun cutRst() {
    frozen = true
    quietly { nas.setSoLinger(true, 0) }
    quietly { nas.close() }
    quietly { inner.close() }
  }

  /** Il processo muore con la rete su: il kernel chiude il socket e il NAS riceve FIN. Nessuna richiesta SMB parte. */
  fun cutFin() {
    frozen = true
    quietly { nas.shutdownOutput() }
    quietly { inner.close() }
  }

  /** Fine della prova: il NAS riceve RST e chiude la connessione (e i suoi handle). */
  fun close() = cutRst()

  private inline fun quietly(block: () -> Unit) {
    try {
      block()
    } catch (_: IOException) {
      // chiusura: niente da dire
    }
  }

  private companion object {
    const val BUFFER = 64 * 1024
    const val CONNECT_MS = 2_000
  }
}
