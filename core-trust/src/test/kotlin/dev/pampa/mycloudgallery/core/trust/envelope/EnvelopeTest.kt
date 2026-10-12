package dev.pampa.mycloudgallery.core.trust.envelope

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class EnvelopeTest {

  private val signer = newSigner()
  private val payload = jsonObject("family" to "x".json(), "version" to 3.json())
  private val sealed = Envelope.seal(DocType.LEASE, payload, signer)

  private fun reason(bytes: ByteArray, type: DocType = DocType.LEASE): RejectReason =
    assertThrows(TrustViolation::class.java) { Envelope.open(bytes, type) }.reason

  @Test
  fun firmaEVerificaFannoIlGiro() {
    val opened = Envelope.open(sealed, DocType.LEASE)

    assertThat(opened.signer).isEqualTo(signer.publicKey)
    assertThat(opened.payloadJson()).isEqualTo(payload)
    assertThat(opened.contentHash).hasLength(52)
  }

  @Test
  fun ogniByteAlteratoRendeLaFirmaNonValida() {
    // Payload, lunghezza dichiarata, tipo, versione, chiave: nessun byte si cambia impunemente.
    for (index in listOf(0, 4, 5, 10, 96, 100, Envelope.HEADER_BYTES, Envelope.HEADER_BYTES + 5, sealed.size - 1)) {
      val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
      assertThrows("byte $index", TrustViolation::class.java) { Envelope.open(tampered, DocType.LEASE) }
    }
    val payloadByte = sealed.copyOf().also { it[Envelope.HEADER_BYTES + 3] = (it[Envelope.HEADER_BYTES + 3] + 1).toByte() }
    assertThat(reason(payloadByte)).isEqualTo(RejectReason.BAD_SIGNATURE)
  }

  @Test
  fun firmaDiUnAltraChiaveConLaChiaveOriginaleNellIntestazione() {
    val other = Envelope.seal(DocType.LEASE, payload, newSigner())
    val forged = sealed.copyOfRange(0, Envelope.HEADER_BYTES) + other.copyOfRange(Envelope.HEADER_BYTES, other.size)

    assertThat(reason(forged)).isEqualTo(RejectReason.BAD_SIGNATURE)
  }

  @Test
  fun tipoDiversoDaQuelloAttesoRifiutato() {
    assertThat(reason(sealed, DocType.FAMILY_REGISTRY)).isEqualTo(RejectReason.WRONG_TYPE)
    val unknownType = sealed.copyOf().also { it[5] = 99 }
    assertThat(reason(unknownType)).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun formatoPiuNuovoChiedeLAggiornamento() {
    val newer = sealed.copyOf().also { it[4] = 2 }
    val zero = sealed.copyOf().also { it[4] = 0 }

    assertThat(reason(newer)).isEqualTo(RejectReason.NEWER_FORMAT)
    assertThat(reason(zero)).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun troncatiConCodaOVuotiRifiutati() {
    assertThat(reason(ByteArray(0))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(sealed.copyOf(sealed.size - 1))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(sealed + byteArrayOf(0))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(sealed.copyOf(Envelope.HEADER_BYTES))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason("not a signed file at all, just text".repeat(4).toByteArray())).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun lunghezzaDichiarataEnormeNonFaAllocareNiente() {
    val lying = sealed.copyOf().also { ByteBuffer.wrap(it).putInt(97, Int.MAX_VALUE) }
    val negative = sealed.copyOf().also { ByteBuffer.wrap(it).putInt(97, -1) }

    assertThat(reason(lying)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason(negative)).isEqualTo(RejectReason.TOO_LARGE)
  }

  @Test
  fun fileOltreIlTettoDelTipoRifiutatoPrimaDiLeggerlo() {
    val huge = ByteArray(Envelope.maxFileBytes(DocType.LEASE) + 1)

    assertThat(reason(huge)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(Envelope.maxFileBytes(DocType.LEASE)).isLessThan(TrustLimits.MAX_LEASE_PAYLOAD + 200)
  }

  @Test
  fun lunghezzaDellaFirmaFuoriIntervalloRifiutata() {
    val at = Envelope.HEADER_BYTES + ByteBuffer.wrap(sealed).getInt(97)
    val zero = sealed.copyOf().also { it[at] = 0 }
    val huge = sealed.copyOf().also { it[at] = 0xFF.toByte() }

    assertThat(reason(zero)).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(huge)).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun chiaviDiAltreCurveOCompresseRifiutate() {
    val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair().public.encoded
    assertThrows(TrustViolation::class.java) { EcPublicKey.fromSpki(p384) }

    val wrongPrefix = signer.publicKey.encoded.also { it[26] = 0x02 } // punto "compresso" dichiarato
    assertThrows(TrustViolation::class.java) { EcPublicKey.fromSpki(wrongPrefix) }

    val withWrongKey = sealed.copyOf().also { it[6 + 26] = 0x03 }
    assertThat(reason(withWrongKey)).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun payloadNonCanonicoRifiutatoAncheSeFirmato() {
    // Un dispositivo approvato ma compromesso puo' firmare qualsiasi byte: la forma canonica si controlla comunque.
    val nonCanonical = """{"version":3,"family":"x"}""".toByteArray()
    val bytes = sealRaw(nonCanonical)
    val opened = Envelope.open(bytes, DocType.LEASE)

    assertThat(assertThrows(TrustViolation::class.java) { opened.payloadJson() }.reason).isEqualTo(RejectReason.NOT_CANONICAL)
  }

  /** Busta con payload arbitrario (non canonico), firmata correttamente. */
  private fun sealRaw(body: ByteArray): ByteArray {
    val header = ByteBuffer.allocate(Envelope.HEADER_BYTES + body.size)
      .put("MCGS".toByteArray()).put(1).put(DocType.LEASE.code.toByte()).put(signer.publicKey.encoded).putInt(body.size).put(body).array()
    val signature = signer.sign("mycloudgallery/signed\u0000".toByteArray() + header)
    return header + byteArrayOf(signature.size.toByte()) + signature
  }
}
