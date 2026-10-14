package dev.pampa.mycloudgallery.core.nas.smb

import com.google.common.truth.Truth.assertThat
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2PacketData
import com.hierynomus.mssmb2.SMB3EncryptedPacketData
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
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
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La **domanda aperta 1 della revisione 2** (`docs/review/2-fase2.md`), provata con un server SMB finto su
 * `127.0.0.1` ([FakeSmbServer]): "smbj 0.15 verifica la firma della risposta finale di SESSION_SETUP e rifiuta le
 * risposte non cifrate quando la sessione richiede la cifratura?".
 *
 * **Risposta: no, a nessuna delle due** (caratterizzazione qui sotto: se una versione nuova di smbj cambiasse
 * comportamento, il primo test lo direbbe). Un impostore che ha raccolto la risposta NTLMv2 potrebbe "completare" la
 * sessione e restituire contenuti suoi. Quindi li fa `SmbjTransport` (P1 della Fase 4): verifica la firma della
 * risposta finale prima di mandare qualunque richiesta e chiude la connessione alla prima risposta in chiaro.
 */
class SessionSetupSignatureTest {

  private val servers = ArrayList<FakeSmbServer>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After
  fun tearDown() {
    servers.forEach { it.close() }
    scope.cancel()
  }

  private fun server(signature: FakeSmbServer.FinalSignature, encryptFlag: Boolean = true) =
    FakeSmbServer(signature, encryptFlag).also { servers += it }

  private fun address(server: FakeSmbServer) = NasAddress("127.0.0.1", port = server.port)

  private fun connect(server: FakeSmbServer): SmbConnection =
    SmbjTransport(libraryTimeout = 5.seconds).connect(DirectSocketBinding("loopback"), address(server), 5.seconds)

  @Test
  fun smbjDaSoloAccettaUnaSessioneNonFirmataEUnaRispostaInChiaro() {
    val server = server(FakeSmbServer.FinalSignature.NONE)
    val config = SmbConfig.builder()
      .withDialects(SMB2Dialect.SMB_3_1_1)
      .withSigningRequired(true)
      .withEncryptData(true)
      .withMultiProtocolNegotiate(false)
      .withTimeout(5, TimeUnit.SECONDS)
      .build()
    SMBClient(config).use { client ->
      val connection = client.connect("127.0.0.1", server.port)
      try {
        // Il login con l'impostore riesce: nessuna verifica della firma finale.
        val session = connection.authenticate(AuthenticationContext("test", "una-password-qualsiasi".toCharArray(), ""))
        assertThat(server.sessionCompleted).isTrue()
        assertThat(session.shouldEncryptData()).isTrue()
        // La TREE_CONNECT parte cifrata, la risposta torna IN CHIARO e smbj la accetta: la share "esiste".
        val share = session.connectShare("foto")
        assertThat(share).isInstanceOf(DiskShare::class.java)
        assertThat(server.encryptedRequests.get()).isEqualTo(1)
      } finally {
        connection.close(true)
      }
    }
  }

  @Test
  fun ilTrasportoRifiutaLaRispostaFinaleNonFirmataPrimaDiQualunqueRichiesta() {
    val server = server(FakeSmbServer.FinalSignature.NONE)
    val connection = connect(server)
    assertThat(connection.negotiation.serverGuid).isEqualTo(server.guid.toString())
    val error = assertThrows(SmbInsecureSessionException::class.java) {
      connection.authenticate("test", "una-password-qualsiasi".toCharArray())
    }
    assertThat(error.defect).isEqualTo(SessionDefect.UNSIGNED_FINAL_RESPONSE)
    assertThat(connection.isConnected).isFalse()
    assertThat(server.encryptedRequests.get()).isEqualTo(0)
  }

  @Test
  fun ancheUnaFirmaInventataERifiutata() {
    val server = server(FakeSmbServer.FinalSignature.GARBAGE)
    val error = assertThrows(SmbInsecureSessionException::class.java) {
      connect(server).authenticate("test", "password-di-prova".toCharArray())
    }
    assertThat(error.defect).isEqualTo(SessionDefect.UNSIGNED_FINAL_RESPONSE)
    assertThat(server.encryptedRequests.get()).isEqualTo(0)
  }

