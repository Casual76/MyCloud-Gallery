package dev.pampa.mycloudgallery.core.nas.smb

import com.google.common.truth.Truth.assertThat
import com.hierynomus.mssmb2.SMB3EncryptedPacketData
import com.hierynomus.protocol.transport.PacketReceiver
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smb.SMBPacketData
import com.hierynomus.smbj.common.SMBRuntimeException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/**
 * R3, scenario B, della revisione 4A: dopo il login un attaccante sul percorso inietta un pacchetto cifrato con il
 * `SessionId` visto in rete e un tag falso. smbj lancia `SMBRuntimeException` consegnandolo; il thread di lettura di smbj
 * cattura solo `TransportException`, quindi su Android sarebbe la chiusura dell'app. Il ricevitore della guardia la
 * trasforma in un errore della connessione (chiusa, richieste fallite) e niente esce dal thread.
 */
class GuardedReceiverTest {

  private class Recorder(private val failOnHandle: Throwable?) : PacketReceiver<SMBPacketData<*>> {
    val errors = ArrayList<Throwable>()
    var delivered = 0

    override fun handle(packet: SMBPacketData<*>) {
      delivered++
      failOnHandle?.let { throw it }
    }

    override fun handleError(t: Throwable) {
      errors += t
    }
  }

  /** Un TRANSFORM_HEADER (0xFD 'SMB') con un tag a caso: smbj lo legge come `SMB3EncryptedPacketData`. */
  private fun encrypted(): SMB3EncryptedPacketData {
    val body = ByteBuffer.allocate(52 + 16).order(ByteOrder.LITTLE_ENDIAN).apply {
      put(byteArrayOf(0xFD.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
      put(ByteArray(16) { 7 })
      put(ByteArray(16))
      putInt(16)
      putShort(0)
      putShort(1)
      putLong(0x11)
    }.array()
    return SMB3EncryptedPacketData(body)
  }

  @Test
  fun r3_unPacchettoCifratoConUnTagFalsoDopoIlLoginChiudeLaConnessioneSenzaEccezioniFuoriDalThread() {
    val delegate = Recorder(SMBRuntimeException("tag GCM non valido"))
    val guard = ResponseGuard().apply { arm() }
    val receiver = GuardedReceiver(delegate, guard)

    receiver.handle(encrypted())

    assertThat(delegate.delivered).isEqualTo(1)
    assertThat(delegate.errors).hasSize(1)
    assertThat(delegate.errors.single()).isInstanceOf(TransportException::class.java)
  }

  @Test
  fun r3_primaDelLoginUnPacchettoCifratoNonArrivaNemmenoASmbj() {
    val delegate = Recorder(NullPointerException("dialetto nullo"))
    val receiver = GuardedReceiver(delegate, ResponseGuard())

    receiver.handle(encrypted())

    assertThat(delegate.delivered).isEqualTo(0)
    assertThat(delegate.errors.single()).isInstanceOf(TransportException::class.java)
  }

  @Test
  fun r3_ancheUnErroreNellaChiusuraNonEsceDalThread() {
    val delegate = object : PacketReceiver<SMBPacketData<*>> {
      override fun handle(packet: SMBPacketData<*>) = throw IllegalStateException("smbj")

      override fun handleError(t: Throwable) = throw IllegalStateException("chiusura")
    }
    GuardedReceiver(delegate, ResponseGuard().apply { arm() }).handle(encrypted())
  }
}
