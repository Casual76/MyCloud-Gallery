package dev.pampa.mycloudgallery.core.common.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactedTest {

  private data class Accesso(val utente: String, val password: Redacted<String>)

  @Test
  fun toString_nonRivelaIlValore() {
    val password = Redacted("segreta-lunghissima")

    assertThat(password.toString()).isEqualTo(Redacted.PLACEHOLDER)
    assertThat("credenziali: $password").doesNotContain("segreta")
  }

  @Test
  fun value_restituisceIlValoreSoloSeLoChiediEsplicitamente() {
    assertThat(Redacted("abc").value).isEqualTo("abc")
  }

  @Test
  fun dentroUnaDataClass_ilValoreNonEsce() {
    assertThat(Accesso("mamma", Redacted("segreta")).toString()).doesNotContain("segreta")
  }
}
