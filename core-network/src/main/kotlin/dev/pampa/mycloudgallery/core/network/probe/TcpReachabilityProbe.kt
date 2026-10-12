package dev.pampa.mycloudgallery.core.network.probe

import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * La sonda di raggiungibilita': **una connessione TCP alla 445 e basta**, subito chiusa (E-R15).
 *
 * Non invia nemmeno una richiesta SMB: e' il percorso piu' leggero e non fa girare i dischi del NAS. Mai `list`,
 * `stat` o `getShareInformation` per sapere "se c'e'". Il socket passa dal legame della rotta consentita, quindi
 * non puo' uscire da una rete diversa da quella scelta dal cancello (D1).
 *
 * La connessione e' bloccante e non si interrompe a meta': una cancellazione ha effetto a fine tentativo, che e'
 * comunque limitato da [timeout]. Gli esiti si distinguono dall'eccezione:
 *  - risposta -> [ProbeResult.Open];
 *  - rifiutata (RST: il NAS e' acceso ma SMB non risponde) -> [ProbeResult.Refused];
 *  - nessuna risposta entro il tempo -> [ProbeResult.TimedOut];
 *  - rete irraggiungibile, nessuna rotta, rete caduta -> [ProbeResult.NoRoute].
 */
class TcpReachabilityProbe(
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) : ReachabilityProbe {

  override suspend fun tcp445(route: NasRoute.Allowed, nas: NasAddress, timeout: Duration): ProbeResult =
    withContext(io) {
      val started = timeSource.markNow()
      try {
        route.binding.connect(nas.toInetAddress(), nas.port, timeout).close()
        ProbeResult.Open(started.elapsedNow())
      } catch (e: IOException) {
        classify(e)
      }
    }

  internal companion object {
    fun classify(e: IOException): ProbeResult {
      val message = e.message.orEmpty().lowercase()
      return when {
        e is SocketTimeoutException || "timed out" in message || "etimedout" in message -> ProbeResult.TimedOut
        e is NoRouteToHostException || e is PortUnreachableException -> ProbeResult.NoRoute
        "refused" in message || "econnrefused" in message -> ProbeResult.Refused
        "unreachable" in message || "no route" in message || "enetunreach" in message || "ehostunreach" in message -> ProbeResult.NoRoute
        // Socket chiuso, rete caduta durante la connessione: il binding non porta piu' al NAS.
        else -> ProbeResult.NoRoute
      }
    }
  }
}
