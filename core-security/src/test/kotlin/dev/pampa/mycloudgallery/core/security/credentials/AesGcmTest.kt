package dev.pampa.mycloudgallery.core.security.credentials

import com.google.common.truth.Truth.assertThat
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmTest {

  private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  private val aad = "voce-a".toByteArray()
  private val plaintext = "segreto-molto-segreto".toByteArray()

  @Test
  fun andataERitorno() {
    val key = newKey()
    val sealed = AesGcm.seal(key, plaintext, aad)
    assertThat(AesGcm.open(key, sealed, aad)).isEqualTo(plaintext)
  }

  @Test
  fun formato_versioneIvETag() {
    val sealed = AesGcm.seal(newKey(), plaintext, aad)
    assertThat(sealed[0]).isEqualTo(AesGcm.VERSION)
    assertThat(sealed.size).isEqualTo(1 + AesGcm.IV_BYTES + plaintext.size + AesGcm.TAG_BITS / 8)
  }

  @Test
  fun ilTestoInChiaroNonCompareNelBloccoCifrato() {
    val sealed = AesGcm.seal(newKey(), plaintext, aad)
    assertThat(String(sealed, Charsets.ISO_8859_1)).doesNotContain("segreto")
  }

  @Test
  fun dueCifrature_dannoIvDiversi() {
    val key = newKey()
    val ivs = (1..50).map { AesGcm.seal(key, plaintext, aad).copyOfRange(1, 1 + AesGcm.IV_BYTES).toList() }.toSet()
    assertThat(ivs).hasSize(50)
  }

  @Test
  fun manomissione_inOgniByte_vieneRifiutata() {
    val key = newKey()
    val sealed = AesGcm.seal(key, plaintext, aad)
    // dal primo byte dell'IV all'ultimo del tag (il byte 0 e' la versione, provato a parte)
    for (index in 1 until sealed.size) {
      val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
      assertThrows("byte $index", AEADBadTagException::class.java) { AesGcm.open(key, tampered, aad) }
    }
  }

  @Test
  fun altraChiave_oAltriDatiAssociati_nonSiApre() {
    val sealed = AesGcm.seal(newKey(), plaintext, aad)
    assertThrows(AEADBadTagException::class.java) { AesGcm.open(newKey(), sealed, aad) }
    val key = newKey()
    val sealedForA = AesGcm.seal(key, plaintext, "voce-a".toByteArray())
    assertThrows(AEADBadTagException::class.java) { AesGcm.open(key, sealedForA, "voce-b".toByteArray()) }
  }

  @Test
  fun formatiSbagliati_sonoErroriDiFormato() {
    val key = newKey()
    val sealed = AesGcm.seal(key, plaintext, aad)
    assertThrows(SealedFormatException::class.java) { AesGcm.open(key, ByteArray(0), aad) }
    assertThrows(SealedFormatException::class.java) { AesGcm.open(key, sealed.copyOf(10), aad) }
    assertThrows(SealedFormatException::class.java) { AesGcm.open(key, sealed.copyOf().also { it[0] = 2 }, aad) }
  }
}