  @Test
  fun conLaFirmaGiustaLaSessioneVaMaUnaRispostaInChiaroChiudeTutto() {
    // Chi conosce la password firma bene: la verifica passa (la prova in positivo del calcolo). Ma poi risponde in
    // chiaro su una sessione cifrata: la guardia chiude la connessione e nessun dato arriva su.
    val server = server(FakeSmbServer.FinalSignature.VALID)
    val connection = connect(server)
    val session = connection.authenticate("test", "password-di-prova".toCharArray())
    assertThat(session.facts.encrypted).isTrue()
    val error = assertThrows(SmbInsecureSessionException::class.java) { session.connectShare("foto") }
    assertThat(error.defect).isEqualTo(SessionDefect.PLAINTEXT_RESPONSE)
    assertThat(connection.isConnected).isFalse()
  }

  @Test
  fun laRispostaInChiaroERifiutataAncheSenzaIlFlagDiCifratura() {
    // Senza ENCRYPT_DATA smbj cifra lo stesso (cifratura scelta dal client): la risposta in chiaro resta un rifiuto.
    val server = server(FakeSmbServer.FinalSignature.VALID, encryptFlag = false)
    val session = connect(server).authenticate("test", "password-di-prova".toCharArray())
    assertThat(session.facts.encrypted).isTrue()
    assertThat(assertThrows(SmbInsecureSessionException::class.java) { session.connectShare("foto") }.defect)
      .isEqualTo(SessionDefect.PLAINTEXT_RESPONSE)
  }

  @Test
  fun ilGestoreDellaSessioneDiceSessioneInsicuraENonRiprova() = runBlockingBounded("il gestore con l'impostore") {
    val server = server(FakeSmbServer.FinalSignature.NONE)
    val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, DirectSocketBinding("loopback")) }
    val account = NasAccount(address(server), "test", "slot", server.guid.toString())
    val manager = SmbNasSessionManager(
      account = { account },
      routeGate = gate,
      credentials = { CredentialLoad.Found(NasCredentials("test", "password-di-prova".toCharArray())) },
      breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
      scope = scope,
      transport = SmbjTransport(libraryTimeout = 5.seconds),
      clock = MonotonicClock { System.nanoTime() / 1_000_000 },
    )
    val expected = NasError.InsecureSession(InsecureKind.SIGNING_NOT_REQUIRED)
    assertThat(manager.withShare("foto", Purpose.INTERACTIVE) { }.errorOrNull()).isEqualTo(expected)
    // Agganciato: sulla stessa rete nessun nuovo tentativo (e nessuna nuova risposta NTLMv2 regalata).
    assertThat(manager.mediaReader().list("foto", null, Purpose.BACKGROUND) {}.errorOrNull()).isEqualTo(expected)
    assertThat(server.connections.get()).isEqualTo(1)
  }

  @Test
  fun laGuardiaRegistraLaRispostaFinaleEPoiVuoleSoloPacchettiCifrati() {
    val guard = ResponseGuard()
    val sessionSetupOk = smb2Header(command = 1, status = 0)
    val plainRead = smb2Header(command = 8, status = 0)
    assertThat(guard.accept(SMB2PacketData(sessionSetupOk))).isTrue()
    assertThat(guard.finalSessionSetup()).isEqualTo(sessionSetupOk)
    // Prima del login (nessuna sessione cifrata) il chiaro e' normale.
    assertThat(guard.accept(SMB2PacketData(plainRead))).isTrue()

    guard.arm()
    assertThat(guard.accept(SMB3EncryptedPacketData(transformHeader()))).isTrue()
    assertThat(guard.accept(SMB2PacketData(plainRead))).isFalse()
    assertThat(guard.violation).isEqualTo(SessionDefect.PLAINTEXT_RESPONSE)
    // Ogni errore successivo sulla connessione e' la violazione, non una "caduta" da riprovare.
    val translated = guard.translate(java.io.IOException("socket chiuso"))
    assertThat(translated).isInstanceOf(SmbInsecureSessionException::class.java)
  }

  private fun smb2Header(command: Int, status: Long): ByteArray =
    java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
      put(byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
      putShort(64)
      putShort(1)
      putInt(status.toInt())
      putShort(command.toShort())
      putShort(1)
      putInt(1)
      putInt(0)
      putLong(7)
      putInt(0)
      putInt(0)
      putLong(0x11)
    }.array()

  private fun transformHeader(): ByteArray =
    java.nio.ByteBuffer.allocate(52 + 16).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
      put(byteArrayOf(0xFD.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
      put(ByteArray(16))
      put(ByteArray(16))
      putInt(16)
      putShort(0)
      putShort(1)
      putLong(0x11)
    }.array()
}
