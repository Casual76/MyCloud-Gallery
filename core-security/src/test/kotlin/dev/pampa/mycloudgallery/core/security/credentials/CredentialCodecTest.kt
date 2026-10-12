package dev.pampa.mycloudgallery.core.security.credentials

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialCodecTest {

  private fun credentials(user: String = "mamma", password: String = "pw-molto-lunga-e-unica") =
    NasCredentials(user, password.toCharArray())

  @Test
  fun andataERitorno_conCaratteriNonAscii() {
    NasCredentials("zoë", "pässwörd-€-日本".toCharArray()).use { original ->
      val bytes = CredentialCodec.encode(original)
      CredentialCodec.decode(bytes).use { decoded ->
        assertThat(decoded.username).isEqualTo("zoë")
        assertThat(String(decoded.password.value)).isEqualTo("pässwörd-€-日本")
        assertThat(decoded).isEqualTo(original)
      }
    }
  }

  @Test
  fun toString_nonMostraNeUtenteNePassword() {
    credentials(user = "utente-segreto", password = "password-segreta").use {
      assertThat(it.toString()).doesNotContain("utente-segreto")
      assertThat(it.toString()).doesNotContain("password-segreta")
      assertThat(it.password.toString()).doesNotContain("password-segreta")
    }
  }

  @Test
  fun close_azzeraLaPassword() {
    val c = credentials()
    val array = c.password.value
    c.close()
    assertThat(array.all { it == '\u0000' }).isTrue()
  }

  @Test
  fun ilCostruttoreTieneUnaCopia_quindiIlChiamantePuoAzzerareIlSuoArray() {
    val mine = "password-vera".toCharArray()
    NasCredentials("u", mine).use { c ->
      mine.fill('x')
      assertThat(String(c.password.value)).isEqualTo("password-vera")
    }
  }

  @Test
  fun rifiutaValoriVuotiOTroppoLunghi_senzaMostrarliNelMessaggio() {
    assertThrows(IllegalArgumentException::class.java) { NasCredentials("", "x".toCharArray()) }
    assertThrows(IllegalArgumentException::class.java) { NasCredentials("u", CharArray(0)) }
    val tooLongUser = assertThrows(IllegalArgumentException::class.java) {
      NasCredentials("u".repeat(CredentialCodec.MAX_USERNAME_BYTES + 1), "x".toCharArray())
    }
    assertThat(tooLongUser.message).doesNotContain("uuuu")
    val tooLongPassword = assertThrows(IllegalArgumentException::class.java) {
      NasCredentials("u", "p".repeat(CredentialCodec.MAX_PASSWORD_BYTES + 1).toCharArray())
    }
    assertThat(tooLongPassword.message).doesNotContain("pppp")
  }

  @Test
  fun alLimiteEsatto_vaBene() {
    NasCredentials("u".repeat(CredentialCodec.MAX_USERNAME_BYTES), "p".repeat(CredentialCodec.MAX_PASSWORD_BYTES).toCharArray())
      .use { CredentialCodec.decode(CredentialCodec.encode(it)).close() }
  }

  @Test
  fun lettura_rifiutaBlocchiMalformati() {
    val good = credentials().use { CredentialCodec.encode(it) }

    // versione sconosciuta
    assertThrows(CredentialFormatException::class.java) { CredentialCodec.decode(good.copyOf().also { it[0] = 9 }) }
    // vuoto e troncato in ogni punto
    assertThrows(CredentialFormatException::class.java) { CredentialCodec.decode(ByteArray(0)) }
    for (cut in 1 until good.size) {
      assertThrows("troncato a $cut", CredentialFormatException::class.java) { CredentialCodec.decode(good.copyOf(cut)) }
    }
    // byte in piu'
    assertThrows(CredentialFormatException::class.java) { CredentialCodec.decode(good + byteArrayOf(0)) }
    // lunghezza utente zero
    assertThrows(CredentialFormatException::class.java) { CredentialCodec.decode(byteArrayOf(1, 0, 0, 0, 1, 'x'.code.toByte())) }
    // lunghezza utente fuori dal blocco
    assertThrows(CredentialFormatException::class.java) { CredentialCodec.decode(byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 'x'.code.toByte())) }
    // UTF-8 non valido nella password
    val invalidUtf8 = byteArrayOf(1, 0, 1, 'u'.code.toByte(), 0, 2, 0xC3.toByte(), 0x28)
    assertThrows(CredentialFormatException::class.java) { CredentialCodec.decode(invalidUtf8) }
  }
}
