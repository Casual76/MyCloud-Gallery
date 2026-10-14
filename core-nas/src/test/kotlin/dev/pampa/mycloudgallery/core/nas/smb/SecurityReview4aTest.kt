package dev.pampa.mycloudgallery.core.nas.smb

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.runBlockingBounded
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.DirectSocketBinding
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 4A (`docs/review/4a-fase4a.md`). Le prove descrivono il comportamento **sicuro**
 * atteso: oggi falliscono, e cosi' dimostrano i problemi.
 *
 * - R3: un impostore all'indirizzo del NAS (un ospite della LAN) risponde alla NEGOTIATE con un pacchetto in formato
 *   cifrato; smbj lancia una `NullPointerException` nel suo thread di lettura, che cattura solo `TransportException`.
 *   Su Android un'eccezione non catturata in un thread qualunque chiude il processo.
 * - R4: l'ora del NAS si ancora alla NEGOTIATE **prima** dell'autenticazione: un impostore che conosce il GUID (pubblico)
 *   e non la password sposta l'ora del NAS dell'app (e con un'ancora gia' presente fa scattare il "salto" di D32).
 */
class SecurityReview4aTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val servers = ArrayList<AutoCloseable>()

  @After
  fun tearDown() {
    servers.forEach { runCatching { it.close() } }
    scope.cancel()
  }

  @Test
  fun r3_unaRispostaCifrataPrimaDellaNegoziazioneNonUccideIlThreadDiLettura() {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { servers += it }
    thread(isDaemon = true, name = "impostore") {
      runCatching {
        server.accept().use { socket ->
          val input = DataInputStream(socket.getInputStream())
          val frame = ByteArray(4).also { input.readFully(it) }
          val length = ((frame[1].toInt() and 0xff) shl 16) or ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
          input.readFully(ByteArray(length)) // la NEGOTIATE del client
          // Un "messaggio cifrato" (SMB2 TRANSFORM_HEADER, 0xFD 'SMB') al posto della risposta: nessuna chiave serve.
          val body = ByteBuffer.allocate(52 + 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0xFD.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
            put(ByteArray(16)) // firma
            put(ByteArray(16)) // nonce
            putInt(16) // dimensione originale
            putShort(0)
            putShort(1) // AES
            putLong(0x11) // sessione
          }.array()
          val out = socket.getOutputStream()
          out.write(byteArrayOf(0, (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
          out.write(body)
          out.flush()
          Thread.sleep(3_000)
        }
      }
    }

    val uncaught = CopyOnWriteArrayList<Pair<String, Throwable>>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += t.name to e }
    try {
      runCatching {
        SmbjTransport(libraryTimeout = 2.seconds).connect(DirectSocketBinding("loopback"), NasAddress("127.0.0.1", port = server.localPort), 2.seconds)
      }
      Thread.sleep(500)
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    // Atteso: la connessione fallisce e basta. Oggi: `NullPointerException` (PacketEncryptor.canDecrypt senza dialetto)
    // non catturata nel thread "Packet Reader for 127.0.0.1": su Android e' la chiusura dell'app.
    assertThat(uncaught.map { (thread, error) -> "$thread: ${error.javaClass.simpleName}" }).isEmpty()
  }

  @Test
  fun r4_unImpostoreSenzaPasswordNonSpostaLOraDelNas() = runBlockingBounded("ora del NAS e impostore") {
    val server = FakeSmbServer(FakeSmbServer.FinalSignature.NONE).also { servers += it }
    val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, DirectSocketBinding("loopback")) }
    val account = NasAccount(NasAddress("127.0.0.1", port = server.port), "test", "slot", server.guid.toString())
    val manager = SmbNasSessionManager(
      account = { account },
      routeGate = gate,
      credentials = { CredentialLoad.Found(NasCredentials("test", "password-di-prova".toCharArray())) },
      breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
      scope = scope,
      transport = SmbjTransport(libraryTimeout = 5.seconds),
      clock = MonotonicClock { System.nanoTime() / 1_000_000 },
    )
    // La sessione con l'impostore viene rifiutata (firma finale assente): bene.
    assertThat(manager.withShare("foto", Purpose.INTERACTIVE) { }.errorOrNull()).isNotNull()

    // Atteso: nessuna ora da un server che non ha dimostrato di conoscere la password.
    // Oggi: l'ancora e' gia' stata presa dalla NEGOTIATE (SmbNasSessionManager, prima di authenticateOnce).
    assertThat(manager.nasClock.nowMillisOrNull()).isNull()
  }
}
