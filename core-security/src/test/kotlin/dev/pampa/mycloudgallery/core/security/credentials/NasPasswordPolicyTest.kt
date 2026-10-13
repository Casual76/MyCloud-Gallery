package dev.pampa.mycloudgallery.core.security.credentials

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.credentials.NasPasswordPolicy.Weakness
import java.security.SecureRandom
import org.junit.Test

/** Revisione 2, F1: la soglia della password del NAS e la password proposta. */
class NasPasswordPolicyTest {

  private fun check(password: String, user: String? = "mamma") = NasPasswordPolicy.check(user, password.toCharArray())

  @Test
  fun lePasswordDeboliSiRifiutanoConIlMotivo() {
    assertThat(check("")).isEqualTo(Weakness.TOO_SHORT)
    assertThat(check("Mario1975!")).isEqualTo(Weakness.TOO_SHORT)
    assertThat(check("Mamma-Foto-2026")).isEqualTo(Weakness.CONTAINS_USER)
    assertThat(check("Password-2026!")).isEqualTo(Weakness.PREDICTABLE)
    assertThat(check("Gatto-123456-x")).isEqualTo(Weakness.PREDICTABLE)
    assertThat(check("Qwerty-Gatto-7")).isEqualTo(Weakness.PREDICTABLE)
    assertThat(check("Fritz!Box-casa-7")).isEqualTo(Weakness.PREDICTABLE)
    assertThat(check("Gattoooo-Nero7")).isEqualTo(Weakness.PREDICTABLE)
    assertThat(check("abababababab")).isEqualTo(Weakness.PREDICTABLE)
    // 12-15 caratteri con due soli tipi: troppo semplice.
    assertThat(check("gattoneroxyz")).isEqualTo(Weakness.TOO_SIMPLE)
    assertThat(check("gatto-nero-xy")).isEqualTo(Weakness.TOO_SIMPLE)
  }

  @Test
  fun lePasswordRagionevoliPassano() {
    assertThat(check("Gatto-Nero-7")).isNull()
    assertThat(check("il-gatto-dorme-sul-divano")).isNull()
    assertThat(check("lunedi pioveva forte")).isNull()
    assertThat(check("Pässwörd-€-日本-sicura")).isNull()
    // Nome e sequenze ovvie non contano, ma il resto basta.
    assertThat(check("una-password-per-le-foto-7")).isNull()
    assertThat(check("mamma-ama-le-foto-del-mare")).isNull()
    // Senza un account noto il controllo del nome si salta.
    assertThat(check("Mamma-Foto-2026", user = null)).isNull()
    // Un nome di due lettere non conta (troppe password lo conterrebbero per caso).
    assertThat(check("Gatto-Nero-7", user = "at")).isNull()
  }

  @Test
  fun laPasswordPropostaPassaSempreEDiversaOgniVolta() {
    val random = SecureRandom()
    val seen = HashSet<String>()
    repeat(500) {
      val generated = NasPasswordPolicy.generate(random)
      assertThat(generated).hasLength(NasPasswordPolicy.GENERATED_CHARS)
      assertThat(NasPasswordPolicy.check("mamma", generated)).isNull()
      assertThat(String(generated).all { it in "abcdefghjkmnpqrstuvwxyz23456789" }).isTrue()
      seen += String(generated)
    }
    assertThat(seen).hasSize(500)
  }
}
